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
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Empire research screen, rendered from PlayerView.TechDto and acting via the
 * setTechAllocations command. Six category sliders (Computers, Construction,
 * Force Fields, Planetology, Propulsion, Weapons) whose ticks always sum to
 * 60 (= 100% of research), reusing the same pure redistribution logic as the
 * colony screen.
 *
 * DTO-driven, holds no game model — mirrors the eventual browser client.
 */
public class ResearchPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    /** research ticks always sum to this (mirrors TechCategory.MAX_ALLOCATION_TICKS) */
    private static final int MAX_TICKS = 60;
    private static final String[] CATEGORY = {
        "Computers", "Construction", "Force Fields", "Planetology", "Propulsion", "Weapons"
    };

    private final Consumer<Object> orderSender;

    private final JLabel rpLabel = new JLabel(" ");
    private final JSlider[] sliders = new JSlider[6];
    private final JLabel[] valueLabels = new JLabel[6];
    /** per-category completion progress toward the current tech (separate from allocation %) */
    private final JLabel[] progressLabels = new JLabel[6];
    /** per-category lock: a locked category holds its value during redistribution */
    private final javax.swing.JCheckBox[] lockChecks = new javax.swing.JCheckBox[6];
    private boolean[] locked = new boolean[6];
    @SuppressWarnings("unchecked")
    private final javax.swing.JComboBox<ChoiceItem>[] choiceCombos = new javax.swing.JComboBox[6];
    private final JLabel totalLabel = new JLabel(" ");
    private final JButton apply = new JButton("Apply research");

    private boolean adjusting = false;   // guards programmatic slider writes
    private boolean dirty = false;       // unsent local edits
    private boolean loaded = false;

    public ResearchPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        JLabel title = new JLabel("Research");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(rpLabel, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            JSlider s = new JSlider(0, MAX_TICKS, 0);
            s.addChangeListener(e -> onSliderChanged(idx));
            sliders[i] = s;
            valueLabels[i] = new JLabel("0%");
            javax.swing.JComboBox<ChoiceItem> choice = new javax.swing.JComboBox<>();
            choice.addActionListener(e -> onChoiceChanged(idx));
            choiceCombos[i] = choice;
            javax.swing.JCheckBox lock = new javax.swing.JCheckBox();
            lock.setToolTipText("Lock this category so it keeps its value when others change");
            lock.addActionListener(e -> onLockToggled(idx));
            lockChecks[i] = lock;

            c.gridy = i * 2;
            c.gridx = 0; c.weightx = 0;
            grid.add(new JLabel(CATEGORY[i]), c);
            c.gridx = 1; c.weightx = 1;
            grid.add(s, c);
            c.gridx = 2; c.weightx = 0;
            grid.add(valueLabels[i], c);
            c.gridx = 3; c.weightx = 0;
            grid.add(lock, c);

            progressLabels[i] = new JLabel(" ");
            progressLabels[i].setToolTipText("How close this category's current research is to completion");

            c.gridy = i * 2 + 1;
            c.gridx = 0; c.weightx = 0;
            grid.add(new JLabel("  research:"), c);
            c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
            grid.add(choice, c);
            c.gridwidth = 1;
            c.gridx = 3; c.weightx = 0;
            grid.add(progressLabels[i], c);
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
        add(south, BorderLayout.SOUTH);

        setControlsEnabled(false);
    }

    /** refresh from a fresh view, unless the user has unsent edits */
    public void updateFromView(PlayerView view) {
        if ((view == null) || (view.tech == null))
            return;
        if (!dirty)
            load(view.tech);
    }

    private void load(PlayerView.TechDto t) {
        rpLabel.setText(String.format("%.0f research points/turn", t.totalRP));
        locked = (t.locked != null) ? t.locked.clone() : new boolean[6];
        adjusting = true;
        for (int i = 0; i < 6; i++) {
            int v = (t.alloc != null && i < t.alloc.length) ? t.alloc[i] : 0;
            sliders[i].setValue(v);
            sliders[i].setEnabled(!locked[i]);
            lockChecks[i].setSelected(locked[i]);   // setSelected does not fire the action listener
            loadChoices(i, t);
            boolean researching = (t.researchingId != null) && (i < t.researchingId.length) && (t.researchingId[i] != null);
            float pr = (t.progress != null && i < t.progress.length) ? t.progress[i] : 0f;
            progressLabels[i].setText(!researching ? " " : (pr >= 1f ? "ready" : Math.round(pr * 100) + "% done"));
        }
        adjusting = false;
        dirty = false;
        loaded = true;
        setControlsEnabled(true);
        refreshLabels();
    }

    /** populate a category's research-target dropdown, preselecting the current tech */
    private void loadChoices(int i, PlayerView.TechDto t) {
        javax.swing.JComboBox<ChoiceItem> combo = choiceCombos[i];
        combo.removeAllItems();
        String currentId = (t.researchingId != null && i < t.researchingId.length) ? t.researchingId[i] : null;
        boolean hasCurrent = false;
        if (t.choices != null && i < t.choices.size())
            for (PlayerView.TechChoice ch : t.choices.get(i)) {
                combo.addItem(new ChoiceItem(ch));
                if (ch.id.equals(currentId))
                    hasCurrent = true;
            }
        // the tech currently being researched is no longer in the "available" list
        if (currentId != null && !hasCurrent) {
            String name = (t.researching != null && i < t.researching.length) ? t.researching[i] : currentId;
            combo.insertItemAt(new ChoiceItem(currentId, name), 0);
        }
        if (combo.getItemCount() == 0)
            combo.addItem(new ChoiceItem(null, "(nothing to research)"));
        selectChoice(combo, currentId);
    }

    private void onChoiceChanged(int idx) {
        if (adjusting)
            return;
        ChoiceItem sel = (ChoiceItem) choiceCombos[idx].getSelectedItem();
        if (sel == null || sel.id == null)
            return;
        Messages.SetResearchChoice msg = new Messages.SetResearchChoice();
        msg.category = idx;
        msg.techId = sel.id;
        orderSender.accept(msg);
    }

    private static void selectChoice(javax.swing.JComboBox<ChoiceItem> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            String itemId = combo.getItemAt(i).id;
            if (itemId != null && itemId.equals(id)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void onSliderChanged(int idx) {
        if (adjusting)
            return;
        int[] a = currentValues();
        ColonyAllocations.balance(a, idx, locked, MAX_TICKS);
        adjusting = true;
        for (int i = 0; i < 6; i++)
            sliders[i].setValue(a[i]);
        adjusting = false;
        dirty = true;
        refreshLabels();
    }

    private void onLockToggled(int idx) {
        boolean lock = lockChecks[idx].isSelected();
        locked[idx] = lock;
        sliders[idx].setEnabled(!lock);
        // if locking with unsent edits, commit the shown split first so the value
        // being locked is the one displayed (mirrors the colony screen)
        if (lock && dirty && (ColonyAllocations.sum(currentValues()) == MAX_TICKS)) {
            Messages.SetTechAllocations alloc = new Messages.SetTechAllocations();
            alloc.alloc = currentValues();
            orderSender.accept(alloc);
            dirty = false;
        }
        Messages.SetTechLock msg = new Messages.SetTechLock();
        msg.category = idx;
        msg.locked = lock;
        orderSender.accept(msg);
    }

    private void sendOrder() {
        Messages.SetTechAllocations msg = new Messages.SetTechAllocations();
        msg.alloc = currentValues();
        orderSender.accept(msg);
        dirty = false;
        totalLabel.setText("Sent - awaiting confirmation");
    }

    private void refreshLabels() {
        int[] a = currentValues();
        for (int i = 0; i < 6; i++)
            valueLabels[i].setText(Math.round(a[i] * 100f / MAX_TICKS) + "%");
        int total = ColonyAllocations.sum(a);
        totalLabel.setText("Allocated " + total + " / " + MAX_TICKS
            + (total == MAX_TICKS ? " (100%)" : ""));
        apply.setEnabled(loaded && (total == MAX_TICKS) && dirty);
    }

    private void setControlsEnabled(boolean on) {
        for (int i = 0; i < 6; i++) {
            sliders[i].setEnabled(on && !locked[i]);
            lockChecks[i].setEnabled(on);
        }
        for (javax.swing.JComboBox<ChoiceItem> c : choiceCombos)
            c.setEnabled(on);
        apply.setEnabled(false);
    }

    private int[] currentValues() {
        int[] a = new int[6];
        for (int i = 0; i < 6; i++)
            a[i] = sliders[i].getValue();
        return a;
    }

    /** a research-target choice in a category dropdown */
    private static final class ChoiceItem {
        final String id;
        final String label;
        ChoiceItem(PlayerView.TechChoice c) {
            this.id = c.id;
            this.label = c.name + " (" + c.cost + " RP)";
        }
        ChoiceItem(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }
}
