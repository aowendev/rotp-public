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
- `rotp.mp.client` — `NetClient` (WebSocket), `ClientMain` (with a Mac-style menu bar wiring the spec's ⌘-shortcuts, e.g. ⌘F Fleet List, ⌘D Ship Design, ⌘T Technology, ⌘N Next Turn), and DTO-rendered screens: `GalaxyViewPanel` (clickable galaxy map), `ColonyPanel` (colony management — spending sliders + which design the colony builds), `ResearchPanel` (research — allocation sliders + per-category research-target choice), `FleetsPanel` (fleets & transports), `ShipDesignPanel` (design list + create/scrap, populated from the `designCatalog` message), `EmpirePanel` (read-only empire overview — colonies table, totals, contact/diplomacy) — each opening as its own window — plus pure helpers `ColonyAllocations` (spending/research redistribution), `FleetView` (fleet summarization/deployability), `ShipDesigns` (free-slot computation), and `EmpireStats` (rollups + relation summary). This covers the core-playable screen set.
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
| `joined` | S→C | Ack a join: your empire id + whether you are the host |
| `raceOptions` | S→C | Selectable races (id, name, trait), sent once on join |
| `pickRace` | C→S | Pick a lobby race; refused if another player already holds it |
| `lobby` | S→C | Player roster (name + chosen race per slot), on every change |
| `startGame` | C→S | Host starts now with the humans present, filling the rest with AI (chooses the AI-opponent count) |
| `gameStarted` | S→C | Your empire id |
| `gameOver` | S→C | Game ended for this empire: won/lost + reason |
| `view` | S→C | `PlayerView` snapshot (start, post-turn, post-order) |
| `setColonyAlloc` | C→S | 5 categories, ticks sum to 50, locked honored |
| `setTechAlloc` | C→S | 6 categories, 0–60 ticks each |
| `setResearchChoice` | C→S | Choose which tech a research category works toward (one of that category's available choices), overriding the AI's default pick |
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

Instead the server **generates each empire's notifications itself** (`rotp.mp.server.NotificationCenter`), the same philosophy as `PlayerView`: it keeps a per-empire snapshot (owned systems, contacted empires, per-contact treaty flags) and diffs it after every turn, emitting a `notifications` message to that player before the fresh view. It covers the unambiguous, high-signal events — first contact, diplomatic status transitions (war/peace/pact/alliance onset and treaty breaks), colonies gained or lost, and technologies researched (diffing `Empire.tech().allKnownTechs()`). Text is generated server-side (English for now); each item also carries `systemId`/`empireId` so a client can localize or link. Snapshots are seeded at game start so turn-1 state isn't reported as news. Extending to combat outcomes, spy reports, and GNN news is a matter of adding fields to the snapshot and cases to the diff. (One v1 limitation: an empire encountered *via* a simultaneous war declaration reports only `CONTACT`, not the war — the war is still visible in the view's `EmpireDto.atWar`.)

### Diplomacy semantics in v1

Player-initiated diplomacy reuses the engine's `Diplomat.receive*` entry points — the same calls the desktop diplomacy UI makes: the server invokes `target.diplomatAI().receiveOfferTrade/Peace/Pact/Alliance(sender)` (or `receiveBreak*` / `receiveDeclareWar`) and relays the `DiplomaticReply` as a `diploReply` message. This holds even when the target is another human (v1 AI-assist); Phase 3 replaces the human-target path with async offer delivery during the order phase. Incoming AI-initiated diplomacy (their offers to us) is auto-answered by our own diplomat AI for now and becomes visible with per-empire notification delivery.

### Colonization semantics in v1

Because the server runs with autoplay, the "colonize this planet?" prompt auto-resolves: **a colony ship arriving at a suitable, uncolonized system settles it automatically.** This is deliberate v1 AI-assist behavior, not a bug — expansion works with `deployFleet` alone. The explicit `colonize` command covers planets the assist declines (e.g. hostile environments the AI considers not worth settling) and becomes the primary path in Phase 3 when arrival prompts are routed to clients instead. Newly founded colonies start with unallocated spending, which is the one case where the governor assist still auto-balances a remote human's colony (see §3.2).

### Research selection in v1

When a research category needs a tech to work toward, ROTP calls the empire's *scientist AI* to pick one (`TechCategory.setTechToResearch`), and this is **not** gated on `decidedByAI` — so a remote human's research always has a sensible default and never stalls. On top of that default, the player is given the choice as a **non-blocking order**: `PlayerView.TechDto` carries each category's available techs, and `setResearchChoice` lets the player override the current target at any time (validated against `techIdsAvailableForResearch()`). This fits the we-go model — choosing research is a persistent setting like allocation, not a mid-turn interrupt. (A stricter "always wait for the human, never auto-pick" mode is possible later by gating `setTechToResearch` on `!isRemoteHuman()` and banking RP until they choose.)

## 5. Concurrency model

Game state is mutated by exactly one thread at a time: command application and turn resolution both serialize on the server's `gameLock`; the turn itself runs on ROTP's existing single turn thread (`GameSession.nextTurn()`), with the server polling `performingTurn()` for completion. WebSocket callbacks do no model work outside the lock. Lobby/readiness bookkeeping synchronizes on the server object.

## 6. Verification approach

Committed JUnit 5 integration tests live under `itest/rotp/mp/` and run with **`mvn test`** (they are also the reason the pom sets `testSourceDirectory` to `itest` — see §2). Each test boots the real game engine headless in-process, stands up a `GameServer` on an ephemeral port, and drives it through a scripted WebSocket `Client` (shared infrastructure in `MpTestSupport`). They assert real behavior, not mocks:

- `OrderCommandsTest` — two players in one game: fog-of-war isolation (no foreign colony detail leaks), colony/tech/fleet orders, ownership + bounds rejection, we-go readiness (one-ready does not advance), and orders surviving AI-driven turn resolution.
- `ShipDesignTransportTest` — design catalog/create/scrap/set-build with space and slot validation; colonization; population transport schedule/abort/deliver.
- `SpyDiplomacyTest` — internal security, spy spending/missions, and diplomatic offers/war/peace answered by the target's diplomat AI. Also asserts per-empire notifications: first contact → `CONTACT`, declaring war → `DIPLOMACY`; `ShipDesignTransportTest` asserts colonization → `COLONY_GAINED`.
- `ColonyScreenTest` — the colony management screen without a display: spending redistribution keeps the total fixed and honours locks, the panel loads a `ColonyDto`, and the galaxy-map click hit-test selects the right system and opens it. (Swing components construct fine headless; only *showing* them needs a display.)
- `ResearchScreenTest` — the server hands a remote human a fully-allocated (sum 60) research split at start (it would otherwise be all-zero and unusable), redistribution keeps that total, the panel loads a `TechDto`, the player can override the research target (`setResearchChoice`), and completing research produces a `TECH` notification.
- `FleetsScreenTest` — fleet summarization by design name, deployability (orbiting + has ships), and the panel loading fleets/transports from a view.
- `ShipDesignScreenTest` — free-slot computation, the panel requesting the design catalog on first data, and loading the catalog + designs.
- `EmpireOverviewTest` — colony/fleet counts, production total, the diplomatic-relation summary, and the overview panel loading a view.
- `LobbyStartTest` — a lone host starts a game against AI (1 human + N AI empires), and only the host may start (non-host attempts are refused).

(Test harness note: `MpTestSupport.startServer` waits until the server's port is actually listening before returning, and each client connects exactly once — a `WebSocketClient` can't be reconnected, so the old connect-retry-on-the-same-object loop was replaced.)

Positive paths that depend on galaxy geography (reaching a colonizable planet, making first contact) use JUnit *assumptions*, so an unlucky galaxy seed **skips** those assertions rather than failing — the always-true mechanics (validation, rejection, persistence) are hard assertions. Tests run on a **tiny galaxy** (`startServer` passes `SIZE_TINY`) so empires start close and first contact is reliable — a remote human's fleets don't auto-explore, so the tests drive scouting themselves (`MpTestSupport.explore`). The whole suite runs in ~20s. The engine's RNG is unseeded (`Base.random`); seeding it for fully deterministic tests is a known future improvement.

## 7. Phase plan and status

- **Phase 0 — walking skeleton: done.** Maven build, protocol core, headless server, minimal DTO-rendered client, verified end-to-end.
- **Phase 1 — playing the game: DONE.** Control split, we-go readiness, enriched `PlayerView`, and the full player order set — colony/tech allocations, **research selection** (`setResearchChoice`), fleet deployment, ship design lifecycle (catalog/create/scrap/set-build), colonization, population transports, spy networks + internal security, and diplomacy (offers/treaty-breaking/war, answered by the target's diplomat AI). Per-empire notification delivery (contact/diplomacy/colony/tech events, server-generated). And the full **core-playable client screen set** — clickable galaxy map (with map-click fleet destinations), colony management (`setColonyAlloc` + `setShipBuild`), research (allocation + per-category research-target choice), fleets & transports (deploy, send/abort), ship design (create/scrap from the `designCatalog`), and a read-only empire overview — each opening as a window from a Mac-style menu bar wiring the Mac-port ⌘-shortcuts. The full economy→build→expand loop is clickable end-to-end; a game is genuinely playable over the wire with no game model on the client. All verified by 28 committed JUnit integration tests. *Deferred polish (not blockers): per-design partial fleet deploys, and broader notification coverage (combat/spy/GNN) — good early Phase-3 companions since those are event-based.*
- **Phase 1.5 — human-validated full playthrough: NEXT (current focus).** Prove, by a person actually playing, that a **complete solo game can be played start → win/loss** in the Java reference client, and fix every gap that *blocks completion*. **API-completeness-first, not UI-polish**: the API must support every action a full playthrough needs, and the Java client needs *just enough* UI to reach them (functional, not pretty — real UX stays deferred to the browser client; Swing polish is throwaway). A playthrough must exercise: **pick a race** (+ color; homeworld named by the race) → explore & **colonize** (usable dispatch affordance) → research incl. **choosing** what to research → **design + build** ships (and see them built) → move fleets & see a **combat** outcome → some **diplomacy** → **observe victory/defeat**. Human = validator; the model fixes what they hit. Known backlog: (1) **race/color selection** [API + server + minimal lobby UI — reusable by the browser], (2) **homeworld name from race** [server, with #1], (3) **fleet dispatch / colonize affordance** [mostly UI; API already supports], (4) **victory/defeat signaling to clients** [likely an API gap — server sets a game-over flag but doesn't send it], (5) scout-exploration convenience [UI]. Caveat: council votes and incoming AI diplomacy are auto-resolved (Phase 3), so "full playthrough" = *can win/lose a game*, not *every interactive prompt restored*.
- **Phase 2 — LAN & session completeness: in progress.** Done: the **lobby can start with the humans present and fill the rest with AI** — `players=` is the human capacity (auto-starts when full, ruleset AI count); the first player is the host and may `startGame` early, choosing the AI-opponent count (total empires = humans present + AI, clamped to the galaxy's `maximumOpponentsOptions()`). This realizes the solo-vs-AI-on-LAN requirement. Remaining: confirm LAN play across machines; reconnection; multiplayer save/load; lobby race/color picks.
- **Phase 3 — optional interactivity:** remote prompts (tech/diplomacy/council) with turn timers; async player-to-player diplomacy in the order phase.
- **Phase 4 — internet hosting:** persistent lobby, auth, deployment.
- **Phase 5 — browser client** speaking the identical protocol (the DTO client screens are its blueprint), reproducing the **1990s Mac-port interaction feel and keyboard shortcuts** (see §1; source the specifics first — don't invent them).
