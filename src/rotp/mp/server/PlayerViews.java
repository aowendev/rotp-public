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

import rotp.model.colony.Colony;
import rotp.model.empires.Empire;
import rotp.model.empires.EmpireView;
import rotp.model.galaxy.Galaxy;
import rotp.model.galaxy.ShipFleet;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.model.planet.PlanetType;
import rotp.model.ships.ShipDesign;
import rotp.model.ships.ShipDesignLab;
import rotp.model.tech.TechCategory;
import rotp.model.tech.TechTree;
import rotp.mp.protocol.PlayerView;

/**
 * Builds a PlayerView for one empire from its fog-of-war data (Empire.sv).
 * Only information that empire legitimately knows goes over the wire.
 */
public final class PlayerViews {
    private PlayerViews() { }

    public static PlayerView build(Empire emp) {
        Galaxy gal = GameSession.instance().galaxy();
        PlayerView v = new PlayerView();
        v.turn = gal.currentTurn();
        v.year = gal.currentYear();
        v.galaxyWidth = gal.width();
        v.galaxyHeight = gal.height();
        v.empireId = emp.id;
        v.empireName = emp.name();
        v.raceName = emp.raceName();
        v.colorId = emp.colorId();

        v.internalSecurity = emp.internalSecurity();
        v.reserve = emp.totalReserve();
        v.totalIncome = emp.totalIncome();
        v.netIncome = emp.netIncome();
        v.maintenanceCost = emp.totalShipMaintenanceCost() + emp.totalStargateCost() + emp.totalMissileBaseCost();
        for (Empire e : gal.empires()) {
            if ((e != emp) && !emp.hasContact(e))
                continue;
            PlayerView.EmpireDto ed = new PlayerView.EmpireDto();
            ed.id = e.id;
            ed.name = e.name();
            ed.race = e.raceName();
            ed.colorId = e.colorId();
            if (e.leader() != null) {
                ed.personality = e.leader().personality();
                ed.objective = e.leader().objective();
            }
            EmpireView ev = (e == emp) ? null : emp.viewForEmpire(e);
            if (ev != null) {
                ed.atWar = ev.embassy().anyWar();
                ed.pact = ev.embassy().pact();
                ed.alliance = ev.embassy().alliance();
                ed.atPeace = ev.embassy().atPeace();
                ed.tradeLevel = ev.trade().level();
                ed.maxTradeLevel = ev.trade().maxLevel();
                ed.spySpending = ev.spies().allocation();
                ed.spyMission = ev.spies().isHide() ? "HIDE"
                    : ev.spies().isEspionage() ? "ESPIONAGE" : "SABOTAGE";
                ed.spies = ev.spies().numActiveSpies();
                ed.maxSpies = ev.spies().maxSpies();
                ed.relativePower = ev.empirePower();
                ed.knownTechCount = ev.spies().tech().allKnownTechs().size();
                ed.reportAge = ev.spies().reportAge();
            }
            v.empires.add(ed);
        }

        for (int i = 0; i < gal.numStarSystems(); i++) {
            StarSystem sys = gal.system(i);
            PlayerView.SystemDto sd = new PlayerView.SystemDto();
            sd.id = i;
            sd.x = sys.x();
            sd.y = sys.y();
            sd.scouted = emp.sv.isScouted(i);
            sd.name = emp.sv.name(i);
            sd.ownerId = emp.sv.empId(i);
            sd.colonized = emp.sv.isColonized(i);
            sd.population = emp.sv.population(i);
            sd.distance = emp.sv.distance(i);
            sd.inShipRange = emp.sv.inShipRange(i);
            if (sd.scouted) {
                PlanetType pt = emp.sv.planetType(i);
                sd.planetType = (pt == null) ? null : pt.key();
                sd.planetTypeName = (pt == null) ? null : pt.name();
                sd.maxSize = emp.sv.currentSize(i);
                sd.canColonize = (pt != null) && !sd.colonized && emp.canColonize(pt);
            }
            if ((sys.empire() == emp) && sys.isColonized())
                sd.colony = colonyDto(sys.colony());
            v.systems.add(sd);
        }

        v.tech = techDto(emp.tech());
        v.fleets = fleetDtos(gal, emp);
        v.designs = designDtos(emp);
        v.transports = transportDtos(gal, emp);
        return v;
    }

    private static PlayerView.ColonyDto colonyDto(Colony col) {
        PlayerView.ColonyDto c = new PlayerView.ColonyDto();
        c.alloc = new int[Colony.NUM_CATS];
        c.locked = new boolean[Colony.NUM_CATS];
        for (int i = 0; i < Colony.NUM_CATS; i++) {
            c.alloc[i] = col.allocation(i);
            c.locked[i] = col.locked(i);
        }
        c.result = colonyResults(col);
        c.population = col.population();
        c.maxSize = col.maxSize();
        c.planetSize = col.planet().currentSize();
        c.waste = col.ecology().waste();
        c.popGrowth = col.ecology().upcomingPopGrowth();
        c.shield = col.defense().shieldLevel();
        c.notes = colonyNotes(col);
        c.factories = col.industry().factories();
        c.bases = col.defense().bases();
        c.production = col.production();
        rotp.model.ships.Design d = col.shipyard().design();
        c.shipyardDesign = (d == null) ? null : d.name();
        c.buildLimit = col.shipyard().buildLimit();
        StarSystem dest = col.transportDestination();
        c.transportSize = (int) col.inTransport();
        c.transportDestId = (dest == null) ? -1 : dest.id;
        return c;
    }

    /** status notes for a colony (rebellion, plague quarantine, a space monster in
     * the system, or a custom system note) — the Notes column of the planets list */
    private static String colonyNotes(Colony col) {
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (col.inRebellion())
            notes.add("Rebellion " + Math.round(col.rebellionPct() * 100) + "%");
        if (col.quarantined())
            notes.add("Quarantine");
        StarSystem sys = col.starSystem();
        if ((sys != null) && sys.hasMonster())
            notes.add("Space monster");
        String custom = (sys == null) ? "" : sys.notes();
        if ((custom != null) && !custom.isEmpty())
            notes.add(custom);
        return String.join(", ", notes);
    }

    /** the per-category result hints for a colony's current allocations, in
     * ColonyDto.result order; reused for the live spending preview */
    public static String[] colonyResults(Colony col) {
        String[] r = new String[Colony.NUM_CATS];
        for (int i = 0; i < Colony.NUM_CATS; i++)
            r[i] = categoryResult(col, i);
        return r;
    }

    /**
     * The colony screen's per-category result hint for the current spending — the
     * same projection the desktop UI shows next to each slider: Ship/Def years to
     * complete, Ind output per year, Eco waste/clean/growth, Tech research points.
     * ROTP's `upcomingResult()` returns display-ready text; a couple of branches
     * hand back a raw label key instead, so resolve those defensively.
     *
     * Ecology is special-cased: `upcomingResult()` only says "Growth" once the
     * colony is growing, but the useful number is how many population it will add,
     * so report "+n pop" (via `upcomingPopGrowth()`) when there is growth, and fall
     * back to the state word (Waste / Clean / terraforming) otherwise.
     */
    private static String categoryResult(Colony col, int cat) {
        if (cat == Colony.ECOLOGY) {
            int growth = col.ecology().upcomingPopGrowth();
            if (growth > 0)
                return "+" + growth + " pop";
        }
        String r = col.category(cat).upcomingResult();
        if ((r != null) && r.startsWith("MAIN_"))
            r = rotp.util.LabelManager.current().label(r);
        return r;
    }

    private static PlayerView.TechDto techDto(TechTree tech) {
        PlayerView.TechDto t = new PlayerView.TechDto();
        t.alloc = new int[TechTree.NUM_CATEGORIES];
        t.locked = new boolean[TechTree.NUM_CATEGORIES];
        t.progress = new float[TechTree.NUM_CATEGORIES];
        t.researching = new String[TechTree.NUM_CATEGORIES];
        t.researchingId = new String[TechTree.NUM_CATEGORIES];
        for (int i = 0; i < TechTree.NUM_CATEGORIES; i++) {
            TechCategory cat = tech.category(i);
            t.alloc[i] = cat.allocation();
            t.locked[i] = cat.locked();
            t.progress[i] = researchProgress(cat);
            t.researchingId[i] = cat.currentTech();
            t.researching[i] = (cat.currentTech() == null) ? null : cat.currentTechName();
            java.util.List<PlayerView.TechChoice> choices = new java.util.ArrayList<>();
            for (String techId : cat.techIdsAvailableForResearch()) {
                rotp.model.tech.Tech tk = rotp.model.tech.TechLibrary.current().tech(techId);
                if (tk == null)
                    continue;
                PlayerView.TechChoice ch = new PlayerView.TechChoice();
                ch.id = techId;
                ch.name = tk.name();
                ch.cost = cat.costForTech(tk);
                choices.add(ch);
            }
            t.choices.add(choices);
        }
        t.totalRP = tech.empire().totalPlanetaryResearch();
        return t;
    }

    /** how close a category's current research is to completion (0..1; >= 1 means
     * the cost is met and it can be discovered on an upcoming turn) */
    private static float researchProgress(TechCategory cat) {
        String id = cat.currentTech();
        if (id == null)
            return 0f;
        rotp.model.tech.Tech tk = rotp.model.tech.TechLibrary.current().tech(id);
        if (tk == null)
            return 0f;
        float cost = cat.costForTech(tk);
        return (cost > 0) ? (cat.totalBC() / cost) : 0f;
    }

    private static java.util.List<PlayerView.FleetDto> fleetDtos(Galaxy gal, Empire emp) {
        java.util.List<PlayerView.FleetDto> out = new java.util.ArrayList<>();
        for (ShipFleet fl : gal.ships.allFleets(emp.id)) {
            if (!fl.isActive())
                continue;
            PlayerView.FleetDto f = new PlayerView.FleetDto();
            f.atSystemId = fl.isOrbiting() ? fl.sysId() : -1;
            f.destSystemId = fl.destSysId();
            f.x = fl.x();
            f.y = fl.y();
            f.counts = new int[ShipDesignLab.MAX_DESIGNS];
            for (int i = 0; i < ShipDesignLab.MAX_DESIGNS; i++)
                f.counts[i] = fl.num(i);
            out.add(f);
        }
        return out;
    }

    private static java.util.List<PlayerView.DesignDto> designDtos(Empire emp) {
        java.util.List<PlayerView.DesignDto> out = new java.util.ArrayList<>();
        for (int slot = 0; slot < ShipDesignLab.MAX_DESIGNS; slot++) {
            ShipDesign d = emp.shipLab().design(slot);
            if ((d == null) || !d.active())
                continue;
            PlayerView.DesignDto dto = new PlayerView.DesignDto();
            dto.slot = slot;
            dto.name = d.name();
            dto.size = d.size();
            dto.totalSpace = d.totalSpace();
            dto.availableSpace = d.availableSpace();
            dto.colonyShip = d.hasColonySpecial();
            dto.range = d.range();
            out.add(dto);
        }
        return out;
    }

    private static java.util.List<PlayerView.TransportDto> transportDtos(Galaxy gal, Empire emp) {
        java.util.List<PlayerView.TransportDto> out = new java.util.ArrayList<>();
        for (rotp.model.galaxy.Transport tr : gal.transports()) {
            if (tr.empId() != emp.id)
                continue;
            PlayerView.TransportDto t = new PlayerView.TransportDto();
            t.destSystemId = tr.destSysId();
            t.size = tr.size();
            t.x = tr.x();
            t.y = tr.y();
            out.add(t);
        }
        return out;
    }
}
