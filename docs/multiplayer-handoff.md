# ROTP Multiplayer — Session Handoff

Read this first when resuming the multiplayer work. It says where things stand,
how to run what exists, and exactly what to do next. The full design rationale is
in [`multiplayer-design.md`](multiplayer-design.md); this is the operational
"pick up here" note.

_Last updated: 2026-07-28, end of Phase 1 order-command work._

## Where we are

Branch **`multiplayer`** (off `master`). Phases 0 and the order-command portion of
Phase 1 are done and committed. The game is fully playable **at the protocol level**:
a headless server runs the real game, and clients drive every economic, military,
expansion, spy, and diplomatic decision over JSON/WebSocket. What's missing is
player-visible *output* (notifications) and a real UI (the Java client is still a
minimal galaxy-map stub).

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
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp --server port=8777 players=2
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
- Verified by `itest/rotp/mp/` (4 tests): order/isolation, design/transport,
  spy/diplomacy.

## Do this next (in order)

1. **Per-empire notification delivery.** *This is the recommended next task —
   server-side, well-scoped, and it makes the game observable before the UI port.*
   - Today `ServerUI` (`rotp.mp.server.ServerUI`) collects `TurnNotification`s
     into one list during turn resolution, but they are never routed to clients.
   - The single-player notification queue in `GameSession` is global (one human).
     For multiplayer, notifications must be attributed to an empire and delivered
     to that empire's client as a new `notifications` protocol message
     (list of {kind, text, systemId?, empireId?}). See design doc §3.1 / §4.
   - Investigate whether each `TurnNotification` can report its target empire; if
     not uniformly, start with the ones that already carry an empire/colony
     (spy reports, GNN, ship-construction, combat results) and route those.
   - Add a scripted assertion to a new/[existing] test: a spy or combat event
     produces a notification delivered to the right player and not others.

2. **Port the real Swing screens to the protocol** (largest remaining item).
   Replace direct-model reads in the main/colony/fleet/tech/design/races screens
   with `PlayerView` DTOs, and wire their buttons to protocol commands instead of
   direct model mutation. The DTO/command surface built in Phase 1 is designed to
   cover the "core playable set" (galaxy map, colony spending, fleets/transports,
   tech, ship design, empire status). Do it screen by screen; keep the desktop
   single-player path working (the same panels still run against a local
   `GameSession` — consider a client-side view provider so panels don't care
   whether data came from the model or the wire).

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
  `PlayerViews` (DTO builder), `ServerUI` (headless `SessionUI`).
- `src/rotp/mp/client/` — `NetClient`, `ClientMain`, `GalaxyViewPanel` (stub UI).
- `itest/rotp/mp/` — integration tests + `MpTestSupport` harness.
- Engine seams: `rotp.model.game.SessionUI`; `Empire.decidedByAI/isRemoteHuman`;
  moved statics in `Rotp` (scaling, debug file) and `GameSession` (pending options).
