# Master of Orion (1995 Macintosh port) — Interface & Shortcut Reference

Authoritative source for the **Mac-port interface feel** requirement (design doc §1):
the eventual browser client should reproduce the interaction behavior and keyboard
shortcuts of the Mac version, not the DOS keyboard interface ROTP otherwise emulates.

**How this was captured:** the 1995 Mac release (Simtex / MacSoft MicroProse) was run
in the [Infinite Mac](https://infinitemac.org) browser emulator under System 7.5 and
driven directly. The menu commands and ⌘-shortcuts below were read off the live game;
screenshots of the core screens are in [`mac-ux/`](mac-ux/). This is a first-hand
reference, not reconstructed from memory.

## 1. What makes the Mac port different from DOS

The DOS version is a full-screen, keyboard/mouse hybrid with its own chrome. The Mac
port wraps the **same underlying screens** in a **native Mac application shell**:

- A real **menu bar** with pull-down menus and ⌘-key shortcuts for essentially every action.
- Each game screen is a **DOS screen presented inside a resizable Mac window** (upscaled).
  You can move/resize windows, bring them forward, and toggle them from the **Windows** menu.
- The default working layout is **two windows**: the **Map Window** (galaxy) and the
  **Info Window** (the right-hand colony/system sidebar). An **Options → Keep Info Window
  On Top** setting keeps the sidebar floating above the map.
- Standard Mac dialog conventions throughout: **popup menus** for choices (difficulty,
  galaxy size, race, flag), **Return activates the default button**, and a **standard
  file Save dialog** (MOO saves the game to a file at game start; there's also
  Options → **PC Compatible Saves** for DOS-save interop).

**Menu behavior detail (important for the browser client):** classic Mac menus are
**not sticky** — you press and *hold* the mouse button on the menu title, drag down to
the item, and release to activate. A plain click does not leave the menu open. The
browser client's menu system should feel modern (click-to-open is fine), but the
*command set and shortcuts* are what must be preserved.

## 2. Menu bar & keyboard shortcuts (complete)

Eight menus: **File · Edit · Options · Planets · Fleet · Map · Windows · Misc**
(Edit is the standard, inactive Mac Edit menu — greyed out in-game.)

| Menu | Command | Shortcut |
|---|---|---|
| **File** | Save | ⌘S |
| | Save as… | |
| | Export… | |
| | Quit | ⌘Q |
| **Options** | Sound *(toggle)* | |
| | Map Animations *(toggle)* | |
| | Scrolling Map *(toggle)* | |
| | Auto Save *(toggle)* | |
| | Keep Info Window On Top *(toggle)* | |
| | PC Compatible Saves *(toggle)* | |
| | Show Intro *(toggle)* | |
| **Planets** | Autotransfer Reserve | ⌘= |
| | View Planet | ⌘W |
| | Relocate New Ships Here | ⌘L |
| | Destroy Bases | ⌘B |
| | Next Planet / Previous Planet | ⌘2 / ⌘3 |
| | Next Attacked Planet / Previous Attacked Planet | ⌘8 / ⌘9 |
| | Planet List | ⌘P |
| **Fleet** | Ship Design | ⌘D |
| | Fleet List | ⌘F |
| | Design View | ⌘V |
| | Next Fleet / Previous Fleet | ⌘4 / ⌘5 |
| | Next New Fleet / Previous New Fleet | ⌘6 / ⌘7 |
| **Map** | Center Map | ⌘E |
| | Galaxy Map | ⌘G |
| | Grid | ⌘H |
| **Windows** | Map Window | ⌘M |
| | Info Window | ⌘I |
| **Misc** | Races | ⌘R |
| | Technology | ⌘T |
| | Spies Caught | ⌘A |
| | Next Turn | ⌘N |

Notable design cues: **⌘N = Next Turn** (the single most-used action gets a shortcut);
paging shortcuts (⌘2/⌘3 planets, ⌘4/⌘5 fleets, ⌘8/⌘9 attacked planets) let you cycle
through objects without the mouse; every major screen has a one-key opener
(⌘P Planets, ⌘D Ship Design, ⌘F Fleets, ⌘T Technology, ⌘R Races).

## 3. Core screens (captured)

| Screen | Opened by | File |
|---|---|---|
| Galaxy map + Info sidebar (main) | default | [`mac-ux/01-galaxy-map.jpg`](mac-ux/01-galaxy-map.jpg) |
| Planet List (empire management table) | Planets → Planet List (⌘P) | [`mac-ux/02-planet-list.jpg`](mac-ux/02-planet-list.jpg) |
| Technology (research) | Misc → Technology (⌘T) | [`mac-ux/03-technology.jpg`](mac-ux/03-technology.jpg) |
| Races (diplomacy) | Misc → Races (⌘R) | [`mac-ux/04-races-diplomacy.jpg`](mac-ux/04-races-diplomacy.jpg) |
| Ship Design view (current designs) | Fleet → Ship Design (⌘D) | [`mac-ux/05-ship-design-view.jpg`](mac-ux/05-ship-design-view.jpg) |
| New Ship Design (editor) | from the design view | [`mac-ux/06-new-ship-design.jpg`](mac-ux/06-new-ship-design.jpg) |

- **Galaxy map + Info sidebar** — the map in the main window; the Info Window on the
  right shows the selected system (name, planet type, POP/BASES/PRODUCTION), the five
  spending sliders **Ship / Def / Ind / Eco / Tech**, SHIPS/RELOC/TRANS buttons, the
  orbiting-fleet display, and **NEXT TURN**. This is the layout ROTP inherited.
- **Planet List** — a spreadsheet of all colonies (#, Planet, Population, Fact, Shd,
  Base, Wst, Prod, Space Dock, Notes) with Spending Costs, Total Income, and a
  Reserve/Transfer control. A dedicated empire-wide management view.
- **Technology** — six research categories (Computers, Construction, Force Fields,
  Planetology, Propulsion, Weapons), each with an allocation slider and a level; a
  preview + description panel for the selected tech. (This is the six-category model
  the multiplayer `setTechAllocations` command already uses.)
- **Races** — a grid of the ten race slots (contact status / portraits), an Overall
  Security slider, and Status / Report / Audience actions ("Audience" = open diplomacy).
- **Ship Design view** — the six design slots (Scout, Fighter, Destroyer, Bomber, Colony
  Ship, + one empty), each with hull stats, weapons, specials, cost, and a Scrap
  button. (The six-slot model the multiplayer ship-design commands already use.)
- **New Ship Design (editor)** — the build screen: a **hull-size selector
  (Small/Medium/Large/Huge)**, component rows (Computer, Shield, ECM, Armor, Engine,
  Maneuver) with derived stats, **four weapon slots** (count + type) and **three special
  slots**, a ship-icon picker, editable Name, and live **Ship Cost / Total Space /
  Available Space**, with Cancel / Clear / Build. This maps one-to-one onto the
  multiplayer `createDesign` command (size, the six component fields, weapons[4] with
  counts, specials[3], and the `availableSpace` validation) — a direct confirmation the
  protocol already models this screen.

## 4. Implications for the multiplayer / browser client

- **Reproduce the menu command set and shortcuts** above in the browser client. The
  current DTO client uses on-screen buttons; the Mac feel wants a menu bar (or an
  equivalent command palette) with these shortcuts bound — especially **⌘N Next Turn**,
  the screen-openers (⌘P/⌘D/⌘F/⌘T/⌘R), and object paging (⌘2/⌘3, ⌘4/⌘5).
  In multiplayer, "Next Turn" becomes the **Ready** action — bind it to the ⌘N muscle memory.
- **Window-based presentation**: the Mac port treats each screen as a window with the
  Map + Info split as the home layout. The browser client can echo this (a persistent
  galaxy view with the Info sidebar, other screens as panels/overlays) rather than the
  DOS full-screen mode-switching.
- **The screen contents already map to the protocol** we built: the five spending
  sliders → `setColonyAllocations`; six research categories → `setTechAllocations`;
  six ship-design slots → the ship-design commands; the Races/Audience screen →
  the diplomacy commands. So the Mac UX layers cleanly onto the existing DTO/command surface.

## 5. Not yet captured (future passes)

Fleet List (⌘F), Design View (⌘V), combat, the colonized-planet detail view, the
galactic council, and event/news dialogs. Add screenshots to [`mac-ux/`](mac-ux/) and
rows above as they're captured. Shortcuts for these are already in the table in §2.
