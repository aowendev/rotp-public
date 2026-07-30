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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * A player can advance turn after turn. Regression: the post-turn TurnStatus
 * was broadcast while turnRunning was still true, so it reported processing and
 * the client left the Next Turn button disabled — stranding the game after the
 * first turn. The terminal status of a resolved turn must report processing=false.
 */
public class TurnAdvanceTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void consecutiveTurnsResolveAndUnlockTheButton() throws Exception {
        server = MpTestSupport.startServer(2);   // solo: lone joiner, host-starts
        host = new Client(server.port, "Alice");
        host.awaitJoined();
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        host.raw(start);
        PlayerView v0 = host.awaitView();
        assertEquals(1, v0.turn, "game starts on turn 1");

        // advance two turns in a row
        PlayerView v1 = host.ready();
        assertEquals(2, v1.turn, "first Ready advances to turn 2");
        assertFalse(terminalStatusProcessing(host, 2), "turn 2 ends with processing=false (button re-enabled)");

        PlayerView v2 = host.ready();
        assertEquals(3, v2.turn, "second Ready advances to turn 3");
        assertFalse(terminalStatusProcessing(host, 3), "turn 3 ends with processing=false (button re-enabled)");
    }

    /** the processing flag of the final TurnStatus seen for the given turn */
    private static boolean terminalStatusProcessing(Client c, int turn) throws Exception {
        Messages.TurnStatus terminal = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Messages.TurnStatus ts = c.turnStatuses.poll(2, TimeUnit.SECONDS);
            if (ts == null)
                break;
            if (ts.turn == turn)
                terminal = ts;   // keep the latest one for this turn
        }
        assertNotNull(terminal, "a TurnStatus for turn " + turn + " arrived");
        return terminal.processing;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
