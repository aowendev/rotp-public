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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Ship design screen: lists the empire's current designs (and scraps them),
 * and builds a new one in a free slot from components offered by the server's
 * design catalog. Renders from PlayerView.designs + the DesignCatalog message
 * and acts via createDesign / scrapDesign — the server validates hull space.
 *
 * DTO-driven, holds no game model — mirrors the eventual browser client.
 */
public class ShipDesignPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String[] HULL_SIZES = {"Small", "Medium", "Large", "Huge"};

    private final Consumer<Object> orderSender;

    // current designs
    private final DefaultListModel<String> designModel = new DefaultListModel<>();
    private final JList<String> designList = new JList<>(designModel);
    private final List<PlayerView.DesignDto> designData = new ArrayList<>();
    private final JButton scrapBtn = new JButton("Scrap selected");

    // new-design form
    private final JComboBox<Integer> slotCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField(12);
    private final JComboBox<String> sizeCombo = new JComboBox<>(HULL_SIZES);
    private final JComboBox<NamedItem> computerCombo = new JComboBox<>();
    private final JComboBox<NamedItem> shieldCombo = new JComboBox<>();
    private final JComboBox<NamedItem> ecmCombo = new JComboBox<>();
    private final JComboBox<NamedItem> armorCombo = new JComboBox<>();
    private final JComboBox<NamedItem> engineCombo = new JComboBox<>();
    private final JComboBox<NamedItem> maneuverCombo = new JComboBox<>();
    @SuppressWarnings("unchecked")
    private final JComboBox<NamedItem>[] weaponCombos = new JComboBox[4];
    private final JSpinner[] weaponCounts = new JSpinner[4];
    @SuppressWarnings("unchecked")
    private final JComboBox<NamedItem>[] specialCombos = new JComboBox[3];
    private final JButton createBtn = new JButton("Create design");

    private Messages.DesignCatalog catalog;
    private boolean catalogRequested = false;

    public ShipDesignPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(section("Current designs"));
        designList.setVisibleRowCount(5);
        add(scroll(designList, 460, 100));
        scrapBtn.setAlignmentX(LEFT_ALIGNMENT);
        scrapBtn.addActionListener(e -> scrap());
        add(scrapBtn);

        add(Box.createVerticalStrut(10));
        add(section("New design"));
        add(buildForm());
        createBtn.setAlignmentX(LEFT_ALIGNMENT);
        createBtn.addActionListener(e -> create());
        add(createBtn);

        setFormEnabled(false);
        scrapBtn.setEnabled(false);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;
        addField(form, c, y++, "Slot:", slotCombo, "Name:", nameField);
        addField(form, c, y++, "Hull:", sizeCombo, "Computer:", computerCombo);
        addField(form, c, y++, "Shield:", shieldCombo, "ECM:", ecmCombo);
        addField(form, c, y++, "Armor:", armorCombo, "Engine:", engineCombo);
        addField(form, c, y++, "Maneuver:", maneuverCombo, null, null);

        for (int i = 0; i < 4; i++) {
            weaponCombos[i] = new JComboBox<>();
            weaponCounts[i] = new JSpinner(new SpinnerNumberModel(0, 0, 99, 1));
            addField(form, c, y++, "Weapon " + (i + 1) + ":", weaponCombos[i], "count:", weaponCounts[i]);
        }
        for (int i = 0; i < 3; i++) {
            specialCombos[i] = new JComboBox<>();
            addField(form, c, y++, "Special " + (i + 1) + ":", specialCombos[i], null, null);
        }
        return form;
    }

    private static void addField(JPanel form, GridBagConstraints c, int row,
                                 String l1, Component f1, String l2, Component f2) {
        c.gridy = row;
        c.gridx = 0; c.weightx = 0; form.add(new JLabel(l1), c);
        c.gridx = 1; c.weightx = 1; form.add(f1, c);
        if (l2 != null) {
            c.gridx = 2; c.weightx = 0; form.add(new JLabel(l2), c);
            c.gridx = 3; c.weightx = 1; form.add(f2, c);
        }
    }

    /** refresh the current-designs list and free-slot choices from a fresh view */
    public void updateFromView(PlayerView v) {
        if (v == null)
            return;
        int prev = designList.getSelectedIndex();
        designModel.clear();
        designData.clear();
        for (PlayerView.DesignDto d : v.designs) {
            designData.add(d);
            designModel.addElement(describeDesign(d));
        }
        if (prev >= 0 && prev < designModel.size())
            designList.setSelectedIndex(prev);

        Integer keepSlot = (Integer) slotCombo.getSelectedItem();
        slotCombo.removeAllItems();
        for (int slot : ShipDesigns.freeSlots(v.designs))
            slotCombo.addItem(slot);
        if (keepSlot != null)
            slotCombo.setSelectedItem(keepSlot);

        // the catalog is static enough to fetch once; request it on first data
        if (catalog == null && !catalogRequested) {
            orderSender.accept(new Messages.DesignCatalog());
            catalogRequested = true;
        }
        updateEnabled();
    }

    /** server's reply to our DesignCatalog request: the components we may use */
    public void setCatalog(Messages.DesignCatalog cat) {
        this.catalog = cat;
        fill(computerCombo, cat.computers);
        fill(shieldCombo, cat.shields);
        fill(ecmCombo, cat.ecms);
        fill(armorCombo, cat.armors);
        fill(engineCombo, cat.engines);
        fill(maneuverCombo, cat.maneuvers);
        for (JComboBox<NamedItem> w : weaponCombos)
            fill(w, cat.weapons);
        for (JComboBox<NamedItem> s : specialCombos)
            fill(s, cat.specials);
        updateEnabled();
    }

    private void create() {
        Integer slot = (Integer) slotCombo.getSelectedItem();
        if (slot == null)
            return;
        Messages.CreateDesign msg = new Messages.CreateDesign();
        msg.slot = slot;
        msg.name = nameField.getText().trim();
        msg.size = sizeCombo.getSelectedIndex();
        msg.computer = value(computerCombo);
        msg.shield = value(shieldCombo);
        msg.ecm = value(ecmCombo);
        msg.armor = value(armorCombo);
        msg.engine = value(engineCombo);
        msg.maneuver = value(maneuverCombo);
        msg.weapons = new String[4];
        msg.weaponCounts = new int[4];
        for (int i = 0; i < 4; i++) {
            msg.weapons[i] = value(weaponCombos[i]);
            msg.weaponCounts[i] = (Integer) weaponCounts[i].getValue();
        }
        msg.specials = new String[3];
        for (int i = 0; i < 3; i++)
            msg.specials[i] = value(specialCombos[i]);
        orderSender.accept(msg);
    }

    private void scrap() {
        int idx = designList.getSelectedIndex();
        if (idx < 0 || idx >= designData.size())
            return;
        Messages.ScrapDesign msg = new Messages.ScrapDesign();
        msg.slot = designData.get(idx).slot;
        orderSender.accept(msg);
    }

    private static String describeDesign(PlayerView.DesignDto d) {
        String size = (d.size >= 0 && d.size < HULL_SIZES.length) ? HULL_SIZES[d.size] : "?";
        return "slot " + d.slot + ": " + d.name + " (" + size
            + (d.colonyShip ? ", colony ship" : "")
            + ", space " + Math.round(d.availableSpace) + "/" + Math.round(d.totalSpace) + ")";
    }

    private void updateEnabled() {
        boolean canCreate = (catalog != null) && (slotCombo.getItemCount() > 0);
        setFormEnabled(canCreate);
        scrapBtn.setEnabled(!designData.isEmpty());
    }

    private void setFormEnabled(boolean on) {
        slotCombo.setEnabled(on); nameField.setEnabled(on); sizeCombo.setEnabled(on);
        computerCombo.setEnabled(on); shieldCombo.setEnabled(on); ecmCombo.setEnabled(on);
        armorCombo.setEnabled(on); engineCombo.setEnabled(on); maneuverCombo.setEnabled(on);
        for (JComboBox<NamedItem> w : weaponCombos) if (w != null) w.setEnabled(on);
        for (JSpinner s : weaponCounts) if (s != null) s.setEnabled(on);
        for (JComboBox<NamedItem> s : specialCombos) if (s != null) s.setEnabled(on);
        createBtn.setEnabled(on);
    }

    private static void fill(JComboBox<NamedItem> combo, List<String> names) {
        combo.removeAllItems();
        if (names == null)
            return;
        for (String n : names)
            combo.addItem(new NamedItem(n));
    }

    private static String value(JComboBox<NamedItem> combo) {
        NamedItem item = (NamedItem) combo.getSelectedItem();
        return item == null ? null : item.value;
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

    /** a component choice; the server matches by name (empty = "none"/basic) */
    private static final class NamedItem {
        final String value;
        NamedItem(String value) { this.value = value; }
        @Override public String toString() {
            return (value == null || value.isEmpty()) ? "(none)" : value;
        }
    }
}
