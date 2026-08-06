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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * A client that drops mid-game rejoins its own empire (matched by name) instead
 * of being turned away as "game full", and resumes from the current turn. This
 * lets the reference client be relaunched (e.g. after a rebuild) without losing
 * a game in progress.
 */
public class ReconnectTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void droppedClientRejoinsSameEmpireAndResumes() throws Exception {
        server = MpTestSupport.startServer(1);           // 1 human -> auto-starts solo vs AI
        Client first = new Client(server.port, "Alice");
        PlayerView v0 = first.awaitView();
        int empireId = v0.empireId;

        // play a couple of turns so the game is genuinely mid-flight
        first.ready();
        PlayerView beforeDrop = first.ready();
        int turnBeforeDrop = beforeDrop.turn;

        // the client drops (crash / rebuild / network blip)
        first.close();

        // relaunch with the same name -> reconnect, not a fresh join
        alice = new Client(server.port, "Alice");
        Messages.GameStarted gs = alice.starts.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(gs, "reconnecting client is told the game is already started");
        assertEquals(empireId, gs.empireId, "reconnects to the same empire it left");

        PlayerView resumed = alice.awaitView();
        assertEquals(empireId, resumed.empireId, "resumed view is for the original empire");
        assertTrue(resumed.turn >= turnBeforeDrop,
            "resumes at the current turn, not a fresh game (turn " + resumed.turn
            + " >= " + turnBeforeDrop + ")");

        // and the reconnected client can still drive the game forward
        PlayerView afterResume = alice.ready();
        assertTrue(afterResume.turn > resumed.turn, "reconnected client can advance the turn");
    }

    @Test
    @Timeout(120)
    void unknownNameAfterStartIsStillRejected() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();                                // game is now running

        // a brand-new player (no matching departed slot) is still turned away
        Client stranger = new Client(server.port, "Mallory");
        Messages.Error err = stranger.awaitError();
        assertNotNull(err, "a new player cannot join a game already in progress");
        stranger.close();
    }
}
