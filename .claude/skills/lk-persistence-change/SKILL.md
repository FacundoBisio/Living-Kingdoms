---
name: lk-persistence-change
description: Use when changing SavedData, NBT schemas or anything persisted in a world (settlements, quests, encounters, construction, citizens, professions, defense, economy, commerce, chronicle, onboarding).
---

Stores extend `net.minecraft.world.level.saveddata.SavedData` and live in `src/main/java/dev/livingkingdoms/**/` (`*SavedData.java`). Most write an int `schema_version` tag; `CommerceSavedData` uses `schema`. Per-entity NBT (`NpcIdentity`, `EncounterMember`) has its own `schema_version`.

1. Find the current schema version of the affected store (a `SCHEMA_VERSION` constant or a literal in `save`/`load`) and bump it in both places.
2. Add an explicit, tested migration from every prior schema; old worlds must load with no data loss. Keep accepting older versions in `load` (e.g. `version < 1 || version > SCHEMA_VERSION` rejects) and `setDirty` after migrating.
3. Reject malformed or future-schema files without overwriting them: `load` throws, and the `getOrCreate(DimensionDataStorage, Path)` factory must refuse to create an empty store when the `.dat` file exists (see `SettlementSavedData.getOrCreate`, `ConstructionStorageGuardTest`).
4. Add a JUnit test using a real `new DimensionDataStorage(dir.toFile(), null, null)`: save, `IOUtilities.waitUntilIOWorkerComplete()`, reopen, compare; plus an unsupported-schema test asserting the file bytes are unchanged.
5. Call `setDirty()` on every mutation; keep the store's `authority()`/server-thread check. No work off the server thread.
6. Note the schema change in the matching `docs/*.md`.
