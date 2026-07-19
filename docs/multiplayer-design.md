# ROTP Online Multiplayer — Design Document

## 1. Goal and constraints

Add online multiplayer to Remnants of the Precursors (a Java/Swing remake of Master of Orion) such that:

- Games run locally and on LAN now, on internet-hosted servers later, with a browser client as the eventual end state.
- The wire protocol is the product: a language-neutral API between the game engine and any frontend (the RuneScape-style client/API split). The first client is the existing Java desktop app; nothing in the protocol may assume a Java client.
- Classic offline single-player must keep working unchanged throughout.

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
- `rotp.mp.client` — `NetClient` (WebSocket), `ClientMain` + `GalaxyViewPanel` (minimal DTO-rendered client; real screens ported later).

One fat jar, three modes: no args = classic desktop; `--server port= players=`; `--client host= port= name=`.

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
| `cmdResult` | S→C | Accept/reject with reason |
| `ready` | C→S | We-go ready flag |
| `turnStatus` | S→C | Ready counts, resolution progress |
| `error` | S→C | Version mismatch, game full, etc. |

`PlayerView` contains: turn/year, galaxy dimensions, own empire identity, contacted empires, all systems (position always; name/type/owner once scouted), own-colony detail (allocations, locks, pop, factories, bases, production, shipyard design + build limit, pending transports), research state, own fleets, own in-flight transports, active design slots (name, hull size, total/available space, colony-ship flag).

Server-side command handling: rejected while a turn resolves; applied under a game lock; validated for ownership, bounds, range (`ShipFleet.canSendTo`, `Empire.canSendTransportsTo`), space (`ShipDesign.availableSpace`), and lock flags; successful orders return a fresh `PlayerView` to the sender.

### Colonization semantics in v1

Because the server runs with autoplay, the "colonize this planet?" prompt auto-resolves: **a colony ship arriving at a suitable, uncolonized system settles it automatically.** This is deliberate v1 AI-assist behavior, not a bug — expansion works with `deployFleet` alone. The explicit `colonize` command covers planets the assist declines (e.g. hostile environments the AI considers not worth settling) and becomes the primary path in Phase 3 when arrival prompts are routed to clients instead. Newly founded colonies start with unallocated spending, which is the one case where the governor assist still auto-balances a remote human's colony (see §3.2).

## 5. Concurrency model

Game state is mutated by exactly one thread at a time: command application and turn resolution both serialize on the server's `gameLock`; the turn itself runs on ROTP's existing single turn thread (`GameSession.nextTurn()`), with the server polling `performingTurn()` for completion. WebSocket callbacks do no model work outside the lock. Lobby/readiness bookkeeping synchronizes on the server object.

## 6. Verification approach

Scripted protocol clients (no GUI) drive end-to-end tests against a real headless server: two players join, receive distinct fog-of-war views (no foreign colony detail leaks), issue orders, see hostile and malformed orders rejected, ready up, and confirm orders survive turn resolution and fleets move. Multi-turn soak tests check for headless UI leaks; a desktop launch check guards single-player regression. (These live as scratch scripts today; promoting them to committed integration tests is planned.)

## 7. Phase plan and status

- **Phase 0 — walking skeleton: done.** Maven build, protocol core, headless server, minimal DTO-rendered client, verified end-to-end.
- **Phase 1 — playing the game: in progress.** Done: control split, we-go readiness, enriched `PlayerView`, and the full order set for economy and expansion — colony/tech allocations, fleet deployment, ship design lifecycle (catalog/create/scrap/set-build), colonization, and population transports, all verified by scripted end-to-end tests (24-check design/transport suite plus the two-player order/isolation suite). Remaining: spy/diplomacy commands; per-empire notification delivery; porting the real Swing screens to consume `PlayerView` (largest work item).
- **Phase 2 — LAN completeness:** reconnection, multiplayer save/load, lobby race/color picks.
- **Phase 3 — optional interactivity:** remote prompts (tech/diplomacy/council) with turn timers; async player-to-player diplomacy in the order phase.
- **Phase 4 — internet hosting:** persistent lobby, auth, deployment.
- **Phase 5 — browser client** speaking the identical protocol.
