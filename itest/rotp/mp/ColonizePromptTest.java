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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Colonize choice (Phase 3): a remote human's colony ship no longer auto-settles on
 * arrival. When it reaches a colonizable, uncolonized system the server raises a
 * COLONIZE prompt; the human settles it with the existing colonize command or leaves
 * the ship in orbit.
 *
 * Whether a colonizable system is in the colony ship's range depends on galaxy geography,
 * so if none is reachable this game the round-trip is skipped (not failed), mirroring the
 * existing colonization/transport test.
 */
public class ColonizePromptTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(240)
    void aColonyShipArrivalPromptsInsteadOfAutoColonizing() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);

        int colonySlot = -1;
        for (PlayerView.DesignDto d : v.designs)
            if (d.colonyShip) colonySlot = d.slot;
        assertTrue(colonySlot >= 0, "found a colony-ship design");

        // send the colony ship outward and wait for a COLONIZE prompt at a settle-able
        // system. It must NOT auto-colonize: no new colony should appear before we act.
        Messages.Prompt prompt = driveColonyShipUntilPrompt(home, colonySlot);
        assumeTrue(prompt != null, "no colonizable system reachable this game; skipping colonize round-trip");
        assertTrue(prompt.systemId >= 0, "the prompt names the system to settle");
        assertNotNull(prompt.text, "the prompt carries human-readable text");
        assertTrue(!isColonyOf(alice.lastView, prompt.systemId),
            "the ship did not auto-colonize; the system is still unsettled pending our choice");

        // accept: settle the system via the existing colonize command
        Messages.Colonize col = new Messages.Colonize();
        col.systemId = prompt.systemId;
        assertTrue(alice.order(col).ok, "settling the prompted system is accepted");

        // the system is now our colony
        boolean settled = false;
        for (int t = 0; t < 3 && !settled; t++) {
            settled = isColonyOf(alice.lastView, prompt.systemId);
            if (!settled) alice.ready();
        }
        assertTrue(settled, "accepting the prompt colonized the system");
    }

    /** returns the system's colony detail if it is one of our colonies, else null */
    private static boolean isColonyOf(PlayerView v, int sysId) {
        for (PlayerView.SystemDto s : v.systems)
            if ((s.id == sysId) && (s.colony != null))
                return true;
        return false;
    }

    /**
     * Deploy the colony ship toward nearby systems, advancing turns, until a COLONIZE
     * prompt arrives (the ship reached a colonizable system and did not auto-settle).
     * Returns the prompt, or null if no colonizable system was reached.
     */
    private Messages.Prompt driveColonyShipUntilPrompt(PlayerView.SystemDto home, int colonySlot) throws Exception {
        PlayerView v = alice.lastView;
        int fleetAt = home.id;
        int attempts = 0;
        for (PlayerView.SystemDto target : MpTestSupport.nearestOthers(v, home)) {
            if (attempts++ >= 5)
                break;
            Messages.DeployFleet df = new Messages.DeployFleet();
            df.fromSystemId = fleetAt;
            df.destSystemId = target.id;
            if (!alice.order(df).ok)
                continue;
            boolean parked = false;
            for (int t = 0; t < 12 && !parked; t++) {
                v = alice.ready();
                Messages.Prompt p = MpTestSupport.firstPrompt(alice, "COLONIZE");
                if (p != null)
                    return p;   // arrived at a colonizable system -> prompted, not settled
                for (PlayerView.FleetDto f : v.fleets)
                    if ((f.atSystemId == target.id) && (f.counts[colonySlot] > 0))
                        parked = true;
            }
            if (!parked)
                return null;   // colony ship lost en route
            fleetAt = target.id;   // parked but not colonizable; try the next system
        }
        return null;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
