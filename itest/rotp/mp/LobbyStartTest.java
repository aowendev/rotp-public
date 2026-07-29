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
 * The Phase-2 lobby: the host can start a game before every human slot is
 * filled, and the remaining empires are filled with AI (this is how a lone
 * player plays against AI on a LAN). Only the host may start.
 */
public class LobbyStartTest {
    private Server server;
    private Client host;
    private Client other;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (other != null) other.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void hostStartsSoloGameAgainstAi() throws Exception {
        // server allows up to 2 humans; only one shows up
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");

        Messages.Joined j = host.awaitJoined();
        assertNotNull(j, "joining is acknowledged");
        assertTrue(j.host, "the first player is the host");

        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 2;
        host.raw(start);

        assertNotNull(host.awaitView(), "the game starts and a view arrives");
        // 1 human + 2 AI opponents = 3 empires; the human is empire 0 and remote
        assertEquals(3, GameSession.instance().galaxy().empires().length, "1 human + 2 AI empires");
        assertTrue(GameSession.instance().galaxy().empire(0).isRemoteHuman(), "the human is a remote human");
    }

    @Test
    @Timeout(120)
    void onlyTheHostMayStart() throws Exception {
        // 3 human slots so two joining players do not auto-start the game
        server = MpTestSupport.startServer(3);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is host");
        other = new Client(server.port, "Bob");
        assertFalse(other.awaitJoined().host, "second player is not host");

        // a non-host start attempt is rejected
        Messages.StartGame nonHost = new Messages.StartGame();
        nonHost.aiOpponents = 1;
        other.raw(nonHost);
        Messages.Error err = other.awaitError();
        assertNotNull(err, "non-host start is refused");
        assertTrue(err.text.toLowerCase().contains("host"), "refusal explains only the host can start");

        // the host can start with the two humans present + AI fill
        Messages.StartGame hostStart = new Messages.StartGame();
        hostStart.aiOpponents = 1;
        host.raw(hostStart);
        assertNotNull(host.awaitView(), "host start succeeds");
        assertEquals(3, GameSession.instance().galaxy().empires().length, "2 humans + 1 AI empire");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
