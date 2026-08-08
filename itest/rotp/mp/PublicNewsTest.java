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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.ui.notifications.GNNNotification;

/**
 * Public galactic news (Phase 3): GNN turn-notifications (random galactic events,
 * genocides, alliances, council news, ...) are broadcast to every client as NEWS
 * notifications. Previously the headless server collected them and dropped them, so
 * clients saw no GNN at all.
 *
 * GNN is generated deep in turn processing from geography-dependent events, so the news
 * is injected through the in-process engine (the server runs in this JVM) to make the
 * server -> client broadcast path deterministic.
 */
public class PublicNewsTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(60)
    void gnnNewsIsBroadcastToTheClientAsANewsNotification() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        // queue a piece of GNN news the way the engine does during a turn
        String story = "A rogue comet streaks toward the Test system";
        GNNNotification.notifyRandomEvent(story, "GNN_Test_Event");

        // resolving the turn collects the queued news and broadcasts it as NEWS
        PlayerView after = alice.ready();
        assertNotNull(after, "the turn resolved");
        Messages.Notification news = MpTestSupport.firstNotification(alice, "NEWS");
        assertNotNull(news, "GNN news is delivered to the client as a NEWS notification");
        assertTrue(news.text != null && news.text.contains("comet"),
            "the news carries the galactic story text: " + (news == null ? null : news.text));
    }

    @Test
    @Timeout(60)
    void quietTurnsCarryNoNews() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        // a turn with no GNN queued should not fabricate NEWS
        alice.ready();
        // (drain once; a plain early-game turn generates no galactic news)
        assertTrue(MpTestSupport.firstNotification(alice, "NEWS") == null,
            "no NEWS notification appears on a quiet turn");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
