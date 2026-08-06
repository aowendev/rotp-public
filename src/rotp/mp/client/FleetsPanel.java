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
    private final JPanel shipCountsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private JSpinner[] shipSpinners = new JSpinner[0];   // one per design slot with ships
    private final JComboBox<SysItem> deployDest = new JComboBox<>();
    private final JButton deployBtn = new JButton("Send selected ships");
    private final JLabel rangeWarn = new JLabel(" ");

    private final DefaultListModel<String> transportModel = new DefaultListModel<>();
    private final JList<String> transportList = new JList<>(transportModel);
    private final JComboBox<SysItem> transFrom = new JComboBox<>();
    private final JComboBox<SysItem> transDest = new JComboBox<>();
    private final JSpinner transSize = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JButton sendBtn = new JButton("Send colonists");
    private final JButton abortBtn = new JButton("Abort");

    private PlayerView view;

    public FleetsPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(section("Send ships (scouts, warships, colony ships)"));
        add(hint("Pick a fleet, set how many of each ship, then click a destination star."));
        fleetList.setVisibleRowCount(6);
        add(scroll(fleetList, 420, 120));
        shipCountsPanel.setAlignmentX(LEFT_ALIGNMENT);
        add(shipCountsPanel);
        JPanel deployRow = row();
        deployRow.add(new JLabel("Destination:"));
        deployRow.add(deployDest);
        deployRow.add(deployBtn);
        add(deployRow);
        rangeWarn.setAlignmentX(LEFT_ALIGNMENT);
        rangeWarn.setFont(rangeWarn.getFont().deriveFont(Font.ITALIC, 11f));
        add(rangeWarn);

        // rebuild the per-ship-type spinners whenever the selected fleet changes
        fleetList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                rebuildShipCounts();
        });
        // recheck range whenever the destination changes
        deployDest.addActionListener(e -> updateRangeWarning());

        add(javax.swing.Box.createVerticalStrut(10));
        add(section("Move colonists (population between your colonies)"));
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
        else if (fleetModel.size() > 0)
            fleetList.setSelectedIndex(0);   // pre-select so a fleet is ready to send
        rebuildShipCounts();

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

    /** rebuild the per-design spinners for the currently selected fleet */
    private void rebuildShipCounts() {
        shipCountsPanel.removeAll();
        PlayerView.FleetDto f = selectedFleet();
        shipSpinners = (f == null) ? new JSpinner[0] : new JSpinner[f.counts.length];
        if (f != null) {
            for (int slot = 0; slot < f.counts.length; slot++) {
                int have = f.counts[slot];
                if (have <= 0)
                    continue;
                // default to the full count, so leaving it alone sends everything
                JSpinner sp = new JSpinner(new SpinnerNumberModel(have, 0, have, 1));
                sp.addChangeListener(e -> updateRangeWarning());
                shipSpinners[slot] = sp;
                shipCountsPanel.add(new JLabel(designName(view, slot) + ":"));
                shipCountsPanel.add(sp);
            }
        }
        shipCountsPanel.revalidate();
        shipCountsPanel.repaint();
        updateRangeWarning();
    }

    /** warn if any selected ship type can't reach the chosen destination */
    private void updateRangeWarning() {
        PlayerView.FleetDto f = selectedFleet();
        SysItem dest = (SysItem) deployDest.getSelectedItem();
        if (view == null || f == null || dest == null) {
            rangeWarn.setText(" ");
            return;
        }
        float dist = systemDistance(dest.id);
        java.util.List<String> tooFar = new java.util.ArrayList<>();
        for (int slot = 0; slot < f.counts.length; slot++) {
            if (shipSpinners[slot] == null)
                continue;
            if (((Integer) shipSpinners[slot].getValue()) <= 0)
                continue;
            int range = designRange(slot);
            if (range >= 0 && dist > range)
                tooFar.add(designName(view, slot));
        }
        if (tooFar.isEmpty()) {
            rangeWarn.setForeground(java.awt.Color.GRAY);
            rangeWarn.setText(String.format("Destination %.1f ly away — in range.", dist));
        }
        else {
            rangeWarn.setForeground(new java.awt.Color(170, 40, 40));
            rangeWarn.setText(String.format("Destination %.1f ly away — out of range for: %s",
                dist, String.join(", ", tooFar)));
        }
    }

    private float systemDistance(int sysId) {
        if (view != null)
            for (PlayerView.SystemDto s : view.systems)
                if (s.id == sysId)
                    return s.distance;
        return 0f;
    }

    private int designRange(int slot) {
        if (view != null && view.designs != null)
            for (PlayerView.DesignDto d : view.designs)
                if (d.slot == slot)
                    return d.range;
        return -1;
    }

    private PlayerView.FleetDto selectedFleet() {
        int idx = fleetList.getSelectedIndex();
        return (idx < 0 || idx >= fleetData.size()) ? null : fleetData.get(idx);
    }

    private void deploy() {
        PlayerView.FleetDto f = selectedFleet();
        if (f == null || !FleetView.deployable(f))
            return;
        SysItem dest = (SysItem) deployDest.getSelectedItem();
        if (dest == null)
            return;
        int[] counts = new int[f.counts.length];
        int total = 0;
        for (int slot = 0; slot < counts.length; slot++) {
            if (shipSpinners[slot] != null)
                counts[slot] = (Integer) shipSpinners[slot].getValue();
            total += counts[slot];
        }
        if (total <= 0)
            return;   // nothing selected to send
        Messages.DeployFleet msg = new Messages.DeployFleet();
        msg.fromSystemId = f.atSystemId;
        msg.destSystemId = dest.id;
        // per-design counts; the server treats a full count as a whole-fleet send
        msg.counts = counts;
        orderSender.accept(msg);
    }

    private static String designName(PlayerView v, int slot) {
        if (v != null && v.designs != null)
            for (PlayerView.DesignDto d : v.designs)
                if (d.slot == slot)
                    return d.name;
        return "Design " + slot;
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

    private static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.ITALIC, 11f));
        l.setForeground(java.awt.Color.GRAY);
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
