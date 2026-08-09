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

# Wire protocol

> **Open standard.** This specification is released under **CC0 1.0** — public domain,
> *not* the GPL that covers the rest of this repository. **Anyone may implement it, in
> any language, in software under any licence, open or proprietary.** Implementing it
> does not make your software a derivative work of this project and creates no
> obligation to it: no attribution, no source sharing, no permission needed.
>
> The protocol is original work. The game this server is built from is single-player and
> has no network protocol; none of this is inherited from it.

Everything the client needs to talk to the game server. This document is the
authority: build against it, not against the server's source.

Protocol version **1** (`hello.version`). A version mismatch is rejected at the
handshake and the connection is closed.

## 1. Transport and envelope

A single WebSocket connection carries the whole session. `ws://host:port` for a
LAN game; a hosted game is `wss://host/path` behind a TLS proxy, so treat the
endpoint as a **URL**, not a host and port.

Every frame is a JSON object with exactly two fields:

```json
{"t": "view", "d": { ... }}
```

- `t` — the message type, from the tables below.
- `d` — the payload object. May be absent or empty for payload-less messages.

An unrecognised `t` is answered with an `error` and otherwise ignored; it does not
close the connection. Absent fields take their type's zero value (`0`, `false`,
`null`, and **`null` for arrays and lists** — do not assume an empty array).

Ids are indices, not opaque handles: `empireId` and `systemId` are stable for the
life of a game and are what you send back.

## 2. Client → server

### 2.1 Session and lobby

| `t` | Payload | Meaning |
|---|---|---|
| `hello` | `version:int`, `playerName:string`, `sessionToken:string?` | First message on every connection. `sessionToken` re-claims a previous session (§ 3 of the client guide). |
| `pickRace` | `raceId:string` | Choose a race in the lobby. Rejected if taken. |
| `startGame` | `aiOpponents:int`, `galaxySize:string`, `difficulty:string`, `turnTimerSeconds:int` | **Host only.** Start now with whoever is present. `aiOpponents` `-1` uses the ruleset default; `turnTimerSeconds` `-1` keeps the server's setting, `0` disables the timer. |
| `ready` | `ready:boolean` | Submit for this turn. The turn resolves when every connected, still-living player is ready. |
| `saveGame` | `name:string` | Save the running game on the server. |

### 2.2 Colony and empire economy

| `t` | Payload | Meaning |
|---|---|---|
| `setColonyAlloc` | `systemId:int`, `alloc:int[5]` | Spending ticks per category, **summing to 50**. Order: ship, defence, industry, ecology, technology. |
| `previewColony` | `systemId:int`, `alloc:int[5]` | Ask what a hypothetical spend *would* do, without committing. Answered by `colonyPreview`. Debounce it while dragging. |
| `setColonyLock` | `systemId:int`, `category:int`, `locked:boolean` | Hold a category's value during redistribution. |
| `setColonyMaxBases` | `systemId:int`, `maxBases:int` | Target missile-base count. Lowering scraps the excess. |
| `setShipBuild` | `systemId:int`, `designSlot:int`, `buildLimit:int` | What this colony builds. `buildLimit` `0` = no limit. |
| `transferReserve` | `systemId:int`, `amount:int` | Move banked BC from the empire reserve to a colony. Lossless. |
| `setEmpireTax` | `level:int`, `onlyDeveloped:boolean` | The **only** way to put money *into* the reserve: an empire-wide tax on colony production, banked at 50%. There is no per-planet "deposit". |
| `setSecurity` | `allocation:int` | Empire-wide internal security, 0–10 ticks. |

### 2.3 Research

| `t` | Payload | Meaning |
|---|---|---|
| `setTechAlloc` | `alloc:int[6]` | Ticks per category, each 0–60, summing to at most 60. Order: computers, construction, force fields, planetology, propulsion, weapons. |
| `setTechLock` | `category:int`, `locked:boolean` | Hold a category during redistribution. |
| `setResearchChoice` | `category:int`, `techId:string` | Choose what to research next in a category. Must be one of that category's `choices` in the view. |

### 2.4 Fleets, transports, colonisation

| `t` | Payload | Meaning |
|---|---|---|
| `deployFleet` | `fromSystemId:int`, `destSystemId:int`, `counts:int[]?` | Send ships. `counts` is per design slot; `null` sends the whole orbiting fleet. |
| `sendTransports` | `fromSystemId:int`, `destSystemId:int`, `size:int` | Schedule population transports. |
| `abortTransports` | `fromSystemId:int` | Cancel transports not yet launched. |
| `colonize` | `systemId:int` | Settle with a colony ship in orbit. Also the answer to a `COLONIZE` prompt. |
| `bombard` | `systemId:int` | Bomb the colony your fleet is holding orbit over. The answer to a `BOMBARD` prompt. |

### 2.5 Ship design

| `t` | Payload | Meaning |
|---|---|---|
| `designCatalog` | *(empty)* | Ask for the component catalogue. Answered by `designCatalog`. |
| `createDesign` | `slot:int`, `name:string`, `size:int`, `computer`, `shield`, `ecm`, `armor`, `engine`, `maneuver` *(all string)*, `weapons:string[]`, `weaponCounts:int[]`, `specials:string[]` | Fill an inactive design slot. Component names come from the catalogue; empty string means none. `size` 0–3 = small…huge. |
| `scrapDesign` | `slot:int` | Retire a design. The last remaining design cannot be scrapped. |

### 2.6 Espionage

| `t` | Payload | Meaning |
|---|---|---|
| `setSpySpending` | `empireId:int`, `allocation:int` | 0–20 ticks against one empire. |
| `setSpyMission` | `empireId:int`, `mission:string` | `HIDE` \| `ESPIONAGE` \| `SABOTAGE`. |
| `setSpyFrame` | `empireId:int`, `frameEmpireId:int` | Standing preference: if your spy is caught stealing from `empireId`, pin it on `frameEmpireId`. `-1` frames nobody. You cannot frame yourself or the victim. |
| `stealTech` | `empireId:int`, `categoryId:string` | Answer to a `STEAL_TECH` prompt: which technology category your spies take from. |
| `sabotage` | `empireId:int`, `action:string` | Answer to a `SABOTAGE` prompt: `FACTORIES` \| `MISSILES` \| `REBELS`. |

### 2.7 Diplomacy

| `t` | Payload | Meaning |
|---|---|---|
| `diploOptions` | `empireId:int` | What can I currently do to this empire? Answered by `techTradeMenu`. |
| `diploOffer` | `empireId:int`, `action:string`, `tradeLevel:int` | `TRADE` (with `tradeLevel`) \| `PEACE` \| `PACT` \| `ALLIANCE`. |
| `breakTreaty` | `empireId:int`, `treaty:string` | `TRADE` \| `PACT` \| `ALLIANCE`. |
| `declareWar` | `empireId:int` | |
| `respondDiplomacy` | `empireId:int`, `action:string`, `accept:boolean` | Answer an `INCOMING_DIPLOMACY` prompt. |
| `requestTech` | `empireId:int`, `techId:string` | Ask for one of their technologies. Answered by `techCounterOffer`, or a refusing `diploReply`. |
| `counterOfferTech` | `empireId:int`, `requestedTechId:string`, `offeredTechId:string` | Pay the price they named and close the exchange. |
| `respondTechRequest` | `requestorId:int`, `counterTechId:string?` | Answer an `INCOMING_TECH_REQUEST`. `counterTechId` is the tech of *theirs* you demand; null or empty refuses. |
| `offerAid` | `empireId:int`, `amount:int`, `techId:string?` | A gift. Send **exactly one** of `amount` (from the menu's `aidAmounts`) or `techId`. |
| `threaten` | `empireId:int`, `threat:string` | `EVICT_SPIES` \| `STOP_SPYING` \| `STOP_ATTACKING`. |
| `castCouncilVote` | `candidateId:int` | Answer a `COUNCIL_VOTE` prompt. `-1` abstains. |

## 3. Server → client

| `t` | Payload | When |
|---|---|---|
| `joined` | `empireId:int`, `host:boolean`, `sessionToken:string` | Answer to `hello`. **Store the token.** |
| `lobby` | `slots:[{empireId, playerName, connected, raceId}]`, `message:string` | Broadcast on every lobby change. |
| `raceOptions` | `races:[{id, name, description}]` | On join. |
| `sizeOptions` | `sizes:[{id, name, stars}]`, `selectedId:string` | On join. Host picks one for `startGame.galaxySize`. |
| `difficultyOptions` | `levels:[{id, name, aiProductionPct}]`, `selectedId:string` | On join. `aiProductionPct` is the AI's economic strength — 100 is parity. Label it as *AI ability*, not puzzle difficulty. |
| `gameStarted` | `empireId:int` | The game is running; leave the lobby. |
| `view` | *(see § 4)* | After game start, after every resolved turn, and after every accepted order. |
| `turnStatus` | `processing:boolean`, `turn:int`, `readyCount:int`, `totalPlayers:int`, `note:string`, `secondsRemaining:int` | Whenever readiness changes. `secondsRemaining` `-1` = no timer. Re-enable the end-turn control only when `processing` is false. |
| `cmdResult` | `command:string`, `ok:boolean`, `text:string` | After every order. On failure `text` is why. |
| `notifications` | `turn:int`, `items:[{category, text, systemId, empireId}]` | After a turn. |
| `prompts` | `turn:int`, `items:[Prompt]` | Decisions awaiting you (§ 5). Usually after a turn, but a tech request arrives mid-turn. |
| `diploReply` | `empireId:int`, `action:string`, `accepted:boolean`, `text:string` | A verdict on something you offered. |
| `techTradeMenu` | *(see below)* | Answer to `diploOptions`. |
| `techCounterOffer` | `empireId:int`, `requestedTechId:string`, `requestedTechName:string`, `text:string`, `counterOptions:[TechOption]` | Their price for the tech you asked for. |
| `colonyPreview` | `systemId:int`, `result:string[5]` | Answer to `previewColony`. Show as provisional. |
| `designCatalog` | `hulls`, `computers`, `shields`, `ecms`, `armors`, `engines`, `maneuvers`, `weapons`, `specials` — all `string[]` | Component names for the design screen. Index 0 of each is "none". |
| `gameOver` | `won:boolean`, `reason:string`, `text:string` | Your own outcome. Sent once. |
| `error` | `text:string` | Something was wrong with a message, or the connection is refused. |

`techTradeMenu`: `empireId:int`, `canExchangeTech`, `canOfferAid`, `canThreatenSpying`,
`canThreatenAttacking`, `canEvictSpies` *(all boolean)*, `canRequest:[TechOption]`,
`canGift:[TechOption]`, `aidAmounts:int[]`.

`TechOption`: `id:string`, `name:string`, `quintile:int` (tier), `cost:int` (research
cost — the rough worth of a deal).

**Offer only what the menu allows.** The booleans and lists are the server's own
answer to "would I accept this right now", so a greyed-out control needs no local
rule. Several actions are gated on things the client cannot see, so guessing will
produce buttons that always fail.

## 4. `view` — the PlayerView

The whole of what your empire knows, rebuilt and resent rather than patched. Replace
your state wholesale on each one; there are no deltas.

**Top level**

`turn:int`, `year:int`, `galaxyWidth:int`, `galaxyHeight:int` (light-years),
`empireId:int`, `empireName:string`, `raceName:string`, `colorId:int`,
`internalSecurity:int`, `reserve:float`, `totalIncome:float`, `netIncome:float`,
`maintenanceCost:float`, `empireTaxLevel:int`, `maxEmpireTaxLevel:int`,
`empireTaxOnlyDeveloped:boolean`, `empireTaxRevenue:float`,
`empires:[EmpireDto]`, `systems:[SystemDto]`, `fleets:[FleetDto]`,
`transports:[TransportDto]`, `designs:[DesignDto]`, `tech:TechDto`.

**EmpireDto** — you and every empire you have contacted.
`id`, `name`, `race`, `colorId`, `personality`, `objective` (leader disposition, e.g.
"Xenophobic" / "Expansionist"), `atWar`, `pact`, `alliance`, `atPeace`, `tradeLevel`,
`maxTradeLevel`, `spySpending`, `spyMission`, `spyFrameEmpireId`, `spies`, `maxSpies`,
`relativePower:float` (their estimated strength relative to yours; 1.0 is parity),
`knownTechCount`, `reportAge` (turns since your last spy report, `-1` never).
Your own entry has defaults for everything relational.

**SystemDto** — every star, at whatever fidelity you have earned.
`id`, `x`, `y`, `scouted`, `name` (empty until scouted), `ownerId` (`-1` unknown),
`planetType`, `planetTypeName`, `maxSize`, `canColonize`, `distance:float`,
`inShipRange`, `colonized`, `population`, `colony:ColonyDto?`.

`colony` is present **only for your own colonies** — that is the fog of war, not an
omission. `distance` is in light-years; show one decimal, because base ship range is
3.0 and a star 3.2 away is genuinely out of reach.

**ColonyDto**
`alloc:int[5]`, `locked:boolean[5]`, `result:string[5]` (the server's per-category
outcome text — years to complete, output, waste/clean, research points; display it,
do not compute it), `population`, `maxSize`, `planetSize`, `waste`, `popGrowth`,
`shield`, `notes` (rebellion, quarantine, space monster…), `factories`, `bases`,
`maxBases`, `production`, `reserveIncome`, `maxReserveNeeded`, `shipyardDesign`,
`buildLimit`, `transportSize`, `transportDestId` (`-1` none).

**TechDto**
`alloc:int[6]`, `locked:boolean[6]`, `progress:float[6]` (0–1 toward the current tech,
distinct from allocation), `researching:string[6]`, `researchingId:string[6]`,
`totalRP:float`, `choices:[[TechChoice]]` — six lists, the techs selectable in each
category. `TechChoice`: `id`, `name`, `cost`.

**FleetDto** `atSystemId` (`-1` in transit), `destSystemId` (`-1` idle), `x`, `y`,
`counts:int[]` per design slot.
**DesignDto** `slot`, `name`, `size` (0–3), `totalSpace`, `availableSpace`,
`colonyShip`, `range`.
**TransportDto** `destSystemId`, `size`, `x`, `y`.

## 5. Prompts

A `prompts` message carries decisions only you can make. Each is **self-contained** —
it holds everything needed to decide, so it does not depend on the order other
messages arrived in.

**Every prompt may be ignored.** The server has a default and the game will not stall.
Ignoring is a real choice with real consequences, not an error state.

| `type` | Carries | Resolve with |
|---|---|---|
| `SELECT_TECH` | `category`, `choiceIds`, `choiceNames` | `setResearchChoice` |
| `INCOMING_DIPLOMACY` | `empireId`, `action` | `respondDiplomacy` |
| `COUNCIL_VOTE` | `choiceIds`, `choiceNames` (last entry is `-1`, abstain) | `castCouncilVote` |
| `COLONIZE` | `systemId` | `colonize` — ignoring leaves the ship in orbit and re-asks next turn |
| `INCOMING_TECH_REQUEST` | `empireId`, `techId`, `techName`, `choiceIds`, `choiceNames` (their techs you may demand) | `respondTechRequest` — ignoring refuses, and the request lapses with the turn |
| `BOMBARD` | `systemId`, `empireId` (the victim) | `bombard` — ignoring bombs nothing and re-asks next turn while you hold orbit |
| `STEAL_TECH` | `empireId` (the victim), `choiceIds` (technology categories), `choiceNames` (the tech each would yield) | `stealTech` — **ignoring does not lose the theft**: your own AI picks when the turn resolves |
| `SABOTAGE` | `empireId` (the victim), `systemId`, `choiceIds` (`FACTORIES`/`MISSILES`/`REBELS`), `choiceNames` (each naming the system it hits) | `sabotage` — ignoring lets your AI choose when the turn resolves |

`Prompt` fields: `type`, `category`, `text`, `choiceIds:string[]`,
`choiceNames:string[]`, `empireId`, `action`, `systemId`, `techId`, `techName`.
Only those relevant to a type are populated. `text` is server-generated English —
usable as a fallback, but the structured fields are there so you can write your own.

## 6. Notification categories

`Notification`: `category`, `text`, `systemId` (`-1` if none), `empireId` (`-1` if none).

| Category | |
|---|---|
| `CONTACT` | First contact with an empire. |
| `DIPLOMACY` | A treaty or war changed. |
| `COLONY_GAINED` / `COLONY_LOST` | |
| `TECH` | A technology completed. |
| `NEWS` | Galactic news — **broadcast to everyone and deliberately not fogged**, including news about empires you have not met. |
| `ALERT` | Combat and espionage affecting you: transports lost, factories sabotaged, tech stolen, spy reports. Routed per-recipient. |

## 7. Not yet on the wire

Being added to the server. Do not design around their absence.

- **Joint war offers** — the one diplomatic action with no protocol equivalent.

## 8. Deliberately absent

Not gaps; decisions.

- **Tactical ship combat.** Battles resolve automatically. An interactive battle is a
  multi-round screen inside a simultaneous turn, so every other player would sit and
  wait. The decisions *around* a battle — whether to bombard, whether to invade — are
  the player's and are on the wire.
- **Player colours** are a local display choice. `colorId` is a suggestion you may
  override.
- **The galaxy-size list.** The server accepts its full range; offering a smaller,
  more faithful set is the client's business.
