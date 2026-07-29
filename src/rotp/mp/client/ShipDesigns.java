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
 * Pure, Swing-free helpers for the ship-design screen: which design slots are
 * free to build into. Kept separate from the panel so it is unit-testable and
 * portable to the browser client.
 */
public final class ShipDesigns {
    /** an empire has this many ship design slots (mirrors ShipDesignLab.MAX_DESIGNS) */
    public static final int MAX_DESIGNS = 6;

    private ShipDesigns() { }

    /** slot indices (0..MAX_DESIGNS-1) with no active design — available to create into */
    public static List<Integer> freeSlots(List<PlayerView.DesignDto> activeDesigns) {
        boolean[] used = new boolean[MAX_DESIGNS];
        if (activeDesigns != null)
            for (PlayerView.DesignDto d : activeDesigns)
                if (d.slot >= 0 && d.slot < MAX_DESIGNS)
                    used[d.slot] = true;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < MAX_DESIGNS; i++)
            if (!used[i])
                out.add(i);
        return out;
    }
}
