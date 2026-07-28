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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.model.colony.ColonySpendingCategory;

/**
 * Two human players in one game: fog-of-war isolation, order validation
 * and ownership enforcement, we-go readiness, and orders surviving the
 * AI-driven turn resolution (i.e. the AI does not overwrite a remote
 * human's strategic orders).
 */
public class OrderCommandsTest {
    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null)
            server.stop();
    }

    @Test
    @Timeout(180)
    void twoPlayerOrdersAndIsolation() throws Exception {
        server = MpTestSupport.startServer(2);
        Client alice = new Client(server.port, "Alice");
        Client bob = new Client(server.port, "Bob");

        PlayerView av = alice.awaitView();
        PlayerView bv = bob.awaitView();

        PlayerView.SystemDto aHome = MpTestSupport.ownColony(av);
        PlayerView.SystemDto bHome = MpTestSupport.ownColony(bv);
        assertNotNull(aHome, "Alice sees her own colony detail");
        assertNotNull(bHome, "Bob sees his own colony detail");
        assertNotNull(av.tech, "Alice has research state");
        assertEquals(6, av.tech.alloc.length, "six research categories");
        assertFalse(av.fleets.isEmpty(), "Alice has starting fleets");

        // fog-of-war: Bob must not receive foreign colony detail
        boolean leak = false;
        for (PlayerView.SystemDto s : bv.systems)
            if ((s.colony != null) && (s.ownerId != bv.empireId))
                leak = true;
        assertFalse(leak, "no foreign colony detail leaks to Bob");

        // colony + tech allocations
        int[] colonyAlloc = {0, 0, 15, 25, 10};  // ship/def/ind/eco/tech, sum 50
        Messages.SetColonyAllocations ca = new Messages.SetColonyAllocations();
        ca.systemId = aHome.id;
        ca.alloc = colonyAlloc;
        assertTrue(alice.order(ca).ok, "Alice sets colony allocations");
        int[] echoed = MpTestSupport.system(alice.lastView, aHome.id).colony.alloc;
        assertArrayEquals5(colonyAlloc, echoed, "server applied colony allocations pre-turn");

        int[] techAlloc = {10, 10, 10, 10, 10, 10};
        Messages.SetTechAllocations ta = new Messages.SetTechAllocations();
        ta.alloc = techAlloc;
        assertTrue(alice.order(ta).ok, "Alice sets tech allocations");

        // ownership + validation
        Messages.SetColonyAllocations hostile = new Messages.SetColonyAllocations();
        hostile.systemId = aHome.id;
        hostile.alloc = colonyAlloc;
        assertFalse(bob.order(hostile).ok, "Bob's order against Alice's colony is rejected");

        Messages.SetColonyAllocations overspent = new Messages.SetColonyAllocations();
        overspent.systemId = aHome.id;
        overspent.alloc = new int[]{50, 50, 50, 50, 50};
        assertFalse(alice.order(overspent).ok, "overspent colony allocation is rejected");

        // fleet deployment to the nearest in-range system
        int deployedTo = -1;
        List<PlayerView.SystemDto> targets = MpTestSupport.nearestOthers(av, aHome);
        for (int i = 0; i < Math.min(6, targets.size()); i++) {
            Messages.DeployFleet df = new Messages.DeployFleet();
            df.fromSystemId = aHome.id;
            df.destSystemId = targets.get(i).id;
            if (alice.order(df).ok) { deployedTo = df.destSystemId; break; }
        }
        assertTrue(deployedTo >= 0, "Alice deploys her fleet to an in-range system");

        // we-go: one ready must not advance the turn
        alice.views.clear();
        bob.views.clear();
        Messages.Ready rd = new Messages.Ready();
        alice.raw(rd);
        Thread.sleep(1000);
        assertTrue(alice.views.isEmpty(), "turn does not resolve with only one player ready");

        bob.raw(rd);
        PlayerView av2 = alice.awaitView();
        bob.awaitView();
        assertEquals(av.turn + 1, av2.turn, "turn advances once both are ready");

        // orders survived AI-driven resolution
        PlayerView.SystemDto aHome2 = MpTestSupport.system(av2, aHome.id);
        assertNotNull(aHome2.colony, "Alice still owns her home colony");
        int[] got = aHome2.colony.alloc;
        // ship/def/ind/tech must be exact; eco may be nudged by cleanup housekeeping
        assertEquals(colonyAlloc[0], got[0], "ship alloc preserved");
        assertEquals(colonyAlloc[1], got[1], "def alloc preserved");
        assertEquals(colonyAlloc[2], got[2], "industry alloc preserved");
        assertEquals(colonyAlloc[4], got[4], "tech alloc preserved");
        assertTrue(got[3] >= colonyAlloc[3] - 5, "eco alloc roughly preserved (got "+Arrays.toString(got)+")");
        assertArrayEquals6(techAlloc, av2.tech.alloc, "research allocations preserved");

        boolean moving = false;
        for (PlayerView.FleetDto f : av2.fleets)
            if ((f.destSystemId == deployedTo) || (f.atSystemId == deployedTo))
                moving = true;
        assertTrue(moving, "deployed fleet is en route or arrived");

        alice.close();
        bob.close();
        assertEquals(ColonySpendingCategory.MAX_TICKS, sum(colonyAlloc), "sanity: test allocation sums to MAX_TICKS");
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int x : a) s += x;
        return s;
    }

    private static void assertArrayEquals5(int[] expected, int[] got, String msg) {
        assertTrue(Arrays.equals(expected, got), msg+" (expected "+Arrays.toString(expected)+", got "+Arrays.toString(got)+")");
    }

    private static void assertArrayEquals6(int[] expected, int[] got, String msg) {
        assertTrue(Arrays.equals(expected, got), msg+" (expected "+Arrays.toString(expected)+", got "+Arrays.toString(got)+")");
    }
}
