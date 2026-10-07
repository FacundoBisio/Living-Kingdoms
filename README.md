# Living Kingdoms

Minecraft Java **1.21.1**, **Java 21**, **NeoForge 21.1.252**. Milestone 1 adds a small physical allied settlement and an interactive Quest Board placeholder to Milestone 0's server-owned persistence. No quests, professions, custom NPCs, economy, conquest, armies, GUI, or external AI services are active.

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

The client opens the Minecraft development environment. Create a world with cheats enabled for debug generation. The mod JAR is `build/libs/livingkingdoms-0.2.0.jar`; the `-sources.jar` is for developers, not installation. Use the same mod version on clients and dedicated servers. Python and development mods are not required for the ordinary Java build or runtime.

On the development machine used for this milestone, `runClient` crashes in the AMD native OpenGL driver (`atio6axx.dll`, `EXCEPTION_ACCESS_VIOLATION`) while GLFW creates a window. Disabling NeoForge's early splash window reproduces the same crash at vanilla window creation. The graphical client and manual Save and Quit/reopen flow therefore remain unverified on that machine. Crash reports are in the ignored `run/hs_err_pid*.log` files. Headless tests do not require a working graphics driver.

For a dedicated development server:

```powershell
.\gradlew.bat runServer
```

Read the Minecraft EULA linked by the server. If you accept it, change `eula=false` to `eula=true` in `run/eula.txt` and rerun. Stop with `stop` to save cleanly. Development files and worlds live in the ignored `run/` directory. A production server also requires Java 21 and the matching NeoForge version.

## Generate and inspect the first physical settlement

All commands require a player context; a direct server-console invocation reports that a player is required. `create` and `generate` require permission level 2 (operator/cheats); any player may use `info`.

1. Find open, dry, nearly flat surface terrain. Let the nearby chunks load. Avoid forests, villages, water, and previous settlements.
2. Run `/kingdom settlement generate`. The server searches sampled positions 32–64 blocks away, within loaded chunks, and reports the settlement name, UUID, and central coordinates. If every site fails validation it changes no blocks and creates no settlement data. Move to another open area and retry.
3. Walk to the central lodestone marker. The **31 × 7 × 31** placeholder includes a Town Hall, three houses, a Blacksmith with furnace/anvil, a small Barracks, labeled signs, a plaza, and the custom Quest Board.
4. Run `/kingdom settlement info` inside or near the settlement. It reports name, ID, allied faction, level 1, and population. Population is abstract; no NPCs are spawned yet.
5. Right-click the Quest Board by the plaza, with an empty hand or an item. It displays **"Quest Board — No quests available yet."** There is no GUI. The board is also available in the Functional Blocks creative tab or via `/give @s livingkingdoms:quest_board`; no recipe is provided yet.
6. Record the UUID. Save and Quit (or stop the dedicated server), reopen the same world, and run `info` there. The buildings, board and settlement must remain. `/reload` must preserve the three commands and saved state.
7. Repeating `generate` in the plaza must fail when all nearby candidates overlap that territory. Move at least 128 blocks from the marker to test another physical settlement with default settings, in another suitable clearing.

`/kingdom settlement create` remains unchanged: it creates an **abstract** allied settlement at your position without placing blocks. Use it separately from physical generation; its saved territory prevents generating a village on top of it.

Generation places a foundation above the highest ground column and supports differences of at most two blocks. It does not excavate existing terrain. It rejects non-approved ground, fluids, occupied building space, block entities, non-spectator entities, world-border/build-height violations and overlapping territories. Only air and explicitly tagged vegetation can be cleared. This is a conservative terrain heuristic, not an ownership/claim system: it cannot identify who placed a dirt/stone surface. No chunks are force-loaded by gameplay generation.

Placement is checked before mutation; original states are retained for rollback of ordinary placement failures. Settlement data is added only after the exported blocks are verified. This does not provide a crash-proof transaction across chunk files and SavedData; do not interrupt the server mid-generation. Breaking or moving a marker/board does not remove or relocate the saved territory.

Territories are horizontal circles in a specific dimension, with an inclusive boundary; height does not affect lookup. `info` outside every territory reports no settlement. Names use `Haven <UUID prefix>`. Abstract creation stores the player position; physical generation stores the lodestone's position. Default radius is 48 blocks. Physical generation enforces a minimum radius derived from the template bounds plus a small margin, so every building remains inside its territory even if the configured radius is reduced.

World data is saved in `<world>/data/livingkingdoms_settlements.dat`, including settlements in the Nether and End. In an integrated dev client the world is under `run/saves/<world>`; on the dev server it is under `run/world`. Server settings live in `<world>/serverconfig/livingkingdoms-server.toml`:

```toml
[settlements]
radius = 48
initialPopulation = 5

[generation]
searchRange = 64
maxTerrainVariation = 2
```

Settings apply to newly created settlements. Existing territories and populations retain their saved values. Normal Minecraft autosave and world shutdown write dirty data; commands do not force disk I/O on each invocation. The Settlement/Territory representation and SavedData schema remain version 1; existing Milestone 0 worlds require no settlement migration.

## Architecture

| Package | Responsibility |
| --- | --- |
| `dev.livingkingdoms` | Common NeoForge bootstrap and registration |
| `config` | Per-world NeoForge server settings |
| `settlement.domain` | Immutable, Minecraft-independent `Settlement`, `Territory`, and `Faction` |
| `settlement.persistence` | NBT conversion and Overworld `SavedData` ownership |
| `settlement.SettlementGenerator` | Server service that places validated structures and then persists the settlement |
| `structure` | Native template loading, bounded validation and read-only site planning |
| `block` | Quest Board registration, item, directional shape and server interaction |
| `command` | Brigadier adapters for player requests and feedback |

There are no client imports in common code and no global settlement cache. Each world owns its data through Minecraft's `DimensionDataStorage`. The command handlers run on the server thread; the storage entrypoint checks thread ownership. Immutable records and immutable snapshots prevent callers from changing values without a dirty-marked storage mutation. UUID lookup is map-backed; territory queries currently scan the stored settlements only when a generation request or command is run. There is no tick loop, external database, or gameplay chunk loading. Add a dimension/chunk spatial index when real gameplay queries justify it.

Persistence uses explicit stable faction identifiers and schema version 1. Loading validates required fields, bounds, UUID uniqueness, and non-overlapping territories. Unsupported or malformed data is rejected. If Minecraft catches a loading error, a guarded factory refuses to create fresh data over an existing file. Back up worlds before future migrations; schema upgrades will need explicit migration code.

Future settlement resources, buildings, and NPC memberships can extend this aggregate using typed values and a versioned migration. Put progression rules in focused domain/application services; store resulting replacements through dirty-marked persistence methods. Professions and capability unlocks belong to NPC progression, separate from settlement level. Conquest should use an explicit lifecycle alongside faction (hostile, defeated, liberating, allied outpost, village), with validated transitions. Quest state should have its own server-owned model/storage and reference settlements by UUID.

## Structures and future natural generation

`SettlementTemplate` uses Minecraft's `StructureTemplateManager` to load `livingkingdoms:allied/test_settlement` from a native NBT resource. It validates a single palette, no saved entities or fluids, bounded size (up to 32 × 12 × 32), a full solid foundation, one lodestone at local Y=1 and exactly one Quest Board. Placement has no rotation in this milestone. Authored state shapes are preserved; replacement exports must have valid doors, beds and other neighboring block states. The block/sign data is placed through Minecraft's official template API.

Natural placement is **not enabled** yet. `SettlementGenerator.generateAt(ServerLevel, BlockPos)` is the shared path for future biome/spacing selection; `generateNear` adds only debug site search. A future worldgen module can propose candidate centers and dispatch placement after chunks are available on the server thread. It must not mutate SavedData from chunk-generation worker threads. Structure art, biome distribution, and persistence therefore remain separate responsibilities without a premature worldgen manager.

See [the template layout and WorldEdit export workflow](docs/structure-layout.md). WorldEdit can be used to build/paste in a disposable design world, then vanilla structure blocks export the final `.nbt`. Native `.schem` files require conversion, not renaming. WorldEdit, JourneyMap and JEI are optional development tools; Living Kingdoms has no dependencies on them.

Future difficulty remains a configurable, pure calculation of distance, settlement tier, world age and conquests, independent of entity code. Quests, NPC progression and conquest transitions are intentionally outside this milestone.

## Tests and community workflow

`test` uses JUnit 5 with ModDevGradle's NeoForge test environment. It covers model invariants, territory boundaries/dimensions/large coordinates, dirty marking, duplicate and overlap rejection, immutable snapshots, and compressed NBT disk round trips. Additional tests use real `DimensionDataStorage` instances to save and reopen data and verify corrupt/future-schema files remain unchanged after rejected loads.

`runGameTestServer` starts a headless Minecraft world and loads a separate test mod from `src/gametest`. Five GameTests cover the original commands/storage plus physical generation, unique IDs, Quest Board interaction with/without items, sign/furniture placement, disk reload, construction/inventory/water/entity/slope/border rejection, gentle-slope supports and refusal to load missing chunks. All test-only terrain preparation, explicit chunk loading, classes and test-mod resources are excluded from the production JAR and normal client/server runs. Its world lives in `runs/gametest`, separate from normal dev worlds. GitHub Actions runs `test build runGameTestServer` on Java 21 and uploads the JARs. The graphical Save and Quit/reopen check remains manual.

Open an issue with Minecraft/NeoForge/mod versions, reproduction steps, and a relevant log excerpt. Keep contributions scoped and run `test build runGameTestServer` before submitting a pull request. The GitHub workflow is configured for pushes and pull requests. The mod currently reserves all rights; a community distribution license must be selected by the project owner before public release.

## Official references

- [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [SavedData in NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [ModDevGradle runs and JUnit support](https://github.com/neoforged/ModDevGradle)

Local results and known runtime limits: [Milestone 0](docs/validation.md), [Milestone 1](docs/milestone-1-validation.md).

## Next milestone

Milestone 2: make the existing Quest Board offer one server-authoritative resource-delivery quest, with player-specific persistent acceptance/completion and a reputation reward. Keep professions, economy, armies, conquest and large world generation for later milestones.
