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

import java.util.List;

import rotp.mp.protocol.PlayerView;

/**
 * Pure, Swing-free helpers for the fleets screen: whether a fleet can be
 * ordered and a readable summary of its ships by design name. Kept separate
 * from the panel so it is unit-testable and portable to the browser client.
 */
public final class FleetView {
    private FleetView() { }

    /** an orbiting fleet (not in transit) with ships can be deployed */
    public static boolean isOrbiting(PlayerView.FleetDto f) {
        return f.atSystemId >= 0;
    }

    public static boolean hasShips(PlayerView.FleetDto f) {
        if (f.counts == null)
            return false;
        for (int c : f.counts)
            if (c > 0)
                return true;
        return false;
    }

    public static boolean deployable(PlayerView.FleetDto f) {
        return isOrbiting(f) && hasShips(f);
    }

    /** e.g. "3x Scout, 1x Colony Ship" using the empire's design slot names */
    public static String summarize(PlayerView.FleetDto f, List<PlayerView.DesignDto> designs) {
        if (f.counts == null)
            return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < f.counts.length; slot++) {
            int n = f.counts[slot];
            if (n <= 0)
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(n).append("x ").append(designName(designs, slot));
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    private static String designName(List<PlayerView.DesignDto> designs, int slot) {
        if (designs != null)
            for (PlayerView.DesignDto d : designs)
                if (d.slot == slot)
                    return d.name;
        return "design " + slot;
    }
}
