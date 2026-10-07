# Milestone 0 validation

Local environment: Windows 11, Temurin Java 21.0.8, Minecraft 1.21.1, NeoForge 21.1.252, Gradle 9.2.1. Verified on October 6, 2026 (America/Buenos_Aires).

## Unit and storage tests

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build
```

Result: **BUILD SUCCESSFUL**, process exit code 0. Eleven JUnit tests passed (4 domain, 7 persistence), no failures, errors, or skips. Reports are generated in `build/reports/tests/test/index.html` and `build/test-results/test`.

Storage tests include actual `DimensionDataStorage` save/flush/reopen, and rejection of corrupt/future-schema files without changing their bytes. Expected error logs in those negative tests are not test failures.

The Gradle wrapper JAR matches the official Gradle 9.2.1 SHA-256 (`423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9`); the wrapper properties pin the distribution SHA-256.

## Headless Minecraft server

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
```

Result: **BUILD SUCCESSFUL**, process exit code 0. NeoForge loaded Living Kingdoms and the isolated test mod in a real GameTest world; **all 1 required GameTests passed**. The test verifies actual Brigadier command execution with a NeoForge FakePlayer, non-operator creation denial, successful creation at the player position, overlap rejection, public info with all five requested fields, dirty marking, and disk reload into a distinct Minecraft storage instance. The server then saved all dimensions and shut down cleanly. Logs are generated at `runs/gametest/logs/latest.log`.

The production JAR was inspected: it includes the mod entrypoint, settlement storage and NeoForge metadata, and contains no GameTest classes or test-mod resources.

## Graphical development client

`runClient` was attempted twice. The first run crashes during NeoForge's early OpenGL window creation; disabling `earlyWindowControl` for a second local run discovers Living Kingdoms 0.1.0 successfully but crashes when vanilla Minecraft creates its normal window. Both native crash reports identify `atio6axx.dll` and `EXCEPTION_ACCESS_VIOLATION`, before a world or player command can run. The local FML setting was restored after verification.

The graphical launch and user-facing Save and Quit/reopen flow remain pending a functioning graphics environment. The server/storage tests establish their own narrower results; they do not prove GUI behavior. No driver or system configuration was changed.

## Deliverables

- Production mod: `build/libs/livingkingdoms-0.1.0.jar`
- Developer sources: `build/libs/livingkingdoms-0.1.0-sources.jar`
- GitHub workflow: `.github/workflows/build.yml` (prepared locally; no remote is configured)

World generation, quests, NPCs, conquest gameplay, and GUI are outside this milestone.
