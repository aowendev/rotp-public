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
package rotp.mp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import rotp.model.game.SessionUI;
import rotp.model.tech.TechLibrary;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.protocol.Protocol;
import rotp.mp.server.GameServer;
import rotp.mp.server.ServerUI;
import rotp.ui.UserPreferences;
import rotp.util.LanguageManager;

/**
 * Shared infrastructure for the multiplayer integration tests: one-time
 * headless engine bootstrap, an in-process {@link GameServer} on an
 * ephemeral port, and a scripted WebSocket {@link Client}.
 *
 * The engine uses process-wide singletons ({@code GameSession.instance},
 * {@code SessionUI}), so tests must run sequentially and each stands up its
 * own server/game (starting a game resets the singleton).
 */
public final class MpTestSupport {
    private MpTestSupport() { }

    private static boolean engineReady = false;

    /** Mirrors the data-loading half of ServerMain.run(); safe to call repeatedly. */
    public static synchronized void bootEngine() {
        if (engineReady)
            return;
        System.setProperty("java.awt.headless", "true");
        SessionUI.set(new ServerUI());
        UserPreferences.load();
        TechLibrary.current();
        LanguageManager.current().selectedLanguageName();
        engineReady = true;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
        catch (IOException e) {
            throw new RuntimeException("could not find a free port", e);
        }
    }

    /** Starts a fresh headless game server; caller must stop() it when done. */
    public static Server startServer(int humanSlots) {
        bootEngine();
        int port = freePort();
        GameServer server = new GameServer(port, humanSlots);
        server.start();  // non-blocking (WebSocketServer)
        return new Server(server, port);
    }

    public static final class Server {
        public final GameServer impl;
        public final int port;
        Server(GameServer impl, int port) { this.impl = impl; this.port = port; }
        public void stop() {
            try { impl.stop(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * A scripted client: connects, then exposes queues of decoded server
     * messages plus helpers for the common request/response patterns.
     */
    public static final class Client {
        public final String name;
        public final BlockingQueue<PlayerView> views = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.CommandResult> results = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.DiploReply> replies = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.DesignCatalog> catalogs = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.Lobby> lobbies = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.GameStarted> starts = new LinkedBlockingQueue<>();

        private final WebSocketClient ws;
        public volatile PlayerView lastView;

        public Client(int port, String name) throws Exception {
            this.name = name;
            this.ws = new WebSocketClient(URI.create("ws://localhost:"+port)) {
                @Override public void onOpen(ServerHandshake h) {
                    Messages.Hello hello = new Messages.Hello();
                    hello.version = Protocol.VERSION;
                    hello.playerName = Client.this.name;
                    send(Protocol.encode(hello));
                }
                @Override public void onMessage(String message) {
                    Object msg = Protocol.decode(message);
                    if (msg instanceof PlayerView) views.offer((PlayerView) msg);
                    else if (msg instanceof Messages.CommandResult) results.offer((Messages.CommandResult) msg);
                    else if (msg instanceof Messages.DiploReply) replies.offer((Messages.DiploReply) msg);
                    else if (msg instanceof Messages.DesignCatalog) catalogs.offer((Messages.DesignCatalog) msg);
                    else if (msg instanceof Messages.Lobby) lobbies.offer((Messages.Lobby) msg);
                    else if (msg instanceof Messages.GameStarted) starts.offer((Messages.GameStarted) msg);
                    else if (msg instanceof Messages.Error) System.out.println("["+Client.this.name+"] server error: "+((Messages.Error) msg).text);
                }
                @Override public void onClose(int code, String reason, boolean remote) { }
                @Override public void onError(Exception ex) { System.out.println("["+Client.this.name+"] ws error: "+ex); }
            };
            connectWithRetry();
        }

        private void connectWithRetry() throws Exception {
            for (int i = 0; i < 20; i++) {
                if (ws.connectBlocking(2, TimeUnit.SECONDS))
                    return;
                Thread.sleep(200);
            }
            throw new RuntimeException(name+": could not connect to server");
        }

        public void raw(Object msg) { ws.send(Protocol.encode(msg)); }

        public PlayerView awaitView() throws Exception {
            PlayerView v = views.poll(180, TimeUnit.SECONDS);
            if (v == null) throw new RuntimeException(name+": timed out waiting for view");
            return lastView = v;
        }

        /** drain any queued views, returning the most recent one seen (or the last known) */
        public PlayerView latestView() throws Exception {
            PlayerView v;
            while ((v = views.poll(500, TimeUnit.MILLISECONDS)) != null)
                lastView = v;
            return lastView;
        }

        /** send an order; on success also consume the server's fresh-view echo */
        public Messages.CommandResult order(Object cmd) throws Exception {
            raw(cmd);
            Messages.CommandResult r = results.poll(30, TimeUnit.SECONDS);
            if (r == null) throw new RuntimeException(name+": timed out waiting for command result");
            if (r.ok)
                awaitView();
            return r;
        }

        /** signal ready and wait for the post-turn view */
        public PlayerView ready() throws Exception {
            Messages.Ready rd = new Messages.Ready();
            rd.ready = true;
            raw(rd);
            return awaitView();
        }

        public Messages.DiploReply awaitReply() throws Exception {
            return replies.poll(30, TimeUnit.SECONDS);
        }

        public void close() throws Exception { ws.closeBlocking(); }
    }

    // ---- small view helpers ----

    public static PlayerView.SystemDto ownColony(PlayerView v) {
        for (PlayerView.SystemDto s : v.systems)
            if (s.colony != null)
                return s;
        return null;
    }

    public static PlayerView.SystemDto system(PlayerView v, int id) {
        for (PlayerView.SystemDto s : v.systems)
            if (s.id == id)
                return s;
        return null;
    }

    public static PlayerView.EmpireDto empire(PlayerView v, int id) {
        for (PlayerView.EmpireDto e : v.empires)
            if (e.id == id)
                return e;
        return null;
    }

    public static PlayerView.EmpireDto anyForeignEmpire(PlayerView v) {
        for (PlayerView.EmpireDto e : v.empires)
            if (e.id != v.empireId)
                return e;
        return null;
    }

    /** systems other than `from`, nearest first */
    public static List<PlayerView.SystemDto> nearestOthers(PlayerView v, PlayerView.SystemDto from) {
        List<PlayerView.SystemDto> out = new java.util.ArrayList<>(v.systems);
        out.removeIf(s -> s.id == from.id);
        out.sort((a, b) -> Float.compare(dist2(a, from), dist2(b, from)));
        return out;
    }

    public static float dist2(PlayerView.SystemDto s, PlayerView.SystemDto o) {
        float dx = s.x - o.x, dy = s.y - o.y;
        return dx*dx + dy*dy;
    }
}
