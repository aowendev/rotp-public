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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import rotp.mp.client.ShipDesignPanel;
import rotp.mp.client.ShipDesigns;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Ship design screen: free-slot computation, the panel requesting the design
 * catalog on first data, and loading catalog + designs without a display.
 */
public class ShipDesignScreenTest {

    @Test
    void freeSlotsAreThoseWithoutAnActiveDesign() {
        List<PlayerView.DesignDto> active = Arrays.asList(
            design(0), design(1), design(2), design(4));   // slots 3 and 5 free
        List<Integer> free = ShipDesigns.freeSlots(active);
        assertEquals(Arrays.asList(3, 5), free, "only unused slots are free");
    }

    @Test
    void allSlotsFreeWhenNoDesigns() {
        assertEquals(6, ShipDesigns.freeSlots(java.util.Collections.emptyList()).size());
    }

    @Test
    void panelRequestsCatalogOnFirstView() {
        AtomicReference<Object> sent = new AtomicReference<>();
        ShipDesignPanel panel = new ShipDesignPanel(sent::set);

        PlayerView v = new PlayerView();
        v.designs.add(design(0));
        panel.updateFromView(v);

        assertTrue(sent.get() instanceof Messages.DesignCatalog,
            "the panel requests the design catalog when it first has data");
    }

    @Test
    void panelLoadsCatalogAndDesigns() {
        AtomicReference<Object> sent = new AtomicReference<>();
        ShipDesignPanel panel = new ShipDesignPanel(sent::set);

        PlayerView v = new PlayerView();
        v.designs.add(design(0));
        panel.updateFromView(v);

        Messages.DesignCatalog cat = new Messages.DesignCatalog();
        cat.hulls = Arrays.asList("SMALL", "MEDIUM", "LARGE", "HUGE");
        cat.computers = Arrays.asList("", "Battle Computer");
        cat.shields = Arrays.asList("");
        cat.ecms = Arrays.asList("");
        cat.armors = Arrays.asList("Titanium");
        cat.engines = Arrays.asList("Retros");
        cat.maneuvers = Arrays.asList("Class 1");
        cat.weapons = Arrays.asList("", "Laser", "Nuclear Bomb");
        cat.specials = Arrays.asList("", "Reserve Fuel Tanks");
        panel.setCatalog(cat);   // headless: must not throw
        assertNotNull(panel);
    }

    private static PlayerView.DesignDto design(int slot) {
        PlayerView.DesignDto d = new PlayerView.DesignDto();
        d.slot = slot;
        d.name = "Design " + slot;
        d.size = 0;
        return d;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
