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

/**
 * Pure colony-spending redistribution logic, shared by {@link ColonyPanel}
 * and exercised directly by unit tests. Kept free of Swing so it doubles as
 * the reference spec for the eventual browser client: five categories whose
 * ticks always sum to a fixed total, with server-locked categories held
 * fixed and excluded from redistribution.
 */
public final class ColonyAllocations {
    private ColonyAllocations() { }

    /**
     * After the user set category {@code changed} to a new value, adjust the
     * other unlocked categories in place so the five values sum to
     * {@code maxTicks}. If the others can't absorb the change (all hit 0 or
     * max), the changed category is clamped back. Mutates {@code a}.
     */
    public static void balance(int[] a, int changed, boolean[] locked, int maxTicks) {
        int over = sum(a) - maxTicks;   // >0: remove from others; <0: add to others
        int step = (over > 0) ? -1 : 1;
        int guard = 0;
        while (over != 0 && guard++ < 100000) {
            boolean moved = false;
            for (int i = 0; i < a.length && over != 0; i++) {
                if (i == changed || (locked != null && locked[i]))
                    continue;
                int nv = a[i] + step;
                if (nv < 0 || nv > maxTicks)
                    continue;
                a[i] = nv;
                over += step;
                moved = true;
            }
            if (!moved)
                break;
        }
        if (over != 0)
            a[changed] = clamp(a[changed] - over, 0, maxTicks);
    }

    public static int sum(int[] a) {
        int s = 0;
        for (int x : a) s += x;
        return s;
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
