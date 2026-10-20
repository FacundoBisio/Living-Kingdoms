# Kingdoms Alive 0.20.0

> **Historical planning record, not a release.** The current version is 0.18.1. Phases 1–2 below (storage, consumption, economic buildings) shipped through 0.17.0 and 0.18.0; relations, diplomacy and the extended progression chapter remain unimplemented. Test counts below are from that time.

Historical preparation record. The requested release at the time was [Living Villagers 0.17.0](living-villagers-v017.md), on `codex/living-villagers-v017`; the progress below was kept there. This document does not declare 0.20.0 complete.

Base: local main `48a2a4c`, which includes 0.16.1; the remote was still at 0.16.0. Single branch: `codex/kingdoms-alive-v020`. Those earlier commits were neither discarded nor published.

Initial audit: Charter, narrative onboarding/names, UI without the inherited blur pass, modular HUD, citizen identity/housing, Farmer/Builder/Guard, construction reservations and stages, encounters/defense and rewards already exist. Those systems are kept. Missing: economic storage with consumption, three productive professions, support buildings, relations/trade, central management and a progression that connects them.

Implementation by thematic commits:

1. Shared resources and consumption, reusing FoodStock and the existing inventory exchanges.
2. Functional buildings, safe upgrades/expansion and funding from storage.
3. Lumberjack/Miner/Blacksmith, schedules/shelter and repair, on ProfessionSavedData and native navigation.
4. Discovered relations and real trade between storages, with server authority.
5. Central management and an integrated progress chapter, keeping the old identities/rewards.
6. Integrated tests, performance, migration and final JAR.

The 0.16.1 UI polish is used from the start; storage comes before the new professions so their production has a single destination. The functional minimums for economy and diplomacy will use configurable limits, loaded chunks and transactions, with no destructive extraction, unbounded simulation of distant kingdoms or wars. Each scope decision and the test evidence will be noted briefly when its phase completes. Graphical acceptance stays pending until the Minecraft client is tested.

Status: audit done; implementation in progress. **Not yet an approved 0.20.0 release.**

## Phase 1: storage and consumption

Persistent shared accounts, real inventory exchanges, revisions against repeated operations, storage limits and periodic consumption by population were implemented. FoodStock is still the only food source; depositing wheat does not increase harvested production. Only the registered, loaded Mayor triggers consumption, with staggered checks and no offline simulation. New corrupt/future data is rejected without overwriting files.

Provisional survival access: `/kingdom stock deposit food|wood|stone|iron` and `/kingdom stock withdraw food|wood|stone|iron|equipment`. Requires kingdom membership and being inside the territory. Moves up to 16 units (one armor piece per withdrawal), consuming nothing when there is no capacity, resources or space. Wood accepts logs of different types and withdraws normalized units as oak. Graphical management and armor production will be connected in later phases.

Validation for this phase: **379 JUnit and 147 GameTests passed**, Java 21 compilation and `git diff --check`. Isolated world: `runs/gametest-v020-storage`. The new tests cover full inventory, permissions, old/multiplayer transactions, one-time consumption and a real read of the saved file. The development version was set to 0.20.0; that does not certify the full update.

## Phase 2: buildings and expansion

Four new native templates (Warehouse, Lumberjack Hut, Mine Workshop and Market) keep the ten earlier modules intact. Blacksmith reuses its existing template; its iron cost includes the anvil's value to avoid getting free iron by dismantling it. The mine uses decorative stone with no extractable ores. The new buildings go through the same reservations, protection, terrain, markers and Builder stages.

Storage funds real projects. Saved upgrades increase Warehouse capacity, work slots and Watchtower detection; the Town Hall also raises the kingdom level. Workshop levels connect with production in the next phase and Market levels with trade. Upgrades are functional; they do not replace the house model or its inventories. Housing grows through additional houses, avoiding silently changing occupancy/beds of earlier saves. The economy loads both its initial schema and the extended one with levels.

Expansion: +16 radius, up to the configurable limit, with a level 2 Town Hall, safety, a finished queue, resource cost, neighbor check and world border. It changes the effective territory without editing terrain or loading chunks. Provisional access: `/kingdom development plan warehouse|lumberjack_hut|mine|blacksmith|market`, `fund`, `upgrade <building UUID>` and `expand`; the central interface will remove the need to identify buildings by UUID.

Validation: **383 JUnit and 149 GameTests passed**, native modules in four rotations, level persistence, economy migration, territorial index expansion, real capacity/slot increase and one-time project payment. Isolated world: `runs/gametest-v020-buildings`. The geometry auditor and generator verify the fourteen modules with no disconnected parts; graphical acceptance pending.

The worldgen JSON helper was run and reported no compatible files: here structures are NBT placed by the existing planner, not worldgen JSON registries. They were validated through the NBT auditor and Minecraft's real codecs/placements, without adding fake definitions to satisfy that helper.
