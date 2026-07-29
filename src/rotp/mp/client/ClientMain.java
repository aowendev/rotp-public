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

import java.awt.BorderLayout;
import java.net.URI;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.server.ServerMain;

/**
 * Walking-skeleton multiplayer client:
 *   java -jar rotp.jar --client [host=localhost] [port=8777] [name=Player]
 *
 * Connects to a server, joins the lobby, and renders the galaxy from
 * PlayerView JSON. The real game screens join the protocol in Phase 1.
 */
public class ClientMain {
    public static void run(String[] args) {
        String host = ServerMain.stringArg(args, "host", "localhost");
        int port = ServerMain.intArg(args, "port", ServerMain.DEFAULT_PORT);
        String name = ServerMain.stringArg(args, "name", System.getProperty("user.name", "Player"));

        SwingUtilities.invokeLater(() -> createUI(host, port, name));
    }

    private static void createUI(String host, int port, String name) {
        JFrame frame = new JFrame("ROTP Multiplayer - "+name);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        NetClient[] clientHolder = new NetClient[1];
        PlayerView[] lastView = new PlayerView[1];

        GalaxyViewPanel galaxyPanel = new GalaxyViewPanel();
        ColonyPanel colonyPanel = new ColonyPanel(order -> clientHolder[0].sendMessage(order));
        JLabel status = new JLabel("Connecting to "+host+":"+port+"...");
        JButton nextTurn = new JButton("Ready");
        nextTurn.setEnabled(false);

        // clicking one of your colonies opens it in the colony panel
        galaxyPanel.onSystemClicked(sysId -> {
            PlayerView v = lastView[0];
            if ((v != null) && (sysId >= 0))
                colonyPanel.showColony(sysId, v);
        });

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(nextTurn, BorderLayout.EAST);

        frame.setLayout(new BorderLayout());
        frame.add(galaxyPanel, BorderLayout.CENTER);
        frame.add(colonyPanel, BorderLayout.EAST);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(1200, 750);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        NetClient client = new NetClient(
            URI.create("ws://"+host+":"+port),
            name,
            msg -> SwingUtilities.invokeLater(() -> handleMessage(msg, galaxyPanel, colonyPanel, lastView, status, nextTurn)),
            text -> SwingUtilities.invokeLater(() -> status.setText(text)));
        clientHolder[0] = client;

        nextTurn.addActionListener(e -> {
            nextTurn.setEnabled(false);
            status.setText("Ready - waiting for other players...");
            clientHolder[0].sendReady(true);
        });

        client.connect();
    }

    private static void handleMessage(Object msg, GalaxyViewPanel galaxyPanel, ColonyPanel colonyPanel,
                                      PlayerView[] lastView, JLabel status, JButton nextTurn) {
        if (msg instanceof Messages.Lobby) {
            Messages.Lobby lobby = (Messages.Lobby) msg;
            status.setText("Lobby ("+lobby.slots.size()+" joined): "+lobby.message);
        }
        else if (msg instanceof Messages.GameStarted) {
            status.setText("Game started - you are empire "+((Messages.GameStarted) msg).empireId);
        }
        else if (msg instanceof PlayerView) {
            PlayerView view = (PlayerView) msg;
            lastView[0] = view;
            galaxyPanel.view(view);
            colonyPanel.updateFromView(view);
            status.setText(view.empireName+"  -  "+view.year+" (turn "+view.turn+")");
            nextTurn.setEnabled(true);
        }
        else if (msg instanceof Messages.TurnStatus) {
            Messages.TurnStatus ts = (Messages.TurnStatus) msg;
            status.setText(ts.note+" (turn "+ts.turn+", ready "+ts.readyCount+"/"+ts.totalPlayers+")");
            if (ts.processing)
                nextTurn.setEnabled(false);
        }
        else if (msg instanceof Messages.CommandResult) {
            Messages.CommandResult cr = (Messages.CommandResult) msg;
            if (!cr.ok)
                status.setText("Order rejected ("+cr.command+"): "+cr.text);
        }
        else if (msg instanceof Messages.Notifications) {
            Messages.Notifications ns = (Messages.Notifications) msg;
            for (Messages.Notification n : ns.items)
                System.out.println("[turn "+ns.turn+"] "+n.category+": "+n.text);
            if (!ns.items.isEmpty())
                status.setText(ns.items.size()+" event(s) this turn: "+ns.items.get(0).text
                    + (ns.items.size() > 1 ? " (+"+(ns.items.size()-1)+" more)" : ""));
        }
        else if (msg instanceof Messages.Error) {
            status.setText("Server error: "+((Messages.Error) msg).text);
        }
    }
}
