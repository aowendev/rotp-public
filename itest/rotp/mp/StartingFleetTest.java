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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.galaxy.ShipFleet;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * A remote human keeps manual control of the opening move: ROTP's turn-1
 * scout auto-launch is recalled so the starting fleet sits in orbit at the
 * homeworld, ready to dispatch as the player chooses (e.g. the colony ship).
 */
public class StartingFleetTest {
    private Server server;
    private Client host;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void startingScoutsAreNotAutoLaunched() throws Exception {
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        host.awaitJoined();
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        host.raw(start);
        PlayerView v = host.awaitView();

        // the client sees no fleet in transit: every fleet is orbiting a system
        assertFalse(v.fleets.isEmpty(), "the player has a starting fleet");
        for (PlayerView.FleetDto f : v.fleets) {
            assertTrue(f.atSystemId >= 0, "fleet is orbiting, not in transit");
            assertEquals(-1, f.destSystemId, "fleet has no auto-assigned destination");
        }

        // and on the server: all the starting ships sit in orbit at the homeworld
        Empire emp = GameSession.instance().galaxy().empire(0);
        int home = emp.homeSysId();
        List<ShipFleet> fleets = GameSession.instance().galaxy().ships.allFleets(emp.id);
        int totalShips = 0;
        for (ShipFleet f : fleets) {
            assertFalse(f.launched(), "no starting fleet has launched");
            assertFalse(f.deployed(), "no starting fleet is deployed");
            assertTrue(f.inOrbit(), "starting fleet is in orbit");
            assertEquals(home, f.sysId(), "starting fleet orbits the homeworld");
            totalShips += f.numShips();
        }
        assertTrue(totalShips >= 3, "scouts and colony ship are all still present: " + totalShips);
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
