# ROTP Multiplayer — Session Handoff

Read this first when resuming the multiplayer work. It says where things stand,
how to run what exists, and exactly what to do next. The full design rationale is
in [`multiplayer-design.md`](multiplayer-design.md); this is the operational
"pick up here" note.

_Last updated: 2026-08-08 — **Phase 1.5 signed off** (human validated the full
economy→research→build→expand→diplomacy loop across all core screens). A large
session-completeness batch landed live: Races/diplomacy panel (⌘R) with spy controls
+ report + leader disposition; client reconnection; lobby galaxy-size + AI-ability
pickers; colony live projections + per-category locks + eco-at-max + richer readout +
max-bases; research progress % + tech-completion alert + research locks; a richer
Empire Overview (planets window); one-decimal ship-range display; and **minimal
local save/load** (Save Game ⌘S; resume with `load=<name>`). **Phase 2
is complete** — its two open items (player-color selection and the galaxy-size
option set) are deferred to the web client, not built in the Java reference client.
**Phase 3 is now underway: increments 1-4 are in.** (1) Interactive **tech selection** —
when a research category completes a tech, the server raises a self-contained SELECT_TECH
prompt and the client pops a chooser. (2) **Incoming diplomacy** — when another empire
offers a treaty/trade to a remote human, the offer is deferred (not auto-resolved) and
delivered as an INCOMING_DIPLOMACY prompt the human accepts/declines (`respondDiplomacy`).
(3) **Council vote** — the Galactic Council now works headlessly (it previously would
*hang* the server) and, when it is a remote human's turn to vote, raises a COUNCIL_VOTE
prompt resolved with `castCouncilVote`. (4) **Colonize choice** — a remote human's colony
ship no longer auto-settles; arriving at a colonizable system raises a COLONIZE prompt
resolved with the existing `colonize` command (or ignored to leave the ship in orbit).
(5) **Turn timers** — an optional per-turn deadline (server `timer=<secs>` arg or
`setTurnTimer`, off by default) auto-resolves a we-go turn so an absent human can't stall
it; `TurnStatus.secondsRemaining` drives a client countdown. (6) **Public news (GNN)** —
galactic-news turn-notifications (random events, genocides, alliances, council, ...) are
now broadcast to every client as NEWS notifications instead of being dropped. Prompts
(1-4) ride a reusable `Prompts` message. **72 tests green.** See "Then — Phase 3"._

## Where we are

Branch **`multiplayer`** (off `master`). **Phases 0, 1, and 1.5 are complete, and
Phase 2 (LAN & session completeness) is complete** — lobby AI-fill / solo-vs-AI,
reconnection, minimal save/load, and race / galaxy-size / AI-ability picks are all
in; the remaining color-selection and galaxy-size-option-set items are deferred to
the web client (Phase 5). **Phase 3 (interactive mid-turn prompts) is next.** A game is
genuinely playable end-to-end over the wire: a headless server runs the real
game; the DTO client renders every core screen (galaxy map, colony, research,
fleets & transports, ship design, empire overview) from `PlayerView` and drives
every decision — economy, research (incl. choosing what to research), ship
design + build, expansion, transports, spy, diplomacy — via commands, holding no
game model. We-go turns; per-empire notifications (contact/diplomacy/colony/tech);
a lobby where the host can start against AI, **choose the galaxy size and AI
ability (difficulty)**, and pick races; a **Races/diplomacy panel** on the client;
and **client reconnection** so a game survives a client relaunch; colony sliders
show **per-category result hints** (years/output/growth/RP) with **live
projections and per-category locks**. **72 JUnit integration tests, green.**

**Phase 2 is complete** (see "Then — Phase 2"); **Phase 3 is underway** — increments 1-6
(interactive tech selection; incoming diplomacy; council vote; colonize choice; turn
timers; public GNN news) are in (see "Then — Phase 3"). Built on `7a2cdd95` (Phase 2
lobby AI-fill); the Races panel, client reconnection, lobby galaxy-size / AI-ability
pickers, the colony/research/empire upgrades, and minimal save/load are committed on
top of it across this session.

## Run it

```bash
# compile
mvn compile

# integration tests (headless, ~30s) — the safety net; run these after any change
mvn test

# play a local game: one server, then N clients
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp --server port=8777 players=2 size=small
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp --client host=localhost port=8777 name=Alice
# solo vs AI: `players=1` auto-starts alone; or with players=N the first client
# (the host) presses Start in the lobby to begin with AI filling the empty slots

# save/load (local, for testing): in-game, Misc -> Save Game (Cmd-S) writes <name>.rotp
# to the server's save dir. Resume by (re)starting the server with load=<name>:
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp --server port=8777 load=mysave
# then reconnect a client (same name) — it rejoins its empire at the saved turn.

# classic offline single-player still works, unchanged
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp
```

## What works (done + tested)

- **Headless engine** via the `SessionUI` seam; offline single-player unaffected.
- **We-go turns**: per-player `ready` flags; turn resolves when all are ready.
- **Fog-of-war `PlayerView`**: each client sees only what its empire knows.
- **Full player order set**, all server-validated for ownership/bounds/range:
  colony spending, research, fleet deploy, ship design (catalog/create/scrap/
  set-build), colonization, population transports, spy spending/missions,
  internal security, diplomacy (trade/peace/pact/alliance offers, treaty breaks,
  war) answered by the target's diplomat AI.
- **Control split** (`Empire.decidedByAI()` vs `isAIControlled()`): the server
  AI auto-resolves mid-turn prompts for everyone, but never overwrites a remote
  human's strategic orders. **This is the single most important invariant — when
  adding AI decision code, gate it on `decidedByAI()`, not `isAIControlled()`.**
- **Per-empire notifications**: the server generates each player's turn events
  itself (`rotp.mp.server.NotificationCenter`) by diffing that empire's state,
  rather than reusing ROTP's single-player notification classes. v1 covers first
  contact, diplomatic status changes, and colonies gained/lost.
- **DTO-rendered client screens**: `ClientMain` shows a clickable galaxy map
  (`GalaxyViewPanel`), a colony-management screen (`ColonyPanel`), a research
  screen (`ResearchPanel`), a fleets & transports screen (`FleetsPanel`), and a
  ship-design screen (`ShipDesignPanel`, populated from the `designCatalog`
  message) — each opened from a Mac-style menu bar that `ClientMain` wires to the
  Mac UX spec's shortcuts (⌘F Fleet List, ⌘D Ship Design, ⌘T Technology, ⌘N Next
  Turn). Pure, unit-tested helpers back the panels: `ColonyAllocations` (colony@50
  / research@60 redistribution), `FleetView` (fleet summarization/deployability),
  and `ShipDesigns` (free-slot computation). The client renders from `PlayerView`
  and acts via commands — **no game model on the client**. Settled architecture
  (design doc "Client rendering"): reusing ROTP's real Swing panels was rejected
  because a browser can use none of it.
- `ColonyPanel` also chooses which design the colony builds (`setShipBuild`), sets
  the colony's **max missile bases** (`setColonyMaxBases` → `ColonyDefense.maxBases`
  — raise to build more, lower to scrap the excess; `ColonyDto.maxBases`), and
  shows a **per-category result hint** beside each slider — years-to-complete
  (Ship/Def), output per year (Ind), Waste/Clean/+n pop (Eco), and research points
  (Tech) — computed server-side via each category's `upcomingResult()` and carried
  in `ColonyDto.result` (the client runs no game math). The hints update **live
  while you drag**: the client sends a debounced `previewColony` (a hypothetical
  spend), the server projects the result without committing it (saves/sets/restores
  the allocations under `gameLock`) and replies `colonyPreview`; live projections
  show in blue, applied ones in grey. Each slider has a **lock** checkbox
  (`setColonyLock`) so a category holds its value during redistribution — e.g.
  keep ecology at "clean" while shifting the rest (locking with unsent edits
  commits the shown value first, so you lock what you see). The colony readout
  shows pop/max-pop (+growth/yr), planet size, factories, bases, waste, and
  production. **Ecology-at-max:** once a colony reaches max population, a locked
  ecology is auto-dropped to the cleanup minimum and the freed ticks go to the
  player's other unlocked categories (server-side, each turn, in
  `GameServer.lowerMaxedColonyEcoToClean()` — it bypasses the ecology lock around
  the engine's `lowerECOToCleanIfEcoComplete()`; the lock flag is preserved).
  `ResearchPanel` lets the player choose each category's research target
  (`setResearchChoice`, dropdowns), shows each category's **completion progress**
  toward its current tech (`TechDto.progress` = `totalBC/costForTech`, separate from
  the allocation %), and **locks a research category** (`setTechLock`, same as the
  colony screen, backed by `TechCategory.toggleLock`). When a tech is researched the
  client pops an **alert** (from the existing "TECH" notification) offering to open
  the Technology screen to pick the next research. `EmpirePanel` (Planet List ⌘P) is
    the planets window: a read-only per-colony table (#, system, pop/max, factories,
  waste, shield, bases, production, building, **notes** — rebellion / quarantine /
  space monster), the **empire economy** (planetary **reserve** amount, gross
  income, upkeep, net), and contacted-empire relations. **TODO — reserve fund
  transfers are deferred** (both spending reserve BC out to a colony and banking a
  planet's output into the reserve): the amount is shown but there are no transfer
  controls yet. A `transferReserve` command over `Empire.allocateReserve` was
  prototyped and pulled; re-add client + server when we take it up. (Note: ROTP
  auto-fills the reserve from excess colony output and has no MOO-style manual
  banking, so the "add to reserve" direction needs a design decision first.) Map
  clicks set the fleet-deploy destination. This is the
  **core-playable screen set**; the full economy→build→expand loop is clickable
  end-to-end (choose research → design → set colony build + ship spending → deploy).
- **Races/diplomacy panel** (`RacesPanel`, Misc → Races / ⌘R): one card per
  contacted empire showing the relationship, with Offer Trade (level spinner) /
  Peace / Pact / Alliance, Break Treaty, and Declare War — each enabled only when
  the server would accept it — plus a reply log. The legality rules live in the
  pure `Diplomacy` helper (mirrors `GameServer.applyDiploOffer/applyBreakTreaty/
  applyDeclareWar`). This is the client UI for the diplomacy commands, which were
  server-complete and test-covered but previously unreachable from the client; the
  `diploReply` message (verdicts) is now surfaced instead of dropped. Each card also
  has **spy controls** (a 0-20 spending spinner + HIDE/ESPIONAGE/SABOTAGE mission,
  wired to `setSpySpending`/`setSpyMission`) and a **Report** button that opens an
  intelligence dialog (relations, estimated relative strength, technologies
  identified, spy network, report age — from new `EmpireDto` intel fields
  `relativePower`/`knownTechCount`/`reportAge`, built server-side from `EmpireView`).
  Each card also shows the race's **leader disposition** (personality + objective,
  e.g. "Xenophobic Expansionist") from `EmpireDto.personality`/`objective` (ROTP's
  `Leader` models the MOO1 6×6 personality/objective set).
- **Client reconnection** (`GameServer`): a client that drops mid-game is held by
  name (`departed` map); a returning `hello` re-attaches to the same empire and
  replays `gameStarted` + a fresh view (the client re-requests the design catalog
  on its first view). Solo games pause while the only human is away. New players
  are still rejected mid-game. **This is why the reference client can be relaunched
  to pick up a rebuild without losing the game.**
- **Lobby galaxy-size picker**: the server offers the selectable sizes on join
  (`sizeOptions`, with labels + star counts) and the host's pick rides on
  `startGame.galaxySize`, overriding the launch `size=` default. Client shows a
  host-only Galaxy dropdown next to the AI-opponent spinner.
- **Lobby "AI ability" (difficulty) picker**: the server offers the levels
  (`difficultyOptions`, each labelled with the AI's production strength — Normal
  100%, Hardest 200%, etc.); the host's pick rides on `startGame.difficulty` and is
  applied via `selectedGameDifficulty`. Deliberately labelled **"AI ability"** on
  the client, since the level scales the AI's economy (a stronger opponent), not a
  human puzzle-difficulty. See the option-set-mismatch TODO under Phase 2.
- Verified by `itest/rotp/mp/` (61 tests): order/isolation, design/transport,
  spy/diplomacy (also assert notification delivery incl. TECH), the colony /
  research / fleets / ship-design / empire-overview screens (redistribution, DTO
  load, map click hit-test + fleet-destination, build-option load, research-choice
  round-trip, tech-completion notification, fleet summarization, free-slot/catalog,
  empire rollups, server equalizing research at start), the lobby
  (`LobbyStartTest`: solo host vs AI, host-only start), the **Races panel**
  (`RacesScreenTest`: diplomacy legality rules + panel load), **reconnection**
  (`ReconnectTest`: rejoin same empire mid-game, new player still rejected), and
  the **galaxy-size picker** (`GalaxySizeTest`: sizes offered, host choice
  overrides the default, invalid size rejected), and the **AI-ability/difficulty
  picker** (`DifficultyTest`: levels offered with AI-strength %, host choice
  applied, invalid rejected). `SpyDiplomacyTest` now establishes first contact
  **deterministically through the in-process engine** (`human.makeContact(other)`)
  instead of scouting up to 120 turns — it runs in ~3s and is stable. This also
  removed a nasty cascade: the old ~120s scouting-miss path could leave the shared
  static `GameSession` wedged, timing out every test ordered after it. If you add
  another geography-dependent test, prefer forcing the state via the engine over
  scouting loops for the same reason (the shared engine is not isolated per test).
  Harness: `startServer` waits for the port to listen, then each client connects
  once (a WebSocketClient can't be reconnected — old retry loop was flaky).

## Phase 1.5 — human-validated playthrough: **DONE** (2026-08-08)

**Signed off.** A human played a solo game far enough to confirm the whole
economy→research→build→expand→diplomacy loop behaves as expected across all the
core screens (they didn't drive it to a literal win/loss, but nothing misbehaved
and every action was reachable). During that validation a large batch of
completeness gaps surfaced and were fixed live — colony live-projections + locks +
eco-at-max + richer readout + max-bases, research progress % + completion alert +
locks, Races spy controls + report + leader disposition, the richer Empire
Overview, the lobby galaxy-size / AI-ability pickers, one-decimal ship-range
display, client reconnection, and minimal save/load. Phase 1.5's original goal
(API-completeness-first, add only enough Java UI to reach each action) is met.

Historical goal (kept for context): prove by *actually playing* that a complete
solo game can be played **start → win/loss** in the Java reference client, and fix
every gap that blocks completion. The Java client is a validation harness, not the
product; real UX is deferred to the browser client (Swing polish is throwaway).

A playthrough must exercise: pick a **race** (+color; homeworld named by race) →
explore & **colonize** (usable dispatch) → research incl. **choosing** what to
research → **design + build** ships (see them built) → move fleets & see a
**combat** outcome → some **diplomacy** → **observe victory/defeat**.

Division of labor: the **human plays/validates**; the model fixes what they hit
(can't meaningfully drive a Swing app via automation). Start with #1.

Known backlog:
1. **Race selection — DONE (2026-07-30).** Protocol: `raceOptions` (server→client
   on join, the 10 selectable races with id/name/trait), `pickRace` (client→server),
   and `Slot.raceId` on the lobby roster. Server (`GameServer`): each joiner is
   defaulted to the first free race (`firstFreeRace()`); `handlePickRace` validates
   the id is a starting race and not held by another player, else refuses + resyncs
   that client with a fresh lobby; `startGame` applies each human's pick to the
   options (empire 0 → `selectedPlayerRace`, human at empire `k` → `selectedOpponentRace(k-1)`
   — the factory maps opponent slot `i` to empire `i+1`, verified in `buildAlienRaces`).
   AI-filled slots stay null → factory picks random unused races. Client: a race
   dropdown in the lobby bar (all players), preselected from the lobby broadcast,
   hidden on game start. See `RaceSelectionTest` (2 tests). **Color** deferred:
   only the host (empire 0) has a clean `selectedPlayerColor`; opponent colors are
   factory-assigned, so a symmetric color picker needs more plumbing — do it with
   the browser lobby.
2. **Homeworld name from race — DONE, rode along with #1.** `GalaxyFactory.newGalaxy`
   already names the homeworld via `playerRace.nextAvailableHomeworld()`, so fixing
   the race fixes the homeworld name; no extra work needed.
3. **Fleet dispatch / colonize affordance — DONE (2026-07-30), via a live play
   session.** Colony/Fleets/System are now docked tabs on the main window (no
   hidden windows). Clicking a star targets it: your colony → Colony tab, a
   scouted star → System tab, an unexplored dot → Fleets tab. Dispatch got a
   **per-ship-type spinner row** (`DeployFleet.counts`) so scouts/colony ships
   leave the shared home stack individually. New **System** info tab reports
   planet type, capacity, ownership, and a colonizable verdict (`SystemDto`
   gained `planetTypeName`/`maxSize`/`canColonize`). **Range** is surfaced three
   ways (`SystemDto.distance`/`inShipRange`, `DesignDto.range`): out-of-range
   stars tinted red on the map (extended-range scouts reach `scoutRange`, colony
   ships only `shipRange`), a range line in the System tab, and a live
   out-of-range warning in the dispatch panel naming the ship type. Colony ship
   auto-settles on arrival (confirmed by the player). See `SystemInfoViewTest`.
   Several bugs were found and fixed while validating (all with regression tests):
   - **Turn stuck after turn 1**: the post-turn `TurnStatus` was broadcast before
     `turnRunning` cleared, so it reported `processing=true` and the client left
     the Next Turn button disabled forever. Moved it after the `finally`. `TurnAdvanceTest`.
   - **Turn-1 scout auto-launch**: ROTP auto-dispatches a new empire's scouts;
     `recallStartingFleets()` pulls them back into orbit so remote humans control
     the opening move (e.g. send the colony ship out). `StartingFleetTest`.
   - **Stale homeworld/leader name**: `selectedPlayerRace()` doesn't refresh the
     `NewPlayer` homeworld/leader names (the setup UI normally does), so a Bulrathi
     start was still named "Kholdan". `startGame` now clears the homeworld name and
     resets the leader. Covered by the homeworld assertion in `RaceSelectionTest`.
   Client also relabeled "Ready" → "Next Turn ▶".
4. **Victory/defeat signaling — DONE (2026-07-30).** New `gameOver` message
   (`won`, `reason`, `text`). After each turn `GameServer.checkGameOver()` reads
   the engine's `GameSession.status()` (evaluated from empire 0 = the "player",
   so authoritative for empire 0 / the solo game) and sends each empire its
   outcome: empire 0 gets the specific win/loss reason; any human whose empire
   went extinct gets `DEFEATED`; other surviving humans get a neutral `GAME_OVER`.
   `gameEnded` then stops further turn resolution. Client pops a Victory/Game Over
   dialog. See `GameOverTest`. **Not** covered: independent per-empire victory for
   multi-human games (needs an engine change to evaluate win/loss from each human's
   perspective) — do it with the deeper multiplayer work.
5. **Scout-exploration convenience — DONE, folded into #3** (per-ship dispatch,
   click-to-target, System info tab, range indicators).

All Phase 1.5 backlog items are addressed; a solo game plays start → win/loss in
the reference client. A human should still do one uninterrupted full playthrough
to final victory/defeat to sign it off.

Caveat (historical, from Phase 1.5): council votes + incoming AI diplomacy were
auto-resolved at that time. Both are now interactive (Phase 3 increments 2-3): incoming
offers and council votes prompt the remote human. Remaining auto-resolved mid-turn prompts
(colonize choice, combat/spy/GNN events) are the remaining Phase 3 backlog.

## Then — Phase 2 (LAN & session completeness)

**DONE — lobby AI-fill / solo-vs-AI.** `players=` is now the human *capacity*
(auto-starts when full, ruleset AI count). The first player is the **host**
(server sends `joined{empireId, host}`); the host may `startGame{aiOpponents}`
early, and the game fills to `humans-present + aiOpponents` empires (clamped to
`options.maximumOpponentsOptions()`). Client shows an AI-count spinner + Start
button to the host. See `GameServer.startGame(int aiOverride)` and
`LobbyStartTest`. This realizes the solo-vs-AI-on-LAN requirement.

**DONE — reconnection.** A dropped client rejoins its empire, matched by name.
`GameServer.onClose` stashes a departed player (`departed` map) once the game has
started; a returning `hello` re-attaches the new connection to the same `Player`
and replays `gameStarted` + a fresh `PlayerView` (`reconnect(...)`). Solo games
pause while the only human is away (empty `players` → no turn resolves); new
players are still rejected mid-game. See `ReconnectTest`. Not yet handled: a
client returning under a *different* name (no match), and multi-human races on the
same name (first match wins).

**DONE — lobby galaxy-size pick.** Server sends `sizeOptions` on join; the host's
choice rides on `startGame.galaxySize` and overrides the launch `size=` default
(`GameServer.sizeOptions()` / `handleStartGame`). See `GalaxySizeTest`.

**DONE — lobby difficulty (= AI ability) pick.** Server sends `difficultyOptions`
on join (each level labelled with the AI's production strength, e.g. Normal = 100%,
Hardest = 200%); the host's choice rides on `startGame.difficulty` and is applied
via `options.selectedGameDifficulty(...)`. Surfaced in the client as an **"AI
ability"** dropdown (not "difficulty"), because in ROTP the level scales the AI's
economy — it makes the opponent stronger, it is not a puzzle-difficulty knob for
the human. Default is `DIFFICULTY_EASY` (the engine default; MP games ran at Easy
before this). See `GameServer.difficultyOptions()` and `DifficultyTest`.

> **Option-set mismatch to resolve (design TODO).** ROTP exposes far more choices
> than the 1990s Mac original we're targeting for UX: **17 galaxy sizes**
> (Tiny…Ludicrous/Maximum) vs the original's Small/Medium/Large/Huge, and **7
> difficulty levels** (Easiest…Hardest) vs the original's Simple/Easy/Average/Hard/
> Impossible. Right now the lobby offers ROTP's full lists verbatim. We need to
> decide the mapping between the original's option set and ROTP's — either restrict
> the lobby to the original's choices (mapping each onto the closest ROTP constant)
> or present ROTP's full range and note the original-equivalents. Same question
> applies to any other setup option we surface later (opponents, research rate, etc.).
> Settle this when building the browser lobby against `mac-ux-spec.md`; the server
> already validates against ROTP's `galaxySizeOptions()` / `gameDifficultyOptions()`,
> so narrowing is a client/lobby concern, not an engine change.
>
> **Decision (2026-08-08): defer to the web client.** The web lobby will present a
> limited, MOO-faithful subset of sizes/difficulties client-side; the engine and
> server keep their full lists unchanged. Not a reference-client task.

**DONE — minimal save/load** (local, testing-focused). The host saves the running
game with the **Save Game** menu item (⌘S → name) → `saveGame` command →
`GameSession.saveSession(name.rotp)` in the server's save dir. To resume, start the
server with **`load=<name>`** (`ServerMain` → `GameServer(..., loadFile)` →
`resumeSavedGame()` → `loadSession`, `gameStarted=true`); connecting clients are
handed the save's `remoteHuman` empires (which serialize with the game) via the
existing `reconnect(...)` path (by-order for now; name matching is a later nicety).
`SaveLoadTest` covers save→resume→continue. **Future:** a client-driven mid-session
load (no server restart), and — eventually — importing **original MOO1 save files**
(a format-translation task, separate from this ROTP-native serialization).

**Phase 2 is COMPLETE (2026-08-08).** The two open items are deliberately **pushed
to the web client (Phase 5), not built in the Java reference client:**

1. **Player color selection → web client.** Colors matter only to the *local*
   human's view, so they need no server-side state. Each client can choose/assign
   display colors locally; the server keeps sending the factory `colorId` as a
   default the client may override. No protocol or server work — the web lobby owns it.
2. **Galaxy-size option set → web client** (the mismatch TODO above). The web lobby
   will present a limited, MOO-faithful subset (Small/Medium/Large/Huge) client-side,
   without changing ROTP's engine. The server already validates against the full
   `galaxySizeOptions()`, so this is purely which options the client chooses to offer.

Next up: **Phase 3** (interactive mid-turn prompts with turn timers — incoming
diplomacy, tech/council selection; async player-to-player diplomacy) and eventually
Phase 4 (internet hosting) / Phase 5 (browser client). See design doc §7.

## Then — Phase 3 (interactive mid-turn prompts) — UNDERWAY

The seam: a new **`Prompts`** message (`{turn, items:[Prompt]}`, registered `"prompts"`)
carries interactive decisions from server to client, alongside the passive
`Notifications` stream. A `Prompt` has a `type`, and is **self-contained** — it carries
everything the client needs to decide, so it does *not* depend on view/notification send
order (the SpyDiplomacyTest regression proved that dependence is fragile). Prompts are
**advisory**: on the headless server every empire is AI-controlled, so `AIScientist`
already auto-picks a default (research never stalls); an ignored prompt just leaves that
default in place. This is why we did **not** touch the engine's global `TurnNotification`
queue.

**Increment 1 — interactive tech selection (DONE, 2026-08-08).** When
`NotificationCenter.diff` sees a newly-known tech, it emits a `SELECT_TECH` prompt for
that tech's category (`Tech.cat.index()`), deduped per category, only if the category
still `hasResearchChoices`. The prompt carries `choiceIds`/`choiceNames` (from
`cat.techIdsAvailableForResearch()`). `update()` now returns a `Result{notifications,
prompts}`; `GameServer.broadcastNotifications` sends both. Client: `ClientMain` routes
`Prompts` → `ResearchPanel.promptSelectTech(prompt)`, which pops a chooser and sends the
existing `SetResearchChoice` (no new resolve message). Test:
`ResearchScreenTest.completingResearchRaisesAnInteractiveSelectTechPrompt`
(+ `MpTestSupport.Client.prompts` queue and `firstPrompt` helper).

**Increment 2 — incoming diplomacy (DONE, 2026-08-08).** The four receive-offer AI gates
(`receiveOfferTrade/Peace/Pact/Alliance`) in all three AIDiplomat variants (base/modnar/
xilmi) were changed from `empire.isPlayerControlled()` to `!empire.decidedByAI()` — a
**no-op for single-player** (the two are identical there) that makes a *remote* human's
empire defer an incoming offer (queue a `DiplomaticNotification`, return null) instead of
auto-resolving it. After each turn, `GameServer.collectIncomingDiplomacy()` drains the
engine's turn-notification queue (via `ServerUI.drainNotifications()`), converts each
deferred offer aimed at a connected human into an `INCOMING_DIPLOMACY` prompt (requestor
id + action), and `broadcastNotifications` sends them alongside the state-diff prompts.
Client: `ClientMain.promptIncomingDiplomacy` pops accept/decline → new `respondDiplomacy`
command → `GameServer.applyRespondDiplomacy` calls the human empire's own diplomat
(`acceptOfferPact` etc.), mirroring what the single-player UI does on click. Trade level
is recovered server-side from `view.trade().maxLevel()` (as the SP `OfferTradeMessage`
does), so the prompt needs no level field. Tests: `DiplomacyPromptTest` (accept signs the
pact; decline leaves none) — triggered deterministically through the in-process engine.
NOTE: outgoing offers to an AI are unaffected (the AI target is `decidedByAI`, still
answers immediately via `DiploReply`); a human→human offer now correctly defers to the
other human's prompt.

**Increment 3 — council vote (DONE, 2026-08-08).** Two parts. First, **council was made
headless-safe**: it previously would *hang the server* — `convene()` →
`CouncilVoteNotification.create()` → `RotPUI.instance().selectCouncilPanel()` pauses turn
processing and waits for a UI that never resumes it. Fixed by adding a default no-op
`selectCouncilPanel()` to the `SessionUI` seam and routing the notification through
`SessionUI.get()` (RotPUI still drives the vote in single-player; ServerUI no-ops). This
was a latent hang for any server game that reached council formation. Second, the three
vote gates in `GalacticCouncil` (`castNextVote`, `castPlayerVote`,
`continueNonPlayerVoting`) changed from `isPlayerControlled()`/`isPlayer()` to
`decidedByAI()` — a no-op for single-player — so the vote pauses on a *remote* human
instead of the AI voting for them. Server: after each turn `GameServer.driveCouncil()`
casts AI votes up to the next human voter (`continueNonPlayerVoting()` now stops there)
and, if paused on a connected human, raises a self-contained COUNCIL_VOTE prompt
(candidates as `choiceIds`/`choiceNames` + "-1" abstain). New `castCouncilVote` command →
`applyCastCouncilVote` casts the human's pick and resumes AI voting. `finalizePendingCouncilVote()`
runs *before* each turn resolves: if a human ignored the prompt, it casts their AI-default
vote so the convention closes rather than re-convening/resetting next turn (a livelock).
Both drive/finalize guard on `councilVoteOpen()` = `active() && votingInProgress() &&
totalVotes()>0`; the `totalVotes()>0` check avoids the transient state where
`votingInProgress()` reads true but the vote arrays aren't initialized (pre-`convene()`,
or after a **save mid-vote** reloads the transient arrays as null — a real edge case this
now defends against). Client: `ClientMain.promptCouncilVote` pops a candidate chooser →
`castCouncilVote`. Tests: `CouncilVoteTest` (a forced convention prompts the human and the
vote is accepted; an ignored vote is finalized, not left hanging) — the convention is
forced via reflection since 2/3-colonized is impractical to reach in a bounded test.
KNOWN GAP: a save taken *while* a council vote is open loses the vote (transient arrays);
on reload the council re-convenes fresh. Acceptable for now.

**Increment 4 — colonize choice (DONE, 2026-08-08).** A remote human's colony ship no
longer auto-settles on arrival. The gate in `AI.checkColonize` changed from
`empire.isAIControlled()` to `empire.decidedByAI()` (a no-op for single-player) so a
remote human hits the `ColonizeSystemNotification` path instead of `fl.colonizeSystem()`.
That notification is *queued* (unlike the council one, so no RotPUI hang); server-side it
is collected by `ServerUI` and `GameServer.collectPostTurnPrompts()` (renamed from
`collectIncomingDiplomacy`, now handling both diplomacy and colonize) converts it to a
COLONIZE prompt carrying the system id (new `Prompt.systemId`), after a last-minute
still-uncolonized/active-fleet check. The human resolves it with the **existing**
`colonize` command (no new command); ignoring it leaves the ship in orbit and the prompt
re-raises next turn (correct MOO behavior). Added `systemId()`/`fleet()`/`design()`
getters to `ColonizeSystemNotification`. Client: `ClientMain.promptColonize` pops yes/no.
Tests: `ColonizePromptTest` (colony ship prompts instead of auto-colonizing; accepting
settles the system) — deploys the real colony ship, `assumeTrue` if no colonizable system
is in range. NOTE: `ShipDesignTransportTest.tryColonize` now reaches the explicit-colonize
path (no auto-settle), so a `ready()` was added there before its COLONY_GAINED check
(colony gained via command surfaces the per-turn notification one turn later).

**Increment 5 — turn timers (DONE, 2026-08-08).** An optional per-turn deadline so an
absent/slow human can't stall a we-go turn. `GameServer.setTurnTimer(seconds)` (0 = off,
the default; wired to a `timer=<secs>` server arg in `ServerMain`) arms a single-thread
`ScheduledExecutorService` when orders open (game start and end of `runTurn`), cancels it
when a turn starts resolving (`maybeRunTurn`) or the game ends, and on expiry
(`onTurnTimeout`) auto-readies every player and calls `maybeRunTurn` — so a timed-out
turn resolves with whatever orders are in plus the server's AI defaults (identical to how
an ignored prompt already falls back). `TurnStatus` gained `secondsRemaining` (-1 = no
timer) for a client countdown; `ClientMain` shows "Xs left". The timer thread is a daemon
and `stop(int)` shuts the executor down. Tests: `TurnTimerTest` (expiry auto-resolves
without readying; readying still resolves immediately under a long timer; a 0 timer never
auto-resolves). NOTE: the timer is a *server/session* setting, not yet a lobby pick — a
host-facing lobby control (like galaxy size / AI ability) is the natural follow-up.

**Increment 6 — public GNN news (DONE, 2026-08-08).** Galactic news turn-notifications are
now broadcast to every client as NEWS notifications (previously collected by `ServerUI`
and dropped). A small `rotp.ui.notifications.PublicNews { String newsText(); }` interface
is implemented by `GNNNotification` and `GNNRandomEventNotification` (both already carry
resolved display text); `GameServer.collectPostTurnPrompts()` now also picks out
`PublicNews` turn-notifications into `pendingPublicNews`, and `broadcastNotifications`
appends them (category `NEWS`) to every client's notification list. Covers random
galactic events, genocides, alliances formed/broken, council news, expansion, rebellion.
Test: `PublicNewsTest` (injected GNN news arrives as a NEWS notification; a quiet turn
carries none). CAVEATS / remaining: (a) the engine composes GNN text from the local
player's (empire 0's) fog-of-war, so in a multi-human game the wording is empire-0-framed;
(b) **ranking bulletins** (`GNNRankingNotification`) are not yet carried — they store a
message-type key + empire list needing per-empire formatting; (c) **combat/spy alerts are
a separate subsystem** (`GameAlert` in `GameSession.alerts`, exposing only
`description()`, with no per-empire target accessor and a player-POV lifecycle) — routing
those per-empire needs target accessors added across ~10 alert classes and is deferred.

**Increment backlog (each: server prompt/notification + client handling + a `*Test`):**
- **Combat / spy alerts (`GameAlert`)** — the remaining notification work; add per-empire
  target accessors to the alert classes and route them to the affected empire (private),
  broadcasting the genuinely-public ones. Plus GNN **ranking** bulletins (need empire-list
  formatting).
- **Turn timer as a lobby pick** — expose `setTurnTimer` as a host lobby control (like
  galaxy size / AI ability) instead of only a server arg.
- **Async player-to-player diplomacy** — today a human→human offer already defers to the
  other human's prompt (increment 2); a fuller negotiation UX (counter-offers, tech
  trades) is future work.

**GNN public news is now broadcast** (increment 6 above); the historical note follows for
context. In the base engine GNN news is posted to the single global `GameSession`
turn-notification queue and rendered from the one local player's POV (`RotPUI`) — not
per-empire fog-of-war. Most GNN content is genuinely *public/galactic* (rankings,
genocides, alliances formed/broken, council, random galactic events), so "scoping" it for
MP mainly means **broadcasting to every client** (now done for the text-carrying GNN
types), with a few naturally-private items (e.g. combat/spy `GameAlert`s) still to be
routed only to the affected empire.

Deferred Phase-1 polish (pick up any time): per-design partial fleet deploys
(`deployFleet.counts[]` — surface per-design count spinners on a selected fleet);
`NotificationCenter` combat/spy/GNN events (event-based — natural Phase-3
companions). Known notification gap: an empire met *via* a simultaneous war
declaration reports only `CONTACT`, not the war (still in `EmpireDto.atWar`).

Reminders for any further screen/order work: keep pure rendering-independent logic
in small non-Swing classes (browser blueprint + unit-testable) with a
`ColonyScreenTest`-style test; the desktop **single-player** game is untouched
(`rotp.mp.client` is a separate client). Gotcha: per-turn state the desktop UI
would initialize for a human (e.g. `tech().equalizeAllocations()`) is NOT set for
remote humans server-side — initialize it in `GameServer.startGame` (as done for
research).

## Product requirements to keep in mind (design doc §1)

- **Solo-and-AI on LAN.** A LAN game must be playable with one human vs AI, with
  empty human slots AI-filled. *Already works engine-side* — the server owns
  every empire and only filled slots are human (the tests run 1 human + AI
  opponents). The remaining piece is a **Phase-2 lobby** that lets the host start
  with whoever is present and choose the AI-opponent count, instead of waiting
  for a fixed head-count (today the game starts only when `players=` humans join).
- **Mac-port interface feel (Phase 5 browser client).** Reproduce the interaction
  behavior + keyboard shortcuts of the **1990s Macintosh port** of MOO (native Mac
  GUI: menus, windows, ⌘-shortcuts), not the DOS keyboard interface ROTP emulates.
  **This is now documented first-hand in [`mac-ux-spec.md`](mac-ux-spec.md)** —
  complete menu/⌘-shortcut map + core-screen captures (`docs/mac-ux/`), read off the
  1995 release running in an emulator. Bind those shortcuts in the browser client
  (esp. ⌘N = Next Turn → the Ready action) and echo the Map+Info window layout.
  Not-yet-captured screens (Fleet List, combat, council, etc.) are listed in the
  spec's §5 for a future pass.

Then Phase 2+ (reconnection, MP save/load, lobby race/color picks), Phase 3
(interactive mid-turn prompts with turn timers), Phase 4 (internet hosting),
Phase 5 (browser client). See design doc §7.

## Gotchas the tests and code already encode (don't relearn these the hard way)

- **Governor auto-rebalance trap.** ROTP's `AIGovernor.setColonyAllocations`
  human branch calls `baseSetPlayerAllocations` (a full rebalance) when a colony
  `hasNewOrders()`, has unallocated ticks, or awaits an allocation advisory. For
  remote humans it now fires only on unallocated ticks (new colonies). **Command
  handlers must never call `col.hasNewOrders(true)`** or wire orders get silently
  rewritten to AI patterns.
- **Colonize choice (Phase 3 increment 4).** A remote human's colony ship no longer
  auto-settles — arrival at a colonizable system raises a COLONIZE prompt, resolved with
  the `colonize` command (see `AI.checkColonize` gated on `decidedByAI()`). AI empires
  still auto-settle. Ignoring the prompt leaves the ship in orbit and re-prompts next turn.
- **Headless boot order.** Server init must mirror `RotPUI`'s data loading:
  `SessionUI.set(new ServerUI())` **first**, then `UserPreferences.load()`,
  `TechLibrary.current()`, `LanguageManager.current().selectedLanguageName()`
  (the lazy `languages()` call does the real label/race loading;
  `selectDefaultLanguage()` is a no-op trap). Encoded in `ServerMain` and
  `MpTestSupport.bootEngine()`.
- **Small hulls are tight.** A MOO1 nuclear bomb doesn't fit a small hull with
  default fittings — tests use a medium hull. Not a bug.
- **Ship range is 3.0 LY and is NOT galaxy-size-scaled.** Base range =
  `TechFuelRange` quintile-0 (`3`) × `options.fuelRangeMultiplier()` (default `1.0`,
  driven by the *fuel-range option*, not galaxy size). The engine's in-range check
  is float (`distance <= shipRange()`), so a star 3.2 LY away is genuinely out of a
  3.0 range — the panels now show one decimal (`3.2 ly`) so it no longer looks like
  a reachable "3 ly". Caveat: `ShipDesign.range()` truncates to `int`, so under a
  non-default fuel-range multiplier (e.g. 1.5 → 4.5) `DesignDto.range` loses the
  fraction; harmless at the default multiplier where ranges are whole numbers.
- **Unseeded RNG.** `Base.random` is unseeded, so galaxy geometry and contact
  timing vary run to run. Geography-dependent test paths use JUnit assumptions to
  skip (not fail). Seeding the RNG for deterministic tests is a worthwhile future
  task and would also enable lockstep-style verification.

## Where things live

- `src/rotp/mp/protocol/` — `Protocol` (envelope/registry), `Messages`, `PlayerView`.
- `src/rotp/mp/server/` — `ServerMain`, `GameServer` (lobby + commands + turn driver),
  `PlayerViews` (DTO builder), `NotificationCenter` (per-empire event diffing),
  `ServerUI` (headless `SessionUI`).
- `src/rotp/mp/client/` — `NetClient`, `ClientMain` (window + Mac-style menu bar,
  incl. Races ⌘R and the lobby race/galaxy-size/AI controls), `GalaxyViewPanel`
  (clickable map), `ColonyPanel` (colony screen), `ColonyAllocations` (pure spending
  logic), `RacesPanel` + `Diplomacy` (diplomacy screen + pure legality rules).
  DTO-rendered, no game model on the client.
- Reconnection lives in `GameServer` (`departed` map, `onClose`, `handleHello` →
  `reconnect`); the lobby galaxy-size pick in `GameServer.sizeOptions()` +
  `handleStartGame`, surfaced via `Messages.SizeOptions` / `StartGame.galaxySize`.
- `itest/rotp/mp/` — integration tests + `MpTestSupport` harness (new:
  `RacesScreenTest`, `ReconnectTest`, `GalaxySizeTest`, `DifficultyTest`,
  `SaveLoadTest`).
- Engine seams: `rotp.model.game.SessionUI`; `Empire.decidedByAI/isRemoteHuman`;
  moved statics in `Rotp` (scaling, debug file) and `GameSession` (pending options).
