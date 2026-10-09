# Professions and functional buildings — 0.12.0

Branch: `codex/professions-functional-buildings`. No commit or push is part of this milestone.

## Architecture and compatibility

`Profession` is an immutable citizen/settlement association with type, independent profession level/XP, workplace, active flag, work state, persisted cooldown/crop cursor/navigation failure count and a small optional trait set. `ProfessionType` includes UNASSIGNED, FARMER, GUARD, BUILDER, BLACKSMITH, MERCHANT, CAPTAIN and MAYOR. Only Farmer has progression and work behavior. Traits are vocabulary without gameplay effects; no traits are generated yet. Work policies and `FarmerProgression` are separate from Citizen and vanilla villager classes, so future professions can use different progression rules and handlers.

`ProfessionSavedData` adds `data/livingkingdoms_professions.dat`, schema 1. The 0.11 citizen/housing/immigration schema, entity UUIDs and settlement schema remain unchanged. Old active citizens lazily receive UNASSIGNED; the Mayor remains MAYOR. Existing citizen roles, names, shared levels, homes, quests, founder metadata, encounters and construction receipts are retained. Initialization and entity joins never spawn a replacement for an unloaded worker. A future or unreadable profession file fails closed instead of being overwritten.

`FunctionalBuilding` reuses the stable settlement/template/origin/rotation identity used for housing. It records kind, dimension, footprint, entrance, slots and active status. Capability policy maps Houses to housing, Town Hall/Core to administration, Farm to Farmer workplaces/Food, and Barracks/Blacksmith to future workplace vocabulary. Storage is prepared as a capability without a Warehouse implementation. Settlement indexes restrict workplace selection and roster queries to one settlement. Active worker assignments derive from the profession records through a rebuilt workplace index, so slot checks do not scan the roster. A building's active status means registered/available, not a simulation of structural damage.

## Farm and construction

The reproducible native `allied/plains/farm.nbt` module is 9×5×9: a solid foundation, irrigated wheat/carrot/potato field, small shelter, wooden door, barrel and lantern. Its controlled water source is the only fluid exception to the existing dry-module validation contract. Four rotations, doors, footprint and connected blocks are covered by the existing structure QA GameTest. Generation/terrain adaptation, protection checks, plot reservations, resources, timers, retry and deferred chunk completion reuse `ConstructionProject`.

An established settlement can choose Construction → Plan Farm. Founding still requires its Town Hall and first House. Default Farm cost: 32 logs, 16 stone and 2 iron ingots; duration: 2400 ticks (two minutes at 20 TPS). A completed Farm registers two worker places by default. Failed/interrupted projects keep the existing receipt/material rules. Converted villages can add LK Farms while preserving vanilla structures and inventories.

## Farmer behavior and Food

Explicit assignment requires an active citizen with a registered home and an active Farm slot in the same established allied settlement. The server chooses the first available registered Farm. New immigrants are never auto-assigned. Removing the job returns the citizen to UNASSIGNED, releases the workplace and keeps the citizen, home and accumulated Farmer progress. Confirmed death/conversion retires the job and preserves history without replacement.

Only loaded assigned villagers receive the work handler. Every twentieth entity tick performs a lightweight identity/cooldown check; a due opportunity inspects at most 16 cells in the assigned Farm's footprint and three-block crop height. The cursor is persisted; no global crop/entity/settlement scan occurs. Native navigation moves toward a mature crop, with a four-block working radius. Five rejected or stalled path attempts stop navigation and back off for 600 ticks before another target. No teleportation, chunk tickets or offline physical work is used. All intersecting Farm chunks must be loaded. Reloaded entity UUIDs resume the saved assignment.

Work hours are daytime 1000–11000. Outside them, loaded home navigation supplies RETURNING_HOME; vanilla sleeping entities can expose SLEEPING. Other states include IDLE, WORKING, BLOCKED, UNLOADED and STORAGE_FULL. This is a coarse schedule, not a complete bed/sleep simulation.

The explicitly assigned worker temporarily stops vanilla Brain behaviors to prevent its vanilla career farming routine from harvesting outside the LK workplace. Vanilla career, level, XP and offers remain unchanged. Removing the assignment refreshes the vanilla Brain and restores normal citizen/home restrictions. Unassigned converted villagers retain their vanilla Brain and trades. Trading, leashes, passengers and NoAI pause work.

Mature supported crops are replaced with age-zero crops; `mobGriefing` and NeoForge entity-destroy/place protection hooks are respected. Rejected placement restores the old crop without reward. No vanilla loot drops or duplicate item output are generated. A persisted opportunity is consumed before the world write; a matching profile receipt commits Food and XP once. Two workers see the real crop state on the server thread, so they cannot harvest the same mature block twice.

Default Food contribution is two units for wheat, carrot or potato, multiplied by `contributionPortion` (default 1.0) and rounded down. Food capacity defaults to 500. Only accepted units increase stock and lifetime Food added; overflow and the unconverted portion are discarded. Full storage pauses harvesting, and no items/XP are produced by idle checks. Replanting assumes one implicit seed/root per crop; remaining seed, poison potato and loot variation are intentionally omitted.

Each successful physical harvest grants 5 Farmer XP. Cumulative thresholds are 20/60/120/200 XP for levels 2/3/4/5; default cap 5. Each additional level reduces the base 120-tick cooldown by 5%, reaching 96 ticks at level 5. Citizen shared level and vanilla villager XP do not change. Other profession types have no generic XP multiplier.

## Immigration, quests and UI

Housing remains a hard gate. At Food ≤20, regular immigration chance is multiplied by 0.1; between 20 and 80 it interpolates smoothly; at ≥80 chance is normal. All thresholds and the minimum multiplier are configurable. Setting the minimum to zero can disable arrivals at low stock. Debug immigration bypasses chance/waiting, preserving housing, lifecycle and shared proposal limits. No Food consumption is implemented yet.

Food shortage supplies WHEAT weight to the existing dynamic quest generator. Existing FOOD_REQUEST delivery receipts contribute Food after actual items are consumed; replayed claims grant nothing. This adds no parallel quest engine. The profession store is validated before consuming quest state or items. Lifetime Food added includes deliveries as well as harvests, not a production-per-minute simulation.

Mayor → Citizens lists current/historical citizens; selecting a name shows shared level, profession, profession level/XP, home coordinates, Farm coordinates/occupancy and status. Assign Farmer and Remove job use server-authoritative requests. UUIDs are payload identity only, never visible labels. Native buttons, translated tooltips, scrolling reader, keyboard focus and narration are reused. The bounded snapshot shows at most 256 citizens, active first; larger rosters report the displayed/total count. English and Argentine Spanish are supported.

The native **FIRST_PROFESSION** advancement is **A Job to Do / Manos a la obra**. Each player receives it on their first successful assignment; repeat assignments do not re-award it. Its parent is first kingdom, since a player can assign an existing resident before accepting an immigrant.

Assignments are shared and serialized by the server thread. Clients cannot supply workplace, costs, XP, stock or level. View mode, per-player session nonce, physical Mayor/board/marker anchor, reach, territory, lifecycle and citizen identity are rechecked. A stale same-citizen request or full workplace is rejected with updated feedback; removal is idempotent. Food/XP use compare-and-set receipts. Config capacity reductions and missing housing/workplace metadata retire excess assignments deterministically.

## Configuration and debug

Settings are in the existing per-world/server `livingkingdoms-server.toml`, under `professions.farmer`, `professions.food` and `construction.farm`.

| Setting | Default |
| --- | --- |
| basicFarmWorkerSlots | 2 |
| workCooldownTicks / cropScanBudget | 120 / 16 |
| xpPerHarvest / xpStep / levelCap | 5 / 20 / 5 |
| baseCapacity | 500 |
| wheatUnits / carrotUnits / potatoUnits | 2 / 2 / 2 |
| contributionPortion | 1.0 |
| lowStockThreshold / healthyStockThreshold | 20 / 80 |
| lowStockImmigrationMultiplier | 0.1 |

Read-only operator commands require a player context inside an allied settlement:

```text
/kingdom citizen info
/kingdom settlement food
/kingdom farm debug
```

Normal gameplay uses Mayor UI. Existing `/kingdom immigration candidate` and `/kingdom construction complete` remain optional development helpers, and do not bypass assignment capacity or construction placement validation.

## Automated validation

Run with Java 21. The development GameTest launcher uses a 3 GB maximum heap for the full physical fixture suite and chunk serialization at shutdown:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
git diff --check
python tools/generate_settlement_modules.py --check
python tools/audit_settlement_modules.py
```

Final validation on 2026-10-08: **231 unit tests passed**, **95 GameTests passed**, with no failed/skipped unit tests. The original 214 unit tests and 86 GameTests are retained; this milestone adds 17 unit tests and 9 GameTests. `test`, `build`, `runGameTestServer` and `git diff --check` were run. Native module reproducibility and all ten modules' connectivity/four-transform asset audits passed; English/Spanish translations have matching sets of 303 keys. The final Gradle run ended BUILD SUCCESSFUL with every dimension saved and a clean GameTest server shutdown; no out-of-memory errors occurred in that run. The packaged artifact is `build/libs/livingkingdoms-0.12.0.jar`.

The navigation GameTest reproduced a rejected first path while the newly placed villager was still airborne. The handler now retains that crop target across failed retries until the five-attempt backoff, instead of skipping it on the first failure. The final test verifies a real path and entity travel before harvest; the crop test also verifies two workers cannot reward the same replanted crop. New unit coverage exercises profession vocabulary/traits, legacy lazy initialization, assignment validity, shared capacity/races, removal, death/history, food bounds, one-time XP/Food receipts, real SavedData file reopen, corrupt/future files, off-thread access, work bounds, level progression and immigration thresholds. New GameTests cover real native crops/replanting/no drops, native movement, GUI session/capacity races, Farm construction/registration, death, protected work, full storage, night scheduling, vanilla trades, unloaded Farm/reloaded entity and existing quest delivery integration.

## Manual QA on the gaming PC

Use the same 0.12.0 JAR on clients/server with Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Copy a 0.11 world for compatibility checks and keep its original backup. Automated file reopen and entity/GameTests do not replace this graphical Save & Quit test; earlier client startup on this PC failed inside the AMD OpenGL driver before gameplay.

1. Establish a settlement with a Charter. In wilderness, fund/finish Town Hall and first House until ESTABLISHED.
2. Open Mayor → Construction and build a second House; confirm a free housing place.
3. Wait for a traveler and accept them in Immigration. For shorter QA only, use `/kingdom immigration candidate`. Verify name/home and UNASSIGNED profession.
4. Choose Construction → Plan Farm, deposit materials, wait for completion and inspect field/water/shelter. Try near sloped/obstructed terrain to verify safe placement/refusal.
5. Open Mayor → Citizens using mouse and keyboard. Check the list, reader scrolling, tooltips/narration, English/Spanish and GUI scales 2/3/4; no UUID labels or overlapping buttons.
6. Select the immigrant and Assign Farmer. Confirm the first-profession advancement once.
7. Verify the home is unchanged, workplace coordinates are correct and worker count is 1/2.
8. During daytime watch the Farmer move toward the Farm without teleporting. Fully grown crops are needed; wait for native growth or use bone meal. Allow a work cooldown/cursor cycle.
9. Observe mature wheat, carrots and potatoes being replanted at age zero. A mature player crop field beyond Farm bounds must stay untouched by this worker; check that no item drops duplicate Food.
10. Refresh Citizens or use `/kingdom settlement food`; verify Food and Farmer XP increase only after a harvest, with citizen/vanilla career levels unchanged. Try `mobGriefing=false`, full storage and night behavior.
11. Save & Quit (or cleanly stop the dedicated server).
12. Reopen the same world/server with matching clients.
13. Confirm citizen name/UUID through operator inspection as needed, home, workplace, Farmer level/XP, Food and worker count persist; loaded workers resume and absent chunks do not respawn them.
14. Remove the profession through Citizens. Confirm UNASSIGNED, same home/name/trades, one released slot and retained Farmer XP when reassigned.
15. With enough Houses/citizens, assign a second Farmer; third assignment to a full two-slot Farm must be disabled/rejected. Use two clients to try the same citizen/last slot, then remove one and retry. Confirm one-time Food/XP/advancement. Kill a Farmer in a copied QA world and verify history/population/slot without replacement.

## Limits and next milestone

Only Farmers have playable profession behavior. No Guard combat, Builder animation, Smith crafting, Merchant economy, wages, taxes, tiers, wars or conquest were added. Native crop growth still needs ticking chunks/light; farmland can be trampled or edited by ordinary Minecraft mechanics. Registered building status does not detect demolition. Workers pause while chunks are absent and do not simulate offline production. Bed use, seed supply, physical product inventories, Food consumption, warehouses and rate forecasts remain future systems. Assignment chooses the first free Farm; explicit Farm selection/large-roster paging is future UI work.

Server-thread receipts prevent normal packet replay, multiple-player/multiple-worker duplication and clean-save reload resets. They are not a cross-file journal for arbitrary power loss between entity/chunk/SavedData writes. The manual graphical and real-client multiplayer checklist remains pending until tested on a working gaming PC.

Recommended next milestone: validate/balance the Farmer loop on the gaming PC, then add modest citizen Food consumption and an existing-quest shortage response. This would give the new resource a sustainable purpose before introducing a separate Guard/Builder/Blacksmith profession milestone.
