# Guards, Barracks and settlement defense — 0.13.0

Branch: `codex/guards-settlement-defense`. No commit or push is performed by this milestone. Existing staged 0.12 changes are preserved.

## Architecture and compatibility

Guard is an actual `ProfessionType.GUARD` on the existing Citizen/Villager, not a new entity type. Citizen UUID, entity UUID, settlement, home, shared citizen level, vanilla career/trades and citizen death history remain authoritative in their existing stores. The profession has its own XP/level, assigned workplace and work/combat state. It retains the RESIDENT NPC identity so housing, citizen validation and Mayor behavior remain compatible.

`ProfessionSavedData` now writes schema 2 and reads schema 1. Schema 1 Farmer fields, Food and buildings load without changes; removed Farmer progress migrates into the Farmer career history. Farmer and Guard career histories remain separate through removal, reassignment and reload. XP never transfers between professions. Unknown/corrupt schemas fail closed. The Citizen, settlement, encounter, reputation, construction and immigration formats are unchanged. Do not downgrade a schema 2 world to 0.12.

Equipment and health modifiers persist in ordinary Minecraft entity NBT. Stable modifier IDs prevent stacking and restoration never heals a guard. Profession metadata does not duplicate item stacks or entity positions. Loaded entities reconcile assignments at join; removing an unloaded citizen's job releases its slot immediately and removes old bound gear on its next join. Death preserves history, releases the existing home and Barracks slot, and never respawns a citizen.

## Barracks and ordinary gameplay

Established settlements can use **Mayor → Construction → Plan Barracks** if they have none. Planning, resource delivery, time, protected native placement, plot reservation and retry use the existing ConstructionProject service. Default requirements: 48 logs, 32 stone, 8 iron ingots and 3,600 ticks (three game minutes). Existing registered Barracks become functional automatically when their settlement metadata is synchronized. Basic capacity is four Guards, shared by all players.

Use **Mayor → Citizens → Assign Guard** on an active UNASSIGNED citizen with a home. The panel shows name, shared level, profession level/XP, home, workplace kind/location/capacity, status and settlement Security. **Remove profession** releases the slot and restores normal vanilla citizen behavior. When a job is assigned, this button replaces Assign Farmer; Assign Guard and Back retain their positions. Construction offers House, Farm and Barracks; Escape closes the screen.

The server revalidates the player, physical Mayor/board anchor, dimension, territory, settlement lifecycle, citizen identity, home and capacity. Each roster snapshot records the employment generation; a stale view cannot remove a later reassignment, even when its citizen, profession and Barracks are identical. Changing work state or gaining XP does not invalidate a management view. Replay cannot overbook capacity or duplicate gear.

## Defense and factions

One local Minecraft goal controls each loaded Guard. Vanilla career Brain behaviors are suspended during explicit assignment and restored on removal; normal trades/career data stay intact. Guards patrol at most 12 loaded registered Town Hall/Core, Barracks and Watchtower entrances, falling back to the settlement center. Patrol destinations rotate on a bounded interval. Navigation remains Minecraft's native pathfinder.

Default defense radius: the smaller of settlement radius and 32 blocks. Local entity queries cover 16 horizontal blocks and six vertical blocks, every 40 ticks, staggered at initial installation. At night searches occur every 20 ticks. Targets require line of sight and must initially enter the defense boundary. Pursuit stops eight blocks beyond it, including when the guard itself crosses that limit; it clears the target and walks back. No chunk tickets, forced loading, global hostile entity scans or path optimization are added. Unreachable terrain can still defeat vanilla pathfinding.

States: IDLE, PATROLLING, ENGAGING, RETURNING, SLEEPING and UNLOADED. Guards patrol both day and night, wake for a detected threat, and retain SLEEPING when Minecraft actually marks them asleep. Rotating sleep shifts are deferred. Trading, riding and leashing pause defense.

Faction metadata and centralized `FactionRelations` determine hostility toward PILLAGER, BANDIT and UNDEAD. An allied tagged mob remains protected regardless of its species. Existing Mayor and civilian NPCs receive no faction combat AI. The generic hostile faction selector is not added to profession Guards because their own goal enforces territory/chase policy. Without faction metadata, a vanilla Monster is attacked only when its native target is a registered citizen. Unrelated animal deaths do not grant profession XP or player reputation.

Hostile encounter members also check for nearby allied metadata every 80 ticks, using one loaded representative per party and the existing indexed nearest-settlement API. This supports alerts even without Guards. Loaded guards can alert immediately upon sighting an encounter. Both paths share a settlement cooldown of 1,200 ticks and notify only alive, nonspectating local players. No offline alert simulation is performed.

## Equipment and combat progression

Server configuration is under `[guards]` in `livingkingdoms-server.toml`.

| Rule | Default |
| --- | --- |
| Barracks Guard slots | 4 |
| Defense / chase margin / local search | 32 / 8 / 16 blocks |
| Search interval | 40 ticks; halved at night, minimum 20 |
| Melee damage / cooldown | 4 damage, 25 ticks |
| Level benefit | +0.25 damage and +1 maximum health per level |
| Benefit ceiling | +2 damage; +8 maximum health |
| XP | 2 XP per four actual hostile damage points |
| XP step / level cap | 40 / 5 |
| Improved armor threshold | Level 3 |
| Modest enchantment | Level 4, deterministic 15% chance of Protection I on issued chest armor |
| Alert cooldown | 1,200 ticks |
| Low-security immigration factor | 0.85 |

Level 1 receives an iron sword, shield, chainmail chestplate and leather helmet. Level 3 receives iron armor. Equipment uses vanilla items and the native equipment slots; no models are introduced. Villager rendering can show fewer armor pieces than a humanoid armor renderer. The shield is a held equipment foundation; active shield blocking is not implemented. Attacks use native mob damage sources/swing and bounded navigation, not advanced combat AI or custom weapons. Loadout enchantment rolls depend on citizen identity and do not reroll on reconnect or job changes.

Only empty or previously service-issued equipment slots are changed. Pre-existing external equipment is retained. Service-issued gear is bound to the citizen, has zero drop chance, is removed before death loot, and cannot join the world as transferable item drops. This also prevents Looting/conversion/debug removal from producing reusable free gear. Original pickup behavior is restored on removal. There is no gear production economy yet.

Guard XP comes from actual post-damage events against authoritative hostile faction members within chase range. Fractional contribution accumulates on the victim. Its finite shared damage budget is capped to one maximum health bar and persists in native entity NBT, including managed conversion. Healing and repeatedly attacking the same target cannot generate unlimited XP. Several guards can share that budget and earn their own damage credit; no killer-only bonus exists. Profession updates use the existing comparison receipt, so a stale XP receipt cannot replay. No XP is granted per tick, for passive proximity, or for unrelated kills.

## Reputation, Security and hooks

Player reputation keeps the existing encounter rules: qualifying player damage participation, expiry, a relevant nearby allied settlement and one defeat/reward receipt. Guard damage never creates a player contribution. A player who participates can receive the normal local reward when Guards finish the party; watching them fight grants nothing. Default debug encounters still grant no reputation. `reward_test` is explicitly an operator-only testing fixture.

Security is derived on demand from settlement indexes: each active Barracks contributes 8, each active Watchtower 6, and each active Guard 12 plus 2 for each extra profession level (level contribution capped at eight extra levels). Clamp the final result to 0–100. Unloaded active Guards remain part of the settlement's assigned defensive strength; no combat is simulated in unloaded chunks. Dead/retired Guards contribute nothing. Watchtower has DEFENSE_SUPPORT capability and a score contribution, without ranged tower AI.

Security contributes a separate immigration factor, linearly increasing from 0.85 at zero Security to 1 at 60. Food retains its existing independent factor. Housing, settlement lifecycle and shared candidate limits remain hard prerequisites. Security alone never makes immigration impossible.

`SecurityService.score`, `lowSecurity` and `questWeights` are reusable hooks. Low Security (<32) raises the existing dynamic generator's iron delivery weight, while preserving Food shortage weights. No new quest engine or raid objective is introduced.

`FIRST_GUARD` / **Defender of the Realm** / **Defensor del reino** is awarded once to each player who first assigns a Guard. First profession also remains eligible through Guard assignment. Native player advancement data persists the receipt.

## Development commands

All diagnostics below require operator permission 2 and are read-only. Stand inside an allied settlement:

```text
/kingdom guard info
/kingdom guard debug
/kingdom settlement security
```

Use the existing controlled encounters to test real combat:

```text
/kingdom encounter spawn pillager_patrol
/kingdom encounter spawn undead_horde
/kingdom encounter spawn pillager_patrol reward_test
/kingdom encounter spawn undead_horde reward_test
```

## PC gamer checklist

Use Java 21, Minecraft 1.21.1 and NeoForge 21.1.252 with `build/libs/livingkingdoms-0.13.0.jar` on client and server. Use a copied test save for operator combat/death checks. This checklist describes pending hands-on graphical QA, not an automated client pass.

1. Establish a settlement through the existing Charter/founding chain or vanilla village conversion.
2. In Mayor → Construction, plan Barracks, deposit its displayed requirements and wait for normal completion. Existing Barracks should disable another plan.
3. Ensure a free home, accept an immigrant through Mayor → Immigration, then open Citizens.
4. Assign Guard. Verify home and shared citizen level remain, profession level starts at 1, and Barracks capacity decreases by one. Assigning the first Guard grants the advancement.
5. Inspect held sword/shield, citizen status and Security. Run `/kingdom guard info` for profession progress and shared capacity. Vanilla villager clothing may conceal armor.
6. Spawn a Pillager Patrol on safe loaded terrain nearby. Default debug groups award no reputation.
7. Observe a threat alert, local pursuit/combat and a return after enemies leave the chase boundary. Verify Guards do not pursue across the world.
8. At night spawn an Undead Horde nearby. A single low-level Guard need not defeat an entire group; recruit enough defenders or help.
9. Observe combat against hostile faction members and protection of citizens/allied mobs. Avoid unrelated wildlife deaths when checking XP.
10. Record reputation, use a `reward_test` encounter, then let only Guards fight. Reputation must stay unchanged even after victory.
11. Spawn another `reward_test` group, deal meaningful damage and help defeat it. Verify one reward for this settlement only; other players need their own qualifying contribution.
12. Save & Quit after assigning a Guard and earning XP.
13. Reload the same save and visit the same settlement.
14. Verify citizen identity/home, profession XP/level, Barracks assignment, capacity, equipment and Security persist without healing/duplicate equipment. In multiplayer, try two simultaneous assignments and a stale removal after another player reassigns a citizen.
15. Kill a Guard using an operator test or real combat. Confirm DEAD history, free home/Barracks slot, reduced Security, no automatic respawn and no transferable issued gear.

Also inspect Citizens/Construction at GUI scales on a small screen, keyboard navigation, English/Spanish text, scrolling, disabled tooltips and Escape. Confirm existing Farmers still harvest, retain XP/trades, obey protection/night rules and produce Food.

## Verification and limits

Current automated verification: 249 unit tests and 105 GameTests, including all previous tests. Run `gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer` with Java 21, then `git diff --check` and `git diff --cached --check`. Final result: all 249 unit tests and all 105 required GameTests passed, Gradle build succeeded, and both diff checks passed. The native module generator check and four-transform structural audit also passed for all ten modules. English/Spanish key parity is 317 keys. The packaged JAR contains the Guard runtime, Security service, advancement and native Barracks. New coverage includes real Pillager/Undead combat, native return, ally protection, shared UI/advancement/capacity, protected Barracks construction, native equipment reload, unloaded removal, death and player-participation rewards. Unit coverage includes schema 1 migration, physical schema 2 save reopen, profession career isolation, employment generations, foreign-thread rejection, XP receipts, boundaries, capacity and Security.

No raids, siege, walls/gates, Captain/Builder/Blacksmith AI, economy, conquest or liberation are implemented. No bows, formations, active shield blocking, sleep rotation, tower combat or equipment crafting/replacement cost. Citizen equipment persistence and profession/world data have normal Minecraft save semantics; there is no cross-file transactional journal for abrupt power loss. Guard XP for unmanaged vanilla monsters is deferred. Alerts are for loaded encounters. Manual visual/client QA remains pending.

Recommended next milestone: a small player-accepted **Defend the Settlement** quest that uses local encounter entry, Guard contribution and the existing quest/reputation receipts. Validate balance and territory pressure before implementing raid waves.
