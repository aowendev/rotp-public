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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/** Ship design lifecycle, colonization, and population transports over the wire. */
public class ShipDesignTransportTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    private PlayerView start() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        return alice.awaitView();
    }

    @Test
    @Timeout(180)
    void shipDesignLifecycle() throws Exception {
        PlayerView v = start();
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);

        // design catalog
        alice.raw(new Messages.DesignCatalog());
        Messages.DesignCatalog cat = alice.catalogs.poll(30, TimeUnit.SECONDS);
        assertNotNull(cat, "design catalog received");
        assertFalse(cat.weapons.isEmpty(), "catalog lists weapons");
        String weapon = firstNonEmpty(cat.weapons);
        assertNotNull(weapon, "catalog has a usable weapon");

        boolean[] used = new boolean[6];
        for (PlayerView.DesignDto d : v.designs)
            used[d.slot] = true;
        int freeSlot = -1;
        for (int i = 0; i < 6; i++)
            if (!used[i]) { freeSlot = i; break; }
        assertTrue(freeSlot >= 0, "found a free design slot");

        // negatives while the slot is empty
        Messages.CreateDesign tooBig = new Messages.CreateDesign();
        tooBig.slot = freeSlot;
        tooBig.size = 0;                         // small hull, 40 space
        tooBig.weapons = new String[]{weapon};
        tooBig.weaponCounts = new int[]{99};     // vastly over budget
        assertFalse(alice.order(tooBig).ok, "over-space design rejected");

        Messages.CreateDesign bogus = new Messages.CreateDesign();
        bogus.slot = freeSlot;
        bogus.size = 1;
        bogus.weapons = new String[]{"Death Ray 9000"};
        bogus.weaponCounts = new int[]{1};
        assertFalse(alice.order(bogus).ok, "unknown component rejected");

        // create a real design (medium hull fits a starting weapon)
        Messages.CreateDesign cd = new Messages.CreateDesign();
        cd.slot = freeSlot;
        cd.name = "Wasp";
        cd.size = 1;
        cd.weapons = new String[]{weapon};
        cd.weaponCounts = new int[]{1};
        assertTrue(alice.order(cd).ok, "design created");
        PlayerView.DesignDto wasp = design(alice.lastView, freeSlot);
        assertNotNull(wasp, "design appears in view");
        assertEquals("Wasp", wasp.name, "design name reflected");
        assertTrue(wasp.availableSpace >= 0, "design fits its hull");

        // occupied slot rejected
        assertFalse(alice.order(cd).ok, "creating into an occupied slot rejected");

        // build it
        Messages.SetShipBuild sb = new Messages.SetShipBuild();
        sb.systemId = home.id;
        sb.designSlot = freeSlot;
        assertTrue(alice.order(sb).ok, "colony set to build the design");
        assertEquals("Wasp", MpTestSupport.ownColony(alice.lastView).colony.shipyardDesign,
            "shipyard design reflected in view");

        Messages.SetColonyAllocations ca = new Messages.SetColonyAllocations();
        ca.systemId = home.id;
        ca.alloc = new int[]{30, 0, 5, 10, 5};   // ship-heavy
        assertTrue(alice.order(ca).ok, "ship-heavy spending set");

        boolean built = false;
        for (int t = 0; t < 8 && !built; t++) {
            v = alice.ready();
            for (PlayerView.FleetDto f : v.fleets)
                if ((f.atSystemId == home.id) && (f.counts[freeSlot] > 0))
                    built = true;
        }
        assertTrue(built, "built ships of the new design appear in the home fleet");

        // scrap it
        Messages.ScrapDesign scrap = new Messages.ScrapDesign();
        scrap.slot = freeSlot;
        assertTrue(alice.order(scrap).ok, "design scrapped");
        v = alice.lastView;
        assertNull(design(v, freeSlot), "scrapped design removed from slots");
        for (PlayerView.FleetDto f : v.fleets)
            assertTrue((f.counts.length <= freeSlot) || (f.counts[freeSlot] == 0),
                "scrapped design's ships removed from fleets");
    }

    @Test
    @Timeout(240)
    void colonizationAndTransports() throws Exception {
        PlayerView v = start();
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);
        int colonySlot = -1;
        for (PlayerView.DesignDto d : v.designs)
            if (d.colonyShip) colonySlot = d.slot;
        assertTrue(colonySlot >= 0, "found a colony-ship design");

        // transport rejections that hold regardless of geography
        Messages.SendTransports sameSys = new Messages.SendTransports();
        sameSys.fromSystemId = home.id;
        sameSys.destSystemId = home.id;
        sameSys.size = 3;
        assertFalse(alice.order(sameSys).ok, "transport to same system rejected");

        Messages.SendTransports toEmpty = new Messages.SendTransports();
        toEmpty.fromSystemId = home.id;
        toEmpty.destSystemId = MpTestSupport.nearestOthers(v, home).get(0).id;
        toEmpty.size = 3;
        assertFalse(alice.order(toEmpty).ok, "transport to uncolonized system rejected");

        // expand: deploy the colony ship; under v1 AI-assist it auto-colonizes on arrival
        int newColony = tryColonize(home, colonySlot);
        assumeTrue(newColony >= 0, "no colonizable system reachable this game; skipping transport delivery");

        v = alice.latestView();
        Messages.SendTransports st = new Messages.SendTransports();
        st.fromSystemId = home.id;
        st.destSystemId = newColony;
        st.size = 3;
        assertTrue(alice.order(st).ok, "transports scheduled to the new colony");
        PlayerView.ColonyDto hc = MpTestSupport.ownColony(alice.lastView).colony;
        assertEquals(3, hc.transportSize, "pending transports visible");
        assertEquals(newColony, hc.transportDestId, "pending transport destination visible");

        Messages.AbortTransports abort = new Messages.AbortTransports();
        abort.fromSystemId = home.id;
        assertTrue(alice.order(abort).ok, "transports aborted");
        assertEquals(0, MpTestSupport.ownColony(alice.lastView).colony.transportSize, "pending transports cleared");

        assertTrue(alice.order(st).ok, "transports re-scheduled");

        boolean delivered = false;
        for (int t = 0; t < 12 && !delivered; t++) {
            v = alice.ready();
            boolean inFlight = !v.transports.isEmpty();
            PlayerView.SystemDto nc = MpTestSupport.system(v, newColony);
            if ((t > 0) && !inFlight && (nc != null) && (nc.colony != null))
                delivered = true;
        }
        assertTrue(delivered, "transports launched, traveled, and arrived");
    }

    /** deploy the colony ship outward; returns the new colony's system id or -1 */
    private int tryColonize(PlayerView.SystemDto home, int colonySlot) throws Exception {
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
                for (PlayerView.SystemDto s : v.systems)
                    if ((s.id != home.id) && (s.colony != null))
                        return s.id;   // auto-colonized on arrival
                for (PlayerView.FleetDto f : v.fleets)
                    if ((f.atSystemId == target.id) && (f.counts[colonySlot] > 0))
                        parked = true;
            }
            if (!parked)
                return -1;   // colony ship lost en route
            // planet not auto-settleable: exercise the explicit command, then move on
            Messages.Colonize col = new Messages.Colonize();
            col.systemId = target.id;
            if (alice.order(col).ok)
                return target.id;
            fleetAt = target.id;
        }
        return -1;
    }

    private static PlayerView.DesignDto design(PlayerView v, int slot) {
        for (PlayerView.DesignDto d : v.designs)
            if (d.slot == slot)
                return d;
        return null;
    }

    private static String firstNonEmpty(List<String> names) {
        for (String n : names)
            if ((n != null) && !n.isEmpty())
                return n;
        return null;
    }

    private static void assertNull(Object o, String msg) {
        org.junit.jupiter.api.Assertions.assertNull(o, msg);
    }
}
