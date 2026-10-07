# Living Kingdoms

Minecraft Java **1.21.1**, **Java 21**, **NeoForge 21.1.252**. Milestone 0 establishes server-owned, persistent settlements and two debug commands. No quests, custom entities, structures, GUI, conquest gameplay, or external services are implemented yet.

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

The client opens the Minecraft development environment. Create a world with cheats enabled for debug creation. The mod JAR is `build/libs/livingkingdoms-0.1.0.jar`; the `-sources.jar` is for developers, not installation. Use the same mod version on clients and dedicated servers.

On the development machine used for this milestone, `runClient` crashes in the AMD native OpenGL driver (`atio6axx.dll`, `EXCEPTION_ACCESS_VIOLATION`) while GLFW creates a window. Disabling NeoForge's early splash window reproduces the same crash at vanilla window creation. The graphical client and manual Save and Quit/reopen flow therefore remain unverified on that machine. Crash reports are in the ignored `run/hs_err_pid*.log` files. Headless tests do not require a working graphics driver.

For a dedicated development server:

```powershell
.\gradlew.bat runServer
```

Read the Minecraft EULA linked by the server. If you accept it, change `eula=false` to `eula=true` in `run/eula.txt` and rerun. Stop with `stop` to save cleanly. Development files and worlds live in the ignored `run/` directory. A production server also requires Java 21 and the matching NeoForge version.

## Commands and persistence check

Both commands require a player context; a direct server-console invocation reports that a player is required.

1. Stand where you want an abstract allied settlement and run `/kingdom settlement create`. Creation requires permission level 2 (operator/cheats).
2. Run `/kingdom settlement info`. Any player can read the name, UUID, faction, level, and population of the settlement containing their position.
3. Record the UUID. Leave the world through Save and Quit (or stop the dedicated server), reopen the same world, and run `info` at the same position. All five values must match.
4. Repeating `create` there must reject the overlapping territory. Move more than 96 horizontal blocks from the founding point to create another settlement with default settings.
5. `/reload` must preserve both commands and settlement state.

Territories are horizontal circles in a specific dimension, with an inclusive boundary; height does not affect lookup. `info` outside every territory reports no settlement. Creation stores the player's block position, a generated `Haven <UUID prefix>` name, allied faction, level 1, and abstract population 5. It places no blocks and spawns no NPCs.

World data is saved in `<world>/data/livingkingdoms_settlements.dat`, including settlements in the Nether and End. In an integrated dev client the world is under `run/saves/<world>`; on the dev server it is under `run/world`. Server settings live in `<world>/serverconfig/livingkingdoms-server.toml`:

```toml
[settlements]
radius = 48
initialPopulation = 5
```

Settings apply to newly created settlements. Existing territories and populations retain their saved values. Normal Minecraft autosave and world shutdown write dirty data; commands do not force disk I/O on each invocation.

## Architecture

| Package | Responsibility |
| --- | --- |
| `dev.livingkingdoms` | Common NeoForge bootstrap and registration |
| `config` | Per-world NeoForge server settings |
| `settlement.domain` | Immutable, Minecraft-independent `Settlement`, `Territory`, and `Faction` |
| `settlement.persistence` | NBT conversion and Overworld `SavedData` ownership |
| `command` | Brigadier adapters for player requests and feedback |

There are no client imports in common code and no global settlement cache. Each world owns its data through Minecraft's `DimensionDataStorage`. The command handlers run on the server thread; the storage entrypoint checks thread ownership. Immutable records and immutable snapshots prevent callers from changing values without a dirty-marked storage mutation. UUID lookup is map-backed; territory queries currently scan the stored settlements only when a command is run. There is no tick loop or chunk loading. Add a dimension/chunk spatial index when real gameplay queries justify it.

Persistence uses explicit stable faction identifiers and schema version 1. Loading validates required fields, bounds, UUID uniqueness, and non-overlapping territories. Unsupported or malformed data is rejected. If Minecraft catches a loading error, a guarded factory refuses to create fresh data over an existing file. Back up worlds before future migrations; schema upgrades will need explicit migration code.

Future settlement resources, buildings, and NPC memberships can extend this aggregate using typed values and a versioned migration. Put progression rules in focused domain/application services; store resulting replacements through dirty-marked persistence methods. Professions and capability unlocks belong to NPC progression, separate from settlement level. Conquest should use an explicit lifecycle alongside faction (hostile, defeated, liberating, allied outpost, village), with validated transitions. Quest state should have its own server-owned model/storage and reference settlements by UUID.

Future world generation should call settlement creation through a server service, independent of structure templates. A configurable, pure `DifficultyScaler` should combine distance from the starting region, tier, world age, and conquests; entities should consume its result instead of owning the formula. None of those future systems are scaffolded or active in Milestone 0.

## Tests and community workflow

`test` uses JUnit 5 with ModDevGradle's NeoForge test environment. It covers model invariants, territory boundaries/dimensions/large coordinates, dirty marking, duplicate and overlap rejection, immutable snapshots, and compressed NBT disk round trips. Additional tests use real `DimensionDataStorage` instances to save and reopen data and verify corrupt/future-schema files remain unchanged after rejected loads.

`runGameTestServer` starts a headless Minecraft world and loads a separate test mod from `src/gametest`. It verifies player commands and server storage using NeoForge's game-testing APIs. Its classes and tiny test structure are excluded from the production JAR and normal client/server runs. Its disposable world lives in `runs/gametest`, separate from normal dev worlds. GitHub Actions runs `test build runGameTestServer` on Java 21 and uploads the JARs. The graphical client Save and Quit/reopen check is still described above, since automated storage tests do not exercise the GUI.

Open an issue with Minecraft/NeoForge/mod versions, reproduction steps, and a relevant log excerpt. Keep contributions scoped and run `test build` before submitting a pull request. This repository has no configured GitHub remote yet; the workflow runs after the project is pushed. The mod currently reserves all rights; a community distribution license must be selected by the project owner before public release.

## Official references

- [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [SavedData in NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [ModDevGradle runs and JUnit support](https://github.com/neoforged/ModDevGradle)

Local results and known runtime limits: [Milestone 0 validation](docs/validation.md).

## Next milestone

Milestone 1: add a minimal Quest Board and one server-authoritative resource-delivery quest, driven by a settlement resource shortage, with player-specific persistent acceptance/completion and rewards. Keep NPC AI, conquest, and large world generation for later milestones.
