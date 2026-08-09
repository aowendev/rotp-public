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
package rotp.mp.server;

import java.util.List;

import rotp.model.empires.Empire;
import rotp.model.empires.GalacticCouncil;
import rotp.model.galaxy.Galaxy;

/**
 * Win/loss, decided per empire.
 *
 * The engine keeps a single global {@code GameStatus} written entirely from
 * {@code player()}'s point of view — `Empire.goExtinct` asks {@code isPlayer()},
 * `GalacticCouncil.end` compares against {@code player()}. That is exactly right
 * for one local human and useless for several: in a two-human game it can only
 * ever describe empire 0, so everyone else was told a neutral "the game ended".
 *
 * So the server decides each empire's fate itself, the same way it already
 * builds each empire's view ({@link PlayerViews}) and events
 * ({@link NotificationCenter}) rather than reusing single-player machinery. The
 * rules below mirror the engine's, with "the player" replaced by "this empire".
 *
 * Pure and static: no engine mutation, so it is safe to ask at any point and
 * cheap to unit-test.
 */
public final class GameOutcomes {
    private GameOutcomes() { }

    /** one empire's result, as sent in a gameOver message */
    public static final class Outcome {
        public final boolean won;
        public final String reason;
        public final String text;
        Outcome(boolean won, String reason, String text) {
            this.won = won;
            this.reason = reason;
            this.text = text;
        }
    }

    /**
     * True when the galaxy itself is settled and no one has anything left to
     * play for: one empire standing, or a council leader elected without a
     * rebellion to fight over it. Note this is NOT "some player lost" — a
     * defeated human must not end anyone else's game.
     */
    public static boolean galaxyDecided(Galaxy gal) {
        if (gal == null)
            return false;
        if (gal.numActiveEmpires() <= 1)
            return true;
        GalacticCouncil council = gal.council();
        return (council != null) && council.hasLeader() && !council.finalWar();
    }

    /**
     * This empire's outcome, or null if it is still in the game. Order matters:
     * extinction first (it is unambiguous), then the council result (an elected
     * leader ends the game for everyone), then the military conditions.
     */
    public static Outcome forEmpire(Galaxy gal, Empire emp) {
        if ((gal == null) || (emp == null))
            return new Outcome(false, "DEFEATED", "Your empire has been destroyed.");
        if (emp.extinct())
            return new Outcome(false, "DEFEATED", "Your empire has been destroyed.");

        GalacticCouncil council = gal.council();
        if ((council != null) && council.hasLeader() && !council.finalWar()) {
            Empire leader = council.leader();
            if (leader == emp)
                return new Outcome(true, "DIPLOMATIC",
                    "Victory! The Galactic Council has elected you.");
            if (council.isAllied(emp))
                return new Outcome(true, "COUNCIL_ALLIANCE",
                    "Victory! The council elected your ally " + leader.name() + ".");
            return new Outcome(false, "DIPLOMATIC",
                "Defeat. The Galactic Council elected " + leader.name() + ".");
        }

        List<Empire> active = gal.activeEmpires();
        if (active.size() == 1)
            return active.get(0) == emp
                ? new Outcome(true, "MILITARY", "Victory! You have conquered the galaxy.")
                : new Outcome(false, "DEFEATED", "Your empire has been destroyed.");

        // every surviving rival is an ally: nobody left who could beat you
        if (!active.isEmpty() && active.contains(emp) && alliedWithEveryRival(emp, active))
            return new Outcome(true, "MILITARY_ALLIANCE",
                "Victory! You and your allies rule the galaxy.");

        return null;   // still playing
    }

    private static boolean alliedWithEveryRival(Empire emp, List<Empire> active) {
        for (Empire other : active) {
            if (other == emp)
                continue;
            if (!emp.alliedWith(other.id))
                return false;
        }
        return true;
    }
}
