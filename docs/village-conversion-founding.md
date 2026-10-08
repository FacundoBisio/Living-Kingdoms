# Village Conversion + Founding Kingdom — 0.9.0
> Historical 0.9.0 report. New wilderness foundations now use the [0.10.0 progressive camp and construction flow](progressive-kingdom-founding.md).

Branch: `codex/village-conversion-founding-kingdom`. It started at the same `004afff` commit as main and the QA branch; switching carried all uncommitted QA fixes forward. No reset, rebase, commit or push was needed. The previous blurred GUI, wrapping/layout and structure support/rotation fixes are preserved.

## Survival tool and recipe

`livingkingdoms:kingdom_charter` is a stackable item (maximum 16), in Tools & Utilities. Its current icon uses the vanilla map texture. English and Argentine Spanish names, tooltips and feedback are provided. Obtaining an emerald unlocks the recipe book entry.

The 3×3 recipe consumes two emeralds, one paper, two iron ingots, any one banner and three stone bricks, yielding one Charter:

```text
E P E
I B I
S S S
```

Replace `data/livingkingdoms/recipe/kingdom_charter.json` in a datapack to tune the cost. No operator permissions are checked for the item. Survival spends one Charter only after the full operation commits; creative retains it. A one-second item cooldown bounds repeated attempts. Use on ground normally for automatic conversion/founding; sneak-use for conversion only. Air use and entity use do not establish a settlement.

## Conversion

`VillageSurvey` runs once per attempt, never on a tick hook. It queries loaded villagers and vanilla HOME/MEETING POIs, verifies that HOME records refer to actual complete head-and-foot beds and that MEETING records still refer to a bell, and accepts unclaimed beds. Villagers must be alive, unassigned to Living Kingdoms, within the configured horizontal range and within 24 vertical blocks. No structure bounding box or natural-generation flag is required: player-built villages can qualify.

The closest bell supplies the initial center when present; otherwise the bed centroid does. The survey checks that center again. Every chunk in the survey square must already be loaded, avoiding a false wilderness/undersized-village result at an unloaded edge. This reads POI metadata only for already-loaded chunks and never creates chunk tickets.

Default minimums are two villagers and two beds; a bell is optional. Nearby but incomplete signals refuse automatic establishment with current/required counts. Conversion-only attempts outside a village also refuse and keep the Charter.

The service searches up to 12 blocks from the village center for dry, empty positions for a lodestone, board, Mayor and board approach. It places exactly **two blocks**, never clears a house, replaces paving, moves beds, modifies containers or requires a Town Hall. The persisted center is the lodestone. A horizontal circular territory includes the surveyed villagers/beds/bells plus an eight-block margin, with the normal settlement radius as a minimum and the conversion cap as a maximum. Loaded chunks, build height, border, permissions and existing settlement overlap are checked before mutation.

An unnamed, adult, unmounted, unemployed villager without saved trades or a leash is preferred for promotion. Children, named villagers, traders and existing/reserved Living Kingdoms identities remain intact. The promoted villager moves to a safe plaza position, retains its UUID, receives the existing persistent Mayor role and ceremonial name/robe/circlet, and becomes stationary and protected like generated Mayors. Its vanilla POI claims are released after successful promotion. If no candidate qualifies, the existing Mayor spawn service supplies one. Both cases persist the existing unique Mayor association in quest storage. Conversion does not create founding residents.

## Founding

With no nearby village signals, normal Charter use calls `SettlementGenerator.foundHere`. It uses the existing `SettlementLayoutPlanner.findHere` and `SettlementPlacement`, including their terrain tolerances, support depth, loaded footprint checks, dry ground, entity/obstacle clearance, border checks, overlap rejection, native template verification and rollback. There is no second terrain/building planner.

The search begins with a plaza four blocks north of the chosen ground, then the existing bounded offsets up to 24 blocks. It requires the normal core plus four initial modules; the watchtower remains optional. Native core placement includes the Mayor plaza, lodestone and Quest Board. Founding additionally requires a safely spawned Mayor and two initial resident entities before committing. It saves the normal layout metadata for future additions. Failed attempts return the existing generation diagnostic summary and preserve the Charter.

Population remains the existing abstract field. Conversion starts with the surveyed villager count; founding uses `settlements.initialPopulation`. No housing capacity, citizen AI, immigration, profession progression or settlement level changes are introduced.

## Server transactions and multiplayer

The item runs in NeoForge's item-before-block hook. This avoids the ordinary item-after-block wrapper committing settlement/NPC state before NeoForge has decided whether to cancel its block placements. The establishment services snapshot the affected blocks and post normal block/multi-block placement events **inside** their reversible transaction, honoring protection mods before creating NPCs or consuming inventory.

All mutation occurs synchronously on the owning server thread. Persistent settlement overlap acts as the reservation; a second player's queued attempt or the other hand observes it and cannot create another settlement. The transaction proceeds through validated block placement, settlement reservation, placement-event approval, unique Mayor association and (for founding) initial residents. Ordinary failures restore blocks/block entities, remove only the new settlement/layout/index entry and Mayor association, and restore a promoted villager's complete saved state or discard newly created NPCs. The Charter is spent after commit. Corrupt/future settlement or quest storage fails closed before placing anything.

These are runtime transactions, not a crash journal spanning independently saved Minecraft chunks, entity files, SavedData and player inventory. A process/OS crash during creation cannot be made atomic by this milestone. Missing or unloaded saved Mayors/residents are never automatically replaced, preserving existing duplicate prevention.

## Provenance and compatibility

Settlement SavedData schema **2** reads schema **1**. Each new record has a `provenance` compound:

| Field | Meaning |
| --- | --- |
| `origin` | `GENERATED`, `FOUNDED`, `CONVERTED`, or reserved `LIBERATED` |
| `founder` | Optional player UUID; required for founded/converted records |
| `created_at_epoch_millis` | UTC Unix milliseconds; zero only for unknown legacy time |
| `kingdom` | Optional future kingdom UUID; absent for current creations |

Old records become `GENERATED`, with unknown founder/time and no kingdom, retaining their original settlement UUID, name, faction, level, population, territory and optional physical layout. Migration marks settlement storage dirty so the next normal save writes schema 2. Unknown origins, malformed provenance, corrupt files and future schemas are rejected rather than replaced with empty data. Older mod releases cannot read schema 2; downgrade requires the pre-upgrade world copy.

Quest schema 4, encounter schema 2, progression/entity identities, existing main-chain ownership, reputation, receipts and UI behavior are preserved. Both routes use the same settlement UUID with `NpcService`, `VillageUiService`, `ExpandedQuestService` and `QuestSavedData`. Regional encounter rewards find these allied territories through the existing derived index. The natural encounter spawner is unchanged and remains independent of settlement existence.

Readable names come from the existing UUID-seeded `VillageNames` generator. The persisted `name` remains independent of provenance and UUID, ready for a future rename feature. Normal feedback and GUIs expose the readable name. Technical IDs appear only in explicit operator inspection/debug output. No ownership permissions or actual kingdom hierarchy are attached to the founder/kingdom fields.

`/kingdom settlement inspect` (permission 2) prints the current territory's UUID, origin, founder, epoch creation time, optional kingdom and center/radius. Existing `info`, `create`, generation, reputation, quest and layout debug commands remain. Debug-generated records retain `GENERATED` origin.

## Configuration

Per-world `serverconfig/livingkingdoms-server.toml` adds:

| Section/key | Default | Bounds |
| --- | --- | --- |
| `establishment.villageDetectionRadius` | 48 | 16–96 |
| `establishment.minimumVillagers` | 2 | 1–32 |
| `establishment.minimumBeds` | 2 | 1–64 |
| `establishment.maximumConversionRadius` | 128 | 48–256 |

`settlements.radius` remains the minimum conversion radius and founding radius (default 48). A minimum larger than the conversion cap refuses conversion; choose compatible settings. Existing generation/module configuration controls founding. No per-tick scan option is added.

## Verification

Validation on 2026-10-08 with Java 21 / NeoForge 21.1.252:

| Check | Result |
| --- | --- |
| `gradlew --no-daemon test build runGameTestServer` | BUILD SUCCESSFUL |
| Unit tests | 178 passed, zero failures/errors/skips |
| Server GameTests | All 68 required tests passed |
| `git diff --check` | Passed |
| Native module generator `--check` and geometry audit | All eight modules, all four rotations passed |
| Production JAR | `build/libs/livingkingdoms-0.9.0.jar`, 446,763 bytes; Charter recipe/advancement/model/translations included; zero test-mod/GameTest entries |

The refused-Mayor GameTest deliberately logs two rollback errors; they are injected failures, and its block/data/inventory restoration assertions pass. Ordinary recipe/advancement loading succeeds. Unit and server tests exercise save data without resetting normal development worlds.

The new tests cover registered item and actual datapack crafting with multiple banner colors; valid/invalid/no-bell conversion; complete/stale bed POIs; existing villager promotion and trader-preserving fallback; exact success-only item consumption; the real NeoForge item-first hook; two players and both hands; immediate Mayor/board GUI and quests; shared encounter reputation; entity and settlement/quest NBT reload; actual SavedData file reopen; origin/founder/kingdom persistence; schema-1 migration and malformed/future guards; unloaded chunks/dimension/water failures; no free plaza; canceled protection events; refused Mayor spawning; full promoted entity rollback including an originally absent name; and derived territory/index rollback.

Remote GameTest fixtures use temporary, expiring test-only region tickets to model a real player's loaded/ticking village. Gameplay never uses those tickets. All prior unit and GameTests remain in the suite.

Graphical QA remains pending on this host because the previous native client attempts crashed in AMD `atio6axx.dll` before gameplay. Save/reload is verified through NBT and actual SavedData reopen, with the complete interactive sequence below prepared for a working client.

## Exact manual QA for a working client

Use matching 0.9.0 client/server builds and a survival player without operator permissions. A second operator/test account may inspect metadata; it is optional for gameplay. Keep two Charters for failure/duplicate checks. Repeat the conversion and founding steps in a multiplayer world with two players where possible.

1. **Convert a vanilla village.** Find an ordinary village in the Overworld. Confirm at least two living villagers and two complete beds; a bell is helpful but optional. Let all nearby chunks load (walk around the village first). Photograph/record a house, chest contents, beds and bell. Craft the recipe above using any banner. Hold two Charters and right-click nearby ground without sneaking. Expect one Charter left, a readable allied settlement name with marker coordinates, a new lodestone/Quest Board and one named Mayor. Verify the recorded vanilla blocks and chest contents remain. No Town Hall should replace houses. On an operator account, `inspect` should report `CONVERTED`, the survival player's UUID, a positive creation time and `kingdom=none`.
2. **Fail conversion outside a village.** Move at least 200 blocks from the converted marker to loaded wilderness with no villagers/beds/bell. Sneak-right-click ground with a Charter. Expect a missing villager/bed count message, the same item count, no marker/board/buildings and no new territory. Release sneaking before the later founding test.
3. **Reject duplicate conversion.** Return to the village and wait one second for cooldown. Use another Charter on ground inside its territory. Expect overlap feedback and unchanged Charter count. Have a second survival player attempt the same conversion and repeat with a Charter in the offhand. Verify the same settlement, one Mayor and one board remain; only the original successful attempt spent an item.
4. **Found a new settlement.** Return to wilderness at least 200 blocks from every settlement marker and at least 48 blocks from villagers/beds/bell. Find a sufficiently large dry clearing, load the surrounding area, and right-click ground normally. Expect one Charter spent, a generated readable name, the native core/modules/paths/board, one Mayor and two initial residents. Optional operator `inspect` must show `FOUNDED` and the correct founder. Try normal use in an unsuitable flooded/cliff area elsewhere: failure must retain the Charter and leave terrain unchanged.
5. **Inspect both Mayors.** In each settlement, inspect the proper name, ceremonial robe/circlet and absence of visible UUIDs. Right-click within eight blocks to open dialogue. Talk to the Mayor and inspect the existing main-chain progression. In the converted village, confirm an eligible unemployed adult was promoted, or that existing traders retained their profession/trades and one fallback Mayor was added.
6. **Inspect both Quest Boards.** Right-click each physical board with empty and occupied hands. Confirm Main quests, Requests and Active quests are readable at normal and small GUI sizes. Follow the existing chain and accept a request; deliver the required resources and Claim once. Verify resource consumption, emerald reward and reputation. Repeating Claim must not pay again. An eligible natural encounter near the new territory should award the existing regional reputation once, using current contribution rules. A second settlement does not reset or re-anchor an already-owned global main chain.
7. **Save & Quit and reload.** Record names, marker positions, Mayor names, active/completed quests, reputation and Charter count. An operator can also record `inspect` UUID/founder/origin/time and the Mayor UUID. Save & Quit or stop the dedicated server cleanly, then reopen the same world. Do not create a fresh world. Also test `/reload` with the operator account.
8. **Verify the same settlements.** Revisit both markers. Confirm identical names, origin/founder/time/settlement UUID through optional `inspect`, the same Mayor identities, no additional residents or boards, preserved vanilla houses/inventories, working dialogue/board, prior quests/reputation and saved Charter count. Retry establishment there and expect rejection without spending an item. On an upgraded existing world, also verify an old generated settlement and previously active encounter/quest state still work; old settlement provenance should now report `GENERATED` with unknown founder/time.

## Limits and next milestone

Detection describes local villager/bed clusters, so custom/player-built villages qualify and an empty or abandoned generated village does not. Isolated villagers or beds nearby deliberately count as incomplete signals: move outside the survey radius before wilderness founding. The survey is bounded and requires loaded chunks; a sprawling village may need a suitable center/configuration and a free plaza. Conversion adds minimal infrastructure only and has no generated-core layout reservation for automatic building additions; surveying a converted village for future additions is a later feature.

The Charter's initial map icon is a reused vanilla asset. Renaming, ownership permissions, kingdom hierarchy, liberation and automated replacement of missing NPCs remain future work. Housing, Citizens immigration, professions, settlement levels, raids, conquest and bosses were not started.

Recommended next step: complete the interactive multiplayer/save-reload checklist, then design the Housing/Citizens milestone around existing converted buildings and persisted settlement identity. This delivery does not begin it.
