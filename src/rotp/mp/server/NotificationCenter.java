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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rotp.model.empires.Empire;
import rotp.model.empires.EmpireView;
import rotp.model.galaxy.StarSystem;
import rotp.mp.protocol.Messages;

/**
 * Generates per-empire notifications server-side by diffing each empire's
 * own state across turns.
 *
 * ROTP's built-in notifications are written entirely from the single local
 * {@code player()}'s fog-of-war perspective and gated on
 * {@code isPlayerControlled()}, so they cannot be reused per empire. This
 * class instead observes what changed for a given empire — the same
 * philosophy as {@link PlayerViews}: the server owns every empire and builds
 * each player's view of events itself.
 *
 * v1 covers the unambiguous, high-signal events: first contact, diplomatic
 * status transitions, and colonies gained or lost. Richer events (tech
 * completed, combat outcomes, spy reports, GNN news) can be added by
 * extending {@link Snapshot} and {@link #diff}.
 */
public class NotificationCenter {
    private final Map<Integer, Snapshot> snapshots = new HashMap<>();

    /**
     * The outcome of diffing an empire across a turn: passive {@link Messages.Notification}s
     * ("what happened") plus interactive {@link Messages.Prompt}s ("a decision awaits you").
     * Prompts are advisory — the server has already applied its AI default (research never
     * stalls), so an ignored prompt simply leaves that default in place.
     */
    public static final class Result {
        public final List<Messages.Notification> notifications;
        public final List<Messages.Prompt> prompts;
        Result(List<Messages.Notification> notifications, List<Messages.Prompt> prompts) {
            this.notifications = notifications;
            this.prompts = prompts;
        }
        static Result empty() {
            return new Result(new ArrayList<>(), new ArrayList<>());
        }
    }

    /** record an empire's baseline without emitting anything (e.g. at game start) */
    public void seed(Empire emp) {
        snapshots.put(emp.id, capture(emp));
    }

    /** diff an empire against its last snapshot, emit notifications + prompts, and re-baseline */
    public Result update(Empire emp) {
        Snapshot prev = snapshots.get(emp.id);
        Snapshot cur = capture(emp);
        snapshots.put(emp.id, cur);
        if (prev == null)
            return Result.empty();
        return diff(prev, cur, emp);
    }

    public void forget(int empireId) {
        snapshots.remove(empireId);
    }

    private Snapshot capture(Empire emp) {
        Snapshot s = new Snapshot();
        for (StarSystem sys : emp.allColonizedSystems())
            s.ownedSystems.add(sys.id);
        for (Empire other : emp.contactedEmpires()) {
            s.contacted.add(other.id);
            EmpireView ev = emp.viewForEmpire(other);
            if (ev != null)
                s.diplo.put(other.id, Relations.of(ev));
        }
        s.knownTechs.addAll(emp.tech().allKnownTechs());
        return s;
    }

    private Result diff(Snapshot prev, Snapshot cur, Empire emp) {
        List<Messages.Notification> out = new ArrayList<>();
        List<Messages.Prompt> prompts = new ArrayList<>();

        // colonies gained / lost
        for (int sysId : cur.ownedSystems)
            if (!prev.ownedSystems.contains(sysId))
                out.add(note("COLONY_GAINED", "Gained colony at " + sysName(emp, sysId), sysId, -1));
        for (int sysId : prev.ownedSystems)
            if (!cur.ownedSystems.contains(sysId))
                out.add(note("COLONY_LOST", "Lost colony at " + sysName(emp, sysId), sysId, -1));

        // technologies researched since last turn. Each completed tech frees its
        // category to pick a new target, so raise a SELECT_TECH prompt (one per
        // category) inviting the human to choose — the server has already applied
        // the AI's default pick, so ignoring the prompt is harmless.
        Set<Integer> promptedCats = new HashSet<>();
        for (String techId : cur.knownTechs) {
            if (prev.knownTechs.contains(techId))
                continue;
            out.add(note("TECH", "Researched " + techName(techId), -1, -1));
            int catIndex = categoryOf(techId);
            if (catIndex >= 0 && promptedCats.add(catIndex) && hasResearchChoices(emp, catIndex))
                prompts.add(selectTech(emp, catIndex));
        }

        // first contact
        for (int empId : cur.contacted)
            if (!prev.contacted.contains(empId))
                out.add(note("CONTACT", "Made first contact with " + empName(empId), -1, empId));

        // diplomatic transitions. A brand-new contact diffs against "no relations",
        // so an empire met *via* a war declaration reports the war as well as the
        // contact (its CONTACT note is already emitted above).
        for (Map.Entry<Integer, Relations> e : cur.diplo.entrySet()) {
            int empId = e.getKey();
            Relations now = e.getValue();
            Relations was = prev.diplo.getOrDefault(empId, Relations.none());
            String name = empName(empId);
            if (now.war && !was.war)
                out.add(note("DIPLOMACY", "Now at war with " + name, -1, empId));
            else if (was.war && now.peace && !now.war)
                out.add(note("DIPLOMACY", "Signed a peace treaty with " + name, -1, empId));
            if (now.alliance && !was.alliance)
                out.add(note("DIPLOMACY", "Formed an alliance with " + name, -1, empId));
            else if (was.alliance && !now.alliance)
                out.add(note("DIPLOMACY", "Alliance with " + name + " has ended", -1, empId));
            if (now.pact && !was.pact)
                out.add(note("DIPLOMACY", "Signed a non-aggression pact with " + name, -1, empId));
            else if (was.pact && !now.pact)
                out.add(note("DIPLOMACY", "Non-aggression pact with " + name + " has ended", -1, empId));
        }
        return new Result(out, prompts);
    }

    /** the research category (0-5) a tech belongs to, or -1 if unknown */
    private static int categoryOf(String techId) {
        rotp.model.tech.Tech t = rotp.model.tech.TechLibrary.current().tech(techId);
        return (t == null || t.cat == null) ? -1 : t.cat.index();
    }

    /** whether this empire still has any tech to research in a category */
    private static boolean hasResearchChoices(Empire emp, int catIndex) {
        return !emp.tech().category(catIndex).techIdsAvailableForResearch().isEmpty();
    }

    private static Messages.Prompt selectTech(Empire emp, int catIndex) {
        rotp.model.tech.TechCategory cat = emp.tech().category(catIndex);
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String techId : cat.techIdsAvailableForResearch()) {
            rotp.model.tech.Tech tk = rotp.model.tech.TechLibrary.current().tech(techId);
            if (tk == null)
                continue;
            ids.add(techId);
            names.add(tk.name());
        }
        Messages.Prompt p = new Messages.Prompt();
        p.type = "SELECT_TECH";
        p.category = catIndex;
        p.text = "Choose the next " + categoryName(catIndex) + " research";
        p.choiceIds = ids.toArray(new String[0]);
        p.choiceNames = names.toArray(new String[0]);
        return p;
    }

    private static final String[] CATEGORY_NAMES = {
        "Computers", "Construction", "Force Fields", "Planetology", "Propulsion", "Weapons"
    };

    private static String categoryName(int catIndex) {
        return (catIndex >= 0 && catIndex < CATEGORY_NAMES.length) ? CATEGORY_NAMES[catIndex] : "technology";
    }

    private static Messages.Notification note(String category, String text, int systemId, int empireId) {
        Messages.Notification n = new Messages.Notification();
        n.category = category;
        n.text = text;
        n.systemId = systemId;
        n.empireId = empireId;
        return n;
    }

    private static String sysName(Empire emp, int sysId) {
        String n = emp.sv.name(sysId);
        return ((n == null) || n.isEmpty()) ? ("system " + sysId) : n;
    }

    private static String empName(int empireId) {
        Empire e = rotp.model.game.GameSession.instance().galaxy().empire(empireId);
        return (e == null) ? ("empire " + empireId) : e.name();
    }

    private static String techName(String techId) {
        rotp.model.tech.Tech t = rotp.model.tech.TechLibrary.current().tech(techId);
        return (t == null) ? techId : t.name();
    }

    private static final class Snapshot {
        final Set<Integer> ownedSystems = new HashSet<>();
        final Set<Integer> contacted = new HashSet<>();
        final Map<Integer, Relations> diplo = new HashMap<>();
        final Set<String> knownTechs = new HashSet<>();
    }

    private static final class Relations {
        final boolean war, pact, alliance, peace;
        private Relations(boolean war, boolean pact, boolean alliance, boolean peace) {
            this.war = war; this.pact = pact; this.alliance = alliance; this.peace = peace;
        }
        /** the baseline for an empire you had not met: no treaties, no war */
        static Relations none() {
            return new Relations(false, false, false, false);
        }
        static Relations of(EmpireView ev) {
            return new Relations(ev.embassy().anyWar(), ev.embassy().pact(),
                ev.embassy().alliance(), ev.embassy().atPeace());
        }
    }
}
