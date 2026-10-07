# Living Kingdoms

Minecraft Java **1.21.1**, **Java 21**, **NeoForge 21.1.252**. Version 0.4.0 adds shared faction identities and controlled roaming hostile encounters to the existing settlements, Mayors, Iron Shortage quest and player reputation. All gameplay state is owned by the server. Advanced professions, diplomacy, economy, conquest, armies, custom GUI and external AI services remain outside the current scope.

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

The client opens the Minecraft development environment. Create a world with cheats enabled for debug generation. The mod JAR is `build/libs/livingkingdoms-0.4.0.jar`; the `-sources.jar` is for developers, not installation. Use the same mod version on clients and dedicated servers. Python and development mods are not required for the ordinary Java build or runtime.

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

Quest states are `AVAILABLE`, `ACTIVE`, `COMPLETED`, `FAILED`. Failure is supported by the storage lifecycle, but Iron Shortage currently has no timer/failure trigger, abandonment or repeatable reset. Inventory progress is checked on interaction, not each tick. An accepted quest snapshots its terms: changing server settings affects new acceptances only.

## World storage and settings

Settlement data remains in `<world>/data/livingkingdoms_settlements.dat`. `<world>/data/livingkingdoms_quests.dat` stores player UUID → settlement UUID → quest ID/state/accepted terms, settlement-specific reputation, Mayor associations and one-time encounter reward receipts. `<world>/data/livingkingdoms_encounters.dat` stores party UUIDs, faction/type/origin, optional settlement association, roster/remaining UUIDs, state, threat and reward eligibility. All three use the Overworld store even for other dimensions. NPC/member identities also travel with vanilla entity NBT; health, equipment, AI and exact entity positions are not copied into party storage. There is no external database or player-object cache.

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
```

Settlement settings apply to new settlements. Existing territories and populations retain their saved values. Quest item quantities accept 1–2304 and reputation rewards 1–1,000,000. Patrol sizes accept 3–5, hordes 4–8 and threat ratings 1–100. Encounter rewards accept 0–1,000,000 (0 disables); size, threat and reward are snapshotted when the party spawns. Reward range is an event-time setting, 0–4096 blocks. Signed player reputation defaults to zero; no tiers or decay exist yet.

Normal Minecraft autosave and shutdown write dirty data; interactions do not force disk I/O on every click. Settlement schema remains 1; legacy `allied` faction IDs read as `ALLIED_KINGDOM` and new writes use `allied_kingdom`. Quest/reputation schema 2 reads schema 1 and preserves its quests, reputation and Mayors; encounter storage starts at schema 1. Older mod versions cannot read all new faction IDs or schema 2. Like ordinary Minecraft storage, independent player/entity/chunk/SavedData files are not a crash-proof transaction; use normal save/shutdown for durability.

## Factions and controlled roaming encounters

Shared `faction.Faction` supports **ALLIED_KINGDOM**, **PILLAGER**, **BANDIT** and **UNDEAD**. Settlements and parties use that same identity. Bandits need no settlement; their roaming content is not implemented yet. Pillagers retain their organized military direction, Bandits will be opportunistic roaming threats, and Undead will favor chaotic nighttime threats. Adding a faction/type requires content registration, without changing player quests or the reputation relationship model.

`FactionRelation` supports `ALLY`, `NEUTRAL`, `HOSTILE`. The initial pure policy treats a faction as allied with itself, allied kingdoms versus these hostile factions as hostile, and different hostile factions as neutral. This is a foundation for future rules; it does not override vanilla targeting or implement diplomacy.

| Playable type | Vanilla members | Default threat | Eligible regional reward |
| --- | --- | --- | --- |
| `pillager_patrol` | 4 Pillagers, including one configured Captain | 2 | +4 with Captain, +2 without |
| `undead_horde` | 6 alternating adult Zombies and Skeletons | 3 | +2 |

Threat is configurable metadata shared across factions, not an entity-class damage multiplier. Members retain vanilla equipment, AI, drops and behavior. Undead burn during the day; test their intended combat at night. No custom models, new combat AI or automatic roaming population is added. No physical Pillager camp exists in the inspected branch; future camps can be hostile Settlements under the shared faction model.

Operator controls:

```text
/kingdom encounter spawn pillager_patrol
/kingdom encounter spawn undead_horde
/kingdom encounter spawn pillager_patrol reward_test
/kingdom encounter spawn undead_horde reward_test
/kingdom encounter info <party-uuid>
```

Default spawned parties are explicitly **debug** and give **no reputation**. `reward_test` is an explicit operator-only reward test; it creates an eligible debug party and reports that mode. Normal players cannot spawn either variant. Spawning searches nearby loaded positions 16–32 blocks away, plans the entire small group on dry solid ground, checks local collisions/borders/heights, and changes no terrain. Peaceful difficulty refuses hostile spawns. Partial additions are removed if spawning fails.

To test regional credit, first generate an allied village, then move to an open clearing within 256 blocks of its center. Use a `reward_test` command and defeat **all** members. Only the player who causes the final member's death receives credit in this first version; player-owned projectiles count, while environmental final deaths do not. Other contributors receive no credit yet. This policy is isolated in `EncounterEvents` so damage participation or group credit can be added later.

Credit goes to the party's associated allied settlement if it is still in the defeat dimension and within the configured center-distance range; otherwise the nearest eligible allied settlement at the final death position is used. Without a relevant nearby ally, no reputation is awarded. Height and territory radius do not affect nearest lookup. Killing each member, repeated death notifications, another player claiming the same party, reconnect and ordinary reload cannot duplicate the group reward. Captain reputation is the **total** group reward, not another per-member bonus. Check `/kingdom reputation` inside the rewarded village.

Nearest-allied queries use a derived per-world/per-dimension 256-block metadata index, inclusive horizontal center distance and stable UUID tie-breaking. They read no chunks and run only when spawning/resolving events. Entity deaths resolve through a UUID roster index. No encounter tick loop or global entity scan is used. Vanilla conversion events replace member UUIDs without counting a defeat. Unloading or discarding a mob is not treated as death, so manually removed members can leave an unresolved party; no forced chunk recovery or cleanup policy exists yet.

`EncounterSpawner.spawnAt` is the server-thread entrypoint for future controlled rules. Biome/road/night/hostile-territory selection can propose loaded candidates without being coupled to death tracking or reputation. Natural spawning remains disabled.

## Architecture

| Package | Responsibility |
| --- | --- |
| `dev.livingkingdoms` | Common NeoForge bootstrap and registration |
| `config` | Per-world NeoForge server settings |
| `settlement.domain` | Immutable, Minecraft-independent `Settlement` and `Territory` |
| `faction` | Shared stable faction identities and future relationship policy |
| `settlement.persistence` | NBT conversion and Overworld `SavedData` ownership |
| `settlement.SettlementGenerator` | Server service that places validated structures and then persists the settlement |
| `structure` | Native template loading, bounded validation and read-only site planning |
| `block` | Quest Board registration, item, directional shape and server interaction |
| `command` | Brigadier adapters for player requests and feedback |
| `quest.domain` | Immutable quest terms/progress, stable quest IDs, lifecycle and type |
| `quest.persistence` | Guarded player/settlement quests, reputation, Mayor associations and encounter reward receipts |
| `quest` | Board interaction routing and server inventory delivery service |
| `npc` | Vanilla Villager role/UUID identity, safe Mayor association and contextual dialogue |
| `encounter.domain` | Immutable faction/type/origin/roster/state/threat metadata |
| `encounter.persistence` | Guarded party storage and derived member UUID index |
| `encounter` | Controlled vanilla spawning and localized death/conversion events |

There are no client imports in common code and no global settlement cache. Each world owns its data through Minecraft's `DimensionDataStorage`. Commands and gameplay services run on the server thread; storage entrypoints check thread ownership. Immutable records and snapshots prevent changes without dirty-marked storage mutations. UUID lookup is map-backed, nearest allied centers are spatially indexed, and existing territory/overlap queries scan metadata only on requests/interactions. There is no mod tick loop, external database, or gameplay chunk loading.

Persistence validates required fields, stable IDs, bounds, UUID uniqueness, non-overlapping territories, roster consistency and receipt uniqueness. Unsupported or malformed data is rejected. If Minecraft catches a loading error, guarded factories refuse to create fresh data over existing files. Quest schema 1-to-2 migration is explicit and tested.

Future settlement resources and buildings can extend the existing aggregate using typed values and versioned migrations. Put progression rules in focused domain/application services and store replacements through dirty-marked persistence methods. Quest IDs/types and per-relationship quest entries allow later quests to share settlement reputation without sharing individual completion. Delivery terms are specialized to Iron Shortage for now; add new typed objectives/handlers when another quest type actually needs them. NPC identities keep roles independent of vanilla professions. Conquest should use an explicit lifecycle alongside faction (hostile, defeated, liberating, allied outpost, village), with validated transitions.

## Structures and future natural generation

`SettlementTemplate` uses Minecraft's `StructureTemplateManager` to load `livingkingdoms:allied/test_settlement` from a native NBT resource. It validates a single palette, no saved entities or fluids, bounded size (up to 32 × 12 × 32), a full solid foundation, one lodestone at local Y=1 and exactly one Quest Board. Placement has no rotation in this milestone. Authored state shapes are preserved; replacement exports must have valid doors, beds and other neighboring block states. The block/sign data is placed through Minecraft's official template API.

Natural placement is **not enabled** yet. `SettlementGenerator.generateAt(ServerLevel, BlockPos)` is the shared path for future biome/spacing selection; `generateNear` adds only debug site search. A future worldgen module can propose candidate centers and dispatch placement after chunks are available on the server thread. It must not mutate SavedData from chunk-generation worker threads. Structure art, biome distribution, and persistence therefore remain separate responsibilities without a premature worldgen manager.

See [the template layout and WorldEdit export workflow](docs/structure-layout.md). WorldEdit can be used to build/paste in a disposable design world, then vanilla structure blocks export the final `.nbt`. Native `.schem` files require conversion, not renaming. WorldEdit, JourneyMap and JEI are optional development tools; Living Kingdoms has no dependencies on them.

Future difficulty remains a configurable, pure calculation of distance, settlement tier, world age and conquests, independent of entity code. Advanced NPC progression and conquest transitions are outside this milestone.

## Tests and community workflow

`test` uses JUnit 5 with ModDevGradle's NeoForge test environment. The 59 tests retain settlement/quest coverage and add shared faction serialization, legacy IDs, relationship policy, spatial lookup boundaries/ties/dimensions, party invariants/UUID uniqueness/death/conversion state, receipt deduplication and schema migration. Real `DimensionDataStorage` save/reopen tests verify persistence and that corrupt/future-schema files remain unchanged after rejected loads.

`runGameTestServer` starts a headless Minecraft world and loads a separate test mod from `src/gametest`. Seventeen GameTests retain physical/command/quest/Mayor coverage and add both encounter commands, operator gating, vanilla entity UUID/tag persistence, real final-member combat deaths, one-time regional credit, multiplayer safety, unrelated/debug/environmental deaths, vanilla Zombie conversion, absence of nearby allies and loaded-only safe spawning. All test-only terrain preparation, explicit chunk loading, classes and test-mod resources are excluded from the production JAR and normal client/server runs. Its world lives in `runs/gametest`, separate from normal dev worlds. GitHub Actions runs `test build runGameTestServer` on Java 21 and uploads the JARs. The graphical Save and Quit/reopen check remains manual.

Open an issue with Minecraft/NeoForge/mod versions, reproduction steps, and a relevant log excerpt. Keep contributions scoped and run `test build runGameTestServer` before submitting a pull request. The GitHub workflow is configured for pushes and pull requests. The mod currently reserves all rights; a community distribution license must be selected by the project owner before public release.

## Official references

- [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [SavedData in NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [ModDevGradle runs and JUnit support](https://github.com/neoforged/ModDevGradle)

Local results and known runtime limits: [Milestone 0](docs/validation.md), [Milestone 1](docs/milestone-1-validation.md), [Milestone 2](docs/milestone-2-validation.md), [hostile factions and encounters](docs/hostile-encounters-validation.md).

## Next milestone

Next gameplay milestone: one Quest Board mission to defeat a regional hostile party, using the existing UUID-linked encounter and reputation foundation. Keep worldwide natural spawning, advanced combat, diplomacy, armies and conquest for later. This combat quest has not been started.
