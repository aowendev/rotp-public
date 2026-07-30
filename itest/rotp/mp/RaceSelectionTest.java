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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;

/**
 * Lobby race selection (Phase 1.5): a player picks a race, distinct races are
 * enforced, and the pick lands on that player's empire when the game starts.
 * The homeworld name follows the race automatically (GalaxyFactory names it
 * from the race), so getting the race right fixes homeworld naming too.
 */
public class RaceSelectionTest {
    private Server server;
    private Client host;
    private Client other;

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) host.close();
        if (other != null) other.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void pickedRaceLandsOnThePlayersEmpire() throws Exception {
        // 2 human slots so a lone joiner does not auto-start the game
        server = MpTestSupport.startServer(2);
        host = new Client(server.port, "Alice");
        assertTrue(host.awaitJoined().host, "first player is host");

        Messages.PickRace pick = new Messages.PickRace();
        pick.raceId = "RACE_PSILON";
        host.raw(pick);

        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 2;
        host.raw(start);

        assertNotNull(host.awaitView(), "the game starts");
        rotp.model.empires.Empire emp = GameSession.instance().galaxy().empire(0);
        assertEquals("RACE_PSILON", emp.race().id, "empire 0 has the race the host picked");

        // the homeworld name must come from the picked race, not the options'
        // initial default race (regression: it used to stay e.g. "Kholdan")
        String homeName = GameSession.instance().galaxy().system(emp.homeSysId()).name();
        assertTrue(rotp.model.empires.Race.keyed("RACE_PSILON").homeSystemNames.contains(homeName),
            "homeworld is named from the Psilon race, was: " + homeName);
    }

    @Test
    @Timeout(120)
    void racesAreUniqueAndSecondHumanPickLandsToo() throws Exception {
        // 3 human slots so two joiners do not auto-start
        server = MpTestSupport.startServer(3);
        host = new Client(server.port, "Alice");
        assertEquals(0, host.awaitJoined().empireId, "host is empire 0");
        other = new Client(server.port, "Bob");
        assertEquals(1, other.awaitJoined().empireId, "second player is empire 1");

        // by default each player is given a distinct race; Alice defaults to the
        // first (RACE_HUMAN), so Bob cannot also take it
        Messages.PickRace clash = new Messages.PickRace();
        clash.raceId = "RACE_HUMAN";
        other.raw(clash);
        Messages.Error err = other.awaitError();
        assertNotNull(err, "taking another player's race is refused");
        assertTrue(err.text.toLowerCase().contains("chose") || err.text.toLowerCase().contains("taken"),
            "refusal explains the race is taken: " + err.text);

        // Bob picks a free race; the lobby broadcast reflects it on his slot
        Messages.PickRace pick = new Messages.PickRace();
        pick.raceId = "RACE_SAKKRA";
        other.raw(pick);
        assertTrue(lobbyShowsRace(other, 1, "RACE_SAKKRA"),
            "Bob's slot shows his chosen race in the lobby");

        // host starts; each human's pick lands on their own empire
        Messages.StartGame start = new Messages.StartGame();
        start.aiOpponents = 1;
        host.raw(start);
        assertNotNull(host.awaitView(), "the game starts");
        assertEquals("RACE_HUMAN", GameSession.instance().galaxy().empire(0).race().id,
            "empire 0 keeps Alice's default race");
        assertEquals("RACE_SAKKRA", GameSession.instance().galaxy().empire(1).race().id,
            "empire 1 has Bob's picked race");
    }

    /** poll lobby broadcasts until the given empire's slot shows the expected race */
    private static boolean lobbyShowsRace(Client c, int empireId, String raceId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Messages.Lobby lobby = c.lobbies.poll(10, TimeUnit.SECONDS);
            if (lobby == null)
                continue;
            for (Messages.Slot s : lobby.slots) {
                if ((s.empireId == empireId) && raceId.equals(s.raceId))
                    return true;
            }
        }
        return false;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
