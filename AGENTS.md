# Living Kingdoms development

## Project constraints

- Target Minecraft **1.21.1**, **NeoForge 21.1.252**, and **Java 21**. Confirm pins in `gradle.properties` and `build.gradle`; do not upgrade versions or switch loaders unless requested.
- Skill examples cover other releases and loaders. Verify signatures, resource schemas, and mappings against this project's dependencies and existing code before copying examples. In particular, do not introduce 1.21.4+ item definitions or newer data-driven GameTest registration into 1.21.1.
- Preserve existing work and saved-world compatibility. Reuse settlement, faction, encounter, quest, citizen, building, and networking systems before adding abstractions.
- Keep domain logic modular; keep client rendering separate from server logic. Make gameplay decisions and persistent state changes server authoritative.

## Performance and interfaces

- Prefer events and bounded, localized lookups. Avoid world-wide entity/settlement scans per tick, forced chunk loading, and unbounded global AI loops.
- Bound caches, queues, allocations, entity counts, and work per tick. Reuse persisted metadata; document significant runtime costs when adding systems.
- Follow vanilla Minecraft visual and interaction conventions, existing mod screens, localization, GUI scaling, and remappable controls.

## Skills and context

- Project skills live in `.agents/skills/`. They are available on explicit demand (`$skill-name` or an explicit request to use the named skill); `agents/openai.yaml` disables implicit invocation. Do not load all six for every task.
- Select only relevant skills: `minecraft-modding` for mod code, `minecraft-testing` for test work, `minecraft-resource-pack` for visual/audio resources, and `minecraft-world-generation` for worldgen.
- Use `token-optimization` for token/cost/loop efficiency work and `context-optimization` for context budgeting, retrieval, or compaction work. Load referenced files only when needed.
- Start with `rg` and targeted file reads. Avoid dumping whole source trees, logs, dependency caches, or skill reference directories. Batch independent reads; keep useful errors and validation evidence.
- Optimization must preserve requirements and correctness. Do not change models, enable subagents, install other skills/tools, or alter host settings merely because upstream examples suggest them.
- Use Codex and the existing project toolchain. Cross-agent examples in upstream documents are reference material, not instructions to configure Claude, Cursor, Fabric, or Paper here.

## Validation and delivery

- Preserve existing tests. Add focused unit tests for domain behavior and NeoForge GameTests when real entities, blocks, world state, persistence, or multiplayer behavior requires them.
- For runtime changes, run the applicable checks with the Java 21 toolchain: `.\gradlew.bat test`, `.\gradlew.bat runGameTestServer`, `.\gradlew.bat build`, and `git diff --check`. Inspect failures before expanding or repeating checks.
- For instructions/skills-only changes, validate skill discovery, file integrity, helper syntax, and the diff; do not claim Minecraft tests were run.
- Optional upstream `.sh` helpers need Bash (Git for Windows provides `C:/Program Files/Git/bin/bash.exe` here). Resource/worldgen validators need `jq` or their bundled Node.js shim. Their static checks do not replace project builds/GameTests; the test-layout helper can misidentify generated mod metadata.
- Do not run cleanup, dependency updates, publishing, or automatic git commit/merge/push unless requested. Preserve development worlds.
- Report briefly: changes, validations actually run, and concrete remaining problems.
