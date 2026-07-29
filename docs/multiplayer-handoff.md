# ROTP Multiplayer — Session Handoff

Read this first when resuming the multiplayer work. It says where things stand,
how to run what exists, and exactly what to do next. The full design rationale is
in [`multiplayer-design.md`](multiplayer-design.md); this is the operational
"pick up here" note.

_Last updated: 2026-07-29, after the first DTO-rendered client screens (galaxy map + colony)._

## Where we are

Branch **`multiplayer`** (off `master`). Phase 0 and most of Phase 1 are done and committed. The game is fully playable
**at the protocol level**: a headless server runs the real game, clients drive
every economic, military, expansion, spy, and diplomatic decision over
JSON/WebSocket, and each player receives per-empire notifications of what
happened each turn. The client UI is now being built the DTO-rendered way
(a clickable galaxy map and a working colony screen exist); most remaining
work is porting the rest of the core-playable screens and broadening
notification coverage.

Commits so far: `Phase 0`, `Phase I part 1`, `Phase 1 part 2`, `Phae 1 part 3`
(plus this test/handoff commit).

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
  (`GalaxyViewPanel`) and a colony-management screen (`ColonyPanel`, with the
  pure `ColonyAllocations` redistribution logic). The client renders from
  `PlayerView` and acts via commands — **no game model on the client**. This is
  the settled architecture (design doc "Client rendering"): reuse of ROTP's real
  Swing panels was rejected because a browser can use none of it.
- Verified by `itest/rotp/mp/` (10 tests): order/isolation, design/transport,
  spy/diplomacy (also assert notification delivery), and colony-screen logic
  (redistribution, DTO load, map click hit-test).

## Do this next (in order)

1. **Port the remaining core-playable screens** — the DTO-client way (render from
   `PlayerView`, act via commands; grow `rotp.mp.client` screen by screen, like
   `ColonyPanel`). The order set already exists for all of these:
   - **Fleets & transports**: list own fleets, select, deploy/redeploy in range
     (`deployFleet`), send/abort transports.
   - **Research**: 6 category sliders → `setTechAllocations` (reuse the
     `ColonyAllocations`-style pure redistribution, but capped 0–60 each).
   - **Ship design**: catalog → build a design (`designCatalog` / `createDesign` /
     `scrapDesign` / `setShipBuild`).
   - **Empire/status overview**: colonies, totals, contact/diplomacy from `EmpireDto`.
   Keep pure rendering-independent logic in small non-Swing classes (browser
   blueprint + unit-testable), and add a `ColonyScreenTest`-style test per screen.
   Note: the desktop **single-player** game is untouched by this work — it still
   runs its own Swing UI against a local `GameSession`; the `rotp.mp.client`
   screens are a *separate* client, not a modification of the desktop screens.

2. **Extend notification coverage.** `NotificationCenter` currently diffs owned
   systems + contacts + treaty flags. Add snapshot fields + diff cases for
   tech completed, combat outcomes, spy reports, and GNN news. Note the one
   known gap: an empire met *via* a simultaneous war declaration reports only
   `CONTACT`, not the war (the war is still in `EmpireDto.atWar`).

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
  **Source the exact patterns/shortcuts from an authoritative reference — do not
  invent them.** Capture Mac-port-flavored interaction behavior in the DTO client
  as you build screens (it's the browser blueprint) and record it in a UX spec
  before the browser client work starts.

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
