# ROTP Online Multiplayer — Design Document

> **Resuming this work?** Start with [`multiplayer-handoff.md`](multiplayer-handoff.md)
> for current status, how to run things, and the next task. This document is the
> design rationale behind it.

## 1. Goal and constraints

Add online multiplayer to Remnants of the Precursors (a Java/Swing remake of Master of Orion) such that:

- Games run locally and on LAN now, on internet-hosted servers later, with a browser client as the eventual end state.
- The wire protocol is the product: a language-neutral API between the game engine and any frontend (the RuneScape-style client/API split). The first client is the existing Java desktop app; nothing in the protocol may assume a Java client.
- Classic offline single-player must keep working unchanged throughout.
- **Solo-and-AI on LAN.** A LAN game must be playable with any number of humans from one upward — a lone host plays against AI opponents, and empty human slots are filled by AI. (Architecturally this is already how it works: the server owns every empire and only the filled slots are human; the remaining Phase-2 work is a *lobby* that lets the host start with the humans present and pick the AI-opponent count, rather than waiting for a fixed head-count.)
- **Mac-port interface feel in the browser client.** The final browser client should reproduce the *interaction behavior and keyboard shortcuts of the 1990s Macintosh port* of Master of Orion (which leaned on native Mac GUI conventions — menus, windows, ⌘-key shortcuts), not the keyboard-driven DOS interface that ROTP otherwise emulates. This is a Phase-5 UX constraint. The specifics are captured **first-hand from the running Mac game** in [`mac-ux-spec.md`](mac-ux-spec.md) (complete menu/⌘-shortcut map + core-screen captures), sourced by running the 1995 release in an emulator — **not invented**. Because the DTO Java client is the browser's blueprint (§4 "Client rendering"), interaction behaviors captured while building it should track that spec where practical.

The earlier `web` branch (Tomcat/JSP scaffold with a stubbed engine bridge and simulated data) was abandoned; work starts clean from `master` on the `multiplayer` branch.

## 2. Architecture overview

**Server-authoritative, JSON over WebSocket, we-go turns.**

- A **headless server** owns the one true `GameSession`/`Galaxy` model. Clients never hold trusted state and never run game math.
- Clients speak **JSON over WebSocket** using a `{"t": <type>, "d": <payload>}` envelope (`rotp.mp.protocol.Protocol`, Gson-based, types registered by short name so any language can implement them).
- State flows to each player as a **`PlayerView`** document — a view-model of *only what that empire knows*, built server-side from ROTP's existing fog-of-war structures (`Empire.sv` / `SystemInfo`, `EmpireView`). Information hiding is enforced by construction, not client trust. Computed display values (colony production, research points) are included so clients never reimplement game rules.
- Orders flow to the server as **commands**, validated against the sending player's empire and applied to the authoritative model between turns.
- **Turn model (we-go):** all players issue orders concurrently against their current view; each signals `ready`; when every connected player is ready the server runs the existing `GameSession.nextTurnProcess()` pipeline once, then broadcasts fresh views. A disconnect re-checks readiness so a departed player cannot block the game. This follows the MOO2/MOO3 lineage: simultaneous orders, auto-resolved mid-turn events, turn timers planned later.

### Package layout

- `rotp.mp.protocol` — `Protocol` (envelope/registry), `Messages` (lobby, orders, status), `PlayerView` (+ nested DTOs). No dependencies on model or UI.
- `rotp.mp.server` — `ServerMain` (headless bootstrap), `GameServer` (WebSocket lobby, command handling, turn driver), `PlayerViews` (DTO builder), `ServerUI` (headless `SessionUI`).
- `rotp.mp.client` — `NetClient` (WebSocket), `ClientMain` (with a Mac-style menu bar wiring the spec's ⌘-shortcuts, e.g. ⌘F Fleet List, ⌘D Ship Design, ⌘T Technology, ⌘N Next Turn), and DTO-rendered screens: `GalaxyViewPanel` (clickable galaxy map), `ColonyPanel` (colony management — spending sliders + which design the colony builds), `ResearchPanel` (research), `FleetsPanel` (fleets & transports), `ShipDesignPanel` (design list + create/scrap, populated from the `designCatalog` message) — each opening as its own window — plus pure helpers `ColonyAllocations` (spending/research redistribution), `FleetView` (fleet summarization/deployability), and `ShipDesigns` (free-slot computation). More screens added incrementally.
- `itest/rotp/mp` — JUnit integration tests + `MpTestSupport` harness. This is a separate `testSourceDirectory` because the main `sourceDirectory` is the whole `src` tree (so `src/test` would be built as main code) and `.gitignore` excludes any `test/` dir.

One fat jar, three modes: no args = classic desktop; `--server port= players= [size=tiny|small|medium|large|huge]`; `--client host= port= name=`.

## 3. The two key engine refactors

### 3.1 `SessionUI` seam (headless engine)

`GameSession`, `NoticeMessage`, and `Base.exception()` no longer call `RotPUI` directly. They call `SessionUI.get()` — an interface with no-op defaults (panel selection, repaint, notification delivery, error display). The desktop registers `RotPUI` (which implements `SessionUI`); the server registers `ServerUI`, which collects `TurnNotification`s instead of showing them. Supporting moves so headless code never class-loads the Swing UI: `scaledSize`/`unscaledSize` and the debug-file statics moved from `RotPUI` to `Rotp`; pending new-game options moved to `GameSession`; `Rotp`'s `GraphicsDevice` is lazily initialized; `Rotp.resizeAmt()` returns 1.0 when headless.

Server bootstrap must mirror the data-loading part of `RotPUI`'s static block (no sounds/images): `UserPreferences.load()`, `TechLibrary.current()`, then `LanguageManager.current().selectedLanguageName()` — the lazy `languages()` call performs the real label/race loading (`selectDefaultLanguage()` is a no-op trap).

### 3.2 Control-predicate split (`decidedByAI` vs `isAIControlled`)

Single-player ROTP conflates two questions in `isAIControlled()`. Multiplayer separates them:

- **`isAIControlled()`** — "should interactive prompts auto-resolve?" The server runs with autoplay enabled (`AUTOPLAY_AI_BASE`), so this is true for *every* empire server-side: ship combat auto-resolves, the scientist AI picks techs, the diplomat AI answers offers, council votes cast automatically. No code path ever tries to open UI.
- **`decidedByAI()`** = `isAIControlled() && !isRemoteHuman()` — "may the AI overwrite this empire's strategic orders?" After game creation the server flags each player's empire `remoteHuman` (a serializable `Empire` field, so it persists in saves). Decision-writing sites are gated on `decidedByAI()`: the two strategy blocks in `Empire.makeNextTurnDecisions()`, and `setColonyAllocations` / `suggestMissileBaseCount` in all three `AIGovernor` implementations (base, modnar, xilmi).

**Rule for contributors:** AI code that *decides for* an empire gates on `decidedByAI()`; code that *prompts a local human* gates on `isPlayerControlled()`/`isAIControlled()`.

**Known trap:** the governor's human branch calls `baseSetPlayerAllocations` (a full colony rebalance) when a colony `hasNewOrders()`, has unallocated ticks, or awaits an allocation advisory. For remote humans this now fires only on unallocated ticks (newly founded colonies) — and command handlers must never set `hasNewOrders(true)`, or wire orders get silently rewritten to AI patterns.

## 4. Protocol v1

| Type | Direction | Purpose |
|---|---|---|
| `hello` | C→S | Join: protocol version + player name |
| `lobby` | S→C | Player roster, on every change |
| `gameStarted` | S→C | Your empire id |
| `view` | S→C | `PlayerView` snapshot (start, post-turn, post-order) |
| `setColonyAlloc` | C→S | 5 categories, ticks sum to 50, locked honored |
| `setTechAlloc` | C→S | 6 categories, 0–60 ticks each |
| `deployFleet` | C→S | Orbiting fleet (whole or per-design counts) to in-range system |
| `sendTransports` | C→S | Population from own colony to colonized in-range system; max half the population |
| `abortTransports` | C→S | Cancel pending (unlaunched) transports at a colony |
| `colonize` | C→S | Settle the system an orbiting colony-ship fleet is at |
| `designCatalog` | C→S empty / S→C filled | Hulls + components available to this empire's tech; names are used verbatim in `createDesign`, index 0 of each list = "none"/basic |
| `createDesign` | C→S | New ship design in an empty slot; validated for hull space |
| `scrapDesign` | C→S | Scrap a slot: removes its ships from all fleets, refunds reserve; last remaining design cannot be scrapped |
| `setShipBuild` | C→S | Which design a colony builds + optional build limit |
| `setSpySpending` | C→S | Spy spending vs contacted empire, 0–20 ticks |
| `setSpyMission` | C→S | HIDE / ESPIONAGE / SABOTAGE vs contacted empire |
| `setSecurity` | C→S | Empire-wide internal security, 0–10 ticks |
| `diploOffer` | C→S | TRADE (level ≤ `maxTradeLevel`) / PEACE / PACT / ALLIANCE to contacted empire |
| `breakTreaty` | C→S | Unilaterally break TRADE / PACT / ALLIANCE |
| `declareWar` | C→S | Declare war; blocked while allied |
| `diploReply` | S→C | Target's verdict on an offer (accepted flag + dialogue text) |
| `cmdResult` | S→C | Accept/reject with reason |
| `notifications` | S→C | Per-empire events from the last turn (list of {category, text, systemId?, empireId?}) |
| `ready` | C→S | We-go ready flag |
| `turnStatus` | S→C | Ready counts, resolution progress |
| `error` | S→C | Version mismatch, game full, etc. |

`PlayerView` contains: turn/year, galaxy dimensions, own empire identity + internal security, contacted empires (with per-contact diplomatic status — war/pact/alliance/peace, trade level and max offerable level — and spy-network state), all systems (position always; name/type/owner once scouted), own-colony detail (allocations, locks, pop, factories, bases, production, shipyard design + build limit, pending transports), research state, own fleets, own in-flight transports, active design slots (name, hull size, total/available space, colony-ship flag).

Server-side command handling: rejected while a turn resolves; applied under a game lock; validated for ownership, bounds, range (`ShipFleet.canSendTo`, `Empire.canSendTransportsTo`), space (`ShipDesign.availableSpace`), and lock flags; successful orders return a fresh `PlayerView` to the sender.

### Client rendering: DTO-rendered screens (decided 2026-07-29)

The client renders each screen **from `PlayerView` and acts via commands** — it holds no game model. The rejected alternative was reusing ROTP's real Swing panels by shipping each client a fog-of-war-filtered *model snapshot*; that would reach full ROTP-fidelity UI faster but is a dead end for the project's stated goal (a browser client over a language-neutral API): a browser can run neither Swing nor a Java-serialized model, so that path produces nothing the browser can reuse and would have to be rebuilt from scratch. The DTO approach compounds toward the browser instead — porting a screen forces the protocol to carry exactly what that screen needs, so the Java client becomes a **reference client** that proves the API is sufficient before any JavaScript is written. The Swing paint code itself is throwaway either way; the reusable asset is the protocol + interaction design.

Consequence: rendering-independent logic that a browser client will also need (e.g. colony-spending redistribution, `ColonyAllocations`) is kept in small pure classes, separate from the Swing widgets, so it is unit-testable and serves as a portable spec.

### Notifications (per-empire, server-generated)

ROTP's built-in notifications cannot be reused for multiplayer: every one is written from the single local `player()`'s fog-of-war perspective (`player().sv.name(...)`, `player().knowsOf(...)`) and creation is gated on `isPlayerControlled()` across ~150 sites — which is false for *every* empire on the autoplay server, so almost nothing is generated and there is no empire attribution. Retrofitting all of that (much of it interactive-prompt code we deliberately auto-resolve) would be a very large refactor.

Instead the server **generates each empire's notifications itself** (`rotp.mp.server.NotificationCenter`), the same philosophy as `PlayerView`: it keeps a per-empire snapshot (owned systems, contacted empires, per-contact treaty flags) and diffs it after every turn, emitting a `notifications` message to that player before the fresh view. v1 covers the unambiguous, high-signal events — first contact, diplomatic status transitions (war/peace/pact/alliance onset and treaty breaks), and colonies gained or lost. Text is generated server-side (English for now); each item also carries `systemId`/`empireId` so a client can localize or link. Snapshots are seeded at game start so turn-1 state isn't reported as news. Extending to tech-completed, combat outcomes, spy reports, and GNN news is a matter of adding fields to the snapshot and cases to the diff. (One v1 limitation: an empire encountered *via* a simultaneous war declaration reports only `CONTACT`, not the war — the war is still visible in the view's `EmpireDto.atWar`.)

### Diplomacy semantics in v1

Player-initiated diplomacy reuses the engine's `Diplomat.receive*` entry points — the same calls the desktop diplomacy UI makes: the server invokes `target.diplomatAI().receiveOfferTrade/Peace/Pact/Alliance(sender)` (or `receiveBreak*` / `receiveDeclareWar`) and relays the `DiplomaticReply` as a `diploReply` message. This holds even when the target is another human (v1 AI-assist); Phase 3 replaces the human-target path with async offer delivery during the order phase. Incoming AI-initiated diplomacy (their offers to us) is auto-answered by our own diplomat AI for now and becomes visible with per-empire notification delivery.

### Colonization semantics in v1

Because the server runs with autoplay, the "colonize this planet?" prompt auto-resolves: **a colony ship arriving at a suitable, uncolonized system settles it automatically.** This is deliberate v1 AI-assist behavior, not a bug — expansion works with `deployFleet` alone. The explicit `colonize` command covers planets the assist declines (e.g. hostile environments the AI considers not worth settling) and becomes the primary path in Phase 3 when arrival prompts are routed to clients instead. Newly founded colonies start with unallocated spending, which is the one case where the governor assist still auto-balances a remote human's colony (see §3.2).

## 5. Concurrency model

Game state is mutated by exactly one thread at a time: command application and turn resolution both serialize on the server's `gameLock`; the turn itself runs on ROTP's existing single turn thread (`GameSession.nextTurn()`), with the server polling `performingTurn()` for completion. WebSocket callbacks do no model work outside the lock. Lobby/readiness bookkeeping synchronizes on the server object.

## 6. Verification approach

Committed JUnit 5 integration tests live under `itest/rotp/mp/` and run with **`mvn test`** (they are also the reason the pom sets `testSourceDirectory` to `itest` — see §2). Each test boots the real game engine headless in-process, stands up a `GameServer` on an ephemeral port, and drives it through a scripted WebSocket `Client` (shared infrastructure in `MpTestSupport`). They assert real behavior, not mocks:

- `OrderCommandsTest` — two players in one game: fog-of-war isolation (no foreign colony detail leaks), colony/tech/fleet orders, ownership + bounds rejection, we-go readiness (one-ready does not advance), and orders surviving AI-driven turn resolution.
- `ShipDesignTransportTest` — design catalog/create/scrap/set-build with space and slot validation; colonization; population transport schedule/abort/deliver.
- `SpyDiplomacyTest` — internal security, spy spending/missions, and diplomatic offers/war/peace answered by the target's diplomat AI. Also asserts per-empire notifications: first contact → `CONTACT`, declaring war → `DIPLOMACY`; `ShipDesignTransportTest` asserts colonization → `COLONY_GAINED`.
- `ColonyScreenTest` — the colony management screen without a display: spending redistribution keeps the total fixed and honours locks, the panel loads a `ColonyDto`, and the galaxy-map click hit-test selects the right system and opens it. (Swing components construct fine headless; only *showing* them needs a display.)
- `ResearchScreenTest` — the server hands a remote human a fully-allocated (sum 60) research split at start (it would otherwise be all-zero and unusable), redistribution keeps that total, and the panel loads a `TechDto`.
- `FleetsScreenTest` — fleet summarization by design name, deployability (orbiting + has ships), and the panel loading fleets/transports from a view.
- `ShipDesignScreenTest` — free-slot computation, the panel requesting the design catalog on first data, and loading the catalog + designs.

Positive paths that depend on galaxy geography (reaching a colonizable planet, making first contact) use JUnit *assumptions*, so an unlucky galaxy seed **skips** those assertions rather than failing — the always-true mechanics (validation, rejection, persistence) are hard assertions. Tests run on a **tiny galaxy** (`startServer` passes `SIZE_TINY`) so empires start close and first contact is reliable — a remote human's fleets don't auto-explore, so the tests drive scouting themselves (`MpTestSupport.explore`). The whole suite runs in ~20s. The engine's RNG is unseeded (`Base.random`); seeding it for fully deterministic tests is a known future improvement.

## 7. Phase plan and status

- **Phase 0 — walking skeleton: done.** Maven build, protocol core, headless server, minimal DTO-rendered client, verified end-to-end.
- **Phase 1 — playing the game: in progress.** Done: control split, we-go readiness, enriched `PlayerView`, the full player order set — colony/tech allocations, fleet deployment, ship design lifecycle (catalog/create/scrap/set-build), colonization, population transports, spy networks + internal security, and diplomacy (offers/treaty-breaking/war, answered by the target's diplomat AI); per-empire notification delivery (contact/diplomacy/colony events, server-generated); and **DTO-rendered client screens** — a clickable galaxy map, the colony-management screen (spending sliders → `setColonyAlloc`), the research screen (six category sliders → `setTechAllocations`), the fleets & transports screen (deploy fleets, send/abort transports), and and the ship design screen (design list + create/scrap from the `designCatalog`), all opened from a Mac-style menu bar wiring the spec's ⌘-shortcuts; the colony screen also chooses which design the colony builds (`setShipBuild`). All verified by committed JUnit integration tests. Remaining: an empire/status overview screen, and extending notification coverage (tech/combat/spy/GNN).
- **Phase 2 — LAN & session completeness:** confirm LAN play (server binds a LAN address; clients — Java now, browser later — connect from other machines on the network); a **lobby that can start with the humans present and fill the rest with AI** (solo host vs AI, configurable AI-opponent count); reconnection; multiplayer save/load; lobby race/color picks.
- **Phase 3 — optional interactivity:** remote prompts (tech/diplomacy/council) with turn timers; async player-to-player diplomacy in the order phase.
- **Phase 4 — internet hosting:** persistent lobby, auth, deployment.
- **Phase 5 — browser client** speaking the identical protocol (the DTO client screens are its blueprint), reproducing the **1990s Mac-port interaction feel and keyboard shortcuts** (see §1; source the specifics first — don't invent them).
