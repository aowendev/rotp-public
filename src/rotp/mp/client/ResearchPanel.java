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
    private final JLabel[] researchingLabels = new JLabel[6];
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
            researchingLabels[i] = new JLabel(" ");
            researchingLabels[i].setFont(researchingLabels[i].getFont().deriveFont(Font.ITALIC, 10f));
            researchingLabels[i].setForeground(java.awt.Color.GRAY);

            c.gridy = i * 2;
            c.gridx = 0; c.weightx = 0;
            grid.add(new JLabel(CATEGORY[i]), c);
            c.gridx = 1; c.weightx = 1;
            grid.add(s, c);
            c.gridx = 2; c.weightx = 0;
            grid.add(valueLabels[i], c);

            c.gridy = i * 2 + 1;
            c.gridx = 1; c.weightx = 1;
            grid.add(researchingLabels[i], c);
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
        adjusting = true;
        for (int i = 0; i < 6; i++) {
            int v = (t.alloc != null && i < t.alloc.length) ? t.alloc[i] : 0;
            sliders[i].setValue(v);
            String r = (t.researching != null && i < t.researching.length) ? t.researching[i] : null;
            researchingLabels[i].setText((r == null || r.isEmpty()) ? "(nothing selected)" : ("→ " + r));
        }
        adjusting = false;
        dirty = false;
        loaded = true;
        setControlsEnabled(true);
        refreshLabels();
    }

    private void onSliderChanged(int idx) {
        if (adjusting)
            return;
        int[] a = currentValues();
        ColonyAllocations.balance(a, idx, null, MAX_TICKS);
        adjusting = true;
        for (int i = 0; i < 6; i++)
            sliders[i].setValue(a[i]);
        adjusting = false;
        dirty = true;
        refreshLabels();
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
        for (JSlider s : sliders)
            s.setEnabled(on);
        apply.setEnabled(false);
    }

    private int[] currentValues() {
        int[] a = new int[6];
        for (int i = 0; i < 6; i++)
            a[i] = sliders[i].getValue();
        return a;
    }
}
