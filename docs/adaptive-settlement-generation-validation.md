# Adaptive settlement generation validation

Verified October 7, 2026 (America/Buenos_Aires), on branch `codex/adaptive-settlement-generation`. Java 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252, Gradle 9.2.1. Changes remain uncommitted; no push was performed.

```powershell
.\gradlew.bat test build runGameTestServer
git diff --check
python tools/generate_settlement_modules.py --check
```

Final Gradle result: **BUILD SUCCESSFUL**, exit code 0. **161 unit tests** passed with zero failures, errors or skips. **All 50 required GameTests passed** in a real headless Minecraft world. The server saved its dimensions and stopped normally. `git diff --check` passed; all six new native module assets matched their authoring script.

The 153 existing unit tests and 41 existing GameTests are retained. Eight unit tests add spacing/circle/tolerance/diagnostic rules and legacy/optional layout persistence. Nine GameTests add compact core planning, independent elevations, one wet plot, four native rotations, path placement/connectivity, territory/border/unloaded-chunk safety, foundation/support caps, stale construction/ground anchors, failed-module rollback, foliage/trunk protection, future additions and operator here/debug behavior. Existing physical tests still verify actual board quests, Mayor dialogue/UUIDs, reputation, distinct settlements and disk reload, with expectations adapted to the new modules.

Remote actor fixtures in three existing quest GameTests now reserve their test chunks, wait asynchronously for entity tracking readiness and release those reservations after their assertions. Their quest/reward assertions remain intact. This fixes the fixture race between block availability and immediate entity UUID lookup; it adds no runtime generation tickets or force-loading.

The production `build/libs/livingkingdoms-0.7.0.jar` contains all six new NBTs and the unchanged legacy settlement NBT. It contains no GameTest classes/test-mod resources. Both language JSON files parse successfully. The original settlement schema remains 1 with an optional `layout` compound; old records still serialize/reload without that field. Existing quest/reputation/progression/encounter persistence coverage passed.

Reports: `build/reports/tests/test/index.html`, `build/test-results/test/`, and `runs/gametest/logs/latest.log`.

Graphical gameplay was not exercised in this change. Follow the exact [five-environment manual QA procedure](adaptive-settlement-generation.md#manual-minecraft-qa) for plains, mild slopes, forest, hills and water, then verify Save and Quit/reopen. The implementation documents its limits: plains art fallback for all biome styles, bounded one-block-wide paths, dense forest/steep terrain refusals, an explicit survey requirement before adding buildings to layout-free legacy settlements, and ordinary rollback rather than crash-atomic persistence.
