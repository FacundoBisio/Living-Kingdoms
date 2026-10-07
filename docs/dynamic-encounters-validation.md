# Dynamic world encounters validation

Date: 2026-10-07 (America/Buenos_Aires). Branch: `codex/dynamic-world-encounters`, based on `9671747` with all Milestone 0–2 and 0.4.0 encounter work preserved. Mod version: 0.5.0; Minecraft 1.21.1, NeoForge 21.1.252, Java 21. No automatic commit, push or following milestone.

## Playable behavior

- Conservative natural **Pillager Patrols** (3–5 vanilla Pillagers, optional Captain) and **Undead Hordes** (4–8 adult Zombies/Skeletons) in active Overworld wilderness. Bandit identity and relationships remain; no Bandit content yet.
- Central symmetric `FactionRelations.getRelation`/`isHostile`: same faction ALLY; Allied versus all three hostile factions HOSTILE; Pillager/Undead and Bandit/Undead HOSTILE; Pillager/Bandit NEUTRAL.
- Authoritatively rostered Pillagers and Undead acquire each other locally and use vanilla combat. A tagged allied `GUARD` can use the same targeting foundation, but gameplay Guard spawning is still future work. Mayors keep their existing behavior. A target-change guard prevents controlled ALLY/NEUTRAL retaliation targets. Ordinary vanilla mobs are not automatically assigned factions or modified by this targeting system.
- Existing debug commands retain their behavior:

```text
/kingdom encounter spawn pillager_patrol
/kingdom encounter spawn undead_horde
/kingdom encounter spawn pillager_patrol reward_test
/kingdom encounter spawn undead_horde reward_test
/kingdom encounter info <party-uuid>
```

All encounter commands require operator permission. Plain debug parties give no reputation; explicit `reward_test` and natural parties are eligible.

## Spawning controls

`encounters.natural.enabled=true` initially. Defaults: check interval 200 ticks, dimension cooldown 2400 ticks, at most three sites for one eligible player per attempt, 128-block origin separation, at most two active groups within 192 blocks of player/candidate, and 256 tracked records worldwide before natural additions stop. Successful and failed attempts both persist their cooldown. Round-robin selection rotates survival/adventure players; creative/spectator players do not trigger spawning. All online players still exclude nearby sites.

Candidate centers lie 54–80 blocks from the selected player (configured 48-block minimum plus six-block member margin). Both min/max and other limits are configurable. Inconsistent min/max settings fail closed. Sites must already be loaded **and entity-ticking**, lie outside allied territories plus a 22-block margin, and pass the existing dry ground/collision/border/build-height checks. No new terrain mutations or chunk tickets are used.

Natural spawning honors `doMobSpawning` and Peaceful. Daytime selects Pillager proposals; night favors Undead (three out of four proposals). Undead require nighttime and block light at most seven at every potential member position. There is no daytime fallback for a failed horde, or biome/road/hostile territory rule yet. Vanilla patrol and normal monster spawning remain intact.

## Contribution and regional reputation

`LivingDamageEvent.Post` records positive post-reduction damage from server players/player-owned projectiles against valid remaining members. This event precedes `LivingDeathEvent`, so the lethal hit participates. Spectators, unrelated entities and non-positive damage do not participate. Each hit is capped to a member's maximum health; lethal overkill remains an approximate damage score. Defaults require score >=4 (two hearts), cumulative through an engagement, with a hit during the last 6000 server ticks. A gap beyond 6000 ticks resets that player's old score on their next hit. Up to 64 participant UUIDs per party are persisted.

First full-party defeat resolves independently of the final killer. Every eligible participant receives the full snapshotted group reward (+2 normal, +4 Captain total by default) in **one** appropriate allied settlement: valid nearby saved association, otherwise indexed nearest allied center within `reputationRange=256`. No nearby ally means no reward. A watcher or a faction-only victory has no player contributions and grants no reputation. Earlier meaningful player contribution still counts when Undead, Pillagers or environmental damage finish the party. Recent disconnected contributors retain UUID credit; only online players receive the chat notification.

Quest storage claims one batch receipt for the entire party and preflights every reputation addition before mutation. Later duplicates/late recipients cannot append rewards. This changes the previous final-killer-only policy while preserving Iron Shortage quest completion and per-settlement reputation. Pets are not inferred as player damage.

## Persistence and cleanup

Settlement schema remains 1. Encounter schema 2 reads schema 1, adding lifecycle timestamps, contribution and dimension cooldowns. Quest schema 3 reads schemas 1 and 2, preserving quest state, terms, reputation, Mayor associations and old single-player receipts. Strict corruption/future-version guards remain. Vanilla entity NBT still owns health, equipment and exact position; the mod saves identities and small metadata only.

Active origin/member indices are derived after load. A defeat immediately removes its active origin. Every 1200 ticks, metadata maintenance retires defeated parties/receipts after `completedRetentionTicks=1200`; still-active groups retire without reward after `activeLifetimeTicks=72000`. Legacy unknown timestamps start their lifetime conservatively at first maintenance. Both settings apply to debug groups. Retiring an active group discards loaded survivors by recorded UUID only and rejects its orphaned entity joins later. Ordinary unloading does not defeat a party. Reputation remains after obsolete receipts disappear.

There is no atomic transaction across vanilla entity/chunk and the two independent encounter/reputation SavedData files. A crash/partial file restore can strand or revive divergent state, including around cleanup; exactly-once guarantees cover normal server events, saved reloads and reconnects, not arbitrary crash recovery across files. This pre-existing limitation is retained rather than introducing a separate journal/database.

## Future regional quests

`EncounterQueries.activeNear(level, position, radius)` and `find(level, encounterId)` expose immutable UUID, faction, type, origin region, threat and optional hostile settlement source. Queries use indexed metadata and do not fetch entity chunks. A current allied reward association is not incorrectly reported as a hostile source. `NaturalEncounterSpawner.attemptAt` is a cooldown/safety-protected candidate API; `EncounterSpawner.spawnAt` remains the shared controlled entity service. Spawning has no quest dependency. Future quests must handle retired/missing references.

## Verification

Final coordinated validation command:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

Final result: **BUILD SUCCESSFUL**, exit 0, in 1 minute 6 seconds. **89 JUnit tests** across 13 suites: zero failures, errors or skips (XML under `build/test-results/test`; the final coordinated run reused those unchanged passing results). **All 27 required GameTests passed**, 25.52 seconds. The server saved all dimensions and shut down cleanly; log: `runs/gametest/logs/latest.log`.

`git diff --check` passed. The native settlement template check passed unchanged (18,511 bytes, SHA-256 `57162a361a53eacebd6fbcf95bebd43db7bc1724414c41ce9a4630e8f51dcb3f`). Production artifact: `build/libs/livingkingdoms-0.5.0.jar`, 171,302 bytes. JAR inspection confirms the dynamic encounter and existing quest/template content, with no GameTest classes/test mod resources. Resource JSON parses and English/Argentinian Spanish localization keys match. No client-only imports or new runtime dependencies were added.

The original 59 unit tests and 17 GameTests are retained. Future-version assertions advance with the explicit migrations, and previous final-killer-only assertions now verify participant credit. New unit coverage includes relationship symmetry/hostility, natural policy bounds/gates, indexed territory exclusion with imported/extreme radii, contribution caps/expiry/reload, active origin index/lifecycle cleanup, legacy migration, multiplayer batch receipts, overflow atomicity and receipt pruning.

Ten new GameTests exercise native Zombie melee and Pillager crossbow damage, faction targets, friendly-fire retaliation prevention, ordinary vanilla mobs, loaded identity AI reinstatement, dead/orphan entity joins, Guard/Mayor routing, natural active-footprint/cooldown/gates/limits, disk reload, natural regional rewards, faction-only victories and real multiplayer damage participation when another faction finishes. The periodic orchestration test verifies player selection, encounter creation, persisted cooldown and repeated event suppression without spawning commands or `attemptAt`. Test-only terrain, ticking tickets and fake players are isolated from the production JAR.

Initial AI fixtures called `Mob.tick()` directly in remote chunks, freezing the counter maintained by `ServerLevel.tickNonPassenger`. The melee fixture now uses Minecraft's normal tick wrapper. The crossbow fixture uses temporary active-region tickets and observes normal server AI/projectile ticks; previously its remote section could disappear before staggered target acquisition. Staged diagnostics distinguish target and attack failures. These fixture corrections do not change production targeting to accommodate tests.

The final run emitted a 6.573-second overload warning while preparing remote test terrain, chunk tickets and saved fixtures. This functional suite is not a production performance benchmark; no sustained tick-time measurement is claimed.

No graphical client attempt was repeated: the known `atio6axx.dll` early-window crash remains outside mod architecture. Visual travel/combat, a client Save and Quit/reopen and longer multiplayer playtesting are unverified on this machine.

## Performance review

Specialized read-only persistence/concurrency and architecture/performance reviews found no blocking issue. Relevant boundaries:

| Operation | Cost and frequency |
| --- | --- |
| Natural event guard | Constant checks per level tick; Overworld only |
| Player selection | Online-player list once per configured check; proposals constrained by persisted cooldown |
| Spawn proposal | At most three local sites; bounded footprint/light checks; metadata bucket queries |
| AI target acquisition | 16-block native section query every 40 active mob ticks, staggered by UUID |
| Damage/death/conversion | Member UUID map; at most 64 participant UUIDs; settlement lookup only at resolution |
| Maintenance | Once per minute over tracked metadata; direct loaded UUID lookup only for retired rosters |
| Reload | Rebuild derived indices from validated persisted records; orphan joins refuse retired identities |

No global entity list polling, per-tick settlement search, forced gameplay chunk loading or physical battle simulation in unloaded regions. Large imported territories (>1024 radius) use a per-dimension exceptional metadata list so exclusion stays correct without expanding an unbounded bucket grid. Default natural cooldown permits at most 30 proposals per server hour; failed sites often lower the actual rate. Tests validate behavior, not a long-running server tick-time benchmark.

## Limits and next milestone

Natural rules are Overworld-only and use approximate **origin** metadata for group limits; a moving group or portal travel does not move its bucket. Lifetime expiry abandons even a surviving group. No Bandit parties, naturally spawned Guards, settlements/camps, diplomacy, roads, biome placement, regional quest, new models, large wars or advanced loot are added. Players can cause native friendly damage, but controlled allies refuse retaliation targeting. Operators can intentionally create reward-test parties; that explicit administrative mode is not restricted as normal gameplay.

Recommended next milestone: one regional Quest Board combat mission (“Raiders on the Road”), selecting a nearby active encounter reference and handling victory, retirement and missing targets through the existing party/contribution boundary. Do not expand natural spawning or world simulation at the same time.
