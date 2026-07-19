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
 * Wire messages for ROTP multiplayer, exchanged as JSON over WebSocket.
 * Everything here must stay language-neutral: a browser client will
 * eventually consume the same protocol.
 */
public final class Messages {
    private Messages() { }

    /** client -> server: first message after connecting */
    public static class Hello {
        public int version;
        public String playerName;
    }

    /** server -> client: lobby roster, sent on every change */
    public static class Lobby {
        public List<Slot> slots = new ArrayList<>();
        public String message;
    }

    public static class Slot {
        public int empireId;
        public String playerName;
        public boolean connected;
    }

    /** server -> client: game created, you are this empire */
    public static class GameStarted {
        public int empireId;
    }

    /** client -> server: we-go ready flag; turn resolves when all players are ready */
    public static class Ready {
        public boolean ready = true;
    }

    /** server -> client: turn processing / readiness state */
    public static class TurnStatus {
        public boolean processing;
        public int turn;
        public int readyCount;
        public int totalPlayers;
        public String note;
    }

    // ---- orders (client -> server), all validated against the sender's empire ----

    /** replace a colony's spending allocation; 5 categories (ship/def/ind/eco/tech), ticks summing to 50 */
    public static class SetColonyAllocations {
        public int systemId;
        public int[] alloc;
    }

    /** replace empire research allocation; 6 categories, ticks 0-60 each, sum <= 60 */
    public static class SetTechAllocations {
        public int[] alloc;
    }

    /**
     * send ships from an orbiting fleet to another system.
     * counts is per design slot (6); null or empty deploys the whole fleet.
     */
    public static class DeployFleet {
        public int fromSystemId;
        public int destSystemId;
        public int[] counts;
    }

    /** send population from one of your colonies to a colonized system in range */
    public static class SendTransports {
        public int fromSystemId;
        public int destSystemId;
        public int size;
    }

    /** cancel pending (unlaunched) transports at one of your colonies */
    public static class AbortTransports {
        public int fromSystemId;
    }

    /** colonize the system an orbiting fleet (with a colony ship) is at */
    public static class Colonize {
        public int systemId;
    }

    /**
     * client -> server as an empty request; server replies with the lists
     * filled in. Component names match this empire's researched tech; use
     * them verbatim in CreateDesign. Index 0 of each list is "none"/basic.
     */
    public static class DesignCatalog {
        public List<String> hulls;      // index = hull size id (0=small..3=huge)
        public List<String> computers;
        public List<String> shields;
        public List<String> ecms;
        public List<String> armors;
        public List<String> engines;
        public List<String> maneuvers;
        public List<String> weapons;
        public List<String> specials;
    }

    /** create a ship design in an empty slot; null component fields mean "none"/basic */
    public static class CreateDesign {
        public int slot;
        public String name;
        public int size;            // 0=small 1=medium 2=large 3=huge
        public String computer;
        public String shield;
        public String ecm;
        public String armor;
        public String engine;
        public String maneuver;
        public String[] weapons;    // up to 4
        public int[] weaponCounts;
        public String[] specials;   // up to 3
    }

    /** scrap the design in a slot (removes its ships from all fleets, refunds reserve) */
    public static class ScrapDesign {
        public int slot;
    }

    /** set which design one of your colonies builds, and an optional build limit (0 = none) */
    public static class SetShipBuild {
        public int systemId;
        public int designSlot;
        public int buildLimit;
    }

    /** server -> client: acknowledgement/rejection of an order */
    public static class CommandResult {
        public String command;
        public boolean ok;
        public String text;
    }

    /** server -> client */
    public static class Error {
        public String text;
    }
}
