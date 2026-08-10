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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Joint war: asking another empire to fight a third.
 *
 * The last diplomatic action to reach the wire. Two halves. Outgoing, they may
 * agree, refuse, or **name a price** in technologies and BC — a counter-offer,
 * which is the part that makes this more than a single order. Incoming, an offer
 * aimed at a human must wait for that human rather than being answered by their
 * AI, the rule every other diplomatic approach follows.
 */
public class JointWarTest {
    private Server server;
    private Client alice, bob;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (server != null) server.stop();
    }

    /**
     * Everyone meets everyone, and everyone can reach everyone. `nonEnemiesKnownBy`
     * — which decides who can be set against whom — requires **economic range on both
     * sides**, so contact alone is not enough. Buy the range by climbing the whole
     * fuel ladder rather than depending on where the stars fell.
     */
    private static void meetAll() {
        for (Empire e : GameSession.instance().galaxy().empires()) {
            if (!e.extinct())
                extendRange(e);
        }
        for (Empire a : GameSession.instance().galaxy().empires()) {
            if (a.extinct())
                continue;
            for (Empire b : GameSession.instance().galaxy().empires()) {
                if ((b == a) || b.extinct())
                    continue;
                a.makeContact(b);
                for (StarSystem sys : b.allColonizedSystems())
                    a.sv.refreshFullScan(sys.id);
            }
        }
    }

    /**
     * Plant a colony for `settler` on the uncolonized system nearest `neighbour`.
     * Even a maxed fuel ladder does not guarantee two empires are within economic
     * range on an unseeded galaxy, and this test needs a third empire reachable by
     * *both* humans — otherwise there is nobody to be set against and the case that
     * matters never runs.
     */
    private static boolean settleNextDoor(Empire settler, Empire neighbour) {
        java.util.List<StarSystem> theirs = neighbour.allColonizedSystems();
        if (theirs.isEmpty())
            return false;
        StarSystem home = theirs.get(0);
        StarSystem best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < GameSession.instance().galaxy().numStarSystems(); i++) {
            StarSystem sys = GameSession.instance().galaxy().system(i);
            if (sys.isColonized() || (sys.planet() == null))
                continue;
            float d = home.distanceTo(sys);
            if (d < bestDist) {
                bestDist = d;
                best = sys;
            }
        }
        if (best == null)
            return false;
        settler.colonize(best.name(), best);
        return true;
    }

    /** learn every fuel-range technology, so economic range stops being a lottery */
    private static void extendRange(Empire e) {
        for (int pass = 0; pass < 8; pass++) {
            boolean learned = false;
            for (String id : new java.util.ArrayList<>(e.tech().propulsion().techIdsAvailableForResearch())) {
                if (e.tech().tech(id).isFuelRangeTech()) {
                    e.tech().learnTech(id);
                    learned = true;
                }
            }
            if (!learned)
                return;
        }
    }

    @Test
    @Timeout(120)
    void theMenuOffersTheEmpiresTheyCouldBeAskedToFight() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
            "a joint war needs a third empire to fight");
        Empire me = GameSession.instance().galaxy().empire(v.empireId);
        // pick from the galaxy, not the view: the view was built before contact
        Empire ally = firstForeign(me);
        assumeTrue(ally != null, "no other empire");
        // and give the third empire a foothold beside both, so it is in economic
        // range of each — otherwise there is nobody the ally could be set against
        // and this case silently does not run
        Empire third = null;
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != me) && (e != ally) && !e.extinct()) {
                third = e;
                break;
            }
        assumeTrue(third != null, "no third empire in this galaxy");
        settleNextDoor(third, me);
        settleNextDoor(third, ally);
        meetAll();

        Messages.DiploOptions ask = new Messages.DiploOptions();
        ask.empireId = ally.id;
        alice.raw(ask);
        Messages.TechTradeMenu menu = alice.diploMenus.poll(30, TimeUnit.SECONDS);
        assertNotNull(menu, "the diplomatic menu arrives");
        assertFalse(menu.jointWarTargets.isEmpty(),
            "the menu offers the third empire as a joint-war target");
        for (Messages.EmpireOption t : menu.jointWarTargets) {
            assertNotNull(t.name, "each target is named");
            assertFalse(t.id == menu.empireId, "they are not offered war on themselves");
            assertFalse(t.id == v.empireId, "nor on you");
        }
    }

    @Test
    @Timeout(120)
    void nonsenseTargetsAreRejected() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire me = GameSession.instance().galaxy().empire(v.empireId);
        meetAll();
        Empire other = firstForeign(me);
        assumeTrue(other != null, "no other empire");

        Messages.OfferJointWar atMe = new Messages.OfferJointWar();
        atMe.empireId = other.id;
        atMe.targetId = v.empireId;
        assertFalse(alice.order(atMe).ok, "you cannot ask them to declare war on you");

        Messages.OfferJointWar nobody = new Messages.OfferJointWar();
        nobody.empireId = other.id;
        nobody.targetId = 999;
        assertFalse(alice.order(nobody).ok, "nor on an empire that does not exist");

        Messages.AcceptJointWarCounter unasked = new Messages.AcceptJointWarCounter();
        unasked.empireId = other.id;
        unasked.targetId = 1;
        assertFalse(alice.order(unasked).ok,
            "paying a price nobody named cannot buy a war");
    }

    /**
     * The multiplayer-critical half: a human must answer for themselves. Their
     * empire is AI-controlled on the server, so without the deferral their AI would
     * commit them to someone else's war.
     */
    @Test
    @Timeout(180)
    void anOfferAimedAtAHumanWaitsForThatHuman() throws Exception {
        server = MpTestSupport.startServer(2);
        alice = new Client(server.port, "Alice");
        assertEquals(0, alice.awaitJoined().empireId, "Alice is empire 0");
        bob = new Client(server.port, "Bob");
        assertEquals(1, bob.awaitJoined().empireId, "Bob is empire 1");
        alice.awaitView();
        bob.awaitView();
        assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
            "a joint war needs a third empire to fight");

        Empire aliceEmp = GameSession.instance().galaxy().empire(0);
        Empire bobEmp = GameSession.instance().galaxy().empire(1);
        // give the third empire a foothold beside each human, so it is within
        // economic range of both and can actually be the subject of a joint war
        Empire third = null;
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != aliceEmp) && (e != bobEmp) && !e.extinct()) {
                third = e;
                break;
            }
        assumeTrue(third != null, "no third empire in this galaxy");
        settleNextDoor(third, aliceEmp);
        settleNextDoor(third, bobEmp);
        meetAll();

        Empire target = null;
        for (Empire e : bobEmp.nonEnemiesKnownBy(aliceEmp))
            if ((e != aliceEmp) && (e != bobEmp)) {
                target = e;
                break;
            }
        assumeTrue(target != null, "no empire Bob could be set against"
            + " (active=" + GameSession.instance().galaxy().numActiveEmpires()
            + ", bobContacts=" + bobEmp.contactedEmpires().size()
            + ", nonEnemies=" + bobEmp.nonEnemiesKnownBy(aliceEmp).size() + ")");

        Messages.OfferJointWar offer = new Messages.OfferJointWar();
        offer.empireId = bobEmp.id;
        offer.targetId = target.id;
        assertTrue(alice.order(offer).ok, "the offer is delivered");

        // Bob's AI must not have answered it
        assertNull(alice.replies.poll(1, TimeUnit.SECONDS),
            "no verdict comes back - only Bob can answer");
        assertFalse(bobEmp.atWarWith(target.id), "Bob is not committed until he agrees");

        Messages.Prompt prompt = null;
        for (int t = 0; (t < 2) && (prompt == null); t++) {
            alice.raw(readyMessage());
            bob.ready();
            prompt = MpTestSupport.firstPrompt(bob, "INCOMING_DIPLOMACY");
        }
        assertNotNull(prompt, "Bob is asked to answer for himself");
        assertEquals("JOINT_WAR", prompt.action, "and told what kind of offer it is");
        assertEquals(aliceEmp.id, prompt.empireId, "who asked");
        assertEquals(target.id, prompt.targetEmpireId, "and who they want him to fight");
    }

    private static Empire firstForeign(Empire self) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != self) && !e.extinct())
                return e;
        return null;
    }

    private static Messages.Ready readyMessage() {
        Messages.Ready r = new Messages.Ready();
        r.ready = true;
        return r;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
