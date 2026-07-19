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
import rotp.model.colony.Colony;
import rotp.model.empires.Empire;
import rotp.model.galaxy.Galaxy;
import rotp.model.galaxy.ShipFleet;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.model.game.IGameOptions;
import rotp.model.game.MOO1GameOptions;
import rotp.model.ships.ShipDesignLab;
import rotp.model.tech.TechCategory;
import rotp.model.tech.TechTree;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.Protocol;

/**
 * Multiplayer server: we-go turns over an authoritative headless game.
 *
 * Orders arrive as JSON commands, are validated against the sender's
 * empire, and mutate the authoritative model between turns. When every
 * player is ready, the turn resolves and fresh per-empire views go out.
 *
 * The game runs with autoplay enabled so mid-turn interactive events
 * auto-resolve via each empire's AI; players' empires are flagged
 * remoteHuman so the AI never overwrites their strategic orders
 * (see Empire.decidedByAI).
 */
public class GameServer extends WebSocketServer {
    private final int humanSlots;
    private final Map<WebSocket, Player> players = new LinkedHashMap<>();
    /** serializes all game-state access (commands vs turn processing) */
    private final Object gameLock = new Object();
    private volatile boolean gameStarted = false;
    private volatile boolean turnRunning = false;

    private static class Player {
        String name;
        int empireId = -1;
        boolean ready = false;
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
            // don't leave the turn blocked on a departed player
            maybeRunTurn();
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
        else if (msg instanceof Messages.Ready)
            handleReady(conn, (Messages.Ready) msg);
        else if (msg instanceof Messages.SetColonyAllocations)
            handleCommand(conn, "setColonyAlloc", (Messages.SetColonyAllocations) msg);
        else if (msg instanceof Messages.SetTechAllocations)
            handleCommand(conn, "setTechAlloc", (Messages.SetTechAllocations) msg);
        else if (msg instanceof Messages.DeployFleet)
            handleCommand(conn, "deployFleet", (Messages.DeployFleet) msg);
        else
            send(conn, error("Unexpected message"));
    }

    // ---- lobby ----

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
        // interactive mid-turn events auto-resolve via each empire's AI
        options.selectedAutoplayOption(IGameOptions.AUTOPLAY_AI_BASE);
        if (options.selectedNumberOpponents() < humanSlots-1)
            options.selectedNumberOpponents(humanSlots-1);
        GameSession.instance().startGame(options);

        synchronized (this) {
            for (Player p : players.values()) {
                Empire emp = galaxy().empire(p.empireId);
                if (emp != null)
                    emp.makeRemoteHuman();
            }
        }
        gameStarted = true;
        System.out.println("[server] game started, turn "+galaxy().currentTurn());

        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Messages.GameStarted gs = new Messages.GameStarted();
                gs.empireId = e.getValue().empireId;
                send(e.getKey(), Protocol.encode(gs));
            }
        }
        broadcastViews();
        broadcastTurnStatus("Awaiting orders");
    }

    // ---- we-go readiness ----

    private void handleReady(WebSocket conn, Messages.Ready msg) {
        if (!gameStarted) {
            send(conn, error("Game not started"));
            return;
        }
        synchronized (this) {
            Player p = players.get(conn);
            if (p == null)
                return;
            p.ready = msg.ready;
        }
        broadcastTurnStatus("Awaiting orders");
        maybeRunTurn();
    }

    private void maybeRunTurn() {
        synchronized (this) {
            if (!gameStarted || turnRunning || players.isEmpty())
                return;
            for (Player p : players.values())
                if (!p.ready)
                    return;
            turnRunning = true;
        }
        Thread t = new Thread(this::runTurn, "rotp-mp-turn");
        t.start();
    }

    private void runTurn() {
        try {
            GameSession session = GameSession.instance();
            broadcastTurnStatus("Resolving turn");
            synchronized (gameLock) {
                session.nextTurn();
                // nextTurn() spawns the turn thread; wait for it to finish
                while (session.performingTurn())
                    sleep(100);
            }
            System.out.println("[server] turn complete: "+galaxy().currentTurn());
            synchronized (this) {
                for (Player p : players.values())
                    p.ready = false;
            }
            broadcastViews();
            broadcastTurnStatus("Awaiting orders");
        }
        finally {
            turnRunning = false;
        }
    }

    // ---- orders ----

    private void handleCommand(WebSocket conn, String name, Object cmd) {
        Player p;
        synchronized (this) {
            p = players.get(conn);
        }
        if (p == null || !gameStarted) {
            send(conn, result(name, false, "Game not started"));
            return;
        }
        if (turnRunning) {
            send(conn, result(name, false, "Turn is resolving; orders locked"));
            return;
        }
        Empire emp = galaxy().empire(p.empireId);
        if (emp == null || emp.extinct()) {
            send(conn, result(name, false, "Empire no longer exists"));
            return;
        }
        String err;
        synchronized (gameLock) {
            if (cmd instanceof Messages.SetColonyAllocations)
                err = applyColonyAllocations(emp, (Messages.SetColonyAllocations) cmd);
            else if (cmd instanceof Messages.SetTechAllocations)
                err = applyTechAllocations(emp, (Messages.SetTechAllocations) cmd);
            else
                err = applyDeployFleet(emp, (Messages.DeployFleet) cmd);
        }
        send(conn, result(name, err == null, err == null ? "OK" : err));
        // successful orders change computed values (production, research);
        // give the sender a fresh view immediately
        if (err == null) {
            synchronized (gameLock) {
                send(conn, Protocol.encode(PlayerViews.build(emp)));
            }
        }
    }

    /** returns null on success, else a rejection reason */
    private String applyColonyAllocations(Empire emp, Messages.SetColonyAllocations cmd) {
        StarSystem sys = galaxy().system(cmd.systemId);
        if (sys == null)
            return "No such system";
        if ((sys.empire() != emp) || !sys.isColonized())
            return "Not your colony";
        int[] alloc = cmd.alloc;
        if ((alloc == null) || (alloc.length != Colony.NUM_CATS))
            return "Expected "+Colony.NUM_CATS+" allocation values";
        int sum = 0;
        for (int a : alloc) {
            if (a < 0)
                return "Allocations must be >= 0";
            sum += a;
        }
        if (sum != rotp.model.colony.ColonySpendingCategory.MAX_TICKS)
            return "Allocations must sum to "+rotp.model.colony.ColonySpendingCategory.MAX_TICKS;
        Colony col = sys.colony();
        for (int i = 0; i < Colony.NUM_CATS; i++) {
            if (col.locked(i) && (alloc[i] != col.allocation(i)))
                return "Category "+i+" is locked";
        }
        for (int i = 0; i < Colony.NUM_CATS; i++)
            col.allocation(i, alloc[i]);
        return null;
    }

    private String applyTechAllocations(Empire emp, Messages.SetTechAllocations cmd) {
        int[] alloc = cmd.alloc;
        if ((alloc == null) || (alloc.length != TechTree.NUM_CATEGORIES))
            return "Expected "+TechTree.NUM_CATEGORIES+" allocation values";
        int sum = 0;
        for (int a : alloc) {
            if ((a < 0) || (a > TechCategory.MAX_ALLOCATION_TICKS))
                return "Allocations must be 0-"+TechCategory.MAX_ALLOCATION_TICKS;
            sum += a;
        }
        if (sum > TechCategory.MAX_ALLOCATION_TICKS)
            return "Total allocation exceeds "+TechCategory.MAX_ALLOCATION_TICKS;
        for (int i = 0; i < TechTree.NUM_CATEGORIES; i++)
            emp.tech().category(i).allocation(alloc[i]);
        return null;
    }

    private String applyDeployFleet(Empire emp, Messages.DeployFleet cmd) {
        Galaxy gal = galaxy();
        if (gal.system(cmd.destSystemId) == null)
            return "No such destination system";
        ShipFleet fleet = gal.ships.orbitingFleet(emp.id, cmd.fromSystemId);
        if ((fleet == null) || !fleet.isActive())
            return "No orbiting fleet at that system";
        if (cmd.destSystemId == cmd.fromSystemId)
            return "Fleet is already there";
        if (!fleet.canSendTo(cmd.destSystemId))
            return "Destination out of range";
        int[] counts = cmd.counts;
        if ((counts == null) || (counts.length == 0)) {
            gal.ships.deployFleet(fleet, cmd.destSystemId);
            return null;
        }
        if (counts.length != ShipDesignLab.MAX_DESIGNS)
            return "Expected "+ShipDesignLab.MAX_DESIGNS+" ship counts";
        boolean any = false;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < 0)
                return "Ship counts must be >= 0";
            if (counts[i] > fleet.num(i))
                return "Not enough ships of design "+i;
            any |= counts[i] > 0;
        }
        if (!any)
            return "No ships selected";
        gal.ships.deploySubfleet(fleet, counts, cmd.destSystemId);
        return null;
    }

    // ---- outbound ----

    private void broadcastViews() {
        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Empire emp = galaxy().empire(e.getValue().empireId);
                if (emp != null) {
                    synchronized (gameLock) {
                        send(e.getKey(), Protocol.encode(PlayerViews.build(emp)));
                    }
                }
            }
        }
    }

    private void broadcastTurnStatus(String note) {
        Messages.TurnStatus ts = new Messages.TurnStatus();
        ts.processing = turnRunning;
        ts.turn = gameStarted ? galaxy().currentTurn() : 0;
        synchronized (this) {
            ts.totalPlayers = players.size();
            for (Player p : players.values())
                if (p.ready)
                    ts.readyCount++;
        }
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

    private static Galaxy galaxy() {
        return GameSession.instance().galaxy();
    }

    private static String error(String text) {
        Messages.Error err = new Messages.Error();
        err.text = text;
        return Protocol.encode(err);
    }

    private static String result(String command, boolean ok, String text) {
        Messages.CommandResult r = new Messages.CommandResult();
        r.command = command;
        r.ok = ok;
        r.text = text;
        return Protocol.encode(r);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
