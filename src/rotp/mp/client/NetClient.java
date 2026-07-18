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
package rotp.mp.client;

import java.net.URI;
import java.util.function.Consumer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.Protocol;

/**
 * WebSocket connection to a multiplayer server. Decoded messages are
 * handed to the listener on the WebSocket thread; UI listeners must
 * marshal to the EDT themselves.
 */
public class NetClient extends WebSocketClient {
    private final String playerName;
    private final Consumer<Object> listener;
    private final Consumer<String> statusListener;

    public NetClient(URI serverUri, String playerName,
                     Consumer<Object> listener, Consumer<String> statusListener) {
        super(serverUri);
        this.playerName = playerName;
        this.listener = listener;
        this.statusListener = statusListener;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        statusListener.accept("Connected to "+getURI()+", joining as "+playerName);
        Messages.Hello hello = new Messages.Hello();
        hello.version = Protocol.VERSION;
        hello.playerName = playerName;
        send(Protocol.encode(hello));
    }

    @Override
    public void onMessage(String message) {
        Object msg;
        try {
            msg = Protocol.decode(message);
        }
        catch (Exception e) {
            statusListener.accept("Bad message from server: "+e.getMessage());
            return;
        }
        if (msg != null)
            listener.accept(msg);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        statusListener.accept("Disconnected: "+reason);
    }

    @Override
    public void onError(Exception ex) {
        statusListener.accept("Connection error: "+ex.getMessage());
    }

    public void requestNextTurn() {
        if (isOpen())
            send(Protocol.encode(new Messages.NextTurn()));
    }
}
