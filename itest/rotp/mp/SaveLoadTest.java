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

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.GalacticCouncil;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Minimal local save/load: the host saves the running game to a named file, and a
 * server started with load=<name> resumes it — the client reconnects to its
 * remote-human empire at the saved turn. This is a testing convenience so late-game
 * states don't have to be replayed from turn 1.
 */
public class SaveLoadTest {

    @Test
    @Timeout(180)
    void saveThenResumeInANewServer() throws Exception {
        String saveName = "mp_itest_save";
        File saveFile = null;
        Server s1 = null, s2 = null;
        Client a1 = null, a2 = null;
        try {
            // play a couple of turns
            s1 = MpTestSupport.startServer(1);   // boots the engine
            saveFile = new File(GameSession.instance().saveDir(), saveName + GameSession.SAVEFILE_EXTENSION);
            a1 = new Client(s1.port, "Alice");
            a1.awaitView();
            a1.ready();
            PlayerView before = a1.ready();
            int savedTurn = before.turn;
            int empireId = before.empireId;

            // save (SaveGame returns only a cmdResult, no fresh view — poll it directly)
            Messages.SaveGame sg = new Messages.SaveGame();
            sg.name = saveName;
            a1.raw(sg);
            Messages.CommandResult saved = a1.results.poll(30, TimeUnit.SECONDS);
            assertNotNull(saved, "a save acknowledgement arrives");
            assertTrue(saved.ok, "the game saves: " + saved.text);
            assertTrue(saveFile.exists(), "the save file was written");

            a1.close(); a1 = null;
            s1.stop(); s1 = null;

            // resume in a fresh server started from the save
            s2 = MpTestSupport.startServerFromSave(saveName);
            a2 = new Client(s2.port, "Alice");
            PlayerView resumed = a2.awaitView();
            assertEquals(empireId, resumed.empireId, "reconnects to the same empire");
            assertTrue(resumed.turn >= savedTurn,
                "resumes at (or after) the saved turn: " + resumed.turn + " >= " + savedTurn);

            // and the resumed game can advance
            PlayerView after = a2.ready();
            assertTrue(after.turn > resumed.turn, "the resumed game keeps playing");
        }
        finally {
            if (a1 != null) a1.close();
            if (a2 != null) a2.close();
            if (s1 != null) s1.stop();
            if (s2 != null) s2.stop();
            if (saveFile != null) saveFile.delete();
        }
    }

    /**
     * Phase 4: a game saved while a Galactic Council vote is open used to lose the
     * vote — the convention tally was transient, so the reload re-convened from
     * scratch. The paused convention must survive: same candidates, same tally,
     * still waiting on the same voter.
     */
    @Test
    @Timeout(180)
    void aCouncilVoteOpenAtSaveTimeSurvivesTheReload() throws Exception {
        String saveName = "mp_itest_council_save";
        File saveFile = null;
        Server s1 = null, s2 = null;
        Client a1 = null, a2 = null;
        try {
            s1 = MpTestSupport.startServer(1);
            saveFile = new File(GameSession.instance().saveDir(), saveName + GameSession.SAVEFILE_EXTENSION);
            a1 = new Client(s1.port, "Alice");
            PlayerView v = a1.awaitView();
            assumeTrue(GameSession.instance().galaxy().numActiveEmpires() >= 3,
                "a council needs at least three empires");

            // force the council to convene next turn (2/3-colonized is out of reach
            // in a bounded test), then stop on the human's vote
            GalacticCouncil council = GameSession.instance().galaxy().council();
            setInt(council, "currentStatus", 1);   // ACTIVE
            setInt(council, "nextAction", 2);      // CONVENE
            setInt(council, "actionCountdown", 1);

            Messages.Prompt prompt = null;
            for (int t = 0; t < 3 && prompt == null; t++) {
                a1.ready();
                prompt = MpTestSupport.firstPrompt(a1, "COUNCIL_VOTE");
            }
            assertNotNull(prompt, "the council election prompted the human before the save");
            int votesBefore = council.totalVotes();
            int candidate1 = council.candidate1().id;
            int candidate2 = council.candidate2().id;
            int nextVoter = council.nextVoter().id;
            assertEquals(v.empireId, nextVoter, "the vote is paused on the human");

            Messages.SaveGame sg = new Messages.SaveGame();
            sg.name = saveName;
            a1.raw(sg);
            Messages.CommandResult saved = a1.results.poll(30, TimeUnit.SECONDS);
            assertNotNull(saved, "a save acknowledgement arrives");
            assertTrue(saved.ok, "the game saves mid-vote: " + saved.text);

            a1.close(); a1 = null;
            s1.stop(); s1 = null;

            s2 = MpTestSupport.startServerFromSave(saveName);
            a2 = new Client(s2.port, "Alice");
            a2.awaitView();

            GalacticCouncil reloaded = GameSession.instance().galaxy().council();
            assertTrue(reloaded.active(), "the council is still in session");
            assertTrue(reloaded.votingInProgress(), "the vote is still open");
            assertEquals(votesBefore, reloaded.totalVotes(), "the vote tally survived");
            assertEquals(candidate1, reloaded.candidate1().id, "same first candidate");
            assertEquals(candidate2, reloaded.candidate2().id, "same second candidate");
            assertEquals(nextVoter, reloaded.nextVoter().id, "still waiting on the same voter");

            // and the reloaded convention still accepts the human's vote
            Messages.CastCouncilVote cv = new Messages.CastCouncilVote();
            cv.candidateId = candidate1;
            assertTrue(a2.order(cv).ok, "the resumed vote can be cast");
        }
        finally {
            if (a1 != null) a1.close();
            if (a2 != null) a2.close();
            if (s1 != null) s1.stop();
            if (s2 != null) s2.stop();
            if (saveFile != null) saveFile.delete();
        }
    }

    /** set a private int field on the council (test-forces a rare game state) */
    private static void setInt(GalacticCouncil c, String field, int value) throws Exception {
        java.lang.reflect.Field f = GalacticCouncil.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(c, value);
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
