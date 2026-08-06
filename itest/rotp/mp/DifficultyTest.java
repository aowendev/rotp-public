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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.game.GameSession;
import rotp.model.game.IGameOptions;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;

/**
 * The lobby lets the host set the difficulty, which in ROTP is really the AI's
 * ability: each level scales the AI's economy, so a higher level is a stronger
 * opponent. The server offers the levels (with the AI's production strength) and
 * applies the host's choice at Start.
 */
public class DifficultyTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(60)
    void serverOffersAiAbilityLevelsOnJoin() throws Exception {
        server = MpTestSupport.startServer(2);            // 2 slots: waits in the lobby
        host = new Client(server.port, "Alice");

        Messages.DifficultyOptions dop = host.difficultyOptions.poll(10, TimeUnit.SECONDS);
        assertNotNull(dop, "the server offers difficulty (AI ability) levels on join");
        assertTrue(dop.levels.size() >= 2, "more than one level is selectable");
        assertNotNull(dop.selectedId, "a default level is indicated");

        int normalPct = -1;
        int hardestPct = -1;
        for (Messages.DifficultyInfo di : dop.levels) {
            assertNotNull(di.name, "each level has a readable label");
            assertTrue(di.aiProductionPct > 0, "each level reports the AI's production strength");
            if (IGameOptions.DIFFICULTY_NORMAL.equals(di.id))  normalPct = di.aiProductionPct;
            if (IGameOptions.DIFFICULTY_HARDEST.equals(di.id)) hardestPct = di.aiProductionPct;
        }
        assertEquals(100, normalPct, "Normal means the AI produces at parity (100%)");
        assertTrue(hardestPct > normalPct,
            "a higher level is a stronger AI (Hardest " + hardestPct + "% > Normal " + normalPct + "%)");
    }

    @Test
    @Timeout(120)
    void hostChosenDifficultyIsApplied() throws Exception {
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is the host");

        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        start.difficulty = IGameOptions.DIFFICULTY_HARDEST;
        host.raw(start);

        assertNotNull(host.awaitView(), "the game starts with the chosen difficulty");
        assertEquals(IGameOptions.DIFFICULTY_HARDEST,
            GameSession.instance().options().selectedGameDifficulty(),
            "the AI ability the host picked is the difficulty the game runs at");
    }

    @Test
    @Timeout(60)
    void anInvalidDifficultyIsRejected() throws Exception {
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is the host");

        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        start.difficulty = "DIFFICULTY_NOT_REAL";
        host.raw(start);

        Messages.Error err = host.awaitError();
        assertNotNull(err, "an unknown difficulty is refused");
        assertTrue(err.text.toLowerCase().contains("difficulty"), "the refusal names the problem");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
