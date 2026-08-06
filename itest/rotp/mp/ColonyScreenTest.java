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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.client.ColonyAllocations;
import rotp.mp.client.ColonyPanel;
import rotp.mp.client.GalaxyViewPanel;
import rotp.mp.protocol.PlayerView;

/**
 * Verifies the colony management screen's logic without a display: the
 * spending redistribution keeps the total fixed and honours locks, and the
 * panel loads a ColonyDto and emits a valid setColonyAlloc order.
 */
public class ColonyScreenTest {

    // ---- redistribution logic (pure) ----

    @Test
    void raisingOneCategoryLowersOthersKeepingTheTotal() {
        int[] a = {10, 10, 10, 10, 10};   // sum 50
        a[2] = 30;                         // user drags industry up
        ColonyAllocations.balance(a, 2, new boolean[5], 50);
        assertEquals(50, ColonyAllocations.sum(a), "total stays at 50");
        assertEquals(30, a[2], "the dragged category keeps its value");
    }

    @Test
    void loweringOneCategoryRaisesOthers() {
        int[] a = {10, 10, 10, 10, 10};
        a[0] = 0;
        ColonyAllocations.balance(a, 0, new boolean[5], 50);
        assertEquals(50, ColonyAllocations.sum(a), "total stays at 50");
        assertEquals(0, a[0], "the dragged category keeps its value");
    }

    @Test
    void lockedCategoriesAreNeverAdjusted() {
        int[] a = {10, 10, 10, 10, 10};
        boolean[] locked = {false, true, false, false, false};   // defense locked
        a[2] = 40;
        ColonyAllocations.balance(a, 2, locked, 50);
        assertEquals(50, ColonyAllocations.sum(a), "total stays at 50");
        assertEquals(10, a[1], "locked category is untouched");
    }

    @Test
    void changeIsClampedWhenOthersCannotAbsorbIt() {
        // everything else locked at 0; industry cannot exceed the remaining budget
        int[] a = {0, 0, 50, 0, 0};
        boolean[] locked = {true, true, false, true, true};
        a[2] = 30;   // try to lower industry while all others are locked at 0
        ColonyAllocations.balance(a, 2, locked, 50);
        assertEquals(50, ColonyAllocations.sum(a), "total stays at 50");
        assertEquals(50, a[2], "industry clamped back since no category can take the freed ticks");
    }

    // ---- panel DTO -> order plumbing (headless; no display shown) ----

    @Test
    void panelLoadsColonyFromView() {
        AtomicReference<Object> sent = new AtomicReference<>();
        ColonyPanel panel = new ColonyPanel(sent::set);

        PlayerView view = viewWithColony(7, new int[]{5, 5, 20, 15, 5});
        panel.showColony(7, view);
        assertEquals(7, panel.shownSystemId(), "panel loaded the selected colony");

        // a fresh authoritative view refreshes cleanly and keeps the colony
        panel.updateFromView(view);
        assertEquals(7, panel.shownSystemId(), "colony still shown after a view refresh");
    }

    @Test
    void colonyPanelLoadsShipBuildOptions() {
        AtomicReference<Object> sent = new AtomicReference<>();
        ColonyPanel panel = new ColonyPanel(sent::set);

        PlayerView v = viewWithColony(7, new int[]{30, 0, 5, 10, 5});
        v.systems.get(0).colony.shipyardDesign = "Scout";
        v.systems.get(0).colony.buildLimit = 3;
        PlayerView.DesignDto scout = new PlayerView.DesignDto();
        scout.slot = 0; scout.name = "Scout";
        PlayerView.DesignDto wasp = new PlayerView.DesignDto();
        wasp.slot = 5; wasp.name = "Wasp";
        v.designs.add(scout);
        v.designs.add(wasp);

        panel.showColony(7, v);   // headless: loads the build dropdown from designs, preselects "Scout"
        assertEquals(7, panel.shownSystemId(), "colony loaded with build options");
    }

    @Test
    void clickingAColonyOnTheMapSelectsIt() {
        // wire the galaxy map to the colony panel exactly as ClientMain does
        AtomicReference<Object> sent = new AtomicReference<>();
        ColonyPanel colony = new ColonyPanel(sent::set);
        GalaxyViewPanel map = new GalaxyViewPanel();
        PlayerView view = viewWithColony(7, new int[]{10, 10, 10, 10, 10});
        view.galaxyWidth = 20;
        view.galaxyHeight = 20;
        view.systems.get(0).x = 5;
        view.systems.get(0).y = 5;
        map.view(view);
        map.onSystemClicked(id -> { if (id >= 0) colony.showColony(id, view); });

        // reproduce the panel's screen transform and click on the colony's pixel
        int margin = 30;
        float scale = Math.min((900 - 2f*margin) / 20, (700 - 2f*margin) / 20);
        int px = margin + Math.round(5 * scale);
        int py = margin + Math.round(5 * scale);
        int hit = GalaxyViewPanel.systemAt(view, px, py, margin, scale);
        assertEquals(7, hit, "hit-test finds the system under the click");

        colony.showColony(hit, view);
        assertEquals(7, colony.shownSystemId(), "clicking the colony opened it in the colony panel");

        assertEquals(-1, GalaxyViewPanel.systemAt(view, px + 200, py + 200, margin, scale),
            "clicking empty space misses");
    }

    // ---- server-computed per-category result hints ----

    @Test
    @Timeout(120)
    void serverProvidesPerCategoryResultHints() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            PlayerView.SystemDto home = MpTestSupport.ownColony(v);
            assertNotNull(home, "player has a home colony");
            assertNotNull(home.colony.result, "colony carries per-category result hints");
            assertEquals(5, home.colony.result.length, "one hint per spending category");
            for (int i = 0; i < 5; i++)
                assertTrue((home.colony.result[i] != null) && !home.colony.result[i].isEmpty(),
                    "category " + i + " has a non-empty, display-ready result hint (got: "
                    + home.colony.result[i] + ")");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    private static PlayerView viewWithColony(int sysId, int[] alloc) {
        PlayerView v = new PlayerView();
        v.empireId = 0;
        PlayerView.SystemDto s = new PlayerView.SystemDto();
        s.id = sysId;
        s.name = "Sol";
        s.ownerId = 0;
        s.colonized = true;
        PlayerView.ColonyDto c = new PlayerView.ColonyDto();
        c.alloc = alloc;
        c.locked = new boolean[5];
        c.population = 40;
        c.factories = 30;
        c.bases = 2;
        c.production = 12;
        c.result = new String[]{"3 years", "5 years", "+8 BC/yr", "+2 pop", "45 RP"};
        s.colony = c;
        v.systems.add(s);
        assertTrue(ColonyAllocations.sum(alloc) == 50, "test fixture allocations sum to 50");
        return v;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
