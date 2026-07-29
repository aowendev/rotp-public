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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.client.ColonyAllocations;
import rotp.mp.client.ResearchPanel;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * The research screen: the server hands a remote human a fully-allocated
 * (sum 60) research split at game start, the redistribution keeps that total,
 * and the panel loads the DTO and can apply an order.
 */
public class ResearchScreenTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void serverEqualizesResearchToFullAllocationAtStart() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        assertNotNull(v.tech, "view carries research state");
        assertEquals(6, v.tech.alloc.length, "six research categories");
        assertEquals(60, ColonyAllocations.sum(v.tech.alloc),
            "research starts fully allocated (sum 60 = 100%), not left at zero");

        // and the server accepts a reallocation that sums to 60
        int[] focus = {30, 30, 0, 0, 0, 0};
        Messages.SetTechAllocations ta = new Messages.SetTechAllocations();
        ta.alloc = focus;
        assertTrue(alice.order(ta).ok, "reallocating research is accepted");
        assertEquals(60, ColonyAllocations.sum(alice.lastView.tech.alloc), "still fully allocated after the order");
    }

    @Test
    void redistributionKeepsResearchTotalAtSixty() {
        int[] a = {10, 10, 10, 10, 10, 10};
        a[0] = 40;
        ColonyAllocations.balance(a, 0, null, 60);
        assertEquals(60, ColonyAllocations.sum(a), "research total stays 60");
        assertEquals(40, a[0], "the dragged category keeps its value");
    }

    @Test
    void panelLoadsResearchFromView() {
        AtomicReference<Object> sent = new AtomicReference<>();
        ResearchPanel panel = new ResearchPanel(sent::set);

        PlayerView v = new PlayerView();
        v.tech = new PlayerView.TechDto();
        v.tech.alloc = new int[]{10, 10, 10, 10, 10, 10};
        v.tech.researching = new String[]{"Controls", null, "Shields", null, "Engines", "Lasers"};
        v.tech.totalRP = 12;
        panel.updateFromView(v);   // headless: constructs and loads without a display
        assertNotNull(panel);
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
