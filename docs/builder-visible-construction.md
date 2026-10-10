# Living Kingdoms 0.15.0 — Builder and visible construction

Branch: `codex/builder-visible-construction`. Changes are left uncommitted and unpushed.

## Gameplay and architecture

An established settlement can assign an active, housed, unassigned citizen as **Builder** through Mayor → Citizens → Choose profession. The existing citizen UUID, home, native villager, career and trades are retained. Builder level and XP are a separate profession career. The first successful assignment grants **Building the Future / Construyendo el futuro** once through vanilla advancement persistence.

A registered Town Hall/Core provides two Builder slots by default. Converted villages use their existing administrative plaza as a logical Town Hall workplace with the same slots: no vanilla structure is rebuilt and no extra NPC/building is fabricated. A previously unhoused converted resident can explicitly register one verified existing vanilla bed when assigned as Builder; this is real bed metadata, capacity one, never an invented house. The menu checks its remembered bed or nearby loaded HOME POIs only on demand. Workplace and bed capacity are shared across players.

Mayor → Construction can plan House, Farm, Barracks and Watchtower projects while another project exists. The default unfinished queue capacity is eight, with one actively building project. Duplicate unfinished or completed Barracks/Watchtowers remain disallowed. Reserved footprints prevent overlapping queued buildings. Funding a new expansion leaves it **READY** until a suitable loaded Builder is free. Funded projects start in reservation order; one Builder owns one project. The model retains configurable concurrency for future upgrades.

The **very first Town Hall and House of progressive founding keep the legacy funded timer**. This avoids needing a housed Builder before housing exists. Later expansion and new buildings in converted villages require Builders. Existing pre-0.15 projects retain their original funded timer and materials; completed buildings are untouched.

`ConstructionProject` now stores whether Builder work is required, the assigned citizen, accumulated work, last credited time, visible stage and rewarded milestone bits. Construction schema 2 loads schema 1 safely. Profession schema 3 loads schemas 1 and 2, preserving Farmer/Guard careers, Food and workplaces. Defense, Security, threats, quests, reputation and settlement storage keep their existing schemas and behavior.

## Stages and work

| Stage | Visible representation |
|---|---|
| Reserved | Existing project marker and reserved footprint |
| 0 | Foundation outline and small native log piles |
| 1 | Lower structure |
| 2 | Walls/main structure |
| 3 | Roof and finishing |
| 4 | Authoritative complete native building and paths |

Intermediate masks derive from the **persisted blueprint**, including transformed bounds and supports, for House, Farm, Barracks, Watchtower and Town Hall in all four rotations. Connected geometry removes unsupported islands. Inventories, fluids, farmland/crops, doors, beds and block-entity decoration wait for final placement. Temporary pieces are replaced by the original native template, including its NBT; no models or animation libraries are added.

Builders follow a native navigation goal to a safe exterior work point near the doorway, look at the work area and swing an arm after credited work. The cached point uses the existing ground height and stays clear in the final blueprint; finishing asks the worker to approach it closely before retrying placement. A configurable interval defaults to 20 ticks. Work requires a live, correctly registered and assigned villager near the work point, with the complete project footprint/path chunks loaded. Traveling, sleeping, trading, being leashed/riding, direct danger and unloaded chunks credit no work. Idle and threatened Builders restore vanilla Brain behavior; settlement-wide THREATENED does not itself stop a safe worker.

One interval at most is credited per check; there is no work catch-up for travel, unloaded time or server downtime. Level 1 has normal speed. The default level cap is five and the maximum additional work rate is 25%, so the estimated duration falls by up to 20%. Levels use the existing cumulative profession progression style. Defaults award five XP for each intermediate milestone 1–3 and twenty for completion. There is no per-tick XP; persisted receipts prevent repeating rewards.

Removing a Builder or confirmed death returns their project to READY, preserving funds, reservation, stage and accumulated work. A replacement resumes that same project. Entity unload preserves ownership and pauses work. Save/reload preserves the work schedule and stage, with no restarting or duplicate final registration.

## Authority, protection and performance

All mutations run on the Minecraft server thread. Storage compare-and-set rejects stale work/deposit/placement calls; an exclusive Builder index prevents double ownership. GUI citizen revision checks reject stale assignment/removal. Final placement and registration use a reversible transaction. XP is granted after committing its project receipt.

Every stage checks loaded chunks, world border, transformed scope, protection hooks, exact previous owned states and nearby entities that would be hit by changed blocks. Player edits or inventories cause safe refusal instead of replacement. Whole exact target stages can be adopted after an interrupted save; partial or edited targets are refused. Previously registered shared paths are accepted only when their states match the new project's planned paving. Minecraft saves chunks and SavedData in separate files; arbitrary process/power loss can leave a blocked partial site requiring inspection. This implementation prioritizes preserving edits and materials over automatic repair.

There are no world entity scans, forced chunks, per-tick block placement or full settlement scans. Loaded Builders do local interval checks; funded settlement queues are processed through a bounded round-robin index. Blueprint stages, validated geometry and unique chunk coordinates use bounded caches. Changed stages perform the heavier local validation only a handful of times per project. Existing construction demand augments resource-shortage quest weighting without changing quest storage.

The GUI shows human Builder names, levels, XP, home, workplace, current project/status, supplied/required resources, visible stage, authoritative work percentage, estimated remaining **work** time and ACTIVE/NEXT entries. Refresh updates snapshots; paused work does not animate a fake countdown. Ordinary players do not need debug commands.

## Development commands

Operator permission level 2 and a player inside the settlement are required:

```text
/kingdom builder info
/kingdom construction info
/kingdom construction stage
/kingdom construction advance
/kingdom construction advance 0
/kingdom construction advance 1
/kingdom construction advance 2
/kingdom construction advance 3
/kingdom construction advance 4
/kingdom construction complete
```

`advance` moves to the next stage; its optional 0–4 argument selects a later stage. It can provide missing test materials and bypass Builder work. Debug milestones consume their reward receipts without granting Builder XP. `complete` requires supplied materials. Neither command forces chunks or bypasses protected/tampered plots. Run these in a disposable test world.

## Automated validation

Validation on 2026-10-09, Java 21.0.8, Minecraft 1.21.1 and NeoForge 21.1.252:

| Check | Result |
|---|---|
| Unit tests | 328 passed; zero failures, errors or skips (39 added) |
| Complete GameTest suite | All 130 required tests passed (15 added) |
| Gradle build | BUILD SUCCESSFUL, exit 0; production and sources JARs generated for 0.15.0 |
| `git diff --check` | Passed; no whitespace errors |
| Language resources | 394 keys in both English and Spanish; identical key sets |
| Production JAR | Includes FIRST_BUILDER; contains no GameTest classes |

Command: `./gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer`. The final complete run exited successfully after all dimensions finished saving. Unit reports are in `build/reports/tests/test/index.html`; the complete native suite result is in `runs/gametest/logs/latest.log`. Client graphical acceptance remains pending, as requested.

Existing tests are retained, including explicit future-schema rejection. New tests cover career/save compatibility, capacity/assignment/CAS, queue ordering/exclusive ownership, work/stage/XP receipts, native navigation/danger/death/unload, actual stage transactions, final native registration, verified converted bed bootstrap and all rotations. The native stage audit checks all 20 building/rotation combinations across all five stages, exact final template blocks, connected geometry and no writes beyond transformed bounds. Occupancy and reload fixtures explicitly wait for Minecraft's entity tracking instead of assuming fixed chunk-loading timings. Three existing physical fixtures were updated for READY/queue semantics and restoring their synthetically reserved marker; none were removed.

## Exact manual QA on the gaming PC

Use Minecraft 1.21.1, Java 21, NeoForge 21.1.252 and `build/libs/livingkingdoms-0.15.0.jar` on all clients/server. Copy an existing 0.14 world for migration checks; keep the original backup. Graphical acceptance is pending: this development PC cannot perform it.

1. Start a new disposable world with cheats enabled and safe dry ground. Establish with a Charter or `/kingdom settlement generate`. Walk to its Mayor. In progressive founding, supply the first Hall and House and verify the timer still finishes without a Builder.
2. Open Citizens in English and Español (Argentina). Select a housed unassigned citizen, choose profession, then Builder. Verify readable name, citizen/Builder levels, XP, home, workplace and project/status; no UUIDs. Verify the advancement appears once.
3. Assign the second Builder; a third eligible citizen must show the workplace capacity restriction. Mayor, inactive citizens, unhoused citizens and already assigned Guards/Farmers must not become Builders through this action.
4. Plan House then Farm before completing either. Fund both with real inventory. Verify supplied counters consume only required items, all projects stay visible, and only one is ACTIVE. Repeat from a second client: shared deposits/assignment cannot duplicate materials or ownership.
5. Watch the first Builder walk to the site. Verify stage 0 fits the reserved footprint. Stand at a safe distance and observe lower structure, walls and roof. Refresh Construction: work increases while the villager is near; ETA is an estimate, not a countdown while paused.
6. Move the Builder away with normal navigation or a test teleport and confirm progress pauses until it returns. Leash it, open trades, let it sleep, then release those conditions and confirm resumption. No instant catch-up should occur.
7. Let a nearby hostile mob strike the Builder, while keeping it alive. Verify work pauses and native panic/flee resumes. Clear danger and confirm it can return. Check that its original vanilla trades/career remain available after removing the job.
8. Remove the Builder during stage 1 or 2. Verify READY, unchanged materials/percentage/stage and no erased site. Assign another eligible Builder and verify the same project resumes. Repeat using an actual lethal mob hit; housing and workplace occupancy must free correctly.
9. Leave far enough that **all clients** unload the site (no `/forceload`). Wait at least a minute. Return and verify no off-screen completion or accumulated work. Repeat after Save & Quit/reopen during stages 0, 1, 2 and 3.
10. Complete the House, then Farm, Barracks and Watchtower normally. Verify exactly one registered building, final door/bed/container/crops/water appear appropriately, temporary marker/log piles disappear, and completion feedback happens once. Barracks/Watchtower effects and Farmer assignment still work.
11. For quick visual passes, plan a project and run `advance 0`, `advance 1`, `advance 2`, `advance 3`, `advance 4`, taking screenshots at each stage. Check zero XP from skipped debug milestones and refusal to repeat/regress a completed stage.
12. Use `/kingdom construction info` to read the saved plot rotation. Repeat planning in independent test settlements until **NONE, CLOCKWISE_90, CLOCKWISE_180 and COUNTERCLOCKWISE_90** have been recorded for each constructible building. The planner controls rotation; there is no force-rotation command. The exhaustive native GameTest separately audits all 20 building/rotation combinations.
13. At every recorded rotation, inspect all four corners from ground and above. Check roof/eaves connection, no floating wooden planes, stage supports, paths meeting doors, unchanged blocks outside the reserved footprint, and no overlap with completed/queued buildings. Town Hall stages can be checked through its initial founding project with operator `advance`; normal founding remains the legacy exception.
14. Place a distinctive player block where a later stage would write; also test a chest containing items. Advance and verify safe refusal with the block/items unchanged. Remove the obstruction and use Construction retry. Do not break original stage blocks for this test unless testing tamper refusal deliberately.
15. Stand where a changed wall/roof block would be placed: work must wait rather than suffocate the player. Move clear and verify resumption without losing materials. Repeat with a resident temporarily inside the footprint.
16. Convert a vanilla village containing trades, complete beds and filled chests. Before assigning a Builder, vanilla structures should still contribute no invented LK housing. Select a converted resident with a real remembered/nearby unoccupied bed and assign Builder: verify a single bed home and plaza workplace are registered without changing any bed, trade or chest. Construct the first LK House/Farm using normal Builder stages. Save/reload, then break that registered bed and check work pauses/releases safely; unloaded beds must not be treated as destroyed.
17. Load the copied 0.14 world with a partially supplied project, a funded building timer, completed buildings, Farmer/Guard careers, Food/Security, local defense quest and reputation. Compare these before/after a save/reload. The old timer must retain its remaining time and all receipts/items.
18. Check GUI scales 1–4 at 1280×720 and 1920×1080, both languages, long citizen/settlement names, keyboard Tab/Enter/Escape, tooltip/narration and scrolling. Funding/retry must remain accessible while planning another project; controls must not cover rows or clipped detail text.

## Limits and next milestone

One Builder owns each project. Natural founding's first Hall/House and migrated timers do not acquire worker-driven intermediate stages automatically. No repairs, construction destruction, raids, siege, walls/gates, Blacksmith gameplay, economy or conquest are included. Builders use vanilla pathfinding and may pause on obstructed terrain; unblock the route/site rather than expecting teleportation. The storage keeps the existing 64 retained-project limit per settlement; the configured queue limit counts unfinished projects only. UI screenshots, real gaming-PC Save & Quit and multiplayer visual acceptance remain manual QA.

Next recommended milestone: complete this gaming-PC visual and save/reload QA, then add small worker/supply shortage quest hooks using the existing construction API. Do not begin raids or another profession automatically.
