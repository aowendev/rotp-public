# Human-vs-human test scenarios

A checklist for two machines and two people. Everything here is either **only
provable with two real humans** or **implemented but never exercised outside an
in-process test** — the routine single-player paths are covered by the 100+
integration tests and are not repeated.

Ordered by risk: the top sections are where a bug would be most damaging and
least likely to have been caught.

**Setup.** On the server machine:

```bash
mvn package -DskipTests
ipconfig getifaddr en0        # the server's LAN address
java -Xmx384m -jar target/rotp-1.04-mp-SNAPSHOT.jar --server port=8777 players=2 size=small
```

Copy `target/rotp-client.jar` (3MB) to the second machine, then on each:

```bash
java -jar rotp-client.jar --client host=<server-ip> port=8777 name=Alice   # and Bob
```

The game auto-starts once both slots fill. **Alice is empire 0, Bob is empire 1** —
that distinction matters for several tests below, because the engine's single
game-status is written from empire 0's point of view.

Watch the server's stdout throughout; it logs joins, disconnects, turn completion
and game-over.

---

## 1. Elimination and victory — highest risk

Only in-process tests cover these, and they exist because the engine could not
express them at all: its `GameStatus` only ever describes empire 0.

- [ ] **1.1 Bob (empire 1) is destroyed.** Bob gets a defeat message naming his
      own outcome — *not* a neutral "the game has ended". Alice keeps playing.
- [ ] **1.2 Alice (empire 0) is destroyed, Bob plays on.** The important one.
      Alice gets defeat; **Bob's turns keep advancing**. Before the fix the engine
      stopped processing turns entirely here — the turn counter silently froze for
      everyone — because empire 0's death set the global status out of
      `IN_PROGRESS`. If Bob's turn number stops moving, that regressed.
- [ ] **1.3 The eliminated player stays connected and never readies.** The
      survivor's turns must still resolve. A dead empire must not hold the game up.
- [ ] **1.4 Ready tally.** With one player eliminated the status should read
      "1/1 ready", not "1/2" waiting on a corpse.
- [ ] **1.5 Last empire standing.** The survivor gets a *victory*, with a reason
      (military), not a neutral game-over.

## 2. Bombardment — implemented, never proven end to end

Its integration test skips on a turn-1 galaxy (no armed fleet is in orbit that
early), so this is the first real exercise. Ship combat auto-resolves by design;
the decision that follows it must not.

- [ ] **2.1 Get an armed fleet into orbit over the other player's colony** (declare
      war first). Combat auto-resolves — the server must not hang.
- [ ] **2.2 The attacker is asked "Bombard?"** rather than the AI deciding. *This
      is the one to report if it fails.*
- [ ] **2.3 Decline.** Nothing is bombed; population and factories unchanged.
- [ ] **2.4 The prompt returns next turn** while the fleet stays in orbit —
      declining now must not forfeit the option later.
- [ ] **2.5 Accept.** Population drops; the victim sees the loss on their side.
- [ ] **2.6 Preserve-and-invade.** Decline the bombardment, send transports, take
      the colony with factories intact. This is *why* the choice exists.

## 3. Human-to-human diplomacy

Extensively built, only ever tested against AI or in-process. The rule under test
throughout: **an offer aimed at a human must wait for that human**, never be
answered by their AI.

- [ ] **3.1 Alice offers a pact to Bob.** Bob gets an accept/decline prompt.
      Alice gets no instant verdict.
- [ ] **3.2 Bob accepts** — both sides show the pact. **3.3 Bob declines** — no treaty.
- [ ] **3.4 Alice asks Bob for a technology** (Races → Audience → Exchange
      technology). Bob is prompted and offered a list of *Alice's* technologies to
      demand in return. Alice sees nothing until Bob answers.
- [ ] **3.5 Bob names a price.** **Both techs arrive after the next turn resolves,
      not instantly** — traded techs are recorded and learned during turn
      processing. Do not report this as a bug until you have ended a turn.
- [ ] **3.6 Bob refuses.** Nothing trades.
- [ ] **3.7 Bob ignores the prompt entirely.** Nothing trades; the request lapses
      with the turn rather than hanging over the next one.
- [ ] **3.8 Aid.** Alice gifts BC, and separately a technology. Both arrive.
- [ ] **3.9 Threats**, where the buttons are enabled.
- [ ] **3.10 Declare war on each other.** Both see it.
- [ ] **3.11 Meet via war.** If you can arrange first contact *and* a war
      declaration on the same turn, the victim should be told about **both** the
      contact and the war.
- [ ] **3.12 Break a treaty.**

## 4. Fog of war and per-empire routing

- [ ] **4.1 Different views.** Alice and Bob each see only their own colony detail.
- [ ] **4.2 Neither sees the other's internals** — spending, research, exact fleets.
- [ ] **4.3 Notifications are per-recipient.** Alice's colony/tech events do not
      appear on Bob's client.
- [ ] **4.4 Combat and spy alerts** reach only the affected player.
- [ ] **4.5 GNN news reaches both** — it is a galaxy-wide news network and is
      deliberately *not* fogged.
- [ ] **4.6 Spy reports are per-empire.** Both players spying should each get
      their own report, not just empire 0.

## 5. Connection, reconnection, turn flow

- [ ] **5.1 A turn resolves only when both are ready.**
- [ ] **5.2 Orders are rejected while a turn is resolving.**
- [ ] **5.3 Bob quits and relaunches with the same name** → same empire, current
      turn, able to keep playing.
- [ ] **5.4 While Bob is away, do turns advance?** They currently do, and 30+
      turns can pass before he returns. **Decide whether that is what you want** —
      see the open question in the handoff doc. Worth deliberately observing.
- [ ] **5.5 Turn timer.** Restart with `timer=60`; a turn should auto-resolve when
      someone does not ready, and the client should count down.
- [ ] **5.6 Kill the server mid-game and restart it.** Without a save the galaxy
      is gone — confirm that is the behaviour you expect before relying on it.

**Cannot be tested with the Java client:** reclaiming an empire under a *different
name* via session token, and the refresh-takeover case where two connections briefly
hold the same token. The Java client does not persist its token. Both are covered by
integration tests and are really browser-client concerns.

## 6. Council, save/load, and the long game

- [ ] **6.1 A council convenes** (needs 2/3 of the galaxy colonised — a long game,
      or force it). Each human is prompted to vote in turn.
- [ ] **6.2 Ignore a vote prompt** — the convention still closes rather than
      re-convening every turn.
- [ ] **6.3 Save mid-vote, restart the server with `load=`, both reconnect.** The
      open vote survives with the same candidates and tally.
- [ ] **6.4 Ordinary save/resume.** Host saves (⌘S), server restarts with
      `load=<name>`, both players reconnect to their own empires.

## 7. Known gaps — expect these to behave "wrong"

Not bugs; unimplemented. Confirm they behave as described so they are not
mistaken for regressions.

- [ ] **7.1 Stealing technology.** After a successful espionage mission the AI
      picks which tech is stolen — you are not offered the choice. MOO1 offers it.
- [ ] **7.2 Sabotage target.** Likewise chosen for you.
- [ ] **7.3 Joint war** cannot be proposed at all.
- [ ] **7.4 Ship combat is never interactive.** Deliberate: a tactical battle
      would stall every other player. Only the decisions around it are yours.

---

## Reporting

For anything that fails, the useful details are: which player (Alice/empire 0 or
Bob/empire 1), the turn number, what each client showed, and the server's stdout
around that turn. The empire number matters more than it looks — several of these
paths behave differently for empire 0.
