# Kingdoms at War 0.20.x

Branches `feature/v0.20-war-kingdoms` (0.20.0) and `feature/v0.20.1-war-polish` (0.20.1), built on `feature/v0.19-kingdoms`. Minecraft 1.21.1, NeoForge 21.1.252, Java 21; no new dependencies. This milestone turns part of the 0.19 NPC kingdoms into enemies that send warbands against player kingdoms and defend their own land, adds loot to every defeated hostile party, and gives players a renown rank with one-time rewards.

## Architecture

### Kingdom stance and war strength

Every NPC kingdom gets a seeded stance the first time the war system looks at it: `WarPolicy.stance(seed, kingdomId, hostileChance)`. The result is saved in the new war store, so changing `hostileChance` later never flips an existing kingdom. Hostile kingdoms keep a `WarFront`: strength 1–10, raid clock, raid counters, current garrison party and whether its war standards were placed.

Strength is the feedback loop: a raid that is ignored, times out or withdraws raises it by one; a raid broken by players or guards lowers it by one, and so does a defeated garrison. Strength sets warband size (`warbandSize` plus one soldier every two levels, max 8), raid frequency (down to half of `raidCooldownTicks` at strength 10) and soldier quality: from 0.20.1 a kingdom at strength `s` fields veterans `(s-1)/2` levels above the regional level (max +4, never above the progression maximum). A hostile kingdom met for the first time starts at `initialStrength` (default 3) instead of 1.

**Capitulation (0.20.1).** A hostile kingdom already at strength 1 whose garrison is defeated by eligible players capitulates: it becomes independent, its garrison and campaigns are cleared, every eligible participant earns 150 bonus renown and the server announces it. Its Mayor, K and Quest Board then show the normal visitor text. An operator can still turn it hostile again with `/kingdom war stance hostile`.

Talking to the Mayor, the K shortcut and the Quest Board of a hostile kingdom show "X is a hostile kingdom ruled by Y…" instead of the visitor notice. Guards of a hostile kingdom resolve to the new `ENEMY_KINGDOM` faction, so they side with its soldiers rather than with player kingdoms.

### Warbands

`WARBAND` is a new party type for the new `ENEMY_KINGDOM` faction: a Vindicator captain wearing a black banner (no drop, no ominous banner, no raid membership), followed by alternating Pillager crossbowmen and Vindicator axemen. Soldiers are always at least leather-armored; otherwise they use the shared regional level, stat and equipment progression. `ENEMY_KINGDOM` is hostile to allied kingdoms and Undead and neutral to Pillagers and Bandits.

`WarService.onServerTick` runs every `checkIntervalTicks` and looks at **one** online survival/adventure Overworld player (round robin):

- Standing in a player kingdom (any non-NPC allied settlement) with no active threat and no raid already marching on it, the nearest hostile kingdom within `raidRange` whose raid clock has expired sends a warband. `WarbandSpawner.raid` tries 24 origins inside the normal approach ring, starting on the side of the attacking kingdom, only on loaded chunks, and links the party with `DefenseService.beginApproach`: the usual warning, THREATENED status, guards and Local Defense quest apply. Players inside the kingdom get a "war drums" message naming the attacker and its ruler.
- Standing in a hostile kingdom, the first visit places its war standards and, if its cooldown allows and it has none alive, the kingdom fields its garrison: a warband around the player (16–32 blocks, same safe-site rules as the debug spawner).

Raids in flight are tracked as campaigns (`party → kingdom, target`). Each check also reconciles them: a campaign whose party is gone without a recorded defeat counts as unanswered; a garrison that is gone is cleared without weakening the kingdom.

Natural roaming parties no longer start an approach against a hostile kingdom, and defeating parties near one never awards reputation with it.

### Bounty, renown and war contracts

`EncounterEvents.onDeath` calls `WarService.onPartyDefeated` once, when the last member falls (isolated in its own try/catch so quest and reputation receipts are unaffected). It settles the war front and, for reward-eligible parties with at least one eligible participant:

- **Bounty** (`Bounty.forParty`): emeralds scaled by members and average level (Undead carry half, warbands double), iron for warbands (strength + members/2, max 16) and experience orbs (Undead and warbands ×1.5), all capped. Dropped where the last member fell.
- **Renown** (`RenownLedger.pointsForDefeat`): members × ceil(average level), doubled for warbands, max 500 per party. Every eligible participant earns the full amount. Ranks: Recruit 0, Squire 40, Sergeant 120, Knight 300, Captain 700, Warlord 1500. Each rank pays a fixed vanilla reward once (sword, shield and armor pieces, emeralds, golden apples, a totem at Warlord). The receipt is written before the items are given; offline players receive pending ranks at login or with `/kingdom renown`.

Guard Captains post `warband_request` contracts ("Banners on the Horizon") against real nearby warbands through the existing dynamic quest generator, with higher selection weight and 1.5× the usual combat pay.

### Commands

- `/kingdom renown` (everyone): rank, renown and what is left for the next rank; also delivers pending rank rewards.
- `/kingdom war status` (operator level 2): hostile kingdoms with ruler, strength, raids and position, plus raids in progress.
- `/kingdom war raid` (operator): inside a player kingdom, makes the nearest hostile kingdom in range send a warband now (ignores its raid clock).
- `/kingdom war stance hostile|independent` (operator): changes the stance of the NPC kingdom you stand in.

### Runtime cost

Per check: one indexed `alliedAt` lookup, one pass over the NPC kingdom registry (≤ `maxNpcKingdoms`, default 48) and one pass over campaigns (≤ 256) and fronts. Spawning reuses the encounter planner (≤ 24 loaded proposals, ≤ 8 entities). War standards are at most 12 block writes once per kingdom. Combat resolution adds one hash lookup when a guard's faction is resolved. Nothing loads or force-loads chunks.

## Configuration keys and defaults

Server config `livingkingdoms-server.toml`:

| Section | Key | Default | Notes |
|---|---|---|---|
| `[war]` | `enabled` | `true` | Hostile kingdoms, warbands and garrisons |
| `[war]` | `hostileChance` | `0.4` | Seeded share of hostile NPC kingdoms; decided once per kingdom |
| `[war]` | `checkIntervalTicks` | `600` | One player handled per check (100–24000) |
| `[war]` | `raidRange` | `1536` | Max kingdom-to-kingdom distance for raids (256–8192) |
| `[war]` | `raidCooldownTicks` | `24000` | Per hostile kingdom at strength 1; half at strength 10 |
| `[war]` | `firstRaidDelayTicks` | `12000` | Grace period after a hostile kingdom is first seen |
| `[war]` | `initialStrength` | `3` | Strength of a hostile kingdom the first time it is seen (1–10) |
| `[war]` | `warbandSize` | `4` | At strength 1 (2–8) |
| `[war]` | `warbandThreat` | `3` | Threat rating metadata |
| `[war]` | `warbandReputation` | `6` | Reputation with the defended kingdom |
| `[war]` | `garrisonEnabled` | `true` | Garrison when a player enters a hostile kingdom |
| `[war]` | `garrisonCooldownTicks` | `6000` | Before a new garrison |
| `[war]` | `warStandards` | `true` | Black standards at hostile kingdom edges |
| `[rewards]` | `bountyEnabled` | `true` | Loot from defeated parties |
| `[rewards]` | `emeraldBase` / `emeraldPerLevel` / `emeraldCap` | `0.5` / `0.15` / `24` | Per member; capped per bounty |
| `[rewards]` | `experiencePerLevel` / `experienceCap` | `3.0` / `400` | Per member and level |
| `[rewards]` | `renownEnabled` | `true` | Renown and rank rewards |

Existing TOML files keep their values; new keys are added with these defaults.

## Compatibility and migration

- New store `<world>/data/livingkingdoms_war.dat` (schema 1): war fronts (≤ 4096), campaigns (≤ 256) and renown ledgers (≤ 65536). Malformed or future-schema files are rejected without being overwritten. No existing schema changed.
- Party type `warband`, faction `enemy_kingdom` and quest template `warband_request` are stored by string id and are additive. Older mod versions cannot load a world that contains a warband party, warband contract or enemy-kingdom record.
- NPC kingdoms created by 0.19 get their stance the first time a player enters them or a raid is considered.

## Limitations

- Hostile kingdom guards fight warbands' enemies but do not attack players on sight; the garrison is the kingdom's answer to trespassers.
- Hostile kingdoms use the same plains templates as independent ones; only the war standards distinguish them. There is no siege, conquest or occupation.
- Renown is per world. The HUD shows the rank and points only when the panel is expanded; the full breakdown is `/kingdom renown`.
- Capitulation is a peace, not a conquest: the kingdom keeps its buildings and can be made hostile again only by an operator.
- Warbands spawn only when a player is in the target kingdom, so offline kingdoms are never raided.
- Balance values were not tuned with playtest data.

### HUD (0.20.1)

The kingdom HUD replaces the friendly rows of a hostile NPC kingdom with a warning row ("Hostile kingdom", ruler) and its war strength (1–10). When renown is enabled the expanded panel adds the player's rank and points. The server sends only a rank id and a number; the client validates the id and shows `?` for anything unexpected.

## Automated validation

Recorded with the `lk-validate` sequence on Linux (`./gradlew test`, `./gradlew -PgameTestDirectory=runs/validate-<timestamp> runGameTestServer`, `./gradlew build`, `git diff --check`), 2026-10-22: **506 unit tests passed** (0 failures/errors/skips; 490 before this milestone), **184/184 required GameTests passed** in a fresh world, Gradle build passed, `git diff --check` passed.

New coverage: seeded stance distribution, warband size and raid cooldown scaling, approach angle ordering, war front strength/garrison transitions, bounty scaling and caps, renown ranks and one-time receipts, warband contract weight and pay, enemy-kingdom faction relations and minimum armor; war store save/reopen, garrison index, malformed and future-schema files left unchanged; GameTests for the warband roster (captain standard, composition, raid exclusion, armor, faction, guard hostility), war-front settlement with bounty and renown on defeat (and no double weakening), no rewards for debug fixtures, and war standards that skip unsuitable ground.

0.20.1 polish, same sequence, 2026-10-22: **509 unit tests passed** (0 failures/errors/skips), **186/186 required GameTests passed** in a fresh world, build and `git diff --check` passed. New coverage: veteran levels by strength, capitulation rules, the hostile HUD rows and rank display, a GameTest for veteran levels on spawned warbands and one for garrison defeat forcing capitulation.

Graphical checks are not covered by these tests.

## Manual QA checklist (gaming PC)

Use matching 0.20 client and server JARs and a **copy** of a 0.19 world.

1. Load the world copy; settlements, NPC kingdoms, quests and parties are intact. `/kingdom war status` lists the hostile ones.
2. Stand in a hostile NPC kingdom: its Mayor, K and Quest Board show the hostile message; black standards appear near the diagonals of its edge; a warband garrison spawns around you once.
3. Defeat the garrison: emeralds, iron and experience drop; renown is announced on the action bar; `/kingdom war status` shows the strength drop.
4. In your kingdom, run `/kingdom war raid`: war drums message, warning, THREATENED status and a Local Defense quest; the warband comes from the attacker's side.
5. The captain wears a black banner that never drops; soldiers have armor; guards fight them; nearby Pillagers do not.
6. Defeat the warband: bounty drops, renown increases, the Local Defense quest can be claimed, the attacker's strength drops.
7. Let a raid time out: the attacker's strength rises and its next warband is larger.
8. Reach 40 renown: Squire message, sound and reward in the inventory; Save & Quit, reopen: the reward is not given again.
9. Earn renown while another player is offline; they receive pending rank rewards on login.
10. A Guard Captain posts "Banners on the Horizon" while a warband is near; completing it pays more than a bandit contract.
11. English and Spanish texts, GUI scales 1–4.
12. Two players: both see the same warbands and both earn renown for the same defeat.
13. Set `[war] enabled = false`, restart: no raids, garrisons or standards; bounty and renown still work. Set `[rewards] bountyEnabled = false` and `renownEnabled = false`: no loot or ranks.
14. Watch the server tick time during a raid spawn and a garrison spawn.
15. (0.20.1) Compare a strength 1 and a strength 8 warband with `/kingdom war status`: the stronger one wears better gear and shows higher levels in the HUD.
16. (0.20.1) Beat the garrison of a strength 1 hostile kingdom: capitulation message, +150 renown, the kingdom turns friendly and stops raiding.
17. (0.20.1) Open the HUD inside a hostile kingdom: warning rows and war strength; expand it to see your rank and renown.
