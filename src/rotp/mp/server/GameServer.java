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
import rotp.model.ships.ShipDesign;
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
        else if (msg instanceof Messages.DesignCatalog)
            handleDesignCatalog(conn);
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
            else
                err = applySetShipBuild(emp, (Messages.SetShipBuild) cmd);
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
