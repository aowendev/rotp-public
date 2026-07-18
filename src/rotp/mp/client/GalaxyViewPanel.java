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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;
import rotp.mp.protocol.PlayerView;

/**
 * Minimal galaxy map rendered purely from PlayerView DTOs — the first
 * proof that a client can draw the game from the JSON protocol alone.
 * The full MainUI galaxy map gets ported to the protocol in Phase 1.
 */
public class GalaxyViewPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    // ROTP banner color order (see MOO1GameOptions); good enough for the skeleton
    private static final Color[] EMPIRE_COLORS = {
        new Color(237,28,36), new Color(0,166,81), new Color(247,229,60),
        new Color(9,131,214), new Color(255,127,0), new Color(145,51,188),
        new Color(0,255,255), new Color(255,0,255), new Color(153,102,51),
        new Color(255,255,255)
    };

    private PlayerView view;

    public void view(PlayerView v) {
        this.view = v;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());

        PlayerView v = view;
        if (v == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("Waiting for game state...", 20, 30);
            return;
        }

        int margin = 30;
        float scale = Math.min(
            (getWidth()-2f*margin) / Math.max(1, v.galaxyWidth),
            (getHeight()-2f*margin) / Math.max(1, v.galaxyHeight));

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (PlayerView.SystemDto s : v.systems) {
            int x = margin + (int)(s.x * scale);
            int y = margin + (int)(s.y * scale);

            if (s.colonized && (s.ownerId >= 0)) {
                g.setColor(empireColor(v, s.ownerId));
                g.drawOval(x-6, y-6, 12, 12);
            }
            g.setColor(s.scouted ? Color.WHITE : Color.GRAY);
            g.fillOval(x-2, y-2, 5, 5);

            if (s.scouted && !s.name.isEmpty()) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(s.name, x+8, y+4);
            }
        }

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString(v.empireName+" ("+v.raceName+")  -  "+v.year, 10, 18);
    }

    private static Color empireColor(PlayerView v, int empireId) {
        for (PlayerView.EmpireDto e : v.empires) {
            if (e.id == empireId)
                return EMPIRE_COLORS[Math.floorMod(e.colorId, EMPIRE_COLORS.length)];
        }
        return Color.DARK_GRAY;  // owner known to exist but not contacted
    }
}
