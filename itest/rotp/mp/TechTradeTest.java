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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.model.tech.Tech;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * The rest of the MOO1 audience screen (Phase 4): technology exchange with
 * counter-offers, aid, and threats.
 *
 * A tech trade is a *negotiation*, not a single order — you ask for a tech, they
 * name a price in techs of your own, you pay it or walk away — so it needs a
 * round trip (requestTech -> techCounterOffer -> counterOfferTech) rather than
 * one command. The critical multiplayer property is that a request aimed at
 * another *human* defers to their prompt instead of being answered by their AI,
 * the same rule Phase 3 established for incoming treaty offers.
 */
public class TechTradeTest {
    private Server server;
    private Client alice;
    private Client bob;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (server != null) server.stop();
    }

    /** the live empire nearest this one — the likeliest to be within the engine's
     * economic range, which every diplomatic exchange is gated on */
    private static Empire nearestForeignEmpire(Empire self) {
        Empire best = null;
        float bestDist = Float.MAX_VALUE;
        for (Empire e : GameSession.instance().galaxy().empires()) {
            if ((e == self) || e.extinct())
                continue;
            for (StarSystem sys : e.allColonizedSystems()) {
                float d = self.sv.distance(sys.id);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        }
        return best;
    }

    /**
     * Climb the whole fuel-range ladder. Every diplomatic exchange — a trade, a
     * gift, a threat — is gated on the engine's *economic range*, which compares
     * the distance to their colonies against scout range. Two empires can be in
     * contact yet out of economic range, and star placement is unseeded, so tests
     * buy the range outright rather than depend on where the stars fell.
     */
    private static void extendRange(Empire e) {
        for (int pass = 0; pass < 8; pass++) {
            boolean learned = false;
            for (String id : new ArrayList<>(e.tech().propulsion().techIdsAvailableForResearch())) {
                if (e.tech().tech(id).isFuelRangeTech()) {
                    e.tech().learnTech(id);
                    learned = true;
                }
            }
            if (!learned)
                return;
        }
    }

    /**
     * Plant a colony for `settler` on the uncolonized system nearest `neighbour`,
     * so the two are unambiguously within economic range. Star placement is
     * unseeded and two homeworlds can land far enough apart that no amount of fuel
     * technology closes the gap — which would silently skip the test that matters
     * most here (a tech request aimed at a human must wait for that human).
     * Returns false if the galaxy has nowhere to put one.
     */
    private static boolean settleNextDoor(Empire settler, Empire neighbour) {
        List<StarSystem> theirs = neighbour.allColonizedSystems();
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

    /**
     * Put two empires on speaking terms. Meeting an empire in a real game means
     * being near it, so this also reveals each side's colonies to the other —
     * every trade, gift and threat is gated on the engine's economic-range check,
     * which measures fog-of-war distance to their colonies.
     */
    private static void contact(Empire a, Empire b) {
        a.makeContact(b);
        b.makeContact(a);
        for (StarSystem sys : b.allColonizedSystems())
            a.sv.refreshFullScan(sys.id);
        for (StarSystem sys : a.allColonizedSystems())
            b.sv.refreshFullScan(sys.id);
    }

    /**
     * Teach `learner` some technologies it could research next, and let `observer`'s
     * spies know about them — a tech trade menu is built from what your spies have
     * identified, so without this there is nothing either side knows to ask for.
     * Returns the ids taught.
     */
    private static List<String> teachAndReveal(Empire learner, Empire observer, int perCategory) {
        List<String> taught = new ArrayList<>();
        for (int cat = 0; cat < 6; cat++) {
            List<String> available = learner.tech().category(cat).techIdsAvailableForResearch();
            for (int i = 0; (i < perCategory) && (i < available.size()); i++) {
                String id = available.get(i);
                learner.tech().learnTech(id);
                observer.viewForEmpire(learner).spies().tech().learnTech(id);
                taught.add(id);
            }
        }
        return taught;
    }

    @Test
    @Timeout(120)
    void theAudienceMenuListsOnlyWhatTheServerWouldAccept() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = nearestForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game");
        extendRange(human);
        extendRange(other);
        contact(human, other);
        human.addToTreasury(5000);           // enough reserve to have money to give
        teachAndReveal(human, other, 1);     // techs of ours we could gift

        Messages.DiploOptions ask = new Messages.DiploOptions();
        ask.empireId = other.id;
        alice.raw(ask);
        Messages.TechTradeMenu menu = alice.diploMenus.poll(30, TimeUnit.SECONDS);
        assertNotNull(menu, "the server answers with a diplomatic menu");
        assertEquals(other.id, menu.empireId, "menu is for the empire asked about");
        assertFalse(menu.aidAmounts.isEmpty(), "a full reserve means money to offer");
        assertFalse(menu.canGift.isEmpty(), "techs they lack are ours to give");
        for (Messages.TechOption o : menu.canGift) {
            assertNotNull(o.id, "each option carries its tech id");
            assertNotNull(o.name, "each option carries a display name");
            assertTrue(o.cost > 0, "each option carries its research cost");
        }

        // an empire we have not met has no menu at all
        Messages.DiploOptions stranger = new Messages.DiploOptions();
        stranger.empireId = 999;
        alice.raw(stranger);
        assertNotNull(alice.awaitError(), "no menu for an empire we have not contacted");
    }

    @Test
    @Timeout(120)
    void aidMovesMoneyAndTechnologyToTheOtherEmpire() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = nearestForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game");
        extendRange(human);
        extendRange(other);
        settleNextDoor(other, human);
        contact(human, other);
        human.addToTreasury(5000);
        List<String> ourTechs = teachAndReveal(human, other, 1);
        assumeTrue(!ourTechs.isEmpty(), "no researchable techs to gift");

        assumeTrue(human.diplomatAI().canOfferAid(other),
            "this galaxy put them out of economic range - no aid is possible");
        int amount = human.diplomatAI().offerAidAmounts().get(0);
        float theirsBefore = other.totalReserve();
        Messages.OfferAid money = new Messages.OfferAid();
        money.empireId = other.id;
        money.amount = amount;
        assertTrue(alice.order(money).ok, "financial aid accepted");
        assertNotNull(alice.awaitReply(), "they answer the gift");
        assertEquals(theirsBefore + amount, other.totalReserve(), 1.0, "the money arrived");

        // gift a tech from the set the engine says is ours to give (it offers the
        // five most valuable, not everything we know)
        List<Tech> giftable = human.diplomatAI().offerableTechnologies(other);
        assumeTrue(!giftable.isEmpty(), "nothing of ours is worth giving them");
        String gift = giftable.get(0).id();
        Messages.OfferAid tech = new Messages.OfferAid();
        tech.empireId = other.id;
        tech.techId = gift;
        Messages.CommandResult r = alice.order(tech);
        assertTrue(r.ok, "technology aid accepted: " + r.text);
        assertNotNull(alice.awaitReply(), "they answer the gift");
        // a traded tech is *recorded* on the receiver and learned when the turn
        // resolves (TechTree.acquireTradedTechs), so it is not known instantly
        assertTrue(other.tech().tradedTechs().contains(gift)
                || other.tech().knows(rotp.model.tech.TechLibrary.current().tech(gift)),
            "the gift was recorded against their empire");
        alice.ready();
        assertTrue(other.tech().knows(rotp.model.tech.TechLibrary.current().tech(gift)),
            "they learned the gifted technology once the turn resolved");
    }

    @Test
    @Timeout(120)
    void aidAndThreatsAreValidatedBeforeTheyReachTheEngine() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = nearestForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game");
        extendRange(human);
        extendRange(other);
        contact(human, other);
        human.addToTreasury(5000);
        teachAndReveal(human, other, 1);

        Messages.OfferAid both = new Messages.OfferAid();
        both.empireId = other.id;
        both.amount = 100;
        both.techId = "TECH_NOT_REAL";
        assertFalse(alice.order(both).ok, "money and a tech in one gift is rejected");

        Messages.OfferAid neither = new Messages.OfferAid();
        neither.empireId = other.id;
        assertFalse(alice.order(neither).ok, "an empty gift is rejected");

        Messages.OfferAid odd = new Messages.OfferAid();
        odd.empireId = other.id;
        odd.amount = 37;            // not one of the offerable amounts
        assertFalse(alice.order(odd).ok, "an arbitrary amount is rejected");

        Messages.OfferAid notOurs = new Messages.OfferAid();
        notOurs.empireId = other.id;
        notOurs.techId = "TECH_NOT_REAL";
        assertFalse(alice.order(notOurs).ok, "a technology we do not have is rejected");

        Messages.Threaten nonsense = new Messages.Threaten();
        nonsense.empireId = other.id;
        nonsense.threat = "SEND_BISCUITS";
        assertFalse(alice.order(nonsense).ok, "an unknown threat is rejected");

        Messages.RespondTechRequest unasked = new Messages.RespondTechRequest();
        unasked.requestorId = other.id;
        unasked.counterTechId = "TECH_NOT_REAL";
        assertFalse(alice.order(unasked).ok,
            "answering a request nobody made cannot conjure a trade");
    }

    @Test
    @Timeout(120)
    void aTechExchangeIsANegotiationWithACounterOffer() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = nearestForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game");
        extendRange(human);
        extendRange(other);
        settleNextDoor(other, human);     // keep them inside economic range
        contact(human, other);
        // both sides need something the other wants, and each side's spies must have
        // identified it, before any exchange is possible
        teachAndReveal(other, human, 3);
        teachAndReveal(human, other, 3);

        assumeTrue(human.diplomatAI().canExchangeTechnology(other),
            "this galaxy put them out of economic range - no exchange is possible");
        List<Tech> askable = human.diplomatAI().techsAvailableForRequest(other);
        assumeTrue(!askable.isEmpty(), "this galaxy offers no tradeable technology pair");
        Tech wanted = askable.get(0);

        Messages.RequestTech req = new Messages.RequestTech();
        req.empireId = other.id;
        req.techId = wanted.id();
        Messages.CommandResult sent = alice.order(req);
        assertTrue(sent.ok, "the request is delivered: " + sent.text);

        Messages.TechCounterOffer offer = alice.counterOffers.poll(30, TimeUnit.SECONDS);
        assumeTrue(offer != null, "they refused outright this run (leader/relations dependent)");
        assertEquals(wanted.id(), offer.requestedTechId, "the counter-offer is about what we asked for");
        assertFalse(offer.counterOptions.isEmpty(), "they name a price in our technologies");

        String give = offer.counterOptions.get(0).id;
        Messages.CounterOfferTech close = new Messages.CounterOfferTech();
        close.empireId = other.id;
        close.requestedTechId = wanted.id();
        close.offeredTechId = give;
        assertTrue(alice.order(close).ok, "paying the named price closes the exchange");
        // both sides *record* the trade now and learn it when the turn resolves
        assertTrue(human.tech().tradedTechs().contains(wanted.id()),
            "we are down to receive the technology we asked for");
        assertTrue(other.tech().tradedTechs().contains(give),
            "they are down to receive the technology we paid with");
        alice.ready();
        assertTrue(human.tech().knows(wanted), "we received the technology we asked for");
        assertTrue(other.tech().knows(rotp.model.tech.TechLibrary.current().tech(give)),
            "they received the technology we paid with");

        // and a price they never named is refused
        Messages.CounterOfferTech bogus = new Messages.CounterOfferTech();
        bogus.empireId = other.id;
        bogus.requestedTechId = wanted.id();
        bogus.offeredTechId = "TECH_NOT_REAL";
        assertFalse(alice.order(bogus).ok, "a technology they never asked for is refused");
    }

    /**
     * The multiplayer-critical case. Asking an AI is answered by that AI; asking
     * another human must NOT be — their empire is AI-controlled on the server, so
     * without this the target's AI would trade away their technology for them.
     */
    @Test
    @Timeout(180)
    void aTechRequestAimedAtAnotherHumanWaitsForThatHuman() throws Exception {
        server = MpTestSupport.startServer(2);
        alice = new Client(server.port, "Alice");
        assertNotNull(alice.awaitJoined(), "Alice joined");
        bob = new Client(server.port, "Bob");
        assertNotNull(bob.awaitJoined(), "Bob joined");
        PlayerView av = alice.awaitView();
        PlayerView bv = bob.awaitView();

        Empire aliceEmp = GameSession.instance().galaxy().empire(av.empireId);
        Empire bobEmp = GameSession.instance().galaxy().empire(bv.empireId);
        extendRange(aliceEmp);
        extendRange(bobEmp);
        // two homeworlds can fall anywhere, so give Bob a colony next to Alice
        // rather than let geography decide whether this test runs at all
        assumeTrue(settleNextDoor(bobEmp, aliceEmp), "nowhere in this galaxy to settle");
        contact(aliceEmp, bobEmp);
        teachAndReveal(bobEmp, aliceEmp, 2);
        teachAndReveal(aliceEmp, bobEmp, 2);

        assumeTrue(aliceEmp.diplomatAI().canExchangeTechnology(bobEmp),
            "this galaxy put the two humans out of economic range - no exchange is possible");
        List<Tech> askable = aliceEmp.diplomatAI().techsAvailableForRequest(bobEmp);
        assumeTrue(!askable.isEmpty(), "this galaxy offers no tradeable technology pair");
        Tech wanted = askable.get(0);

        Messages.RequestTech req = new Messages.RequestTech();
        req.empireId = bobEmp.id;
        req.techId = wanted.id();
        Messages.CommandResult sent = alice.order(req);
        assertTrue(sent.ok, "the request is delivered: " + sent.text);

        // Bob is asked; Bob's AI has NOT answered for him
        Messages.Prompt prompt = MpTestSupport.firstPrompt(bob, "INCOMING_TECH_REQUEST");
        assertNotNull(prompt, "Bob is prompted to answer the request himself");
        assertEquals(aliceEmp.id, prompt.empireId, "the prompt names the requestor");
        assertEquals(wanted.id(), prompt.techId, "the prompt names the technology asked for");
        assertNotNull(prompt.choiceIds, "the prompt is self-contained: it carries Bob's price options");
        assertTrue(prompt.choiceIds.length > 0, "Bob has technologies of Alice's he could demand");
        assertNull(alice.counterOffers.poll(1, TimeUnit.SECONDS),
            "no counter-offer arrives from Bob's AI - only Bob can answer");
        assertFalse(aliceEmp.tech().tradedTechs().contains(wanted.id()),
            "nothing traded until Bob answers");

        // Bob names his price; the swap happens
        String demand = prompt.choiceIds[0];
        Messages.RespondTechRequest answer = new Messages.RespondTechRequest();
        answer.requestorId = aliceEmp.id;
        answer.counterTechId = demand;
        Messages.CommandResult answered = bob.order(answer);
        assertTrue(answered.ok, "Bob's answer is accepted: " + answered.text);
        assertTrue(aliceEmp.tech().tradedTechs().contains(wanted.id()),
            "Alice is down to receive the technology she asked for");
        assertTrue(bobEmp.tech().tradedTechs().contains(demand),
            "Bob is down to receive the technology he demanded");

        // the request is consumed: answering twice cannot trade again
        assertFalse(bob.order(answer).ok, "a second answer to the same request is rejected");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
