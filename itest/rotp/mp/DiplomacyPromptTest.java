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
 * Incoming diplomacy (Phase 3): when another empire offers a treaty to a remote human,
 * the server no longer auto-resolves it (the receive-offer AI gates now fire for remote
 * humans, see {@code decidedByAI()}); instead the offer is deferred and delivered as an
 * INCOMING_DIPLOMACY prompt the human accepts or refuses.
 *
 * Getting an AI to *spontaneously* offer a treaty depends on relations warming up over
 * many turns, so this drives the deferred offer deterministically through the in-process
 * engine (the server runs in this JVM) and then verifies the full server -> client prompt
 * -> response path over the wire.
 */
public class DiplomacyPromptTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void anIncomingOfferBecomesAPromptTheHumanCanAccept() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        // the server runs in-process: reach the authoritative engine directly
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire ai = GameSession.instance().galaxy().empire(v.empireId == 0 ? 1 : 0);
        assumeTrue(human.isRemoteHuman(), "the human's empire must be a remote human for offers to defer");

        // establish mutual contact so a pact offer (and later the response) is legal,
        // then have the AI offer the human a non-aggression pact. Because the human is a
        // remote human, the offer is deferred (queued as a turn notification) rather than
        // auto-resolved.
        human.makeContact(ai);
        ai.makeContact(human);
        human.diplomatAI().receiveOfferPact(ai);

        // advancing a turn drains the engine's notification queue and broadcasts the
        // deferred offer as an INCOMING_DIPLOMACY prompt
        Messages.Prompt prompt = null;
        for (int t = 0; t < 3 && prompt == null; t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "INCOMING_DIPLOMACY");
        }
        assertNotNull(prompt, "the deferred offer arrives as an INCOMING_DIPLOMACY prompt");
        assertEquals("PACT", prompt.action, "the prompt carries the offered action");
        assertEquals(ai.id, prompt.empireId, "the prompt names the offering empire");
        assertNotNull(prompt.text, "the prompt carries human-readable text");

        // accepting the offer signs the pact
        Messages.RespondDiplomacy rd = new Messages.RespondDiplomacy();
        rd.empireId = prompt.empireId;
        rd.action = prompt.action;
        rd.accept = true;
        assertTrue(alice.order(rd).ok, "accepting the offer is accepted by the server");
        assertTrue(MpTestSupport.empire(alice.lastView, ai.id).pact,
            "a non-aggression pact is now in effect with the offering empire");
    }

    @Test
    @Timeout(120)
    void anIncomingOfferCanBeDeclined() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire ai = GameSession.instance().galaxy().empire(v.empireId == 0 ? 1 : 0);
        assumeTrue(human.isRemoteHuman(), "the human's empire must be a remote human for offers to defer");

        human.makeContact(ai);
        ai.makeContact(human);
        human.diplomatAI().receiveOfferPact(ai);

        Messages.Prompt prompt = null;
        for (int t = 0; t < 3 && prompt == null; t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "INCOMING_DIPLOMACY");
        }
        assertNotNull(prompt, "the deferred offer arrives as a prompt");

        Messages.RespondDiplomacy rd = new Messages.RespondDiplomacy();
        rd.empireId = prompt.empireId;
        rd.action = prompt.action;
        rd.accept = false;
        assertTrue(alice.order(rd).ok, "declining is accepted by the server");
        assertTrue(!MpTestSupport.empire(alice.lastView, ai.id).pact,
            "declining leaves no pact in effect");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
