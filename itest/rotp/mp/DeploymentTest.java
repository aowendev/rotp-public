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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rotp.model.game.IGameOptions;
import rotp.mp.client.ClientMain;
import rotp.mp.server.GameServer;
import rotp.mp.server.ServerMain;

/**
 * The server options that only matter once the game is hosted on the internet
 * (Phase 4): which interface it listens on, and how quickly it notices a
 * connection that died without a close frame. See docs/deployment.md.
 */
public class DeploymentTest {

    @Test
    void aBindHostRestrictsTheServerToThatInterface() {
        MpTestSupport.bootEngine();
        // behind a TLS-terminating reverse proxy the game port must not be
        // reachable from the internet at all
        GameServer loopback = new GameServer("127.0.0.1", 0, 2, IGameOptions.SIZE_TINY, null);
        assertTrue(loopback.getAddress().getAddress().isLoopbackAddress(),
            "bind=127.0.0.1 listens on loopback only");

        GameServer anywhere = new GameServer(null, 0, 2, IGameOptions.SIZE_TINY, null);
        assertTrue(anywhere.getAddress().getAddress().isAnyLocalAddress(),
            "no bind host listens on every interface");
    }

    @Test
    void theServerPingsSoADeadBrowserConnectionIsNoticed() {
        MpTestSupport.bootEngine();
        GameServer server = new GameServer(null, 0, 2, IGameOptions.SIZE_TINY, null);
        // a slept laptop or dropped mobile link sends no close frame; without a
        // heartbeat the ghost would keep holding the empire
        assertTrue(server.getConnectionLostTimeout() > 0, "connection-lost detection is on");
        assertEquals(30, server.getConnectionLostTimeout(), "pings every 30s");
    }

    @Test
    void deploymentArgumentsAreParsed() {
        String[] args = {"--server", "port=8778", "bind=127.0.0.1",
                         "savedir=/var/lib/rotp/game-2", "keystorePassword=s3cret"};
        assertEquals(8778, ServerMain.intArg(args, "port", 0), "port");
        assertEquals("127.0.0.1", ServerMain.stringArg(args, "bind", null), "bind host");
        assertEquals("/var/lib/rotp/game-2", ServerMain.stringArg(args, "savedir", null), "save dir");
        // the arg name is matched case-insensitively, the value is taken verbatim
        assertEquals("s3cret", ServerMain.stringArg(args, "keystorepassword", null), "keystore password");
        assertNull(ServerMain.stringArg(args, "keystore", null), "absent args keep their default");
        assertFalse("s3cret".equals(ServerMain.stringArg(args, "keystore", null)),
            "keystorePassword= is not mistaken for keystore=");
    }

    /** the client has to be able to *reach* a hosted game, not just a LAN host:port */
    @Test
    void theClientAcceptsAFullWebSocketUrlAsWellAsHostAndPort() {
        assertEquals("ws://localhost:8777",
            ClientMain.serverUrl(new String[]{"--client"}), "defaults to a local ws:// game");
        assertEquals("ws://192.168.1.20:8778",
            ClientMain.serverUrl(new String[]{"--client", "host=192.168.1.20", "port=8778"}),
            "host+port still builds a plain LAN address");
        // a hosted game lives at a path behind a TLS proxy, which host:port cannot express
        assertEquals("wss://rotp.example.com/game/1",
            ClientMain.serverUrl(new String[]{"--client", "url=wss://rotp.example.com/game/1"}),
            "an explicit url reaches a proxied, TLS-terminated game");
        assertEquals("wss://rotp.example.com/game/2",
            ClientMain.serverUrl(new String[]{"--client", "host=ignored", "port=1",
                                              "url=wss://rotp.example.com/game/2"}),
            "an explicit url wins over host+port");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
