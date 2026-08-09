<!--
  Star Lords multiplayer protocol specification.

  LICENCE: CC0 1.0 Universal — this document is placed in the public domain.
  https://creativecommons.org/publicdomain/zero/1.0/

  This file is NOT under the GPL that covers the rest of this repository. It is
  deliberately unencumbered so that ANYONE may implement this protocol, in any
  language, in software under ANY licence — open source or proprietary, free or
  commercial — with no obligation to this project.

  IMPLEMENTATION GRANT: implementing this specification does not make your
  software a derivative work of this project, and creates no licence obligation.
  You need not credit us, share your source, or ask permission. Interoperating is
  the entire point.

  The protocol itself is original work: the game this server is built from is
  single-player and has no network protocol. Nothing here is inherited from it.
-->

# Implementing the protocol

> **Open standard.** This specification is released under **CC0 1.0** — public domain,
> *not* the GPL that covers the rest of this repository. **Anyone may implement it, in
> any language, in software under any licence, open or proprietary.** Implementing it
> does not make your software a derivative work of this project and creates no
> obligation to it: no attribution, no source sharing, no permission needed.
>
> The protocol is original work. The game this server is built from is single-player and
> has no network protocol; none of this is inherited from it.

Companion to [`protocol.md`](protocol.md), which is the message reference. This is the
part a message list does not tell you: the order things happen in, and the behaviours
that will otherwise look like bugs in your client when they are the specified behaviour.

Written for anyone building a client — the Java client in `rotp.mp.client` is the
reference implementation, but nothing here assumes you are extending it.

## 1. Connection and lobby

```
client                          server
  │── open WebSocket ─────────────▶
  │── hello {version, name} ──────▶
  │◀───────────── joined {empireId, host, sessionToken}
  │◀───────────── raceOptions / sizeOptions / difficultyOptions
  │◀───────────── lobby            (broadcast, on every change)
  │
  │── pickRace ───────────────────▶   (optional)
  │── startGame ──────────────────▶   (host only; or wait for auto-start)
  │◀───────────── gameStarted {empireId}
  │◀───────────── view
```

**Store `sessionToken` immediately** — `localStorage`, keyed by server URL. It is the
only way back into your empire if anything goes wrong. See § 3.

The game starts either when every human slot is full, or when the host sends
`startGame` and AI fills the rest. Only the host's `startGame` is honoured; `joined.host`
tells you whether to show those controls.

## 2. The turn loop

```
  │── setColonyAlloc / deployFleet / … ─▶
  │◀───────────── cmdResult {ok}
  │◀───────────── view                  (on success — orders change computed values)
  │
  │── ready {true} ────────────────────▶
  │◀───────────── turnStatus {readyCount, totalPlayers}
  │                                      … waiting for other players …
  │◀───────────── notifications
  │◀───────────── prompts
  │◀───────────── view                  (the new turn)
  │◀───────────── turnStatus {processing: false}
```

Turns are **simultaneous**: everyone gives orders at once and the turn resolves when
all connected, still-living players are ready.

Three things to get right:

- **Every accepted order is followed by a fresh `view`.** Do not apply orders
  optimistically to your local state — send, then render what comes back. A rejected
  order changes nothing.
- **Re-enable the end-turn control on `turnStatus.processing == false`**, not on
  receiving a view. Getting this wrong leaves the button dead for the rest of the game.
- **Orders are refused while a turn is resolving** with an `ok:false` result. Expected,
  not an error to surface loudly.

If a turn timer is set, `turnStatus.secondsRemaining` counts down and the turn
auto-resolves at zero with whatever orders are in. `-1` means no timer.

## 3. Reconnection — assume it happens constantly

A browser tab gets refreshed, a laptop sleeps, a phone changes network. The server is
built for this and the client must be too.

**Reconnecting:** open a new socket and send `hello` **with the stored
`sessionToken`**. You get `joined`, then `gameStarted`, then a fresh `view`, and you
are back in the same empire at the current turn.

The token beats the display name, so a player can come back under a different name.
It also works when the *old socket is still open* — the server evicts the stale
connection in your favour, which is exactly the refresh case where the new connection
arrives before the old close is noticed.

Without a token, the server falls back to matching your player name. A genuinely new
player cannot join a game in progress.

**While you are away the AI plays your empire**, and hands it back on reconnect. So a
disconnect costs you the decisions made in the meantime, not your position — but do
not treat reconnection as rare or expensive. The server also pings every 30 seconds
and drops silent connections, so a sleeping tab *will* be disconnected.

## 4. Things that will look like bugs and are not

Each of these cost real debugging time on the server side. They are the behaviour.

- **A traded technology does not arrive immediately.** Closing a tech exchange records
  the trade; both sides learn their tech when the *next turn resolves*. Do not report
  a missing tech until a turn has passed.
- **Diplomacy is gated on things you cannot see.** Trades, gifts and threats all
  require the other empire to be within economic range — roughly, near enough to your
  colonies. Two empires can be in contact and still unable to trade. Always drive the
  UI from `techTradeMenu` rather than your own rules.
- **An offer to another human is not answered immediately.** Their AI must not answer
  for them, so it becomes a prompt on their client and you hear nothing until they act.
  Against an AI empire the reply is immediate. Do not build a UI that blocks waiting
  for a verdict.
- **`view.systems[].colony` is null for everything but your own colonies.** Fog of war.
- **News is not fogged.** `NEWS` items can name empires and systems you have never met.
- **`distance` needs one decimal place.** Base ship range is 3.0 light-years; a star at
  3.2 is out of range and "3 ly" makes that look like a bug.
- **Ignoring a prompt is legitimate.** The server has a default for every one. Do not
  force the player through a modal they cannot dismiss.

## 5. What the server will not do for you

- **No deltas.** Every `view` is complete. Diff it yourself if you want animation.
- **No localisation.** `text` fields are English. The structured fields exist so you
  can write your own copy; prefer them.
- **No layout, no colours.** `colorId` is a suggestion.
- **No game rules.** If you find yourself computing whether something is legal, stop —
  either the server already answers it (`canColonize`, `inShipRange`, `maxTradeLevel`,
  the `techTradeMenu` booleans), or it is a protocol gap worth raising.

## 6. Checking your client against a known-good one

The reference client is the Java Swing application in `rotp.mp.client`. It is a
validation harness rather than a product, but it is a working implementation of every
message here, and running it alongside your own client is the fastest way to settle
"is this me or the server?".

Two clients against one local server, on two different machines or two windows on one,
is the normal way to exercise anything human-versus-human. That is also the only way
to see the prompts that only fire between two humans.
