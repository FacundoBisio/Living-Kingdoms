# Milestone 1 validation

Validated locally on 2026-10-06 with Java 21, Minecraft 1.21.1 and NeoForge
21.1.252. Mod version: **0.2.0**. Work branch:
`codex/milestone-1-physical-settlement`, based on Milestone 0 commit `e6866c5`.

## Changes

| Files / area | Result |
| --- | --- |
| `LivingKingdoms.java`, `block/KingdomBlocks.java`, `block/QuestBoardBlock.java` | Register the common Quest Board block, item, codec and creative-tab entry; server-side interaction displays the placeholder message. |
| `command/SettlementCommands.java`, `config/KingdomConfig.java` | Add operator-only `generate`, generation search range and terrain tolerance; preserve `create` and `info`. |
| `structure/SettlementTemplate.java`, `structure/SettlementSitePlanner.java` | Load and validate native templates, then plan placement within loaded chunks without changing the world. |
| `settlement/SettlementGenerator.java` | Place a validated village, verify blocks, create a unique allied level-1 settlement through existing SavedData, and restore original states on ordinary placement failures. |
| `data/livingkingdoms/structure/allied/test_settlement.nbt`, `tools/generate_settlement_template.py` | Supply a reproducible 31 × 7 × 31 placeholder with Town Hall, three houses, Blacksmith, Barracks, lodestone and Quest Board. |
| Blockstate/model/item/loot resources, block tags and `en_us`/`es_ar` translations | Render, place, break and interact with the board; define approved ground and vegetation. Vanilla textures only. |
| `PhysicalSettlementGameTests.java`, test-mod metadata | Add four isolated server GameTests alongside the original Milestone 0 test. |
| `gradle.properties`, production mod metadata | Version 0.2.0 and updated milestone description. |
| `README.md`, `docs/structure-layout.md`, this report | Document generation, limits, validation and the WorldEdit-to-native-template authoring workflow. |

The Settlement/Territory records, persistence implementation and schema version 1
are unchanged. The existing eleven JUnit tests and original GameTest remain in
place. No WorldEdit, JourneyMap or JEI runtime dependencies were added.

## Automated checks

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

- **Build successful**; production and sources JARs are in `build/libs`.
- **11 JUnit tests passed**: existing domain and persistence regression coverage.
- **5 required GameTests passed**: the original command/storage test plus four
  physical-settlement tests.
- Template regeneration check passed; exported bytes match the checked-in asset.
- Whitespace/diff check passed.
- Production JAR inspection confirmed the block, generator, native template,
  model and loot resources, valid JSON, no GameTest content and no dependencies
  on the optional development mods.

The final sandboxed GameTest launch failed inside BootstrapLauncher before
Minecraft started. Repeating the same command with access to local dependency
caches launched successfully and passed all five tests. The reused test world's
test-mod version warning (0.1.0 to 0.2.0) did not prevent loading or validation.

The physical GameTests exercise the actual Brigadier command using a server
player, deny generation without operator permission, place two settlements with
distinct UUIDs, identify them through `info`, reject overlapping generation,
check lodestone/furnace/sign/board placement, interact with the board using an
empty hand and an item, and reopen settlement records from disk. They also check
protected chest contents, water, entities, excessive slopes, the world border,
gentle-slope supports, preserved original ground, and refusal to load missing
chunks. Terrain preparation and explicit test chunk loading exist only in the
isolated GameTest source set.

Reports: `build/test-results/test`, `build/reports/tests/test/index.html`, and
`runs/gametest/logs/latest.log`. GameTest shutdown saves the headless test world.
Production artifact: `build/libs/livingkingdoms-0.2.0.jar`.

## Client result and manual verification

`runClient` was attempted again. It crashed while GLFW created the early OpenGL
window with `EXCEPTION_ACCESS_VIOLATION` in `atio6axx.dll+0x193ca0`, before mod
gameplay code ran. The native report is `run/hs_err_pid16376.log`; the client log
is `run/logs/latest.log`. No architecture or graphics-driver workaround was
introduced. This reproduces the external AMD driver issue recorded in Milestone
0; the headless server checks above passed.

Visual appearance, creative-tab rendering and graphical Save and Quit/reopen
remain unverified on this machine. On a client with working graphics:

1. Install the 0.2.0 JAR with matching Minecraft/NeoForge and enable cheats.
2. Find a dry, open, nearly flat area and let surrounding chunks load.
3. Run `/kingdom settlement generate`; follow the reported central coordinates.
4. Inspect the six buildings, plaza, signs, lodestone and Quest Board.
5. Run `/kingdom settlement info` there; record the UUID and check allied level 1.
6. Right-click the board with and without an item; expect the placeholder message.
7. Save and reopen the world; check the structure and the same settlement UUID.
8. Move to another suitable clearing at least 128 blocks away and generate again;
   verify that its UUID differs. Unsafe/overlapping locations must be refused.

## Scope and limits

Natural distribution is not enabled. Future biome/spacing selection can use
`SettlementGenerator.generateAt` on the server thread after chunks are available.
The debug command samples nearby candidates; a failed search does not mean that
every possible location in the search radius is unsafe. No rotation is applied.

Terrain validation is conservative and does not implement land ownership. No
ground is excavated; a raised foundation and supports span at most the configured
height variation. Placement rollback covers ordinary runtime failures, not a
process crash between world and SavedData writes. Breaking/moving the marker does
not relocate or delete the territory. Population remains abstract; no NPCs,
quests, economy, conquest, professions or GUI were implemented.

Next logical milestone: one persistent, server-authoritative resource-delivery
quest through the existing Quest Board, with a reputation reward. Milestone 2 has
not been started.
