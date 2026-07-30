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
import rotp.mp.protocol.PlayerView;

/**
 * The per-system view carries what a scout needs to report: readable planet
 * type, population capacity, and whether the player can colonize it. Verified
 * on the homeworld (a scouted, colonized system the player owns).
 */
public class SystemInfoViewTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void scoutedSystemCarriesPlanetInfo() throws Exception {
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        host.awaitJoined();
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        host.raw(start);
        PlayerView v = host.awaitView();

        int home = GameSession.instance().galaxy().empire(0).homeSysId();
        PlayerView.SystemDto hs = null;
        for (PlayerView.SystemDto s : v.systems)
            if (s.id == home)
                hs = s;
        assertNotNull(hs, "the homeworld is in the view");
        assertTrue(hs.scouted, "the homeworld is scouted");
        assertNotNull(hs.planetTypeName, "planet type name is populated for a scouted system");
        assertTrue(hs.maxSize > 0, "planet capacity is populated: " + hs.maxSize);
        assertFalse(hs.canColonize, "you cannot colonize a system you already own");

        // range info: your own homeworld is in range (distance 0), and each ship
        // design reports how far it can travel (scouts further than colony ships)
        assertTrue(hs.inShipRange, "the homeworld is within your ship range");
        assertFalse(v.designs.isEmpty(), "the player has ship designs");
        for (PlayerView.DesignDto d : v.designs)
            assertTrue(d.range > 0, "design '" + d.name + "' reports a travel range");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
