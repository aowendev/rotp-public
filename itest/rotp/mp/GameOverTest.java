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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;

/**
 * Victory/defeat signaling (Phase 1.5): when the game reaches a win/loss, the
 * server tells the player. The engine's win/loss computation is stock and
 * already tested; here we verify the wire signal by forcing the engine status
 * and resolving a turn.
 */
public class GameOverTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    private void startSoloGame() throws Exception {
        server = MpTestSupport.startServer(2);   // lone joiner does not auto-start
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is host");
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        host.raw(start);
        assertNotNull(host.awaitView(), "game starts");
    }

    @Test
    @Timeout(120)
    void victoryIsSignalled() throws Exception {
        startSoloGame();
        // force a win from empire 0's perspective, then resolve a turn
        GameSession.instance().status().winMilitary();
        host.ready();   // consumes the post-turn view

        Messages.GameOver go = host.awaitGameOver();
        assertNotNull(go, "the player is told the game is over");
        assertTrue(go.won, "it is a victory");
        assertEquals("MILITARY", go.reason, "the win reason is reported");
    }

    @Test
    @Timeout(120)
    void defeatIsSignalled() throws Exception {
        startSoloGame();
        GameSession.instance().status().loseNoColonies();
        host.ready();

        Messages.GameOver go = host.awaitGameOver();
        assertNotNull(go, "the player is told the game is over");
        assertFalse(go.won, "it is a defeat");
        assertEquals("NO_COLONIES", go.reason, "the loss reason is reported");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
