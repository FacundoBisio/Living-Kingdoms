# UI and structure QA fixes — 0.8.0

Branch: `codex/qa-ui-structure-bugfix`. No commit or push. This change adds no gameplay milestone and retains the saved-data schemas, entity identities, quest rules/rewards and layout footprint dimensions.

Implementation and automated regression verification are ready for review. **Graphical acceptance is still pending.** The local client crashed before opening a playable window in AMD `atio6axx.dll` on both attempts. Disabling the local FML early window moved the crash to vanilla window creation; the original FML configuration was restored. Crash reports: `run/hs_err_pid11216.log` and `run/hs_err_pid16700.log`. No actual in-game screenshot, GUI-scale render or Save & Quit/reopen result is claimed.

## 1. Confirmed root causes

- `VillageScreen` rendered its parchment/text before `super.render`. Minecraft 1.21.1's `Screen.render` invokes `renderBackground`, which then blurs the already-rendered reader and draws the in-world menu texture over it. This explains washed-out text despite opaque palette values. The custom screen now owns its background; the inherited widget pass cannot cover the reader.
- The original watchtower NBT contains a disconnected **3 × 7 wooden slab strip at local Y=10**, X=2..4, Z=0..6: 21 blocks. Original slabs at Y=9 meet that strip diagonally, and no posts/gables join it. This is an authoring defect in the actual exported asset, reproduced independently by reading HEAD's binary NBT, rather than an assumed pivot error.
- The door validator inspected local Z=`sizeZ-1`, the exported air/doorstep margin. It accepted missing doors because it checked air instead of the actual inset wall at `sizeZ-2`. The outer path entrance itself is intentional: the real door, reserved doorstep and path entrance are contiguous.
- Glass panes declared connections in all four directions even on a flat wall. Known-shape placement preserves those authored properties, producing cross-shaped panes. Beams also used X axes on walls running along Z. Some decorative banners had no backing block.
- Placement lacked an independent complete geometry audit before its first write. Planning already reserves exported X/Z dimensions including eaves; the new audit explicitly compares those reservations with native rotated X/Y/Z bounds and checks building/path intersections.

Native origin/pivot math was traced against Minecraft's implementation. In the checked-in assets, 90/270-degree dimensions swap correctly, all transformed local positions remain in the expected bounds, and no duplicated or oversized roof is produced by those transforms. **Reported surfaces extending dozens of blocks or intersecting other buildings remain unconfirmed without the original screenshots or a working client.** The proven tower defect is corrected; this report does not claim that it explains every observed screenshot.

## 2. Structure placement fixes

- Before APPLY, verify native rotated bounds, reservation dimensions, entrance transform, unique transformed block positions, planned snapshots and foundation support positions.
- Compare full building volumes in X/Y/Z, including support height, against every other building. Retain the stronger horizontal reservation rule: stacked buildings remain unsupported.
- Reject path writes intersecting any building clearance volume, including air above floors, before mutation. Roofs, awnings, stairs, fences, balconies and decoration must remain inside the full exported reservation.
- Existing terrain checks, unloaded-chunk refusals, protections, foundation depth limits and rollback remain. Rollback regression still injects native refusal after placing the core; malformed empty geometry is now rejected earlier.
- All eight templates are placed in actual server GameTests at all four rotations, including every directional state and explicit air. A one-block outer ring and the space above each module stay untouched.

## 3. Quest Board readability and information hierarchy

- Opaque near-black/dark-brown text, stronger bold title, dark selected rows/tabs with gold selection marker, visible hover/focus outline, and a light detail inset. Labels use sharp unshadowed text; tooltips/narration retain full labels when a button needs ellipsis.
- Reader order: title; difficulty/type; state; description; **OBJECTIVES**; icon + quantity + item name; **REWARDS**; emerald icon + quantity; reputation; expiration/context; fixed Accept/Claim and Leave controls.
- Item icons use fixed 18-unit slots, while quantity/name wraps together at a consistent baseline. Long section headings grow their background to fit the native word wrap.
- `VillageUiLayout` recalculates coordinates after resize/GUI-scale changes. Board caps at 500 × 306 GUI units; dialogue caps at 408 × 228. Reader/list/controls stay inside tested GUI viewports from 320 × 240 through 1920 × 1080. Small viewports scroll the reader rather than shrinking Minecraft's font.
- List paging, mouse-wheel reader scrolling, Page Up/Down, keyboard focus, server notices and Accept → Active / Claim → history selection transitions remain available. Empty-state notices also wrap and scroll.

## 4. Mayor dialogue and presentation

- Compact portrait on the left; bold Mayor name, readable dialogue and contextual level/reputation on the right. Four modest choices use two columns below the dialogue: Talk, Quests, Settlement, Leave.
- The same background-order fix removes the overlay from the dialogue and 3D portrait.
- Dialogue uses the persisted proper name. Presentation/reload retains stationary persistent identity, ceremonial cleric outfit, and the synchronized named-Mayor marker used by the gold circlet layer.
- Normal settlement info no longer prints a UUID. Reputation and legacy quest feedback use immersive aliases; a missing main-settlement reference no longer falls back to its UUID. Technical session/entity IDs stay in transport only. Operator generation/debug output remains diagnostic.

## 5. Visual structure changes

- Watchtower: one lower accessible lookout, continuous corner supports, seven ladder blocks with backing, guardrail, backed banner, hanging lantern with overhead support, and a compact connected gable instead of separated slab bands.
- Houses, hall, barracks and workshop: continuous roof boarding beneath stepped courses; wall-aligned glass panes, modest trapdoor shutters and correctly oriented beams. The workshop's open arcade has no unsupported shutter.
- Quest Board plaza: canopy posts moved to its outer corners; supported hanging lantern keeps the board area readable and leaves the west approach open. Existing paving and bench remain compact.
- Barracks banners moved onto actual backing poles. All authoring changes stay within the old template size envelopes; houses are not enlarged.
- A native-NBT geometry contact sheet was inspected for proportions (`build/qa/structures.svg`, optional raster `build/qa/structures.png`). It approximates vanilla block shapes/colors and is **not** a substitute for Minecraft screenshot acceptance.

## 6. Regression validation

Final local results: **172 unit tests passed, all 55 required GameTests passed, Gradle build passed, `git diff --check` passed**. Both native-template reproducibility checks passed. The binary NBT audit verifies all four transforms for all eight assets and reports zero detached blocks in the corrected exports (HEAD watchtower: 21 detached blocks).

Requested commands:

```powershell
.\gradlew.bat test build runGameTestServer
git diff --check
python tools/generate_settlement_modules.py --check
python tools/generate_settlement_template.py --check
python tools/audit_settlement_modules.py --compare-head --svg build/qa/structures.svg
```

Coverage adds all 32 template/rotation combinations, exact transformed bounds, no detached non-air blocks, roof/decoration states, no writes outside the exported volume, doorstep connection, and refusal of overlapping modules or path clearance cuts before placement. Existing tests remain; command-info expectations now enforce immersive fields, and the rollback fixture retains a placement refusal after the core is written.

Unit regressions check scaled layout bounds, selection/action transitions, deep requirement/reward payload integrity, Unicode, and long translated paragraphs through the same native `StringSplitter` used by the screen. Mayor GameTest coverage verifies reload identity/name, profession, persistence and circlet eligibility. These checks establish data/layout behavior, not visual screenshot quality.

Repeated GameTest runs had accumulated 284 party records in their persistent test world, exceeding the existing natural-spawn cap of 256. The untouched fixture was archived to `build/qa/gametest-world-before-qa` and tests were rerun in a fresh `runs/gametest/world`. Normal `run/saves` worlds were never moved or changed. Keep a fresh GameTest fixture when accumulated test records exceed production caps; do not increase gameplay caps to hide the failure.

## 7. Exact in-game QA checklist

Use Minecraft 1.21.1 + NeoForge 21.1.252 with the rebuilt `build/libs/livingkingdoms-0.8.0.jar`. Create a fresh Creative test world with cheats. Verify that the loaded mod is this rebuilt JAR. Existing settlements retain their old blocks and are not automatically rebuilt.

1. **Generate a fresh settlement:** choose a dry loaded clearing and run `/kingdom settlement generate here debug`. If the planner refuses, read its diagnostics and try another clearing. Generation places a compact core, two house variants, Blacksmith, Barracks and an optional Watchtower.
2. **Inspect from above:** fly over the village. Record a screenshot with all footprints and paths visible. Check spacing and that roads never cut through interiors. Run `/kingdom settlement debug-layout` inside the territory; corner particles mark each volume, green particles mark entrances, and chat lists anchors/rotations/bounds. Only operators can invoke it.
3. **Inspect every roof:** walk/fly around all four sides of the hall, both houses, Blacksmith, Barracks and tower. Capture roof joins, ridges/eaves, chimneys and canopies. Reject any detached strip, extra plane or roof overlap.
4. **Inspect each interior:** open each door and walk through it. Check headroom, floor/foundation support, beds, storage, smithing stations, table/seats and lighting. Include the third house and standalone Town Hall through the rotation gallery below.
5. **Inspect windows:** check panes/shutters from outside and inside. Check alignment with floor height and wall/roof separation in all four rotations.
6. **Inspect Watchtower:** confirm it was generated (optional on constrained terrain). Climb the ladder to the lookout, walk around the railing, and inspect banner/lantern/roof support. Use the gallery if the production plan omitted it.
7. **Inspect Quest Board plaza:** approach the board from the west, open it normally, and check the canopy, posts, lantern, foundation and bench without obstructions.
8. **Open Quest Board:** right-click it with the Mayor nearby. Check sharp dark text, title weight, readable descriptions, icon/quantity alignment and separate reward rows. No menu texture or blur should cover the reader.
9. **Inspect all tabs:** Main quests, Requests, Active quests. Check selected/hover/disabled states, empty states, full-title tooltips, list paging, Tab/Shift+Tab, Enter, Page Up/Down and mouse wheel. Repeat at GUI scale 1, 2, 3 and Auto where available, at 854 × 480 windowed, 1280 × 720 windowed, and fullscreen. Switch language between English and Español (Argentina), and resize with the screen open. Reader text may require scrolling at high GUI scales; controls must remain usable.
10. **Accept a quest:** choose an available resource request and click Accept. Verify immediate button disabling while awaiting the server and that selection follows the quest into Active. Meet the Mayor first to unlock the main chapter.
11. **Inspect active quest:** record progress before/after collecting items. Refresh and verify the snapshot. For quick resource QA use `/give @s minecraft:iron_ingot 64`, `/give @s minecraft:wheat 64`, `/give @s minecraft:oak_log 64`, `/give @s minecraft:stone 64` as appropriate for the displayed objective. Leave room for rewards.
12. **Claim quest:** once the quantities are satisfied, click Claim. Verify exact item consumption, emeralds/reputation, completed state and no second reward on repeated click/refresh. Try a claim with missing materials or a full inventory; a readable rejection must appear without consuming materials.
13. **Talk to Mayor:** inspect portrait, gold circlet, distinctive robe and proper name. Use Talk, Quests, Settlement and Leave. No UUID/internal ID appears in name, dialogue or ordinary `/kingdom settlement info` output. Check narration and focus behavior if enabled.
14. **Save & Quit:** keep one accepted quest unfinished and one completed quest; record their states, inventory/reputation and the Mayor's name/appearance. Save & Quit normally.
15. **Reload:** reopen the same world, revisit Mayor/board and verify name, robe/circlet, single Mayor/resident receipts, buildings, quest states, reward deduplication and inventory. Repeat UI/scale checks. Capture comparison screenshots. `/reload` is an additional check; it does not replace Save & Quit/reopen.

For additional full settlements, travel at least 160 blocks to another loaded clearing, then repeat `/kingdom settlement generate here debug`. There is **no forced-rotation option for complete settlements**: individual buildings face the core according to their plot. Debug output reveals those rotations.

For a quick four-rotation art gallery, use the vanilla template command in a disposable flat test world. Stand at a fixed point with an empty loaded area around it; replace Y=`~` with the surface height if needed. These commands place art only: they bypass the settlement planner/protections and create no settlement data/NPCs/quests. They must not be used to judge foundation or production path handling.

```mcfunction
/place template livingkingdoms:allied/plains/watchtower ~16 ~ ~16 none
/place template livingkingdoms:allied/plains/watchtower ~48 ~ ~16 clockwise_90
/place template livingkingdoms:allied/plains/watchtower ~80 ~ ~16 180
/place template livingkingdoms:allied/plains/watchtower ~112 ~ ~16 counterclockwise_90
```

Keep the same player position for all four commands. Repeat for `core`, `town_hall`, `house`, `house_variant`, `house_third`, `blacksmith`, `barracks`, on distinct Z rows spaced by 24 blocks. Vanilla rotates around the supplied origin: 90/180/270-degree templates can extend toward negative X/Z, unlike the planner's minimum-corner convention. This spacing accommodates that difference. If the distant column is unloaded, move to load it and return to the fixed starting point before issuing relative commands. Town Hall in a real generated settlement is part of `core`; the standalone module is also covered by tests/gallery.

Screenshot acceptance matrix: each of the eight template rows × rotations 0/90/180/270, plus fresh complete settlement overhead/path/plaza views, Board tabs/actions at supported GUI scales, Mayor dialogue, and post-reload comparisons. All graphical cells are pending in this environment.

## 8. Known remaining visual limitations

- Actual rendered colors, item/model depth behavior, GUI-scale/window/fullscreen appearance, survival block updates and Save & Quit/reopen still require the checklist above on a working client. Tests and the diagnostic contact sheet cannot certify them.
- No original screenshots were attached with the request. The reported very large surfaces/building intersections need screenshot/coordinate/version confirmation beyond the proven 21-block tower strip.
- Existing placed structures retain the old art, including any detached pieces. There is no destructive migration or repair of already-built worlds.
- Watchtower is optional on tight terrain; founding shows two of the three house variants. Use the gallery to inspect every export.
- Other biome architecture styles still use the existing Plains art fallback. Bounded one-block paths and limited terrain supports remain unchanged.

Do not begin Village Conversion, Housing, Citizens, Professions, Conquest or another milestone after this change. Graphical acceptance remains the outstanding step for this QA milestone.
