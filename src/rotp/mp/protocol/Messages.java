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
        /**
         * the token from a previous {@link Joined}, replayed to re-claim the same
         * empire. A browser reconnects constantly (refresh, sleep, flaky network)
         * and may not keep a stable display name, so the token — not the name — is
         * the identity the server matches on. Null/empty on a first join; the
         * server falls back to name matching for clients that carry no token.
         */
        public String sessionToken;
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
        /** store this and send it back in {@link Hello#sessionToken} to re-claim
         * this empire after a disconnect or refresh */
        public String sessionToken;
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
        /** per-turn timer in seconds: the server auto-resolves a we-go turn this long after
         * orders open, so an absent human can't stall it. 0 = off; -1 = keep server default */
        public int turnTimerSeconds = -1;
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
        /** seconds left on the turn timer before the server auto-resolves; -1 = no timer.
         * The client may tick this down locally between status messages. */
        public int secondsRemaining = -1;
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
     * set a colony's target number of missile bases. Defense spending builds up to
     * this; setting it below the current count scraps the excess (refunding BC to
     * the reserve). Reflected back in ColonyDto.maxBases.
     */
    public static class SetColonyMaxBases {
        public int systemId;
        public int maxBases;
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

    /**
     * set which empire to frame for espionage against a contacted empire, MOO1-style: when
     * your spy is caught stealing tech from empireId, the blame is pinned on frameEmpireId
     * (if that empire is a plausible scapegoat for the theft). frameEmpireId = -1 frames
     * no one. This is a standing preference (we-go analogue of MOO1's reactive choice).
     */
    public static class SetSpyFrame {
        public int empireId;        // the empire being spied on
        public int frameEmpireId;   // the empire to frame, or -1 for none
    }

    /** set empire-wide internal security (0-10 ticks) */
    public static class SetSecurity {
        public int allocation;
    }

    /** save the running game to a named file on the server (local, for testing).
     * Load is done by (re)starting the server with load=<name>. */
    public static class SaveGame {
        public String name;
    }
    /**
     * move BC out of the planetary reserve into one of your colonies. Lossless
     * (`Empire.allocateReserve`); the colony spends it next turn up to its own
     * production, and any surplus stays banked on the colony.
     */
    public static class TransferReserve {
        public int systemId;
        public int amount;      // BC to move; clamped to the reserve on hand
    }

    /**
     * the "into the reserve" direction. ROTP has no per-planet manual banking —
     * the reserve is filled by an empire-wide tax on colony production
     * (`Empire.addReserve(production * colonyTaxPct)`, banked at 50%). So the
     * order is a tax rate, not a transfer: `level` percent of production, either
     * from every colony or only from developed ones.
     */
    public static class SetEmpireTax {
        public int level;               // 0..maxEmpireTaxLevel (percent of production)
        public boolean onlyDeveloped;   // tax only fully-developed colonies
    }

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

    // ---- fuller diplomacy: tech exchange, aid, threats (Phase 4) ----
    //
    // MOO1's audience screen is more than accept/decline on a treaty. The three
    // additions here mirror the desktop diplomacy menus exactly, so the same
    // engine entry points answer them:
    //   exchange technology  DiplomacyTechRequestMenu -> DiplomacyTechCounterMenu
    //   offer aid            DiplomacyOfferAidMenu
    //   threaten             DiplomacyThreatenMenu
    // Tech exchange is a *two-step* negotiation — you ask for a tech, they name a
    // price in techs of their own, you pick one or walk away — which is why it
    // needs a counter-offer round trip rather than one command.

    /** a technology as it appears in a trade menu (name, tier and cost, as the
     * desktop menu shows them) */
    public static class TechOption {
        public String id;
        public String name;
        public int quintile;   // tech tier
        public int cost;       // research cost, the rough "worth" of the trade
    }

    /** client -> server: what can I currently do with this empire diplomatically?
     * Answered with a {@link TechTradeMenu}. */
    public static class DiploOptions {
        public int empireId;
    }

    /**
     * server -> client: the diplomatic menu for one contacted empire — which
     * actions the server would accept right now, and the technologies/amounts
     * involved. Computed from that empire's own diplomat AI, so the client runs
     * no game math and cannot see techs it has no business knowing about.
     */
    public static class TechTradeMenu {
        public int empireId;
        public boolean canExchangeTech;
        public boolean canOfferAid;
        public boolean canThreatenSpying;
        public boolean canThreatenAttacking;
        public boolean canEvictSpies;
        public List<TechOption> canRequest = new ArrayList<>();  // their techs you may ask for
        public List<TechOption> canGift = new ArrayList<>();     // your techs you may give
        public List<Integer> aidAmounts = new ArrayList<>();     // BC gifts you can afford
    }

    /** client -> server: ask an empire for one of their technologies. They answer
     * with a {@link TechCounterOffer} (their price) or a refusing diploReply. */
    public static class RequestTech {
        public int empireId;
        public String techId;
    }

    /**
     * server -> client: they will trade the tech you asked for, in exchange for
     * one of `counterOptions` — your techs they want. Resolve with
     * {@link CounterOfferTech}, or simply drop it to walk away.
     */
    public static class TechCounterOffer {
        public int empireId;
        public String requestedTechId;
        public String requestedTechName;
        public String text;                 // their words, from the engine
        public List<TechOption> counterOptions = new ArrayList<>();
    }

    /** client -> server: close a tech exchange by paying the named price */
    public static class CounterOfferTech {
        public int empireId;
        public String requestedTechId;   // what you are getting
        public String offeredTechId;     // what you are giving, from counterOptions
    }

    /**
     * client -> server: answer another *human's* tech request (an
     * INCOMING_TECH_REQUEST prompt). counterTechId is the tech of theirs you want
     * in exchange, from the prompt's choices; null/empty refuses the request.
     */
    public static class RespondTechRequest {
        public int requestorId;
        public String counterTechId;
    }

    /** client -> server: a gift, expecting nothing back. Exactly one of amount
     * (BC from your reserve) or techId. */
    public static class OfferAid {
        public int empireId;
        public int amount;
        public String techId;
    }

    /** client -> server: a demand backed by nothing but menace.
     * threat: EVICT_SPIES | STOP_SPYING | STOP_ATTACKING */
    public static class Threaten {
        public int empireId;
        public String threat;
    }

    /**
     * client -> server: the answer to a STEAL_TECH prompt. Your spy got in and you
     * choose what they take — categoryId is one of the prompt's choices, and the
     * technology you receive is the one the prompt showed for it. Ignoring the prompt
     * is not a lost theft: when the turn resolves your empire's AI picks for you.
     */
    public static class StealTech {
        public int empireId;        // the empire being stolen from
        public String categoryId;   // technology category to take from
    }

    /**
     * client -> server: the answer to a SABOTAGE prompt. `action` is one of the
     * prompt's choices — FACTORIES, MISSILES or REBELS — and the target system is the
     * one the prompt showed against it. Ignoring the prompt lets your empire's AI
     * choose when the turn resolves; the spy is already in position either way.
     */
    public static class Sabotage {
        public int empireId;     // the empire being sabotaged
        public String action;    // FACTORIES | MISSILES | REBELS
    }

    /**
     * client -> server: the answer to a BOMBARD prompt — your fleet is in orbit over
     * a colony you are aggressive with, and may bomb it. Ship combat auto-resolves in
     * multiplayer (a tactical battle would stall every other player), but the decision
     * to bombard is not part of the battle: it is a deliberate act against another
     * player's world, so a human makes it. Declining just leaves the fleet in orbit,
     * and the prompt returns next turn while it stays there.
     */
    public static class Bombard {
        public int systemId;
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

    /**
     * server -> client: interactive decisions awaiting the player this turn (Phase 3).
     * The human resolves each via the matching command; if left unresolved the server's
     * AI default stands. Types:
     *   SELECT_TECH        - choose the next tech in a category that just completed one
     *                        (resolve with setResearchChoice)
     *   INCOMING_DIPLOMACY - another empire is offering a treaty/trade to you
     *                        (resolve with respondDiplomacy)
     *   COUNCIL_VOTE       - the Galactic Council is electing a leader and it is your
     *                        turn to vote; choiceIds/choiceNames list the candidates
     *                        (empire ids as strings) plus "-1" = abstain
     *                        (resolve with castCouncilVote)
     *   COLONIZE           - a colony ship of yours is orbiting a colonizable, uncolonized
     *                        system (systemId); settle it or leave it (resolve with
     *                        colonize, or ignore to leave the ship in orbit)
     */
    public static class Prompts {
        public int turn;
        public List<Prompt> items = new ArrayList<>();
    }

    public static class Prompt {
        public String type;       // "SELECT_TECH" | "INCOMING_DIPLOMACY" | "INCOMING_TECH_REQUEST" | ...
        public int category;      // research category (0-5) for SELECT_TECH, else -1
        public String text;       // human-readable
        // SELECT_TECH: the techs available to research now, so the prompt is
        // self-contained (independent of view/notification ordering). Parallel
        // arrays; resolve by sending SetResearchChoice{category, chosen id}.
        public String[] choiceIds;
        public String[] choiceNames;
        // INCOMING_DIPLOMACY: who is offering, and what.
        public int empireId = -1;         // the empire making the offer
        public String action;             // TRADE | PEACE | PACT | ALLIANCE
        // COLONIZE: the system a colony ship is orbiting and may settle.
        public int systemId = -1;
        // INCOMING_TECH_REQUEST: another human wants this tech of yours. The
        // choices are *their* techs you may demand in exchange (engine-priced);
        // resolve with RespondTechRequest, or ignore to refuse.
        public String techId;
        public String techName;
    }

    /**
     * client -> server: the human's answer to an INCOMING_DIPLOMACY prompt. empireId is
     * the offering empire; action matches the prompt (TRADE/PEACE/PACT/ALLIANCE). The
     * engine applies the human's accept/refuse via that empire's diplomat.
     */
    public static class RespondDiplomacy {
        public int empireId;
        public String action;
        public boolean accept;
    }

    /**
     * client -> server: the human's vote in response to a COUNCIL_VOTE prompt.
     * candidateId is the empire voted for, or -1 to abstain.
     */
    public static class CastCouncilVote {
        public int candidateId = -1;
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
