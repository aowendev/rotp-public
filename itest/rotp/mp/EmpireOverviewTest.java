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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import rotp.mp.client.EmpirePanel;
import rotp.mp.client.EmpireStats;
import rotp.mp.protocol.PlayerView;

/**
 * Empire overview screen: the pure rollups (colony/fleet counts, production
 * total, relation summary) and the panel loading a view without a display.
 */
public class EmpireOverviewTest {

    @Test
    void rollupsCountColoniesFleetsAndSumProduction() {
        PlayerView v = new PlayerView();
        v.systems.add(colony("Sol", 12));
        v.systems.add(colony("Vega", 8));
        v.systems.add(uncolonized("Rigel"));   // not a colony
        v.fleets.add(new PlayerView.FleetDto());
        v.fleets.add(new PlayerView.FleetDto());
        v.fleets.add(new PlayerView.FleetDto());

        assertEquals(2, EmpireStats.colonyCount(v), "only colonies count");
        assertEquals(20f, EmpireStats.totalProduction(v), 0.001, "production summed over colonies");
        assertEquals(3, EmpireStats.fleetCount(v), "fleets counted");
    }

    @Test
    void relationSummaryReadsTheTreatyFlags() {
        assertEquals("at war", EmpireStats.describeRelation(rel(true, false, false, false, 0)));
        assertEquals("allied", EmpireStats.describeRelation(rel(false, true, false, false, 0)));
        assertEquals("non-aggression pact, trade 15",
            EmpireStats.describeRelation(rel(false, false, true, false, 15)));
        assertEquals("at peace", EmpireStats.describeRelation(rel(false, false, false, true, 0)));
        assertEquals("no treaties", EmpireStats.describeRelation(rel(false, false, false, false, 0)));
    }

    @Test
    void panelLoadsOverviewFromView() {
        EmpirePanel panel = new EmpirePanel();
        PlayerView v = new PlayerView();
        v.empireId = 0;
        v.tech = new PlayerView.TechDto();
        v.tech.totalRP = 12;
        v.systems.add(colony("Sol", 20));
        PlayerView.EmpireDto foe = new PlayerView.EmpireDto();
        foe.id = 1; foe.name = "Altairi Sovereignty"; foe.race = "Altairi"; foe.atWar = true;
        v.empires.add(foe);
        panel.updateFromView(v);   // headless: must not throw
        assertNotNull(panel);
    }

    private static PlayerView.SystemDto colony(String name, int production) {
        PlayerView.SystemDto s = new PlayerView.SystemDto();
        s.name = name;
        s.colony = new PlayerView.ColonyDto();
        s.colony.production = production;
        return s;
    }

    private static PlayerView.SystemDto uncolonized(String name) {
        PlayerView.SystemDto s = new PlayerView.SystemDto();
        s.name = name;
        return s;
    }

    private static PlayerView.EmpireDto rel(boolean war, boolean ally, boolean pact, boolean peace, int trade) {
        PlayerView.EmpireDto e = new PlayerView.EmpireDto();
        e.atWar = war; e.alliance = ally; e.pact = pact; e.atPeace = peace; e.tradeLevel = trade;
        return e;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
