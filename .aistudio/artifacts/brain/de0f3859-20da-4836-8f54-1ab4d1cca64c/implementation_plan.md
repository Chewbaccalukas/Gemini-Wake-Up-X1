# Adventure Drawer — Quest Filter Toggle & Rarity Glow Indicator System

Upgrades the Infinite Adventure Engine drawer with a persistent **All / Active / Completed** quest filter toggle bar (featuring live quest count badges) and a multi-tiered **Visual Rarity Glow System** on the 3-column Inventory Grid (Common Grey, Rare Blue, Epic Purple, and Legendary Orange, with an animated pulsing aura on Epic and Legendary items).

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following design and behavior decisions incorporate your confirmed choices from Phase 1:

- **Confirmed Decision 1 — Rarity Glow Behavior**: **Pulsing aura on Epic and Legendary with static glow on Common and Rare**.
  - `Common` (Grey `#94A3B8`) and `Rare` (Blue `#38BDF8`) items render a crisp static outer glow halo and gradient border.
  - `Epic` (Purple `#C084FC`) and `Legendary` (Orange `#F59E0B`) items render a continuous, smooth `InfiniteTransition` pulsing radial/border glow aura with a corner rarity tier badge.
- **Confirmed Decision 2 — Quest Filter Toggle & Persistence**: **Show live count badges on chips and persist selection**.
  - The Quests tab displays a 3-way segmented filter toggle (**All (N)**, **Active (N)**, **Completed (N)**) that persists the user's selected `QuestFilterMode` in `AdventureGameState` across drawer open/close sessions and chat switches.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. Adds a segmented **Quest Filter Toggle** (`All`, `Active`, `Completed`) to the Quests tab in the Adventure Drawer so players can isolate current objectives or review completed milestones at a glance.
  2. Introduces a custom Compose `drawBehind` + gradient border **Rarity Glow System** in the 3-column Inventory Grid that visually differentiates item tiers by color and aura intensity (`Common: Grey`, `Rare: Blue`, `Epic: Purple`, `Legendary: Orange`).
- **Target Audience / Persona**: Players managing growing RPG quest logs and multi-tier procedural loot collections who want immediate visual hierarchy and effortless organization.
- **Key Value**: Makes high-tier loot drops (`Epic` and `Legendary`) feel genuinely special with an animated pulsing halo while keeping long campaign quest logs organized.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Quest Filter Toggle (`All` / `Active` / `Completed`)**:
   - Located directly below the Campaign Quest Milestones progress card in the Quests tab.
   - Displays three interactive filter chips with live counts: `All (total)`, `Active (activeCount)`, and `Completed (completedCount)`.
   - Selecting a filter smoothly updates the visible quest cards; if a filtered view has no matching quests (e.g., no completed quests yet), a tailored empty-state card appears with a helpful prompt.
   - Selected filter mode is stored in `AdventureGameState.questFilter` (and persisted in `GameSettings`) so reopening the drawer preserves your view.
2. **Inventory Rarity Glow Indicator System**:
   - Every item card in the 3-column Inventory Grid resolves its rarity tier (`Common`, `Rare`, `Epic`, `Legendary`) and applies a custom `Modifier.rarityGlowBorder(...)`:
     - **Common (`#94A3B8` Grey)**: Subtle 1.5dp slate border with a soft 4dp static outer halo (`alpha = 0.22f`).
     - **Rare (`#38BDF8` Blue)**: Luminous 1.8dp sapphire border with a 7dp static outer glow (`alpha = 0.35f`).
     - **Epic (`#C084FC` Purple)**: 2.dp amethyst gradient border with an animated pulsing 11dp outer aura (`alpha` oscillating between `0.30f` and `0.72f`).
     - **Legendary (`#F59E0B` Orange)**: 2.5dp solar-orange/gold gradient border with an animated pulsing 14dp outer aura (`alpha` oscillating between `0.40f` and `0.88f`) and a subtle inner shimmer gradient.

### Visual Identity & Theme
- **Rarity Palette**:
  - `Common`: Slate Grey (`#94A3B8`)
  - `Rare`: Arcane Blue (`#38BDF8`)
  - `Epic`: Void Purple (`#C084FC`)
  - `Legendary`: Mythic Orange (`#F59E0B`)
- **Interactive Feedback & Motion**: Smooth `rememberInfiniteTransition` sine-wave glow breathing (1800ms cycle) for `Epic` and `Legendary` items, and animated state transitions when switching between `All`, `Active`, and `Completed` quest views.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Canvas `drawBehind` Outer Glow Halo + Gradient Border**
  - *Chosen Approach*: Render the glowing border using a custom `Modifier.drawBehind` rounded-rect radial/halo pass combined with a `Brush.linearGradient` border stroke.
  - *Why*: Works reliably across all Android API levels (API 24–36) and Robolectric unit tests without relying on hardware-only `RenderEffect` blur APIs that can clip inside grid cells.
- **Decision 2: Persisting `QuestFilterMode` in `GameSettings` / `AdventureGameState`**
  - *Chosen Approach*: Store `questFilter: QuestFilterMode` (`ALL`, `ACTIVE`, `COMPLETED`) in `GameSettings` and `AdventureGameState` with Room JSON serialization.
  - *Why*: Guarantees the user's chosen quest filter persists across drawer open/close cycles, configuration changes, and app restarts.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram
```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         GameDrawerSheet (Right Drawer)                       │
│  ┌────────────────────────────────────┐  ┌────────────────────────────────┐  │
│  │     3-Column Inventory Grid        │  │         Quests Tab             │  │
│  │  • RarityTier Resolver             │  │  • Milestone Progress Card     │  │
│  │    (Common Grey, Rare Blue,        │  │  • 3-Way Filter Toggle Row     │  │
│  │     Epic Purple, Legendary Orange) │  │    [All (N)] [Active (N)]      │  │
│  │  • Modifier.rarityGlowBorder()     │  │    [Completed (N)]             │  │
│  │    - Static Halo (Common / Rare)   │  │  • Filtered Quest Card List +  │  │
│  │    - Pulsing Aura (Epic/Legendary) │  │    Tailored Empty State        │  │
│  └─────────────────┬──────────────────┘  └───────────────┬────────────────┘  │
└────────────────────┼─────────────────────────────────────┼───────────────────┘
                     ▼                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│          MainViewModel & AdventureGameState (Room SQLite Persistence)        │
│  • QuestFilterMode (ALL, ACTIVE, COMPLETED) persisted in GameSettings        │
│  • setAdventureQuestFilter(filter: QuestFilterMode)                          │
│  • filteredQuests computed property on AdventureGameState                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **`QuestFilterMode`**: Enum (`ALL`, `ACTIVE`, `COMPLETED`) with `displayName` (`"All"`, `"Active"`, `"Completed"`).
- **`RarityTier`**: Enum (`COMMON`, `RARE`, `EPIC`, `LEGENDARY`) resolved from `InventoryItem.rarityColorHex` or `rarityLabel`, providing canonical hex/Compose colors (`Grey #94A3B8`, `Blue #38BDF8`, `Purple #C084FC`, `Orange #F59E0B`), border widths, glow radii, and `isAnimatedPulse: Boolean`.
- **`AdventureGameState.filteredQuests`**: Returns all quests for `ALL`, `!it.isCompleted` for `ACTIVE`, and `it.isCompleted` for `COMPLETED`.
