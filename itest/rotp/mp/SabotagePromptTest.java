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
import rotp.model.empires.SabotageMission;
import rotp.model.empires.Spy;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Directing a sabotage mission: factories, missile bases, or rebellion.
 *
 * The AI used to choose for a remote human, because the deferral branch keyed on
 * {@code isPlayerControlled()}, which is never true on the server. Each option
 * names the system it would hit, so the target is part of the visible choice
 * rather than hidden behind it.
 */
public class SabotagePromptTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    private static SabotageMission missionAgainst(Empire saboteur, Empire victim) {
        rotp.model.empires.EmpireView ev = saboteur.viewForEmpire(victim);
        assumeTrue(ev != null, "no view of the victim");
        // a super spy so the sabotage lands measurable damage rather than fizzling
        return new SabotageMission(ev.spies(), new Spy(ev.spies()).makeSuper());
    }

    @Test
    @Timeout(120)
    void theHumanDirectsTheirOwnSabotage() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire saboteur = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(saboteur);
        assumeTrue(victim != null, "no other empire");
        saboteur.makeContact(victim);
        victim.makeContact(saboteur);
        StarSystem target = victim.allColonizedSystems().get(0);
        saboteur.sv.refreshFullScan(target.id);

        rotp.ui.notifications.SabotageNotification.createDeferred(
            missionAgainst(saboteur, victim), target.id);

        Messages.Prompt prompt = null;
        for (int t = 0; (t < 2) && (prompt == null); t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "SABOTAGE");
        }
        assertNotNull(prompt, "the human is asked what their saboteurs do");
        assertEquals(victim.id, prompt.empireId, "the prompt names the victim");
        assertEquals(target.id, prompt.systemId, "and the system");
        assertEquals(3, prompt.choiceIds.length, "factories, missile bases, rebellion");
        assertEquals("FACTORIES", prompt.choiceIds[0], "actions are sent as stable ids");
        for (String name : prompt.choiceNames)
            assertTrue(name.length() > 0, "each option is labelled with what it hits");
    }

    @Test
    @Timeout(120)
    void factoriesAreDestroyedWhenThatIsChosen() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire saboteur = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(saboteur);
        assumeTrue(victim != null, "no other empire");
        saboteur.makeContact(victim);
        victim.makeContact(saboteur);
        StarSystem target = victim.allColonizedSystems().get(0);
        saboteur.sv.refreshFullScan(target.id);
        target.colony().industry().factories(60);

        SabotageMission m = missionAgainst(saboteur, victim);
        rotp.ui.notifications.SabotageNotification.createDeferred(m, target.id);
        // exactly one turn: enough to raise the prompt, and not so many that the
        // end-of-turn finalizer settles it with the AI's choice first
        alice.ready();
        assertNotNull(MpTestSupport.firstPrompt(alice, "SABOTAGE"), "the prompt was raised");

        // snapshot AFTER the turn: the colony builds factories during it, so a count
        // taken beforehand would be compared against growth as well as damage
        float before = target.colony().industry().factories();

        Messages.Sabotage order = new Messages.Sabotage();
        order.empireId = victim.id;
        order.action = "FACTORIES";
        assertTrue(alice.order(order).ok, "the sabotage order is accepted");
        assertTrue(target.colony().industry().factories() < before,
            "factories were destroyed ("+before+" -> "+target.colony().industry().factories()+")");
    }

    @Test
    @Timeout(120)
    void badSabotageOrdersAreRejected() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire saboteur = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeign(saboteur);
        assumeTrue(victim != null, "no other empire");

        Messages.Sabotage noMission = new Messages.Sabotage();
        noMission.empireId = victim.id;
        noMission.action = "FACTORIES";
        assertFalse(alice.order(noMission).ok,
            "directing saboteurs you never sent cannot damage anyone");
    }

    private static Empire firstForeign(Empire self) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != self) && !e.extinct())
                return e;
        return null;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
