# Living Kingdoms 0.16.1: interface, Charter and validation

Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Branch `codex/ui-ux-polish-v0161`, created from `origin/main` at `9a969cc9a293f9c68860d37ded2f781ae76f60c4`. The progression, rewards, worlds and systems of 0.16.0 are kept. No automatic commit, merge or push.

## Blur cause and fix

The earlier fix documented in the README existed in `VillageScreen`, but did not cover `MayorOnboardingScreen`. The exact `Screen` code was checked in `neoforge-21.1.252-sources.jar`: `Screen.render` calls `renderBackground` before drawing its widgets; the inherited background runs `gameRenderer.processBlurEffect` and the menu background. The introduction drew its panel and text before calling `super.render`, so that second pass could blur and cover content already drawn. Changing only colors did not remove the cause.

Both screens now extend `KingdomScreen`, whose final `renderBackground` does not run that pass. They draw a single dark layer over the world and opaque panels, with native text without fractional scaling or shadow. Minecraft's global blur setting is not changed. `VillageTheme` already used opaque colors; tooltips stay native. This removes the path identified in the code; the result still has to be accepted on a real GPU and checked against possible shaders or other mods' changes.

The introduction shows up to five lines per page, fewer when space is tight. Continue, Back and Close are native controls. Back reviews earlier pages or stages on the client without rolling back server state. The name draft is kept when reviewing pages, resizing, reloading fonts or receiving a rejection. Only confirming the last page of the current stage sends an action.

Later Mayor dialogues also have short pages, arrows to review and the usual exit control. Full statistics are in Information; they no longer fill every conversation. A threat still appears in context. If the window leaves less than 280×200 GUI units, a short instruction to enlarge it and a Close button are shown, avoiding overlapping controls.

Corrupted encoding sequences that already existed in both languages were also fixed, including Spanish accents and English separators. Translations keep their arguments.

## HUD and quests

There are two independent cards, with a semi-transparent dark background, opaque text and native 8×8 icons:

- Kingdom: name, reputation and population; safety when there is a threat. Shown only inside the allied territory chosen by the existing service.
- Tracked quest: title, short objective, every required resource and its progress, defeated members of the specific party, and ready-to-claim state. It stays visible outside the kingdom; when ready it names the destination board.

Progress comes from the server snapshot, not client estimates. The server includes the party's original size to show defeated/total, even during a fight. Contribution and reward rules do not change. Expanded mode adds reputation, safety and destination details. Long text wraps without ellipses. When it does not all fit, the lines are kept in pages with a counter that rotate every six visible seconds; progress updates do not keep resetting that clock.

H keeps its existing setting and remapping; K opens local administration and J the remote journal. The HUD hides when screens/chat, F3 or F1 are open. It avoids the space of boss bars that actually cross it. Other overlays are accommodated through offsets; there is no automatic detection of each minimap.

Client configuration in `livingkingdoms-client.toml`:

| Key | Default | Use |
| --- | --- | --- |
| `hudEnabled` | `true` | Show the HUD; H toggles it |
| `showKingdomInfo` | `true` | Kingdom card |
| `showTrackedQuest` | `true` | Quest card |
| `expandedHud` | `false` | Extra details |
| `hudX`, `hudY` | `6`, `6` | Offset in GUI units, clamped to the screen |
| `hudWidth` | `200` | Width between 160 and 320 |
| `hudBackgroundAlpha` | `204` | Opacity between 192 and 255 |

The minimum opacity keeps a contrast ratio of at least 4.5:1 for every tone over a white background in the tested compositing model. This does not replace real reading, nor does it guarantee visual compatibility with every font or shader.

The Quest Board prioritizes objective, progress and unlock conditions. It explains why the job exists and where to claim it. The initial reader shows current work and the next locked step; History shows the rest. It does not delete or alter quests. Short rows keep the full title in the tooltip, narration and detail reader.

## Charter: existing system verified

`SettlementEstablishmentService` and `VillageSurvey` are reused, with no other conversion system. The default minimums are still two living available villagers and two complete beds. The bell is not required. The survey runs when the Charter is used, over already-loaded chunks; it does not force world loading.

Conversion keeps houses, beds, inventories and residents. It promotes a suitable villager to Mayor when it can, or creates the needed Mayor, and places the marker and Quest Board in a free plaza. The success message and next instructions stay in chat, with the settlement location and the step of talking to the Mayor. When a minimum is missing, it shows current/required villagers and beds, explains how to retry and keeps the Charter.

The narrative flow and naming use the 0.16.0 system. The new test uses the real item interaction, completes that conversation and re-reads the saved files through `DimensionDataStorage`, verifying name, UUID, territory and JOINED state. The existing tests for founding from scratch, initial projects, geometry and rollback were kept. Saving and quitting/reopening a full world from the client still requires the manual test below.

## Costs and compatibility

The HUD keeps receiving snapshots compared/deduplicated by the existing service; it adds no other sync. Rendering only draws lines, backgrounds and icons. Its presentation and layout are rebuilt on new data, size, configuration, boss bars or resources. The reader uses a per-screen cache of up to 128 wrapped texts, invalidated when data or fonts update. The introduction and dialogues cache pages and visual order. No dependencies, entity sweeps, AI or per-frame server work were added.

Saved data keeps its schemas. The network registry moves to version 10: client and server must use the same 0.16.1 JAR. Earlier development JARs and worlds are kept.

## Automated validation

- 374 JUnit tests, no failures or skips; they include pagination, word preservation, cache, contrast, translation parity/UTF-8, GUI limits, history, independent cards and real progress.
- 145 GameTests passed in `runs/gametest-v0161-final-validation`. Two new ones verify conversion/narrative/name/disk read and the two incomplete minimums without consumption. Earlier founding, defense, multiplayer and reward checks are still present.
- Gradle build and JAR check: version 0.16.1, Minecraft `[1.21.1]`, NeoForge `[21.1.252,21.2)`, current languages and no test mod/classes.
- HUD atlas, blur-free filtering and nine earlier resources compared against the JAR bytes. The auditor tolerates checkout CRLF endings in JSON and keeps the exact comparison of packaged bytes.
- Resource skill validator on a temporary copy in `build`, with `pack.mcmeta` format 34 checked against 1.21.1 `SharedConstants`. The mod does not need that external pack file. Three vanilla reference warnings are resolved separately against Minecraft's resources; no artificial files are added to the mod.
- `git diff --check`.

The first run logged two failures from a name collision in the new message-capturing helper; it was fixed without altering the old checks and the whole suite was repeated in another world. Both worlds were kept. The provoked rollback logs are part of the rejection tests, not errors of the final run.

## Pending manual test in Minecraft

Use a copy of the usual world and the ordinary JAR `build/libs/livingkingdoms-0.16.1.jar`, not the `-sources.jar`. Record GPU, resolution, GUI scale, language, font/resource pack and shaders. There is no accepted graphical capture of 0.16.1 in this release.

| Resolution | Scales to test, when available |
| --- | --- |
| 854×480 | 1, 2, Auto |
| 1280×720 | 1, 2, 3, Auto |
| 1920×1080 | 1, 2, 3, 4, Auto |
| 2560×1440 | 2, 3, 4, Auto |
| 3840×2160 | 3, 4, Auto |

1. In EN and ES, open every initial Mayor stage, including Name. Compare menu blur at zero and at its maximum, against a day/night world and bright terrain. Text must keep crisp edges and contrast. Back, Continue and Close must stay separate from the text and the field.
2. Type a name with accents; go back to earlier pages, resize and press F3+T. The draft must stay intact. Confirm an invalid or duplicate name, fix it and register; it must not advance twice or lose characters. Closing while waiting for a response must not reopen the screen later.
3. Open the normal dialogue, go through its arrows and enter Information, Construction, Immigration and Citizens. Test tooltips and tabbing. Blur must not reappear when drawing controls, and the portrait must not overlap the arrows. Shrink the window a lot: the notice must appear and be closable.
4. Track a quest from the board or journal. Inside the territory check both cards; outside check that only the kingdom card disappears. Toggle H and remap it; check J/K and that chat, inventory, F1 and F3 respect visibility.
5. For a multi-resource delivery, change amounts in the inventory/offhand and verify every counter. For real combat, defeat members of the linked party and check defeated/total. When finished, it must say ready to claim and name the correct board; claiming once must keep the usual reward.
6. Test long kingdom names, long titles, expanded mode and width 160. If there are pages, read the full content during its cycle, also while progress updates. Add one and several boss bars; check the offsets with the minimap you normally use. Text must not be cropped or overlap.
7. On the Quest Board, check that the reason, objective, lock requirement and reward destination appear. Switch History, accept, track, claim and review the next step. Progression and reward values must match 0.16.0.
8. Convert a real vanilla village with two villagers and two complete beds. Record houses, beds, residents and inventories before/after; find the Mayor and Quest Board, finish the introduction and name it. Repeat with one bed and then with one villager: the minimums must appear and the Charter must not be consumed.
9. Found with a Charter on empty terrain with no sign of a village, supply the Town Hall and first House. Try forced conversion while crouching outside a village: it must refuse without founding. Do not enable cheats for these survival flows.
10. Save and quit, close/reopen Minecraft and return to the world. Verify name, Mayor, board, residents, progress, tracked quest and H preferences. Repeat onboarding with two players: one shared name, personal conversations and no duplicated reward.

Accept visually only after completing these checks. Third-party fonts can change widths; F3+T invalidates the caches. Many boss bars in a small window can take all the safe space and temporarily hide the cards; use offsets, a smaller GUI scale or a single panel. Automatic adaptation to every mod's overlays is out of scope for this fix.
