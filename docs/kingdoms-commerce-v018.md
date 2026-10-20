# Kingdoms & Commerce 0.18.0

Branch `codex/kingdoms-commerce-v018`, based on `main/origin/main` `203a976` (complete 0.17.0). Minecraft 1.21.1, NeoForge 21.1.252 and Java 21; no new dependencies. Identity, routines, economy, construction, quests, HUD and dialogue systems were preserved. Local commits; main was not modified and no merge/push was made.

## Survival gameplay flow

1. Found or join a village with a Kingdom Charter and complete onboarding with the Mayor.
2. Craft a **Kingdom Storage**: crafting table, pattern `LIL / LCL / LLL`, where L = planks, I = iron and C = chest. Place it inside the territory. Right-click opens the inventory and resources of the same KingdomEconomy that citizens use.
3. Shift-click an inventory stack to deposit that stack; click a resource to withdraw up to 16 physical units (one chestplate), right-click withdraws one. Hovering shows balance/capacity, materials reserved and consumed by construction, and food reserves. Construction reservations are already paid and stay in ConstructionProject: they are not deducted again from the available balance. Equipment cannot be deposited into storage; blacksmiths produce it.
4. **Mayor → Construction → Choose building → Preview**. Choose the type, rotate with R, move the corner to the targeted terrain with G, confirm with Enter or cancel with Backspace. Keys are remappable. The green/red outline reflects the server's latest survey; blue marks the entrance. Confirming creates the existing pending project; materials are then supplied from the Backpack/Storage and a Builder works. Nothing is charged or placed during the preview. You must talk to the Mayor directly to start a plot session; the quick-access key or a board does not grant that capability.
5. Build a **Market** through the same flow and assign a **Merchant** from **Residents → Profession**. It needs a home, a free slot, an intact station, working hours and safety. Interacting with the merchant buys or sells one unit per offer for emeralds. Shift + use opens their activity card.
6. **Mayor → Construction → Buildings** opens administration: upgrades, structural repairs, expansion, guard equipment, repair of the held item and the history log. Actions spend real resources or create work orders; no commands are required.

## Commerce and storage

A physical storage block is an access point to the kingdom's single balance, with no duplicated economic inventory in the block. Breaking/re-placing the block keeps the stock and does not turn it into drops. Native menu, read-only resource icons, normal inventory, and checks for territory, membership, reach, liveness, permissions and capacity. Icons cannot be extracted by cloning, hotbar swapping or throwing.

The market shares one treasury and quotas per kingdom; several merchants do not multiply stock. The configurable starting treasury is 32 emeralds per kingdom. Restocking renews quotas without creating money or goods; the merchant sells real KingdomEconomy stock. Purchases/sales use configurable prices with a mandatory margin (buy price above sell price). Each offer has a usage receipt and market, economy, food and employment revisions. A stale offer is renewed and must be confirmed again. A full inventory, modified components, food reserves, a lost station, a profession change or a disconnect never produce partial trades or duplicate payments. Configuration: `livingkingdoms-commerce.toml`.

Food keeps the 0.17.0 nutrition units: by default 1 wheat = 2 units. Healthy rations and food pledged to paid quests are protected from withdrawals/sales until residents or work consume them. Wood stays fungible and is withdrawn as oak logs. Economic professions keep paid inputs and persisted waits; no destructive logging or mining was added.

## Citizens and presentation

Entities remain Villagers with the same UUIDs, inventories, equipment, navigation and persistence. Only registered citizens/Mayors get the humanoid presentation; the rest of the world is not transformed. Native renderer, separate arms, walk/idle/combat/rest and 80 reproducible original skins. Real armor and weapons are rendered; cosmetic tools never enter inventories or drops. The work gesture is tied to real progress and is hidden during rest/shelter or travel. Discreet, bounded vanilla sounds, with no per-frame sounds.

Name, translated profession and level appear according to distance/configuration; an activity card opens when interacting with citizens. The Mayor and the Merchant keep their normal interaction; Shift + use inspects them. `livingkingdoms-citizen-visuals.toml` controls humanoids, labels, distances and work accents. [Native/GeckoLib comparison and limits](v018-citizen-rendering.md).

## Verifiable upgrades

New upgrades are limited to levels 1–3. Higher historical levels are not removed. Next-level costs: 32 × current level wood, 16 × level stone and 4 × level iron. Requests include a resource revision; repeating an earlier packet neither charges again nor raises the level.

| Building | Real benefit |
|---|---|
| Town Hall / Core | +1 Builder slot per level; raises development and enables expansion from level 2. |
| Warehouse | Extra material and food capacity, configured per level. |
| Farm | +1 Farmer slot per level for real harvests. |
| Barracks | +1 Guard slot per level. |
| Watchtower | Larger existing detection range. |
| Blacksmith / Mine / Lumberjack Hut | +1 slot per level and 120 fewer ticks of production wait per level; minimum 200. |
| Market | Merchant slots and larger quotas on restock. |
| Houses | +1 housing place per level; beds are needed for physical rest. |

Functional upgrades do not replace templates or overwrite player modifications. Level and benefit are shown in administration; additional permanent architectural changes are out of scope for this release. Repairs only cover safe structural gaps from the template and respect protection/events. Guard equipment consumes a crafted chestplate, with the existing ownership/no-drop policy. Repairing the held item requires a blacksmith at their station and costs 1 iron + 1 food. Expansion keeps identity and indexes; it requires level 2, safety, no pending construction and 64 wood + 32 stone + 8 iron.

## Alerts and advancements

Persisted facts: death of a registered citizen/guard, loss of the last essential worker, major threat detected, construction and upgrade completed. Known name/role/kingdom are shown, without attributing unverified culprits or attack causes. Personal notices go to connected kingdom members; nothing repeats on login. History from Buildings → H: 128 facts per kingdom; 512 recent receipts, history for up to 1024 kingdoms. Receipt eviction cannot replay transitions: the source systems keep their durable receipts. Queue of eight notices and 128 recent IDs, integrated with the native Toast. No world polling generates notices.

Six new native advancements: village conversion, first Warehouse, seven operational professions, first upgrade, first trade and development 3. Earlier founding, guard, Builder and defense advancements are kept. They are granted after real events; they add no repeatable payouts or inventory rewards. Construction/debug does not grant the normal production notices/advancements.

## Verified performance and compatibility

- Citizen work/tracking is staggered per entity every 20 ticks. Visual state is sent on change or every 200 ticks to its observers. Server cache of 4096 and client caches of 1024 entries; no global per-frame lookups.
- On-demand market: five offers and 37 physical slots; lazy quotas/restock with no recovery multiplied by offline time. Bounded sessions.
- Projection: one cached list of up to 1024 non-empty cells; large modules are visually simplified while the server keeps the full blueprint. Rotation/movement validated at most twice per second. Unloaded chunks are not forced. Unsupported animated block entities keep their outline and are skipped by the special draw.
- Storage updates menu data at most every 20 ticks or on transfer; the protocol keeps 32-bit quantities. Administration uses snapshots on open, refresh or action, with no per-frame sync.
- The nine SavedData files copied from the synthetic 0.17.0 world loaded with real registries/tags and kept every persisted fact after save and reload. UUIDs, equipment, drops, AI and presentation after native reload were checked separately. Original worlds were not modified.

**428 JUnit and 174 GameTests passed**, none skipped; Gradle build passed. Logs: `build/v018-final-release-build.log`, `build/v018-integrated-gametest-b.log`. The first native run was 170/171: a collision between fixtures was fixed. The snapshot test moved from JUnit to GameTest to have vanilla tags available, keeping its checks. No tests were disabled. The old expectation that prevented house upgrades was updated for the new benefit, keeping the cost/limit checks.

Static checks: 14 modules/rotations/reproducibility, 80 skins, storage assets, eight HUD icons and nine earlier civic assets. Resource validator passed, with three vanilla references verified against the game resources. JAR checked against all current compiled classes and resources; pins/metadata/Java 21 and locales consistent, with no GameTest mod or fixtures.

JAR: `build/libs/livingkingdoms-0.18.0.jar`, **1,283,077 bytes**. SHA-256: `43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3`.

## Manual checks for the user

No browser or Minecraft client was opened or controlled. Graphical appearance and real performance are not presented as validated by server tests.

- 0.17.0 world copy: citizens/names/careers/XP, materials, buildings, quests/reputation/receipts and history; save/reopen. New world: founding and conversion.
- Chest: craft/place/break/re-place, separate stacks, full inventory, food and construction reserves. Two clients withdrawing the last batch, and other blocks of the same kingdom.
- Market: build, assign a Merchant, day/night, broken station, threat, buy/sell, last good/quota/treasury, emeralds and restock after restart.
- Citizens: skins/seams, walking, bed/orientation, tool and dominant hand, armor/shield, Farmer/Builder and the three economic professions, trading and guards in combat. Vanilla villagers outside the registry must keep their look.
- Labels and card: English/Spanish, levels and professions, nearby groups, invisibility, distances, configuration and resource packs/shaders.
- Preview: the nine selector options, four rotations, moving, uneven terrain, entrance/cost/outline, protecting/modifying terrain between survey and confirmation; cancelling must not charge or place anything. Use remapped controls.
- Buildings: two paid upgrades, real capacity/slots/efficiency, repair structure/item, equip a guard, expansion, history and one-time advancements. Houses must have enough beds for rest.
- Dialogue/HUD/Toast: 854×480, 1280×720, 1920×1080 and 4K, GUI 1–4/Auto; readability, H/F1, chat, boss bars/minimap and keyboard navigation.
- Profile with several kingdoms/NPCs visible: FPS, TPS/MSPT, memory/network and blocked paths; compare with unloaded chunks. Lag warnings while preparing large fixtures are not a benchmark of the mod.
