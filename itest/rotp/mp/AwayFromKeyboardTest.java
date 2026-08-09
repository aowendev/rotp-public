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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.PlayerView;

/**
 * A dropped player's empire is played by the AI until they reconnect.
 *
 * Without this it does not coast, it *stalls*: {@code decidedByAI()} is false for
 * a remote human whether or not they are connected, so while they are away nothing
 * reallocates research, designs ships, commands fleets or sends transports. A real
 * two-machine game (2026-08-09) ran 31 turns with one player disconnected and his
 * empire frozen on its last orders the whole time.
 */
public class AwayFromKeyboardTest {
    private Server server;
    private Client alice, bob;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(180)
    void theAiPlaysADroppedEmpireAndHandsItBackOnReconnect() throws Exception {
        server = MpTestSupport.startServer(2);
        alice = new Client(server.port, "Alice");
        assertEquals(0, alice.awaitJoined().empireId, "Alice is empire 0");
        bob = new Client(server.port, "Bob");
        assertEquals(1, bob.awaitJoined().empireId, "Bob is empire 1");
        alice.awaitView();
        PlayerView bv = bob.awaitView();

        Empire bobEmp = GameSession.instance().galaxy().empire(bv.empireId);
        assertTrue(bobEmp.isRemoteHuman(), "Bob's empire is a remote human's");
        assertFalse(bobEmp.decidedByAI(), "while connected, Bob decides for himself");

        bob.close();
        bob = null;
        // the drop is processed on the server's socket thread
        for (int i = 0; (i < 100) && !bobEmp.awayFromKeyboard(); i++)
            Thread.sleep(50);
        assertTrue(bobEmp.awayFromKeyboard(), "the server notices Bob has gone");
        assertTrue(bobEmp.decidedByAI(),
            "the AI takes the helm so the empire keeps playing, rather than stalling");
        assertTrue(bobEmp.isRemoteHuman(),
            "but it is still a human's empire - clearing that would lose the slot on save/load");

        // the game runs on without him
        PlayerView before = alice.lastView;
        PlayerView after = alice.ready();
        assertTrue(after.turn > before.turn, "Alice's turn resolves while Bob is away");

        // ...and Bob gets the helm back
        Client returning = new Client(server.port, "Bob");
        try {
            assertNotNull(returning.starts.poll(10, TimeUnit.SECONDS), "Bob rejoins");
            returning.awaitView();
            assertFalse(bobEmp.awayFromKeyboard(), "Bob is back at the controls");
            assertFalse(bobEmp.decidedByAI(), "the AI stops deciding for him");
        }
        finally {
            returning.close();
        }
    }

    /**
     * The flag must not leak into single-player, where empires are never remote
     * humans and decidedByAI() has to keep meaning exactly what it did.
     */
    @Test
    @Timeout(120)
    void anOrdinaryAiEmpireIsUnaffected() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        for (Empire e : GameSession.instance().galaxy().empires()) {
            if (e.id == v.empireId)
                continue;
            assertFalse(e.isRemoteHuman(), "AI empires are not remote humans");
            assertTrue(e.decidedByAI(), "and are still decided by the AI as before");
            assertFalse(e.awayFromKeyboard(), "nothing marks them away");
        }
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
