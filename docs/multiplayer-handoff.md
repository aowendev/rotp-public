# ROTP Multiplayer — Session Handoff

Read this first when resuming the multiplayer work. It says where things stand,
how to run what exists, and exactly what to do next. The full design rationale is
in [`multiplayer-design.md`](multiplayer-design.md); this is the operational
"pick up here" note.

_Last updated: 2026-07-30 — Phase 1 complete; **Phase 1.5 backlog all addressed** through a live solo play session (race + homeworld naming, victory/defeat, fleet dispatch with per-ship counts, System info tab, ship-range indicators; plus fixes for a turn-advance lockup, turn-1 scout auto-launch, and stale homeworld/leader names). 37 tests green. Remaining: one human sign-off playthrough to final win/loss, then Phase 2. See "Do this next"._

## Where we are

Branch **`multiplayer`** (off `master`). **Phases 0 and 1 are complete and
committed; Phase 2 is underway** (lobby AI-fill / solo-vs-AI done). A game is
genuinely playable end-to-end over the wire: a headless server runs the real
game; the DTO client renders every core screen (galaxy map, colony, research,
fleets & transports, ship design, empire overview) from `PlayerView` and drives
every decision — economy, research (incl. choosing what to research), ship
design + build, expansion, transports, spy, diplomacy — via commands, holding no
game model. We-go turns; per-empire notifications (contact/diplomacy/colony/tech);
a lobby where the host can start against AI. **30 JUnit integration tests, green.**

**Continuing Phase 2** (LAN & session completeness) — see "Do this next".
Latest commit: `7a2cdd95` (Phase 2 lobby AI-fill).

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
- `ColonyPanel` also chooses which design the colony builds (`setShipBuild`);
  `ResearchPanel` lets the player choose each category's research target
  (`setResearchChoice`, dropdowns); `EmpirePanel` (Planet List ⌘P) is a read-only
  overview. Map clicks set the fleet-deploy destination. This is the
  **core-playable screen set**; the full economy→build→expand loop is clickable
  end-to-end (choose research → design → set colony build + ship spending → deploy).
- Verified by `itest/rotp/mp/` (30 tests): order/isolation, design/transport,
  spy/diplomacy (also assert notification delivery incl. TECH), the colony /
  research / fleets / ship-design / empire-overview screens (redistribution, DTO
  load, map click hit-test + fleet-destination, build-option load, research-choice
  round-trip, tech-completion notification, fleet summarization, free-slot/catalog,
  empire rollups, server equalizing research at start), and the lobby
  (`LobbyStartTest`: solo host vs AI, host-only start).
  Harness: `startServer` waits for the port to listen, then each client connects
  once (a WebSocketClient can't be reconnected — old retry loop was flaky).

## Do this next — Phase 1.5 (human-validated full playthrough)

**This is the current focus** (decided 2026-07-29 after a live solo play session).
Goal: prove by *actually playing* that a complete solo game can be played
**start → win/loss** in the Java reference client, and fix every gap that blocks
completion. **API-completeness-first, NOT UI polish** — the Java client is a
validation harness, not the product; real UX is deferred to the browser client
(Swing polish is throwaway). Add only enough Java UI to reach each action.

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

Caveat: council votes + incoming AI diplomacy are auto-resolved (Phase 3), so
"full playthrough" = you can win/lose a game, not every interactive prompt restored.

## Then — Phase 2 (LAN & session completeness)

**DONE — lobby AI-fill / solo-vs-AI.** `players=` is now the human *capacity*
(auto-starts when full, ruleset AI count). The first player is the **host**
(server sends `joined{empireId, host}`); the host may `startGame{aiOpponents}`
early, and the game fills to `humans-present + aiOpponents` empires (clamped to
`options.maximumOpponentsOptions()`). Client shows an AI-count spinner + Start
button to the host. See `GameServer.startGame(int aiOverride)` and
`LobbyStartTest`. This realizes the solo-vs-AI-on-LAN requirement.

Remaining Phase 2:

1. **Reconnection**: let a dropped client rejoin its empire (the game keeps running
   on the server; a rejoining client just needs a fresh `PlayerView`). Match a
   returning `hello` to a departed player's slot (e.g. by name) rather than a new
   join; re-send `gameStarted` + a `view`.
3. **Multiplayer save/load**: the whole `GameSession` already serializes
   (`saveSession`/`loadSession`); add lobby actions to save/restore a running game,
   including the `remoteHuman` flags.
4. **Lobby polish**: race/color picks before start.

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
- **Auto-colonize.** Under server autoplay, a colony ship auto-settles a suitable
  planet on arrival (the colonize prompt auto-resolves). Deliberate v1 behavior;
  the explicit `colonize` command is for planets the AI declines.
- **Headless boot order.** Server init must mirror `RotPUI`'s data loading:
  `SessionUI.set(new ServerUI())` **first**, then `UserPreferences.load()`,
  `TechLibrary.current()`, `LanguageManager.current().selectedLanguageName()`
  (the lazy `languages()` call does the real label/race loading;
  `selectDefaultLanguage()` is a no-op trap). Encoded in `ServerMain` and
  `MpTestSupport.bootEngine()`.
- **Small hulls are tight.** A MOO1 nuclear bomb doesn't fit a small hull with
  default fittings — tests use a medium hull. Not a bug.
- **Unseeded RNG.** `Base.random` is unseeded, so galaxy geometry and contact
  timing vary run to run. Geography-dependent test paths use JUnit assumptions to
  skip (not fail). Seeding the RNG for deterministic tests is a worthwhile future
  task and would also enable lockstep-style verification.

## Where things live

- `src/rotp/mp/protocol/` — `Protocol` (envelope/registry), `Messages`, `PlayerView`.
- `src/rotp/mp/server/` — `ServerMain`, `GameServer` (lobby + commands + turn driver),
  `PlayerViews` (DTO builder), `NotificationCenter` (per-empire event diffing),
  `ServerUI` (headless `SessionUI`).
- `src/rotp/mp/client/` — `NetClient`, `ClientMain`, `GalaxyViewPanel` (clickable
  map), `ColonyPanel` (colony screen), `ColonyAllocations` (pure spending logic).
  DTO-rendered, no game model on the client.
- `itest/rotp/mp/` — integration tests + `MpTestSupport` harness.
- Engine seams: `rotp.model.game.SessionUI`; `Empire.decidedByAI/isRemoteHuman`;
  moved statics in `Rotp` (scaling, debug file) and `GameSession` (pending options).
