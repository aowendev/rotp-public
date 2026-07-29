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

import java.util.ArrayList;
import java.util.List;

import rotp.mp.protocol.PlayerView;

/**
 * Pure, Swing-free rollups for the empire overview screen: colony/fleet counts,
 * total production, and a readable summary of a contacted empire's relations.
 * Kept separate from the panel so it is unit-testable and portable.
 */
public final class EmpireStats {
    private EmpireStats() { }

    public static int colonyCount(PlayerView v) {
        int n = 0;
        for (PlayerView.SystemDto s : v.systems)
            if (s.colony != null)
                n++;
        return n;
    }

    public static float totalProduction(PlayerView v) {
        float t = 0;
        for (PlayerView.SystemDto s : v.systems)
            if (s.colony != null)
                t += s.colony.production;
        return t;
    }

    public static int fleetCount(PlayerView v) {
        return v.fleets == null ? 0 : v.fleets.size();
    }

    /** e.g. "at war", "non-aggression pact, trade 15", or "no treaties" */
    public static String describeRelation(PlayerView.EmpireDto e) {
        List<String> parts = new ArrayList<>();
        if (e.atWar)
            parts.add("at war");
        else if (e.alliance)
            parts.add("allied");
        else if (e.pact)
            parts.add("non-aggression pact");
        else if (e.atPeace)
            parts.add("at peace");
        if (e.tradeLevel > 0)
            parts.add("trade " + e.tradeLevel);
        return parts.isEmpty() ? "no treaties" : String.join(", ", parts);
    }
}
