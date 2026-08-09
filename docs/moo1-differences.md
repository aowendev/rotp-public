# ROTP vs. classic Master of Orion 1 — divergence ledger

A running record of where **Remnants of the Precursors (ROTP)** diverges from the
**original Master of Orion (1993, "MOO1")**. Built the clean way:

- **MOO1 side** = the *Official Strategy Guide* (OSG) formulas/tables, transcribed
  (game rules and formulas are facts and not copyrightable — only the guide's text/
  layout/art is, so we transcribe values, we do **not** reproduce or redistribute a
  scan of the book).
- **ROTP side** = the actual implementation, cited by `file — method()` (and line
  where stable) so it stays checkable as the code moves.

Status: **confirmed** = both sides verified (ROTP in code, MOO1 in the guide or an
in-code OSG citation); **pending guide** = ROTP side verified in code, MOO1 side
still needs a value from the guide.

## Which "MOO1"? — three references, and the Mac port wins for us

"MOO1" is not one thing. There are three reference points, and they can disagree:

1. **DOS game (1993)** — the shipped code's *actual behavior*. ROTP's comment at
   `Empire.java:1075` ("in the OSG, which was never implemented in actual MOO1 code
   anyway") means the **DOS** game here.
2. **Official Strategy Guide (OSG)** — written *for* the DOS version, but it
   demonstrably documents rules the DOS game didn't implement.
3. **Mac port (1995)** — a *separate* implementation. **Hypothesis:** if the Mac
   port was written *from the guide* rather than ported from the DOS source, it may
   match the OSG exactly where DOS diverged. Unverified, but plausible.

**This project targets the Mac port's feel** (that's what `mac-ux-spec.md` is —
captured from the 1995 Mac release in an emulator). So **where the three disagree,
the Mac version is our authoritative reference**, not DOS — and if the hypothesis
holds, the OSG becomes *more* trustworthy for us, not less.

Practical consequence: DOS-vs-OSG conflicts can be settled **empirically**, because
the Mac version runs in the same emulator used to build the UX spec. Strongest
evidence chain: **OSG formula → confirm against Mac-in-emulator → diff vs ROTP code.**
When a row's MOO1 value is guide-only (not yet checked against the Mac port), say so;
when it's confirmed against the Mac port, mark it — that's the gold standard here.

> Note on ROTP's stance: ROTP generally targets the **DOS** behavior/OSG, not the
> Mac port. So a ROTP↔OSG match doesn't guarantee a ROTP↔Mac match, and a case where
> "ROTP followed the OSG but DOS didn't" may actually be ROTP *agreeing with the Mac
> port* — exactly the cases this project cares about most.

> **Handy:** ROTP's own source cites the OSG in several spots — mine these first when
> filling in the MOO1 column:
> - `MOO1GameOptions.numberStarSystems()` — "MOO Strategy Guide, Table 3-2, p.50" (galaxy star counts)
> - `MOO1GameOptions` ~line 365 — "Table 3-3, p.51"
> - `MOO1GameOptions.randomPlanet()` ~line 489 — star-type distribution "per MOO1 Official Strategy Guide"
> - `MOO1GameOptions` ~line 994 — "planet/star ratios per Table 3-9a"
> - `Spy.java:47` — spy fate, "p271 of Strategy Guide, Table 12-1"
> - `Empire.java:1075` — a rule "in the OSG (which was never implemented in actual MOO1 code anyway)"
>
> That last one flags a deliberate ROTP philosophy: **it sometimes implements the
> OSG's *documented* rules even where the shipped 1993 DOS game did something
> different (or buggy).** See "Which MOO1?" above — those OSG-over-DOS cases are the
> ones most likely to also match the **Mac port**, which is what this project
> actually targets. Always note which reference (DOS / OSG / Mac) a row is measured against.

---

## Divergences found so far

### 1. Galaxy sizes & star counts — **confirmed**
- **MOO1 (OSG Table 3-2, p.50):** four sizes — Small **24** stars, Medium **48**,
  Large **70**, Huge **108**.
- **ROTP:** seventeen sizes with a denser progression — Tiny **33**, Small **50**,
  Small2 70, Medium **100**, Medium2 150, Large 225, Large2 333, Huge **500**,
  Huge2 700, Massive 1000 … Insane 10000, Ludicrous 100000, Maximum.
  `MOO1GameOptions.numberStarSystems()` / `galaxySizeOptions()`.
- **Divergence:** ROTP replaced the four OSG counts (still present, commented out at
  `numberStarSystems()` line ~322) with its own lineup and added many more sizes.
  Same MOO1 names (Small/Medium/Large/Huge) map to **different** star counts.
- **Impact on us:** this is the "map the original's option set onto ROTP's" TODO in
  the multiplayer handoff; the classic four map to ROTP Small≈24 (no exact match;
  ROTP Small=50), so a faithful "MOO1 sizes" preset would need the commented-out
  values.

### 2. Ship fuel range — **partly confirmed**
- **MOO1:** base range **3 LY** without reserve fuel tanks (user-confirmed; matches
  the ROTP base). *(Pending guide: the exact reserve-tank / range-tech ladder.)*
- **ROTP:** base range also **3 LY** — `TechFuelRange` quintile 0 returns `3`
  (`TechFuelRange.java` ~line 40), surfaced via `TechTree.shipRange()`. **But** the
  value is multiplied by a configurable `IGameOptions.fuelRangeMultiplier()`
  (Normal **1.0**, High **1.5**, Higher **2.0**, Highest **2.5**), and
  `ShipDesign.range()` **truncates to an int**.
- **Divergence:** (a) ROTP adds a galaxy-setup **fuel-range multiplier** option MOO1
  didn't have; (b) the int-truncation means fractional ranges (e.g. 3×1.5 = 4.5)
  display/enforce as 4. Range is **not** scaled by galaxy size in either — a bigger
  galaxy just spreads stars over more LY.

### 3. Difficulty = AI economy strength — **pending guide**
- **MOO1:** five levels — Simple / Easy / Average / Hard / Impossible. *(Pending
  guide: the exact per-level AI bonuses.)*
- **ROTP:** seven levels — Easiest / Easier / Easy / Normal / Hard / Harder /
  Hardest — with `IGameOptions.aiProductionModifier()` = **0.5 / 0.75 / 0.9 / 1.0 /
  1.1 / 1.4 / 2.0** (AI production multiplier), plus an `aiWasteModifier()` that
  eases the AI's pollution on the three easy levels.
- **Divergence:** seven levels vs five; ROTP models difficulty purely as an AI
  economy multiplier (the human's rules are unchanged). Need the OSG's MOO1 numbers
  to line them up.

### 4. Planetary reserve — **pending guide**
- **MOO1:** a galactic reserve you could pay into and draw from (Mac-port Planets
  window offered both directions). *(Pending guide: banking loss %, transfer rules.)*
- **ROTP:** the reserve **auto-fills** from excess colony output (e.g. a maxed
  colony's surplus ecology BC — the "Reserve" hint on the colony screen).
  `Empire.allocateReserve(col, amt)` moves reserve→colony **losslessly**;
  `Empire.addReserve(amt)` banks at **50%** (`addToTreasury(amt/2)`). ROTP's own UI
  only offers reserve→colony (`TransferReserveUI`), no manual "bank income."
- **Divergence:** interaction model differs (ROTP auto-fills, no manual add).
- **Resolved for multiplayer (Phase 4, 2026-08-09).** The "add to reserve" direction has
  a faithful ROTP mechanic after all — it is just not a *transfer*. `Empire.nextTurn`
  banks `production × colonyTaxPct()` from every taxed colony, and `colonyTaxPct` comes
  from the empire-wide `empireTaxLevel` (0-20%, optionally developed colonies only). So
  our protocol exposes **out** as a transfer (`transferReserve{systemId, amount}`) and
  **in** as a rate (`setEmpireTax{level, onlyDeveloped}`). The 50% banking loss is the
  engine's, left as-is. Still divergent from the Mac port's symmetric pay-in/draw-out
  Planets window; revisit if the guide gives MOO1's actual banking rules.

### 5. Research cost — **pending guide**
- **MOO1:** OSG has a research-cost table by tech level. *(Pending guide: the table.)*
- **ROTP:** `TechCategory.baseResearchCost(level) = options().researchCostBase(level)
  × session().researchMapSizeAdjustment()` — research cost is **scaled by galaxy/map
  size**.
- **Divergence (likely):** the map-size scaling of research cost appears ROTP-specific;
  confirm against the OSG whether MOO1 scaled research by galaxy size.

### 6. Tech discovery is probabilistic after cost — **pending guide (likely same)**
- **MOO1:** research doesn't complete the instant you hit the cost; there's a per-turn
  discovery chance once you're at/over cost. *(Pending guide: the exact chance.)*
- **ROTP:** `TechCategory.discoveryChance() = (totalBC − cost) / (cost × 2)` once
  `totalBC > cost` (and `upcomingDiscoveryChance()` for next turn).
- **Divergence:** probably faithful in spirit; the exact chance curve needs checking.

### 7. Ecology / waste cleanup — **pending guide**
- **MOO1:** OSG waste + cleanup-cost formulas. *(Pending guide.)*
- **ROTP:** `Colony.wasteCleanupTechMod() = 4 × factoryWasteMod() / wasteElimination()`;
  waste from `ColonyEcology.waste()` (= `Planet.waste()`); cleanup via
  `Colony.wasteCleanupCost()`.
- **Divergence:** unknown until compared; recorded here so the numbers are ready to
  diff.

### 8a. Leader personality & objective — **confirmed faithful (no divergence)**
- **MOO1:** each race's leader has a personality (Erratic / Pacifist / Honorable /
  Ruthless / Aggressive / Xenophobic) and an objective (Militarist / Ecologist /
  Diplomat / Industrialist / Expansionist / Technologist) — e.g. "Xenophobic
  Expansionist", shown on the races screen after contact.
- **ROTP:** the **same 6×6 set** — `Leader.Personality` / `Leader.Objective`
  (`Leader.java`), surfaced as `leader().personality()` / `leader().objective()` and
  formatted via `LEADER_PERSONALITY_FORMAT` (`Empire.java:1129`). Assigned per race
  (`race().randomLeaderAttitude()/randomLeaderObjective()`) or fully random with the
  `randomizeAIPersonality` option.
- **Divergence:** none in the taxonomy — a faithful match. (What each disposition
  *does* to the AI is ROTP's own AI code — see 8 below.) Now surfaced in the
  multiplayer Races panel (`EmpireDto.personality`/`objective`).

### 8. AI is entirely original — **confirmed (structural, not a formula)**
- **MOO1:** its own (undocumented) AI.
- **ROTP:** a from-scratch AI — `rotp.model.ai.base`, `…modnar`, `…xilmi`
  (`AIGovernor`, `AITreasurer`, `diplomatAI`, ship templates). Selectable per game.
- **Divergence:** total. Any "difficulty" comparison is really "ROTP AI at multiplier
  X vs the 1993 AI," not the same opponent.

---

## Backlog — mechanics to diff against the guide next
(Each has an in-code OSG citation or a clear ROTP implementation ready to compare.)

- Number of opponents / homeworld placement (OSG Table 3-3, `MOO1GameOptions` ~365)
- Star-type & planet-type distributions (OSG, `randomPlanet()` ~489; Table 3-9a ~994)
- Spy mission success / spy fate (OSG Table 12-1, p.271, `Spy.java:47`)
- Factory cost & industrial output (`ColonyIndustry`, `researchCostBase` siblings)
- Population growth & max population / terraforming (`ColonyEcology`, `Colony.maxSize()`)
- Ship maintenance & command points (`Empire.totalShipMaintenanceCost()`)
- Combat: to-hit, beam/missile damage, shields, planetary shield (`ColonyDefense`, combat model)
- Diplomacy / council-vote thresholds
- Random events, space monsters, the Guardian of Orion

## How to add an entry
1. Read the MOO1 value from the guide (or an in-code OSG citation).
2. Find the ROTP implementation (`grep` the model classes above) and cite `file — method()`.
3. Add a section: MOO1 (with source) → ROTP (with code ref) → the divergence → status.
