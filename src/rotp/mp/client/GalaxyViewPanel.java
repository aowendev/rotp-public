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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
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

    private static final int MARGIN = 30;

    private PlayerView view;
    private float scale = 1f;
    private int selectedSystemId = -1;
    private IntConsumer systemClickListener = id -> { };

    public GalaxyViewPanel() {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleClick(e.getX(), e.getY()); }
        });
    }

    public void view(PlayerView v) {
        this.view = v;
        repaint();
    }

    /** notified with the clicked system's id, or -1 when clicking empty space */
    public void onSystemClicked(IntConsumer listener) {
        this.systemClickListener = listener;
    }

    public void select(int systemId) {
        this.selectedSystemId = systemId;
        repaint();
    }

    private void handleClick(int px, int py) {
        PlayerView v = view;
        if (v == null)
            return;
        int best = systemAt(v, px, py, MARGIN, scale);
        selectedSystemId = best;
        repaint();
        systemClickListener.accept(best);
    }

    /**
     * The id of the system nearest a screen point (within a click tolerance),
     * or -1 if the click missed. Pure so the hit-testing is unit-testable
     * without a display.
     */
    public static int systemAt(PlayerView v, int px, int py, int margin, float scale) {
        int best = -1;
        float bestD = 14 * 14;   // click tolerance in pixels, squared
        for (PlayerView.SystemDto s : v.systems) {
            float sx = margin + s.x * scale;
            float sy = margin + s.y * scale;
            float d = (sx - px) * (sx - px) + (sy - py) * (sy - py);
            if (d < bestD) { bestD = d; best = s.id; }
        }
        return best;
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

        scale = Math.min(
            (getWidth()-2f*MARGIN) / Math.max(1, v.galaxyWidth),
            (getHeight()-2f*MARGIN) / Math.max(1, v.galaxyHeight));

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (PlayerView.SystemDto s : v.systems) {
            int x = MARGIN + (int)(s.x * scale);
            int y = MARGIN + (int)(s.y * scale);

            if (s.id == selectedSystemId) {
                g.setColor(Color.YELLOW);
                g.drawOval(x-9, y-9, 18, 18);
            }
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
