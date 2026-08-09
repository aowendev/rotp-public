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
**Phase 3 is complete for v1: 7 increments landed.** (1) Interactive **tech selection** —
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
broadcast to every client as NEWS notifications instead of being dropped. (7) **Combat /
spy alerts** — the engine's per-turn `GameAlert`s (transports killed/perished, bases /
factories sabotaged, tech stolen, spy report, ...) are delivered to the affected human as
ALERT notifications, routed per-recipient (works in a 2-human game). Prompts (1-4) ride a
reusable `Prompts` message. **Backend hardening (2026-08-09) then closed out the multi-human
gaps as a pre-Phase-5 gate**: per-recipient alert routing, GNN ranking bulletins as NEWS, a
host turn-timer lobby pick, and a 2-human end-to-end test foundation. **Phase 4:
implementation essentially complete, NOT yet validated by human-vs-human play.** Landed:
reserve fund transfers, browser-grade reconnection via session tokens, contact-via-war,
a council vote that survives save/load, the fuller diplomacy backend (tech exchange with
counter-offers, aid, threats), per-empire multi-human outcomes, and the bombardment
choice. **Ship combat auto-resolves by decision** (2026-08-09) — a tactical battle would
stall every other player — so only the decisions *around* combat are on the wire.
**Four items remain open, all confirmed in scope** (user, 2026-08-09 — not to be dropped
or pushed to Phase 5): the steal-tech and sabotage-target choices, joint war offers, and a
written protocol spec. Plus **server error-hardening** as a standing requirement (anything
that could cause an error on the server must be resolved before Phase 5). Hosting moved to **Phase 6** (Scaleway for initial testing;
it also owes a front end that spins up a JVM per game). **Phase 5 is a separate private
repo**, not under the ROTP licence.
**108 tests green, 1 skip (incl. 2-human).** See "Then — Phase 3" / "Then — Phase 4"._

## Where we are

Branch **`multiplayer`** (off `master`). **Phases 0, 1, 1.5, and 2 (LAN & session
completeness) are complete, and Phase 3 (interactive mid-turn prompts) is complete for
v1** — lobby AI-fill / solo-vs-AI, reconnection, minimal save/load, race / galaxy-size /
AI-ability picks, and all seven Phase-3 prompt/notification increments are in; the
remaining color-selection and galaxy-size-option-set items are deferred to the web client
(Phase 5). **Phase 4 is in progress** — reserve transfers, session-token reconnection,
contact-via-war, save-mid-council-vote, fuller diplomacy and per-empire multi-human outcomes
have landed; tactical combat, the bombard/steal-tech/sabotage choices, joint war and a written
protocol spec have not (see "Then — Phase 4"). A game is
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
projections and per-category locks**. **108 JUnit integration tests green, 1 skip (incl. 2-human end-to-end).**

**Phase 2 is complete** (see "Then — Phase 2"); **Phase 3 is complete for v1** — all seven
increments (interactive tech selection; incoming diplomacy; council vote; colonize choice;
turn timers; public GNN news; combat/spy alerts) are in (see "Then — Phase 3"). Built on
`7a2cdd95` (Phase 2 lobby AI-fill); the Races panel, client reconnection, lobby
galaxy-size / AI-ability pickers, the colony/research/empire upgrades, minimal save/load,
and the Phase-3 prompt/notification increments are committed on top of it.

> **BACKEND SIGN-OFF (2026-08-09): the server/protocol backend is feature-complete and
> end-to-end tested for v1 — Phase 5 (browser client) can begin.** By deliberate decision,
> the remaining Phase-3 items were resolved and the backend proven per-empire-correct
> (2-human end-to-end tests, per-recipient alerts/reports, galaxy-wide GNN, framing) *before*
> starting the browser client, so **any bug found while building the Phase-5 client is a
> client bug, not a backend one.** The Java client in `rotp.mp.client` remains the reference
> implementation that proves the protocol; the browser client speaks the same
> JSON-over-WebSocket protocol. Outstanding items (backend gaps + edge cases + internet
> hosting) are gathered under **"Then — Phase 4"** as the work to finish before the web
> client is done. **Superseded (2026-08-09): that original six-item list was not the whole
> job** — Phase 4 now means *all* game functionality on the wire, and is unfinished. 101
> integration tests green
> (run in batches — see the test-env note there).

## Run it

```bash
# compile
mvn compile

# integration tests (headless) — the safety net; run these after any change.
# NB: run them in a few -Dtest=A,B,C batches, not one shot (see the test-env note
# under "Then — Phase 3"); batched runs are the source of truth.
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

# TWO MACHINES ON A LAN. `mvn package` builds two jars:
#   target/rotp-1.04-mp-SNAPSHOT.jar   ~969MB, everything (server + offline game)
#   target/rotp-client.jar             ~3MB, CLIENT ONLY
# The client renders from PlayerView JSON and holds no game model, so it needs none
# of the races/data/images/lang art — copy the 3MB one to the second machine, not
# the gigabyte. (The server and the offline desktop game DO need the assets.)
mvn package -DskipTests
ipconfig getifaddr en0            # the server machine's LAN address, e.g. 192.168.1.4

# machine A (server; no bind= means every interface, so the LAN can reach it)
java -Xmx384m -jar target/rotp-1.04-mp-SNAPSHOT.jar --server port=8777 players=2 size=small
# machine A can also play: java -jar target/rotp-client.jar --client host=localhost port=8777 name=Alice
# machine B (scp target/rotp-client.jar over first)
java -jar rotp-client.jar --client host=192.168.1.4 port=8777 name=Bob
# macOS may prompt to allow incoming connections for java on machine A - accept it.
# The game auto-starts once both human slots are filled.

# hosted on the internet (Phase 6): one JVM per game; bind=/keystore=/savedir= are
# the deployment args - see docs/deployment.md.
java -Xmx384m -jar target/rotp-1.04-mp-SNAPSHOT.jar --server port=8777 players=2 \
     bind=127.0.0.1 savedir=/var/lib/rotp/game-1
# ...and connect from anywhere (url= takes a full ws:// or wss:// URL, so it reaches
# a game behind a TLS proxy at a path, not just host:port)
java -jar rotp-client.jar --client url=wss://rotp.example.com/game/1 name=Alice
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
   dialog. See `GameOverTest`. **SUPERSEDED (2026-08-09): per-empire outcomes are in** —
   see "Multi-human outcomes" below; every human now gets their own verdict, and no
   engine change was needed.
5. **Scout-exploration convenience — DONE, folded into #3** (per-ship dispatch,
   click-to-target, System info tab, range indicators).

All Phase 1.5 backlog items are addressed; a solo game plays start → win/loss in
the reference client. A human should still do one uninterrupted full playthrough
to final victory/defeat to sign it off.

Caveat (historical, from Phase 1.5): council votes + incoming AI diplomacy were
auto-resolved at that time. Phase 3 is now complete for v1 — incoming diplomacy, council
votes, colonize choice, tech selection, turn timers, and GNN/combat/spy notifications are
all wired (see "Then — Phase 3"). What remains is post-v1 polish (multi-human alert
routing, GNN ranking, turn-timer lobby pick).

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

Next up (Phases 3 and — bar one conditional item — 4 are done, see below): **Phase 5**
(browser client). See design doc §7.

## Then — Phase 3 (interactive mid-turn prompts) — COMPLETE (v1)

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
auto-resolves). The timer is settable as a server arg AND as a host lobby pick
(`StartGame.turnTimerSeconds`; a spinner in the reference client) — see the pre-Phase-5
hardening section below.

**Increment 6 — public GNN news (DONE, 2026-08-08).** Galactic news turn-notifications are
now broadcast to every client as NEWS notifications (previously collected by `ServerUI`
and dropped). A small `rotp.ui.notifications.PublicNews { String newsText(); }` interface
is implemented by `GNNNotification` and `GNNRandomEventNotification` (both already carry
resolved display text); `GameServer.collectPostTurnPrompts()` now also picks out
`PublicNews` turn-notifications into `pendingPublicNews`, and `broadcastNotifications`
appends them (category `NEWS`) to every client's notification list. Covers random
galactic events, genocides, alliances formed/broken, council news, expansion, rebellion.
Test: `PublicNewsTest` (injected GNN news arrives as a NEWS notification; a quiet turn
carries none). **GNN NO-FOG (2026-08-09):** GNN is a galaxy-wide news network, so the
whole subsystem's fog gating on `player()` (genocide/alliance/rebellion/expansion notices
and every random-event notice gate on `player().knowsOf/hasContact/hasContacted` and frame
with `player().sv.name`) was routed through new `Base` helpers (`gnnKnowsOf`,
`gnnHasContact/ed`, `gnnKnowsSystem`, `gnnSysName`) governed by a session flag the server
sets (`GameSession.gnnIgnoresFogOfWar`). With it on, news about un-met empires/systems is
generated with true names and broadcast to everyone; single-player leaves the flag off so
it is unchanged. Test: `PublicNewsTest.gnnReportsNewsAboutEmpiresThePlayerHasNotMet`.
Earlier caveats all resolved: (a) ranking bulletins ARE carried (increment 2); (b) GNN is
no longer empire-0-fog-limited; (c) combat/spy `GameAlert`s ARE routed per-recipient — the
`GameAlert` base gained a `recipient` empire and the creation gates read `!decidedByAI()`
(see the pre-Phase-5 hardening section); (d) spy reports ARE per-empire and MOO1-style
espionage framing is in (see the SPY note in the hardening section).

**Increment 7 — combat/spy alerts (DONE, 2026-08-09).** The engine's per-turn
`GameAlert`s are now delivered to the human. They were gated on `isPlayerControlled()` at
their creation sites (Colony transports, Transport perish/capture, Sabotage bases/factories
incidents, Espionage tech-steal, Trespassing) — never true on the autoplay server — so
none fired. The gates now read `isPlayer()` (empire 0): a no-op for real single-player
(the human *is* empire 0), and on the server it makes empire-0's events generate their
(already empire-0-framed) alerts. `SpyReportAlert` already used `isPlayer()` via
`SpyNetwork`. Server: `GameSession.alerts()` accessor added; `GameServer.collectCombatSpyAlerts()`
(run from `collectPostTurnPrompts`) reads them, and `broadcastNotifications` delivers them
(category `ALERT`) **to empire 0's client only** (they're framed by `player()` = empire 0).
Client: ALERT flows through the existing Notifications handler. Test: `CombatSpyAlertTest`
sends empire-0 transports to an uncolonized system where they perish (a deterministic
`TransportsPerishedAlert`) and asserts the client receives an ALERT. (SUPERSEDED by the
pre-Phase-5 hardening below: combat/spy alerts now route per-recipient — the `GameAlert`
base gained a `recipient` empire and the gates read `!decidedByAI()` — and GNN ranking
bulletins ARE carried.)

**DECISION (2026-08-09): resolve the remaining Phase-3 items and get the backend
end-to-end tested BEFORE starting the browser client (Phase 5)** — a fully proven,
per-empire-correct backend means any bug found while building the browser client is
purely a client bug. **All four items are now DONE (2026-08-09).** (Test count has since
moved on with Phase 4 — 108 green, 1 skip.)
- **[1] Multi-human alert routing (DONE).** `GameAlert` base gained a `recipient` empire
  (defaults to `player()` when unset, so single-player/desktop are unchanged); each alert's
  `description()` frames from `recipient().sv`, and `create()` returns the instance so call
  sites chain `.recipient(...)`. The creation gates flipped from `isPlayer()` to
  `!decidedByAI()` (a true no-op for single-player incl. autoplay) across Colony
  (transports/invaders), Transport (perished/captured — one alert per human involved),
  Sabotage bases/factories, Espionage tech-steal, Trespassing. Server: `pendingAlerts` is
  keyed by recipient empire id; each empire gets its own alerts. Test:
  `CombatSpyAlertTest.aCombatAlertIsRoutedToTheAffectedHumanNotEveryone` (Bob/empire 1
  perishes transports → Bob gets the ALERT, Alice/empire 0 does not). (The note that
  SpyReportAlert stays empire-0 is stale: `GameSession` already loops `spyReportEmpires()`
  and raises a `SpyReportAlert().recipient(owner)` per empire, so spy reports route
  per-empire too — see the SPY note below.)
- **[2] GNN ranking bulletins (DONE).** `GNNRankingNotification` implements `PublicNews`;
  `newsText()` appends the ranked empires (list sorted strongest-first) to the title, and
  the existing `collectPublicNews` path broadcasts it as NEWS. Test:
  `PublicNewsTest.gnnRankingBulletinsAreBroadcastAsNews`.
- **[3] Turn timer as a lobby pick (DONE).** `StartGame.turnTimerSeconds` (>=0 sets it, -1
  keeps the server default); `handleStartGame` calls `setTurnTimer`; the reference client
  has a host-only turn-timer spinner. Test:
  `TurnTimerTest.theHostCanSetTheTurnTimerAsALobbyPick`.
- **[4] Multi-human test coverage (DONE).** `TwoHumanTest` (deterministic joins via
  `awaitJoined`: Alice=0, Bob=1; distinct fog-of-war views; a we-go turn resolves only when
  both are ready) + the 2-human alert-routing test above. Pattern for 2-human turns in
  tests: `alice.raw(new Ready())` (non-blocking) then `bob.ready()` (awaits the post-turn
  view).

**Backend is now considered complete and end-to-end tested for Phase 5.** GNN is now a
true galaxy-wide news network (no fog of war, broadcast to all — see "GNN NO-FOG" above).
**SPY (2026-08-09):** (1) spy reports are now per-empire — `SpyNetwork.enableSpyReport(owner)`
records the owning empire (`GameSession.spyReportEmpires`), gated on `!decidedByAI()`, and
each empire gets its own `SpyReportAlert` routed to its client (being spied ON — bases /
factories destroyed, tech stolen — already routes per-recipient). (2) MOO1-style
**espionage framing**: a standing per-target preference (`SpyNetwork.frameTarget`, set via
the `setSpyFrame` command, exposed as `EmpireDto.spyFrameEmpireId`, with a "Frame" combo in
the reference client's Races panel) pins the blame on a chosen scapegoat when your spy is
caught stealing tech — the we-go analogue of MOO1's reactive choice; AI still uses
`suggestToFrame`, single-player still uses the espionage UI. Tests:
`SpyDiplomacyTest.aHumanCanSetAnEspionageFramePreference`. GOTCHA fixed: `spyReportEmpires`
is transient, so it deserializes as null after a save/load — lazily created in the getter
(SaveLoadTest caught the NPE). Remaining limitation: per-empire `NotificationCenter` text
(colony/contact system names) is framed from each recipient's own `sv`, which is correct
per-empire.

NOTE (test env, 2026-08-09): the whole `mvn test` in one shot can wedge on this machine
under load (maven leaves a surefire fork that stops reporting; the timing-sensitive 2-human
tests then hit their 120s timeouts). It bites *batched* runs too if something else is
compiling at the same time — if a batch stops producing reports, `pkill -f surefire` and
re-run rather than waiting it out. Running the mp tests in a few `-Dtest=A,B,C` batches
is fast and reliable — all 79 pass that way; individual/batched runs are the source of
truth, not a single stalled full-suite invocation.

Phase 3 has no open blockers. The outstanding items (backend gaps + edge cases from every
phase before Phase 4, plus internet hosting) are gathered as **Phase 4 work — everything
that must be resolved before the web client (Phase 5) is done** — see "Then — Phase 4"
below.

**GNN public news is now broadcast** (increment 6 above); the historical note follows for
context. In the base engine GNN news is posted to the single global `GameSession`
turn-notification queue and rendered from the one local player's POV (`RotPUI`) — not
per-empire fog-of-war. Most GNN content is genuinely *public/galactic* (rankings,
genocides, alliances formed/broken, council, random galactic events), so "scoping" it for
MP mainly means **broadcasting to every client** (done, including GNN no-fog so news about
un-met empires still fires), while combat/spy `GameAlert`s ARE now routed to the affected
empire (per-recipient). All of GNN + combat/spy is wired.

## Then — Phase 4 — get *all* game functionality onto the wire

Phase 4 ends when a remote human can make **every decision the desktop game lets a local
human make**. That is the bar, because Phase 5 is purely a browser client speaking this
protocol: if the client reaches for something the protocol doesn't carry, the bug is
server-side and the sign-off is worthless. Anything still auto-resolved by the AI on a
remote human's behalf is Phase-4 work, not Phase-5 polish.

**STATUS (2026-08-09): the six items originally listed here are done — but Phase 4 is NOT
complete.** Reserve transfers, browser-grade reconnection, contact-via-war,
save-mid-council-vote, and the fuller diplomacy backend all landed; the hosting work has
**moved to Phase 6**. The original list was never the whole job.

> **PHASE 4'S REAL DEFINITION OF DONE (user, 2026-08-09): every piece of game
> functionality is on the wire.** Not "the items someone wrote down". Phase 5 is *purely*
> a new browser client talking to what Phase 4 built, so **any bug found in Phase 5 must
> be conclusively a client bug** — which only holds if there is nothing left for the
> client to reach for. Anything a human can decide in the desktop game, a remote human
> must be able to decide over the protocol.

See **"Then — Phase 4 — what is still missing"** for the outstanding list.

**Backend / protocol — resolve before the web client is complete:**
- **Reserve fund transfers — DONE (2026-08-09).** Both directions. **Out:** `transferReserve
  {systemId, amount}` → `Empire.allocateReserve` (lossless; the colony spends up to its own
  production next turn and keeps the surplus banked). **In:** the design question is settled
  — ROTP has *no* per-planet manual banking, the reserve is filled by an empire-wide tax on
  colony production (`addReserve(production × colonyTaxPct)`, banked at 50%), so the "add to
  reserve" order is a **rate, not a transfer**: `setEmpireTax{level, onlyDeveloped}` over
  `Empire.empireTaxLevel`. View gained `empireTaxLevel`/`maxEmpireTaxLevel`/
  `empireTaxOnlyDeveloped`/`empireTaxRevenue` and per-colony `reserveIncome`/
  `maxReserveNeeded`. `EmpirePanel` has the transfer + tax controls (select a colony row,
  spend BC; tax spinner). Tests: `ReserveTest` (4). The `TODO`s in `EmpirePanel.java` and
  `Messages.java` are gone. See `moo1-differences.md` §4.
- **Reconnection robustness for a browser — DONE (2026-08-09).** **Session tokens** are the
  policy: `Joined.sessionToken` is issued on join and replayed in `Hello.sessionToken`.
  `GameServer.claimByToken` matches it against `departed` *and* against still-attached
  connections — so a refresh whose old socket is still open takes over and the stale
  connection is evicted (only one connection may drive an empire). The token beats the name:
  a client may return under a different display name. Pre-start the same reclaim keeps the
  player's lobby slot (`rejoinLobby`) instead of consuming another one, so refreshes can't
  fill a lobby with ghosts; `Player.host` now carries the host role through a reclaim.
  Name matching stays as the fallback for token-less clients, and brand-new players are
  still rejected mid-game. The server also pings every 30s
  (`setConnectionLostTimeout(30)`) so a slept laptop or dead mobile link is noticed instead
  of leaving a ghost on the empire. Tests: `ReconnectTest` (5). The browser client should
  keep the token in `localStorage`.
- **Contact-via-war notification — DONE (2026-08-09).** `NotificationCenter.diff` used to
  `continue` past brand-new contacts in the relations loop, so a war that arrived *with* the
  contact was never reported. A new contact now diffs against `Relations.none()`, emitting
  CONTACT *and* the DIPLOMACY war note from the same turn. Test:
  `SpyDiplomacyTest.meetingAnEmpireByItsWarDeclarationReportsTheWarTooNotJustTheContact`.
- **Save-mid-council-vote — DONE (2026-08-09).** The convention tally in `GalacticCouncil`
  (`voteIndex`, `votes[]`, `totalVotes`, `votes1/2`, `candidate1/2`, `lastVoter/lastVoted`)
  is no longer `transient`, so a save taken mid-vote reloads with the convention intact.
  **The subtle half:** `votes[]` is indexed by the `voters()`/`empires()` ordering, which is
  *not* faithfully reproducible after a reload (it sorts by population), so those lists
  persist too and `nextTurn()` only clears them when no convention is open (`conventionOpen()`)
  — this also fixes a latent drift across an ordinary turn boundary mid-convention. Test:
  `SaveLoadTest.aCouncilVoteOpenAtSaveTimeSurvivesTheReload` (same candidates, same tally,
  same next voter, and the resumed vote can be cast). The `councilVoteOpen` guard in
  `GameServer` still defends the genuine pre-`convene()` case.
- **Fuller diplomacy backend — DONE (2026-08-09).** Built rather than deferred, because
  deferring it to Phase 5 would violate the backend sign-off principle (a gap found while
  building the web client would then be a *backend* bug). The rest of the MOO1 audience
  screen now rides the wire, hitting the same engine entry points the desktop diplomacy
  menus call:
  - **Technology exchange with counter-offers** — a trade is a *negotiation*, not one
    order, so it is a round trip: `requestTech{empireId, techId}` → the target names a
    price as `techCounterOffer{requestedTechId, counterOptions[]}` → `counterOfferTech
    {requestedTechId, offeredTechId}` closes it (or you walk away). Mirrors
    `DiplomacyTechRequestMenu` → `DiplomacyTechCounterMenu`.
  - **Human→human requests defer to the human.** `applyRequestTech` checks
    `!target.decidedByAI()` and, for another human, raises an **INCOMING_TECH_REQUEST**
    prompt carrying the requested tech plus *their* engine-priced counter options
    (`techsRequestedForCounter`), answered with `respondTechRequest{requestorId,
    counterTechId}` (empty = refuse). Without this the target's AI would trade their
    technology away for them — the same class of bug Phase 3 increment 2 fixed for treaty
    offers. Interception is at the **command layer, not the engine**: no AIDiplomat gate
    was touched, so single-player is bit-for-bit unchanged.
  - **Aid** — `offerAid{empireId, amount | techId}` (money from the reserve, or a
    technology), and **threats** — `threaten{empireId, EVICT_SPIES | STOP_SPYING |
    STOP_ATTACKING}`.
  - **Menu** — `diploOptions{empireId}` → `techTradeMenu` lists exactly what the server
    would accept right now (`canExchangeTech`/`canOfferAid`/`canThreaten*`, the techs you
    may request or gift with tier + research cost, the BC amounts you can afford). All of
    it comes from the diplomat AIs, so the client runs no trade math and sees no tech its
    spies haven't identified. Prices are **re-derived server-side** on every command — the
    client's copy of a counter-offer is never trusted.
  - Client: an **Audience…** button per race card in `RacesPanel`, driving the whole menu.
  - Tests: `TechTradeTest` (5). **GOTCHA:** `acquireTechThroughTrade` does *not* learn a
    tech — it records it in `tradedTechs()`, and `TechTree.acquireTradedTechs()` learns it
    when the turn resolves. Assert on `tradedTechs()` immediately and on `knows()` only
    after a `ready()`. **GOTCHA 2:** every exchange/gift/threat is gated on the engine's
    **economic range** (fog distance to their colonies vs scout range), so forced contact
    alone is *not* enough — and this made the suite flaky before it was understood. The
    tests now buy the range instead of hoping for it: climb the whole fuel-range ladder
    (`extendRange`), `sv.refreshFullScan` each side's colonies, and — for the two-human
    test, whose empires can't be chosen — plant a colony next door (`settleNextDoor`)
    rather than let star placement decide whether the most important test runs.
  - **AI→human requests defer too.** The one place an engine change *was* needed: an AI's
    own `makeDiplomaticOffers` tech path calls `receiveRequestTech` directly, which would
    have let a remote human's AI trade their technology away mid-turn without asking. All
    three `AIDiplomat` variants gained an `isRemoteHuman()` branch that queues the request
    (`DiplomaticNotification.createTechRequest`, carrying the tech id) and returns null, so
    the asking AI walks away with no deal; `GameServer.collectTechRequestPrompt` turns it
    into the same INCOMING_TECH_REQUEST prompt. **No-op for single-player** — the
    `isPlayerControlled()` modal branch above it is untouched, and `isRemoteHuman()` is
    false there.
  - Still v1 (not a gap in this class — nothing auto-resolves behind a human's back):
    joint-war offers and their counter-reply are not on the wire yet.

**Infrastructure:**
- **Internet hosting — DONE (2026-08-09), decision + plumbing.** Target is the **Oracle
  Cloud Always Free Ampere A1 ARM VM (4 OCPU / 24 GB)** running the plain fat JAR — no
  container (the shade plugin already produces a runnable JAR; Docker would be overkill).
  One JVM hosts one game (the engine has a process-wide `GameSession`), so N games = N
  processes on N ports; measured at ~128MB per game (see docs/deployment.md), so even a 1GB box fits several. New server args: `bind=` (listen
  on loopback behind a proxy), `keystore=`/`keystorePassword=` (serve `wss://` from the JVM
  itself; a bad keystore **fails startup** rather than silently serving plain `ws://`), and
  `savedir=` (per-game save dir, set without rewriting the shared prefs file via
  `UserPreferences.saveDirForThisProcess`). TLS recommendation is **Caddy in front** (a
  single static binary, auto-renews Let's Encrypt, and a renewal reloads *Caddy* — renewing
  inside each JVM would mean restarting it, ending the game in progress). Ships
  `deploy/rotp-game@.service` (systemd template, one instance per game) +
  `deploy/game-1.env.example` + **`docs/deployment.md`** (build, host layout, TLS both ways,
  and Oracle's *two* firewall layers — VCN security list and the instance's own iptables).
  Smoke-tested end to end: loopback bind verified with `lsof`, a real TLSv1.3 handshake
  served from a PKCS12 keystore, and the bad-keystore path failing before it listens.
  Tests: `DeploymentTest` (3).

### Then — Phase 4 — what is still missing

**Not yet validated by human-vs-human play.** Everything below and everything already
landed is proven only by in-process tests, where latency is zero and both clients share a
JVM. A first two-machine LAN game (2026-08-09) got as far as both players joining from
separate Macs, a galaxy generating and turns resolving; the rest is unexercised. The
checklist is **`mp-test-scenarios.md`** — treat Phase 4 as unfinished until it passes.

Found by auditing the engine for decisions a *local* human makes that a remote human
currently cannot. Everything here is a Phase-4 blocker under the definition above.

> **ALL FOUR CONFIRMED IN SCOPE (user, 2026-08-09).** None of these are to be dropped or
> pushed into Phase 5. Do not re-litigate; build them.

**The four remaining items:**

1. **`StealTechNotification` — which technology to steal.** After a successful espionage
   mission MOO1 lets you pick; a remote human never sees the choice, so the AI takes it.
2. **`SabotageNotification` — the sabotage target** (which colony's bases or factories).
   Likewise chosen for the player today.
3. **Joint war offers** — `receiveOfferJointWar` / `receiveCounterJointWar` and
   `DiplomacyJointWarMenu` have no protocol equivalent. The last audience action missing.
4. **A written protocol specification** — see below; it is what keeps the private Phase-5
   client a non-derivative work, so it is engineering, not paperwork.

(1) and (2) are **queued turn-notifications the server does not yet convert into
prompts** — the *same shape* as COLONIZE / INCOMING_DIPLOMACY and now BOMBARD, all of
which are done: the engine queues a notification, `collectPostTurnPrompts` turns it into
a prompt, and a command resolves it. The pattern is proven three times over, so these are
tractable; follow `collectBombardPrompt` and `applyBombard` as the template.

Alongside them, **server error-hardening** continues as a standing requirement rather than
a discrete item (see its section below): long-running fuzzing, oversized payloads and
many-client churn are still unaudited.

**Tactical ship combat — SETTLED, will not be built (user, 2026-08-09).** Combat
auto-resolves. The reason is decisive: an interactive battle is a multi-round screen
*inside* a we-go turn, so every other player sits idle while two of them fight. That is
also the original design decision (2026-07-18) rather than a new concession.

What that does **not** license is auto-resolving the decisions *around* a battle — those
are strategy, not tactics, and a human must make them:
- **Bombardment — DONE (2026-08-09).** `BombardSystemNotification` only ever asked an
  `isPlayerControlled()` empire, never true on the server, so it fell through to
  `fl.bombard()`: one player's world could be glassed with neither player asked.
  `AI.promptForBombardment` now gates on `!decidedByAI()` and the notification queues for
  a remote human, becoming a BOMBARD prompt resolved by `bombard{systemId}`. Declining
  leaves the fleet in orbit and re-asks next turn — **the point being that you may want
  the factories intact to capture and steal technology**, which bombing destroys. The
  desktop auto-bombard preferences are bypassed for a remote human: they are a local
  setting on whatever machine happens to run the server. **Caveat: its test skips on a
  turn-1 galaxy (no armed fleet in orbit that early), so this is implemented but not
  proven end-to-end** — see `mp-test-scenarios.md` §2.
- **Ground invasion** is driven by the transport orders, which are already player-issued,
  so the decision is the human's; only the resulting battle auto-resolves. Worth
  confirming in play (`mp-test-scenarios.md` §2.6).

**Server error-hardening (user requirement, 2026-08-09): nothing a client sends may
cause a server error.** Same reason as everything else in this phase — a browser client is
written against the protocol by someone who cannot see the server, and will send things
the Java client never does. A dead or wedged server is indistinguishable from a protocol
misunderstanding, and it takes every other player's game down with it. Done so far:
- Every message handler is now wrapped (`onMessage` → `dispatch`). Only the *decode* was
  guarded before, so a null field or short array in any handler threw into the WebSocket
  read loop.
- A failed turn no longer freezes the game. `runTurn` had `try/finally` with no `catch`,
  so an exception skipped everything after it — including the status broadcast that
  re-enables Next Turn — and every client sat on "resolving" forever. The end-of-turn
  status broadcast now happens in the `finally`, for any throwable.
- **Bug found by the new tests: a repeat `hello` on an established connection closed
  that connection.** It fell through to the "game is full" branch and hung up on a player
  who was already happily connected; a browser retrying its handshake would just be
  dropped. It now re-sends identity and state.

Tests: `ServerRobustnessTest` — junk on the wire, null/out-of-range fields across the
command set, and out-of-order messages; each asserts the server still *resolves turns*
afterwards, not merely that it replied. Still to audit: long-running fuzzing, oversized
payloads, and many-client churn.

**Protocol specification (license-driven, new).** Phase 5 is a **separate private repo,
not under the ROTP licence** (see below). For that client to be a non-derivative work it
must be written from a *documented wire protocol*, not by reading or porting the GPL Java
`Messages`/`Protocol`/`PlayerView` classes. So Phase 4 owes a written spec of the message
set — every type, field, and the request/response and prompt/resolve flows. Without it
the only way to build the private client is to crib GPL source, which is exactly what the
licence boundary depends on not happening. **This is a Phase-4 deliverable, not
paperwork.** (Not legal advice — worth a lawyer's review, as the open-core note says.)

**Belongs to Phase 5 (web client), NOT Phase-4 backend work** — these use the
already-complete backend (listed here so they aren't mistaken for backend gaps):
- **Player color selection** — local view only, no server state; each client picks colors.
- **Galaxy-size option-set limiting** — the server validates the full `galaxySizeOptions()`;
  the web lobby presents a MOO-faithful subset (Small/Medium/Large/Huge) client-side. See
  the option-set-mismatch TODO under "Then — Phase 2".
- **Per-design partial fleet deploys** — the protocol already carries `deployFleet.counts[]`;
  the web client just surfaces per-design count controls (the reference client deploys whole
  fleets only).
- **Mac-port interaction feel** — reproduce the 1990s Mac port's menus/⌘-shortcuts (see
  `mac-ux-spec.md`) in the browser client.

## Multi-human outcomes — DONE (2026-08-09)

Everything above makes a *game* work over the wire; this is what makes a game with
**more than one human** work, ahead of a real two-machine test. Three faults, all from
the same root: the engine's single `GameStatus` is written from `player()`'s point of
view, so it can only ever describe empire 0.

- **Every human gets their own verdict.** New `rotp.mp.server.GameOutcomes` decides
  win/loss **per empire** — extinction, sole survivor, allied with every survivor,
  council leader / council ally / council loser — mirroring the engine's rules with
  "the player" replaced by "this empire". Pure and static, so it is unit-testable and
  mutates nothing. Same philosophy as `PlayerViews` and `NotificationCenter`: the
  server owns every empire, so it works out each one's events itself rather than
  reusing single-player machinery. Previously a second human who *conquered the
  galaxy* was told only "the game has ended".
  Empire 0 still prefers the engine's wording when the two agree, because it carries
  reasons the server cannot re-derive (overthrown, New Republic, rebellion) — but the
  global status is last-writer-wins across all empires, so when they **disagree** the
  per-empire verdict is the truth. (Concretely: wiping out everyone except empire 1
  leaves the global status reading WIN_MILITARY, which would have told the *destroyed*
  empire 0 that it won.)
- **One human losing no longer ends everyone's game.** `gameEnded` is now set when the
  *galaxy* is decided (`GameOutcomes.galaxyDecided`) or when every connected human has
  had their verdict — not when empire 0's status stops being IN_PROGRESS.
- **...and no longer freezes the galaxy.** The real blocker: `GameSession.nextTurnProcess`
  bails on `!inProgress()`, so once empire 0 died the engine silently stopped processing
  turns — the turn counter simply never advanced for anyone. `GameServer.keepGalaxyTurning()`
  restores the status before each turn while the game is still live for someone. Server-side
  only; single-player never reaches it.
- **An eliminated human stops holding up the turn.** `maybeRunTurn` and the ready tally
  now skip players whose empire is gone (`stillPlaying`), so a defeated player watching
  the rest of the game does not block it forever, and the status never reads "1/2 ready"
  waiting on a dead empire.

- **The reference client can reach a hosted game.** `--client url=ws(s)://host/path`
  alongside the existing `host=`/`port=`, because a game behind the Caddy proxy lives at
  a *path on 443*, which host:port cannot express. This is how a two-machine test is run
  before the browser client exists (`ClientMain.serverUrl`, covered in `DeploymentTest`).

Tests: `MultiHumanOutcomeTest` (4) — the second human is told they won; one human's
elimination neither ends nor freezes the other's game; the dead empire is out of the
ready tally; plus the pure rules.

**First two-machine LAN game run 2026-08-09.** Both players joined from separate Macs
(Alice on localhost as empire 0, Bob over the LAN as empire 1), a galaxy generated with
2 humans + 3 AI, colonize prompts fired for both, and turns resolved to 33. The rest of
the human-vs-human surface is still unexercised — see **`mp-test-scenarios.md`**.

**RESOLVED (user, 2026-08-09): a dropped player's empire is played by the AI until they
reconnect.** The observed behaviour was worse than it first appeared, and worse than
reported at the time: the empire did not "run on AI", it **stalled**. `decidedByAI()` is
false for a remote human whether or not they are connected, so for all 31 turns Bob was
away nothing reallocated his research, designed ships, commanded fleets or sent
transports — the empire sat frozen on its last orders while the galaxy moved on.

`Empire.awayFromKeyboard` (transient) now makes `decidedByAI()` true while a remote human
is disconnected; `GameServer.onClose` sets it and `reconnect` clears it. Deliberately a
*separate* flag rather than clearing `remoteHuman`: that field is what `resumeSavedGame`
reads to find the human slots, so a player who happened to be away when the game was saved
would come back to find their empire had become an AI. Transient because it describes a
live connection, which no save can carry. No effect on single-player, where `remoteHuman`
is false and `decidedByAI()` keeps its original meaning exactly.
Tests: `AwayFromKeyboardTest` (2).

## Then — Phase 5: the browser client (SEPARATE PRIVATE REPO)

**Purely a new browser client against the Phase-4 protocol. No server or protocol work
belongs here** — if Phase 5 needs a backend change, that is a Phase-4 miss, and it goes
back to Phase 4.

**It lives in a separate private repository and is NOT covered by the ROTP licence**
(user decision, 2026-08-09). This supersedes the earlier plan of shipping a *simple
reference web client* publicly under GPL — there is no public web client; the Java Swing
app in `rotp.mp.client` remains the public reference implementation that proves the
protocol.

What that costs, and why the spec above is load-bearing: the private client is a separate
non-derivative work **only if** it shares no GPL source and speaks purely the documented
wire protocol. It must therefore be written against the Phase-4 protocol spec — not by
translating `Messages.java`. Keep that discipline visible in the private repo's history.

Phase-5 scope (from the list above): player colour selection, the MOO-faithful galaxy-size
subset, per-design partial fleet deploys, and the Mac-port interaction feel
(`mac-ux-spec.md`).

## Then — Phase 6: hosting on Oracle, with a game launcher

Moved out of Phase 4 (user decision, 2026-08-09) — deployment is not backend
functionality, and lumping it in obscured what Phase 4 actually owed.

Already built and smoke-tested (see **`docs/deployment.md`** and `deploy/`): the
`bind=` / `keystore=` / `keystorePassword=` / `savedir=` server arguments, the
`rotp-game@.service` systemd template, and the Oracle Cloud ARM + Caddy runbook. One JVM
hosts one game at ~128MB measured, so a 1 vCPU / 1GB VM fits several.

**Still to build: a front end that spins up a JVM for players to join.** Today an
operator starts each game by hand with `systemctl start rotp-game@N`. Phase 6 needs a
service that lists running games, starts a new JVM on a free port when someone creates
one, hands back the `wss://` URL to connect to, and reaps finished games. Open questions
to settle when it starts: which repo it belongs in (it is server-side infrastructure, so
GPL-public by the protocol-boundary rule — but it is also the commercial hosting layer,
so this needs a deliberate call); how a game is authenticated/claimed; and the port and
memory budget per game.

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

Phases 2 (reconnection, MP save/load, lobby race picks) and 3 (interactive mid-turn
prompts with turn timers) are complete; **Phase 4 is in progress** (all game functionality
onto the wire), then Phase 5 (private browser-client repo) and Phase 6 (Oracle hosting +
game launcher). See design doc §7.

## Gotchas the tests and code already encode (don't relearn these the hard way)

- **Governor auto-rebalance trap.** ROTP's `AIGovernor.setColonyAllocations`
  human branch calls `baseSetPlayerAllocations` (a full rebalance) when a colony
  `hasNewOrders()`, has unallocated ticks, or awaits an allocation advisory. For
  remote humans it now fires only on unallocated ticks (new colonies). **Command
  handlers must never call `col.hasNewOrders(true)`** or wire orders get silently
  rewritten to AI patterns.
- **Deferral is the multiplayer rule, and it has two shapes.** Anything the engine would
  auto-resolve for an empire must not auto-resolve for a *remote human*. Where the decision
  arrives as a **command** (a human asking another human for a tech), intercept it in
  `GameServer` on `!target.decidedByAI()` — no engine change needed, so single-player cannot
  regress. Where the engine initiates it **mid-turn** (an AI's `makeDiplomaticOffers`, an
  incoming treaty offer, a council vote), the engine site itself must gate — on
  `!decidedByAI()` or `isRemoteHuman()` — queue a notification, and return null so the
  caller walks away with no deal. Prefer the command layer whenever the choice is available.
- **Traded techs are not learned on the spot.** `acquireTechThroughTrade` only records the
  tech in `tradedTechs()`; `TechTree.acquireTradedTechs()` learns it during turn processing.
  Anything asserting on a completed trade must advance a turn first.
- **Economic range gates all diplomacy.** `canExchangeTechnology` / `canOfferAid` /
  `canThreaten*` all require `inEconomicRange`, which compares fog-of-war distance to their
  colonies against scout range. Two empires can be in contact and still unable to trade —
  it is not a bug, and tests must engineer the range rather than assume it.
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
- Reconnection lives in `GameServer` (`departed` map, `Player.token`, `claimByToken`,
  `onClose`, `handleHello` → `reconnect` / `rejoinLobby`); the lobby galaxy-size pick in
  `GameServer.sizeOptions()` + `handleStartGame`, surfaced via `Messages.SizeOptions` /
  `StartGame.galaxySize`.
- Per-empire win/loss lives in `rotp.mp.server.GameOutcomes` (pure), used by
  `GameServer.checkGameOver`; `keepGalaxyTurning` and `stillPlaying` keep a multi-human
  game running after one player is knocked out.
- Fuller diplomacy lives in `GameServer` (`handleDiploOptions`, `applyRequestTech`,
  `applyCounterOfferTech`, `applyRespondTechRequest`, `applyOfferAid`, `applyThreaten`,
  `pendingTechRequests`, `collectTechRequestPrompt`) and `RacesPanel` (the Audience menu).
- `itest/rotp/mp/` — integration tests + `MpTestSupport` harness (new:
  `RacesScreenTest`, `ReconnectTest`, `GalaxySizeTest`, `DifficultyTest`,
  `SaveLoadTest`; Phase 4 added `ReserveTest`, `DeploymentTest`, `TechTradeTest`,
  `MultiHumanOutcomeTest`, `BombardPromptTest`, `AwayFromKeyboardTest` and
  `ServerRobustnessTest`). The one skip is BombardPromptTest's main case, which needs a
  mid-game state a turn-1 galaxy cannot provide.
- `deploy/` + `docs/deployment.md` — Phase-6 hosting: systemd template unit, per-game env
  file, and the Scaleway / Caddy / TLS runbook with measured per-game sizing.
- `docs/mp-test-scenarios.md` — the human-vs-human checklist Phase 4 must pass before it
  counts as done.
- Engine seams: `rotp.model.game.SessionUI`; `Empire.decidedByAI/isRemoteHuman`;
  moved statics in `Rotp` (scaling, debug file) and `GameSession` (pending options).
