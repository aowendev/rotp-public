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

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import rotp.mp.protocol.PlayerView;

/**
 * Read-only info for the system last clicked on the map: what a scout has
 * found there (planet type, size, whether your race can colonize it) or a
 * prompt to scout it if it is still unexplored. Rendered purely from
 * PlayerView.SystemDto, so it ports directly to the browser client.
 */
public class SystemInfoPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel("No system selected");
    private final JLabel body = new JLabel(" ");

    private PlayerView view;
    private int systemId = -1;

    public SystemInfoPanel() {
        setPreferredSize(new Dimension(320, 0));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        body.setVerticalAlignment(JLabel.TOP);
        add(title, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        clear();
    }

    /** show the system last clicked on the map */
    public void show(int sysId, PlayerView v) {
        systemId = sysId;
        view = v;
        render();
    }

    /** refresh from a fresh view (e.g. a scout just revealed this system) */
    public void updateFromView(PlayerView v) {
        view = v;
        if (systemId >= 0)
            render();
    }

    private void clear() {
        systemId = -1;
        title.setText("No system selected");
        body.setText("<html>Click a star on the map to inspect it.</html>");
    }

    private void render() {
        PlayerView.SystemDto s = system(view, systemId);
        if (s == null) {
            clear();
            return;
        }
        String name = (s.name == null || s.name.isEmpty()) ? ("System " + s.id) : s.name;

        if (!s.scouted) {
            title.setText("Unexplored star");
            body.setText("<html>This star has not been scouted yet.<br><br>"
                + "Send a scout here (Fleets tab) to reveal its planet.<br><br>"
                + rangeLine(s) + "</html>");
            return;
        }

        title.setText(name);
        StringBuilder b = new StringBuilder("<html>");
        if (s.planetTypeName != null) {
            b.append("Planet: <b>").append(s.planetTypeName).append("</b><br>");
            b.append("Max population: ").append(s.maxSize).append("<br><br>");
        }
        else {
            b.append("No habitable planet in this system.<br><br>");
        }

        b.append("Status: ").append(ownership(s)).append("<br>");
        b.append(rangeLine(s)).append("<br><br>");

        if (s.colonized) {
            // owned system: nothing to colonize
        }
        else if (s.canColonize) {
            b.append("<b>Habitable — you can colonize this.</b><br>")
             .append("Send a colony ship here to settle it.");
        }
        else if (s.planetType != null) {
            b.append("Cannot colonize yet: the planet is hostile or needs "
                + "colonization technology.");
        }
        else {
            b.append("Nothing here to colonize.");
        }
        b.append("</html>");
        body.setText(b.toString());
    }

    private static String rangeLine(PlayerView.SystemDto s) {
        String dist = String.format("Distance: %.1f ly &mdash; ", s.distance);
        return dist + (s.inShipRange
            ? "within ship range"
            : "<b>beyond ship range</b> (colony ships can't reach; a scout might)");
    }

    private String ownership(PlayerView.SystemDto s) {
        if (!s.colonized || (s.ownerId < 0))
            return "uncolonized";
        if (s.ownerId == view.empireId)
            return "your colony (pop " + s.population + ")";
        String owner = empireName(s.ownerId);
        return "colonized by " + owner + " (pop " + s.population + ")";
    }

    private String empireName(int empireId) {
        if (view.empires != null)
            for (PlayerView.EmpireDto e : view.empires)
                if (e.id == empireId)
                    return (e.name != null && !e.name.isEmpty()) ? e.name : e.race;
        return "an unknown empire";
    }

    private static PlayerView.SystemDto system(PlayerView v, int id) {
        if (v != null)
            for (PlayerView.SystemDto s : v.systems)
                if (s.id == id)
                    return s;
        return null;
    }
}
