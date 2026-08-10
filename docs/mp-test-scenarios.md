# Phase 4.5 — human-vs-human test scenarios

**This checklist is the whole of Phase 4.5.** Phase 4 put every decision a desktop player
can make onto the wire; this is how we find out whether that is actually true. Phase 5
(the browser client) does not start until it passes — the value of the backend sign-off
is that a bug found there is conclusively a *client* bug, and that only holds once the
backend has met real play.

**Exit criteria:** the checklist passes end to end, a session of deliberate abuse
produces no server stack traces, and anything it turns up is fixed. Fixes are Phase-4
work; finding them is this phase.

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
- [ ] **5.4 While Bob is away, the AI plays his empire.** Turns advance without
      him (a dropped player must not stall the game) and his empire should keep
      *developing* — research reallocated, ships built, fleets moved — not sit
      frozen on its last orders. Leave him disconnected for several turns, then
      reconnect and check his empire actually progressed.
- [ ] **5.5 On reconnect the human has the helm again.** His next orders stick and
      are not overwritten by the AI.
- [ ] **5.6 Turn timer.** Restart with `timer=60`; a turn should auto-resolve when
      someone does not ready, and the client should count down.
- [ ] **5.7 Kill the server mid-game and restart it.** Without a save the galaxy
      is gone — confirm that is the behaviour you expect before relying on it.

**Cannot be tested with the Java client:** reclaiming an empire under a *different
name* via session token, and the refresh-takeover case where two connections briefly
hold the same token. The Java client does not persist its token. Both are covered by
integration tests and are really browser-client concerns.

## 6. Try to break the server

Nothing a client does may error the server — a browser client will send things the
Java client never does, and a wedged server is indistinguishable from a protocol
misunderstanding. Automated coverage exists (`ServerRobustnessTest`); these are the
things a person can do that it cannot.

- [ ] **6.1 Force-quit a client mid-turn**, repeatedly, while the other player
      keeps playing. The game keeps resolving.
- [ ] **6.2 Pull the network** on one machine (wifi off) rather than closing
      cleanly — no close frame is sent, so the server has to notice by heartbeat.
- [ ] **6.3 Both players ready at the same instant**, repeatedly.
- [ ] **6.4 Spam clicks** on Next Turn, and on the diplomacy buttons, during turn
      resolution. Orders should be rejected with a message, not swallowed or fatal.
- [ ] **6.5 Reconnect both players simultaneously.**
- [ ] **6.6 Leave a game idle for an hour**, then resume — nothing should have
      timed out or leaked.
- [ ] Watch the server's stdout throughout. **Any stack trace is a Phase-4 bug**,
      even if the game carries on.

## 7. Council, save/load, and the long game

- [ ] **7.1 A council convenes** (needs 2/3 of the galaxy colonised — a long game,
      or force it). Each human is prompted to vote in turn.
- [ ] **7.2 Ignore a vote prompt** — the convention still closes rather than
      re-convening every turn.
- [ ] **7.3 Save mid-vote, restart the server with `load=`, both reconnect.** The
      open vote survives with the same candidates and tally.
- [ ] **7.4 Ordinary save/resume.** Host saves (⌘S), server restarts with
      `load=<name>`, both players reconnect to their own empires.

## 8. Espionage and joint war — newest, least exercised

Added after this checklist was first written, so nothing here has been played.

- [ ] **8.1 Stealing technology.** Run an ESPIONAGE mission until it succeeds. You
      should be asked **which technology category** to take, with the technology
      each would yield. Choose one and confirm you receive it.
- [ ] **8.2 Ignore that prompt.** The theft is *not* lost — your own AI picks when
      the turn resolves. Confirm you still get a technology.
- [ ] **8.3 Sabotage.** Run a SABOTAGE mission. You should be asked to choose
      between destroying factories, destroying missile bases, and inciting
      rebellion, **each naming the system it would hit**. Confirm the damage lands
      on that system.
- [ ] **8.4 Joint war, outgoing.** Races → Audience → Propose a joint war. Pick who
      they should fight. They agree, refuse, or **name a price** in technologies and
      BC — pay it and confirm the war starts.
- [ ] **8.5 Joint war, human to human.** Alice asks Bob to fight a third empire.
      **Bob must be prompted**; Alice gets no instant verdict, and Bob is not at war
      until he agrees.
- [ ] **8.6 Joint war needs reach.** If the option is missing, that is probably
      correct: both of you must be within *economic range* of the target. Contact
      alone is not enough. Not a bug.

## 9. The planetary reserve

Both directions, including the MOO1 "bank a share of everything" behaviour.

- [ ] **9.1 Set an empire tax** (Planet List → Planetary reserve → Tax colonies %).
      This is the automatic proportion-of-production route into the reserve. End a
      turn and confirm the reserve grows.
- [ ] **9.2 Developed-colonies-only.** Toggle it and confirm the take changes.
- [ ] **9.3 Spend it.** Select a colony, enter BC, Transfer. The colony's reserve
      figure rises and the empire reserve falls.
- [ ] **9.4 The 50% haircut is expected.** Banking is lossy — the engine halves what
      the tax takes. Spending it back out is lossless. Not a bug; see
      `moo1-differences.md` §4.

## 10. Deliberately absent — expect these to behave "wrong"

Not gaps; decisions.

- [ ] **10.1 Ship combat is never interactive.** A tactical battle would stall every
      other player, so battles auto-resolve. Only the decisions around them —
      bombard, invade — are yours.

---

## Reporting

Tick items as they pass, and note the date at the top when a full pass completes — that
is the record that Phase 4.5 is done.

For anything that fails, the useful details are: which player (Alice/empire 0 or
Bob/empire 1), the turn number, what each client showed, and the server's stdout
around that turn. The empire number matters more than it looks — several of these
paths behave differently for empire 0.
