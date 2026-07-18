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

import rotp.model.empires.Empire;
import rotp.model.galaxy.Galaxy;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.model.planet.PlanetType;
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
            v.systems.add(sd);
        }
        return v;
    }
}
