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

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.GalacticCouncil;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * The Galactic Council (Phase 3). Two things are verified: the council no longer hangs
 * the headless server when it convenes (the CouncilVoteNotification is routed through
 * the SessionUI seam, a no-op on the server), and when it is a remote human's turn to
 * vote the server raises an interactive COUNCIL_VOTE prompt the human resolves with
 * castCouncilVote.
 *
 * A council convenes only once 2/3 of the galaxy is colonized, which is impractical to
 * reach in a bounded test, so the convention is forced through the in-process engine
 * (the server runs in this JVM) by scheduling the council to convene next turn.
 */
public class CouncilVoteTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    /** set a private int field on the council (test-forces a rare game state) */
    private static void setInt(GalacticCouncil c, String field, int value) throws Exception {
        Field f = GalacticCouncil.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(c, value);
    }

    @Test
    @Timeout(120)
    void aCouncilVoteBecomesAPromptTheHumanCanCast() throws Exception {
        server = MpTestSupport.startServer(1);   // 1 human + AI opponents
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        GalacticCouncil council = GameSession.instance().galaxy().council();
        assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
            "a council needs at least three empires");

        // schedule the council to convene on the next turn: ACTIVE, next action CONVENE,
        // countdown 1 (nextTurn decrements to 0 and convenes). The convention opens
        // during turn processing; afterwards the server drives AI voting and prompts us.
        setInt(council, "currentStatus", 1);   // ACTIVE
        setInt(council, "nextAction", 2);      // CONVENE
        setInt(council, "actionCountdown", 1);

        Messages.Prompt prompt = null;
        for (int t = 0; t < 3 && prompt == null; t++) {
            alice.ready();   // must not hang: council notification is a headless no-op
            prompt = MpTestSupport.firstPrompt(alice, "COUNCIL_VOTE");
        }
        assertNotNull(prompt, "a council election prompts the human to vote");
        assertNotNull(prompt.choiceIds, "the prompt is self-contained: it carries the candidates");
        assertTrue(prompt.choiceIds.length >= 2, "at least two candidates plus abstain");
        assertEquals("-1", prompt.choiceIds[prompt.choiceIds.length - 1], "abstain is the last option");

        // it is our turn to vote (the engine paused AI voting on the human)
        assertTrue(council.votingInProgress(), "voting is paused awaiting the human");
        assertEquals(v.empireId, council.nextVoter().id, "it is the human's turn to vote");

        // cast a vote for the first candidate
        Messages.CastCouncilVote cv = new Messages.CastCouncilVote();
        cv.candidateId = Integer.parseInt(prompt.choiceIds[0]);
        assertTrue(alice.order(cv).ok, "casting a council vote is accepted");

        // the human has now voted; the convention resumes/closes, so a second vote is rejected
        Messages.CastCouncilVote again = new Messages.CastCouncilVote();
        again.candidateId = Integer.parseInt(prompt.choiceIds[0]);
        assertFalse(alice.order(again).ok, "a second vote is rejected once ours is cast");
    }

    @Test
    @Timeout(120)
    void anIgnoredCouncilVoteIsFinalizedInsteadOfHanging() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        GalacticCouncil council = GameSession.instance().galaxy().council();
        assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
            "a council needs at least three empires");

        setInt(council, "currentStatus", 1);
        setInt(council, "nextAction", 2);
        setInt(council, "actionCountdown", 1);

        // convene and get prompted, then ignore the prompt and just advance turns: the
        // server must finalize the vote (AI default for us) so the game keeps running
        // rather than re-convening/hanging on an unanswered vote.
        Messages.Prompt prompt = null;
        for (int t = 0; t < 3 && prompt == null; t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "COUNCIL_VOTE");
        }
        assertNotNull(prompt, "a council election prompted the human");
        assertTrue(council.votingInProgress(), "the vote is paused awaiting the human before we ignore it");

        // ignore the prompt and advance a turn: the server must finalize the vote for us
        // (AI default) so the convention resolves instead of being left open. An open
        // convention would re-convene and reset every turn (a livelock); resolving it
        // means voting is over afterward, whether a leader was elected or the next
        // council was scheduled.
        alice.ready();
        assertFalse(council.votingInProgress(),
            "an ignored council vote is finalized, not left open awaiting the human");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
