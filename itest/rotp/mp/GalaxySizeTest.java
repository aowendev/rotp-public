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
 * The lobby lets the host pick the galaxy size before the game starts. The
 * server offers the available sizes on join, and the host's choice on Start
 * overrides the size the server was launched with.
 */
public class GalaxySizeTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(60)
    void serverOffersGalaxySizesOnJoin() throws Exception {
        server = MpTestSupport.startServer(2);            // 2 slots: waits in the lobby
        host = new Client(server.port, "Alice");

        Messages.SizeOptions so = host.sizeOptions.poll(10, TimeUnit.SECONDS);
        assertNotNull(so, "the server offers galaxy sizes on join");
        assertTrue(so.sizes.size() >= 2, "more than one size is selectable");
        assertNotNull(so.selectedId, "a default size is indicated");

        boolean sawTiny = false;
        for (Messages.SizeInfo si : so.sizes) {
            assertNotNull(si.name, "each size has a readable label");
            assertTrue(si.stars > 0, "each size reports a star count");
            if (IGameOptions.SIZE_TINY.equals(si.id))
                sawTiny = true;
        }
        assertTrue(sawTiny, "Tiny is among the offered sizes");
    }

    @Test
    @Timeout(120)
    void hostChosenSizeOverridesTheServerDefault() throws Exception {
        // the harness launches every server at SIZE_TINY (33 systems)
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is the host");

        // host picks a clearly larger galaxy than the tiny default
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        start.galaxySize = IGameOptions.SIZE_MEDIUM;   // 100 systems
        host.raw(start);

        assertNotNull(host.awaitView(), "the game starts with the chosen size");
        int systems = GameSession.instance().galaxy().numStarSystems();
        assertTrue(systems > 60,
            "a medium galaxy has far more systems than the tiny default (got " + systems + ")");
    }

    @Test
    @Timeout(60)
    void anInvalidSizeIsRejected() throws Exception {
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is the host");

        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        start.galaxySize = "SIZE_NOT_REAL";
        host.raw(start);

        Messages.Error err = host.awaitError();
        assertNotNull(err, "an unknown galaxy size is refused");
        assertTrue(err.text.toLowerCase().contains("size"), "the refusal names the problem");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
