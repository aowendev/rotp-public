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
- **We-go turns**: all players issue orders simultaneously; the server resolves the turn when everyone is ready. Mid-turn interactive events (combat tactics, tech choices, incoming diplomacy, council votes) are auto-resolved by each empire's own AI — the approach the MOO2/MOO3 multiplayer community converged on — with optional interactivity planned for a later phase. Player-to-player diplomacy will happen asynchronously during the order phase.
- **`SessionUI` seam** (`rotp.model.game.SessionUI`): game-session and turn processing no longer call the Swing UI directly. The desktop game registers `RotPUI` as the implementation; the server registers a headless one. This is what lets the unmodified game engine run on a server with no display.
- **Two control predicates on `Empire`** (the key multiplayer refactor): `isAIControlled()` still answers "should interactive prompts auto-resolve?" — true for every empire on the server, so combat, tech picks, and diplomacy never try to open UI. The new `decidedByAI()` answers "may the AI overwrite this empire's strategic orders?" — false for empires flagged `remoteHuman`, so wire orders survive turn resolution. Anyone adding AI decision code must gate it on `decidedByAI()`, not `isAIControlled()`.

## Building

Requires JDK 17+ and Maven:

```
mvn compile          # compile
mvn test             # run the multiplayer integration tests (~30s, headless)
mvn package          # fat jar in target/ (large: embeds all game assets)
```

The tests (`itest/rotp/mp/`) boot a real headless server in-process and drive it over the wire; see the design doc's Verification section.

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
java -jar target/rotp-*.jar --client host=localhost port=8777 name=Alice
```

The server starts a game as soon as `players` clients have joined. For LAN play, clients use the host machine's address; for internet play, the same server can run on any reachable machine.

## Protocol (v1)

Messages are JSON over WebSocket in a `{"t": <type>, "d": <payload>}` envelope; types are registered in `rotp.mp.protocol.Protocol`. Current vocabulary:

| Type | Direction | Purpose |
|---|---|---|
| `hello` | client → server | Join with protocol version + player name |
| `lobby` | server → client | Roster of joined players, sent on every change |
| `gameStarted` | server → client | Game created; tells the client its empire id |
| `view` | server → client | `PlayerView`: everything this empire knows — systems (fog-of-war), own colonies (spending, pop, factories, bases, production, pending transports, build choice), research state, fleets, in-flight transports, ship design slots (hull, space, colony-ship flag). Sent on game start, after every turn, and after each accepted order |
| `setColonyAlloc` | client → server | Colony spending: 5 categories (ship/def/ind/eco/tech), ticks summing to 50, locked categories honored |
| `setTechAlloc` | client → server | Research allocation: 6 categories, 0–60 ticks each |
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

**Phase 1 — playing the game (in progress):**
- Done: per-empire human control — players' empires are flagged `remoteHuman`, so the AI auto-resolves their mid-turn prompts but never overwrites their strategic orders (`Empire.decidedByAI()`).
- Done: first commands with server-side ownership validation — colony spending allocation, research allocation, fleet deployment — plus we-go ready flags (turn resolves when all players are ready; a disconnect can't block the turn).
- Done: `PlayerView` carries own-colony detail, research state, fleets, and ship design slots; every accepted order returns a fresh view.
- Verified by a scripted two-player test: hostile/invalid orders rejected, orders survive turn resolution, deployed fleets move.
- Done: the full expansion loop over the wire — ship design (catalog/create/scrap/set-build, space-validated), colonization (auto on arrival per v1 AI-assist, plus an explicit `colonize` command), and population transports (send/abort, delivery verified end-to-end).
- Done: spy and diplomacy commands — spy spending/missions and internal security; diplomatic offers (trade/peace/pact/alliance) answered by the target's diplomat AI, treaty breaking, war declarations; contact status (treaties, trade levels, spy networks) in `PlayerView`.
- Done: per-empire notification delivery — the server generates each player's events (first contact, diplomatic changes, colonies gained/lost) itself, since ROTP's built-in notifications are single-player-only.
- Done: DTO-rendered client screens — a clickable galaxy map, a colony-management screen (spending sliders → `setColonyAlloc`), a research screen (six category sliders → `setTechAllocations`), a fleets & transports screen (deploy fleets, send/abort transports), and a ship design screen (design list + create/scrap from the design catalog), all opened from a Mac-style menu bar wiring the Mac-port ⌘-shortcuts (⌘F Fleet List, ⌘D Ship Design, ⌘T Technology, ⌘N Next Turn). The client renders from `PlayerView` and acts via commands, holding no game model, so it doubles as the blueprint for the eventual browser client (see the design doc's "Client rendering" note).
- Remaining: an empire/status overview screen; surfacing "which design a colony builds" in the colony screen; extending notification coverage (tech, combat, spy reports, GNN).

**Roadmap:**
1. **Phase 2 — full we-go multiplayer on LAN**: a lobby that can start with the humans present and fill the rest with AI (solo host vs AI, configurable AI count); reconnection; saves/loads of multiplayer games; lobby polish (race/color picks).
2. **Phase 3 — optional interactivity**: remote prompts for tech choices/diplomacy/council votes with turn timers; async player-to-player diplomacy.
3. **Phase 4 — internet hosting**: persistent lobby, authentication, server deployment.
4. **Phase 5 — browser client** speaking the same protocol, reproducing the 1990s Macintosh port's interface feel and keyboard shortcuts.
