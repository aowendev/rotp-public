/*
 * Copyright 2015-2020 Ray Fowler
 *
 * Licensed under the GNU General Public License, Version 3 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rotp.mp.server;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.model.game.IGameOptions;
import rotp.model.game.MOO1GameOptions;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.Protocol;

/**
 * Walking-skeleton multiplayer server: lobby fills, a real game starts
 * headless, each client receives its empire's PlayerView, and any client
 * may advance the turn (per-player ready flags come later).
 *
 * V1 runs the authoritative game with autoplay enabled, so every empire's
 * decisions are AI-made during turn resolution; human orders arriving as
 * commands during the order phase are a later phase.
 */
public class GameServer extends WebSocketServer {
    private final int humanSlots;
    private final Map<WebSocket, Player> players = new LinkedHashMap<>();
    private volatile boolean gameStarted = false;
    private volatile boolean turnRunning = false;

    private static class Player {
        String name;
        int empireId = -1;
    }

    public GameServer(int port, int humanSlots) {
        super(new InetSocketAddress(port));
        this.humanSlots = humanSlots;
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        System.out.println("[server] listening on port "+getPort()+", waiting for "+humanSlots+" player(s)");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[server] connection from "+conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Player p;
        synchronized (this) {
            p = players.remove(conn);
        }
        if (p != null) {
            System.out.println("[server] "+p.name+" disconnected");
            broadcastLobby(p.name+" disconnected");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.out.println("[server] error: "+ex);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Object msg;
        try {
            msg = Protocol.decode(message);
        }
        catch (Exception e) {
            send(conn, error("Malformed message: "+e.getMessage()));
            return;
        }
        if (msg instanceof Messages.Hello)
            handleHello(conn, (Messages.Hello) msg);
        else if (msg instanceof Messages.NextTurn)
            handleNextTurn(conn);
        else
            send(conn, error("Unexpected message"));
    }

    private synchronized void handleHello(WebSocket conn, Messages.Hello hello) {
        if (hello.version != Protocol.VERSION) {
            send(conn, error("Protocol version mismatch: server="+Protocol.VERSION+" client="+hello.version));
            conn.close();
            return;
        }
        if (gameStarted || players.size() >= humanSlots) {
            send(conn, error("Game is full"));
            conn.close();
            return;
        }
        Player p = new Player();
        p.name = (hello.playerName == null || hello.playerName.isEmpty()) ? "Player" : hello.playerName;
        p.empireId = players.size();   // slot order for now
        players.put(conn, p);
        System.out.println("[server] "+p.name+" joined as empire "+p.empireId);
        broadcastLobby(p.name+" joined");

        if (players.size() == humanSlots) {
            Thread t = new Thread(this::startGame, "rotp-mp-start");
            t.start();
        }
    }

    private void startGame() {
        System.out.println("[server] all players present - generating galaxy");
        MOO1GameOptions options = new MOO1GameOptions();
        // v1: the server AI-manages all empires during resolution (we-go
        // orders from humans are applied before resolution in a later phase)
        options.selectedAutoplayOption(IGameOptions.AUTOPLAY_AI_BASE);
        GameSession.instance().startGame(options);
        gameStarted = true;
        System.out.println("[server] game started, turn "+GameSession.instance().galaxy().currentTurn());

        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Messages.GameStarted gs = new Messages.GameStarted();
                gs.empireId = e.getValue().empireId;
                send(e.getKey(), Protocol.encode(gs));
            }
        }
        broadcastViews();
    }

    private void handleNextTurn(WebSocket conn) {
        if (!gameStarted) {
            send(conn, error("Game not started"));
            return;
        }
        synchronized (this) {
            if (turnRunning)
                return;
            turnRunning = true;
        }
        Thread t = new Thread(this::runTurn, "rotp-mp-turn");
        t.start();
    }

    private void runTurn() {
        try {
            GameSession session = GameSession.instance();
            broadcastTurnStatus(true, session.galaxy().currentTurn(), "Resolving turn");
            session.nextTurn();
            // nextTurn() spawns the turn thread; wait for it to finish
            while (session.performingTurn())
                sleep(100);
            System.out.println("[server] turn complete: "+session.galaxy().currentTurn());
            broadcastTurnStatus(false, session.galaxy().currentTurn(), "Turn complete");
            broadcastViews();
        }
        finally {
            turnRunning = false;
        }
    }

    private void broadcastViews() {
        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Empire emp = GameSession.instance().galaxy().empire(e.getValue().empireId);
                if (emp != null)
                    send(e.getKey(), Protocol.encode(PlayerViews.build(emp)));
            }
        }
    }

    private void broadcastTurnStatus(boolean processing, int turn, String note) {
        Messages.TurnStatus ts = new Messages.TurnStatus();
        ts.processing = processing;
        ts.turn = turn;
        ts.note = note;
        broadcastAll(Protocol.encode(ts));
    }

    private synchronized void broadcastLobby(String message) {
        Messages.Lobby lobby = new Messages.Lobby();
        lobby.message = message;
        for (Player p : players.values()) {
            Messages.Slot slot = new Messages.Slot();
            slot.empireId = p.empireId;
            slot.playerName = p.name;
            slot.connected = true;
            lobby.slots.add(slot);
        }
        broadcastAll(Protocol.encode(lobby));
    }

    private synchronized void broadcastAll(String json) {
        for (WebSocket ws : players.keySet())
            send(ws, json);
    }

    private void send(WebSocket conn, String json) {
        try {
            if (conn.isOpen())
                conn.send(json);
        }
        catch (Exception e) {
            System.out.println("[server] send failed: "+e);
        }
    }

    private static String error(String text) {
        Messages.Error err = new Messages.Error();
        err.text = text;
        return Protocol.encode(err);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
