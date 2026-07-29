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
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import rotp.mp.protocol.PlayerView;

/**
 * Empire overview (the Mac "Planet List" screen): a read-only rollup of your
 * colonies, empire totals, and the diplomatic status of every empire you've
 * contacted. Rendered entirely from PlayerView; issues no commands.
 */
public class EmpirePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"System", "Pop", "Factories", "Bases", "Production", "Building"};

    private final JLabel totals = new JLabel(" ");
    private final DefaultTableModel colonyModel = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final DefaultListModel<String> contactsModel = new DefaultListModel<>();

    public EmpirePanel() {
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        JLabel title = new JLabel("Empire Overview");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(totals, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        add(new JScrollPane(new JTable(colonyModel)), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.add(bold("Contacted empires"), BorderLayout.NORTH);
        JList<String> contacts = new JList<>(contactsModel);
        contacts.setVisibleRowCount(5);
        south.add(new JScrollPane(contacts), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    public void updateFromView(PlayerView v) {
        if (v == null)
            return;

        float rp = (v.tech != null) ? v.tech.totalRP : 0;
        totals.setText(String.format("Colonies: %d   Production: %d BC/turn   Research: %d RP/turn   Fleets: %d",
            EmpireStats.colonyCount(v), Math.round(EmpireStats.totalProduction(v)),
            Math.round(rp), EmpireStats.fleetCount(v)));

        colonyModel.setRowCount(0);
        for (PlayerView.SystemDto s : v.systems) {
            if (s.colony == null)
                continue;
            PlayerView.ColonyDto c = s.colony;
            colonyModel.addRow(new Object[]{
                s.name == null || s.name.isEmpty() ? ("System " + s.id) : s.name,
                Math.round(c.population), Math.round(c.factories), Math.round(c.bases),
                Math.round(c.production), c.shipyardDesign == null ? "-" : c.shipyardDesign});
        }

        contactsModel.clear();
        for (PlayerView.EmpireDto e : v.empires) {
            if (e.id == v.empireId)
                continue;
            contactsModel.addElement(e.name + " (" + e.race + ") - " + EmpireStats.describeRelation(e));
        }
        if (contactsModel.isEmpty())
            contactsModel.addElement("(no contact with other empires yet)");
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }
}
