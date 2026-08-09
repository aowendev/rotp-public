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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Two-human end-to-end foundation: two clients fill the human slots, the game auto-starts,
 * each client drives its own empire with its own fog-of-war view, and a we-go turn resolves
 * only once BOTH are ready. This underpins the per-empire routing tests (notifications,
 * prompts, alerts) that a multi-human backend must get right before the browser client.
 */
public class TwoHumanTest {
    private Server server;
    private Client alice, bob;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void twoHumansGetDistinctEmpiresAndResolveTurnsTogether() throws Exception {
        server = MpTestSupport.startServer(2);
        // serialize the joins so empire assignment is deterministic (the two client
        // connections would otherwise race): Alice -> empire 0 (host), Bob -> empire 1.
        alice = new Client(server.port, "Alice");
        assertEquals(0, alice.awaitJoined().empireId, "the first to join is empire 0");
        bob = new Client(server.port, "Bob");         // fills the slots -> auto-start
        assertEquals(1, bob.awaitJoined().empireId, "the second to join is empire 1");

        PlayerView av = alice.awaitView();
        PlayerView bv = bob.awaitView();
        assertNotEquals(av.empireId, bv.empireId, "the two humans control different empires");
        assertEquals(0, av.empireId, "alice's view is empire 0's perspective");
        assertEquals(1, bv.empireId, "bob's view is empire 1's perspective");

        int startTurn = av.turn;

        // a we-go turn resolves only when BOTH are ready. Alice readies without blocking
        // (no view will arrive until bob also readies); bob readies and the turn resolves.
        Messages.Ready aliceReady = new Messages.Ready();
        alice.raw(aliceReady);
        PlayerView bAfter = bob.ready();     // now both ready -> turn resolves
        assertTrue(bAfter.turn > startTurn, "the turn resolves once both humans are ready");

        PlayerView aAfter = alice.awaitView();
        assertTrue(aAfter.turn > startTurn, "both humans advance to the new turn together");
        assertEquals(0, aAfter.empireId, "alice still controls empire 0 after the turn");
        assertEquals(1, bob.lastView.empireId, "bob still controls empire 1 after the turn");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
