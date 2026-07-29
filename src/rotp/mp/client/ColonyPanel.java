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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Colony management screen, rendered entirely from PlayerView.ColonyDto and
 * acting via the setColonyAlloc command. Five spending sliders (ship,
 * defense, industry, ecology, research) that always sum to 50 ticks; a
 * server-locked category is shown disabled and excluded from redistribution.
 *
 * This is a DTO-driven client screen: it holds no game model, only the
 * protocol view-model, so the same design maps directly to the eventual
 * browser client.
 */
public class ColonyPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    /** colony spending ticks always sum to this (mirrors ColonySpendingCategory.MAX_TICKS) */
    private static final int MAX_TICKS = 50;
    private static final String[] CATEGORY = {"Ship", "Defense", "Industry", "Ecology", "Research"};

    private final Consumer<Object> orderSender;

    private final JLabel title = new JLabel("No colony selected");
    private final JLabel readout = new JLabel(" ");
    private final JSlider[] sliders = new JSlider[5];
    private final JLabel[] valueLabels = new JLabel[5];
    private final JLabel totalLabel = new JLabel(" ");
    private final JButton apply = new JButton("Apply spending");

    // which design this colony builds (setShipBuild)
    private final JComboBox<DesignItem> buildCombo = new JComboBox<>();
    private final JSpinner buildLimit = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
    private final JButton setBuildBtn = new JButton("Set build");

    private int systemId = -1;
    private boolean[] locked = new boolean[5];
    private List<PlayerView.DesignDto> designs;   // empire designs, for the build dropdown
    private boolean adjusting = false;   // guards programmatic slider writes
    private boolean dirty = false;       // unsent spending edits

    public ColonyPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setPreferredSize(new Dimension(320, 0));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(readout, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            JSlider s = new JSlider(0, MAX_TICKS, 0);
            s.addChangeListener(e -> onSliderChanged(idx));
            sliders[i] = s;
            valueLabels[i] = new JLabel("0%");

            c.gridy = i;
            c.gridx = 0; c.weightx = 0;
            grid.add(new JLabel(CATEGORY[i]), c);
            c.gridx = 1; c.weightx = 1;
            grid.add(s, c);
            c.gridx = 2; c.weightx = 0;
            grid.add(valueLabels[i], c);
        }
        add(grid, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        totalLabel.setAlignmentX(LEFT_ALIGNMENT);
        apply.setAlignmentX(LEFT_ALIGNMENT);
        apply.addActionListener(e -> sendOrder());
        south.add(totalLabel);
        south.add(Box.createVerticalStrut(4));
        south.add(apply);

        south.add(Box.createVerticalStrut(10));
        JLabel buildLabel = new JLabel("Building:");
        buildLabel.setAlignmentX(LEFT_ALIGNMENT);
        south.add(buildLabel);
        buildCombo.setAlignmentX(LEFT_ALIGNMENT);
        south.add(buildCombo);
        JPanel limitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        limitRow.setAlignmentX(LEFT_ALIGNMENT);
        limitRow.add(new JLabel("Limit (0 = none):"));
        limitRow.add(buildLimit);
        limitRow.add(setBuildBtn);
        south.add(limitRow);
        setBuildBtn.addActionListener(e -> sendBuild());
        add(south, BorderLayout.SOUTH);

        setEnabledControls(false);
    }

    /** the system whose colony is currently shown, or -1 */
    public int shownSystemId() {
        return systemId;
    }

    /** open the colony at the given system, loading its current allocations */
    public void showColony(int sysId, PlayerView view) {
        PlayerView.SystemDto sys = system(view, sysId);
        if ((sys == null) || (sys.colony == null)) {
            clear();
            return;
        }
        designs = view.designs;
        systemId = sysId;
        load(sys);
    }

    /** refresh from a fresh view, unless the user has unsent edits or changed focus */
    public void updateFromView(PlayerView view) {
        if (systemId < 0)
            return;
        designs = view.designs;
        PlayerView.SystemDto sys = system(view, systemId);
        if ((sys == null) || (sys.colony == null)) {
            clear();
            return;
        }
        if (!dirty)
            load(sys);
        else
            title.setText(sys.name + "  (unsent changes)");
    }

    private void load(PlayerView.SystemDto sys) {
        PlayerView.ColonyDto col = sys.colony;
        title.setText(sys.name.isEmpty() ? ("System " + sys.id) : sys.name);
        readout.setText(String.format("<html>pop %d &nbsp; factories %d &nbsp; bases %d<br>production %d BC/turn</html>",
            Math.round(col.population), Math.round(col.factories), Math.round(col.bases), Math.round(col.production)));
        locked = (col.locked != null) ? col.locked.clone() : new boolean[5];
        adjusting = true;
        for (int i = 0; i < 5; i++) {
            int v = (col.alloc != null && i < col.alloc.length) ? col.alloc[i] : 0;
            sliders[i].setValue(v);
            sliders[i].setEnabled(!locked[i]);
        }
        adjusting = false;
        loadBuild(col);
        dirty = false;
        setEnabledControls(true);
        refreshLabels();
    }

    /** populate the build dropdown from the empire's designs, preselecting the current one */
    private void loadBuild(PlayerView.ColonyDto col) {
        buildCombo.removeAllItems();
        if (designs != null)
            for (PlayerView.DesignDto d : designs)
                buildCombo.addItem(new DesignItem(d.slot, d.name));
        if (col.shipyardDesign != null)
            for (int i = 0; i < buildCombo.getItemCount(); i++)
                if (col.shipyardDesign.equals(buildCombo.getItemAt(i).name)) {
                    buildCombo.setSelectedIndex(i);
                    break;
                }
        buildLimit.setValue(col.buildLimit);
    }

    private void sendBuild() {
        DesignItem d = (DesignItem) buildCombo.getSelectedItem();
        if (systemId < 0 || d == null)
            return;
        Messages.SetShipBuild msg = new Messages.SetShipBuild();
        msg.systemId = systemId;
        msg.designSlot = d.slot;
        msg.buildLimit = (Integer) buildLimit.getValue();
        orderSender.accept(msg);
    }

    private void clear() {
        systemId = -1;
        dirty = false;
        title.setText("No colony selected");
        readout.setText("Click one of your colonies on the map.");
        totalLabel.setText(" ");
        setEnabledControls(false);
    }

    private void onSliderChanged(int idx) {
        if (adjusting)
            return;
        int[] a = currentSliderValues();
        ColonyAllocations.balance(a, idx, locked, MAX_TICKS);
        adjusting = true;
        for (int i = 0; i < 5; i++)
            sliders[i].setValue(a[i]);
        adjusting = false;
        dirty = true;
        refreshLabels();
    }

    private void sendOrder() {
        if (systemId < 0)
            return;
        Messages.SetColonyAllocations msg = new Messages.SetColonyAllocations();
        msg.systemId = systemId;
        msg.alloc = currentSliderValues();
        orderSender.accept(msg);
        dirty = false;
        totalLabel.setText("Sent - awaiting confirmation");
    }

    private void refreshLabels() {
        int[] a = currentSliderValues();
        for (int i = 0; i < 5; i++)
            valueLabels[i].setText((a[i] * 2) + "%" + (locked[i] ? " (locked)" : ""));
        int total = ColonyAllocations.sum(a);
        totalLabel.setText("Allocated " + total + " / " + MAX_TICKS
            + (total == MAX_TICKS ? "" : "  (must total " + MAX_TICKS + ")"));
        apply.setEnabled((systemId >= 0) && (total == MAX_TICKS) && dirty);
    }

    private void setEnabledControls(boolean on) {
        for (int i = 0; i < 5; i++)
            sliders[i].setEnabled(on && !locked[i]);
        apply.setEnabled(false);
        buildCombo.setEnabled(on);
        buildLimit.setEnabled(on);
        setBuildBtn.setEnabled(on && buildCombo.getItemCount() > 0);
    }

    private int[] currentSliderValues() {
        int[] a = new int[5];
        for (int i = 0; i < 5; i++)
            a[i] = sliders[i].getValue();
        return a;
    }

    private static PlayerView.SystemDto system(PlayerView v, int id) {
        for (PlayerView.SystemDto s : v.systems)
            if (s.id == id)
                return s;
        return null;
    }

    /** a ship design choice in the build dropdown */
    private static final class DesignItem {
        final int slot;
        final String name;
        DesignItem(int slot, String name) { this.slot = slot; this.name = name; }
        @Override public String toString() { return name; }
    }
}

