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
 * A client that drops mid-game rejoins its own empire instead of being turned
 * away as "game full", and resumes from the current turn. This lets the
 * reference client be relaunched (e.g. after a rebuild) without losing a game in
 * progress.
 *
 * Two ways to re-claim an empire. The **session token** issued in
 * {@link Messages.Joined} is the browser-grade one: it survives a display-name
 * change and works even when the old socket is still open (a refresh reconnects
 * before the server sees the close). Matching on **player name** stays as the
 * fallback for clients that carry no token. A connection with neither is still
 * rejected mid-game.
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

    /**
     * The browser case the name fallback cannot serve: the player comes back
     * under a different display name. The token still identifies the empire.
     */
    @Test
    @Timeout(120)
    void aSessionTokenReclaimsTheEmpireEvenUnderADifferentName() throws Exception {
        server = MpTestSupport.startServer(1);
        Client first = new Client(server.port, "Alice");
        PlayerView v0 = first.awaitView();
        int empireId = v0.empireId;
        String token = first.issuedToken;
        assertNotNull(token, "the server issues a session token on join");

        first.ready();
        first.close();

        alice = new Client(server.port, "Alice on her phone", token);
        Messages.GameStarted gs = alice.starts.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(gs, "token-bearing client is told the game is already started");
        assertEquals(empireId, gs.empireId, "the token, not the name, chose the empire");
        assertEquals(empireId, alice.awaitView().empireId, "resumed view is for that empire");
    }

    /**
     * A browser refresh opens the new socket before the server notices the old
     * one closed, so for a moment two connections claim the same empire. The
     * token holder wins and the stale connection is dropped.
     */
    @Test
    @Timeout(120)
    void reconnectingWhileTheOldSocketIsStillOpenEvictsIt() throws Exception {
        server = MpTestSupport.startServer(1);
        Client stale = new Client(server.port, "Alice");
        PlayerView v0 = stale.awaitView();
        int empireId = v0.empireId;
        String token = stale.issuedToken;

        // no close() first: the old connection is still live when the new one arrives
        alice = new Client(server.port, "Alice", token);
        Messages.GameStarted gs = alice.starts.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(gs, "the refreshed client takes over");
        assertEquals(empireId, gs.empireId, "same empire as the stale connection");

        // and the game still runs from the new connection alone
        PlayerView resumed = alice.awaitView();
        assertTrue(alice.ready().turn > resumed.turn, "the surviving connection drives the turn");
        stale.close();
    }

    /**
     * Pre-start the same reclaim keeps a refreshing client in its lobby slot,
     * rather than consuming another one and filling the lobby with ghosts.
     */
    @Test
    @Timeout(120)
    void aTokenKeepsTheLobbySlotBeforeTheGameStarts() throws Exception {
        server = MpTestSupport.startServer(2);   // 2 human slots: no auto-start with one player
        Client first = new Client(server.port, "Alice");
        Messages.Joined joined = first.awaitJoined();
        assertNotNull(joined, "joined the lobby");
        assertTrue(joined.host, "first player is the host");
        String token = joined.sessionToken;

        alice = new Client(server.port, "Alice", token);   // refresh, old socket still open
        Messages.Joined again = alice.awaitJoined();
        assertNotNull(again, "rejoined the lobby");
        assertEquals(joined.empireId, again.empireId, "kept the same lobby slot");
        assertTrue(again.host, "kept the host role");

        // the freed-up second slot is still available to a genuinely new player
        Client bob = new Client(server.port, "Bob");
        Messages.Joined bobJoined = bob.awaitJoined();
        assertNotNull(bobJoined, "the second slot was never consumed by the refresh");
        assertEquals(1, bobJoined.empireId, "Bob takes the second slot");
        first.close();
        bob.close();
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
