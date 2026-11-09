# Living Kingdoms — Game Design Spec (living document)

This is the reference for *what* Living Kingdoms is trying to be and *why*. Implementation detail lives in the
per-version docs; process rules and past mistakes live in [LESSONS_LEARNED.md](LESSONS_LEARNED.md). Update this
file when a decision changes, not when code changes.

## 1. Vision

A Minecraft settlement RPG where **helping your town is the most rewarding way to progress**. Inspiration:
*Emberville* (Cygnus Cross, early access Oct 2026): every rescued townsperson rebuilds part of the town, and each
rebuilt piece unlocks crafting, services and quests that make the next outing stronger. The town is the reason to
go out, not a side activity.

Translated to Living Kingdoms:

| Emberville idea | Living Kingdoms equivalent |
|---|---|
| Rescue a survivor → rebuild a building | Accept immigrants / complete construction → unlock professions, buildings, quests |
| Town progress opens services | Buildings unlock workers, storage, market, defense, trials |
| Descents get stronger thanks to the town | Quests and trials pay **Royal Whetstones**, tomes and trophy weapons that beat what mining alone gives |
| Townsfolk with stories and requests | Named citizens with roles, Mayor dialogue, contextual requests |
| Several progression tracks in parallel | Player XP, reputation, renown rank, weapon refinement, kingdom development |

## 2. Design pillars (ranked; use them to break ties)

1. **The village pays better than the mine.** Every hour spent on quests must out-earn an hour of strip mining in
   the long run, through rewards that cannot be crafted (whetstones, tomes, trophies, citizens' work).
2. **Always a clear next step.** The player must never wonder "what now?". Main quests show a checklist, the HUD
   shows the tracked objective, locked content says exactly what unlocks it.
3. **Every claim feels good.** Claiming is a moment: sound, particles, an animated banner listing what was earned.
4. **Readable kingdom.** The Town Hall/Mayor view shows every citizen, what they do and lets you call them.
5. **Server-authoritative and cheap.** No per-tick world scans, bounded maps, nothing a client can forge.
6. **Vanilla-compatible.** Rewards are normal items with normal components; other mods see ordinary gear.

## 3. Core loop

```
 Talk to Mayor ─► accept main/regional quest ─► gather / fight / build ─► claim at the board
       ▲                                                                     │
       │        ┌──────── Royal Whetstones, tomes, XP, emeralds ◄────────────┘
       │        ▼
   new citizens, buildings, professions ─► stronger kingdom ─► Trials (choose difficulty) ─► trophies
```

Short loop (5–10 min): one regional request. Medium loop (30–60 min): one main step + one building.
Long loop (hours): refine a weapon to +5, win the Warlord trial, develop the Town Hall.

## 4. Systems

### 4.1 Founding and first house (0.21 change)
- Wilderness Charter → camp → **first House first**, then Town Hall (`construction.foundingHouseFirst = true`).
  Rationale: the first goal was a 64-log/32-stone/4-iron Town Hall before any home, which read as a strange first
  mission. A house is cheaper, immediately useful (homes the founders) and teaches Supply.
- Projects already reserved keep their order; old worlds are unaffected.
- *Open recommendation:* starting projects could accept cobblestone. Current tests deliberately require smooth
  stone, so this stays a design decision for the owner, not a silent change.

### 4.2 Main chain with checklists
- "Space for our future" (`main_development`) now shows a derived checklist: plan → supply → Builder (when required)
  → wait → return. Steps are recomputed from construction receipts; nothing new is persisted.
- Rule: **every main quest that needs more than one action must expose its steps** (see backlog).

### 4.3 Rewards and claim ceremony
- Persisted quest receipt (emeralds/reputation) is unchanged. On top, `QuestBonus` derives XP, whetstones and tomes
  from template + difficulty. Because it is derived and the claim is already one-time, there is no new save data.
- Claim → level-up/chime sound, particles, animated banner (queue max 4). Items that do not fit drop at the feet.
- Main chain: 15 → 80 XP, whetstones from *Tools for a growing kingdom* onward, a tome on *A safer kingdom*.
- Regional: XP 10–60 by difficulty; whetstone on easy+/combat, 2 on hard+, a tome on very hard.

### 4.4 Weapon refinement (Royal Whetstone)
- Not craftable — earned only from quests/trials (pillar 1).
- Whetstone in one hand, sword/axe/trident/mace in the other, use: +1 refinement, max +5, +0.4 attack damage per
  level (diamond sword 7 → 9). Costs 1, 2, 3, 4, 5 XP levels (15 total) and one whetstone per step.
- Stored as custom data + an attribute modifier + a lore line on the stack. No SavedData.

### 4.5 Trials (boss and horde challenges)
- Opened from Quest Board → **Trials**. Choose a tier; the server re-validates everything.
- Tiers: Skirmish (2 waves, unlock after *Tools*), Siege (3 waves + Siege Captain, after *Protect our roads*),
  Warlord (4 waves + The Warlord, after *A safer kingdom*).
- Rules: start inside your kingdom; stay within 48 blocks; time limit; dying/leaving fails; not while the
  settlement is THREATENED; cooldown (full on victory, ¼ on failure). Enemy equipment never drops.
- Rewards: emeralds, whetstones, tomes; Siege trophy *Siegebreaker Axe* (diamond, Sharpness II, +3); Warlord trophy
  *Warlord's Blade* (diamond, Sharpness III, Looting V, Unbreaking III, +5 → 9 base damage).
- Runs are transient (max 8 concurrent). After a restart, orphaned members are refused on load.

### 4.6 Town roster and rally
- Mayor → Citizens shows "Residents (N)", a roster summary by profession, and each citizen's detail.
- **Rally here** calls every resident (except Guards and the Mayor) to your position for 60 s; **Call** brings only
  the selected one; **Release** ends it. Citizens answer in their existing work ticks. Threats override rallies.

### 4.7 UI motion
- Every Living Kingdoms screen opens with a 200 ms ease-out slide/scale + fade; switching views inside the shared
  village shell replays it. Buttons get an eased gold underline on hover/focus.

### 4.8 Kingdom rank (0.22)
- Shared per allied settlement. XP from quest claims (= bonus XP, min 10), contracts (30 daily / 150 weekly / 40
  bonus), completed constructions (60) and trial wins (100 × tier). Thresholds 0, 100, 300, 600, 1000, … cap 20.
- Every level-up pays a chest to the player who earned it (rare; epic every 5th and from 10; legendary at 20) and
  announces new perks: weekly contracts (2), fourth daily (4), +10%/+20% contract emeralds (6/8).
- Shown in the kingdom HUD and the contract screen as `rank · progress/span`.
- Stored in `livingkingdoms_rpg.dat` (schema 1, new in 0.22).

### 4.9 Contracts and streak (0.22)
- Quest Board → *Contracts*. 3 dailies (4 from rank 4) drawn deterministically per player and calendar day from
  slay / deliver / regional request / construction supply; a weekly (from rank 2) of slay, requests or trials.
- Tracked kinds count from events; deliveries are checked and consumed at claim time. Claims only inside your
  kingdom. Receipt first, inventory second.
- Claiming all dailies unlocks the daily chest and extends the **streak**: +10% daily emeralds per streak day
  (cap +100%). A missed day lowers the streak by one per day, never straight to zero.
- Days follow the server's local calendar (`RpgClock`), so dailies reset at midnight.

### 4.10 Loot chests (0.22)
- Four items: Kingdom Chest common/rare/epic/legendary. Use to open; contents roll at open time and the banner and
  particles take the rarity color.
- Rarity roll: base 70/24/5/1, shifted by luck (kingdom rank − 1, streak × 2 for the daily chest), with a minimum
  per source (weekly ≥ rare, Siege ≥ rare, Warlord ≥ epic).
- Sources: daily bonus, weekly, rank-ups, trial wins, and a 12% chance on any regional request claim.

### 4.11 Living construction (0.23, from the first playtest)
- Every project — Builder or timed (first House, Town Hall) — rises visibly: the four stages plus gradual
  block-by-block placement in between (bottom-up, up to 6 blocks per second, with place sounds and particles).
- Natural noise on a site (air, grass, snow, small plants) is overwritten. Real obstacles are reported with block
  and position, highlighted with red particles and removed by Builders (1 per second, 3 with a pickaxe) or by the
  timed crew; containers and unbreakable blocks are never removed and ask the player instead.
- Items, animals and residents are moved out of the footprint before each stage; players and monsters are named
  in the construction screen; a Builder holding a sword strikes a monster in the way. Finishing works from the
  normal work range (no exact standing spot).
- Up to two extra Builders help the active project, each adding its own work; Town Hall slots default to 4.
- Sneak-use a sword/axe/pickaxe on a Guard or Builder to hand it over. Arming your first Guard with a sword is a
  one-time kingdom task (advancement + whetstone + XP).
- Establishing a founding camp assigns one resident to the Town Hall's single guard post.

### 4.12 Presentation and loot (0.23)
- Mayor: gold crown with gems on the humanoid model; face/outfit texture pending Argon 4 (prompt in
  `argon4-prompts-v021.md`). Citizen names render like vanilla (faint see-through + normal pass).
- Trials: wave members march in from one side a few at a time (raid style); the boss bar counts pending + alive.
- Royal Whetstones also refine armor: +0.5 armor and +0.25 toughness per level.
- Chests add 1–4 surprise rolls from a wide weighted pool; epic/legendary chests can hold refined armor.

### 4.13 Friendship with citizens (0.24)
- Right-click a resident (sneak-right-click a Merchant; sneak + tool still equips) to talk. Once per calendar day
  talking gives +3 bond; each resident has one profession-themed **wish** per day (deterministic per citizen and
  day). Talking while holding it delivers it: +15 bond, 3 emeralds, 20 XP, 15 kingdom XP, 15% common chest.
- Bond levels: Stranger 0, Friendly 25, Trusted 50, Loyal 75, Devoted 100. Loyal residents give a daily gift
  and unlock a perk (Builders +20% work, Guards +6 max health); Devoted pays a one-time epic chest.
- The roster shows bond hearts, today's wish and whether it was fulfilled.

### 4.14 Expeditions (0.24)
- Mayor → Citizens → *Expedition* on an unassigned resident: Forage (5 min), Mine (10 min), Explore (15 min).
  Up to 3 away per kingdom. The resident is hidden in place (invisible, frozen, invulnerable) so identity and
  home are untouched; a restart without the expedition record makes them visible again.
- On return the sender gets the loot with a banner (or on next login), +5 bond and 20 kingdom XP. Explore has a
  25% chance to rescue a survivor who arrives as an immigration candidate (rare chest if there is no housing).
- `livingkingdoms_rpg.dat` schema 2 stores bonds, expeditions and undelivered loot; schema 1 files migrate.

## 5. Roadmap (next steps, in priority order)

Done in 0.22: kingdom rank with HUD bar, daily/weekly contracts with streak, rarity loot chests.
Done in 0.24: citizen bonds with daily wishes and perks, expeditions with survivor rescue.


| # | Feature | Why | Acceptance |
|---|---|---|---|
| 3 | Checklists for every multi-step main quest (Watchtower, Patrol) | Pillar 2 | Each step updates from real state; GameTest per quest |
| 4 | Timed world events (blood moon horde, merchant caravan, festival) with HUD countdown | Appointment play | One active event per kingdom, cleaned on restart |
| 9 | Codex / collections (bosses, trophies, enemies, citizens) with set rewards | Completionism | One-time set receipts |
| 10 | Visible town upgrades per rank (lanterns, banners, paths) | Progress you can see | Placement reuses construction safety rules |
| 11 | Player talent tree from rank/renown points | Build choice | Respec rules, bounded passives |
| 5 | Blacksmith service: spend emeralds + whetstone to reroll/upgrade one enchant | Sink for emeralds | Server-validated, no item duplication |
| 6 | Trial modifiers (night, no armor, double boss) for bonus loot | Replayability | Shown in picker, tested reward multipliers |
| 7 | Rally destinations (plaza, gate, a clicked block) and "defend here" for guards | Requested control | Guards follow without breaking patrol AI |
| 8 | Wave composition from hostile NPC kingdoms (warband skins in trials) | Ties trials to the world | Uses existing warband assets |

Visual/3D assets for new mobs or items go through Argon 4 (owner's pipeline); Claude produces the prompt.

## 6. Balance knobs

| Knob | Where | Default |
|---|---|---|
| Founding order | `livingkingdoms-server.toml` `construction.foundingHouseFirst` | `true` |
| Refinement cap / damage per level / XP cost | `rpg/domain/RefinementRules` | 5 / 0.4 / level+1 |
| Quest bonus tables | `rpg/domain/QuestBonus` | see 4.3 |
| Trial tiers | `rpg/domain/ChallengeTier` | see 4.5 |
| Rally duration / reach | `rpg/domain/RallyPolicy` | 1200 ticks / 96 blocks |
| Kingdom rank curve, perks, level-up chests | `rpg/domain/KingdomRank` | see 4.8 |
| Contract pools, streak math | `rpg/domain/ContractBoard`, `rpg/ContractService` | see 4.9 |
| Chest rarity weights / contents | `rpg/domain/LootRarity`, `rpg/LootChests` | see 4.10 |
| Bond gains, levels, wishes | `rpg/domain/Bond`, `rpg/domain/PersonalRequest` | see 4.13 |
| Expedition durations, loot, rescue chance | `rpg/domain/Expedition`, `rpg/ExpeditionService` | see 4.14 |

Balance tables are code on purpose: changes show up as reviewable diffs and the domain tests assert the escalation
contract (later tiers always pay more, later waves are larger, refinement cost always increases).

## 7. Definition of done for any gameplay feature

1. Server-authoritative, bounded, no new per-tick global scans.
2. Persistence only when unavoidable; if added, follow `lk-persistence-change` and document migration.
3. English + Spanish (Argentina) strings for every player-facing key.
4. Unit test for pure rules; GameTest for anything touching entities, blocks, inventory or world state.
5. `test`, `runGameTestServer` (fresh directory), `build`, `git diff --check` all pass.
6. Manual QA checklist written for what automation cannot see (visuals, feel).
7. Spec updated if a design decision changed; a lessons entry added if something went wrong.
