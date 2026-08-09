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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.empires.EspionageMission;
import rotp.model.empires.Spy;
import rotp.model.game.GameSession;
import rotp.model.tech.Tech;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Choosing which technology a successful espionage mission steals.
 *
 * MOO1 asks the player for a category. Single-player does it by blocking turn
 * processing on a modal panel, which a server cannot do — so a remote human's
 * mission is parked, raised as a STEAL_TECH prompt, and completed when they
 * answer. Left unanswered it is settled with their own AI's pick when the turn
 * resolves: the theft already happened, and silently dropping it would be worse
 * than an unchosen tech.
 */
public class StealTechPromptTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    /** a successful espionage mission against `victim`, with its tech unchosen */
    private static EspionageMission deferredMission(Empire thief, Empire victim) {
        rotp.model.empires.EmpireView ev = thief.viewForEmpire(victim);
        assumeTrue(ev != null, "no view of the victim");
        List<Tech> stealable = new ArrayList<>();
        for (int cat = 0; cat < 6; cat++) {
            List<String> avail = victim.tech().category(cat).techIdsAvailableForResearch();
            for (int i = 0; (i < 2) && (i < avail.size()); i++) {
                victim.tech().learnTech(avail.get(i));
                stealable.add(rotp.model.tech.TechLibrary.current().tech(avail.get(i)));
            }
        }
        assumeTrue(stealable.size() >= 2, "not enough stealable technology");
        Spy spy = new Spy(ev.spies());
        return new EspionageMission(ev.spies(), spy, stealable,
            victim.allColonizedSystems().get(0), stealable);
    }

    @Test
    @Timeout(120)
    void theHumanChoosesWhatTheirSpiesTake() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire thief = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(thief);
        assumeTrue(victim != null, "no other empire");
        thief.makeContact(victim);
        victim.makeContact(thief);

        EspionageMission m = deferredMission(thief, victim);
        assertFalse(m.hasStolenTech(), "the mission starts with nothing chosen");
        rotp.ui.notifications.StealTechNotification.createDeferred(m, victim.id, null);

        Messages.Prompt prompt = null;
        for (int t = 0; (t < 2) && (prompt == null); t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "STEAL_TECH");
        }
        assertNotNull(prompt, "the human is asked what to steal");
        assertEquals(victim.id, prompt.empireId, "the prompt names the victim");
        assertTrue(prompt.choiceIds.length > 0, "it offers categories to steal from");
        assertEquals(prompt.choiceIds.length, prompt.choiceNames.length,
            "each category shows the technology it would yield");
    }

    @Test
    @Timeout(120)
    void anUnansweredTheftIsSettledRatherThanLost() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire thief = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(thief);
        assumeTrue(victim != null, "no other empire");
        thief.makeContact(victim);
        victim.makeContact(thief);

        EspionageMission m = deferredMission(thief, victim);
        rotp.ui.notifications.StealTechNotification.createDeferred(m, victim.id, null);

        // raise the prompt, ignore it, then let a turn resolve
        for (int t = 0; t < 2; t++)
            alice.ready();
        alice.ready();

        assertTrue(m.hasStolenTech(),
            "the theft is completed with the AI's pick rather than silently dropped");
    }

    @Test
    @Timeout(120)
    void aStealAnswerWithoutAMissionIsRejected() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire thief = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(thief);
        assumeTrue(victim != null, "no other empire");

        Messages.StealTech unasked = new Messages.StealTech();
        unasked.empireId = victim.id;
        unasked.categoryId = "TECH_COMPUTERS";
        assertFalse(alice.order(unasked).ok,
            "answering a theft nobody committed cannot conjure a technology");
    }

    private static Empire firstForeign(Empire self) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != self) && !e.extinct())
                return e;
        return null;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
