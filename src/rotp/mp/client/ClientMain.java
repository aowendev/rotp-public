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
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
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
        SystemInfoPanel systemInfoPanel = new SystemInfoPanel();
        ResearchPanel researchPanel = new ResearchPanel(order -> clientHolder[0].sendMessage(order));
        FleetsPanel fleetsPanel = new FleetsPanel(order -> clientHolder[0].sendMessage(order));
        ShipDesignPanel shipDesignPanel = new ShipDesignPanel(order -> clientHolder[0].sendMessage(order));
        EmpirePanel empirePanel = new EmpirePanel();
        JLabel status = new JLabel("Connecting to "+host+":"+port+"...");
        JButton nextTurn = new JButton("Next Turn ▶");
        nextTurn.setToolTipText("Submit your orders (if any) and advance the turn (⌘N)");
        nextTurn.setEnabled(false);

        // Colony + Fleets are docked as tabs on the right of the main window, so
        // fleet dispatch is always visible (not hidden in a separate window):
        // click a star on the map to target it, then Send the fleet there.
        JTabbedPane eastTabs = new JTabbedPane();
        eastTabs.addTab("Colony", colonyPanel);
        eastTabs.addTab("System", new JScrollPane(systemInfoPanel));
        eastTabs.addTab("Fleets", new JScrollPane(fleetsPanel));
        final int COLONY_TAB = 0;
        final int SYSTEM_TAB = 1;
        final int FLEETS_TAB = 2;

        // each screen opens as its own window (like the Mac port)
        JFrame researchWindow = new JFrame("Research");
        researchWindow.add(researchPanel);
        researchWindow.setSize(470, 320);
        researchWindow.setLocationByPlatform(true);

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

        // clicking a system targets it: it becomes the fleet-deploy destination
        // and (if it is one of your colonies) opens in the Colony tab. Clicking
        // one of your own colonies shows the Colony tab; clicking anywhere else
        // jumps to the Fleets tab, ready to send a fleet to the star you picked.
        galaxyPanel.onSystemClicked(sysId -> {
            PlayerView v = lastView[0];
            if ((v != null) && (sysId >= 0)) {
                colonyPanel.showColony(sysId, v);
                systemInfoPanel.show(sysId, v);
                fleetsPanel.selectDestination(sysId);
                // your colony -> Colony controls; a scouted star -> System info
                // (what's there / can I colonize); an unexplored dot -> Fleets
                // (send a scout there).
                int tab = isOwnColony(v, sysId) ? COLONY_TAB
                        : isScouted(v, sysId)   ? SYSTEM_TAB
                        : FLEETS_TAB;
                eastTabs.setSelectedIndex(tab);
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
        fleetListItem.addActionListener(e -> eastTabs.setSelectedIndex(FLEETS_TAB));
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
        // race picker (all players): shown once the server sends the race list,
        // hidden on game start. Preselected to this player's default race.
        JLabel raceLabel = new JLabel("Race:");
        JComboBox<RaceItem> raceCombo = new JComboBox<>();
        raceLabel.setVisible(false);
        raceCombo.setVisible(false);
        final boolean[] updatingRace = {false};
        final int[] myEmpireId = {-1};
        raceCombo.addActionListener(e -> {
            if (updatingRace[0])
                return;
            RaceItem sel = (RaceItem) raceCombo.getSelectedItem();
            if (sel == null)
                return;
            Messages.PickRace pr = new Messages.PickRace();
            pr.raceId = sel.info.id;
            clientHolder[0].sendMessage(pr);
        });

        JPanel lobby = new JPanel();
        lobby.add(raceLabel);
        lobby.add(raceCombo);
        lobby.add(aiLabel);
        lobby.add(aiSpinner);
        lobby.add(startBtn);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(lobby, BorderLayout.WEST);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(nextTurn, BorderLayout.EAST);

        frame.setLayout(new BorderLayout());
        frame.add(galaxyPanel, BorderLayout.CENTER);
        frame.add(eastTabs, BorderLayout.EAST);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(1200, 750);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        NetClient client = new NetClient(
            URI.create("ws://"+host+":"+port),
            name,
            msg -> SwingUtilities.invokeLater(() -> {
                if (msg instanceof Messages.Joined) {
                    Messages.Joined j = (Messages.Joined) msg;
                    myEmpireId[0] = j.empireId;
                    boolean amHost = j.host;
                    aiLabel.setVisible(amHost);
                    aiSpinner.setVisible(amHost);
                    startBtn.setVisible(amHost);
                    status.setText(amHost
                        ? "You are the host - choose AI opponents and press Start."
                        : "Joined - waiting for the host to start the game.");
                }
                else if (msg instanceof Messages.RaceOptions) {
                    updatingRace[0] = true;
                    raceCombo.removeAllItems();
                    for (Messages.RaceInfo ri : ((Messages.RaceOptions) msg).races)
                        raceCombo.addItem(new RaceItem(ri));
                    updatingRace[0] = false;
                    raceLabel.setVisible(true);
                    raceCombo.setVisible(true);
                    lobby.revalidate();
                }
                else if (msg instanceof Messages.Lobby) {
                    for (Messages.Slot s : ((Messages.Lobby) msg).slots) {
                        if ((s.empireId == myEmpireId[0]) && (s.raceId != null))
                            selectRace(raceCombo, s.raceId, updatingRace);
                    }
                }
                else if (msg instanceof Messages.GameStarted) {
                    raceLabel.setVisible(false);
                    raceCombo.setVisible(false);
                    aiLabel.setVisible(false);
                    aiSpinner.setVisible(false);
                    startBtn.setVisible(false);
                }
                handleMessage(msg, galaxyPanel, colonyPanel, systemInfoPanel, researchPanel, fleetsPanel, shipDesignPanel, empirePanel, lastView, status, nextTurn);
            }),
            text -> SwingUtilities.invokeLater(() -> status.setText(text)));
        clientHolder[0] = client;

        nextTurn.addActionListener(e -> ready.run());

        client.connect();
    }

    private static void handleMessage(Object msg, GalaxyViewPanel galaxyPanel, ColonyPanel colonyPanel,
                                      SystemInfoPanel systemInfoPanel,
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
            systemInfoPanel.updateFromView(view);
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
        else if (msg instanceof Messages.GameOver) {
            Messages.GameOver go = (Messages.GameOver) msg;
            status.setText((go.won ? "YOU WON - " : "GAME OVER - ") + go.text);
            nextTurn.setEnabled(false);
            javax.swing.JOptionPane.showMessageDialog(null, go.text,
                go.won ? "Victory" : "Game Over",
                go.won ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                       : javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        else if (msg instanceof Messages.Error) {
            status.setText("Server error: "+((Messages.Error) msg).text);
        }
    }

    /** true if the system is one of the player's own colonies (has colony detail in the view) */
    private static boolean isOwnColony(PlayerView v, int sysId) {
        for (PlayerView.SystemDto s : v.systems)
            if ((s.id == sysId) && (s.colony != null))
                return true;
        return false;
    }

    /** true if the player has scouted the system (its planet is known) */
    private static boolean isScouted(PlayerView v, int sysId) {
        for (PlayerView.SystemDto s : v.systems)
            if (s.id == sysId)
                return s.scouted;
        return false;
    }

    /** select the combo entry for a race id without firing a pick back to the server */
    private static void selectRace(JComboBox<RaceItem> combo, String raceId, boolean[] guard) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).info.id.equals(raceId)) {
                if (combo.getSelectedIndex() != i) {
                    guard[0] = true;
                    combo.setSelectedIndex(i);
                    guard[0] = false;
                }
                return;
            }
        }
    }

    /** combo wrapper so the race dropdown shows the race name */
    private static class RaceItem {
        final Messages.RaceInfo info;
        RaceItem(Messages.RaceInfo info) { this.info = info; }
        @Override public String toString() { return info.name; }
    }
}
