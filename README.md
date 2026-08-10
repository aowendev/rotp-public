# Remnants of the Precursors

Remnants of the Precursors is a Java-based modernization of the original Master of Orion game from 1993.

# Links
Official website: https://www.remnantsoftheprecursors.com/<br/>
Community subreddit: https://www.reddit.com/r/rotp/<br/>
Download build: https://rayfowler.itch.io/remnants-of-the-precursors

# Online Multiplayer (this fork)

This fork adds online multiplayer to ROTP, developed on the `multiplayer` branch. The design splits the game into a headless, authoritative **server** that owns all game logic, and **clients** that speak a JSON-over-WebSocket protocol. The first client is the existing Java desktop app; because the protocol is language-neutral, it can eventually be replaced by a browser client (the RuneScape-style client/API split).

Full design in [`docs/multiplayer-design.md`](docs/multiplayer-design.md); if you're picking the work back up, start with the [handoff note](docs/multiplayer-handoff.md).

## Architecture

- **Server-authoritative**: the server runs the real `GameSession`/`Galaxy` model. Clients never hold trusted state.
- **JSON + WebSocket wire protocol** (`rotp.mp.protocol`): messages use a `{"t": <type>, "d": <payload>}` envelope. Game state is sent as per-player `PlayerView` documents built from each empire's fog-of-war data (`SystemInfo`/`EmpireView`), so a client only ever receives what its empire legitimately knows.
- **We-go turns**: all players issue orders simultaneously; the server resolves the turn when everyone is ready. Mid-turn decisions reach the player as **prompts** — which technology to research or to steal, an incoming treaty or joint-war offer, a council vote, whether to colonise or bombard, what to sabotage — each self-contained and each safely ignorable, since the server falls back to that empire's AI default. **Ship combat is the deliberate exception and auto-resolves**: an interactive battle is a multi-round screen inside a simultaneous turn, so every other player would sit and wait. The decisions *around* a battle are still the player's.
- **`SessionUI` seam** (`rotp.model.game.SessionUI`): game-session and turn processing no longer call the Swing UI directly. The desktop game registers `RotPUI` as the implementation; the server registers a headless one. This is what lets the unmodified game engine run on a server with no display.
- **Two control predicates on `Empire`** (the key multiplayer refactor): `isAIControlled()` still answers "should interactive prompts auto-resolve?" — true for every empire on the server, so combat, tech picks, and diplomacy never try to open UI. The new `decidedByAI()` answers "may the AI overwrite this empire's strategic orders?" — false for empires flagged `remoteHuman`, so wire orders survive turn resolution. Anyone adding AI decision code must gate it on `decidedByAI()`, not `isAIControlled()`.

## Building

Requires JDK 17+ and Maven:

```
mvn compile          # compile
mvn test             # run the multiplayer integration tests (headless)
mvn package          # two jars in target/: the full one (~969MB, embeds all game
                     # assets) and rotp-client.jar (~3MB, multiplayer client only)
```

The tests (`itest/rotp/mp/`) boot a real headless server in-process and drive it over the wire; see the design doc's Verification section. On a loaded machine a single `mvn test` can wedge — run one class per invocation if it does, and watch the **skip** count as well as failures (see the test-env note in the handoff doc).

**The wire protocol is an open standard.** [`docs/protocol.md`](docs/protocol.md) and [`docs/protocol-implementers-guide.md`](docs/protocol-implementers-guide.md) are released under **CC0** — not the GPL covering the rest of this repository — with an explicit implementation grant. Anyone may implement the protocol in any language, in software under any licence, open or proprietary, with no obligation to this project.

For development, run from the compiled classes instead of repackaging:

```
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" rotp.Rotp <mode args>
```

## Running

One jar, three modes:

```
java -jar target/rotp-*.jar                                  # classic offline single-player (unchanged)
java -jar target/rotp-*.jar --server port=8777 players=2     # headless multiplayer server
java -jar target/rotp-client.jar --client host=localhost port=8777 name=Alice
java -jar target/rotp-client.jar --client url=wss://host/game/1 name=Alice   # hosted game
```

`players=` is the human capacity: the game auto-starts once that many join. The **first client to join is the host** and can start the game earlier from the lobby, choosing how many AI opponents to add — so a lone player can start a game against AI (solo-vs-AI on LAN). For LAN play, clients use the host machine's address; for internet play, the same server can run on any reachable machine.

## Protocol (v1)

Messages are JSON over WebSocket in a `{"t": <type>, "d": <payload>}` envelope; types are registered in `rotp.mp.protocol.Protocol`. Current vocabulary:

| Type | Direction | Purpose |
|---|---|---|
| `hello` | client → server | Join with protocol version + player name |
| `joined` | server → client | Acknowledges the join: the client's empire id and whether it's the host |
| `raceOptions` | server → client | The selectable races (id, name, trait), sent once on join |
| `pickRace` | client → server | Pick a race in the lobby; rejected if another player already holds it |
| `lobby` | server → client | Roster of joined players (name + chosen race per slot), sent on every change |
| `startGame` | client → server | Host starts the game with the humans present, filling the rest with AI (picks the AI-opponent count) |
| `gameStarted` | server → client | Game created; tells the client its empire id |
| `gameOver` | server → client | The game ended for this empire: won/lost plus a reason (military, diplomatic, no-colonies, defeated, …) |
| `view` | server → client | `PlayerView`: everything this empire knows — systems (fog-of-war), own colonies (spending, pop, factories, bases, production, pending transports, build choice), research state, fleets, in-flight transports, ship design slots (hull, space, colony-ship flag). Sent on game start, after every turn, and after each accepted order |
| `setColonyAlloc` | client → server | Colony spending: 5 categories (ship/def/ind/eco/tech), ticks summing to 50, locked categories honored |
| `setTechAlloc` | client → server | Research allocation: 6 categories, 0–60 ticks each |
| `setResearchChoice` | client → server | Choose which tech a research category works toward (from its available choices), overriding the AI's default |
| `deployFleet` | client → server | Send an orbiting fleet (whole, or per-design counts) to a system in range |
| `sendTransports` | client → server | Send population from a colony to a colonized system in range (max half the population) |
| `abortTransports` | client → server | Cancel pending (unlaunched) transports at a colony |
| `colonize` | client → server | Settle the system an orbiting colony-ship fleet is at (note: in v1, suitable planets also auto-colonize on arrival via the AI-assist prompt resolution) |
| `designCatalog` | client → server (empty), server → client (filled) | Available hulls and components for this empire's researched tech |
| `createDesign` | client → server | Create a ship design in an empty slot from catalog component names; validated for hull space |
| `scrapDesign` | client → server | Scrap a design slot (removes its ships everywhere, refunds reserve) |
| `setShipBuild` | client → server | Choose which design a colony builds, with optional build limit |
| `setSpySpending` / `setSpyMission` | client → server | Spy network vs a contacted empire: spending ticks and HIDE/ESPIONAGE/SABOTAGE |
| `setSecurity` | client → server | Empire-wide internal security ticks |
| `diploOffer` | client → server | Offer TRADE (with level), PEACE, PACT, or ALLIANCE to a contacted empire; the target's diplomat AI answers via `diploReply` |
| `breakTreaty` | client → server | Unilaterally break TRADE, PACT, or ALLIANCE |
| `declareWar` | client → server | Declare war (requires breaking an alliance first) |
| `diploReply` | server → client | The target's verdict on a diplomatic offer, with dialogue text |
| `notifications` | server → client | Per-empire events from the last turn (first contact, diplomatic changes, colonies gained/lost), generated server-side |
| `cmdResult` | server → client | Accept/reject for an order, with reason ("Not your colony", "Destination out of range", …) |
| `ready` | client → server | We-go ready flag; the turn resolves when all players are ready |
| `turnStatus` | server → client | Ready counts and turn-resolution progress |
| `error` | server → client | Connection-level errors (version mismatch, game full) |

All orders are validated server-side against the sending player's empire; clients are untrusted.

## Status

**Phase 0 — walking skeleton (done):**
- Maven build compiling the unmodified game on modern JDKs; offline single-player still works.
- Protocol core: message envelope, lobby messages, first `PlayerView` slice (known systems, contacted empires, turn/year).
- Headless server: WebSocket lobby, real galaxy generation, full turn resolution with no display (all empires AI-driven via autoplay for now).
- Minimal client: joins a lobby, renders the galaxy map purely from `PlayerView` JSON, and can advance the turn.
- Verified end-to-end: two clients with distinct fog-of-war views, 15-turn headless soak, desktop regression.

**Phase 1 — playing the game (done):**
- Done: per-empire human control — players' empires are flagged `remoteHuman`, so the AI auto-resolves their mid-turn prompts but never overwrites their strategic orders (`Empire.decidedByAI()`).
- Done: first commands with server-side ownership validation — colony spending allocation, research allocation, fleet deployment — plus we-go ready flags (turn resolves when all players are ready; a disconnect can't block the turn).
- Done: `PlayerView` carries own-colony detail, research state, fleets, and ship design slots; every accepted order returns a fresh view.
- Verified by a scripted two-player test: hostile/invalid orders rejected, orders survive turn resolution, deployed fleets move.
- Done: the full expansion loop over the wire — ship design (catalog/create/scrap/set-build, space-validated), colonization (auto on arrival per v1 AI-assist, plus an explicit `colonize` command), and population transports (send/abort, delivery verified end-to-end).
- Done: spy and diplomacy commands — spy spending/missions and internal security; diplomatic offers (trade/peace/pact/alliance) answered by the target's diplomat AI, treaty breaking, war declarations; contact status (treaties, trade levels, spy networks) in `PlayerView`.
- Done: research selection — the scientist AI picks a sensible default for remote humans (research never stalls), and `setResearchChoice` lets the player override each category's target from its available techs, as a non-blocking order.
- Done: per-empire notification delivery — the server generates each player's events (first contact, diplomatic changes, colonies gained/lost, technologies researched) itself, since ROTP's built-in notifications are single-player-only.
- Done: the core-playable set of DTO-rendered client screens — a clickable galaxy map (click to set a fleet destination), colony management (spending sliders → `setColonyAlloc`, plus choosing which design the colony builds → `setShipBuild`), research (allocation sliders → `setTechAllocations` + per-category research-target choice), fleets & transports (deploy fleets, send/abort transports), ship design (create/scrap from the design catalog), and a read-only empire overview (colonies, totals, contact/diplomacy) — all opened from a Mac-style menu bar wiring the Mac-port ⌘-shortcuts (⌘P Planet List, ⌘F Fleet List, ⌘D Ship Design, ⌘T Technology, ⌘N Next Turn). The full economy→build→expand loop is clickable end-to-end; a game is genuinely playable over the wire. The client renders from `PlayerView` and acts via commands, holding no game model, so it doubles as the blueprint for the eventual browser client (see the design doc's "Client rendering" note).
- Deferred polish (not blockers): per-design partial fleet deploys, and broader notification coverage (combat/spy/GNN).

**Phase 1.5 — human-validated full playthrough (current focus):**
- Done: lobby race selection — each player is defaulted to a distinct race on join and can pick any free one (`raceOptions`/`pickRace`); duplicates are refused, and each human's pick lands on their own empire when the game starts. The homeworld name follows the race automatically (the galaxy factory names it from the race), so this fixes homeworld naming too.
- Done: victory/defeat signaling — after each turn the server checks the engine's win/loss state and sends a `gameOver` message (won/lost + reason), so the client shows the outcome and stops resolving turns. The engine evaluates victory from the player's (empire 0's) perspective, which is authoritative for the solo game; any human's defeat is also detected per-empire via extinction. (Per-empire victory for multi-human games is a deeper engine change, deferred.)
- Done: usable fleet dispatch — Colony/System/Fleets docked as tabs; click a star to target it; a per-ship-type picker sends scouts/colony ships individually from the shared home stack; a **System** info tab reports planet type, capacity, ownership, and whether you can colonize; and ship range is shown three ways (map tint for out-of-range stars, a System-tab range line, and a live out-of-range warning in the dispatch panel that names the ship type). Colony ships auto-settle on arrival.
- Also fixed while validating: a turn-advance lockup (the Next Turn button stuck disabled after turn 1), ROTP's turn-1 scout auto-launch (recalled so you control the opening move), and a stale homeworld/leader name after race selection.
- Remaining: one human sign-off playthrough to final win/loss.

**Phase 2 — LAN & session completeness (in progress):**
- Done: a lobby that starts with the humans present and fills the rest with AI — the host chooses the AI-opponent count (so a lone player can play against AI on a LAN); only the host may start.
- Remaining: confirm LAN play across machines; reconnection; multiplayer save/load; lobby color picks (race picks done in Phase 1.5).

**Roadmap:**
1. **Phase 1.5 — human-validated full playthrough (current focus)**: a person plays a complete solo game (start → win/loss) in the reference client; fix every gap that blocks completion, API-completeness-first (race/color selection, homeworld naming, usable fleet dispatch, victory/defeat signaling), keeping the Java UI minimal — real UX is the browser client's job.
2. **Phase 2 — full we-go multiplayer on LAN**: (lobby AI-fill done) reconnection; saves/loads of multiplayer games; lobby polish (race/color picks).
2. **Phase 3 — optional interactivity**: remote prompts for tech choices/diplomacy/council votes with turn timers; async player-to-player diplomacy.
3. **Phase 4 — internet hosting**: persistent lobby, authentication, server deployment.
4. **Phase 5 — browser client** speaking the same protocol, reproducing the 1990s Macintosh port's interface feel and keyboard shortcuts.
