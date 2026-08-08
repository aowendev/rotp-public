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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.PlayerView;

/**
 * Combat / spy alerts (Phase 3): the engine's per-turn {@code GameAlert}s (transports
 * killed/perished, bases/factories sabotaged, tech stolen, spy report, ...) are delivered
 * to the human (empire 0) as ALERT notifications. Previously they were gated on
 * {@code isPlayerControlled()} — never true on the autoplay server — so they never fired
 * and were never delivered; the gates now read {@code isPlayer()} (empire 0).
 *
 * Alerts are generated deep in turn processing, so this drives a real, deterministic
 * event through the in-process engine: empire 0's transports are sent to an uncolonized
 * system, where they perish on arrival, raising a TransportsPerishedAlert.
 */
public class CombatSpyAlertTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    /** the uncolonized star system nearest to the given one, or null */
    private static StarSystem nearestUncolonized(StarSystem from) {
        StarSystem best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < GameSession.instance().galaxy().numStarSystems(); i++) {
            StarSystem s = GameSession.instance().galaxy().system(i);
            if ((s == from) || s.isColonized())
                continue;
            double d = Math.hypot(s.x() - from.x(), s.y() - from.y());
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    @Test
    @Timeout(120)
    void aCombatEventIsDeliveredToTheHumanAsAnAlert() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        assumeTrue(v.empireId == Empire.PLAYER_ID, "combat/spy alerts are framed for and routed to empire 0");

        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        StarSystem home = GameSession.instance().galaxy().system(MpTestSupport.ownColony(v).id);
        StarSystem target = nearestUncolonized(home);
        assumeTrue(target != null, "no uncolonized system to send transports to");

        // send population toward an uncolonized system: the transports perish on arrival,
        // which raises a TransportsPerishedAlert (a combat GameAlert) during that turn
        home.colony().scheduleTransportsToSystem(target, 1);

        boolean sawAlert = false;
        for (int t = 0; t < 25 && !sawAlert; t++) {
            alice.ready();
            if (MpTestSupport.sawNotification(alice, "ALERT"))
                sawAlert = true;
        }
        assertTrue(sawAlert, "the perished transports were delivered as an ALERT notification to the human");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
