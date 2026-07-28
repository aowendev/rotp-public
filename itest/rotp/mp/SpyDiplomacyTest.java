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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Internal security, spy networks, and diplomacy. The security and
 * pre-contact validation always run; spy-mission and diplomatic-offer
 * round-trips require first contact with an AI empire, which depends on
 * galaxy geography and is skipped (not failed) if it doesn't happen.
 */
public class SpyDiplomacyTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(300)
    void securitySpyAndDiplomacy() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        // internal security (always available)
        Messages.SetSecurity sec = new Messages.SetSecurity();
        sec.allocation = 5;
        assertTrue(alice.order(sec).ok, "internal security set");
        assertEquals(5, alice.lastView.internalSecurity, "security reflected in view");
        sec.allocation = 11;
        assertFalse(alice.order(sec).ok, "out-of-range security rejected");

        // pre-contact validation
        Messages.SetSpySpending spySelf = new Messages.SetSpySpending();
        spySelf.empireId = v.empireId;
        spySelf.allocation = 5;
        assertFalse(alice.order(spySelf).ok, "spy spending against self rejected");

        Messages.DiploOffer offerUnknown = new Messages.DiploOffer();
        offerUnknown.empireId = 999;
        offerUnknown.action = "PACT";
        assertFalse(alice.order(offerUnknown).ok, "offer to unknown empire rejected");

        // actively scout outward until we make first contact
        PlayerView.SystemDto home = MpTestSupport.ownColony(v);
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        visited.add(home.id);
        int otherId = -1;
        boolean sawContactNote = false;
        for (int t = 0; t < 120 && otherId < 0; t++) {
            v = MpTestSupport.explore(alice, v, visited);
            v = alice.ready();
            if (MpTestSupport.sawNotification(alice, "CONTACT"))
                sawContactNote = true;
            PlayerView.EmpireDto o = MpTestSupport.anyForeignEmpire(v);
            if (o != null) otherId = o.id;
        }
        assumeTrue(otherId >= 0, "no first contact within 120 turns this game; skipping spy/diplomacy round-trips");
        assertTrue(sawContactNote, "first contact delivered as a CONTACT notification");

        // spy network vs the contacted empire
        Messages.SetSpySpending ss = new Messages.SetSpySpending();
        ss.empireId = otherId;
        ss.allocation = 10;
        assertTrue(alice.order(ss).ok, "spy spending set against contact");
        assertEquals(10, MpTestSupport.empire(alice.lastView, otherId).spySpending, "spy spending reflected");

        Messages.SetSpyMission sm = new Messages.SetSpyMission();
        sm.empireId = otherId;
        sm.mission = "ESPIONAGE";
        assertTrue(alice.order(sm).ok, "spy mission set");
        assertEquals("ESPIONAGE", MpTestSupport.empire(alice.lastView, otherId).spyMission, "spy mission reflected");
        sm.mission = "BURGLE";
        assertFalse(alice.order(sm).ok, "invalid spy mission rejected");

        // diplomacy: the target's AI answers each offer
        Messages.DiploOffer pact = new Messages.DiploOffer();
        pact.empireId = otherId;
        pact.action = "PACT";
        assertTrue(alice.order(pact).ok, "pact offer delivered");
        assertNotNull(alice.awaitReply(), "diplomatic reply received for pact");

        int maxTrade = MpTestSupport.empire(alice.lastView, otherId).maxTradeLevel;
        Messages.DiploOffer trade = new Messages.DiploOffer();
        trade.empireId = otherId;
        trade.action = "TRADE";
        if (maxTrade > 0) {
            trade.tradeLevel = maxTrade;
            assertTrue(alice.order(trade).ok, "trade offer delivered");
            assertNotNull(alice.awaitReply(), "diplomatic reply received for trade");
        }
        else {
            trade.tradeLevel = 25;
            assertFalse(alice.order(trade).ok, "trade offer above max level rejected");
        }

        // war and peace
        Messages.DeclareWar dw = new Messages.DeclareWar();
        dw.empireId = otherId;
        assertTrue(alice.order(dw).ok, "war declared");
        assertTrue(MpTestSupport.empire(alice.lastView, otherId).atWar, "war state reflected in view");
        assertFalse(alice.order(dw).ok, "duplicate war declaration rejected");

        // the new war becomes news on the next turn
        MpTestSupport.drain(alice.notifications);
        alice.ready();
        assertTrue(MpTestSupport.sawNotification(alice, "DIPLOMACY"),
            "war onset delivered as a DIPLOMACY notification");

        Messages.DiploOffer pactAtWar = new Messages.DiploOffer();
        pactAtWar.empireId = otherId;
        pactAtWar.action = "PACT";
        assertFalse(alice.order(pactAtWar).ok, "pact offer while at war rejected");

        Messages.DiploOffer peace = new Messages.DiploOffer();
        peace.empireId = otherId;
        peace.action = "PEACE";
        assertTrue(alice.order(peace).ok, "peace offer delivered");
        assertNotNull(alice.awaitReply(), "diplomatic reply received for peace");
    }
}
