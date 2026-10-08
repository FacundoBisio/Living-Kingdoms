# Adaptive settlement generation

**Historical adaptive-generation milestone.** Version 0.8.0 replaces its module architecture, adds a third house variant and optional tower, and expands `generate here` into a local search. Current sizes, visuals, NPCs, UI and manual QA are documented in [village identity and UI polish](village-identity-ui-polish.md). The planning/transaction design below remains the foundation.

Branch: `codex/adaptive-settlement-generation`. This milestone replaces new physical settlement generation while preserving the existing gameplay systems. Housing and Citizens are not implemented.

## Placement architecture

`BuildingCatalog` loads validated native Minecraft NBT modules. `SettlementLayoutPlanner` selects a compact core, independently validates four nearby plots, reserves spacing, assigns cardinal rotations, and plans local paths. It returns an immutable `SettlementLayout` containing the exact original block states, including foundation anchors. Failed candidates have no world or SavedData side effects.

`SettlementPlacement` rechecks chunk availability, world border, territory, original states and entities before its first write. It places supports, clears only validated module volumes, uses native `StructureTemplate.placeInWorld` with rotation, verifies exported states and applies paths. Placement stays reversible until `SettlementGenerator` records the settlement and optional layout metadata and commits it. A module/write/storage failure restores original states and removes newly created block entities. Mayor creation follows the physical transaction using the existing UUID association service.

This is an ordinary synchronous rollback, not a crash-safe transaction spanning Minecraft chunk files and SavedData.

## Core and modules

The core is **13 Ã— 13**, seven blocks high. It contains a 9 Ã— 7 Town Hall, a plaza, one lodestone at local `(6,1,9)`, one Quest Board at `(9,1,9)` facing west, a Town Hall sign and safe Mayor positions. The founding coordinate is the lodestone. Paths enter the plaza at local floor positions `(0,0,9)`, `(12,0,9)` and `(6,0,12)`.

All current assets live under `data/livingkingdoms/structure/allied/plains/`:

| Module | Footprint | Default height variance | Default maximum infill |
| --- | --- | --- | --- |
| `core` (includes Town Hall) | 13 Ã— 13 | 2 | 2 |
| `town_hall` (independent reusable module) | 9 Ã— 7 | 2 | 2 |
| `house` | 5 Ã— 5 | 3 | 3 |
| `house_variant` | 5 Ã— 7 | 3 | 3 |
| `blacksmith` | 7 Ã— 7 | 3 | 3 |
| `barracks` | 9 Ã— 7 | 2 | 2 |

The initial village requires the core, two house variants, Blacksmith and Barracks, each with a safe path. Building footprints need not share an elevation. Golden-angle candidate sampling gives deterministic position variation rather than fixed grid slots. Entrances face toward the plaza using 0/90/180/270-degree native rotations, including doors, furnace/anvil directions, beds and roof stairs.

`ArchitectureStyle` records PLAINS, TAIGA, DESERT, SNOW or SAVANNA intent from the founding biome. Every style currently falls back to the plains placeholder assets. A future catalog can select style-specific native resources without changing the planner or saved building identity.

## Terrain, paths and limits

Planning reads `MOTION_BLOCKING_NO_LEAVES`, then skips bounded low vegetation/snow and validates tagged dry, solid ground. Every module floor rests above its own highest ground column. Short cobblestone supports fill the lower columns down to their original ground; no terrain excavation occurs. The configurable `foundationDepth` checks consecutive solid ground beneath every column. Air, tagged low vegetation and leaves inside the validated volume may be cleared. Trunks, existing construction, inventories, fluids, entities, cliffs and unsupported sites are rejected locally.

A bad house plot can be skipped in favor of another nearby plot. Water outside the chosen core, modules and paths is untouched. The core itself must be safe; a lake or ravine through the core still rejects that center.

Paths use bounded local surface search, four-direction steps, a six-block detour margin and at most 512 nodes per candidate by default. Routes allow at most one block of height change between adjacent cells, replace ground with dirt path or gravel, add cobblestone stairs on ascending steps, and support short raised doorsteps. They avoid buildings, water, cliffs and protected obstacles. Existing saved paths can be reused during later additions without clearing their stairs. Paths are one block wide; there are no bridges, tunnels or global routing.

Configuration is in `KingdomConfig` and `SettlementGenerationConfig`, under `[generation]` and `[generation.<module>]`. Defaults: search centers 32â€“64 blocks from the caller; plot centers up to 30 blocks from the marker; 64 candidate attempts per module; two clear blocks between footprints; solid foundation depth one. The existing `maxTerrainVariation` key still limits the core together with `core.maxHeightVariance`. Here mode adds one block to local variance/infill tolerances, capped at four. It never bypasses water, entities, territory, border or protected construction checks.

Every footprint corner, path cell and support must stay inside the configured circular territory (default radius 48). Very small configured radii can fail with insufficient plots/territory diagnostics. The planner does not enlarge a requested territory or force-load chunks. Generation runs only on explicit requests, with bounded searches; no generation work runs each tick.

Natural ground tags cannot distinguish player-placed dirt/stone from natural terrain. Deep caves hidden beneath an otherwise solid surface are not exhaustively surveyed. Dense forests may still have insufficient connected clearings; large mountains and lakes are intentionally unsuitable.

## Developer commands

All generation modes require permission level 2 and a player:

```text
/kingdom settlement generate
/kingdom settlement generate debug
/kingdom settlement generate here
/kingdom settlement generate here debug
```

`debug` adds compact counts for center and plot candidates and separate rejection totals: slope, water, unloaded chunks, settlement overlap, insufficient connected plots, border, territory, obstacles/spacing, entities, unsupported ground/build height and paths. One center can test many plots, so plot counts are separate from center counts. Normal generation has no verbose diagnostics.

`here` attempts one core four blocks north of the current player, keeping that player outside its raised footprint. Nearby modules are still searched normally; the command does not force a placement. This is operator testing tooling, not normal gameplay behavior.

## Persistence and future growth

Settlement schema 1, UUIDs, names, territories, level/population and all quest/reputation/Mayor stores retain their existing identities. New entries optionally contain a `layout` compound recording style, template IDs, kinds, origins, rotations, footprints, entrances, plaza ports and path floor coordinates. Old records without `layout` load unchanged; old physical blocks are not regenerated or moved. Corrupt optional layout data is rejected through the existing guarded loader.

`SettlementLayoutPlanner.planPlot` validates a specific module request. `planAddition` searches an existing territory using caller-supplied occupied footprints and plaza ports. `SettlementGenerator.planBuildingAddition` uses saved metadata; `applyBuildingAddition` applies and records its single-module plan with the same reversible transaction. These APIs do not change population, housing capacity, quests or reputation. Layout-free legacy settlements require an explicit survey/reservation step before the convenience growth API can be used; no automatic conversion is attempted.

## Rebuild or replace native modules

```powershell
python tools/generate_settlement_modules.py
python tools/generate_settlement_modules.py --check
```

The script uses only Python's standard library and the existing placeholder authoring helpers. Ordinary Gradle builds consume the checked-in NBTs and require neither Python nor WorldEdit. The legacy 31 Ã— 31 asset/script remain available for compatibility tests and historical authoring documentation.

To replace a module with WorldEdit-authored art:

1. Build/paste in a disposable Java 1.21.1 design world. Keep a complete solid floor at local Y=0, a footprint of 3â€“15 blocks per side, height 3â€“12, and all roof overhangs within that footprint. Keep its doorway/opening at the south-center edge, at local Y=1/2. Exports must have one palette, no entities, liquids, structure blocks, structure void or jigsaw blocks.
2. For `core`, preserve the 13 Ã— 7 Ã— 13 size, marker `(6,1,9)`, single board, plaza ports and safe Mayor positions. Other modules must have no lodestone or Quest Board.
3. Export with a vanilla structure block, name `livingkingdoms:allied/plains/<module>`, and Include entities disabled. Copy the native `.nbt` from the world's `generated/livingkingdoms/structures/allied/plains/` to the mod's `src/main/resources/data/livingkingdoms/structure/allied/plains/` directory. A `.schem` requires conversion, not renaming.
4. Test all four rotations and rerun GameTests in a fresh world. World-generated exports can shadow packaged assets; move that export out of the test world or use a fresh world when verifying the packaged replacement.
5. Update/retire the generator's byte check for hand-authored replacements so it cannot overwrite the new art accidentally.

WorldEdit remains development-only.

## Manual Minecraft QA

Launch `.\gradlew.bat runClient`, create a creative world with cheats, and let nearby chunks finish loading. Use locations at least 160 blocks apart to avoid territory overlap. For each environment, run `generate debug`; inspect the reported marker and all five modules. If no center works, try `generate here debug` from a visible clearing and retain its rejection counts.

| Environment | Exact test setup | Expected result |
| --- | --- | --- |
| Plains | Stand in a dry grass clearing with about 60 blocks of visible loaded surroundings. | Generation should succeed; marker, Town Hall, two houses, Blacksmith, Barracks, connected paths and Mayor exist. |
| Mild slope | Choose terrain changing 1â€“2 blocks across the core and no more than 2â€“3 across individual plots. | Should succeed with short foundations and different building elevations; original ground beneath modules remains. Walk entrances and stairs. |
| Forest | Use a clearing large enough for the 13 Ã— 13 core, with scattered trees around it and some grass/leaves in proposed plots. | Should succeed if four connected plots exist between trunks. Grass/leaves may clear locally; trunks and inventories remain. Dense continuous woodland may fail with obstacles/insufficient plots. |
| Hill | Test a gentle terrace first, then a steep summit or ravine edge. | Terrace may succeed. A >2-block core variance (>3 in here mode), >2/3-block building variance, major cliff or missing route should fail without flattening or partial structures. |
| Near water | Stand on dry land with a small pond beside a likely outer plot. Then try here mode over deep water or a shoreline crossing the core. | Dry core should succeed when alternative dry connected plots exist, leaving the pond intact. Water through the core/deep lake must fail; no draining or lake fill. |

For each success: `/kingdom settlement info` must show its UUID; inspect the board, accept/complete the existing main/resource quest flow, verify reputation and Mayor dialogue, then Save and Quit/reopen. UUID, board, Mayor association, paths, quests and reputation must survive. Run generation again in the plaza: occupied nearby territories should fail. Confirm a nonoperator cannot invoke any generation mode and that normal `generate` feedback contains no developer counts.

For every refusal, inspect that no supports, paths, structures or extra settlement record appeared. For here mode, the player should remain outside the core and must not be enclosed by a building.

Next recommended milestone: a separate Housing implementation using the saved plot reservation and building-addition APIs. Citizens require their own later design and are not started by this change.

Automated results and report locations are recorded in [validation](adaptive-settlement-generation-validation.md).
