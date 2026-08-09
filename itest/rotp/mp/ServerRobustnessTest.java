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
 * Nothing a client sends may take the server down.
 *
 * This exists because of the Phase-5 guarantee: a browser client is written
 * against the protocol by someone who cannot see the server, and *will* send
 * things the Java reference client never sends — null fields, empty arrays,
 * absurd ids, messages out of order, junk. When that happens the bug has to be
 * visibly the client's. A dead or wedged server is indistinguishable from a
 * protocol misunderstanding, and it takes everyone else's game with it.
 *
 * The bar each case checks: the server answers, and is still playing the game
 * afterwards.
 */
public class ServerRobustnessTest {
    private Server server;
    private Client alice;

    @AfterEach
    void tearDown() throws Exception {
        if (alice != null) alice.close();
        if (server != null) server.stop();
    }

    /** the server is still alive and driving the game */
    private void assertStillPlaying(String after) throws Exception {
        PlayerView before = alice.latestView();
        PlayerView post = alice.ready();
        assertNotNull(post, "server still responds after " + after);
        assertTrue(post.turn > before.turn, "server still resolves turns after " + after);
    }

    @Test
    @Timeout(180)
    void junkOnTheWireDoesNotKillTheServer() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        alice.rawText("this is not json at all");
        alice.rawText("{}");
        alice.rawText("{\"t\":\"nosuchtype\",\"d\":{}}");
        alice.rawText("[]");
        alice.rawText("");
        alice.rawText("{\"t\":\"setColonyAlloc\"}");            // no payload
        alice.rawText("{\"t\":\"setColonyAlloc\",\"d\":null}");  // null payload

        assertStillPlaying("malformed messages");
    }

    /**
     * Well-formed messages with hostile *contents* — the realistic case, since a
     * client that gets the envelope right can still get every field wrong.
     */
    @Test
    @Timeout(180)
    void nullAndOutOfRangeFieldsAreRejectedNotFatal() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        PlayerView v = alice.awaitView();
        int home = MpTestSupport.ownColony(v).id;

        // null arrays and strings where the client would normally send data
        Messages.SetColonyAllocations nullAlloc = new Messages.SetColonyAllocations();
        nullAlloc.systemId = home;
        nullAlloc.alloc = null;
        alice.raw(nullAlloc);

        Messages.SetResearchChoice nullTech = new Messages.SetResearchChoice();
        nullTech.category = 0;
        nullTech.techId = null;
        alice.raw(nullTech);

        Messages.SetSpyMission nullMission = new Messages.SetSpyMission();
        nullMission.empireId = 1;
        nullMission.mission = null;
        alice.raw(nullMission);

        Messages.DiploOffer nullAction = new Messages.DiploOffer();
        nullAction.empireId = 1;
        nullAction.action = null;
        alice.raw(nullAction);

        // absurd indices
        Messages.SetColonyAllocations badSystem = new Messages.SetColonyAllocations();
        badSystem.systemId = Integer.MIN_VALUE;
        badSystem.alloc = new int[]{10, 10, 10, 10, 10};
        alice.raw(badSystem);

        Messages.SetTechAllocations badCat = new Messages.SetTechAllocations();
        badCat.alloc = new int[]{-5, 999, 0, 0, 0, 0};
        alice.raw(badCat);

        Messages.SetColonyLock badLock = new Messages.SetColonyLock();
        badLock.systemId = home;
        badLock.category = 99;
        alice.raw(badLock);

        Messages.DeployFleet badFleet = new Messages.DeployFleet();
        badFleet.fromSystemId = 12345;
        badFleet.destSystemId = -7;
        alice.raw(badFleet);

        Messages.Bombard badBombard = new Messages.Bombard();
        badBombard.systemId = Integer.MAX_VALUE;
        alice.raw(badBombard);

        Messages.RequestTech badTech = new Messages.RequestTech();
        badTech.empireId = 999;
        badTech.techId = null;
        alice.raw(badTech);

        Messages.TransferReserve badTransfer = new Messages.TransferReserve();
        badTransfer.systemId = home;
        badTransfer.amount = Integer.MIN_VALUE;
        alice.raw(badTransfer);

        assertStillPlaying("hostile field values");
    }

    /**
     * Messages sent in the wrong order or at the wrong time. A reconnecting browser
     * replays state at moments the Java client never would.
     */
    @Test
    @Timeout(180)
    void messagesOutOfOrderAreHandled() throws Exception {
        server = MpTestSupport.startServer(1);
        alice = new Client(server.port, "Alice");
        alice.awaitView();

        // answering prompts nobody raised
        Messages.RespondDiplomacy noOffer = new Messages.RespondDiplomacy();
        noOffer.empireId = 1;
        noOffer.action = "PACT";
        noOffer.accept = true;
        alice.raw(noOffer);

        Messages.CastCouncilVote noVote = new Messages.CastCouncilVote();
        noVote.candidateId = 1;
        alice.raw(noVote);

        Messages.RespondTechRequest noRequest = new Messages.RespondTechRequest();
        noRequest.requestorId = 1;
        noRequest.counterTechId = "TECH_MADE_UP";
        alice.raw(noRequest);

        // a second hello on an established connection
        Messages.Hello again = new Messages.Hello();
        again.version = rotp.mp.protocol.Protocol.VERSION;
        again.playerName = "Alice";
        alice.raw(again);

        // starting a game that is already running
        Messages.StartGame restart = new Messages.StartGame();
        restart.aiOpponents = 3;
        alice.raw(restart);

        assertStillPlaying("out-of-order messages");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
