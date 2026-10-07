# Hostile factions and roaming encounters — validation

Validated locally on **2026-10-06**, Java 21, Minecraft 1.21.1, NeoForge 21.1.252.
Mod version: **0.4.0**. Branch: `codex/hostile-factions-encounters`.

The starting branch contained the uncommitted Milestone 2 implementation over
Milestone 1 commit `e6ee55c`. Those files, features and tests were preserved and
carried into this branch. No physical Pillager Camp implementation was present;
no camp work was discarded. No commit or push was required to continue.

## Architecture and compatibility

- Shared `faction.Faction` contains `ALLIED_KINGDOM`, `PILLAGER`, `BANDIT`, `UNDEAD`.
  Settlements and encounters use the same Minecraft-independent identity. Legacy
  saved ID `allied` reads as `ALLIED_KINGDOM`; new writes use `allied_kingdom`.
  Existing tests keep a deprecated `ALLIED` source alias. Settlement schema is 1.
- `FactionRelation`/`FactionRelations` provide a pure `ALLY`/`NEUTRAL`/`HOSTILE`
  baseline. Same faction is allied, allied kingdoms versus the three hostile
  identities are hostile, and distinct hostile factions initially are neutral.
  This policy does not implement diplomacy or replace vanilla combat targeting.
- Immutable `HostileParty` records a unique UUID, faction/type, approximate origin,
  optional settlement UUID, ALIVE/DEFEATED state, roster/remaining member UUIDs,
  threat, reward and debug/eligibility flags. Entity health/equipment/AI/exact
  positions stay in Minecraft entity persistence.
- Guarded `EncounterSavedData` uses the Overworld file
  `data/livingkingdoms_encounters.dat`, schema 1. A derived member UUID index resolves
  deaths/conversions without a world entity scan. Unloaded members are not assumed
  dead. Vanilla conversion replaces the member identity without reducing the group.
- `NearestAlliedSettlementService` delegates to a per-world, per-dimension index of
  allied center positions in 256-block buckets. The query uses inclusive horizontal
  center distance, ignores Y/territory radius, validates range 0–4096, uses UUID
  tie-breaking and reads no chunks. The index rebuilds from persisted metadata.
- Quest/reputation schema 2 reads schema 1, preserving player quests, reputation and
  Mayor associations. A one-time party reward receipt is stored in that same file
  as its reputation effect. Unsupported/corrupt files retain the existing guarded
  no-overwrite behavior. Old mod versions cannot read all new IDs/schema 2.

All gameplay mutation runs on the server thread. There are no global tick loops,
new world-wide entity scans, forced chunk loading, external database or new runtime
dependencies. Existing quest/Mayor services now query faction eligibility through
the shared API. Future Bandit content or a Pillager hostile Settlement can use
these boundaries without changing the player/settlement quest relationship.

## Currently playable encounters

| Type | Configured default members | Threat | Potential regional reward |
| --- | --- | --- | --- |
| `PILLAGER_PATROL` | 4 equipped vanilla Pillagers, one optional Captain | 2 | +4 with Captain, +2 without |
| `UNDEAD_HORDE` | 6 alternating adult Zombies and Skeletons | 3 | +2 |

Sizes are configurable within 3–5 patrol members and 4–8 horde members. Threat is
metadata, configurable 1–100, separate from vanilla entity classes and damage.
Rewards are configurable 0–1,000,000, snapshotted at spawn; Captain reward is the
total for the party. Members use vanilla AI, equipment and drops. No custom models,
advanced AI, custom loot tables or massive groups were introduced.

Spawning plans every member on loaded, dry ground before any entity is added. It
checks support, fluids, local block/entity collision, height and border, changes no
terrain and removes ordinary partial additions if the spawn is refused. The normal
server spawning entrypoint can later accept candidates from road/night/biome rules;
natural generation is currently disabled.

## Commands and reputation policy

All encounter controls require operator level 2. Spawn requires a player context.
Info can be used by an operator/console with an encounter UUID.

```text
/kingdom encounter spawn pillager_patrol
/kingdom encounter spawn undead_horde
/kingdom encounter spawn pillager_patrol reward_test
/kingdom encounter spawn undead_horde reward_test
/kingdom encounter info <party-uuid>
```

Default command parties are marked debug and give **no reputation**. The explicit
`reward_test` suffix enables an operator-only reward test and is reported in the
command response. Normal players cannot create either type. Eligibility and reward
amount stay on the party, so a later config change cannot convert an old debug
party into a rewarding encounter.

The first credit policy is **final killer only**: the direct player or owner of a
player projectile killing the last recorded member receives credit. Earlier member
deaths do not award reputation. Environmental final deaths, ordinary unrelated
mob deaths, ineligible debug parties and groups without an appropriate nearby ally
do not award anything. Other participants do not yet receive credit.

At defeat, the associated allied settlement is preferred only if it remains in the
defeat dimension and within the configured center-distance range (default 256).
Otherwise, the nearest allied settlement at the final death position is selected.
Exactly one settlement receives the player's credit; there is no global reputation
grant. Death events are handled at LOWEST priority without canceled events, with
additional identity/roster and death-state checks. Duplicate final notifications
cannot defeat the party again, and the persisted receipt cannot reward another
player/settlement or replay the reward after ordinary reload/reconnect.

This policy lives in the event/reward boundary. A future contribution policy can
record damage participation and extend a claimed-party receipt to multiple credited
players; no synchronized loop over all hostile entities is needed.

## Automated results

Final command:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

- **BUILD SUCCESSFUL**, 40 seconds for the coordinated test/build/GameTest run.
- **59 JUnit tests passed**, zero failures/errors/skips. The existing 26 remain;
  33 new tests cover factions/nearest lookup (10), party domain/persistence (18)
  and reputation receipts/migration (5).
- **17 required GameTests passed**, 9.359 seconds. The existing 11 remain; six new
  encounter tests exercise real vanilla spawn/combat/conversion and disk storage.
- Template generation check passed; the original native settlement asset is
  unchanged and still matches the deterministic authoring script.
- `git diff --check` passed.
- Production JAR inspection confirmed shared faction/encounter classes, preserved
  quest/NPC classes and native structure, valid JSON, aligned English/Argentine
  Spanish keys, exclusion of GameTest classes and no optional-mod dependencies.

The new tests cover all four faction IDs and legacy allied saves; horizontal
nearest selection, hostile/dimension exclusions, inclusive range, ties and extreme
coordinates; UUID/roster uniqueness, immutable states, only one full defeat,
conversion replacement, disk reload and protected malformed/future files. Receipt
tests cover first credit winning across different players/settlements, adding to
existing quest reputation, migration and joint receipt/reputation disk reload.

Real server tests exercise both commands and permissions, equipped ordinary-AI
Pillagers, Captain count, Zombie/Skeleton faction tags, member NBT persistence,
per-member deaths, final-killer multiplayer credit, region selection, duplicate
notifications/receipts, unrelated/debug/environmental deaths, vanilla Zombie-to-
Drowned conversion, safe loaded-only spawning and absence of an allied region.
Test-only terrain/chunk preparation and AI isolation are excluded from the JAR.
The headless server saved all dimensions and shut down cleanly. These tests are
functional checks, not a large-world performance benchmark.

Reports: `build/test-results/test`, `build/reports/tests/test/index.html`,
`runs/gametest/logs/latest.log`. Artifact: `build/libs/livingkingdoms-0.4.0.jar`.

## Client and manual check

`runClient` was attempted once for 0.4.0. It reproduced the native AMD
`EXCEPTION_ACCESS_VIOLATION` in `atio6axx.dll+0x193ca0` while creating the early
GLFW/OpenGL window, before mod gameplay. Report: `run/hs_err_pid20124.log`.
No graphics workaround or architecture change was made. Visual/manual gameplay
verification on this machine remains pending despite successful headless tests.

On a working client:

1. Generate an allied settlement, record its UUID, and move to dry open ground
   within 256 blocks of the center. Keep the surrounding chunks loaded; use a
   difficulty other than Peaceful.
2. Spawn a default patrol. Record its reported party UUID, inspect it with `info`,
   defeat all members and confirm no reputation change.
3. Spawn a `pillager_patrol reward_test`. Kill all members. Verify only the final
   killer receives +4 in the associated village with default Captain settings.
   Repeating an interaction or reconnecting must not create another reward.
4. At night, spawn an `undead_horde reward_test`. Verify mixed Zombies/Skeletons
   and +2 regional reputation after all die to player combat.
5. Use another player for earlier kills and a different player for the final kill.
   Verify the documented final-killer policy and unchanged distant settlements.
6. Save/reopen normally and inspect the same party UUID and village reputation.
   Repeat away from allied settlements; no global reputation should appear.

## Important files

- `faction/Faction.java`, `FactionRelation.java`, `FactionRelations.java` and the
  migrated faction imports/founding/eligibility checks.
- `settlement/persistence/SettlementSavedData.java` and
  `settlement/NearestAlliedSettlementService.java`.
- `encounter/domain/*`, `encounter/persistence/EncounterSavedData.java`,
  `EncounterNbt.java`, `encounter/EncounterDefinition.java`, `EncounterMember.java`,
  `EncounterSpawner.java`, `EncounterEvents.java`.
- `quest/persistence/QuestSavedData.java`, `command/EncounterCommands.java`,
  `config/KingdomConfig.java`, common bootstrap and version metadata.
- Faction, nearest lookup, encounter and receipt unit tests;
  `gametest/EncounterGameTests.java`; preserved existing tests.
- English/Argentine Spanish resources, `README.md` and this report.

Java paths above are relative to `src/main/java/dev/livingkingdoms`; test paths
use their existing source sets. The source relocation removes the old settlement-
specific faction enum in favor of the shared faction package.

## Limits and next gameplay milestone

Bandit roaming content, camps, diplomacy, advanced combat and natural spawning are
not implemented. Undead retain vanilla sunlight burning; Captain/loot/targeting
remain vanilla. Threat numbers do not yet scale equipment, damage or AI.
Credit uses the final player killer only; pets/environmental kills do not infer
participation. Defeated party metadata and receipts are retained, with no pruning
policy yet. Manually discarded members or members lost without a death/conversion
event can leave a party ALIVE; unloading is deliberately not considered defeat.

Receipt and reputation are persisted together, but independent entity/encounter/
world files are not an atomic crash-proof transaction. Normal save/shutdown/reload
is tested; a process crash between files can lose or strand state. A failed reward
store/overflow is logged and not replayed automatically. No world scanning or
forced chunk recovery was introduced to hide these limits.

Next logical gameplay milestone: one Quest Board mission to defeat a regional
hostile party, reusing encounter UUIDs and the one-time reputation foundation.
That mission and larger future systems have not been started.

Event integration follows the official
[NeoForge 1.21.1 events documentation](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
and was checked against the exact local NeoForge/Minecraft sources.
