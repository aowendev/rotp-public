# ROTP Multiplayer — Session Handoff

Read this first when resuming the multiplayer work. It says where things stand,
how to run what exists, and exactly what to do next. The full design rationale is
in [`multiplayer-design.md`](multiplayer-design.md); this is the operational
"pick up here" note.

_Last updated: 2026-07-29 — Phase 1 complete; Phase 2 started (lobby AI-fill / solo-vs-AI done)._

## Where we are

Branch **`multiplayer`** (off `master`). **Phases 0 and 1 are complete and
committed.** A game is genuinely playable end-to-end over the wire: a headless
server runs the real game; the DTO client renders every core screen (galaxy
map, colony, research, fleets & transports, ship design, empire overview) from
`PlayerView` and drives every decision — economy, research (incl. choosing what
to research), ship design + build, expansion, transports, spy, diplomacy — via
commands, holding no game model. We-go turns; per-empire notifications
(contact/diplomacy/colony/tech). 28 JUnit integration tests, green.

**Next is Phase 2** (LAN & session completeness) — see "Do this next".

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
- Verified by `itest/rotp/mp/` (28 tests): order/isolation, design/transport,
  spy/diplomacy (also assert notification delivery incl. TECH), and the colony /
  research / fleets / ship-design / empire-overview screens (redistribution, DTO
  load, map click hit-test + fleet-destination, build-option load, research-choice
  round-trip, tech-completion notification, fleet summarization, free-slot/catalog,
  empire rollups, server equalizing research at start).
  Harness: `startServer` waits for the port to listen, then each client connects
  once (a WebSocketClient can't be reconnected — old retry loop was flaky).

## Do this next — Phase 2 (LAN & session completeness)

Phase 1 is complete. Phase 2 is underway:

**DONE — lobby AI-fill / solo-vs-AI.** `players=` is now the human *capacity*
(auto-starts when full, ruleset AI count). The first player is the **host**
(server sends `joined{empireId, host}`); the host may `startGame{aiOpponents}`
early, and the game fills to `humans-present + aiOpponents` empires (clamped to
`options.maximumOpponentsOptions()`). Client shows an AI-count spinner + Start
button to the host. See `GameServer.startGame(int aiOverride)` and
`LobbyStartTest`. This realizes the solo-vs-AI-on-LAN requirement.

Next:

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
