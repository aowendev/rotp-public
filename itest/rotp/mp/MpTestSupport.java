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
        // a tiny galaxy keeps empires close so tests reliably reach first
        // contact (a remote human's fleets don't auto-explore) and run fast
        GameServer server = new GameServer(port, humanSlots, rotp.model.game.IGameOptions.SIZE_TINY);
        server.start();  // non-blocking (WebSocketServer)
        waitUntilListening(port, 10_000);
        return new Server(server, port);
    }

    /** Starts a headless server that resumes a saved game (see the load= server arg). */
    public static Server startServerFromSave(String loadFile) {
        bootEngine();
        int port = freePort();
        GameServer server = new GameServer(port, 1, null, loadFile);
        server.start();  // non-blocking
        waitUntilListening(port, 10_000);
        return new Server(server, port);
    }

    /** block until the server is accepting TCP connections (start() is async) */
    private static void waitUntilListening(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress("localhost", port), 200);
                return;
            }
            catch (java.io.IOException notYet) {
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
        throw new RuntimeException("server never started listening on port " + port);
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
        public final BlockingQueue<Messages.Joined> joins = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.SizeOptions> sizeOptions = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.DifficultyOptions> difficultyOptions = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.ColonyPreview> colonyPreviews = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.TechTradeMenu> diploMenus = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.TechCounterOffer> counterOffers = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.Error> errors = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.Notifications> notifications = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.Prompts> prompts = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.GameOver> gameOvers = new LinkedBlockingQueue<>();
        public final BlockingQueue<Messages.TurnStatus> turnStatuses = new LinkedBlockingQueue<>();

        private final WebSocketClient ws;
        private final String sessionToken;
        public volatile PlayerView lastView;
        /** the token the server issued this connection (see Messages.Joined) */
        public volatile String issuedToken;

        public Client(int port, String name) throws Exception {
            this(port, name, null);
        }

        /** connect replaying a session token, as a reconnecting browser client does */
        public Client(int port, String name, String sessionToken) throws Exception {
            this.name = name;
            this.sessionToken = sessionToken;
            this.ws = new WebSocketClient(URI.create("ws://localhost:"+port)) {
                @Override public void onOpen(ServerHandshake h) {
                    Messages.Hello hello = new Messages.Hello();
                    hello.version = Protocol.VERSION;
                    hello.playerName = Client.this.name;
                    hello.sessionToken = Client.this.sessionToken;
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
                    else if (msg instanceof Messages.Joined) {
                        issuedToken = ((Messages.Joined) msg).sessionToken;
                        joins.offer((Messages.Joined) msg);
                    }
                    else if (msg instanceof Messages.SizeOptions) sizeOptions.offer((Messages.SizeOptions) msg);
                    else if (msg instanceof Messages.DifficultyOptions) difficultyOptions.offer((Messages.DifficultyOptions) msg);
                    else if (msg instanceof Messages.ColonyPreview) colonyPreviews.offer((Messages.ColonyPreview) msg);
                    else if (msg instanceof Messages.TechTradeMenu) diploMenus.offer((Messages.TechTradeMenu) msg);
                    else if (msg instanceof Messages.TechCounterOffer) counterOffers.offer((Messages.TechCounterOffer) msg);
                    else if (msg instanceof Messages.Notifications) notifications.offer((Messages.Notifications) msg);
                    else if (msg instanceof Messages.Prompts) prompts.offer((Messages.Prompts) msg);
                    else if (msg instanceof Messages.GameOver) gameOvers.offer((Messages.GameOver) msg);
                    else if (msg instanceof Messages.TurnStatus) turnStatuses.offer((Messages.TurnStatus) msg);
                    else if (msg instanceof Messages.Error) errors.offer((Messages.Error) msg);
                }
                @Override public void onClose(int code, String reason, boolean remote) { }
                @Override public void onError(Exception ex) { System.out.println("["+Client.this.name+"] ws error: "+ex); }
            };
            connectWithRetry();
        }

        private void connectWithRetry() throws Exception {
            // the server is already listening (startServer waited), and a
            // WebSocketClient can only be connected once, so connect a single time
            if (!ws.connectBlocking(10, TimeUnit.SECONDS))
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

        public Messages.Joined awaitJoined() throws Exception {
            return joins.poll(10, TimeUnit.SECONDS);
        }

        public Messages.Error awaitError() throws Exception {
            return errors.poll(10, TimeUnit.SECONDS);
        }

        public Messages.GameOver awaitGameOver() throws Exception {
            return gameOvers.poll(180, TimeUnit.SECONDS);
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

    /**
     * Keep this empire's fleets exploring: send every idle orbiting fleet
     * onward to the nearest not-yet-visited system in range. Since a remote
     * human's fleets don't auto-explore (the AI is off), tests must drive
     * scouting themselves to reach neighbours and make first contact.
     * Returns the (possibly updated) latest view.
     */
    public static PlayerView explore(Client c, PlayerView v, java.util.Set<Integer> visited) throws Exception {
        for (PlayerView.FleetDto f : new java.util.ArrayList<>(v.fleets)) {
            if (f.atSystemId < 0)
                continue;
            visited.add(f.atSystemId);
            for (PlayerView.SystemDto cand : nearestOthers(v, systemOrSelf(v, f.atSystemId))) {
                if (visited.contains(cand.id))
                    continue;
                rotp.mp.protocol.Messages.DeployFleet d = new rotp.mp.protocol.Messages.DeployFleet();
                d.fromSystemId = f.atSystemId;
                d.destSystemId = cand.id;
                if (c.order(d).ok) { visited.add(cand.id); break; }
            }
        }
        return c.latestView();
    }

    private static PlayerView.SystemDto systemOrSelf(PlayerView v, int id) {
        PlayerView.SystemDto s = system(v, id);
        if (s != null)
            return s;
        PlayerView.SystemDto stub = new PlayerView.SystemDto();
        stub.id = id;
        return stub;
    }

    public static <T> java.util.List<T> drain(BlockingQueue<T> q) {
        java.util.List<T> out = new java.util.ArrayList<>();
        q.drainTo(out);
        return out;
    }

    /** every queued notification, flattened — drain once when a test asserts on
     * more than one category from the same turn */
    public static java.util.List<Messages.Notification> allNotifications(Client c) {
        java.util.List<Messages.Notification> out = new java.util.ArrayList<>();
        for (Messages.Notifications ns : drain(c.notifications))
            out.addAll(ns.items);
        return out;
    }

    /** true if the given already-drained notifications carry the category */
    public static boolean hasCategory(java.util.List<Messages.Notification> notes, String category) {
        for (Messages.Notification n : notes)
            if (category.equals(n.category))
                return true;
        return false;
    }

    /** true if any drained Notifications message carries an item of the given category */
    public static boolean sawNotification(Client c, String category) {
        for (Messages.Notifications ns : drain(c.notifications))
            for (Messages.Notification n : ns.items)
                if (category.equals(n.category))
                    return true;
        return false;
    }

    /** the first drained notification of the given category, or null if none */
    public static Messages.Notification firstNotification(Client c, String category) {
        for (Messages.Notifications ns : drain(c.notifications))
            for (Messages.Notification n : ns.items)
                if (category.equals(n.category))
                    return n;
        return null;
    }

    /** the first drained Prompt of the given type, or null if none has arrived */
    public static Messages.Prompt firstPrompt(Client c, String type) {
        for (Messages.Prompts ps : drain(c.prompts))
            for (Messages.Prompt p : ps.items)
                if (type.equals(p.type))
                    return p;
        return null;
    }
}
