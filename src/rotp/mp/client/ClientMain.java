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
 * Multiplayer reference client:
 *   java -jar rotp.jar --client [host=localhost] [port=8777] [name=Player]
 *   java -jar rotp.jar --client url=wss://rotp.example.com/game/1 name=Alice
 *
 * Connects to a server, joins the lobby, and renders every screen from
 * PlayerView JSON, holding no game model of its own.
 *
 * `url=` is the form to use against a hosted server: a game behind a
 * TLS-terminating proxy lives at a path on 443 rather than a bare host:port
 * (see docs/deployment.md), and it must be wss:// — a browser on https cannot
 * open a plain ws:// socket, so the deployed server speaks wss only.
 */
public class ClientMain {
    public static void run(String[] args) {
        String serverUrl = serverUrl(args);
        String name = ServerMain.stringArg(args, "name", System.getProperty("user.name", "Player"));
        SwingUtilities.invokeLater(() -> createUI(serverUrl, name));
    }

    /** the WebSocket URL to connect to: an explicit url= wins, else host+port
     * as a plain ws:// address (pure, so it is unit-tested) */
    public static String serverUrl(String[] args) {
        String url = ServerMain.stringArg(args, "url", null);
        if ((url != null) && !url.isEmpty())
            return url;
        String host = ServerMain.stringArg(args, "host", "localhost");
        int port = ServerMain.intArg(args, "port", ServerMain.DEFAULT_PORT);
        return "ws://" + host + ":" + port;
    }

    private static void createUI(String serverUrl, String name) {
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
        EmpirePanel empirePanel = new EmpirePanel(order -> clientHolder[0].sendMessage(order));
        RacesPanel racesPanel = new RacesPanel(order -> clientHolder[0].sendMessage(order));
        JLabel status = new JLabel("Connecting to "+serverUrl+"...");
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

        JFrame racesWindow = new JFrame("Races");
        racesWindow.add(racesPanel);
        racesWindow.setSize(640, 560);
        racesWindow.setLocationByPlatform(true);

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
        JMenuItem saveItem = new JMenuItem("Save Game");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask));
        saveItem.addActionListener(e -> {
            String saveName = javax.swing.JOptionPane.showInputDialog(frame, "Save name:", "mp_save");
            if ((saveName != null) && !saveName.trim().isEmpty()) {
                Messages.SaveGame sg = new Messages.SaveGame();
                sg.name = saveName.trim();
                clientHolder[0].sendMessage(sg);
            }
        });
        JMenuItem racesItem = new JMenuItem("Races");
        racesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask));
        racesItem.addActionListener(e -> racesWindow.setVisible(!racesWindow.isVisible()));
        JMenuItem nextTurnItem = new JMenuItem("Next Turn (Ready)");
        nextTurnItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask));
        nextTurnItem.addActionListener(e -> { if (nextTurn.isEnabled()) ready.run(); });
        misc.add(techItem);
        misc.add(saveItem);
        misc.add(racesItem);
        misc.add(nextTurnItem);
        menuBar.add(misc);
        frame.setJMenuBar(menuBar);

        // host-only lobby controls: choose AI opponents and start the game with
        // the humans present (hidden until we learn we're the host, and on start)
        final boolean[] amHost = {false};
        JLabel aiLabel = new JLabel("AI opponents:");
        JSpinner aiSpinner = new JSpinner(new SpinnerNumberModel(2, 0, 8, 1));
        // galaxy size picker (host-only, populated from the server's sizeOptions)
        JLabel sizeLabel = new JLabel("Galaxy:");
        JComboBox<SizeItem> sizeCombo = new JComboBox<>();
        // "difficulty" in ROTP is really the AI's ability, so label it as such
        JLabel difficultyLabel = new JLabel("AI ability:");
        JComboBox<DifficultyItem> difficultyCombo = new JComboBox<>();
        difficultyCombo.setToolTipText("Sets the AI opponents' strength (their economy). "
            + "Higher = tougher AI, not a harder puzzle for you.");
        // turn timer picker (host-only): auto-resolve a we-go turn after N seconds (0 = off)
        JLabel timerLabel = new JLabel("Turn timer (s):");
        JSpinner timerSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 600, 10));
        timerSpinner.setToolTipText("Auto-resolve a turn this many seconds after orders open, "
            + "so an absent player can't stall the game. 0 = off (no timer).");
        JButton startBtn = new JButton("Start Game");
        aiLabel.setVisible(false);
        aiSpinner.setVisible(false);
        sizeLabel.setVisible(false);
        sizeCombo.setVisible(false);
        difficultyLabel.setVisible(false);
        difficultyCombo.setVisible(false);
        timerLabel.setVisible(false);
        timerSpinner.setVisible(false);
        startBtn.setVisible(false);
        startBtn.addActionListener(e -> {
            Messages.StartGame sg = new Messages.StartGame();
            sg.aiOpponents = (Integer) aiSpinner.getValue();
            SizeItem sz = (SizeItem) sizeCombo.getSelectedItem();
            if (sz != null)
                sg.galaxySize = sz.id;
            DifficultyItem df = (DifficultyItem) difficultyCombo.getSelectedItem();
            if (df != null)
                sg.difficulty = df.id;
            sg.turnTimerSeconds = (Integer) timerSpinner.getValue();
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
        lobby.add(sizeLabel);
        lobby.add(sizeCombo);
        lobby.add(difficultyLabel);
        lobby.add(difficultyCombo);
        lobby.add(timerLabel);
        lobby.add(timerSpinner);
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
            URI.create(serverUrl),
            name,
            msg -> SwingUtilities.invokeLater(() -> {
                if (msg instanceof Messages.Joined) {
                    Messages.Joined j = (Messages.Joined) msg;
                    myEmpireId[0] = j.empireId;
                    amHost[0] = j.host;
                    aiLabel.setVisible(j.host);
                    aiSpinner.setVisible(j.host);
                    timerLabel.setVisible(j.host);
                    timerSpinner.setVisible(j.host);
                    // size/AI-ability pickers show once their options arrive; reveal now if already loaded
                    sizeLabel.setVisible(j.host && sizeCombo.getItemCount() > 0);
                    sizeCombo.setVisible(j.host && sizeCombo.getItemCount() > 0);
                    difficultyLabel.setVisible(j.host && difficultyCombo.getItemCount() > 0);
                    difficultyCombo.setVisible(j.host && difficultyCombo.getItemCount() > 0);
                    startBtn.setVisible(j.host);
                    status.setText(j.host
                        ? "You are the host - choose galaxy size and AI opponents, then press Start."
                        : "Joined - waiting for the host to start the game.");
                }
                else if (msg instanceof Messages.SizeOptions) {
                    Messages.SizeOptions so = (Messages.SizeOptions) msg;
                    sizeCombo.removeAllItems();
                    for (Messages.SizeInfo si : so.sizes)
                        sizeCombo.addItem(new SizeItem(si));
                    selectSize(sizeCombo, so.selectedId);
                    // only the host chooses the size
                    sizeLabel.setVisible(amHost[0]);
                    sizeCombo.setVisible(amHost[0]);
                    lobby.revalidate();
                }
                else if (msg instanceof Messages.DifficultyOptions) {
                    Messages.DifficultyOptions dop = (Messages.DifficultyOptions) msg;
                    difficultyCombo.removeAllItems();
                    for (Messages.DifficultyInfo di : dop.levels)
                        difficultyCombo.addItem(new DifficultyItem(di));
                    selectDifficulty(difficultyCombo, dop.selectedId);
                    // only the host chooses the AI ability
                    difficultyLabel.setVisible(amHost[0]);
                    difficultyCombo.setVisible(amHost[0]);
                    lobby.revalidate();
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
                    sizeLabel.setVisible(false);
                    sizeCombo.setVisible(false);
                    difficultyLabel.setVisible(false);
                    difficultyCombo.setVisible(false);
                    timerLabel.setVisible(false);
                    timerSpinner.setVisible(false);
                    aiLabel.setVisible(false);
                    aiSpinner.setVisible(false);
                    startBtn.setVisible(false);
                }
                else if (msg instanceof Messages.Prompts) {
                    // interactive decisions from the server (Phase 3). SELECT_TECH pops
                    // a chooser for the category that just completed a tech; the pick
                    // overrides the server's auto-selected default. INCOMING_DIPLOMACY
                    // pops an accept/decline dialog for an offer another empire made.
                    for (Messages.Prompt p : ((Messages.Prompts) msg).items) {
                        if ("SELECT_TECH".equals(p.type))
                            researchPanel.promptSelectTech(p);
                        else if ("INCOMING_DIPLOMACY".equals(p.type))
                            promptIncomingDiplomacy(frame, clientHolder[0], p);
                        else if ("COUNCIL_VOTE".equals(p.type))
                            promptCouncilVote(frame, clientHolder[0], p);
                        else if ("COLONIZE".equals(p.type))
                            promptColonize(frame, clientHolder[0], p);
                        else if ("INCOMING_TECH_REQUEST".equals(p.type))
                            racesPanel.promptIncomingTechRequest(p);
                        else if ("BOMBARD".equals(p.type))
                            promptBombard(frame, clientHolder[0], p);
                    }
                }
                handleMessage(msg, galaxyPanel, colonyPanel, systemInfoPanel, researchPanel, fleetsPanel, shipDesignPanel, empirePanel, racesPanel, lastView, status, nextTurn);
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
                                      RacesPanel racesPanel,
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
            racesPanel.updateFromView(view);
            status.setText(view.empireName+"  -  "+view.year+" (turn "+view.turn+")");
            nextTurn.setEnabled(true);
        }
        else if (msg instanceof Messages.TurnStatus) {
            Messages.TurnStatus ts = (Messages.TurnStatus) msg;
            String timer = (ts.secondsRemaining >= 0) ? ", "+ts.secondsRemaining+"s left" : "";
            status.setText(ts.note+" (turn "+ts.turn+", ready "+ts.readyCount+"/"+ts.totalPlayers+timer+")");
            if (ts.processing)
                nextTurn.setEnabled(false);
        }
        else if (msg instanceof Messages.CommandResult) {
            Messages.CommandResult cr = (Messages.CommandResult) msg;
            if ("saveGame".equals(cr.command))
                status.setText(cr.text);                 // show save success/failure
            else if (!cr.ok)
                status.setText("Order rejected ("+cr.command+"): "+cr.text);
        }
        else if (msg instanceof Messages.DesignCatalog) {
            shipDesignPanel.setCatalog((Messages.DesignCatalog) msg);
        }
        else if (msg instanceof Messages.ColonyPreview) {
            colonyPanel.onPreview((Messages.ColonyPreview) msg);
        }
        else if (msg instanceof Messages.DiploReply) {
            Messages.DiploReply dr = (Messages.DiploReply) msg;
            racesPanel.showReply(dr);
            status.setText("Diplomatic reply: " + (dr.accepted ? "accepted" : "refused")
                + " your " + dr.action + " offer");
        }
        else if (msg instanceof Messages.TechTradeMenu) {
            racesPanel.showAudience((Messages.TechTradeMenu) msg);
        }
        else if (msg instanceof Messages.TechCounterOffer) {
            // the second half of a tech exchange: their price for what you asked for
            racesPanel.showCounterOffer((Messages.TechCounterOffer) msg);
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

    /**
     * An INCOMING_DIPLOMACY prompt: another empire is offering a treaty/trade. Pops an
     * accept/decline dialog and sends the human's answer as a RespondDiplomacy command.
     * Declining is the safe default (window closed / cancelled).
     */
    private static void promptIncomingDiplomacy(JFrame frame, NetClient client, Messages.Prompt p) {
        String body = (p.text == null ? "Another empire has made you a diplomatic offer." : p.text) + ".";
        int pick = javax.swing.JOptionPane.showConfirmDialog(frame, body + "\n\nAccept?",
            "Incoming Diplomacy", javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.QUESTION_MESSAGE);
        Messages.RespondDiplomacy rd = new Messages.RespondDiplomacy();
        rd.empireId = p.empireId;
        rd.action = p.action;
        rd.accept = (pick == javax.swing.JOptionPane.YES_OPTION);
        client.sendMessage(rd);
    }

    /**
     * A COUNCIL_VOTE prompt: the Galactic Council is electing a leader and it is this
     * player's turn to vote. Pops a chooser of the candidates (plus Abstain) and sends
     * the pick as a CastCouncilVote. Cancelling abstains (the safe default).
     */
    private static void promptCouncilVote(JFrame frame, NetClient client, Messages.Prompt p) {
        if (p.choiceNames == null || p.choiceNames.length == 0)
            return;
        String text = (p.text == null) ? "Cast your council vote" : p.text;
        Object chosen = javax.swing.JOptionPane.showInputDialog(frame, text + ":",
            "Galactic Council", javax.swing.JOptionPane.QUESTION_MESSAGE,
            null, p.choiceNames, p.choiceNames[0]);
        Messages.CastCouncilVote v = new Messages.CastCouncilVote();
        v.candidateId = -1;   // abstain by default (also if the dialog was cancelled)
        if (chosen != null) {
            for (int i = 0; i < p.choiceNames.length; i++) {
                if (p.choiceNames[i].equals(chosen)) {
                    try { v.candidateId = Integer.parseInt(p.choiceIds[i]); }
                    catch (NumberFormatException ignored) { v.candidateId = -1; }
                    break;
                }
            }
        }
        client.sendMessage(v);
    }

    /**
     * A COLONIZE prompt: a colony ship is orbiting a settle-able, uncolonized system.
     * Pops a yes/no dialog and, on yes, sends the existing Colonize command. Declining
     * (or closing) leaves the colony ship in orbit — the server will ask again next turn.
     */
    private static void promptColonize(JFrame frame, NetClient client, Messages.Prompt p) {
        String body = (p.text == null ? "Colonize this system?" : p.text);
        int pick = javax.swing.JOptionPane.showConfirmDialog(frame, body,
            "Colonize", javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (pick == javax.swing.JOptionPane.YES_OPTION) {
            Messages.Colonize c = new Messages.Colonize();
            c.systemId = p.systemId;
            client.sendMessage(c);
        }
    }

    /**
     * Your fleet holds orbit over a colony it can bomb. The battle itself already
     * auto-resolved; this is the separate, deliberate choice to bombard another
     * player's world. Declining leaves the fleet in orbit and asks again next turn.
     */
    private static void promptBombard(JFrame frame, NetClient client, Messages.Prompt p) {
        String body = (p.text == null ? "Bombard this colony?" : p.text);
        int pick = javax.swing.JOptionPane.showConfirmDialog(frame, body,
            "Bombard", javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (pick == javax.swing.JOptionPane.YES_OPTION) {
            Messages.Bombard b = new Messages.Bombard();
            b.systemId = p.systemId;
            client.sendMessage(b);
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

    /** select the combo entry for a galaxy-size id (no action needed - the size
     * is only read when Start is pressed, so no guard against re-firing) */
    private static void selectSize(JComboBox<SizeItem> combo, String sizeId) {
        if (sizeId == null)
            return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id.equals(sizeId)) {
                combo.setSelectedIndex(i);
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

    /** combo wrapper showing a galaxy size and its star count */
    private static class SizeItem {
        final String id;
        final String label;
        SizeItem(Messages.SizeInfo info) {
            this.id = info.id;
            this.label = (info.stars > 0) ? info.name + " (" + info.stars + " stars)" : info.name;
        }
        @Override public String toString() { return label; }
    }

    /** select the combo entry for a difficulty id (read only when Start is pressed) */
    private static void selectDifficulty(JComboBox<DifficultyItem> combo, String id) {
        if (id == null)
            return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id.equals(id)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** combo wrapper for a difficulty level, shown as the AI's economy strength so
     * it reads as "how able the AI is", not "how hard the game is for me" */
    private static class DifficultyItem {
        final String id;
        final String label;
        DifficultyItem(Messages.DifficultyInfo info) {
            this.id = info.id;
            this.label = info.name + " - AI " + info.aiProductionPct + "%";
        }
        @Override public String toString() { return label; }
    }
}
