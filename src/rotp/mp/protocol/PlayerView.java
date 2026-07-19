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
package rotp.mp.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything one player's client is allowed to know about the game,
 * built server-side from that empire's fog-of-war views (SystemInfo /
 * EmpireView). Sent as JSON after game start and after each turn.
 *
 * This is a view-model, not the game model: values the UI needs are
 * precomputed by the server so clients never run game math.
 */
public final class PlayerView {
    public int turn;
    public int year;
    public int galaxyWidth;     // light-years
    public int galaxyHeight;

    public int empireId;
    public String empireName;
    public String raceName;
    public int colorId;

    /** empires this player has contacted (plus itself) */
    public List<EmpireDto> empires = new ArrayList<>();
    public List<SystemDto> systems = new ArrayList<>();
    /** this empire's fleets (visible foreign fleets come in a later phase) */
    public List<FleetDto> fleets = new ArrayList<>();
    /** this empire's active ship design slots */
    public List<DesignDto> designs = new ArrayList<>();
    public TechDto tech;

    public static class EmpireDto {
        public int id;
        public String name;
        public String race;
        public int colorId;
    }

    public static class SystemDto {
        public int id;
        public float x;
        public float y;
        public boolean scouted;
        public String name;        // empty until scouted
        public int ownerId;        // -1 if unowned or unknown
        public String planetType;  // key like "PLANET_TERRAN", null until scouted
        public boolean colonized;  // as known to this player
        public int population;     // last known
        public ColonyDto colony;   // full detail, own colonies only
    }

    /** spending categories: 0=ship 1=def 2=ind 3=eco 4=tech, ticks sum to 50 */
    public static class ColonyDto {
        public int[] alloc;
        public boolean[] locked;
        public float population;
        public float factories;
        public float bases;
        public float production;      // BC produced this turn
        public String shipyardDesign;
    }

    /** research: 6 categories (computer/construction/forcefield/planetology/propulsion/weapon) */
    public static class TechDto {
        public int[] alloc;           // ticks 0-60 per category
        public String[] researching;  // current tech name per category, null if none
        public float totalRP;
    }

    public static class FleetDto {
        public int atSystemId;    // -1 when in transit
        public int destSystemId;  // -1 when orbiting with no deployment
        public float x;
        public float y;
        public int[] counts;      // ships per design slot
    }

    public static class DesignDto {
        public int slot;
        public String name;
    }
}
