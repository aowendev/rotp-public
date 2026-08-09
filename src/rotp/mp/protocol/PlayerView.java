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
    public int internalSecurity;   // empire-wide security ticks (0-10)
    // empire economy (this player's own totals)
    public float reserve;          // planetary reserve (BC banked)
    public float totalIncome;      // gross income this turn (planetary + trade)
    public float netIncome;        // income minus ship/stargate/missile-base maintenance
    public float maintenanceCost;  // total upkeep (ship + stargate + missile bases)

    /** empires this player has contacted (plus itself) */
    public List<EmpireDto> empires = new ArrayList<>();
    public List<SystemDto> systems = new ArrayList<>();
    /** this empire's fleets (visible foreign fleets come in a later phase) */
    public List<FleetDto> fleets = new ArrayList<>();
    /** this empire's population transports in flight */
    public List<TransportDto> transports = new ArrayList<>();
    /** this empire's active ship design slots */
    public List<DesignDto> designs = new ArrayList<>();
    public TechDto tech;

    public static class EmpireDto {
        public int id;
        public String name;
        public String race;
        public int colorId;
        public String personality;   // leader disposition, e.g. "Xenophobic"
        public String objective;     // leader agenda, e.g. "Expansionist"
        // diplomatic/spy status vs this empire (all defaults for your own entry)
        public boolean atWar;
        public boolean pact;
        public boolean alliance;
        public boolean atPeace;      // active peace treaty
        public int tradeLevel;       // 0 = no trade route
        public int maxTradeLevel;    // largest offerable trade level right now
        public int spySpending;      // 0-20 ticks
        public String spyMission;    // HIDE / ESPIONAGE / SABOTAGE
        public int spyFrameEmpireId = -1;  // empire to frame for espionage vs this empire (-1 = none)
        public int spies;
        public int maxSpies;
        // intelligence report (best current estimate; all defaults for your own entry)
        public float relativePower;  // their estimated strength relative to you (1.0 = parity)
        public int knownTechCount;   // how many of their technologies your spies have identified
        public int reportAge;        // turns since your last spy report on them (-1 = never)
    }

    public static class SystemDto {
        public int id;
        public float x;
        public float y;
        public boolean scouted;
        public String name;        // empty until scouted
        public int ownerId;        // -1 if unowned or unknown
        public String planetType;  // key like "PLANET_TERRAN", null until scouted
        public String planetTypeName; // readable, e.g. "Terran"; null until scouted
        public int maxSize;        // planet population capacity, 0 until scouted
        public boolean canColonize;// your race+tech can settle it now (scouted, habitable, empty)
        public float distance;     // light-years from your empire (fog-of-war aware)
        public boolean inShipRange;// within your base ship range (most ships, incl. colony ships)
        public boolean colonized;  // as known to this player
        public int population;     // last known
        public ColonyDto colony;   // full detail, own colonies only
    }

    /** spending categories: 0=ship 1=def 2=ind 3=eco 4=tech, ticks sum to 50 */
    public static class ColonyDto {
        public int[] alloc;
        public boolean[] locked;
        /** per-category server-computed result hint for the current spending, in the
         * same words the desktop colony screen shows: Ship/Def years-to-complete,
         * Ind BC or factories per year, Eco WASTE/CLEAN/+n pop, Tech research points.
         * One entry per category (indices as above). */
        public String[] result;
        public float population;
        public float maxSize;         // population capacity (max pop this planet can hold)
        public float planetSize;      // current planet size (base + terraforming)
        public float waste;           // industrial waste awaiting cleanup
        public int popGrowth;         // expected population added next turn
        public int shield;            // planetary shield level
        public String notes;          // status notes (rebellion, quarantine/plague, space monster, ...)
        public float factories;
        public float bases;
        public int maxBases;          // target missile-base count (defense builds up to this)
        public float production;      // BC produced this turn
        public String shipyardDesign;
        public int buildLimit;        // 0 = no limit
        public int transportSize;     // pending (unlaunched) outgoing transports
        public int transportDestId;   // -1 when none pending
    }

    /** research: 6 categories (computer/construction/forcefield/planetology/propulsion/weapon) */
    public static class TechDto {
        public int[] alloc;             // ticks 0-60 per category
        public boolean[] locked;        // per category: held during redistribution
        public float[] progress;        // per category: research toward current tech (0..1, >=1 = ready to discover)
        public String[] researching;    // current tech name per category, null if none
        public String[] researchingId;  // current tech id per category, null if none
        public float totalRP;
        /** per category (6): the techs available to research next, so the player may choose */
        public List<List<TechChoice>> choices = new ArrayList<>();
    }

    public static class TechChoice {
        public String id;
        public String name;
        public int cost;
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
        public int size;             // 0=small..3=huge
        public float totalSpace;
        public float availableSpace;
        public boolean colonyShip;
        public int range;            // how far this design can travel (light-years)
    }

    /** own population transports in flight */
    public static class TransportDto {
        public int destSystemId;
        public int size;
        public float x;
        public float y;
    }
}
