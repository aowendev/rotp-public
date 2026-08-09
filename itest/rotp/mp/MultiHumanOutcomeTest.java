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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.server.GameOutcomes;

/**
 * Win, loss and elimination in a game with more than one human.
 *
 * The engine's single GameStatus is written from {@code player()}'s point of
 * view, so it can only ever describe empire 0. Everything here is about the
 * other humans: they must get their own verdict, their defeat must not end
 * anyone else's game, and once eliminated they must not hold up the turn they
 * can no longer take part in.
 */
public class MultiHumanOutcomeTest {
    private Server server;
    private Client alice, bob;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (server != null) server.stop();
    }

    /** two humans in deterministic slot order: Alice = empire 0, Bob = empire 1 */
    private void startTwoHumanGame() throws Exception {
        server = MpTestSupport.startServer(2);
        alice = new Client(server.port, "Alice");
        assertEquals(0, alice.awaitJoined().empireId, "Alice is empire 0");
        bob = new Client(server.port, "Bob");     // fills the slots -> auto-start
        assertEquals(1, bob.awaitJoined().empireId, "Bob is empire 1");
        alice.awaitView();
        bob.awaitView();
    }

    private static void wipeOutEveryEmpireExcept(Empire survivor) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != survivor) && !e.extinct())
                e.goExtinct();
    }

    @Test
    @Timeout(180)
    void thesecondHumanIsToldWhenTheyWinNotJustEmpireZero() throws Exception {
        startTwoHumanGame();
        Empire bobEmp = GameSession.instance().galaxy().empire(1);
        wipeOutEveryEmpireExcept(bobEmp);

        // Alice is gone, so the turn must resolve on Bob's readiness alone
        bob.ready();

        Messages.GameOver bobResult = bob.awaitGameOver();
        assertNotNull(bobResult, "the surviving human is told the game is over");
        assertTrue(bobResult.won, "Bob conquered the galaxy, so Bob won");
        assertEquals("MILITARY", bobResult.reason, "and is told why");

        Messages.GameOver aliceResult = alice.awaitGameOver();
        assertNotNull(aliceResult, "the destroyed human is told too");
        assertFalse(aliceResult.won, "Alice lost");
    }

    /**
     * The regression that mattered most: empire 0 dying used to set the engine's
     * global status to a loss, which ended the game on the server — taking every
     * other human's game down with it.
     */
    @Test
    @Timeout(180)
    void oneHumanBeingEliminatedDoesNotEndTheGameForTheOther() throws Exception {
        startTwoHumanGame();
        Empire aliceEmp = GameSession.instance().galaxy().empire(0);
        aliceEmp.goExtinct();     // AI empires are still alive; Bob plays on

        // Bob alone drives the turn: an eliminated human has no orders to give and
        // must not block the game (Alice never readies again)
        PlayerView before = bob.lastView;
        PlayerView after = bob.ready();
        assertTrue(after.turn > before.turn,
            "Bob's readiness alone resolves the turn (" + before.turn + " -> " + after.turn + ")");
        assertTrue(bob.ready().turn > after.turn, "and keeps resolving turns");

        assertNull(bob.gameOvers.poll(1, TimeUnit.SECONDS),
            "Bob is still playing - another human's defeat is not his game over");
        Messages.GameOver aliceResult = alice.awaitGameOver();
        assertNotNull(aliceResult, "Alice is told she is out");
        assertFalse(aliceResult.won, "Alice lost");
    }

    @Test
    @Timeout(180)
    void anEliminatedHumanIsNotCountedInTheReadyTally() throws Exception {
        startTwoHumanGame();
        GameSession.instance().galaxy().empire(0).goExtinct();
        MpTestSupport.drain(bob.turnStatuses);

        bob.raw(readyMessage());
        Messages.TurnStatus ts = null;
        for (Messages.TurnStatus t : MpTestSupport.drain(bob.turnStatuses))
            ts = t;
        // the turn resolves, so grab a status from after it if the drain was empty
        if (ts == null)
            ts = bob.turnStatuses.poll(30, TimeUnit.SECONDS);
        assertNotNull(ts, "a turn status arrives");
        assertEquals(1, ts.totalPlayers,
            "only the humans still in the game are counted - a dead empire is not waited on");
    }

    /** the pure outcome rules, independent of the wire */
    @Test
    @Timeout(120)
    void outcomeRulesReadTheGalaxyNotTheEngineStatus() throws Exception {
        startTwoHumanGame();
        Empire alice0 = GameSession.instance().galaxy().empire(0);
        Empire bob1 = GameSession.instance().galaxy().empire(1);

        assertNull(GameOutcomes.forEmpire(GameSession.instance().galaxy(), bob1),
            "nobody has an outcome while the galaxy is contested");
        assertFalse(GameOutcomes.galaxyDecided(GameSession.instance().galaxy()),
            "a contested galaxy is not decided");

        wipeOutEveryEmpireExcept(bob1);
        assertTrue(GameOutcomes.galaxyDecided(GameSession.instance().galaxy()),
            "one empire standing decides the galaxy");
        GameOutcomes.Outcome win = GameOutcomes.forEmpire(GameSession.instance().galaxy(), bob1);
        assertNotNull(win, "the survivor has an outcome");
        assertTrue(win.won, "the survivor won");
        assertEquals("MILITARY", win.reason, "by conquest");

        GameOutcomes.Outcome loss = GameOutcomes.forEmpire(GameSession.instance().galaxy(), alice0);
        assertNotNull(loss, "the destroyed empire has an outcome");
        assertFalse(loss.won, "and it is a defeat");
    }

    private static Messages.Ready readyMessage() {
        Messages.Ready r = new Messages.Ready();
        r.ready = true;
        return r;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
