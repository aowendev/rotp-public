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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import rotp.mp.client.FleetView;
import rotp.mp.client.FleetsPanel;
import rotp.mp.protocol.PlayerView;

/**
 * Fleets & transports screen: the pure summarization/deployability helpers,
 * and the panel loading fleets/transports from a view without a display.
 */
public class FleetsScreenTest {

    @Test
    void summarizeNamesShipsByDesignSlot() {
        PlayerView.FleetDto f = new PlayerView.FleetDto();
        f.counts = new int[]{3, 0, 0, 0, 1, 0};   // 3 in slot 0, 1 in slot 4
        List<PlayerView.DesignDto> designs = Arrays.asList(
            design(0, "Scout"), design(4, "Colony Ship"));
        assertEquals("3x Scout, 1x Colony Ship", FleetView.summarize(f, designs));
    }

    @Test
    void emptyFleetSummarizesAsEmpty() {
        PlayerView.FleetDto f = new PlayerView.FleetDto();
        f.counts = new int[]{0, 0, 0, 0, 0, 0};
        assertEquals("(empty)", FleetView.summarize(f, null));
    }

    @Test
    void deployabilityRequiresOrbitingWithShips() {
        PlayerView.FleetDto orbiting = fleet(7, new int[]{2, 0, 0, 0, 0, 0});
        PlayerView.FleetDto inTransit = fleet(-1, new int[]{2, 0, 0, 0, 0, 0});
        PlayerView.FleetDto orbitingEmpty = fleet(7, new int[]{0, 0, 0, 0, 0, 0});
        assertTrue(FleetView.deployable(orbiting), "orbiting with ships is deployable");
        assertFalse(FleetView.deployable(inTransit), "in-transit is not deployable");
        assertFalse(FleetView.deployable(orbitingEmpty), "orbiting but empty is not deployable");
    }

    @Test
    void panelLoadsFleetsAndTransportsFromView() {
        AtomicReference<Object> sent = new AtomicReference<>();
        FleetsPanel panel = new FleetsPanel(sent::set);

        PlayerView v = new PlayerView();
        // two systems, one a colony
        PlayerView.SystemDto home = new PlayerView.SystemDto();
        home.id = 1; home.name = "Sol"; home.colony = new PlayerView.ColonyDto();
        PlayerView.SystemDto other = new PlayerView.SystemDto();
        other.id = 2; other.name = "Vega";
        v.systems.add(home);
        v.systems.add(other);
        v.designs.add(design(0, "Scout"));
        v.fleets.add(fleet(1, new int[]{2, 0, 0, 0, 0, 0}));
        PlayerView.TransportDto t = new PlayerView.TransportDto();
        t.destSystemId = 2; t.size = 5;
        v.transports.add(t);

        panel.updateFromView(v);   // headless: must not throw
        assertNotNull(panel);
    }

    private static PlayerView.DesignDto design(int slot, String name) {
        PlayerView.DesignDto d = new PlayerView.DesignDto();
        d.slot = slot; d.name = name;
        return d;
    }

    private static PlayerView.FleetDto fleet(int atSystemId, int[] counts) {
        PlayerView.FleetDto f = new PlayerView.FleetDto();
        f.atSystemId = atSystemId; f.destSystemId = -1; f.counts = counts;
        return f;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
