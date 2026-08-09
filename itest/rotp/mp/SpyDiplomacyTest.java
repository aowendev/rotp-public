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

import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Internal security, spy networks, and diplomacy. First contact with an AI empire is
 * established deterministically through the in-process engine (the server runs in this
 * JVM): scouting to contact is geography-dependent (unseeded RNG), slow, and — on the
 * ~120-turn miss path — could leave the shared engine wedged and cascade-fail every test
 * ordered after this one. Forcing contact keeps the whole round-trip fast and reliable.
 */
public class SpyDiplomacyTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    /** the first live empire that is not the given one, or null */
    private static Empire firstForeignEmpire(Empire self) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != self) && !e.extinct())
                return e;
        return null;
    }

    /**
     * Phase 4: an empire you meet *via* its war declaration used to report only
     * CONTACT — the relationship diff skipped brand-new contacts entirely, so the
     * war never surfaced as news. Both must arrive from the same turn's diff.
     */
    @Test
    @Timeout(120)
    void meetingAnEmpireByItsWarDeclarationReportsTheWarTooNotJustTheContact() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire aggressor = firstForeignEmpire(human);
        assumeTrue(aggressor != null, "no other empire in this game");

        // contact and war land in the same turn, as they do when an empire's first
        // act on meeting you is to declare war
        MpTestSupport.drain(alice.notifications);
        human.makeContact(aggressor);
        aggressor.makeContact(human);
        aggressor.viewForEmpire(human).embassy().declareWar();

        alice.ready();
        java.util.List<Messages.Notification> notes = MpTestSupport.allNotifications(alice);
        assertTrue(MpTestSupport.hasCategory(notes, "CONTACT"), "first contact reported");
        assertTrue(MpTestSupport.hasCategory(notes, "DIPLOMACY"),
            "the war that came with the contact is reported too");
        assertTrue(MpTestSupport.empire(alice.lastView, aggressor.id).atWar, "view shows the war");
    }

    @Test
    @Timeout(120)
    void aHumanCanSetAnEspionageFramePreference() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
            "framing needs a third empire to blame");

        // establish contact with a spy target (empire 1) and a scapegoat (empire 2)
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire target = GameSession.instance().galaxy().empire(1);
        Empire scapegoat = GameSession.instance().galaxy().empire(2);
        for (Empire e : new Empire[]{target, scapegoat}) {
            human.makeContact(e);
            e.makeContact(human);
        }
        alice.ready();

        // set a standing frame preference: blame the scapegoat for espionage vs the target
        Messages.SetSpyFrame sf = new Messages.SetSpyFrame();
        sf.empireId = target.id;
        sf.frameEmpireId = scapegoat.id;
        assertTrue(alice.order(sf).ok, "framing preference accepted");
        assertEquals(scapegoat.id, MpTestSupport.empire(alice.lastView, target.id).spyFrameEmpireId,
            "the frame preference is reflected in the view");

        // validation: can't frame yourself or the empire you're spying on
        Messages.SetSpyFrame self = new Messages.SetSpyFrame();
        self.empireId = target.id; self.frameEmpireId = human.id;
        assertFalse(alice.order(self).ok, "cannot frame yourself");
        Messages.SetSpyFrame sameAsTarget = new Messages.SetSpyFrame();
        sameAsTarget.empireId = target.id; sameAsTarget.frameEmpireId = target.id;
        assertFalse(alice.order(sameAsTarget).ok, "cannot frame the empire you are spying on");

        // clearing the preference (-1 = frame no one)
        sf.frameEmpireId = -1;
        assertTrue(alice.order(sf).ok, "clearing the frame preference accepted");
        assertEquals(-1, MpTestSupport.empire(alice.lastView, target.id).spyFrameEmpireId,
            "the cleared preference is reflected in the view");
    }

    @Test
    @Timeout(120)
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

        // establish first contact deterministically through the in-process engine
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = firstForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game; skipping spy/diplomacy round-trips");
        human.makeContact(other);
        other.makeContact(human);
        int otherId = other.id;

        // the contact still surfaces as a CONTACT notification on the next turn's diff
        v = alice.ready();
        assertTrue(MpTestSupport.sawNotification(alice, "CONTACT"),
            "first contact delivered as a CONTACT notification");

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
