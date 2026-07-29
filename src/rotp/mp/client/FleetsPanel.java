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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Fleets & transports screen, rendered from PlayerView and acting via the
 * deployFleet / sendTransports / abortTransports commands. Lists your fleets
 * (deploy an orbiting one to any system — the server validates range), and
 * your in-flight transports (send new ones from a colony, or abort pending).
 *
 * DTO-driven, holds no game model — mirrors the eventual browser client.
 */
public class FleetsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Consumer<Object> orderSender;

    private final DefaultListModel<String> fleetModel = new DefaultListModel<>();
    private final JList<String> fleetList = new JList<>(fleetModel);
    private final List<PlayerView.FleetDto> fleetData = new ArrayList<>();
    private final JComboBox<SysItem> deployDest = new JComboBox<>();
    private final JButton deployBtn = new JButton("Deploy whole fleet");

    private final DefaultListModel<String> transportModel = new DefaultListModel<>();
    private final JList<String> transportList = new JList<>(transportModel);
    private final JComboBox<SysItem> transFrom = new JComboBox<>();
    private final JComboBox<SysItem> transDest = new JComboBox<>();
    private final JSpinner transSize = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JButton sendBtn = new JButton("Send");
    private final JButton abortBtn = new JButton("Abort");

    private PlayerView view;

    public FleetsPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(section("Your fleets"));
        fleetList.setVisibleRowCount(6);
        add(scroll(fleetList, 420, 120));
        JPanel deployRow = row();
        deployRow.add(new JLabel("Deploy to:"));
        deployRow.add(deployDest);
        deployRow.add(deployBtn);
        add(deployRow);

        add(javax.swing.Box.createVerticalStrut(10));
        add(section("Population transports (in flight)"));
        transportList.setVisibleRowCount(4);
        add(scroll(transportList, 420, 80));
        JPanel sendRow = row();
        sendRow.add(new JLabel("From:"));
        sendRow.add(transFrom);
        sendRow.add(new JLabel("to:"));
        sendRow.add(transDest);
        sendRow.add(new JLabel("pop:"));
        sendRow.add(transSize);
        sendRow.add(sendBtn);
        sendRow.add(abortBtn);
        add(sendRow);

        deployBtn.addActionListener(e -> deploy());
        sendBtn.addActionListener(e -> sendTransports());
        abortBtn.addActionListener(e -> abortTransports());

        setControlsEnabled(false);
    }

    /** set the deploy destination to a system clicked on the galaxy map */
    public void selectDestination(int sysId) {
        selectById(deployDest, sysId);
    }

    /** the currently chosen deploy destination system id, or -1 */
    public int selectedDestinationId() {
        return selectedId(deployDest);
    }

    public void updateFromView(PlayerView v) {
        this.view = v;
        if (v == null)
            return;

        // fleets
        int prevFleet = fleetList.getSelectedIndex();
        fleetModel.clear();
        fleetData.clear();
        for (PlayerView.FleetDto f : v.fleets) {
            fleetData.add(f);
            fleetModel.addElement(describeFleet(f));
        }
        if (prevFleet >= 0 && prevFleet < fleetModel.size())
            fleetList.setSelectedIndex(prevFleet);

        // transports in flight
        transportModel.clear();
        for (PlayerView.TransportDto t : v.transports)
            transportModel.addElement(t.size + " pop -> " + systemName(t.destSystemId));

        // destination combos (all systems); from-combo = own colonies
        int keepDeploy = selectedId(deployDest);
        int keepTransDest = selectedId(transDest);
        int keepFrom = selectedId(transFrom);
        fillSystems(deployDest, v, false);
        fillSystems(transDest, v, false);
        fillSystems(transFrom, v, true);
        selectById(deployDest, keepDeploy);
        selectById(transDest, keepTransDest);
        selectById(transFrom, keepFrom);

        setControlsEnabled(true);
    }

    private void deploy() {
        int idx = fleetList.getSelectedIndex();
        if (idx < 0 || idx >= fleetData.size())
            return;
        PlayerView.FleetDto f = fleetData.get(idx);
        if (!FleetView.deployable(f))
            return;
        SysItem dest = (SysItem) deployDest.getSelectedItem();
        if (dest == null)
            return;
        Messages.DeployFleet msg = new Messages.DeployFleet();
        msg.fromSystemId = f.atSystemId;
        msg.destSystemId = dest.id;
        msg.counts = null;   // whole fleet
        orderSender.accept(msg);
    }

    private void sendTransports() {
        SysItem from = (SysItem) transFrom.getSelectedItem();
        SysItem dest = (SysItem) transDest.getSelectedItem();
        if (from == null || dest == null)
            return;
        Messages.SendTransports msg = new Messages.SendTransports();
        msg.fromSystemId = from.id;
        msg.destSystemId = dest.id;
        msg.size = (Integer) transSize.getValue();
        orderSender.accept(msg);
    }

    private void abortTransports() {
        SysItem from = (SysItem) transFrom.getSelectedItem();
        if (from == null)
            return;
        Messages.AbortTransports msg = new Messages.AbortTransports();
        msg.fromSystemId = from.id;
        orderSender.accept(msg);
    }

    private String describeFleet(PlayerView.FleetDto f) {
        String where = FleetView.isOrbiting(f)
            ? "at " + systemName(f.atSystemId)
            : "in transit";
        String dest = (f.destSystemId >= 0) ? " -> " + systemName(f.destSystemId) : "";
        return where + dest + ": " + FleetView.summarize(f, view.designs);
    }

    private String systemName(int sysId) {
        if (view != null)
            for (PlayerView.SystemDto s : view.systems)
                if (s.id == sysId)
                    return (s.name == null || s.name.isEmpty()) ? ("System " + sysId) : s.name;
        return "System " + sysId;
    }

    private static void fillSystems(JComboBox<SysItem> combo, PlayerView v, boolean ownColoniesOnly) {
        combo.removeAllItems();
        for (PlayerView.SystemDto s : v.systems) {
            if (ownColoniesOnly && s.colony == null)
                continue;
            String label = (s.name == null || s.name.isEmpty()) ? ("System " + s.id) : s.name;
            combo.addItem(new SysItem(s.id, label));
        }
    }

    private static int selectedId(JComboBox<SysItem> combo) {
        SysItem s = (SysItem) combo.getSelectedItem();
        return s == null ? -1 : s.id;
    }

    private static void selectById(JComboBox<SysItem> combo, int id) {
        for (int i = 0; i < combo.getItemCount(); i++)
            if (combo.getItemAt(i).id == id) {
                combo.setSelectedIndex(i);
                return;
            }
    }

    private void setControlsEnabled(boolean on) {
        deployBtn.setEnabled(on);
        deployDest.setEnabled(on);
        transFrom.setEnabled(on);
        transDest.setEnabled(on);
        transSize.setEnabled(on);
        sendBtn.setEnabled(on);
        abortBtn.setEnabled(on);
    }

    private static JLabel section(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JScrollPane scroll(Component c, int w, int h) {
        JScrollPane sp = new JScrollPane(c);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(w, h));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return sp;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    /** a system choice in a combo box */
    private static final class SysItem {
        final int id;
        final String label;
        SysItem(int id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }
}
