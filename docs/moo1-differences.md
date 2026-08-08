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
> OSG's *documented* rules even where the shipped 1993 game did something different
> (or buggy).** So "MOO1" can mean two things — *the guide* vs *the actual game* —
> and ROTP occasionally picks the guide. Note which one a divergence is against.

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
- **Divergence:** interaction model differs (ROTP auto-fills, no manual add). This is
  exactly why our Empire-Overview "add to reserve" is deferred (see the TODO in
  `EmpirePanel` / handoff) — it has no faithful ROTP mechanic yet.

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
