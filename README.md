# Living Kingdoms

Minecraft Java **1.21.1**, **Java 21**, **NeoForge 21.1.252**. Version 0.7.0 adds a persistent main chapter, rotating resource/combat quests and progression-aware rewards to the existing settlements, Mayors, factions, natural encounters, combat, regional reputation and levels. All gameplay state is owned by the server. Housing, advanced professions, diplomacy, economy, conquest, armies, custom GUI and external AI services remain outside the current scope.

Living Kingdoms focuses on exploring, discovering settlements, gaining reputation, fighting and liberating hostile territory. Its settlements are RPG/strategy hubs; the buildings in this milestone do not automate workers or manage colonies.

## Build and run

Install a Java 21 JDK, then open this directory as a Gradle project in IntelliJ IDEA or another Java IDE. The checked-in Gradle 9.2.1 wrapper downloads the required build tools. First setup needs internet access and may take several minutes while Minecraft dependencies are prepared. No global Gradle installation is required.

On Windows (PowerShell):

```powershell
.\gradlew.bat test build
.\gradlew.bat runGameTestServer
.\gradlew.bat runClient
```

On Linux/macOS:

```sh
bash ./gradlew test build
bash ./gradlew runGameTestServer
bash ./gradlew runClient
```

The client opens the Minecraft development environment. Create a world with cheats enabled for debug generation. The mod JAR is `build/libs/livingkingdoms-0.7.0.jar`; the `-sources.jar` is for developers, not installation. Use the same mod version on clients and dedicated servers. Python and development mods are not required for the ordinary Java build or runtime.

On the development machine used for this milestone, `runClient` crashes in the AMD native OpenGL driver (`atio6axx.dll`, `EXCEPTION_ACCESS_VIOLATION`) while GLFW creates a window. Disabling NeoForge's early splash window reproduces the same crash at vanilla window creation. The graphical client and manual Save and Quit/reopen flow therefore remain unverified on that machine. Crash reports are in the ignored `run/hs_err_pid*.log` files. Headless tests do not require a working graphics driver.

For a dedicated development server:

```powershell
.\gradlew.bat runServer
```

Read the Minecraft EULA linked by the server. If you accept it, change `eula=false` to `eula=true` in `run/eula.txt` and rerun. Stop with `stop` to save cleanly. Development files and worlds live in the ignored `run/` directory. A production server also requires Java 21 and the matching NeoForge version.

## Generate and inspect the first physical settlement

Settlement commands require a player context; a direct server-console invocation reports that a player is required. `create` and `generate` require permission level 2 (operator/cheats); any player may use settlement `info` or `/kingdom reputation`. Commands are for generation/inspection, not normal quest gameplay. Encounter controls below require operator permission.

1. Find open, dry, nearly flat surface terrain. Let the nearby chunks load. Avoid forests, villages, water, and previous settlements.
2. Run `/kingdom settlement generate`. The server searches sampled positions 32–64 blocks away, within loaded chunks, and reports the settlement name, UUID, and central coordinates. If every site fails validation it changes no blocks and creates no settlement data. Move to another open area and retry.
3. Walk to the central lodestone marker. The **31 × 7 × 31** placeholder includes a Town Hall, three houses, a Blacksmith with furnace/anvil, a small Barracks, labeled signs, a plaza, and the custom Quest Board.
4. Run `/kingdom settlement info` inside or near the settlement. It reports name, ID, allied faction, level 1, and population. Population remains abstract; the single Mayor is a separate dialogue NPC, not a population simulation.
5. Meet the named Mayor in the plaza, then follow the quest steps below. The board is also available in the Functional Blocks creative tab or via `/give @s livingkingdoms:quest_board`; no recipe is provided yet.
6. Record the UUID. Save and Quit (or stop the dedicated server), reopen the same world, and run `info` there. Buildings, board, Mayor and quest state must remain. `/reload` must preserve commands and saved state.
7. Repeating `generate` in the plaza must fail when all nearby candidates overlap that territory. Move at least 128 blocks from the marker to test another physical settlement with default settings, in another suitable clearing.

`/kingdom settlement create` remains unchanged: it creates an **abstract** allied settlement at your position without placing blocks. Use it separately from physical generation; its saved territory prevents generating a village on top of it.

Generation places a foundation above the highest ground column and supports differences of at most two blocks. It does not excavate existing terrain. It rejects non-approved ground, fluids, occupied building space, block entities, non-spectator entities, world-border/build-height violations and overlapping territories. Only air and explicitly tagged vegetation can be cleared. This is a conservative terrain heuristic, not an ownership/claim system: it cannot identify who placed a dirt/stone surface. No chunks are force-loaded by gameplay generation.

Placement is checked before mutation; original states are retained for rollback of ordinary placement failures. Settlement data is added only after the exported blocks are verified. This does not provide a crash-proof transaction across chunk files and SavedData; do not interrupt the server mid-generation. Breaking or moving a marker/board does not remove or relocate the saved territory.

Territories are horizontal circles in a specific dimension, with an inclusive boundary; height does not affect lookup. `info` outside every territory reports no settlement. Names use `Haven <UUID prefix>`. Abstract creation stores the player position; physical generation stores the lodestone's position. Default radius is 48 blocks. Physical generation enforces a minimum radius derived from the template bounds plus a small margin, so every building remains inside its territory even if the configured radius is reduced.

## Play Iron Shortage

1. Right-click the named **Mayor** in the plaza. The Mayor welcomes you to that settlement and points you to the board. Dialogue is deterministic and localized.
2. Right-click the **Quest Board** normally. Chat shows **Iron Shortage**, its state, the required Iron Ingots, current inventory progress, reward and your reputation with this settlement. Viewing does not accept the quest.
3. **Crouch + right-click** the same board to accept. Chat confirms acceptance and shows the objective. The default requirement is **16 Iron Ingots**, with **8 Emeralds** and **+10 reputation** as the reward.
4. Obtain the Iron Ingots. Normal right-click shows live progress from the main inventory and offhand. Items in armor slots are not counted. You can hold the iron while interacting.
5. **Crouch + right-click** again to deliver. The server verifies the active quest, sufficient iron and capacity for the complete reward after consumption. It removes exactly the required iron, inserts the Emeralds, completes the quest and adds reputation. Insufficient iron or space changes nothing.
6. Talk to the Mayor again: the dialogue now acknowledges your help. View the board or use optional `/kingdom reputation` to see your reputation. Repeated delivery, reconnect and `/reload` cannot grant the completed reward again.

The quest is offered once **per player, per allied settlement**, keyed by UUID. Another player can complete their own instance; completing it in one settlement does not complete it in another. Boards outside an allied territory refuse quests. A board elsewhere within the same territory accesses the same player/settlement quest, not another reward. Stay close to the board and inside its territory. No commands or GUI are needed for this loop.

Existing Milestone 1 physical settlements gain a Mayor on first board use if their central lodestone is still present and a safe loaded plaza position is available. Newly generated settlements spawn the Mayor immediately. Abstract `create` records do not automatically spawn NPCs, but a manually placed board in an allied territory can offer the quest. Names are display values; renaming does not change UUID associations.

The Mayor is a vanilla Villager with a persisted Living Kingdoms role and settlement UUID, a visible name and no trades. It is stationary (`NoAI`), persistent and protected from ordinary combat for this prototype; creative players can still remove it and physical pushes can move it. `MAYOR`, `BLACKSMITH` and `GUARD` are identity concepts; only the Mayor is spawned in this milestone. Missing or unloaded recorded Mayors are not automatically replaced, which avoids duplicate NPCs. There are no schedules, professions, recruitment or autonomous worker behavior.

Quest states are `AVAILABLE`, `ACTIVE`, `COMPLETED`, `FAILED`, `EXPIRED`. Original Iron Shortage still has no timer, abandonment or repeatable reset. Inventory progress is checked on interaction, not each tick. An accepted Iron quest snapshots its terms: changing settings affects new acceptances only.

## Main quests and regional requests

Right-click the board to see **MAIN QUESTS — Chapter 1: First Steps** and **AVAILABLE REQUESTS / ACTIVE QUESTS**, beneath the compatible Iron Shortage section. Entries show source role, state, level/difficulty, objective, progress and reward. Click **Accept quest** or **Deliver / Claim reward** in chat; no operator permission or typed commands are needed. Remain within eight blocks of the actual board and inside its allied territory. UUID ownership, reach and lifecycle are checked again on every action.

One main chapter persists globally per player, anchored to their first Mayor-backed settlement:

1. **Meet the Mayor:** a real main-hand interaction completes the first step.
2. **Iron Shortage:** finish the existing delivery, default 16 iron for eight emeralds/+10 reputation. The chapter observes this same completion, including old saved completion; it does not pay again.
3. **Secure the Roads:** accept a quest bound to an actual nearby non-debug Pillager Patrol. Contribute meaningfully to defeating that specific group, then confirm victory at the board. Approximate origin coordinates guide travel. A missing/retired target fails; the main step can bind a replacement later.
4. **Return Home:** accept and claim at the chapter settlement's board for six emeralds/+5 reputation by default. The chapter never expires or repeats at another settlement.

Abstract records without a Mayor do not lock a player's main chapter. Their boards can still offer delivery requests. Only the existing Mayor is spawned; other quest source roles are metadata for future NPCs, brokered by the board.

| Dynamic request | Default objective | Source role |
| --- | --- | --- |
| Iron Supplies | 16 iron ingots | Blacksmith |
| Food Shortage | 32 wheat | Farmer |
| Building Materials | 32 logs **and** 32 stone | Citizen |
| Raiders on the Road | Actual nearby Pillager Patrol UUID | Guard Captain |
| The Restless Dead | Actual nearby Undead Horde UUID | Guard Captain |

Logs use the Minecraft `logs` item tag; stone means ordinary stone, not cobblestone. Multi-resource delivery preflights all requirements and full reward capacity on copies, ignoring armor. Refusal consumes nothing, and repeated claims/reconnects cannot repeat a payout.

Default batches contain **three dynamic slots per player/settlement**, rotated on demand after a persisted **48000-tick** (two elapsed game days) cooldown. Unaccepted offers expire after 48000 ticks; accepted requests remain active and occupy slots in later rotations. Opening another board or reconnecting cannot reroll the batch. Count is configurable 2–4; only three resource templates are available without hostiles. Combat generation uses actual indexed parties within 256 blocks of the settlement center, excluding debug and already actively targeted dynamic UUIDs. It never spawns an enemy or loads chunks.

Resource fallback weight is 3; each real party contributes a combat candidate weighted 8–12 by level. A stable player/settlement/generation seed selects without replacement. Future shortage weights are supported, but there is no simulated stockpile/resource deficit yet. Current resource requests are fallback needs; hostile activity comes from persisted world context.

Resource recommended level uses the existing regional service; combat uses rounded-up original party average. Difficulty bands are VERY_EASY 1–3, EASY 4–7, NORMAL 8–12, HARD 13–20, VERY_HARD 21–100. Dynamic rewards start at four emeralds/+5 reputation for resources or four/+3 for combat, adding +1/+1 per difficulty step, capped at 32/50. Generated terms/rewards persist. Full configurable defaults are in [the quest expansion report](docs/quest-system-expansion-validation.md).

Combat objectives become ready only on the recorded party's final defeat event, using the existing participation threshold. Every player must accept their own instance; passive/nonparticipating players cannot inherit victory. Offline participants keep ready state, which survives party cleanup. Claim at the source settlement's board pays once. Pure faction victories, unrelated mob deaths, debug groups and retirement cannot complete a quest. Quest reputation is additional to the separately deduplicated regional roaming reward.

Sources hold settlement UUID, optional NPC UUID and MAYOR/BLACKSMITH/FARMER/GUARD_CAPTAIN/CITIZEN role. Typed objectives and templates allow future quest handlers without dependence on placeholder building coordinates. No housing, NPC professions, rescue/escort AI, bosses, custom GUI or structure redesign is added.

## World storage and settings

Settlement data remains in `<world>/data/livingkingdoms_settlements.dat`. `<world>/data/livingkingdoms_quests.dat` stores player UUID → settlement UUID → quest ID/state/accepted terms, expanded quest UUIDs/objectives/sources/levels/rewards, main chapter anchors, board rotation clocks, settlement-specific reputation, Mayor associations and one-time encounter reward receipts for a batch of participating player UUIDs. `<world>/data/livingkingdoms_encounters.dat` stores party UUIDs, faction/type/origin, optional settlement association, roster/remaining UUIDs, state, threat, reward eligibility, lifecycle timestamps, damage contribution and per-dimension natural spawn cooldowns. All three use the Overworld store even for other dimensions. NPC/member identities also travel with vanilla entity NBT; health, equipment, AI and exact entity positions are not copied into party storage. There is no external database or player-object cache.

In an integrated dev client the world is under `run/saves/<world>`; on the dev server it is under `run/world`. Server settings live in `<world>/serverconfig/livingkingdoms-server.toml`:

```toml
[settlements]
radius = 48
initialPopulation = 5

[generation]
searchRange = 64
maxTerrainVariation = 2

[quests]
requiredIron = 16
rewardEmeralds = 8
reputationReward = 10

[encounters]
pillagerPatrolSize = 4
undeadHordeSize = 6
pillagerThreat = 2
undeadThreat = 3
patrolCaptain = true
normalReputation = 2
captainReputation = 4
reputationRange = 256
minimumContributionDamage = 4.0
activeLifetimeTicks = 72000
completedRetentionTicks = 1200

[encounters.natural]
enabled = true
checkIntervalTicks = 200
cooldownTicks = 2400
minimumEncounterDistance = 128
maximumNearbyGroups = 2
regionRadius = 192
minimumPlayerDistance = 48
maximumSpawnDistance = 80
maximumTrackedParties = 256
```

Settlement settings apply to new settlements. Existing territories and populations retain their saved values. Quest item quantities accept 1–2304 and reputation rewards 1–1,000,000. Patrol sizes accept 3–5, hordes 4–8 and threat ratings 1–100. Encounter rewards accept 0–1,000,000 (0 disables); size, threat and reward are snapshotted when the party spawns. Reward range is an event-time setting, 0–4096 blocks. Signed player reputation defaults to zero; no tiers or decay exist yet.

Normal Minecraft autosave and shutdown write dirty data; interactions do not force disk I/O on every click. Settlement schema remains 1; legacy `allied` faction IDs read as `ALLIED_KINGDOM` and new writes use `allied_kingdom`. Quest/reputation schema 4 reads schemas 1, 2 and 3, preserving legacy Iron, reputation, Mayors and encounter receipts while adding expanded quests and board clocks. Encounter schema 2 reads schema 1 and adds lifecycle/contribution/cooldown metadata; legacy unknown lifetimes start conservatively at first maintenance. Older mod versions cannot read the new schemas. Like ordinary Minecraft storage, independent player/entity/chunk/SavedData files are not a crash-proof transaction; use normal save/shutdown for durability.

## Factions and dynamic roaming encounters

Shared `faction.Faction` supports **ALLIED_KINGDOM**, **PILLAGER**, **BANDIT** and **UNDEAD**. Settlements and parties use that same identity. Bandits need no settlement; their roaming content is not implemented yet. Pillagers retain their organized military direction, Bandits will be opportunistic roaming threats, and Undead will favor chaotic nighttime threats. Adding a faction/type requires content registration, without changing player quests or the reputation relationship model.

`FactionRelation` supports `ALLY`, `NEUTRAL`, `HOSTILE`. A central symmetric table treats a faction as allied with itself. Allied Kingdoms are hostile to Pillagers, Bandits and Undead; Pillagers and Bandits are each hostile to Undead; Pillager/Bandit is neutral. `FactionRelations.getRelation` and `isHostile` are shared by a localized target goal and a target-change guard. Controlled allies/neutral entities cannot become native retaliation targets. Unrelated vanilla mobs retain their targeting and are never inferred to be encounter members.

| Playable type | Vanilla members | Default threat | Eligible regional reward |
| --- | --- | --- | --- |
| `pillager_patrol` | 4 Pillagers, including one configured Captain | 2 | +4 with Captain, +2 without |
| `undead_horde` | 6 alternating adult Zombies and Skeletons | 3 | +2 |

Threat is configurable metadata shared across factions. Levels use the separate progression policy below; native AI, attacks and existing equipment remain, with small spawn-time gear additions. Undead burn during the day; test their intended combat at night. Controlled Pillagers and Undead can acquire and fight each other with vanilla crossbow/melee attacks. An allied NPC tagged `GUARD` can participate through the same resolver; guards are not spawned as gameplay content yet. Mayors keep their existing protected stationary behavior. No custom models or advanced battle AI is added. No physical Pillager camp exists in the inspected branch; future camps can be hostile Settlements under the shared faction model.

Operator controls:

```text
/kingdom encounter spawn pillager_patrol
/kingdom encounter spawn undead_horde
/kingdom encounter spawn pillager_patrol reward_test
/kingdom encounter spawn undead_horde reward_test
/kingdom encounter info <party-uuid>
```

Default spawned parties are explicitly **debug** and give **no reputation**. `reward_test` is an explicit operator-only reward test; it creates an eligible debug party and reports that mode. Normal players cannot spawn either variant. Spawning searches nearby loaded positions 16–32 blocks away, plans the entire small group on dry solid ground, checks local collisions/borders/heights, and changes no terrain. Peaceful difficulty refuses hostile spawns. Partial additions are removed if spawning fails.

Natural roaming is enabled conservatively in **active Overworld wilderness**. Survival/adventure players trigger proposals; creative/spectator players do not. `doMobSpawning=false`, Peaceful and the natural enable setting suppress them. Daytime selects Pillager Patrols. At night, 75% of proposals select small Undead Hordes; their complete footprint must have block light at most 7. Failed nighttime Undead proposals do not fall back to a different type. No biome, roads or hostile-territory rules are implemented.

Default spawning checks every **200 ticks** (10 seconds), chooses one eligible player in rotation and tries at most **three** sites. Every attempt spends a persisted **2400-tick** (2-minute) dimension cooldown even when all sites fail. Candidate centers are 54–80 blocks from the selected player, including a six-block group safety margin; every online player must be at least 54 blocks from the center. This ensures members remain beyond the configured 48-block minimum. The whole footprint must already be loaded and entity-ticking; proposals never request chunk tickets or chunk generation. Terrain safety uses the existing spawner. Allied territory plus a 22-block buffer excludes candidates, including larger saved radii. Vanilla patrol/monster mechanics are not replaced.

Tracked active origins must be separated by **128 blocks**, with fewer than **two** active groups within **192 blocks** of both the selected player and candidate. Debug groups count toward these limits. A **256-record global cap** stops natural proposals from adding metadata when too many parties remain, including unloaded parties and defeated records awaiting cleanup. These limits use approximate persisted origins, rather than exact live positions; players do not reset limits or cooldowns by reconnecting. Invalid distance settings (minimum plus margin exceeding maximum) safely produce no encounters.

To test regional credit, generate an allied village and fight an encounter in open terrain within 256 blocks of its center. Default debug commands give no reputation; `reward_test` and natural encounters are eligible. Player attacks and player-owned projectiles record post-reduction damage on authoritative living roster members. A player needs a configured **4-damage score** (two hearts), summed over that party's recent engagement, with a hit in the last **6000 ticks** (five server minutes). Each hit is capped to the member's maximum health; lethal overkill is a capped damage score rather than exact remaining HP loss. Inactivity beyond five minutes resets the participant's score on their next hit. At most 64 participant UUIDs are stored. Spectators, passive observers, pets and unrelated vanilla mobs receive no inferred participation.

On the first full-party defeat, **all eligible participants** receive the party's full configured reputation (+2 normal, +4 Captain total), even if another faction or the environment kills the last member. Only the relevant allied settlement gets credit: prefer its saved association if still eligible and in range, otherwise resolve the nearest allied center at the final death position. No nearby allied center means no reward. Faction-only fighting gives players no reputation. Participants can disconnect and still receive their persisted UUID reputation if their last damage is recent; online recipients get a chat message. One batch receipt is saved with all recipients and reputation in the same quest store; later duplicate deaths or late claims cannot append recipients or repeat credit.

Active origin lookup and allied territory lookup use derived 256-block metadata buckets. Deaths/damage/conversions resolve by UUID index. Controlled mob AI searches at most 16 blocks every 40 mob ticks with staggered starts; its vanilla target priorities and attacks remain. Minecraft runs these goals only for active entities. There is no global entity scan, forced chunk loading, unloaded combat simulation or settlement scan per tick.

Every **1200 ticks**, metadata maintenance removes defeated records/receipts after the configured 1200-tick retention and abandons still-active groups after **72000 ticks** (one server hour). Abandonment grants no defeat reward. It discards only already-loaded roster members through direct UUID lookups; unloaded retired members are refused when their chunks next load. Reputation and quest completion remain. Ordinary unloading alone is not death. Manual deletion can leave an active record until its lifetime expires. Legacy encounters receive a fresh lifetime when first maintained. Debug groups have the same lifetime. Server/offline time does not advance these durations.

`EncounterSpawner.spawnAt` remains the shared entity path. `NaturalEncounterSpawner.attemptAt` accepts a future controlled regional proposal under the same cooldown/safety rules. `EncounterQueries.activeNear` and `find` return UUID, faction, type, region, threat, level summary and optional hostile settlement source without touching entity chunks. Today's allied reward association is not exposed as a hostile source. Regional combat requests now consume these party identifiers and levels through the shared quest service. Independent Minecraft save files remain non-atomic across crashes: party, entity and reward files can be interrupted at different points, including during cleanup; normal autosave/reload persistence is covered by tests.

## Progression core

Only Living Kingdoms encounter members receive hostile progression. Natural and debug Pillager Patrols and Undead Hordes use the same `ProgressionService`; unrelated vanilla mobs, players and allied NPCs receive no automatic levels. `LevelValue` is a shared 1–100 identity for future profession-specific policies, while the default maximum for new hostile members is **30**.

`RegionalDifficultyService.at` calculates difficulty on demand from horizontal distance to a configured origin (default X=0, Z=0), elapsed Overworld game ticks and indexed nearby active encounters. Player gear is not an input. Defaults are base 1, **+1 per 1024-block band** capped at +24, **+1 per 10 elapsed game days** capped at +3, and **+1 per four nearby threat points** capped at +2 within 192 blocks. Debug parties do not contribute activity. Sleeping and `/time set` do not advance world age. A caller-supplied hostile settlement tier is supported by the pure calculation (+2 per tier, capped at +10); current runtime supplies zero because camps are future content.

In a young region without activity, 0–1023 blocks gives regional level 1; 4096 gives 5; 9216 gives 10; 19456 gives 20; and 24576 or farther gives 25. Total regional difficulty is capped at 30. Each new member rolls within **regional level ±2**, clamped to 1–30; groups have varied levels when the configured window permits it. `HostileParty.levels()` stores original roster count, minimum, maximum and average, retained after deaths/conversions rather than recomputed from surviving entities.

Bonuses use `level - 1`: health +2.5% per level (cap +75%), melee/native arrow damage +2% (cap +50%), armor +0.15 points (cap +4), movement +0.2% (cap +8%). Level 1 has zero stat bonuses. Level 5 gives +10% health/+8% damage; level 10 gives +22.5%/+18%. Stable permanent modifier IDs prevent stacking on reload and preserve other mods' modifiers. Ranged accuracy and vanilla AI are unchanged. Caps constrain this system's additions, not unrelated mods or native equipment.

Empty chest/head slots gain leather at level 5, chainmail at 10 and iron at 20. Undead Zombies with an empty weapon slot gain an iron sword from level 10. Existing gear, bows, crossbows and Captain banners remain. At level 15+, an 8% spawn-time chance adds Protection I to an unenchanted chest piece. Added gear has base drop chance zero, though native Looting can still affect drops. At level 20+, a 5% elite chance adds up to +10% health/damage and +1 armor inside the same caps. No abilities or custom gear are added.

All defaults are configurable in the existing per-world `livingkingdoms-server.toml`, under `progression`, `progression.stats`, `progression.equipment` and `progression.elite`. Full keys/defaults and validation evidence are in [the progression report](docs/progression-core-validation.md). Levels, elite status, stat bonuses and native equipment are saved at spawn; configuration changes affect new members. Old managed mobs and parties without progression default to level 1 with vanilla bonuses, preserving wounds and earlier quest/reputation state. Encounter schema stays 2 with an optional validated summary. Progression did not change settlement/quest schemas; quest expansion now advances quest storage to schema 4.

Read-only operator feedback:

```text
/kingdom progression info
/kingdom progression info <entity-uuid>
/kingdom progression info @e[type=minecraft:pillager,sort=nearest,limit=1]
/kingdom encounter info <party-uuid>
```

The first command reports regional level and contributions; an entity argument reports its persistent profile; encounter info includes roster min/max/average. `progression.showLevelNames=false` keeps normal gameplay names uncluttered; enabling it labels new members and gives elites a gold prefix. There is no level-setting/reroll command. Runtime calculation occurs once per spawn group or explicit query, restoration on entity join, and ranged scaling on projectile join. No progression tick loop, global entity search, settlement scan or chunk loading is added.

Quest policies reuse party levels and the existing `RegionalDifficulty` service; future objectives can also read target `EntityProgression.Profile`. Future allied NPCs can reuse `LevelValue` with their own role policy; hostile modifiers require encounter identity and are not applied to civilian professions. Settlement progression, player levels, bosses and raids remain future work.

## Architecture

| Package | Responsibility |
| --- | --- |
| `dev.livingkingdoms` | Common NeoForge bootstrap and registration |
| `config` | Per-world NeoForge server settings |
| `settlement.domain` | Immutable, Minecraft-independent `Settlement` and `Territory` |
| `faction` | Shared stable faction identities, centralized relationships and local controlled-mob targeting |
| `settlement.persistence` | NBT conversion and Overworld `SavedData` ownership |
| `settlement.SettlementGenerator` | Server service that places validated structures and then persists the settlement |
| `structure` | Native template loading, bounded validation and read-only site planning |
| `block` | Quest Board registration, item, directional shape and server interaction |
| `command` | Brigadier adapters for player requests and feedback |
| `quest.domain` | Immutable quest terms/progress, stable quest IDs, lifecycle and type |
| `quest.persistence` | Guarded player/settlement quests, reputation, Mayor associations and encounter reward receipts |
| `quest` | Board interaction routing and server inventory delivery service |
| `quest.expansion.domain` | Typed templates/sources/objectives, chapter order, weighted candidates and capped reward policies |
| `quest.expansion.persistence` | Strict expanded objective/source/clock serialization inside the existing quest store |
| `quest.expansion` | On-demand board generation, main/legacy bridge, normal player actions and claim orchestration |
| `npc` | Vanilla Villager role/UUID identity, safe Mayor association and contextual dialogue |
| `encounter.domain` | Immutable faction/type/origin/roster/state/threat metadata |
| `encounter.persistence` | Guarded party storage, UUID/spatial indexes, contributions, lifecycle and cooldowns |
| `encounter` | Natural/debug vanilla spawning, damage/death/conversion events, bounded cleanup and regional query API |
| `progression.domain` | Shared levels, party snapshots, pure regional/stat/equipment rules and bounded distribution |
| `progression` | On-demand regional inputs, spawn progression, persistent modifiers, equipment and projectile join hooks |

There are no client imports in common code and no global settlement cache. Each world owns its data through Minecraft's `DimensionDataStorage`. Commands and gameplay services run on the server thread; storage entrypoints check thread ownership. Immutable records and snapshots prevent changes without dirty-marked storage mutations. UUID lookup is map-backed, nearest allied centers are spatially indexed, and existing territory/overlap queries scan metadata only on requests/interactions. Natural proposals have a cheap tick guard; low-frequency maintenance scans tracked metadata only. There is no global entity polling, external database or gameplay chunk loading.

Persistence validates required fields, stable IDs, bounds, UUID uniqueness, non-overlapping territories, roster consistency and receipt uniqueness. Unsupported or malformed data is rejected. If Minecraft catches a loading error, guarded factories refuse to create fresh data over existing files. Quest schemas 1/2/3-to-4 and encounter schema 1-to-2 migrations are explicit and tested.

Future settlement resources and buildings can extend the existing aggregate using typed values and versioned migrations. Put progression rules in focused domain/application services and store replacements through dirty-marked persistence methods. Quest IDs/types and per-relationship quest entries allow later quests to share settlement reputation without sharing individual completion. Expanded typed resource/party/meeting/return objectives share handlers; the original Iron API remains as a compatibility bridge. Add future objective handlers only with their actual gameplay. NPC identities keep roles independent of vanilla professions. Conquest should use an explicit lifecycle alongside faction (hostile, defeated, liberating, allied outpost, village), with validated transitions.

## Structures and future natural generation

`SettlementTemplate` uses Minecraft's `StructureTemplateManager` to load `livingkingdoms:allied/test_settlement` from a native NBT resource. It validates a single palette, no saved entities or fluids, bounded size (up to 32 × 12 × 32), a full solid foundation, one lodestone at local Y=1 and exactly one Quest Board. Placement has no rotation in this milestone. Authored state shapes are preserved; replacement exports must have valid doors, beds and other neighboring block states. The block/sign data is placed through Minecraft's official template API.

Natural placement is **not enabled** yet. `SettlementGenerator.generateAt(ServerLevel, BlockPos)` is the shared path for future biome/spacing selection; `generateNear` adds only debug site search. A future worldgen module can propose candidate centers and dispatch placement after chunks are available on the server thread. It must not mutate SavedData from chunk-generation worker threads. Structure art, biome distribution, and persistence therefore remain separate responsibilities without a premature worldgen manager.

See [the template layout and WorldEdit export workflow](docs/structure-layout.md). WorldEdit can be used to build/paste in a disposable design world, then vanilla structure blocks export the final `.nbt`. Native `.schem` files require conversion, not renaming. WorldEdit, JourneyMap and JEI are optional development tools; Living Kingdoms has no dependencies on them.

Regional difficulty now has a configurable pure calculation of distance, world age, activity and caller-supplied hostile settlement tier, independent of entity AI. Conquest inputs, advanced NPC progression and conquest transitions are future extensions.

## Tests and community workflow

`test` uses JUnit 5 with ModDevGradle's NeoForge test environment. The 153 tests retain all previous settlement/quest/faction/encounter/progression coverage and add main ordering/prerequisites, dynamic weighting, resource variants, difficulty/rewards, lifecycle/rotation, UUID participation, multiplayer ownership, exchange preflight and schema-4 migration. Existing cases cover spatial lookup, UUID/state invariants, reward deduplication, atomic multiplayer batches, contribution expiry, lifecycle cleanup and natural gates. Real `DimensionDataStorage` save/reopen tests verify persistence and that corrupt/future-schema files remain unchanged after rejected loads.

`runGameTestServer` starts a headless Minecraft world and loads a separate test mod from `src/gametest`. Forty-one GameTests retain all previous physical/quest/Mayor/encounter/natural/faction/progression coverage. Seven new cases verify generic multi-resource exchanges/live log tags, real board actions for nonoperators, source/reach/ownership gates, disk reload/rotation, all four main steps, actual participant-based Pillager/Undead victory and failure of missing/debug/retired targets. All test-only terrain preparation, explicit chunk loading, classes and test-mod resources are excluded from the production JAR and normal client/server runs. Its world lives in `runs/gametest`, separate from normal dev worlds. GitHub Actions runs `test build runGameTestServer` on Java 21 and uploads the JARs. The graphical Save and Quit/reopen check remains manual.

Open an issue with Minecraft/NeoForge/mod versions, reproduction steps, and a relevant log excerpt. Keep contributions scoped and run `test build runGameTestServer` before submitting a pull request. The GitHub workflow is configured for pushes and pull requests. The mod currently reserves all rights; a community distribution license must be selected by the project owner before public release.

## Official references

- [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [SavedData in NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [ModDevGradle runs and JUnit support](https://github.com/neoforged/ModDevGradle)

Local results and known runtime limits: [Milestone 0](docs/validation.md), [Milestone 1](docs/milestone-1-validation.md), [Milestone 2](docs/milestone-2-validation.md), [hostile factions and encounters](docs/hostile-encounters-validation.md), [dynamic world encounters](docs/dynamic-encounters-validation.md), [progression core](docs/progression-core-validation.md), [quest system expansion](docs/quest-system-expansion-validation.md).

## Next milestone

Recommended next gameplay milestone: one Tier 1 Pillager camp with a regional quest, reusing shared factions, settlement metadata, progression and quest objectives. Keep raids, conquest, housing and advanced NPC professions for later. The next milestone has not been started.
