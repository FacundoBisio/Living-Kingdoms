# Living Villagers 0.17.0

Branch `codex/living-villagers-v017`, on the stable local main 0.16.1 (`48a2a4c`). It keeps the dialogue/HUD/Quest Board polish and the preparatory economy/building commits (`d46445c`, `6b5bdc5`). The requested release changed from 0.20.0 to 0.17.0: commerce/diplomacy, complete central management and the extended chapter stay for Kingdoms Alive. Main and the Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 versions were not modified.

## Citizen cycle

Identity, housing, buildings, assignment and careers are reused. From **Mayor → Residents → Profession** the six professions are assigned; the picker paginates on small GUIs. Each citizen shows profession, level/XP, home, workplace and activity. **Construction → Choose building** adds the warehouse, hut, mine and smithy, with the same requirements, terrain reservations, stages and workers as the earlier system.

**Residents → Resources** lets you deposit and withdraw food, wood, stone and iron, or withdraw crafted equipment. It shows capacity, carried inventory, available physical amounts and nutrition reserves; equipment is only obtained through production. Full details are split into pages, also on small GUIs. Depositing/withdrawing moves up to 16 items (one piece of equipment), with the same transactions as the commands: it validates the individual session, territory, membership, reach to the Mayor/board/marker, balance and inventory space. Two collaborators with stale views cannot spend the same last batch. **Back** keeps the Residents session; leaving/reopening invalidates old actions. There is no polling or per-tick sync: **Refresh** renews the view when the inventory changed or another player used the storage. Texts are computed when the snapshot, resource, language or dimensions change, not recomputed when paging.

| Profession | Station | Real consequence |
|---|---|---|
| Farmer | Farm | Harvests/replants real crops, produces food and earns XP; respects protection and plot limits. |
| Builder | Town Hall / converted administration | Builds funded projects and repairs explicitly ordered structural damage, spending wood/stone and earning XP. |
| Guard | Barracks | Patrols defensive buildings, fights local threats and progresses through real damage. Can receive a crafted chestplate. |
| Lumberjack | Hut with an intact crafting table | Consumes 1 food and produces 4–8 wood by level. |
| Miner | Mine with an intact stonecutter | Consumes 2 food + 1 wood and produces 4–8 stone + 1 iron. |
| Blacksmith | Smithy with an intact smithing table | Consumes 2 food + 2 wood + 8 iron and crafts an iron chestplate; also repairs held equipment with a cost and wait. |

Lumberjacks/miners/blacksmiths walk to their station using vanilla navigation. Abstract extraction avoids chopping/mining player terrain; it has paid inputs, capacity, tracked production/XP and a persisted wait. The default initial wait is 1,200 ticks, with bounded level bonuses and a 200-tick minimum. Removing, reassigning or reloading does not reset a pending wait.

Economic/farming work: day 1,000–11,000. Builders work before 12,000. At night they return home and can use their own real bed. Guards rest between 16,000–22,000 when there is no threat; they keep looking for enemies and wake up to fight. Civilians seek shelter on alert or recent damage. The Mayor keeps its role as the interaction point. Paths use the native pathfinder, with no teleports or new road network; obstacles produce bounded waits/retries.

## Resources, construction and maintenance

Food keeps its stock and the historical counter of units added by harvests and quests. Materials/equipment use an additional SavedData with limits and server-thread transactions. Direct deposits do not count as production; the earlier system's historical food delivery counter is kept. One equipment entry equals one iron chestplate; it can be withdrawn to the inventory. Wood is stored as a fungible resource and withdrawn as oak logs.

Default consumption happens every 2,400 ticks: one food per four residents, rounded up. Insufficient food keeps the existing immigration modifier and requests. The warehouse and its upgrades increase capacity; building upgrades add slots or reduce waits, and upgraded watchtowers extend detection. The controlled expansion already implemented is kept, with costs and rejection on pending projects/threats/overlaps.

Survival commands, inside a joined kingdom:

```text
/kingdom stock
/kingdom stock deposit food|wood|stone|iron
/kingdom stock withdraw food|wood|stone|iron|equipment
/kingdom development
/kingdom development plan warehouse|lumberjack_hut|mine|blacksmith
/kingdom development fund
/kingdom development repair <buildingUUID>
/kingdom development repair_equipment
/kingdom development equip_guard <citizenUUID>
/kingdom development upgrade <buildingUUID>
/kingdom development expand
```

Command deposits/withdrawals move up to 16 items (one piece of equipment). Food is measured in nutrition units: by default one wheat equals two units, both when depositing and withdrawing. Only the surplus above the configurable healthy reserve can be withdrawn; food paid for by quests stays reserved until residents, production or maintenance consume it. This prevents duplicating wheat or getting paid for a delivery and immediately taking it back. The Residents screen shows the reserve. Profession SavedData migrates from schemas 1–3 to 4 without changing balances/UUIDs/careers; new reserves are saved so they do not reset on reconnect. Funding uses real materials: in Construction, **Backpack** contributes carried items and **Storage** contributes stock to the selected project, with both counts visible. Repairing equipment requires holding a damaged tool/armor/shield, a loaded blacksmith next to their station and 1 iron + 1 food; it removes 40 damage at level 1 without altering components. Equipment crafted for a guard is spent once and keeps the non-transferable/no-drop equipment contract. For now these advanced actions use commands; `/kingdom development` lists buildings and workers with copyable UUIDs.

Structural repairs: one explicit order per building; only AIR gaps that correspond to structural blocks of the original module. It does not overwrite intentional changes, containers, beds, stations or ore blocks. Before navigating/writing it checks that the NBT module still matches the registered plot and entrance, including rotation. Missing, invalid or resized resources block work with retries, without losing materials or altering outside terrain. It also rejects block-property changes introduced during EntityPlaceEvent, keeping legitimate connection adjustments made by vanilla. It scans up to 32 entries per opportunity and places one block every 100 ticks, spending one material; it respects mobGriefing and EntityPlaceEvent. A cancellation restores the block and waits before retrying. The transient cursor can reset on load; blocks already placed and costs already persisted are not duplicated.

## Notifications

Personal server notices distinguish objective ready, quest completed without a material reward, and reward claimed. They use vanilla icons, a dark background, a pixelated gold/green border, wrapped text and pages when needed. The queue holds eight pending notices and deduplicates the 128 most recent events; claiming replaces a still-unshown pending notice for the same quest. Animation/sound and coordination with other notifications are those of the native Toast system. Only one mod card enters that system at a time, and it respects Minecraft's configured duration and F1. `questNotifications` disables them; H keeps independent control of the HUD.

There is no polling of all quests and no resend on connect. The existing `objectiveSatisfied`/`COMPLETED` transitions are the persisted receipts. The old Iron Shortage reports its real accepted terms and its chapter mirror does not duplicate notices. Events while the player is offline are not replayed later. Physically finishing a project immediately resolves accepted development quests for that settlement through an index derived on load, without scanning all quests every tick. It updates the HUD and the open quest view; switching between the journal and the local screen invalidates the previous session. Old names up to 128 characters are encoded in full and bounded. The HUD and Quest Board keep using the same quests/states, rewards and session capacity. No new quests or rewards are added in this release.

## Verifiable costs and limits

- Economic/Farmer/Builder/Guard checks staggered per entity every 20 ticks; civilians every 40. Vanilla goals keep animation/combat smooth, and their searches/movements have their own waits.
- Consumption shares one check per kingdom per second, triggered by a loaded Mayor or registered citizen. Temporary cache of at most 2,048 kingdoms; a persisted receipt prevents duplicate charges even if an entry is evicted.
- Native templates: cache of 64, invalidated when the resource manager changes. Beds: only the assigned home's plot, positive results cached for 2,400 ticks and verified before use; absence is retried after 200.
- Unloaded chunks pause work/consumption; there is no remote simulation, no bulk recovery of lost hours and no mod chunk tickets. The 1.21.1 pathfinder queries available chunks; GameTests verify there are no forced loads.
- Resident details/texts are cached by snapshot, selection/language and width. The existing HUD only sends changed data once per second. Notifications send one packet per transition, not per frame/tick.
- Balance in `kingdomEconomy`, `professions`, Builder and Guard in `livingkingdoms-server.toml`: wait, slots, consumption, capacity and existing limits. No new dependencies. Limit/navigation tests are not a TPS measurement under real load.

## Validation

**403 JUnit and 163 GameTests passed**, Gradle build and JAR/assets/diff checks passed. The last run uses the isolated world `runs/gametest-v017-stock-ui-j`, with process and Gradle exit 0. The new cases verify stable identities, codec, pagination, nutrition reserves, full inventory, reach and two collaborator sessions using the last batch. Updated log of commands/results in [AUTONOMOUS_PROGRESS.md](AUTONOMOUS_PROGRESS.md). Generated JAR: `build/libs/livingkingdoms-0.17.0.jar` (1,021,784 bytes; SHA-256 `b6d923f2f8b9f887bce0c1f6792145703c9cec4a320022121a4a8d4ff084a3a0`). Failed runs and fixes are kept in the log; no worlds were deleted and no tests were disabled. Starting the client was attempted: it failed in the AMD driver (`atio6axx.dll`) before reaching the game. It was repeated with NeoForge's early window temporarily disabled: it got to the LWJGL backend and failed again in the same native module. The local configuration was restored byte for byte. Automated validation does not approve readability/blur/rendering inside the client.

## Pending manual checklist

- New world and a **copy** of a 0.16.0/0.16.1 save: found and convert a village with a Charter; earlier houses/residents/name/progress must be kept. Confirm messages with fewer than two villagers/beds.
- Mayor, Quest Board, Main Quests/Requests and history: objectives, locks, reward/destination, back/close and selection after claiming. Compare English and Spanish.
- 854×480, 1280×720, 1920×1080 and 4K; GUI 1–4/Auto and usual shaders: crisp dialogue, uncropped text, paginated picker, HUD inside/outside, H, F1, boss bars/chat/minimap and a notice queue that does not cover important controls.
- Assign the six professions in survival; check stations, crops, movement through doors/paths, obstruction/retry, night/own bed, waking up and shelter during an attack.
- Residents → Resources: go through the five materials and every page, deposit/withdraw with an empty/full inventory, compare food reserves, use Refresh and Back/close. Repeat with two collaborators and move out of reach while the screen is open. Watch consumption/shortage and production batches; fill storage, remove a station, unload chunks and come back. Build using storage materials, order a repair, protect a block and keep a player edit.
- Blacksmith: crafting, withdrawing a chestplate, repairing enchanted/component items, paid guard upgrade and no duplicated drops on death/removal.
- Two players: try the same assignment/last slot, concurrent funding/withdrawal, equipment and claims; only the owner gets notices and each cost/reward happens once.
- Save/quit/reopen and restart the server: UUIDs, houses, careers, waits, stock/production, orders and rewards must persist; notices must not reappear.
- Real profile with several loaded kingdoms: measure TPS/MSPT, failed paths, memory and traffic; unload kingdoms and check that work stops. No real-load benchmark has been approved yet.
