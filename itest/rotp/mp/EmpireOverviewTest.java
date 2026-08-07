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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.client.EmpirePanel;
import rotp.mp.client.EmpireStats;
import rotp.mp.protocol.Messages;
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
        EmpirePanel panel = new EmpirePanel(o -> { });
        PlayerView v = new PlayerView();
        v.empireId = 0;
        v.tech = new PlayerView.TechDto();
        v.tech.totalRP = 12;
        v.reserve = 240;
        v.totalIncome = 55;
        v.netIncome = 48;
        v.maintenanceCost = 7;
        PlayerView.SystemDto sol = colony("Sol", 20);
        sol.colony.maxSize = 100;
        sol.colony.waste = 5;
        sol.colony.shield = 5;
        sol.colony.notes = "Rebellion 30%";
        v.systems.add(sol);
        PlayerView.EmpireDto foe = new PlayerView.EmpireDto();
        foe.id = 1; foe.name = "Altairi Sovereignty"; foe.race = "Altairi"; foe.atWar = true;
        v.empires.add(foe);
        panel.updateFromView(v);   // headless: must not throw with the new columns/economy
        assertNotNull(panel);
    }

    @Test
    @Timeout(120)
    void serverProvidesEconomyAndColonyDetailAndValidatesReserveTransfer() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            // empire economy fields are populated
            assertTrue(v.totalIncome > 0, "gross income reported");
            assertTrue(v.reserve >= 0, "planetary reserve reported");
            PlayerView.SystemDto home = MpTestSupport.ownColony(v);
            assertNotNull(home.colony.notes, "colony carries a (possibly empty) notes string");
            assertTrue(home.colony.shield >= 0, "colony reports a shield level");

            // reserve transfer validation: empty reserve at game start, and not-your-colony
            Messages.TransferReserve tr = new Messages.TransferReserve();
            tr.systemId = home.id; tr.amount = 50;
            assertFalse(alice.order(tr).ok, "transfer from an empty reserve is rejected");
            Messages.TransferReserve bad = new Messages.TransferReserve();
            bad.systemId = 99999; bad.amount = 50;
            assertFalse(alice.order(bad).ok, "transfer to an unknown/not-owned system is rejected");
        }
        finally {
            alice.close();
            server.stop();
        }
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
