# Remnants of the Precursors

Remnants of the Precursors is a Java-based modernization of the original Master of Orion game from 1993.

# Links
Official website: https://www.remnantsoftheprecursors.com/<br/>
Community subreddit: https://www.reddit.com/r/rotp/<br/>
Download build: https://rayfowler.itch.io/remnants-of-the-precursors

# Online Multiplayer (this fork)

This fork adds online multiplayer to ROTP, developed on the `multiplayer` branch. The design splits the game into a headless, authoritative **server** that owns all game logic, and **clients** that speak a JSON-over-WebSocket protocol. The first client is the existing Java desktop app; because the protocol is language-neutral, it can eventually be replaced by a browser client (the RuneScape-style client/API split).

## Architecture

- **Server-authoritative**: the server runs the real `GameSession`/`Galaxy` model. Clients never hold trusted state.
- **JSON + WebSocket wire protocol** (`rotp.mp.protocol`): messages use a `{"t": <type>, "d": <payload>}` envelope. Game state is sent as per-player `PlayerView` documents built from each empire's fog-of-war data (`SystemInfo`/`EmpireView`), so a client only ever receives what its empire legitimately knows.
- **We-go turns**: all players issue orders simultaneously; the server resolves the turn when everyone is ready. Mid-turn interactive events (combat tactics, tech choices, incoming diplomacy, council votes) are auto-resolved by each empire's own AI — the approach the MOO2/MOO3 multiplayer community converged on — with optional interactivity planned for a later phase. Player-to-player diplomacy will happen asynchronously during the order phase.
- **`SessionUI` seam** (`rotp.model.game.SessionUI`): game-session and turn processing no longer call the Swing UI directly. The desktop game registers `RotPUI` as the implementation; the server registers a headless one. This is what lets the unmodified game engine run on a server with no display.

## Building

Requires JDK 17+ and Maven:

```
mvn compile          # compile
mvn package          # fat jar in target/ (large: embeds all game assets)
```

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

## Status

**Phase 0 — walking skeleton (done):**
- Maven build compiling the unmodified game on modern JDKs; offline single-player still works.
- Protocol core: message envelope, lobby messages, first `PlayerView` slice (known systems, contacted empires, turn/year).
- Headless server: WebSocket lobby, real galaxy generation, full turn resolution with no display (all empires AI-driven via autoplay for now).
- Minimal client: joins a lobby, renders the galaxy map purely from `PlayerView` JSON, and can advance the turn.
- Verified end-to-end: two clients with distinct fog-of-war views, 15-turn headless soak, desktop regression.

**Roadmap:**
1. **Phase 1 — playing the game**: JSON command layer (colony spending, fleet orders, transports, tech allocation, ship design, spying, diplomacy actions) with server-side ownership validation; human empires stop being AI-decided; core game screens ported to the protocol; per-empire notification delivery.
2. **Phase 2 — full we-go multiplayer on LAN**: ready flags, reconnection, saves/loads of multiplayer games.
3. **Phase 3 — optional interactivity**: remote prompts for tech choices/diplomacy/council votes with turn timers; async player-to-player diplomacy.
4. **Phase 4 — internet hosting**: persistent lobby, authentication, server deployment.
5. **Phase 5 — browser client** speaking the same protocol.
