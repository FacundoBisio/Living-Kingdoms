# Diplomacy & Defense 0.19.0 — foundation

Branch `feature/v0.19-kingdoms`. Minecraft 1.21.1, NeoForge 21.1.252, Java 21; no new dependencies. This milestone adds visible citizen activity, automatic Builder supply, Bandit parties, guard combat improvements and (second batch) independent NPC kingdoms. Diplomacy, inter-kingdom trade and NPC kingdom autonomy are the next step and are not part of this release.

## Architecture

### Citizen activity line

Registered citizens show a third label line under name and `Profession · Level N`: for example "Building: Town Hall", "Tending crops", "Chopping wood", "Repairing: Barracks", "On the way", "Resting" or "Taking shelter". The server adds one field, `target`, to the existing `CitizenPresentation` snapshot: the building kind a Builder is constructing (`ConstructionService.projectForBuilder`, an O(1) index lookup) or repairing (first repair order). Other professions send an empty target. The client builds the line once per snapshot/language change in the cached `View`; rendering allocates nothing new. Snapshots are still sent only on change or every 200 ticks.

The citizen visual network channel moves to version **2**: client and server must use the same 0.19 JAR.

`NpcService.ensureMayor` no longer scans every loaded entity when recovering an unassociated Mayor. It searches only loaded Villagers inside the territory square, with the radius clamped to 128 blocks.

### Builders supply themselves from storage

An idle Builder with no assigned project funds the first `WAITING_FOR_RESOURCES` project of its kingdom from Kingdom Storage, then the existing assignment picks it up. The manual Construction → Storage button uses the same server code (`ConstructionService.supplyFromStock`) with the same compare-and-set receipt: validate the stock revision, replace the project, commit the stock. A stale or concurrent attempt cannot spend twice. Partial supply is allowed. Each material always keeps `autoSupplyKeepBack` units in storage so repairs, smithing and upgrades are not starved. Attempts are throttled to one per kingdom every 100 ticks and only happen while a loaded Builder is working (daytime, safe, housed).

### Bandit parties

`BANDIT_PARTY` is a new roaming party type for the existing `BANDIT` faction: Vindicators, with a Pillager every third member. They spawn as event mobs (no patrol banner, no captain) and cannot join vanilla raids. Bandits are hostile to players, allied kingdoms, Guards and Undead, and neutral to Pillagers. They use the same spawner, progression, defense detection, Local Defense quests and reputation as other parties. A dedicated regional request (`bandit_request`) targets them. Natural parties are bandits with probability `banditChance`, day or night.

### Guards

- Guards raise their off-hand shield when a hostile is within 4 blocks and their next attack is not ready, and lower it to strike. Damage reduction is vanilla shield blocking (including its raise delay); the Argon `guard_block` animation plays while the shield is up.
- Attack cooldown shortens with level: `max(attackCooldownMin, 25 − 2 × (level − 1))` ticks. Level 1 is unchanged (25).
- While their kingdom has an active threat (approaching, detected or active), Guards search `alertSearchMultiplier` times farther, never beyond 48 blocks or twice the defense radius.

## Configuration keys and defaults

Server config `livingkingdoms-server.toml`:

| Section | Key | Default | Notes |
|---|---|---|---|
| `[builders]` | `autoSupplyFromStorage` | `true` | Idle Builders fund waiting projects from storage |
| `[builders]` | `autoSupplyKeepBack` | `8` | Units of each material always left in storage (0–4096) |
| `[encounters]` | `banditPartySize` | `3` | 2–5 members |
| `[encounters]` | `banditThreat` | `2` | Threat rating used by difficulty/activity |
| `[encounters.natural]` | `banditChance` | `0.25` | Share of natural parties that are bandits |
| `[guards]` | `shieldBlocking` | `true` | Raise shields between attacks |
| `[guards]` | `attackCooldownMin` | `15` | Fastest cadence at high level (5–25 ticks) |
| `[guards]` | `alertSearchMultiplier` | `2.0` | Search radius multiplier during a threat (1.0–3.0) |

Client config `livingkingdoms-citizen-visuals.toml`: `citizenPresentation.activityDistance` = `8` (0 hides the line; also capped by `nameDistance`).

Existing TOML files keep their previous values; new keys are added with these defaults. No existing balance default changed.

## Compatibility and migration

- No persistent schema changed. Party types and quest templates are stored by string id, so `bandit_party` and `bandit_request` are additive. Older mod versions cannot load a world that contains a bandit party or bandit request.
- Citizen visual protocol version 2: mixed 0.18/0.19 client–server connections are refused on that channel.
- Builder auto-supply only moves materials that a player could already move with the Storage button.

## Limitations

- Founding projects without a Builder are still funded manually.
- Auto-supply competes with other consumers of the same stock; `autoSupplyKeepBack` is the only reserve.
- Bandits use vanilla illager models; there are no custom bandit models.
- Guard shields do not lose durability (vanilla behavior for non-player entities in 1.21.1).
- Balance values (detection ranges, approach distances, rewards, party sizes) were not retuned without playtest data; see the tuning checklist.

## Automated validation

Recorded after each batch with `lk-validate` (`.\gradlew.bat test`, `.\gradlew.bat -PgameTestDirectory=runs/validate-<timestamp> runGameTestServer`, `.\gradlew.bat build`, `git diff --check`).

- Batch 1 (activity line, auto-supply, Bandits, guards), 2026-10-20: **478 unit tests passed** (0 failures/errors/skips), **178/178 required GameTests passed** in the fresh world `runs/validate-20261020-145022`, Gradle build passed, `git diff --check` passed. New coverage: activity target codec/rejection, auto-supply keep-back and stale receipts, Builder auto-supply GameTest (off/on/partial/once), bandit composition/banner/raid flag/NBT reload, bandit persistence and Local Defense, guard shield raise/lower, threat search radius and cadence.

Graphical checks are not covered by these tests.

## Manual QA checklist (gaming PC)

Use matching 0.19 client and server JARs and a **copy** of a 0.18.1 world.

1. Load the 0.18.1 world copy. Citizens, names, professions, XP, stock, buildings and quests are intact.
2. Look at citizens of every profession at work, walking, resting and sheltering: the third line matches what they do. Builders show the building name; a Builder repairing shows "Repairing: …". Beyond 8 blocks the line disappears; set `activityDistance = 0` and confirm it is hidden.
3. Check English and Spanish, GUI scales 1–4, and that three lines do not overlap the head or the Mayor circlet.
4. Plan a building, put enough materials in Kingdom Storage, assign a Builder and wait during the day: the project starts without pressing Storage. At least 8 of each material remain in storage.
5. With less stock than needed, the project gets partial supply, then completes after you add more.
6. Set `autoSupplyFromStorage = false`, restart: projects wait until you press Storage, which still works.
7. `/kingdom encounter spawn bandit_party` near a kingdom: Vindicators plus a Pillager, no banner. Guards and you fight them; Pillagers nearby do not fight them; Undead do.
8. Trigger `/kingdom defense trigger bandit`: warning, THREATENED status, Local Defense quest at the Board, claim after victory.
9. Accept a bandit regional request if one appears on the Board; the HUD names bandits.
10. Watch a Guard fight: the shield goes up between strikes (and the block animation plays), and goes down to attack. Higher-level Guards strike faster.
11. During an approaching threat, Guards react from farther away than normal.
12. Save & Quit, reopen: bandit parties, quests and guard state persist; no duplicated rewards.
13. Two players: both see the same activity lines and bandit parties.

## Tuning checklist

Play several in-game days and note values that feel wrong, then adjust these keys (server config) and report them so defaults can be updated:

- Warning distance: `[defense] normalDetectionRange` (60), `watchtowerDetectionBonus` (40), `maximumDetectionRange` (160), `minimumApproachDistance` (70), `maximumApproachDistance` (140).
- Travel encounter frequency and size: `[encounters.natural]` interval, cooldown and caps; `[encounters]` party sizes and `banditChance`.
- Difficulty curve: `[progression] baseLevel`, `blocksPerDistanceBand`, `worldDaysPerLevel`.
- Rewards: `[quests.expansion.rewards] combatEmeralds` (4), `combatReputation` (3); `[encounters] normalReputation` (2), `captainReputation` (4).
