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
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.net.URI;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
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
        ResearchPanel researchPanel = new ResearchPanel(order -> clientHolder[0].sendMessage(order));
        FleetsPanel fleetsPanel = new FleetsPanel(order -> clientHolder[0].sendMessage(order));
        ShipDesignPanel shipDesignPanel = new ShipDesignPanel(order -> clientHolder[0].sendMessage(order));
        EmpirePanel empirePanel = new EmpirePanel();
        JLabel status = new JLabel("Connecting to "+host+":"+port+"...");
        JButton nextTurn = new JButton("Ready");
        nextTurn.setEnabled(false);

        // each screen opens as its own window (like the Mac port)
        JFrame researchWindow = new JFrame("Research");
        researchWindow.add(researchPanel);
        researchWindow.setSize(470, 320);
        researchWindow.setLocationByPlatform(true);

        JFrame fleetsWindow = new JFrame("Fleets & Transports");
        fleetsWindow.add(fleetsPanel);
        fleetsWindow.setSize(480, 420);
        fleetsWindow.setLocationByPlatform(true);

        JFrame shipDesignWindow = new JFrame("Ship Design");
        shipDesignWindow.add(new javax.swing.JScrollPane(shipDesignPanel));
        shipDesignWindow.setSize(560, 560);
        shipDesignWindow.setLocationByPlatform(true);

        JFrame empireWindow = new JFrame("Empire Overview");
        empireWindow.add(empirePanel);
        empireWindow.setSize(580, 480);
        empireWindow.setLocationByPlatform(true);

        Runnable ready = () -> {
            nextTurn.setEnabled(false);
            status.setText("Ready - waiting for other players...");
            clientHolder[0].sendReady(true);
        };

        // clicking a system opens your colony there (if any) and sets it as the
        // fleets screen's deploy destination
        galaxyPanel.onSystemClicked(sysId -> {
            PlayerView v = lastView[0];
            if ((v != null) && (sysId >= 0)) {
                colonyPanel.showColony(sysId, v);
                fleetsPanel.selectDestination(sysId);
            }
        });

        // Mac-style menu bar with the Mac-port shortcuts (see docs/mac-ux-spec.md);
        // the accelerator mask is Cmd on macOS, Ctrl elsewhere
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar menuBar = new JMenuBar();

        JMenu planets = new JMenu("Planets");
        JMenuItem planetListItem = new JMenuItem("Planet List");
        planetListItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask));
        planetListItem.addActionListener(e -> empireWindow.setVisible(!empireWindow.isVisible()));
        planets.add(planetListItem);
        menuBar.add(planets);

        JMenu fleet = new JMenu("Fleet");
        JMenuItem fleetListItem = new JMenuItem("Fleet List");
        fleetListItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask));
        fleetListItem.addActionListener(e -> fleetsWindow.setVisible(!fleetsWindow.isVisible()));
        JMenuItem shipDesignItem = new JMenuItem("Ship Design");
        shipDesignItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, menuMask));
        shipDesignItem.addActionListener(e -> shipDesignWindow.setVisible(!shipDesignWindow.isVisible()));
        fleet.add(fleetListItem);
        fleet.add(shipDesignItem);
        menuBar.add(fleet);

        JMenu misc = new JMenu("Misc");
        JMenuItem techItem = new JMenuItem("Technology");
        techItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, menuMask));
        techItem.addActionListener(e -> researchWindow.setVisible(!researchWindow.isVisible()));
        JMenuItem nextTurnItem = new JMenuItem("Next Turn (Ready)");
        nextTurnItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask));
        nextTurnItem.addActionListener(e -> { if (nextTurn.isEnabled()) ready.run(); });
        misc.add(techItem);
        misc.add(nextTurnItem);
        menuBar.add(misc);
        frame.setJMenuBar(menuBar);

        // host-only lobby controls: choose AI opponents and start the game with
        // the humans present (hidden until we learn we're the host, and on start)
        JLabel aiLabel = new JLabel("AI opponents:");
        JSpinner aiSpinner = new JSpinner(new SpinnerNumberModel(2, 0, 8, 1));
        JButton startBtn = new JButton("Start Game");
        aiLabel.setVisible(false);
        aiSpinner.setVisible(false);
        startBtn.setVisible(false);
        startBtn.addActionListener(e -> {
            Messages.StartGame sg = new Messages.StartGame();
            sg.aiOpponents = (Integer) aiSpinner.getValue();
            clientHolder[0].sendMessage(sg);
            startBtn.setEnabled(false);
            status.setText("Starting game...");
        });
        JPanel lobby = new JPanel();
        lobby.add(aiLabel);
        lobby.add(aiSpinner);
        lobby.add(startBtn);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(lobby, BorderLayout.WEST);
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
            msg -> SwingUtilities.invokeLater(() -> {
                if (msg instanceof Messages.Joined) {
                    boolean amHost = ((Messages.Joined) msg).host;
                    aiLabel.setVisible(amHost);
                    aiSpinner.setVisible(amHost);
                    startBtn.setVisible(amHost);
                    status.setText(amHost
                        ? "You are the host - choose AI opponents and press Start."
                        : "Joined - waiting for the host to start the game.");
                }
                else if (msg instanceof Messages.GameStarted) {
                    aiLabel.setVisible(false);
                    aiSpinner.setVisible(false);
                    startBtn.setVisible(false);
                }
                handleMessage(msg, galaxyPanel, colonyPanel, researchPanel, fleetsPanel, shipDesignPanel, empirePanel, lastView, status, nextTurn);
            }),
            text -> SwingUtilities.invokeLater(() -> status.setText(text)));
        clientHolder[0] = client;

        nextTurn.addActionListener(e -> ready.run());

        client.connect();
    }

    private static void handleMessage(Object msg, GalaxyViewPanel galaxyPanel, ColonyPanel colonyPanel,
                                      ResearchPanel researchPanel, FleetsPanel fleetsPanel,
                                      ShipDesignPanel shipDesignPanel, EmpirePanel empirePanel,
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
            researchPanel.updateFromView(view);
            fleetsPanel.updateFromView(view);
            shipDesignPanel.updateFromView(view);
            empirePanel.updateFromView(view);
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
        else if (msg instanceof Messages.DesignCatalog) {
            shipDesignPanel.setCatalog((Messages.DesignCatalog) msg);
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
