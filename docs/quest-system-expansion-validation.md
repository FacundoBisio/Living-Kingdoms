# Quest system expansion validation

Date: 2026-10-07 (America/Buenos_Aires). Branch: **`codex/quest-system-expansion`**, based on committed `5bb3f79`. Version **0.7.0**, Minecraft 1.21.1, NeoForge 21.1.252, Java 21. Existing settlements, Mayor, board, Iron Shortage, reputation, factions, natural encounters, faction combat, contributions and progression remain. No automatic commit, push or next milestone.

## Architecture

`quest.expansion.domain` adds explicit MAIN/DYNAMIC categories, stable `QuestTemplate` identities, source roles, typed objectives, immutable `QuestInstance` snapshots, `QuestCatalog` chapter order, `QuestGenerator` weighted candidates, difficulty and reward policies. The existing quest state/type vocabulary expands with EXPIRED and hostile-party/meeting/return handlers; unsupported future objective types are not fake implementations.

Each instance owns a UUID, template/category/chapter/order, source settlement UUID, optional NPC UUID, source role, objective, recommended `LevelValue`, difficulty, emerald/reputation reward, lifecycle, objective-ready flag and creation/expiration clocks. Category/chapter/order come from its stable template rather than duplicate editable state. Objectives contain identifiers/terms, never live entity references or structure layout coordinates.

`QuestSavedData` retains the original Iron/reputation/Mayor/encounter-receipt aggregate and adds expanded player journals, main anchors and board clocks. `ExpandedQuestNbt` validates typed records and clocks. A derived party UUID index addresses accepted unresolved contracts directly on defeat; it is rebuilt on load. `ExpandedQuestService` handles board/Mayor context, generation, the legacy Iron bridge and claims. `QuestCommands` supplies normal player actions behind clickable chat components. `DeliveryInventory` now shares one transactional handler for all resource variants, retaining its old iron API.

All gameplay runs on the server thread. Board actions validate the real loaded block, alive nonspectator player, eight-block reach, allied territory, source settlement, ownership and state. Commands do not let a client assign state/rewards or act on another player's UUID. Console calls require a player. Common production code has no client imports, GUI or new runtime dependency.

## Playable main chapter

Chapter 1 **First Steps** is one deterministic chain per player, anchored globally to the first Mayor-backed settlement visited through the board or Mayor. Abstract settlements without an associated Mayor cannot strand the anchor. Prerequisites are enforced when offers are created and accepted; later steps are not generated ahead of earlier completion.

| Order | Quest | Playable action and reward |
| --- | --- | --- |
| 1 | Meet the Mayor | Valid main-hand interaction with the associated Mayor completes the zero-reward meeting step. Existing dialogue/vanilla interaction suppression remains. |
| 2 | Iron Shortage | Uses the existing quest and frozen accepted terms; default 16 iron, eight emeralds/+10 reputation. Old saved completion also satisfies this step without another payout. |
| 3 | Secure the Roads | Binds one actual nearby non-debug Pillager Patrol UUID. Accept, contribute, defeat it and confirm at the board. This step pays zero; the roaming reputation system still resolves independently. |
| 4 | Return Home | Accept and claim at the chapter settlement's board for default six emeralds/+5 reputation. |

Main quests do not expire. A quiet region reports that no patrol is available, rather than inventing an enemy. An unresolved missing, retired, defeated without participation or invalid target becomes FAILED. Failed main patrols may be replaced with a new live target UUID on a later board visit. Confirmed victory stays ready even after encounter metadata is cleaned up. No second chapter or long story campaign is added.

The original crouch-click Iron loop remains playable per player/per settlement, including worlds where the player delivered before meeting a Mayor. Main Iron only observes that original completion and displays its actual current/accepted terms. A stale expanded Accept action cannot silently deliver resources. Main rewards are not replayable by changing settlement.

## Playable dynamic requests

| Template | Default requirement | Source role |
| --- | --- | --- |
| Iron Supplies | 16 iron ingots | BLACKSMITH |
| Food Shortage | 32 wheat | FARMER |
| Building Materials | 32 logs and 32 stone | CITIZEN |
| Raiders on the Road | Actual nearby PILLAGER_PATROL UUID | GUARD_CAPTAIN |
| The Restless Dead | Actual nearby UNDEAD_HORDE UUID | GUARD_CAPTAIN |

Resources share a list of typed requirements, not separate hardcoded delivery implementations. `LOGS` matches Minecraft's `logs` item tag, including qualifying ordinary, stripped, wood and Nether variants; `STONE` means Items.STONE, not cobblestone. Main inventory and offhand count; armor does not. The complete multi-resource removal and full emerald insertion are planned on copied stacks, preserving other items/components. Insufficient resources or reward capacity changes no inventory or quest payout. Reward-only and zero-emerald claims use the same exchange planner.

Only the Mayor is instantiated. BLACKSMITH/FARMER/GUARD_CAPTAIN/CITIZEN are source roles brokered by the board, with absent NPC UUIDs until real NPC gameplay exists. Mayor-sourced main records retain the associated NPC UUID when known. There are no new professions, citizen requests backed by fake entities, or stockpile simulations.

## Generation, rotation and expiration

Board generation runs on demand after a saved per-player/per-settlement cooldown, default **48000 elapsed server ticks** (two ticking game days). Rotation saves its timestamp and monotonically increasing generation counter. The seed uses player UUID, settlement UUID and that counter; opening/reconnecting/using another board cannot advance it prematurely. Acceptance does not change a saved offer's objective, difficulty or rewards.

Default total dynamic slots are three; config allows 2–4. Existing ACTIVE requests are preserved and occupy slots at the next rotation. Terminal/unaccepted requests from that settlement are replaced only after cooldown. Without hostiles the three resource templates are the entire candidate set, so count four can yield three offers. Config lowering slot count does not delete accepted contracts. Unaccepted offers expire after default 48000 ticks on observation; accepted requests stay active. Main quests never expire. Time uses Overworld `getGameTime`, unaffected by sleep, `/time set` or offline wall time.

The service queries active party origins within default 256 blocks of the settlement center using the existing metadata index. It excludes all debug parties and duplicate already-active dynamic target UUIDs. Each resource fallback has weight 3. Every real nearby party contributes its matching faction/type candidate with weight `8 + min(4, recommendedLevel/5)`, so hostile activity changes combat likelihood. Candidates are sorted stably and selected without replacement. No random enemy or chunk is generated to fulfill a quest.

The pure generator accepts resource-shortage weights for future settlement resource events. Current runtime has no genuine stockpile and supplies no shortage bonuses: resource quests are fallback requests, while combat context uses actual world state. Adding a resource economy is outside this milestone.

## Difficulty and rewards

Resource level comes from the existing `RegionalDifficultyService`, with no duplicate distance/age calculation. Combat level is the ceiling of the target's original party average. Each generated quest snapshots level and terms. Quantity is configurable through typed objectives; there is no extra quantity multiplier or player-equipment scaling yet.

| Difficulty | Recommended level | Difficulty reward steps |
| --- | --- | --- |
| VERY_EASY | 1–3 | 0 |
| EASY | 4–7 | 1 |
| NORMAL | 8–12 | 2 |
| HARD | 13–20 | 3 |
| VERY_HARD | 21–100 | 4 |

Dynamic resource base is four emeralds/+5 reputation; combat base four/+3. Defaults add one emerald and one reputation per step, capped at 32 emeralds/50 reputation. Main meeting/iron/patrol expansion records pay zero; the original Iron pays its own configured reward, and final return has a fixed configurable reward. Rewards support zero but remain bounded by the shared inventory/reputation limits. More reward types require real handlers later; no loot generator, experience or unlock system is added.

`completeExpanded` preflights reputation overflow, performs one ACTIVE+ready → COMPLETED transition and applies source-settlement reputation in the same quest store. The service then applies the already-preflighted inventory exchange without yielding. Failed capacity/duplicate claims cannot pay. Like the original quest, this is server-event atomicity, not a crash transaction across player/entity/chunk files.

## Board and multiplayer behavior

Normal right-click shows MAIN QUESTS and AVAILABLE REQUESTS / ACTIVE QUESTS with localized title/state/source, recommended level/rating, objectives/progress, reward and clickable actions. Combat descriptions report approximate X/Z origin instead of requiring players to identify a raw UUID. Moving groups may have traveled from that report.

Click actions run these server-revalidated commands; normal gameplay exposes the buttons, so typing and operator permission are unnecessary:

```text
/kingdom quest inspect <quest-uuid> <board-x> <board-y> <board-z>
/kingdom quest accept <quest-uuid> <board-x> <board-y> <board-z>
/kingdom quest claim <quest-uuid> <board-x> <board-y> <board-z>
```

Every main journal, dynamic instance and batch belongs to a player UUID. Other players cannot accept or claim it. Combat completion requires that player's own ACTIVE contract at the recorded party's actual final defeat and the existing meaningful participation threshold: default cumulative damage score >=4 with a recent hit inside 6000 ticks. Prior recent contribution can count if the quest is active at defeat; this milestone does not maintain a separate acceptance-time damage ledger. Passive observers and nonparticipants fail the defeated-target contract rather than inheriting victory. Multiple meaningful participants may each finish their own accepted contract.

Final defeat marks the objective ready, not paid. Players must return and claim; offline UUIDs keep ready state. Unrelated vanilla deaths, another party UUID, debug encounters, faction-only victory without player contribution and retirement cannot fabricate objective completion. Ready contracts retain proof after party/receipt cleanup. Unresolved vanished targets are reconciled during that player's next board interaction, without a global quest poll.

Quest reputation always goes to its source settlement. Existing roaming reputation independently awards the relevant nearby allied region once; the two are distinct intentional rewards. Disabling roaming reputation does not prevent legitimate non-debug contribution from reaching combat quest resolution. Debug reward-test encounters remain an explicit operator roaming test and are excluded from quest generation.

## Configuration and compatibility

New settings append to the existing per-world `serverconfig/livingkingdoms-server.toml`; old `[quests]` settings still control original/main Iron acceptance. All new defaults:

```toml
[quests.expansion]
dynamicCount = 3
refreshTicks = 48000
expirationTicks = 48000
encounterRange = 256
requiredIron = 16
requiredWheat = 32
requiredLogs = 32
requiredStone = 32

[quests.expansion.rewards]
resourceEmeralds = 4
resourceReputation = 5
combatEmeralds = 4
combatReputation = 3
mainReturnEmeralds = 6
mainReturnReputation = 5
emeraldsPerDifficulty = 1
reputationPerDifficulty = 1
maximumEmeralds = 32
maximumReputation = 50
```

Quest SavedData schema **4** reads schemas **1–3** and preserves all old Iron progress/terms, reputation, Mayor associations and single/batch encounter receipts. New state occupies an additive expanded player section, not a second independent reputation store. Party indexes rebuild from valid accepted objectives on reload. A malformed schema downgrade retaining nonempty expanded data is rejected rather than silently dropping the new journal. Unsupported/corrupt files retain the existing refusal-to-overwrite guard. Settlement schema 1, encounter schema 2 and entity progression schema 1 are unchanged.

Bounds are 256 expanded quests and 1024 board records per player. Main history and active contracts remain; old dynamic records at a settlement are pruned at its rotation. Visiting many settlements without returning to old boards may reach the quest bound and suppress fresh offers. No cross-file crash journal, administrative reset or quest abandonment system is added.

## Validation

Java 21 coordinated command:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

Final coordinated Gradle result: **BUILD SUCCESSFUL**, exit 0, **1 minute 41 seconds**. **153 JUnit tests** across 18 suites passed, zero failures/errors/skips. The coordinated run reused the preceding passing unit execution. **All 41 required GameTests passed**, **42.15 seconds**. The headless server saved all dimensions and shut down cleanly. Unit reports: `build/test-results/test`; server log: `runs/gametest/logs/latest.log`.

All previous 109 unit and 34 GameTests remain. Forty-four new unit cases cover category/template ordering and prerequisites, typed objectives/resources, deterministic weighted generation, faction/UUID association, level-derived difficulty, configurable bounded rewards, lifecycle, acceptance snapshots, rotation/expiry, multiplayer/index resolution, once-only reward/overflow preflight, source/NBT validation, legacy schemas and compressed disk reload. Nine of those exercise native stack exchange preflight/components; log tag behavior is checked in the real server.

Seven new GameTests include two inventory/tag cases and five integrated board/player/NPC/entity cases. They exercise real board feedback and clickable permission-zero commands; ownership, distance, wrong block/settlement denial; wheat/logs+stone delivery; no duplicate claims across reconnect/disk reload; saved rotation clocks; actual Mayor → original Iron → actual Pillager victory → return chain; nonparticipant failure; actual Undead victory against the exact target; unrelated deaths; and debug/missing/retired target failure. Terrain, fake players and explicit test chunk preparation remain in the isolated GameTest mod and do not ship in production.

Earlier schema migration assertions now expect schema 4, and future-schema rejection uses 5; their original persistence/reward checks remain. The first integration compile needed an explicit generic type for the nearest-patrol comparator. Two old migration assertions initially expected schema 3; they were updated to the new emitted version, after which the complete suite passed. No production behavior or existing gameplay assertions were weakened to make tests pass.

The server emitted a 9.174-second overload warning while preparing remote test terrain/chunks. This is functional verification, not a sustained multiplayer performance benchmark. No graphical client attempt was repeated: the previously documented AMD `atio6axx.dll` window-creation crash remains unrelated to quest architecture. Manual client chat-click UX, travel/combat, Save and Quit/reopen and long multiplayer sessions remain unverified on this machine.

Artifact: `build/libs/livingkingdoms-0.7.0.jar`, **280,359 bytes**, SHA-256 `5772ec52e05c024128bf688df54baa42ab8d5108db010cb0edbe8f2735a0f6a6`. Archive inspection confirms expanded service/domain/commands/NBT, excludes GameTest classes/test-mod resources, and retains the unchanged native settlement template (18,511 bytes; SHA-256 `57162a361a53eacebd6fbcf95bebd43db7bc1724414c41ce9a4630e8f51dcb3f`). English/Argentinian Spanish JSON parses with 101 aligned keys/placeholders. Final whitespace/UTF-8/diff checks cover tracked and new files.

## Performance, limits and next milestone

There is no quest tick loop, global entity/settlement scan or gameplay chunk loading. Board work scans only that bounded player's records and queries nearby origin buckets on generation or target binding; live target checks are UUID metadata lookups. Damage retains the existing member index; final defeat uses the new accepted-contract party index. Save reload rebuilds derived maps. No quest depends on the current placeholder structure's layout beyond using an actual board inside saved allied territory.

Limits: one main chapter/anchor; no abandonment or transfer; accepted resource requests can remain active indefinitely; combat targets may disappear and fail; origin hints are approximate; source roles mostly lack concrete NPC entities; resource fallback needs do not derive from an economy; per-player bounds can suppress distant fresh offers; no automatic world spawning for quests. No housing, citizens, professions, settlement upgrades, GUI, rescue/escort AI, bosses, conquest or structure redesign is implemented.

Recommended next milestone: one **Tier 1 Pillager camp** and a linked regional quest, reusing shared hostile settlements/factions, progression and typed objectives. Keep raids, conquest, housing and advanced NPC behavior out of that first step. It has not been started.
