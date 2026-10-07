# Progression core validation

Date: 2026-10-07 (America/Buenos_Aires). Branch: `codex/progression-core`. Mod version: **0.6.0**; Minecraft 1.21.1, NeoForge 21.1.252, Java 21. Work continues from committed `9671747` and the existing uncommitted dynamic encounter milestone. Earlier settlements, Mayor, Quest Board, Iron Shortage, reputation, factions, roaming parties, natural encounters, faction combat and multiplayer contribution are preserved. No automatic commit, push or following milestone.

## Architecture and authority

`progression.domain` holds Minecraft-independent `LevelValue`, `LevelSummary`, `RegionalRules`, `RegionalDifficulty`, `PartyLevels`, `StatRules`, `StatScaling` and `EquipmentRules`. `LevelValue` is a shared 1–100 identity; future allied NPC professions can use that value with different policies. Current hostile scaling requires a managed encounter identity and does not assign levels to ordinary vanilla mobs, players, Mayors or Guards.

`RegionalDifficultyService.at(ServerLevel, BlockPos)` reads server-owned inputs on demand. `ProgressionService.initializeParty` rolls one regional window for both existing hostile types and assigns individual frozen profiles. `EntityProgression` applies stable permanent attribute IDs and stores entity level, elite status and stat bonuses in `livingkingdoms:progression` persistent data (entity schema 1). `EquipmentProgression` applies small faction-aware spawn loadouts separately from AI. `ProgressionEvents` restores profiles on valid entity joins and scales managed-owner native arrows once, using a persistent projectile marker. Vanilla Zombie conversion copies the profile to the replacement member.

Mutation entrypoints require the server thread. Spawn initialization occurs after vanilla mob finalization and before entity addition; party metadata is installed before join validation. Reload removes/replaces only this mod's four attribute IDs, keeps other modifiers and preserves current health up to the restored maximum. It does not reroll levels, elite status or equipment. New spawns start at their scaled maximum health. Native conversion health behavior remains; this milestone preserves progression identity through conversion rather than changing vanilla conversion mechanics.

## Regional level and distribution

The current deterministic calculation is:

```text
distance = hypot(x - originX, z - originZ)
distanceBonus = min(distance cap, floor(distance / band size) * levels per band)
ageBonus = min(age cap, floor(elapsed game days / days per level))
activityBonus = min(activity cap, floor(nearby threat points / threat per level))
tierBonus = min(tier cap, caller-supplied hostile settlement tier * tier weight)
regionalLevel = min(maximumLevel, baseLevel + all contributions)
memberLevel = clamp(regionalLevel + random offset, 1, maximumLevel)
```

Default origin is coordinate X=0, Z=0, configurable for a chosen start region; it does not automatically follow spawn relocation. Distance is horizontal Euclidean distance in the current dimension, with the same origin coordinates/config. Natural spawning remains Overworld-only. Player equipment, individual player level and Minecraft local difficulty are not inputs to the Living Kingdoms formula (vanilla spawn finalization still uses its own rules).

World age uses Overworld `getGameTime()/24000`: elapsed ticking time, not daylight changes, real wall time or offline time. Sleeping and `/time set` do not accelerate it. Regional activity sums configurable party threat ratings from the existing active-origin index within 192 blocks, excluding debug parties. Loaded and unloaded active party metadata can count. This is an origin approximation; roving members do not move their party bucket.

Hostile settlement tier is already an input to the pure calculator, with independent weight/cap. Current runtime supplies zero because no camp/tier gameplay exists yet. No settlement scan, conquest state or automatic promotion is introduced.

| Distance from configured origin | Regional level with no age/activity/tier bonus |
| --- | --- |
| 0–1023 blocks | 1 |
| 1024–2047 | 2 |
| 4096 | 5 |
| 9216 | 10 |
| 19456 | 20 |
| 24576 or farther | 25 (distance contribution capped) |

Age can add at most 3 levels; activity at most 2; total defaults cap at 30. Members roll in the regional ±2 window, clamped to 1–30. A level-10 region produces levels 8–12. If all rolls happen to match and the permitted window has at least two levels, the last member receives a different permitted level. Setting variance to zero or maximum level to one intentionally allows identical levels.

`HostileParty.levels()` and `EncounterQueries.Reference.levels()` expose count, sum, minimum, maximum and `average()`. These are original spawn-roster snapshots, retained through deaths and UUID replacement. Individual levels stay in native entity persistence; party storage does not duplicate a full per-entity stat/equipment record. Existing threat ratings and reputation values retain their earlier meanings and are not multiplied by level.

## Stats, equipment and elites

Stat steps are `level - 1`, with separate configurable caps:

| Stat | Default per step | Default cap | Implementation |
| --- | --- | --- | --- |
| Maximum health | +2.5% | +75% | Permanent base multiplier |
| Damage | +2% | +50% | Melee attribute; managed-owner native arrow base damage |
| Armor | +0.15 points | +4 points | Permanent additive modifier |
| Movement | +0.2% | +8% | Permanent base multiplier |

Level 1 has zero stat bonuses. Level 5 gives +10% health and +8% damage; level 10 gives +22.5%/+18%; level 20 gives +47.5%/+38% before elite bonuses. Even level 100 uses the same caps. Config hard bounds limit this system to +100% health/damage, +8 armor and +15% speed; native equipment, effects or other mods can add their own modifiers. Ranged accuracy, targeting and attack mechanics are unchanged. Arrow scaling leaves vanilla/player projectiles alone and the persisted marker prevents scaling twice after reload.

Pillager, Bandit and Undead policies share configurable leather (5), chainmail (10) and iron (20) tiers. Allied roles return BASIC without hostile equipment upgrades. Current runtime fills only empty chest/head slots; existing gear is kept even if it differs from the selected tier. Captain banners are protected, Pillager crossbows and Skeleton bows remain. Undead Zombies with an empty main hand get an iron sword at chainmail tier or higher. There are no custom items.

At level 15 or higher, an 8% chance adds Protection I to an unenchanted chest item, at most once during initial setup. Added equipment has base drop chance zero; vanilla Looting may still influence equipment drops. Native loot tables are unchanged. At level 20 or higher, a 5% elite roll adds +10% health/damage and +1 armor within the ordinary caps. Elite status persists. There are no special abilities, bosses or models.

## Configuration defaults

These sections are part of the existing per-world `serverconfig/livingkingdoms-server.toml`. Normal NeoForge server config loading/correction applies. Equipment thresholds edited out of order normalize upward (chainmail at least leather, iron at least chainmail). All level/stat/equipment rolls snapshot when new members spawn; changing config does not silently upgrade or weaken saved members. Existing levels may therefore exceed a newly lowered maximum, within the supported 1–100 save bound.

```toml
[progression]
maximumLevel = 30
baseLevel = 1
originX = 0
originZ = 0
blocksPerDistanceBand = 1024
levelsPerDistanceBand = 1
maximumDistanceContribution = 24
worldDaysPerLevel = 10
maximumWorldAgeContribution = 3
activityRadius = 192
activityThreatPerLevel = 4
maximumActivityContribution = 2
levelsPerHostileSettlementTier = 2
maximumHostileTierContribution = 10
memberLevelVariance = 2
showLevelNames = false

[progression.stats]
healthBonusPerLevel = 0.025
maximumHealthBonus = 0.75
damageBonusPerLevel = 0.02
maximumDamageBonus = 0.5
armorPerLevel = 0.15
maximumArmorBonus = 4.0
movementBonusPerLevel = 0.002
maximumMovementBonus = 0.08
eliteHealthDamageBonus = 0.1
eliteArmorBonus = 1.0

[progression.equipment]
leatherLevel = 5
chainmailLevel = 10
ironLevel = 20
enchantmentMinimumLevel = 15
enchantmentChance = 0.08

[progression.elite]
minimumLevel = 20
chance = 0.05
```

Setting contribution caps to zero disables those regional inputs. Setting stat caps to zero disables those bonuses; setting enchantment/elite chances to zero disables those rolls. `maximumLevel=1` and variance zero provide a vanilla-level mode for new encounters. Optional `showLevelNames=true` adds visible level names to new members and a gold elite prefix. The default is false. Native custom names persist, so changing that setting does not rename already saved entities.

## Commands and future APIs

All controls below require permission level 2. They only inspect state:

```text
/kingdom progression info
/kingdom progression info <entity-uuid>
/kingdom progression info @e[type=minecraft:pillager,sort=nearest,limit=1]
/kingdom encounter info <party-uuid>
```

Without a target, progression info shows regional level and each contribution at the command source. An entity UUID/single selector reports a managed mob's level, elite flag and raw fractional bonuses; unrelated mobs report no managed progression. Encounter info adds roster minimum, maximum and average. Existing encounter spawn and reward-test commands remain functional and unchanged in authorization/reward policy. There is no level assignment, reroll or promotion command.

Future quests can use `RegionalDifficultyService.at`, `RegionalDifficulty.calculate`, `EntityProgression.read` and `EncounterQueries` references for region/target/party/tier difficulty. A later hostile settlement service can supply its tier to the pure calculation. Future civilian professions can share `LevelValue` and define role-specific rules; this does not apply combat bonuses to them. No new quest, NPC progression, player leveling or settlement progression is started.

## Compatibility and performance

Encounter SavedData schema remains **2**, still reading schema 1. The new optional `levels` compound defaults to a uniform level-1 roster when absent and validates its count against membership; malformed present data is rejected. Old constructors default the same way. Existing managed entities without progression become level 1 with zero modifiers on join, retaining health and gear. Entity persistent data schema 1 freezes the complete profile; missing fields in a present profile or invalid bounds/schema are rejected rather than silently rerolled. Quest/reputation schema **3** and settlement schema **1** are unchanged by progression, retaining their previous migrations.

Vanilla entity/chunk persistence owns equipment, health, position and modifiers. Existing dirty-marked party/reputation data keeps its UUID, contribution, cooldown and receipt behavior. Independent Minecraft save files still do not form a crash-atomic transaction; normal saved reloads/reconnects are supported, arbitrary partial restores are not reconciled by a new journal.

Progression has no tick loop. A party spawn queries the regional metadata buckets once and handles at most the configured small roster. Explicit info commands perform the same on-demand lookup. Entity join restores four stable modifiers; projectile join performs one owner/profile lookup. No global entity scan, per-tick settlement calculation, forced chunk load or unloaded combat simulation is introduced. Functional tests are not a sustained multiplayer performance benchmark.

## Verification

Commands run on Java 21:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

The coordinated Gradle run completed with **BUILD SUCCESSFUL**, exit 0, in 1 minute 6 seconds. **109 JUnit tests** across 15 suites passed with zero failures, errors or skips; this coordinated run reused the passing unit results. **All 34 required GameTests passed** in 27.79 seconds. The headless server saved all dimensions and shut down cleanly. Unit XML is under `build/test-results/test`; the GameTest log is `runs/gametest/logs/latest.log`. After final Spanish localization corrections, `build` passed again in 22 seconds and reran all 109 unit cases successfully; production Java and GameTest code were unchanged after the passing GameTest run.

All 89 previous unit cases and 27 previous GameTests are retained. Twenty new unit cases cover supported level bounds, distance band boundaries/extreme saturation, slow/capped age, independent activity/tier limits, total cap, custom/disabled policies, shared distributions/variety, level summaries, conservative/high-level stats, equipment/faction/enchantment/elite thresholds and legacy/schema-2 party progression persistence.

Seven new GameTests exercise actual scaled attributes and foreign-modifier retention, entity NBT reload without stacking/healing, level-1 migration and vanilla exclusion, both actual encounter spawn paths/summary round trips, iron gear/native ranged weapons/Captain banner preservation, scaled native arrow combat/reload deduplication, real Zombie-to-Drowned conversion, command permissions/read-only inspection and rejected off-thread mutation. Existing real multiplayer contribution/credit tests also pass with the new spawn progression.

The first coordinated attempt found an existing crossbow fixture scheduling problem: it registered many `onEachTick` callbacks from inside a timed callback while `GameTestInfo` iterated its callback map. The fixture now registers its observation loop before ticking, retaining its target/damage/deadline assertions and native active-chunk combat. The complete suite then passed; no production behavior was changed to accommodate that fixture.

The final run emitted a 2.362-second overload warning while preparing remote test chunks/terrain. No sustained gameplay timing result is claimed. Test-only classes, fake players, chunk tickets and terrain helpers remain isolated in the separate GameTest mod.

Final artifact checks passed:

- `git diff --check`, plus explicit UTF-8/trailing-whitespace validation covering new untracked source/doc files.
- Native settlement template unchanged: 18,511 bytes, SHA-256 `57162a361a53eacebd6fbcf95bebd43db7bc1724414c41ce9a4630e8f51dcb3f`.
- Both localization JSON files parse; all 57 keys and placeholder counts align.
- Production JAR `build/libs/livingkingdoms-0.6.0.jar`: 209,190 bytes, SHA-256 `c8009e1b56c3db0738f6b5f91ad40522b0860f54a9814e0ec1e213c7120faac9`.
- Archive contains the progression service/profile/hooks/commands, updated translations and original native template; no GameTest classes or test-mod resources. No client-only imports or new runtime dependencies were added.

No graphical client run was repeated. The previously documented AMD `atio6axx.dll` window-creation crash remains outside mod architecture; graphical travel/combat, client Save and Quit/reopen and longer multiplayer playtesting remain unverified on this machine.

## Limits and next milestone

Distance tuning uses a configurable coordinate origin, not an automatic player-specific start area. Activity remains approximate party-origin metadata. Hostile settlement tier is a future caller input only. Party summaries are original-roster snapshots. Config changes do not alter existing profiles. Equipment fills empty slots and currently covers head/chest and Undead melee weapons; no full loadout generator or accuracy scaling exists. Elite units only have a flag and capped stats, with optional names. Bandit content, player levels, civilian progression, settlement buildings, bosses, raids, advanced abilities, custom models/weapons, conquest and diplomacy remain outside scope.

Recommended next milestone: one regional Quest Board combat mission (“Raiders on the Road”) using party UUIDs, regional/party levels and the existing contribution/defeat boundary. Handle missing or retired targets explicitly. It has not been started.
