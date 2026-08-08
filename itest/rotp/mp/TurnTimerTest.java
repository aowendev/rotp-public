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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * The optional turn timer (Phase 3): when set, the server auto-resolves a we-go turn a
 * fixed number of seconds after orders open, so an absent or slow human can't stall the
 * game. Zero (the default) disables it — turns only resolve when everyone is ready.
 */
public class TurnTimerTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(60)
    void anExpiredTurnTimerAutoResolvesWithoutTheHumanReadying() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        int startTurn = v.turn;

        // enable a short timer; the client is told the new deadline
        server.impl.setTurnTimer(2);
        Messages.TurnStatus armed = awaitStatusWithTimer(alice);
        assertNotNull(armed, "the client is told the turn timer is armed");
        assertTrue(armed.secondsRemaining > 0 && armed.secondsRemaining <= 2,
            "the status carries the seconds remaining: " + armed.secondsRemaining);

        // without ever readying, the next view should arrive on its own once the timer fires
        PlayerView after = alice.awaitView();
        assertEquals(startTurn + 1, after.turn,
            "the turn auto-resolved when the timer expired, without the human readying");
    }

    @Test
    @Timeout(60)
    void readyingStillResolvesImmediatelyWithATimerSet() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        int startTurn = v.turn;

        server.impl.setTurnTimer(30);   // long timer: readying must not wait for it
        long t0 = System.currentTimeMillis();
        PlayerView after = alice.ready();
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(startTurn + 1, after.turn, "readying resolves the turn");
        assertTrue(elapsed < 20_000, "readying resolved promptly, not after the 30s timer: " + elapsed + "ms");
    }

    @Test
    @Timeout(30)
    void aDisabledTimerDoesNotAutoResolve() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        server.impl.setTurnTimer(0);   // disabled (also the default)
        // no view should arrive on its own within a short window if we never ready
        PlayerView stray = alice.views.poll(4, TimeUnit.SECONDS);
        assertNull(stray, "with the timer off, the turn does not auto-resolve");
    }

    /** wait for a turn-status message that reports an armed timer */
    private static Messages.TurnStatus awaitStatusWithTimer(Client c) throws Exception {
        for (int i = 0; i < 20; i++) {
            Messages.TurnStatus ts = c.turnStatuses.poll(5, TimeUnit.SECONDS);
            if (ts == null)
                return null;
            if (ts.secondsRemaining >= 0)
                return ts;
        }
        return null;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
