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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    @Timeout(180)
    void completingResearchProducesATechNotification() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);

        // fund research heavily and concentrate all of it into one category so a
        // tech completes within a bounded number of turns (certain by ~3x cost)
        Messages.SetColonyAllocations ca = new Messages.SetColonyAllocations();
        ca.systemId = home.id;
        ca.alloc = new int[]{0, 0, 5, 15, 30};   // ship/def/ind/eco/tech, sum 50
        assertTrue(alice.order(ca).ok, "set heavy research spending");
        Messages.SetTechAllocations ta = new Messages.SetTechAllocations();
        ta.alloc = new int[]{60, 0, 0, 0, 0, 0};   // all into Computers
        assertTrue(alice.order(ta).ok, "concentrate research");

        boolean sawTech = false;
        for (int t = 0; t < 60 && !sawTech; t++) {
            alice.ready();
            if (MpTestSupport.sawNotification(alice, "TECH"))
                sawTech = true;
        }
        assertTrue(sawTech, "researching a technology produced a TECH notification");
    }

    @Test
    @Timeout(120)
    void playerCanChooseWhatToResearch() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        assertNotNull(v.tech.choices, "view carries research choices");
        assertEquals(6, v.tech.choices.size(), "one choice list per category");

        // find a category that offers at least one tech to research
        int cat = -1;
        String pickId = null;
        for (int i = 0; i < 6; i++)
            if (!v.tech.choices.get(i).isEmpty()) {
                cat = i;
                pickId = v.tech.choices.get(i).get(0).id;
                break;
            }
        assumeTrue(cat >= 0, "some category offers a research choice");

        Messages.SetResearchChoice rc = new Messages.SetResearchChoice();
        rc.category = cat;
        rc.techId = pickId;
        assertTrue(alice.order(rc).ok, "research choice accepted");
        assertEquals(pickId, alice.lastView.tech.researchingId[cat],
            "the chosen tech is now this category's research target");

        // an unavailable tech is rejected
        Messages.SetResearchChoice bad = new Messages.SetResearchChoice();
        bad.category = cat;
        bad.techId = "not_a_real_tech";
        assertFalse(alice.order(bad).ok, "an invalid research choice is rejected");
    }

    @Test
    @Timeout(180)
    void researchProgressTowardTheCurrentTechIsReported() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        assertNotNull(v.tech.progress, "view carries per-category research progress");
        assertEquals(6, v.tech.progress.length, "one progress value per category");

        // fund research heavily and concentrate it so progress accrues on one category
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);
        Messages.SetColonyAllocations ca = new Messages.SetColonyAllocations();
        ca.systemId = home.id;
        ca.alloc = new int[]{0, 0, 5, 15, 30};      // heavy tech spending
        assertTrue(alice.order(ca).ok, "heavy research spending set");
        Messages.SetTechAllocations ta = new Messages.SetTechAllocations();
        ta.alloc = new int[]{60, 0, 0, 0, 0, 0};    // all into Computers
        assertTrue(alice.order(ta).ok, "concentrate research");

        boolean sawProgress = false;
        for (int t = 0; t < 12 && !sawProgress; t++) {
            alice.ready();
            for (int i = 0; i < 6; i++)
                if ((alice.lastView.tech.researchingId[i] != null) && (alice.lastView.tech.progress[i] > 0f))
                    sawProgress = true;
        }
        assertTrue(sawProgress, "research progress toward the current tech is reported as it accrues");
    }

    @Test
    @Timeout(120)
    void lockingAResearchCategoryProtectsItAndIsReflectedInTheView() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        Messages.SetTechAllocations ta = new Messages.SetTechAllocations();
        ta.alloc = new int[]{20, 20, 20, 0, 0, 0};       // sum 60
        assertTrue(alice.order(ta).ok, "research split applied");

        Messages.SetTechLock lk = new Messages.SetTechLock();
        lk.category = 0; lk.locked = true;               // lock Computers
        assertTrue(alice.order(lk).ok, "locking a research category is accepted");
        assertTrue(alice.lastView.tech.locked[0], "the lock is reflected in the view");

        // an allocation that would move the locked category is rejected
        Messages.SetTechAllocations move = new Messages.SetTechAllocations();
        move.alloc = new int[]{0, 30, 30, 0, 0, 0};      // Computers 0 != locked 20
        assertFalse(alice.order(move).ok, "changing a locked research category is rejected");

        lk.locked = false;
        assertTrue(alice.order(lk).ok, "unlocking is accepted");
        assertFalse(alice.lastView.tech.locked[0], "the category is no longer locked");
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
