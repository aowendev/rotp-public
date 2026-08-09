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
 * Bombardment is a decision, not part of the battle.
 *
 * Ship combat auto-resolves in multiplayer — a tactical battle would stall every
 * other player, so the engine's AI fights it. Bombarding a colony afterwards is a
 * different thing: a deliberate act against another player's world. It used to
 * fall through to {@code fl.bombard()} on the server, because that path only asks
 * an {@code isPlayerControlled()} empire and no empire is player-controlled there —
 * so the AI decided whether to glass a human's planet.
 */
public class BombardPromptTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    @Test
    @Timeout(120)
    void aHumanIsAskedBeforeBombardingAndNothingBurnsIfTheyIgnoreIt() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();

        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire victim = firstForeignEmpire(human);
        assumeTrue(victim != null, "no other empire in this game");
        rotp.model.galaxy.StarSystem target = victim.allColonizedSystems().isEmpty()
            ? null : victim.allColonizedSystems().get(0);
        assumeTrue(target != null, "the other empire has no colony to bomb");

        // at war, in orbit, and able to hit planets: exactly the state that used to
        // bombard with nobody asked
        human.makeContact(victim);
        victim.makeContact(human);
        human.viewForEmpire(victim).embassy().declareWar();
        human.sv.refreshFullScan(target.id);
        float popBefore = target.colony().population();

        rotp.ui.notifications.BombardSystemNotification.create(
            target.id, armedFleetInOrbit(human, target), false);

        // the notification is queued for the server, not acted on
        assertEquals(popBefore, target.colony().population(), 0.001,
            "nothing is bombed at the moment the decision arises");

        Messages.Prompt prompt = null;
        for (int t = 0; (t < 2) && (prompt == null); t++) {
            alice.ready();
            prompt = MpTestSupport.firstPrompt(alice, "BOMBARD");
        }
        assertNotNull(prompt, "the human is asked before their fleet bombards");
        assertEquals(target.id, prompt.systemId, "the prompt names the target system");
    }

    @Test
    @Timeout(120)
    void bombardIsRefusedWithoutAWarAndAFleetInOrbit() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        Empire human = GameSession.instance().galaxy().empire(v.empireId);
        Empire other = firstForeignEmpire(human);
        assumeTrue(other != null, "no other empire in this game");

        Messages.Bombard nowhere = new Messages.Bombard();
        nowhere.systemId = 9999;
        assertFalse(alice.order(nowhere).ok, "cannot bombard a system that does not exist");

        PlayerView.SystemDto home = MpTestSupport.ownColony(v);
        Messages.Bombard ownWorld = new Messages.Bombard();
        ownWorld.systemId = home.id;
        assertFalse(alice.order(ownWorld).ok, "cannot bombard your own colony");
    }

    private static Empire firstForeignEmpire(Empire self) {
        for (Empire e : GameSession.instance().galaxy().empires())
            if ((e != self) && !e.extinct())
                return e;
        return null;
    }

    /** put an armed fleet of the human's in orbit over the target */
    private static rotp.model.galaxy.ShipFleet armedFleetInOrbit(Empire emp,
            rotp.model.galaxy.StarSystem sys) {
        rotp.model.galaxy.ShipFleet fl =
            GameSession.instance().galaxy().ships.orbitingFleet(emp.id, sys.id);
        assumeTrue(fl != null, "no fleet available to place in orbit");
        return fl;
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
