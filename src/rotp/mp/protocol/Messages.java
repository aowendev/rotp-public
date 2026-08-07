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
        public String raceId;     // race this player has picked in the lobby
    }

    /** one selectable race, for the lobby race picker */
    public static class RaceInfo {
        public String id;
        public String name;
        public String description;
    }

    /** server -> client: the races a player may pick, sent once on join */
    public static class RaceOptions {
        public List<RaceInfo> races = new ArrayList<>();
    }

    /**
     * client -> server: pick a race in the lobby. Rejected (via error/lobby
     * reload) if the race is already taken by another connected player.
     */
    public static class PickRace {
        public String raceId;
    }

    /** server -> client: acknowledges a join, before the lobby roster */
    public static class Joined {
        public int empireId;
        public boolean host;      // this player may start the game
    }

    /**
     * client -> server: the host starts the game now with the humans present,
     * filling the remaining empires with AI. aiOpponents = number of AI empires
     * to add (-1 = use the ruleset default).
     */
    public static class StartGame {
        public int aiOpponents = -1;
        /** chosen galaxy size (IGameOptions.SIZE_*); null keeps the server default */
        public String galaxySize;
        /** chosen difficulty = AI ability (IGameOptions.DIFFICULTY_*); null keeps the default */
        public String difficulty;
    }

    /**
     * server -> client: the difficulty levels the host may choose in the lobby.
     * In ROTP "difficulty" is really the AI's ability: each level scales the AI's
     * economy (production), so a higher level means a stronger opponent, not a
     * harder puzzle for the human. The client presents this as "AI ability".
     */
    public static class DifficultyOptions {
        public List<DifficultyInfo> levels = new ArrayList<>();
        /** the level currently selected by default (an id in the list above) */
        public String selectedId;
    }

    public static class DifficultyInfo {
        public String id;              // IGameOptions.DIFFICULTY_* constant
        public String name;            // readable label, e.g. "Normal"
        public int aiProductionPct;    // AI economy strength, e.g. 100 = parity, 200 = double
    }

    /** server -> client: galaxy sizes the host may choose in the lobby */
    public static class SizeOptions {
        public List<SizeInfo> sizes = new ArrayList<>();
        /** the size currently selected by default (an id in the list above) */
        public String selectedId;
    }

    public static class SizeInfo {
        public String id;      // IGameOptions.SIZE_* constant
        public String name;    // readable label, e.g. "Small"
        public int stars;      // number of star systems at this size
    }

    /** server -> client: game created, you are this empire */
    public static class GameStarted {
        public int empireId;
    }

    /**
     * server -> client: the game has ended for this empire. Sent once, when the
     * empire is defeated (its empire goes extinct) or the game reaches a
     * win/loss condition. won=true only for the empire the engine evaluated as
     * the victor.
     */
    public static class GameOver {
        public boolean won;
        public String reason;   // MILITARY, NO_COLONIES, DIPLOMATIC, DEFEATED, GAME_OVER, ...
        public String text;     // human-readable
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

    /**
     * client -> server: ask for the projected per-category result of a hypothetical
     * spending split *without* committing it, so the colony screen can show live
     * projections as the sliders move. The server replies with a colonyPreview.
     */
    public static class PreviewColony {
        public int systemId;
        public int[] alloc;
    }

    /** server -> client: the projected result hints for a previewed spending split */
    public static class ColonyPreview {
        public int systemId;
        public String[] result;   // one entry per spending category, same order as ColonyDto.result
    }

    /**
     * lock or unlock one spending category on a colony, so redistribution leaves
     * it untouched (e.g. hold ecology at "clean"). Reflected back in ColonyDto.locked.
     */
    public static class SetColonyLock {
        public int systemId;
        public int category;      // 0=ship 1=def 2=ind 3=eco 4=tech
        public boolean locked;
    }

    /**
     * lock or unlock one research category, so redistribution leaves it untouched.
     * Reflected back in TechDto.locked.
     */
    public static class SetTechLock {
        public int category;      // 0-5 (computers/construction/forcefield/planetology/propulsion/weapon)
        public boolean locked;
    }

    /** replace empire research allocation; 6 categories, ticks 0-60 each, sum <= 60 */
    public static class SetTechAllocations {
        public int[] alloc;
    }

    /**
     * choose which technology a research category works toward, overriding the
     * AI's default pick. techId must be one of that category's available choices
     * (see PlayerView.TechDto.choices).
     */
    public static class SetResearchChoice {
        public int category;   // 0-5
        public String techId;
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

    /** set spy spending against a contacted empire (0-20 ticks, each 0.5% of income) */
    public static class SetSpySpending {
        public int empireId;
        public int allocation;
    }

    /** set spy mission against a contacted empire: HIDE, ESPIONAGE, or SABOTAGE */
    public static class SetSpyMission {
        public int empireId;
        public String mission;
    }

    /** set empire-wide internal security (0-10 ticks) */
    public static class SetSecurity {
        public int allocation;
    }
    // TODO: reserve fund transfers (reserve -> colony, and banking a planet's
    // output into the reserve) are deferred; the TransferReserve command was
    // prototyped and pulled. Re-add here + in GameServer when we take this up.

    /**
     * make a diplomatic offer to a contacted empire. In v1 the target's
     * diplomat AI answers immediately (even for human empires); the verdict
     * arrives as a diploReply. action: TRADE (with tradeLevel), PEACE,
     * PACT, or ALLIANCE.
     */
    public static class DiploOffer {
        public int empireId;
        public String action;
        public int tradeLevel;
    }

    /** unilaterally break an existing treaty: TRADE, PACT, or ALLIANCE */
    public static class BreakTreaty {
        public int empireId;
        public String treaty;
    }

    public static class DeclareWar {
        public int empireId;
    }

    /** server -> client: the target's answer to a diplomatic offer */
    public static class DiploReply {
        public int empireId;
        public String action;
        public boolean accepted;
        public String text;
    }

    /** server -> client: acknowledgement/rejection of an order */
    public static class CommandResult {
        public String command;
        public boolean ok;
        public String text;
    }

    /**
     * server -> client: things that happened to this empire during the last
     * turn, generated server-side per empire (see rotp.mp.server
     * .NotificationCenter). Sent after each turn, before the fresh view.
     */
    public static class Notifications {
        public int turn;
        public List<Notification> items = new ArrayList<>();
    }

    public static class Notification {
        public String category;   // CONTACT, DIPLOMACY, COLONY_GAINED, COLONY_LOST
        public String text;       // human-readable, English for now
        public int systemId = -1; // related system, -1 if n/a
        public int empireId = -1; // related empire, -1 if n/a
    }

    /** server -> client */
    public static class Error {
        public String text;
    }
}
