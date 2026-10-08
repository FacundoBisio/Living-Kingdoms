# Village identity and UI polish - 0.8.0

Branch: `codex/village-identity-ui-polish`. The uncommitted adaptive-generation work was carried forward from `codex/adaptive-settlement-generation`. No commit or push was made. The existing version change to 0.8.0 was preserved.

## Architecture and visual content

The active native NBT modules were redesigned, rather than merely rearranged. They use solid reserved footprints with inset walls, roof overhangs inside those footprints, steep stair roofs, framed gables, timber beams and deliberate interiors. The catalog still isolates future biome palettes; only PLAINS assets are implemented, with the existing fallback for other styles.

| Module | Size X/Y/Z | New composition |
| --- | --- | --- |
| Core | 13/11/13 | Taller timber Town Hall, dark oak roof, blue banners, meeting table, seats, bookshelf; stone plaza, bench, lantern posts and sheltered Quest Board |
| Standalone Town Hall | 11/11/9 | Higher framed meeting hall, central entrance, table, seating and banners; available to the addition API |
| House A (`HOUSE`) | 7/9/7 | Spruce gable, stripped oak frame, bed, storage, table, lighting and modest planting |
| House B (`HOUSE_VARIANT`) | 7/10/9 | Longer footprint and crosswise roof, different bed color and room proportions |
| House C (`HOUSE_THIRD`) | 9/10/7 | Wider cottage, stone chimney and hay detail |
| Blacksmith | 7/10/7 | Stone-heavy walls, open covered work side, furnace, chimney, anvil, smithing table, grindstone and barrel |
| Barracks | 9/10/9 | Red beds, blue banners, targets and fenced practice detail |
| Watchtower | 7/12/7 | Stone/wood posts, elevated platform, continuous ladder, guardrail, lantern and roof |

Palette: oak planks and stripped oak/spruce beams; cobblestone with restrained mossy accents; stone bricks; spruce/dark oak stair and slab roofs; blue banners. Furnaces are unlit and chimneys contain no fire or lava. No decorative armor stands or saved structure entities are added.

Each founding layout uses House A plus B or C, selected deterministically from its position. Rotation, chosen template, footprint, entrances and paths are persisted. The four functional outer plots remain required; the watchtower is attempted afterward and is optional on constrained terrain. Roads use a deterministic mix of dirt path, gravel and coarse dirt, with cobblestone stairs for elevation changes. Existing-path recognition and persisted path-floor metadata support all three paving materials.

All active generation uses the redesigned modules. The old `allied/test_settlement.nbt` remains solely for legacy planner regression coverage; it is not selected by the adaptive generator. Already-built worlds retain their blocks, including old visuals. There is no automatic demolition or rebuild on loading a save.

Authoring and reproducibility:

```powershell
python tools/generate_settlement_modules.py
python tools/generate_settlement_modules.py --check
```

WorldEdit and Python are not runtime dependencies.

## Mayor and founding residents

Mayors keep the same entity and settlement UUIDs. Their name is chosen from twelve proper names using the random entity UUID, stored in entity persistent data, and displayed with a localized title. Loading a tagged old Mayor supplies/migrates the presentation safely. New settlement names use immersive combinations; the GUI aliases old `Haven <UUID prefix>` defaults without modifying their stored settlement records.

The Mayor uses the vanilla cleric robe and a client render layer for a small gold circlet, using the vanilla gold-block texture. This is a geometry layer, not a custom skin or new entity type. The Mayor remains stationary, persistent, invulnerable to ordinary combat and without trade offers. The role, not the appearance, authorizes interactions.

Newly generated settlements also create two ordinary persistent vanilla villagers. They retain ordinary AI, receive a `RESIDENT` identity and settlement UUID, and can later be adopted by a Citizens system. Founding receipts on the Mayor prevent repeating those spawns. They are not counted as a new capacity system and do not alter the existing abstract population value. Old worlds are not automatically repopulated, and missing or unloaded actors are not replaced. There is no immigration, profession simulation or Housing mechanic.

## Quest Board and NPC dialogue

The UI UX Pro Max skill was installed and applied to shared colors, native controls, focus, selection, loading feedback, text overflow and keyboard scrolling. See [interface design and review notes](village-ui-design.md).

The client-only `VillageScreen` provides a parchment/wood-colored shell, a three-tab quest reader, scrollable details and real item rendering. It shows titles, descriptions, objectives, current requirements, level/difficulty, emerald/reputation rewards, state and offer expiry. Accept/Claim are disabled when unavailable or while awaiting a reply. Rejections are also shown in the reader. Main and dynamic gameplay remains in the existing services.

Right-clicking a board always opens the GUI, including while holding an item or crouching. Crouching no longer accepts or delivers immediately. Existing command and legacy delivery adapters remain available and covered by regression tests.

The dialogue portion renders the live NPC model, role/name, contextual text and Talk / Open Quest Board / Settlement Info / Leave buttons. Its snapshot is based on NPC entity ID, role, name and a dialogue translation key, so the shell can be reused for later important NPC roles. Only Mayor routing is implemented now; there is no general RPG dialogue engine. The Mayor's board option searches a small loaded area around the player and still requires actual board reach.

NeoForge payload registration is common-side; all Minecraft screen/render references are isolated in the physical-client bootstrap. A snapshot contains presentation data. A client action contains only a session UUID, quest UUID and an enum. Server sessions are bound to the actual player object, dimension, settlement and board/NPC, expire after 12,000 game ticks, and are invalid after leaving reach or destroying the board. Every action rechecks ownership and context. Inventory exchange, objective completion and reputation/rewards remain authoritative and one-time server transitions. No client reward/count is accepted.

Data is gathered on opening, an action, or explicit Refresh. There are no UI tick scans or new world-loading tickets. Expiry/progress is a snapshot, not a continuously polled display. Both `en_us` and `es_ar` include the new labels, descriptions and diagnostics.

## Generation UX

`generate here` first tries a plaza four blocks north of the caller. If that fails, it tries eight directions at offsets of 8, 16 and 24 blocks: at most 25 centers, including the first. Diagonal candidates are correspondingly farther away. It uses the existing one-block relaxed local tolerance, capped at four, and still rejects water, protected blocks/inventories, entities, unsupported terrain, world-border violations, overlapping territories and unloaded chunks.

Debug output separates center/plot rejection counts, includes insufficient core area, and reports the best count of valid functional plots against the required four. Normal feedback distinguishes an overlapping territory, a crowded area with too few valid plots, entirely unloaded terrain, and a general unsafe site.

## Automated validation

Run from the repository root:

```powershell
.\gradlew.bat test build runGameTestServer
python tools/generate_settlement_modules.py --check
git diff --check
```

The suite includes name variety/stability, old-name aliases, snapshot and action codecs, malformed action rejection, diagnostic classification, actual outgoing GUI payloads, main-delivery GUI actions, foreign-session/quest refusal, inventory validation, duplicate-claim protection, reach/destroyed-board invalidation, quest save reload, Mayor migration/identity, resident receipts, house interiors and role landmarks. Prior adaptive placement, rollback, quest, encounter, faction, progression and persistence tests remain.

The first full runs exposed an accumulated-test-world limitation: 295 persisted encounter records exceeded the configured global cap of 256. The old ignored test world was preserved as `runs/gametest/world-before-ui-validation-20261007-225655`, and validation was rerun with a fresh test world. No player save or production configuration was reset, and no gameplay cap was weakened.

Final validation: **167 unit tests passed**, **53 GameTests passed**, Gradle build succeeded, all eight native modules passed `--check`, and `git diff --check` passed.

Report locations: `build/reports/tests/test/index.html`, `build/test-results/test/`, `runs/gametest/logs/latest.log`. The distributable is `build/libs/livingkingdoms-0.8.0.jar`; test classes are excluded.

## Exact manual QA

Use a disposable creative test world with commands enabled and the same 0.8.0 mod on client/server. Let nearby chunks load. A Superflat world is useful for the initial visual inspection; also repeat generation in ordinary plains and gentle hills.

1. **Normal generation:** run `/kingdom settlement generate`. Follow its reported marker coordinates. Check that generation succeeds and the plaza, two homes, smith and barracks connect by paths. A tower is expected when a suitable extra plot exists.
2. **Here:** travel at least 192 blocks from the first marker, wait for terrain to load, then run `/kingdom settlement generate here`. Check that the core is nearby and does not bury the player. A small obstacle at the first proposed core should make it try other nearby centers.
3. **Debug:** travel to a third clear area and run `/kingdom settlement generate here debug`. Check center/plot counts and the required-four-plots line. Repeat inside an existing settlement: it should fail with overlap information and no new buildings. At a broad lake or cliff, verify useful water/slope/core-area diagnostics without terrain destruction.
4. **Buildings:** inspect the taller hall, meeting room and banners; each house's roof shape, doorway, bed and storage; smith's arcade/anvil/furnace/chimney; barracks' beds/targets; tower ladder/platform when present; board awning, bench and lights. Walk every doorway and path, especially stair transitions. Create more settlements to see all three house variants. Check at night for lighting and confirm no fire hazards.
5. **Mayor:** right-click the stationary Mayor using the main hand. Confirm the robe, gold circlet, portrait, contextual text and four dialogue options. Talk and Settlement Info should update the screen; Leave/Escape should close it. Ordinary residents should move and behave as vanilla villagers.
6. **Names:** confirm the visible Mayor name is a title plus proper name, and that neither dialogue nor the quest reader displays UUIDs/debug suffixes. Internal IDs remain available in explicit diagnostic commands and save data.
7. **Quest Board:** right-click directly and via the Mayor's Open Quest Board option while standing near the board. Repeat with a held item and while crouching. Check the Main quests / Requests / Active quests tabs, localized descriptions, difficulty, expiry, item icons, disabled actions and both scrolling areas. Use Tab, Shift+Tab, Enter, Space, Page Up / Page Down and the visible scroll buttons. Check selected markers, focus outlines and full-label tooltips. Read all Mayor dialogue with scrolling as well. Resize the window and check GUI scales 2 and 3.
8. **Accept and claim:** meet the Mayor first, select Iron Shortage under Main quests, and press Accept. Confirm the accepted mission remains selected under Active quests. Close the GUI, run `/give @s minecraft:iron_ingot 16` with default settings, reopen/Refresh, select the active quest and press Claim. Verify 16 iron consumed, eight emeralds and +10 reputation, then confirm the completed entry remains visible and Claim cannot pay twice. Repeat a dynamic food/material request using its displayed amounts. Test an incomplete inventory and a full inventory: no partial consumption or reward should occur.
9. **Save & Quit/reload:** record the buildings and actor names, save and exit normally, then reopen. Verify unchanged architecture/names, one Mayor and the two original residents (no duplicates), completed quest state and reputation. Reopen the board and ensure the completed reward is still unavailable. Confirm Mayor dialogue now acknowledges the iron delivery.
10. **Older settlement and localization:** in a copy of a pre-0.8.0 world, load an existing physical settlement without regenerating it. Its buildings, UUIDs, reputation and quests must persist. The old Mayor should acquire the clean presentation; a missing association may be created on board use if the marker remains. The new GUI must work without layout metadata. Switch to Español (Argentina), repeat dialogue/board/actions/errors, and look for untranslated keys or clipped labels.

## Status and limitations

The user will perform the next in-game visual check in their modded profile; do not launch Minecraft automatically. Replace the mod with the newly built JAR and restart the game before checking. The earlier development client launched and entered a fresh creative Superflat world. The user stopped Computer Use with physical Escape before the village/UI visual inspection was completed. No in-game screenshots of the redesign or rendered GUI are claimed. Visual acceptance, multi-scale layout, in-game delivery clicks and Save & Quit/reload remain manual; equivalent server payload/gameplay and NBT persistence paths are automated.

The Mayor has a vanilla robe plus custom circlet geometry, not a bespoke texture. Only the PLAINS palette is authored. The tower is optional, resident wandering uses vanilla AI, and there is no automatic visual upgrade of old settlements. An abstract board without a Mayor offers dynamic requests and does not anchor the main chapter. No Housing/Citizens, economy, conquest or profession mechanics were added.

Recommended next milestone, after visual acceptance: Housing/Citizens foundations that discover the existing beds and adopt the saved resident identities. It has not been started.
