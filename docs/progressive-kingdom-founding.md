# Progressive Kingdom Founding — 0.10.0

Branch: `codex/progressive-kingdom-founding`. This milestone introduces gradual wilderness development and does not start Housing/Citizens. No commit or push is part of the delivery.

## Gameplay and lifecycle

A wilderness Kingdom Charter immediately registers a named allied settlement with origin `FOUNDED`, founder UUID, creation time, territory and lifecycle `FOUNDING`. Its physical starting area is a **9×9 founding camp**: lodestone, Quest Board, canvas shelter with two beds, campfire, barrel, crafting table, Mayor and two starting residents. The camp uses the same terrain validation, short supports, native template placement and rollback as existing generation. It has no permanent Town Hall, houses, Blacksmith or Barracks.

The first reserved project is the Town Hall. Completing it makes the first permanent House available. Completing both transitions the same settlement UUID to `ESTABLISHED`. The completed House's kind, template, position, rotation and bounds are appended to layout metadata for future housing queries; there is no capacity or citizen assignment. The camp remains as the founding plaza. Utility buildings are optional future projects, not a requirement for establishment.

`EXPANDING`, `UNDER_ATTACK` and `LIBERATING` are enum values and localized labels only. There are no associated gameplay systems. Origins remain independent of lifecycle. Debug generation and disabling progressive founding retain the old full settlement route.

Converted vanilla villages start `ESTABLISHED`, preserve their houses/inventories/beds/bells and activate their Mayor and board without a camp or construction chain. They qualify for the first-kingdom advancement. Existing conversion layout behavior remains unchanged: later additions need a survey of vanilla buildings before a planner can safely reserve their space. Future expansion policies can use the same generic project/domain, blueprint, delivery and completion services with surveyed metadata.

## Projects, resources and physical placement

`construction.domain.ConstructionProject` is an immutable settlement-level receipt: project UUID, settlement UUID, `BuildingKind`, stable plot coordinates and orientation, state, immutable requirements/supplied counts, duration, creation/start/completion game-time timestamps and deferred-chunk flag. Progress is derived from timestamps, avoiding a constantly dirtied progress counter. States are `PLANNED`, `WAITING_FOR_RESOURCES`, `READY`, `BUILDING`, `COMPLETED`, `FAILED`. `READY` is the resource-complete transition before the server starts the timer in the delivery event.

`ConstructionService.reserve` has a generic requirements/duration overload for later building policies. All materials use the existing `ResourceKind` / `DeliveryInventory` matcher and exchange preflight. No individual building implements its own inventory rules.

The project stores the **entire approved blueprint**, including native template NBT, rotated footprint, origin/entrance, supports, path writes and original block states. Completion never searches for another plot or reloads a changed template from a datapack. Active/failed reservations and their paths are included in later planning; direct additions cannot claim an active project footprint. Completed buildings remain in settlement metadata.

Planning installs a small blue timber construction marker at a known empty corner of the reserved plot. The marker has no craftable item or drops. It is an interaction point, not a Builder NPC or animated block-by-block structure. At completion the server removes only its owned marker, revalidates every terrain/chunk/border/entity precondition, places the saved native structure and paths reversibly, checks NeoForge placement protection, registers the building and records completion once. Unrelated replacement blocks are preserved.

Temporary entity occupancy leaves the project `BUILDING` and schedules a retry five seconds later, never before its saved deadline. New blocks, altered foundations, inventories or protection cancellation leave it `FAILED`; resources and reservation are retained. Clear the obstruction, then use **Retry** in survival. A missing marker does not erase the project; the Mayor or board remains available. Templates and snapshots deliberately do not adjust themselves to changed terrain.

## Survival interface and multiplayer

Talk to the Mayor and select **Construction**, use **Build** on the Quest Board, or interact with the construction marker. The existing parchment interface shows the project list, localized state, estimated progress/time, stable coordinates, actual item icons, shared supplied/required amounts and your carried amounts. **Supply** contributes every eligible carried material up to the remaining requirement. Partial contributions are supported. Inventory includes main slots and offhand, not armor. Stone means **Minecraft stone**, not cobblestone, consistent with the existing quest resource matcher.

No founder ownership lock is introduced. Different players in the settlement can supply the same project. Session UUIDs remain player-bound and anchored to a nearby, loaded Mayor/board/project marker. The server validates project membership, player state, territory, reach and current project state; clients cannot send costs, quantities, plots or timestamps. Inventory preflight, conditional project replacement and exact inventory application run in one server-thread event with no asynchronous gap. Replayed funded requests spend nothing, surplus items remain, and a completed receipt cannot place a structure again.

The screen provides keyboard navigation, narration, bounded list paging and detail scrolling at compact GUI sizes. Its progress bar appears before the material list. Client interpolation estimates progress between snapshots; **Refresh** retrieves authoritative state. If a marker disappears on completion, its open session closes on the next action; inspect the next project from the Mayor or board.

During `FOUNDING`, the Mayor uses contextual localized dialogue. First Meeting remains available, subsequent main steps wait, and generated dynamic offers are restricted to food/building resource requests. The original main/dynamic quest rules resume once established. Quest history, reputation and individual completion are not reset.

## Advancement and celebration

`livingkingdoms:first_kingdom` is a Minecraft advancement with a Charter icon and native toast. English: **Your First Kingdom** / **Found your first Living Kingdoms settlement.** Spanish: **Tu primer reino** / **Fundá tu primer asentamiento de Living Kingdoms.** A successful wilderness foundation **or first vanilla-village conversion** qualifies. Only the establishing player earns it; contributing materials does not award it. Vanilla advancement data persists the one-time receipt across restarts. No global chat announcement is enabled. Successful founding uses the action bar: **Kingdom founded: <name>**.

`KingdomMilestone` reserves identifiers for FIRST_CITIZEN, FIRST_UPGRADE, FIRST_PILLAGER_CAMP, FIRST_LIBERATION, FIRST_FORTRESS and SURVIVE_RAID. They have no definitions, triggers or placeholder advancement nodes yet.

## Configuration and operator tools

Per-world `livingkingdoms-server.toml`:

```toml
[construction]
progressiveFounding = true
testingDurationTicks = 0

[construction.town_hall]
durationTicks = 3600
logs = 64
stone = 32
ironIngots = 4

[construction.house]
durationTicks = 1800
logs = 32
stone = 16
ironIngots = 0
```

At 20 TPS these durations are three minutes and one and a half minutes. Durations count **server game ticks**; offline time does not advance them, while an unloaded plot in a running world does. A testing override of `100` gives new projects a five-second duration; values below 20 clamp to 20. Costs and durations are snapshotted when a project is planned, so config changes do not alter existing commitments. Disabling progressive founding affects new Charter foundations only; existing projects continue.

Operator permission level 2 is required for:

```text
/kingdom construction info
/kingdom construction complete
/kingdom construction advance
```

All responses are labelled **Development tool**. `info` displays lifecycle/current receipt. `complete` skips time for a funded project, retaining terrain/protection validation. `advance` also grants missing test resources without taking inventory, then attempts completion. A blocked funded project can retry through these commands. With no active project, a command can attempt the next founding plan. They never force-load chunks and are not required in survival. `/kingdom settlement info` now includes the localized lifecycle.

## Persistence, performance and compatibility

Settlement schema **3** reads schemas **1 and 2**. Older records safely default to `ESTABLISHED`, including 0.9.0 `FOUNDED` records; identity, layout, founder/provenance, population and indexes remain intact. Only explicit current-schema lifecycle metadata enters the new founding flow. Quest schema remains 4 and encounter schema remains 2. Do not downgrade a world written by this version to older code that cannot read schema 3.

`data/livingkingdoms_construction.dat` uses guarded `SavedData` schema 1. Malformed or unsupported files cannot be replaced with a fresh empty store after vanilla catches a deserialization error. Project/template sizes, resource bounds, states/timestamps, orientation, blueprint consistency, block names/properties and reservation uniqueness are validated. A world stores at most 16,384 project receipts and 64 per settlement; the current chain creates two.

Derived deadline queues resolve at most **two** ready projects every **20 ticks**, with a bounded stale-entry budget. Unloaded due projects persist a deferred flag and are indexed by their blueprint chunks. Relevant chunk-load events wake them; restart schedules one initial availability check for deferred projects. No construction loop scans every settlement/project each tick, and no production path adds chunk tickets or calls a chunk-loading getter. The full footprint/path/snapshot chunks must be available before placement.

Normal autosave and Save & Quit persist resources, timers, blueprints and completed metadata. This shares Minecraft's ordinary independent chunk/entity/player/SavedData durability model; it is not a journaled transaction across multiple files after a hard process or power failure. Construction background placement posts NeoForge protection events without an online actor; claim-mod behavior with such events requires integration QA. Interactive planning/retries and operator completion also check the interacting player's native permissions. There is no built-in ownership/claim/economy system.

## Automated validation

**2026-10-08:** `gradlew --no-daemon test build runGameTestServer` passed: **190 unit tests** (178 retained + 12 new), **77 GameTests** (68 retained + 9 new), production/sources JAR build and `git diff --check`. The nine native modules also passed reproducible generation, connected-block and four-rotation audits; the eight existing module files are unchanged from HEAD. The initial GameTest failures were corrected without weakening prior coverage. The previously accumulated test world was archived under `build/qa/gametest-world-before-progressive-retest`; normal development worlds were not reset. Two deliberate protection/obstruction warnings and the existing injected Mayor-refusal errors are expected negative-test output, not failing tests. New tests cover lifecycle migration/persistence, partial shared materials, invalid transitions/durations, midpoint/deferred serialization, corrupt-file guards, native blueprint/file reopen, reservations, UI capability validation, exact consumption/replays, multiplayer, timed Town Hall/House placement, metadata and establishment, blocked/protected rollback, temporary entity occupancy, unloaded-chunk wakeup and native one-time advancement persistence. Existing conversion tests also assert established lifecycle, no projects and conversion advancement.

The old full-Charter GameTest explicitly disables progressive founding during its synchronous body so its original full-module/NPC/quest assertions remain covered. The native geometry QA includes the camp in all four rotations and checks its open entrance separately from permanent house doors. Test-only terrain/loading/tickets/player adapters are excluded from the production JAR.

## Manual QA on the gaming PC

Use Minecraft 1.21.1, Java 21, NeoForge 21.1.252 and `livingkingdoms-0.10.0.jar` on each client/server. Use a disposable QA world and keep an older-world backup for migration checks. The existing development PC's AMD `atio6axx.dll` crashes before gameplay were documented in the preceding UI/structure QA; this milestone does not claim new graphical screenshots or interactive client acceptance.

1. **Craft the Charter.** At a crafting table use the existing recipe: emerald/paper/emerald, iron/banner/iron, stone bricks/stone bricks/stone bricks. Any banner color should work.
2. **Found in wilderness.** In Survival, select dry, unobstructed Overworld terrain away from village beds/villagers. Let surrounding chunks load and use the Charter on reachable ground.
3. **Inspect the starting area.** Verify exactly one Charter was consumed, only the 9×9 camp and construction marker exist, and a Mayor plus two residents spawned. No permanent Town Hall, House, Blacksmith or Barracks should exist. `settlement info` should show Founding.
4. **Verify celebration.** See the action-bar name and Your First Kingdom / Tu primer reino toast. Check the Advancements screen. Later successful foundations/conversions by the same player should not repeat the toast; another player's first foundation should award their own advancement.
5. **Inspect requirements.** Talk to the Mayor, select Construction, or use Build on the board / the project marker. Check the reserved coordinates, Waiting for materials state, real icons and supplied/required counts. Test English and Spanish, GUI scale 2/3/4, scrolling, Tab, Enter and Escape.
6. **Supply resources.** Deliver a partial stack of logs, then have a second player deliver the remainder, stone and iron. Verify shared totals and exact inventory subtraction. Click Supply repeatedly; surplus resources should remain and no player should pay twice.
7. **Verify BUILDING.** Once funded, the state and progress bar should change. The building must not appear in the same resource-delivery tick. Leave the supply screen open briefly, then Refresh to compare authoritative progress.
8. **Wait or advance for QA.** Wait three minutes of server time, or as an operator use `construction complete` to skip time for the funded project. `construction advance` is explicitly a development shortcut that also grants missing test resources.
9. **Inspect the Town Hall.** Verify its native structure, roof, rotation, elevation/supports and connecting path at the original reserved plot. The camp and settlement name/Mayor/reputation should remain. A first-House project should now be available.
10. **Complete the first House.** Supply its listed materials and wait or use the labelled operator shortcut. Verify the House appears once at its saved plot and receives building metadata. Repeat completion commands to check for duplication.
11. **Verify ESTABLISHED.** Town Hall plus one House should switch lifecycle to Established. Regular Mayor dialogue and existing main/dynamic quests should resume. No immigration, housing capacity or professions should activate.
12. **Save & Quit during a project.** In another new camp, partially supply or start the Town Hall, note coordinates, materials and progress, then Save & Quit. Also test leaving the area while a dedicated server keeps running, without adding chunk-loading mods/tickets.
13. **Reload.** Restart the world/server and return to the camp. Reopen Construction via the Mayor/board. If the deadline passed while the plot was unloaded, load its footprint and path chunks and give the scheduler a few seconds.
14. **Check preservation.** Verify identical project coordinates/rotation, material counts, timer receipt and no duplicate structures/NPCs. Offline time should not count. A deferred due build should finish when all necessary chunks are available. Complete the House and confirm the same settlement becomes Established.

Additional checks: convert a complete vanilla village and verify immediate Established state, intact inventories/houses and no wilderness chain; reopen a copied 0.9.0 world and inspect old founded/generated/converted records; place a chest in a reserved building area before the deadline and verify Failed preserves it and all supplied resources, then remove it and Retry; stand a villager/player inside a due plot and verify automatic delayed completion after they leave; break the project marker and continue via the Mayor; try another player's session token/project identity and leave UI reach to verify rejection; test a claims mod's placement cancellation for planning and scheduled completion.

## Limitations and next milestone

Visible development is marker → timed native structure. The progress display is an estimate until refreshed. Terrain changes can require restoring the saved ground state before Retry. A missing next safe House plot leaves the completed Town Hall and saved kingdom intact; load/clear nearby terrain and select Plan. Converted-village expansion surveying is future work. No housing capacity, immigration, citizen assignment, professions, Builder AI, tiers, raids, conquest or liberation has been implemented.

Recommended next milestone: perform the graphical/multiplayer/normal-save QA above, then design Housing + Citizens around completed building metadata and a survey of converted villages. That system is intentionally not started here.
