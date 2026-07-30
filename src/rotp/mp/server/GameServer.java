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
import java.util.List;
import java.util.Map;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import rotp.model.colony.Colony;
import rotp.model.empires.Empire;
import rotp.model.empires.EmpireView;
import rotp.model.galaxy.Galaxy;
import rotp.model.galaxy.ShipFleet;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.model.game.IGameOptions;
import rotp.model.game.MOO1GameOptions;
import rotp.model.ships.ShipDesign;
import rotp.model.ships.ShipDesignLab;
import rotp.model.tech.TechCategory;
import rotp.model.tech.TechTree;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.Protocol;
import rotp.ui.diplomacy.DiplomaticReply;

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
    /** IGameOptions.SIZE_* for the galaxy, or null to keep the ruleset default */
    private final String galaxySize;
    private final Map<WebSocket, Player> players = new LinkedHashMap<>();
    /** serializes all game-state access (commands vs turn processing) */
    private final Object gameLock = new Object();
    private final NotificationCenter notiCenter = new NotificationCenter();
    private WebSocket hostConn;   // first player to join; may start the game
    private volatile boolean starting = false;
    private volatile boolean gameStarted = false;
    private volatile boolean turnRunning = false;
    private volatile boolean gameEnded = false;   // a win/loss was reached and signalled

    private static class Player {
        String name;
        int empireId = -1;
        boolean ready = false;
        String raceId;   // race picked in the lobby (defaulted on join)
    }

    /**
     * The player-selectable races, mirroring MOO1GameOptions.startingRaceOptions().
     * Kept as a constant so the lobby can offer races before any game options
     * object exists.
     */
    private static final String[] STARTING_RACE_IDS = {
        "RACE_HUMAN", "RACE_ALKARI", "RACE_SILICOID", "RACE_MRRSHAN", "RACE_KLACKON",
        "RACE_MEKLAR", "RACE_PSILON", "RACE_DARLOK", "RACE_SAKKRA", "RACE_BULRATHI"
    };

    public GameServer(int port, int humanSlots) {
        this(port, humanSlots, null);
    }

    public GameServer(int port, int humanSlots, String galaxySize) {
        super(new InetSocketAddress(port));
        this.humanSlots = humanSlots;
        this.galaxySize = galaxySize;
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
        else if (msg instanceof Messages.SetResearchChoice)
            handleCommand(conn, "setResearchChoice", (Messages.SetResearchChoice) msg);
        else if (msg instanceof Messages.DeployFleet)
            handleCommand(conn, "deployFleet", (Messages.DeployFleet) msg);
        else if (msg instanceof Messages.SendTransports)
            handleCommand(conn, "sendTransports", (Messages.SendTransports) msg);
        else if (msg instanceof Messages.AbortTransports)
            handleCommand(conn, "abortTransports", (Messages.AbortTransports) msg);
        else if (msg instanceof Messages.Colonize)
            handleCommand(conn, "colonize", (Messages.Colonize) msg);
        else if (msg instanceof Messages.CreateDesign)
            handleCommand(conn, "createDesign", (Messages.CreateDesign) msg);
        else if (msg instanceof Messages.ScrapDesign)
            handleCommand(conn, "scrapDesign", (Messages.ScrapDesign) msg);
        else if (msg instanceof Messages.SetShipBuild)
            handleCommand(conn, "setShipBuild", (Messages.SetShipBuild) msg);
        else if (msg instanceof Messages.SetSpySpending)
            handleCommand(conn, "setSpySpending", (Messages.SetSpySpending) msg);
        else if (msg instanceof Messages.SetSpyMission)
            handleCommand(conn, "setSpyMission", (Messages.SetSpyMission) msg);
        else if (msg instanceof Messages.SetSecurity)
            handleCommand(conn, "setSecurity", (Messages.SetSecurity) msg);
        else if (msg instanceof Messages.DiploOffer)
            handleCommand(conn, "diploOffer", (Messages.DiploOffer) msg);
        else if (msg instanceof Messages.BreakTreaty)
            handleCommand(conn, "breakTreaty", (Messages.BreakTreaty) msg);
        else if (msg instanceof Messages.DeclareWar)
            handleCommand(conn, "declareWar", (Messages.DeclareWar) msg);
        else if (msg instanceof Messages.DesignCatalog)
            handleDesignCatalog(conn);
        else if (msg instanceof Messages.PickRace)
            handlePickRace(conn, (Messages.PickRace) msg);
        else if (msg instanceof Messages.StartGame)
            handleStartGame(conn, (Messages.StartGame) msg);
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
        p.raceId = firstFreeRace();    // a distinct race per player by default
        players.put(conn, p);
        if (hostConn == null)
            hostConn = conn;   // the first player to join is the host
        System.out.println("[server] "+p.name+" joined as empire "+p.empireId
            + (conn == hostConn ? " (host)" : "") + ", race "+p.raceId);

        Messages.Joined joined = new Messages.Joined();
        joined.empireId = p.empireId;
        joined.host = (conn == hostConn);
        send(conn, Protocol.encode(joined));
        send(conn, Protocol.encode(raceOptions()));
        broadcastLobby(p.name+" joined");

        // auto-start once every human slot is filled (ruleset default AI count)
        if (players.size() == humanSlots)
            beginStart(-1);
    }

    /** host asks to start now with the humans present, filling the rest with AI */
    private synchronized void handleStartGame(WebSocket conn, Messages.StartGame msg) {
        if (gameStarted || starting) {
            send(conn, error("Game already starting"));
            return;
        }
        if (conn != hostConn) {
            send(conn, error("Only the host can start the game"));
            return;
        }
        if (players.isEmpty()) {
            send(conn, error("No players present"));
            return;
        }
        beginStart(msg.aiOpponents);
    }

    /** a player picks a race in the lobby; rejected if another player already holds it */
    private synchronized void handlePickRace(WebSocket conn, Messages.PickRace msg) {
        if (gameStarted || starting) {
            send(conn, error("Game already starting; race is locked"));
            return;
        }
        Player p = players.get(conn);
        if (p == null)
            return;
        String raceId = msg.raceId;
        if ((raceId == null) || !isStartingRace(raceId)) {
            send(conn, error("Unknown race: "+raceId));
            send(conn, Protocol.encode(buildLobby(null)));   // resync the picker
            return;
        }
        for (Player other : players.values()) {
            if ((other != p) && raceId.equals(other.raceId)) {
                send(conn, error(other.name+" already chose "+raceId));
                send(conn, Protocol.encode(buildLobby(null)));   // resync the picker
                return;
            }
        }
        p.raceId = raceId;
        System.out.println("[server] "+p.name+" picked race "+raceId);
        broadcastLobby(p.name+" chose "+raceId);
    }

    private static boolean isStartingRace(String raceId) {
        for (String id : STARTING_RACE_IDS)
            if (id.equals(raceId))
                return true;
        return false;
    }

    /** first starting race not already held by a connected player (assumes callers hold the monitor) */
    private String firstFreeRace() {
        for (String id : STARTING_RACE_IDS) {
            boolean taken = false;
            for (Player p : players.values()) {
                if (id.equals(p.raceId)) {
                    taken = true;
                    break;
                }
            }
            if (!taken)
                return id;
        }
        return STARTING_RACE_IDS[0];   // more players than races: fall back (shouldn't happen)
    }

    private Messages.RaceOptions raceOptions() {
        Messages.RaceOptions opts = new Messages.RaceOptions();
        for (String id : STARTING_RACE_IDS) {
            rotp.model.empires.Race r = rotp.model.empires.Race.keyed(id);
            Messages.RaceInfo info = new Messages.RaceInfo();
            info.id = id;
            info.name = (r == null) ? id : r.setupName();
            info.description = (r == null) ? "" : r.description1;
            opts.races.add(info);
        }
        return opts;
    }

    /** guard so the game is generated exactly once, off the WebSocket thread */
    private synchronized void beginStart(int aiOverride) {
        if (starting || gameStarted)
            return;
        starting = true;
        Thread t = new Thread(() -> startGame(aiOverride), "rotp-mp-start");
        t.start();
    }

    private void startGame(int aiOverride) {
        System.out.println("[server] generating galaxy (humans=" + players.size()
            + ", aiOverride=" + aiOverride + ")");
        MOO1GameOptions options = new MOO1GameOptions();
        // interactive mid-turn events auto-resolve via each empire's AI
        options.selectedAutoplayOption(IGameOptions.AUTOPLAY_AI_BASE);
        if (galaxySize != null)
            options.selectedGalaxySize(galaxySize);

        int humans = players.size();
        int opponents;
        if (aiOverride >= 0)
            opponents = humans + aiOverride - 1;   // total empires = humans + aiOverride
        else
            opponents = Math.max(options.selectedNumberOpponents(), humans - 1);
        // at least one opponent, and no more than the galaxy allows
        opponents = Math.max(1, Math.min(opponents, options.maximumOpponentsOptions()));
        options.selectedNumberOpponents(opponents);
        System.out.println("[server] " + humans + " human(s) + " + opponents + " AI opponent(s)");

        // apply each human's lobby race pick. Empire 0 (host) is the "player";
        // the other humans occupy the first opponent slots. Any opponent slot we
        // don't set stays null, so GalaxyFactory fills it with a random unused
        // race, avoiding collisions with the races we pin here.
        synchronized (this) {
            for (Player p : players.values()) {
                if (p.raceId == null)
                    continue;
                if (p.empireId == 0) {
                    options.selectedPlayerRace(p.raceId);
                    // selectedPlayerRace() changes the race but not the player's
                    // homeworld/leader names, which the setup UI would normally
                    // refresh. Left stale they keep the initial default race's
                    // values (e.g. a Bulrathi homeworld still named "Kholdan").
                    // Clearing the homeworld name makes the galaxy factory name it
                    // from the chosen race; reset the leader to a race-appropriate one.
                    rotp.model.empires.Race r = rotp.model.empires.Race.keyed(p.raceId);
                    options.selectedHomeWorldName("");
                    if (r != null)
                        options.selectedLeaderName(r.randomLeaderName());
                }
                else if (p.empireId - 1 < options.selectedOpponentRaces().length)
                    options.selectedOpponentRace(p.empireId - 1, p.raceId);
            }
        }
        GameSession.instance().startGame(options);

        synchronized (this) {
            for (Player p : players.values()) {
                Empire emp = galaxy().empire(p.empireId);
                if (emp != null) {
                    emp.makeRemoteHuman();
                    synchronized (gameLock) {
                        // a remote human's research starts unallocated (all zero):
                        // the desktop equalizes it when the tech screen opens, and
                        // the AI that would set it is intentionally gated off. Give
                        // them a sensible 100%-allocated even split up front.
                        emp.tech().equalizeAllocations();
                        // ROTP auto-launches a new empire's scouts on turn 1; pull
                        // them back so the remote human keeps full manual control of
                        // the opening move (e.g. sending the colony ship instead).
                        recallStartingFleets(emp);
                        // baseline so turn-1 state isn't reported as "news"
                        notiCenter.seed(emp);
                    }
                }
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

    /**
     * Consolidate a new empire's not-yet-moved fleets back into orbit at the
     * homeworld. ROTP auto-dispatches the starting scouts during galaxy
     * generation (a single-player convenience); a remote human wants to make
     * the opening move themselves. Safe only at game start: no turn has
     * processed, so every fleet is still un-launched and sitting at home.
     */
    private void recallStartingFleets(Empire emp) {
        int home = emp.homeSysId();
        StarSystem homeSys = galaxy().system(home);
        ShipFleet homeFleet = galaxy().ships.orbitingFleet(emp.id, home);
        for (ShipFleet f : new java.util.ArrayList<>(galaxy().ships.allFleets(emp.id))) {
            if ((f == homeFleet) || f.launched() || !f.deployed())
                continue;   // leave already-moving fleets and the orbiting home fleet
            if (homeFleet == null) {
                f.arrive(homeSys, false);   // no orbiting fleet yet: this becomes it
                homeFleet = f;
                continue;
            }
            for (int i = 0; i < ShipDesignLab.MAX_DESIGNS; i++) {
                int n = f.num(i);
                if (n > 0) {
                    homeFleet.num(i, homeFleet.num(i) + n);
                    f.num(i, 0);
                }
            }
            galaxy().ships.deleteFleet(f);
        }
        emp.setVisibleShips(home);
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
            if (!gameStarted || turnRunning || gameEnded || players.isEmpty())
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
            broadcastNotifications();
            broadcastViews();
            checkGameOver();
        }
        finally {
            turnRunning = false;
        }
        // must run after turnRunning is cleared: this status reports processing,
        // and the client re-enables the Next Turn button only when processing is
        // false. Broadcasting it while turnRunning was still true left the button
        // stuck disabled on every turn after the first.
        broadcastTurnStatus(gameEnded ? "Game over" : "Awaiting orders");
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
            else if (cmd instanceof Messages.SetResearchChoice)
                err = applyResearchChoice(emp, (Messages.SetResearchChoice) cmd);
            else if (cmd instanceof Messages.DeployFleet)
                err = applyDeployFleet(emp, (Messages.DeployFleet) cmd);
            else if (cmd instanceof Messages.SendTransports)
                err = applySendTransports(emp, (Messages.SendTransports) cmd);
            else if (cmd instanceof Messages.AbortTransports)
                err = applyAbortTransports(emp, (Messages.AbortTransports) cmd);
            else if (cmd instanceof Messages.Colonize)
                err = applyColonize(emp, (Messages.Colonize) cmd);
            else if (cmd instanceof Messages.CreateDesign)
                err = applyCreateDesign(emp, (Messages.CreateDesign) cmd);
            else if (cmd instanceof Messages.ScrapDesign)
                err = applyScrapDesign(emp, (Messages.ScrapDesign) cmd);
            else if (cmd instanceof Messages.SetShipBuild)
                err = applySetShipBuild(emp, (Messages.SetShipBuild) cmd);
            else if (cmd instanceof Messages.SetSpySpending)
                err = applySetSpySpending(emp, (Messages.SetSpySpending) cmd);
            else if (cmd instanceof Messages.SetSpyMission)
                err = applySetSpyMission(emp, (Messages.SetSpyMission) cmd);
            else if (cmd instanceof Messages.SetSecurity)
                err = applySetSecurity(emp, (Messages.SetSecurity) cmd);
            else if (cmd instanceof Messages.DiploOffer)
                err = applyDiploOffer(conn, emp, (Messages.DiploOffer) cmd);
            else if (cmd instanceof Messages.BreakTreaty)
                err = applyBreakTreaty(emp, (Messages.BreakTreaty) cmd);
            else
                err = applyDeclareWar(emp, (Messages.DeclareWar) cmd);
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

    private String applyResearchChoice(Empire emp, Messages.SetResearchChoice cmd) {
        if ((cmd.category < 0) || (cmd.category >= TechTree.NUM_CATEGORIES))
            return "Category must be 0-" + (TechTree.NUM_CATEGORIES - 1);
        TechCategory cat = emp.tech().category(cmd.category);
        if ((cmd.techId == null) || !cat.techIdsAvailableForResearch().contains(cmd.techId))
            return "Not an available research choice for that category";
        rotp.model.tech.Tech t = rotp.model.tech.TechLibrary.current().tech(cmd.techId);
        if ((t == null) || !cat.currentTech(t))
            return "Could not set that research target";
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

    private String applySendTransports(Empire emp, Messages.SendTransports cmd) {
        StarSystem from = galaxy().system(cmd.fromSystemId);
        if ((from == null) || (from.empire() != emp) || !from.isColonized())
            return "Not your colony";
        Colony col = from.colony();
        StarSystem dest = galaxy().system(cmd.destSystemId);
        if (dest == null)
            return "No such destination system";
        if (cmd.destSystemId == cmd.fromSystemId)
            return "Cannot transport to the same system";
        if (!col.canTransport())
            return "Colony cannot send transports (rebellion)";
        if (!emp.canSendTransportsTo(dest))
            return "Destination must be a colonized, scouted system in range (and habitable for your race)";
        if (cmd.size <= 0)
            return "Transport size must be positive";
        if (cmd.size > col.maxTransportsAllowed())
            return "At most half the population ("+col.maxTransportsAllowed()+") can be sent";
        col.scheduleTransportsToSystem(dest, cmd.size);
        return null;
    }

    private String applyAbortTransports(Empire emp, Messages.AbortTransports cmd) {
        StarSystem from = galaxy().system(cmd.fromSystemId);
        if ((from == null) || (from.empire() != emp) || !from.isColonized())
            return "Not your colony";
        from.colony().clearTransport();
        return null;
    }

    private String applyColonize(Empire emp, Messages.Colonize cmd) {
        StarSystem sys = galaxy().system(cmd.systemId);
        if (sys == null)
            return "No such system";
        if (!emp.sv.isScouted(cmd.systemId))
            return "System not scouted";
        if (sys.isColonized())
            return "System is already colonized";
        ShipFleet fleet = galaxy().ships.orbitingFleet(emp.id, cmd.systemId);
        if ((fleet == null) || !fleet.isActive())
            return "No orbiting fleet at that system";
        if (!fleet.canColonizeSystem(sys))
            return "Fleet has no colony ship able to settle this planet";
        fleet.colonizeSystem(sys);
        return null;
    }

    private void handleDesignCatalog(WebSocket conn) {
        Player p;
        synchronized (this) {
            p = players.get(conn);
        }
        if (p == null || !gameStarted) {
            send(conn, error("Game not started"));
            return;
        }
        Empire emp = galaxy().empire(p.empireId);
        if (emp == null)
            return;
        Messages.DesignCatalog cat = new Messages.DesignCatalog();
        synchronized (gameLock) {
            ShipDesignLab lab = emp.shipLab();
            cat.hulls = java.util.Arrays.asList("SMALL", "MEDIUM", "LARGE", "HUGE");
            cat.computers = names(lab.computers());
            cat.shields = names(lab.shields());
            cat.ecms = names(lab.ecms());
            cat.armors = names(lab.armors());
            cat.engines = names(lab.engines());
            cat.maneuvers = names(lab.maneuvers());
            cat.weapons = names(lab.weapons());
            cat.specials = names(lab.specials());
        }
        send(conn, Protocol.encode(cat));
    }

    private static java.util.List<String> names(java.util.List<? extends rotp.model.ships.ShipComponent> comps) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (rotp.model.ships.ShipComponent c : comps)
            out.add(c.name());
        return out;
    }

    private String applyCreateDesign(Empire emp, Messages.CreateDesign cmd) {
        ShipDesignLab lab = emp.shipLab();
        if ((cmd.slot < 0) || (cmd.slot >= ShipDesignLab.MAX_DESIGNS))
            return "Design slot must be 0-"+(ShipDesignLab.MAX_DESIGNS-1);
        ShipDesign slotDesign = lab.design(cmd.slot);
        if (slotDesign.active())
            return "Slot in use; scrap the existing design first";
        if ((cmd.size < 0) || (cmd.size > ShipDesign.MAX_SIZE))
            return "Hull size must be 0-"+ShipDesign.MAX_SIZE;

        ShipDesign d = lab.newBlankDesign(cmd.size);
        String err = setComponents(lab, d, cmd);
        if (err != null)
            return err;
        if (d.availableSpace() < 0)
            return "Design exceeds available space by "+(int) Math.ceil(-d.availableSpace());

        slotDesign.copyFrom(d);
        slotDesign.active(true);
        slotDesign.name(((cmd.name == null) || cmd.name.trim().isEmpty()) ? null : cmd.name.trim());
        if (slotDesign.name() == null)
            lab.nameDesign(slotDesign);
        slotDesign.setIconKey();
        slotDesign.clearEmptyWeapons();
        return null;
    }

    private String setComponents(ShipDesignLab lab, ShipDesign d, Messages.CreateDesign cmd) {
        Integer idx;
        if ((idx = find(lab.computers(), cmd.computer)) == null)  return "Unknown computer: "+cmd.computer;
        d.computer(lab.computers().get(idx));
        if ((idx = find(lab.shields(), cmd.shield)) == null)      return "Unknown shield: "+cmd.shield;
        d.shield(lab.shields().get(idx));
        if ((idx = find(lab.ecms(), cmd.ecm)) == null)            return "Unknown ECM: "+cmd.ecm;
        d.ecm(lab.ecms().get(idx));
        if ((idx = find(lab.armors(), cmd.armor)) == null)        return "Unknown armor: "+cmd.armor;
        d.armor(lab.armors().get(idx));
        if ((idx = find(lab.engines(), cmd.engine)) == null)      return "Unknown engine: "+cmd.engine;
        d.engine(lab.engines().get(idx));
        if ((idx = find(lab.maneuvers(), cmd.maneuver)) == null)  return "Unknown maneuver: "+cmd.maneuver;
        d.maneuver(lab.maneuvers().get(idx));

        if (cmd.weapons != null) {
            if (cmd.weapons.length > ShipDesign.maxWeapons())
                return "At most "+ShipDesign.maxWeapons()+" weapon types";
            for (int i = 0; i < cmd.weapons.length; i++) {
                if ((idx = find(lab.weapons(), cmd.weapons[i])) == null)
                    return "Unknown weapon: "+cmd.weapons[i];
                int count = ((cmd.weaponCounts != null) && (i < cmd.weaponCounts.length))
                    ? cmd.weaponCounts[i] : 0;
                if ((count < 0) || (count > 99))
                    return "Weapon counts must be 0-99";
                d.weapon(i, lab.weapons().get(idx), count);
            }
        }
        if (cmd.specials != null) {
            if (cmd.specials.length > ShipDesign.maxSpecials())
                return "At most "+ShipDesign.maxSpecials()+" specials";
            for (int i = 0; i < cmd.specials.length; i++) {
                if ((idx = find(lab.specials(), cmd.specials[i])) == null)
                    return "Unknown special: "+cmd.specials[i];
                d.special(i, lab.specials().get(idx));
            }
        }
        return null;
    }

    /** null/empty name selects index 0 (the "none"/basic component) */
    private static Integer find(java.util.List<? extends rotp.model.ships.ShipComponent> comps, String name) {
        if ((name == null) || name.isEmpty())
            return 0;
        for (int i = 0; i < comps.size(); i++) {
            if (name.equalsIgnoreCase(comps.get(i).name()))
                return i;
        }
        return null;
    }

    private String applyScrapDesign(Empire emp, Messages.ScrapDesign cmd) {
        ShipDesignLab lab = emp.shipLab();
        if ((cmd.slot < 0) || (cmd.slot >= ShipDesignLab.MAX_DESIGNS))
            return "Design slot must be 0-"+(ShipDesignLab.MAX_DESIGNS-1);
        ShipDesign d = lab.design(cmd.slot);
        if (!d.active())
            return "No active design in that slot";
        if (activeDesignCount(lab) <= 1)
            return "Cannot scrap your last design";
        lab.scrapDesign(d);
        return null;
    }

    private static int activeDesignCount(ShipDesignLab lab) {
        int n = 0;
        for (int i = 0; i < ShipDesignLab.MAX_DESIGNS; i++)
            if (lab.design(i).active())
                n++;
        return n;
    }

    private String applySetShipBuild(Empire emp, Messages.SetShipBuild cmd) {
        StarSystem sys = galaxy().system(cmd.systemId);
        if ((sys == null) || (sys.empire() != emp) || !sys.isColonized())
            return "Not your colony";
        if ((cmd.designSlot < 0) || (cmd.designSlot >= ShipDesignLab.MAX_DESIGNS))
            return "Design slot must be 0-"+(ShipDesignLab.MAX_DESIGNS-1);
        ShipDesign d = emp.shipLab().design(cmd.designSlot);
        if (!d.active())
            return "No active design in that slot";
        if (cmd.buildLimit < 0)
            return "Build limit must be >= 0";
        sys.colony().shipyard().design(d);
        sys.colony().shipyard().buildLimit(cmd.buildLimit);
        return null;
    }

    /** resolves a contacted foreign empire's view, or null */
    private EmpireView contactedView(Empire emp, int otherEmpireId) {
        if (otherEmpireId == emp.id)
            return null;
        Empire other = galaxy().empire(otherEmpireId);
        if ((other == null) || other.extinct())
            return null;
        EmpireView ev = emp.viewForEmpire(other);
        if ((ev == null) || !ev.embassy().contact())
            return null;
        return ev;
    }

    private String applySetSpySpending(Empire emp, Messages.SetSpySpending cmd) {
        EmpireView ev = contactedView(emp, cmd.empireId);
        if (ev == null)
            return "No contact with that empire";
        if ((cmd.allocation < 0) || (cmd.allocation > 20))
            return "Spy spending must be 0-20 ticks";
        ev.spies().allocation(cmd.allocation);
        return null;
    }

    private String applySetSpyMission(Empire emp, Messages.SetSpyMission cmd) {
        EmpireView ev = contactedView(emp, cmd.empireId);
        if (ev == null)
            return "No contact with that empire";
        String m = (cmd.mission == null) ? "" : cmd.mission.toUpperCase();
        switch (m) {
            case "HIDE":      ev.spies().beginHide(); return null;
            case "ESPIONAGE": ev.spies().beginEspionage(); return null;
            case "SABOTAGE":  ev.spies().beginSabotage(); return null;
            default:          return "Mission must be HIDE, ESPIONAGE, or SABOTAGE";
        }
    }

    private String applySetSecurity(Empire emp, Messages.SetSecurity cmd) {
        if ((cmd.allocation < 0) || (cmd.allocation > 10))
            return "Security must be 0-10 ticks";
        emp.internalSecurity(cmd.allocation);
        return null;
    }

    private String applyDiploOffer(WebSocket conn, Empire emp, Messages.DiploOffer cmd) {
        EmpireView ev = contactedView(emp, cmd.empireId);
        if (ev == null)
            return "No contact with that empire";
        Empire target = galaxy().empire(cmd.empireId);
        String action = (cmd.action == null) ? "" : cmd.action.toUpperCase();
        DiplomaticReply reply;
        switch (action) {
            case "TRADE":
                if (ev.embassy().anyWar())
                    return "Cannot trade while at war";
                if ((cmd.tradeLevel <= 0) || (cmd.tradeLevel > ev.trade().maxLevel()))
                    return "Trade level must be 1-"+ev.trade().maxLevel();
                reply = target.diplomatAI().receiveOfferTrade(emp, cmd.tradeLevel);
                break;
            case "PEACE":
                if (!ev.embassy().anyWar())
                    return "Not at war";
                reply = target.diplomatAI().receiveOfferPeace(emp);
                break;
            case "PACT":
                if (ev.embassy().anyWar())
                    return "Cannot propose a pact while at war";
                if (ev.embassy().pact())
                    return "Pact already in effect";
                reply = target.diplomatAI().receiveOfferPact(emp);
                break;
            case "ALLIANCE":
                if (ev.embassy().anyWar())
                    return "Cannot propose an alliance while at war";
                if (ev.embassy().alliance())
                    return "Alliance already in effect";
                reply = target.diplomatAI().receiveOfferAlliance(emp);
                break;
            default:
                return "Action must be TRADE, PEACE, PACT, or ALLIANCE";
        }
        Messages.DiploReply dr = new Messages.DiploReply();
        dr.empireId = cmd.empireId;
        dr.action = action;
        dr.accepted = (reply != null) && reply.accepted();
        dr.text = (reply == null) ? "" : reply.text();
        send(conn, Protocol.encode(dr));
        return null;
    }

    private String applyBreakTreaty(Empire emp, Messages.BreakTreaty cmd) {
        EmpireView ev = contactedView(emp, cmd.empireId);
        if (ev == null)
            return "No contact with that empire";
        Empire target = galaxy().empire(cmd.empireId);
        String treaty = (cmd.treaty == null) ? "" : cmd.treaty.toUpperCase();
        switch (treaty) {
            case "TRADE":
                if (!ev.trade().active())
                    return "No trade route to break";
                target.diplomatAI().receiveBreakTrade(emp);
                return null;
            case "PACT":
                if (!ev.embassy().pact())
                    return "No pact to break";
                target.diplomatAI().receiveBreakPact(emp);
                return null;
            case "ALLIANCE":
                if (!ev.embassy().alliance())
                    return "No alliance to break";
                target.diplomatAI().receiveBreakAlliance(emp);
                return null;
            default:
                return "Treaty must be TRADE, PACT, or ALLIANCE";
        }
    }

    private String applyDeclareWar(Empire emp, Messages.DeclareWar cmd) {
        EmpireView ev = contactedView(emp, cmd.empireId);
        if (ev == null)
            return "No contact with that empire";
        if (ev.embassy().anyWar())
            return "Already at war";
        if (ev.embassy().alliance() || ev.embassy().unity())
            return "Break the alliance before declaring war";
        galaxy().empire(cmd.empireId).diplomatAI().receiveDeclareWar(emp);
        return null;
    }

    // ---- game over ----

    /**
     * After a turn, tell each empire whether it won, lost, or was destroyed.
     * The engine's global GameStatus is evaluated from empire 0's perspective
     * (the "player"), so its win/loss is authoritative for empire 0 and for the
     * solo game. Any other human's *defeat* is still detected per-empire via
     * extinction. Per-empire victory for multi-human games needs a deeper engine
     * change and is deferred.
     */
    private void checkGameOver() {
        if (gameEnded)
            return;
        rotp.model.game.GameStatus st;
        synchronized (gameLock) {
            st = GameSession.instance().status();
        }
        boolean over = !st.inProgress();
        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Empire emp = galaxy().empire(e.getValue().empireId);
                boolean extinct = (emp == null) || emp.extinct();
                Messages.GameOver go = null;
                if ((e.getValue().empireId == 0) && over)
                    go = gameOverForPlayer(st);
                else if (extinct)
                    go = gameOver(false, "DEFEATED", "Your empire has been destroyed.");
                else if (over)
                    go = gameOver(false, "GAME_OVER", "The game has ended.");
                if (go != null)
                    send(e.getKey(), Protocol.encode(go));
            }
        }
        if (over) {
            gameEnded = true;   // a win/loss was reached; stop resolving turns
            System.out.println("[server] game over ("
                + (st.won() ? "player won" : st.lost() ? "player lost" : "ended") + ")");
        }
    }

    private static Messages.GameOver gameOverForPlayer(rotp.model.game.GameStatus st) {
        if (st.wonMilitary())          return gameOver(true, "MILITARY", "Victory! You have conquered the galaxy.");
        if (st.wonMilitaryAlliance())  return gameOver(true, "MILITARY_ALLIANCE", "Victory! Your alliance rules the galaxy.");
        if (st.wonDiplomatic())        return gameOver(true, "DIPLOMATIC", "Victory! The Galactic Council has elected you.");
        if (st.wonCouncilAlliance())   return gameOver(true, "COUNCIL_ALLIANCE", "Victory! Your alliance holds the council.");
        if (st.wonNewRepublic())       return gameOver(true, "NEW_REPUBLIC", "Victory! The New Republic prevails.");
        if (st.wonRebellion())         return gameOver(true, "REBELLION", "Victory! Your rebellion has triumphed.");
        if (st.wonRebellionAlliance()) return gameOver(true, "REBELLION_ALLIANCE", "Victory! Your rebel alliance has triumphed.");
        if (st.lostNoColonies())       return gameOver(false, "NO_COLONIES", "Defeat. Your last colony is gone.");
        if (st.lostMilitary())         return gameOver(false, "MILITARY", "Defeat. Your empire has been conquered.");
        if (st.lostDiplomatic())       return gameOver(false, "DIPLOMATIC", "Defeat. The Galactic Council elected another leader.");
        if (st.lostOverthrown())       return gameOver(false, "OVERTHROWN", "Defeat. You have been overthrown.");
        if (st.lostNewRepublic())      return gameOver(false, "NEW_REPUBLIC", "Defeat. The New Republic has fallen without you.");
        if (st.lostRebellion())        return gameOver(false, "REBELLION", "Defeat. The rebellion succeeded against you.");
        return gameOver(false, "GAME_OVER", "The game has ended.");
    }

    private static Messages.GameOver gameOver(boolean won, String reason, String text) {
        Messages.GameOver go = new Messages.GameOver();
        go.won = won;
        go.reason = reason;
        go.text = text;
        return go;
    }

    // ---- outbound ----

    /** per-empire notifications for what changed this turn, sent before the fresh view */
    private void broadcastNotifications() {
        synchronized (this) {
            for (Map.Entry<WebSocket, Player> e : players.entrySet()) {
                Empire emp = galaxy().empire(e.getValue().empireId);
                if (emp == null)
                    continue;
                List<Messages.Notification> items;
                synchronized (gameLock) {
                    items = notiCenter.update(emp);
                }
                if (items.isEmpty())
                    continue;
                Messages.Notifications msg = new Messages.Notifications();
                msg.turn = galaxy().currentTurn();
                msg.items = items;
                send(e.getKey(), Protocol.encode(msg));
            }
        }
    }

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
        broadcastAll(Protocol.encode(buildLobby(message)));
    }

    private synchronized Messages.Lobby buildLobby(String message) {
        Messages.Lobby lobby = new Messages.Lobby();
        lobby.message = message;
        for (Player p : players.values()) {
            Messages.Slot slot = new Messages.Slot();
            slot.empireId = p.empireId;
            slot.playerName = p.name;
            slot.connected = true;
            slot.raceId = p.raceId;
            lobby.slots.add(slot);
        }
        return lobby;
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
