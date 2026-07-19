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

        for (Empire e : gal.empires()) {
            if ((e != emp) && !emp.hasContact(e))
                continue;
            PlayerView.EmpireDto ed = new PlayerView.EmpireDto();
            ed.id = e.id;
            ed.name = e.name();
            ed.race = e.raceName();
            ed.colorId = e.colorId();
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
            if (sd.scouted) {
                PlanetType pt = emp.sv.planetType(i);
                sd.planetType = (pt == null) ? null : pt.key();
            }
            if ((sys.empire() == emp) && sys.isColonized())
                sd.colony = colonyDto(sys.colony());
            v.systems.add(sd);
        }

        v.tech = techDto(emp.tech());
        v.fleets = fleetDtos(gal, emp);
        v.designs = designDtos(emp);
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
        c.population = col.population();
        c.factories = col.industry().factories();
        c.bases = col.defense().bases();
        c.production = col.production();
        rotp.model.ships.Design d = col.shipyard().design();
        c.shipyardDesign = (d == null) ? null : d.name();
        return c;
    }

    private static PlayerView.TechDto techDto(TechTree tech) {
        PlayerView.TechDto t = new PlayerView.TechDto();
        t.alloc = new int[TechTree.NUM_CATEGORIES];
        t.researching = new String[TechTree.NUM_CATEGORIES];
        for (int i = 0; i < TechTree.NUM_CATEGORIES; i++) {
            TechCategory cat = tech.category(i);
            t.alloc[i] = cat.allocation();
            t.researching[i] = (cat.currentTech() == null) ? null : cat.currentTechName();
        }
        t.totalRP = tech.empire().totalPlanetaryResearch();
        return t;
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
            out.add(dto);
        }
        return out;
    }
}
