# Living Kingdoms 0.18.1 — compact progress

Stable base: `codex/kingdoms-commerce-v018`, commit `d9dd748`. Integration: `codex/citizen-models-v0181`. Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21, no dependency changes.

- Prior analysis done: intact ZIP, 14 bones, 24 cubes, four 64×64 textures, nine distinct clips. Shared auxiliary UVs, a permanent hammer and a risk of covering the eyes were detected.
- A single delegating renderer keeps the native model and allows selecting the Argon adaptation. Entities, AI, real equipment, packets and persistent schemas remain those of 0.18.0.
- 80 original skins intact; 80 derived variants fill auxiliary UVs. Hair and sash repositioned; beard by identity; hammer separated from the body and tied to real work.
- Numeric animations read once per reload; bounded interpolation with no per-sample allocations. Work tied to confirmed progress; native locomotion, combat and rest kept.
- `/kingdommodels native` and `/kingdommodels argon4`: immediate, persistent local comparison, with no privileges or messages to the server.
- Source resources and provenance kept in `docs/argon4-source/`; reproducibility: `tools/integrate_argon_assets.py --check`.

Final validation:

- `gradlew test build`: **469 JUnit, 0 failures, 0 errors, 0 skipped**; build passed. Log: `build/v0181-final-release-build.log`.
- `gradlew runGameTestServer -PgameTestDirectory=runs/gametest-v0181-models-a`: **174 GameTests passed**, exit 0. Log: `build/v0181-gametest-a.log`. Fresh isolated world; the nine 0.17.0 data copies are included in the existing suite.
- `integrate_argon_assets.py --check` and `generate_citizen_assets.py --check`: source resources, nine clips, UVs and the 80 original/derived skins checked. Four CPU mesh tests verify pivots, head UVs/normals against Minecraft 1.21.1, hammer separation and invalid resources. CPU tests do not certify the graphical appearance.
- `verify_release_artifact.py --version 0.18.1`: every class and resource in the JAR matches the current files; Java 21, pins, translations and absence of the test mod verified. `git diff --check` passed.
- `build/libs/livingkingdoms-0.18.1.jar`: 1,406,929 bytes; SHA-256 `035fa624238d4876a8716e3228836679c953f591af06cd21eb66985dc08a1596`.
- The stable 0.18.0 JAR keeps SHA-256 `43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3`. Original worlds, main, dependencies and persistent schemas were not altered.

During verification, relative test fixture paths were fixed; the last full run passes. The test server logged tick lag while loading its many fixtures: that is not a measurement of graphical performance or of a normal game.

Pending manual: the user launches Minecraft, compares faces/UVs, tools and armor, sleep, labels, shaders and performance with several citizens; watches two clients with different models and tests a copy of an older world. The client will not be run or controlled from Codex.

Comparison and detailed guide: [v0181-model-comparison.md](v0181-model-comparison.md).

## Validation for publishing on GitHub

The full publish check found that the visual fixture registered a guard with a nonexistent workplace UUID. Its invalid data polluted the shared SavedData and could make the Farmer test's reload fail depending on execution order. Only the fixture was fixed: it registers a valid barracks, verifies the full profile reload and removes the profession at the end. Validation was not relaxed and gameplay and the persistent format were not modified.

After the fix: **469 JUnit verified, 174 GameTests passed and build passed**, exit 0. Final log: `build/v0181-main-publish-final-validation.log`; isolated world: `runs/gametest-v0181-main-publish-final`. The log includes a long interruption, so its duration is not used as a performance measurement. Logs, worlds, caches and the mod JAR stay excluded from Git.

The 14 templates, storage, the 80 original skins and their 80 adapted variants, the versioned Argon resources and their hashes, the JAR contents and `git diff --check` were also verified. The JAR rebuilt for publishing is 1,406,951 bytes with SHA-256 `d49b98efe98fef73afde307e36b0c466777d73f0702d62f1b358acb5ac759ee7`; the earlier hash belongs to the first local delivery.
