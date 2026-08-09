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
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Empire overview (the Mac "Planet List" screen): a rollup of your colonies with
 * per-colony detail (#, shield, waste, notes), the empire economy (income,
 * upkeep, net, and the planetary reserve), and the diplomatic status of
 * contacted empires. Rendered entirely from PlayerView.
 *
 * The reserve's two directions are both here. **Out** is a transfer: pick a
 * colony row, enter BC, Transfer ({@link Messages.TransferReserve}). **In** is a
 * rate, not a transfer — ROTP fills the reserve only from an empire-wide tax on
 * colony production, so the "bank output" control is a tax-rate spinner
 * ({@link Messages.SetEmpireTax}). See docs/moo1-differences.md §4.
 */
public class EmpirePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS =
        {"#", "System", "Pop", "Factories", "Waste", "Shield", "Bases", "Production", "Reserve", "Building", "Notes"};

    private final Consumer<Object> orderSender;
    private final JLabel totals = new JLabel(" ");
    private final JLabel economy = new JLabel(" ");
    private final DefaultTableModel colonyModel = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable colonies = new JTable(colonyModel);
    private final DefaultListModel<String> contactsModel = new DefaultListModel<>();
    /** system id per colony row, so a selected row maps back to a transfer target */
    private final List<Integer> rowSystemIds = new ArrayList<>();

    private final JSpinner transferAmount = new JSpinner(new SpinnerNumberModel(0, 0, 999999, 10));
    private final JButton transfer = new JButton("Transfer to selected colony");
    private final JSpinner taxLevel = new JSpinner(new SpinnerNumberModel(0, 0, 20, 1));
    private final JCheckBox taxOnlyDeveloped = new JCheckBox("developed colonies only");
    /** set while loading a view, so programmatic control updates don't send orders */
    private boolean loading;

    public EmpirePanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        JLabel title = new JLabel("Empire Overview");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        title.setAlignmentX(LEFT_ALIGNMENT);
        totals.setAlignmentX(LEFT_ALIGNMENT);
        economy.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title);
        header.add(totals);
        header.add(economy);
        add(header, BorderLayout.NORTH);

        colonies.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(colonies), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(reserveControls(), BorderLayout.NORTH);
        JPanel contactsBox = new JPanel(new BorderLayout());
        contactsBox.add(bold("Contacted empires"), BorderLayout.NORTH);
        JList<String> contacts = new JList<>(contactsModel);
        contacts.setVisibleRowCount(5);
        contactsBox.add(new JScrollPane(contacts), BorderLayout.CENTER);
        south.add(contactsBox, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** the two reserve directions: transfer BC out to a colony, and the tax rate
     * that banks colony production into the reserve */
    private JPanel reserveControls() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setBorder(BorderFactory.createTitledBorder("Planetary reserve"));

        row.add(new JLabel("Spend"));
        row.add(transferAmount);
        row.add(new JLabel("BC"));
        transfer.addActionListener(e -> sendTransfer());
        row.add(transfer);

        row.add(javax.swing.Box.createHorizontalStrut(16));
        row.add(new JLabel("Tax colonies"));
        taxLevel.addChangeListener(e -> sendTax());
        row.add(taxLevel);
        row.add(new JLabel("%"));
        taxOnlyDeveloped.addActionListener(e -> sendTax());
        row.add(taxOnlyDeveloped);
        return row;
    }

    private void sendTransfer() {
        int row = colonies.getSelectedRow();
        int amount = (Integer) transferAmount.getValue();
        if ((row < 0) || (row >= rowSystemIds.size()) || (amount <= 0))
            return;
        Messages.TransferReserve cmd = new Messages.TransferReserve();
        cmd.systemId = rowSystemIds.get(row);
        cmd.amount = amount;
        orderSender.accept(cmd);
        transferAmount.setValue(0);
    }

    private void sendTax() {
        if (loading)
            return;
        Messages.SetEmpireTax cmd = new Messages.SetEmpireTax();
        cmd.level = (Integer) taxLevel.getValue();
        cmd.onlyDeveloped = taxOnlyDeveloped.isSelected();
        orderSender.accept(cmd);
    }

    public void updateFromView(PlayerView v) {
        if (v == null)
            return;

        float rp = (v.tech != null) ? v.tech.totalRP : 0;
        totals.setText(String.format("Colonies: %d   Production: %d BC/turn   Research: %d RP/turn   Fleets: %d",
            EmpireStats.colonyCount(v), Math.round(EmpireStats.totalProduction(v)),
            Math.round(rp), EmpireStats.fleetCount(v)));
        economy.setText(String.format("Reserve: %d BC (+%d/turn taxed)    Income: %d    Upkeep: %d    Net: %+d BC/turn",
            Math.round(v.reserve), Math.round(v.empireTaxRevenue), Math.round(v.totalIncome),
            Math.round(v.maintenanceCost), Math.round(v.netIncome)));

        loading = true;
        ((SpinnerNumberModel) taxLevel.getModel()).setMaximum(v.maxEmpireTaxLevel);
        taxLevel.setValue(Math.min(v.empireTaxLevel, v.maxEmpireTaxLevel));
        taxOnlyDeveloped.setSelected(v.empireTaxOnlyDeveloped);
        loading = false;
        ((SpinnerNumberModel) transferAmount.getModel()).setMaximum(Math.max(0, Math.round(v.reserve)));
        transfer.setEnabled(v.reserve >= 1);

        int selectedSystem = selectedSystemId();
        colonyModel.setRowCount(0);
        rowSystemIds.clear();
        int n = 0;
        for (PlayerView.SystemDto s : v.systems) {
            if (s.colony == null)
                continue;
            PlayerView.ColonyDto c = s.colony;
            n++;
            rowSystemIds.add(s.id);
            colonyModel.addRow(new Object[]{
                n,
                (s.name == null || s.name.isEmpty()) ? ("System " + s.id) : s.name,
                Math.round(c.population) + "/" + Math.round(c.maxSize),
                Math.round(c.factories),
                Math.round(c.waste),
                (c.shield > 0) ? c.shield : "-",
                Math.round(c.bases),
                Math.round(c.production),
                reserveCell(c),
                (c.shipyardDesign == null) ? "-" : c.shipyardDesign,
                (c.notes == null) ? "" : c.notes});
        }
        int restored = rowSystemIds.indexOf(selectedSystem);
        if (restored >= 0)
            colonies.setRowSelectionInterval(restored, restored);

        contactsModel.clear();
        for (PlayerView.EmpireDto e : v.empires) {
            if (e.id == v.empireId)
                continue;
            contactsModel.addElement(e.name + " (" + e.race + ") - " + EmpireStats.describeRelation(e));
        }
        if (contactsModel.isEmpty())
            contactsModel.addElement("(no contact with other empires yet)");
    }

    /** reserve BC banked on this colony, and how much more it could spend next turn */
    private static String reserveCell(PlayerView.ColonyDto c) {
        if ((c.reserveIncome <= 0) && (c.maxReserveNeeded <= 0))
            return "-";
        return Math.round(c.reserveIncome) + " (+" + Math.round(c.maxReserveNeeded) + ")";
    }

    private int selectedSystemId() {
        int row = colonies.getSelectedRow();
        return ((row >= 0) && (row < rowSystemIds.size())) ? rowSystemIds.get(row) : -1;
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }
}
