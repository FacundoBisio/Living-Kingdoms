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

## 5. Roadmap (next steps, in priority order)

| # | Feature | Why | Acceptance |
|---|---|---|---|
| 1 | Checklists for every multi-step main quest (Watchtower, Patrol) | Pillar 2 | Each step updates from real state; GameTest per quest |
| 2 | Kingdom/Player level track shown in HUD (XP bar for kingdom rank) | Long-term goal visibility | Rank-up banner, persisted once |
| 3 | Daily/weekly contracts with streak bonus | Habit loop | Bounded offers, no duplicate pay after reload |
| 4 | Citizen stories: 3–5 personal quests per named citizen, unlocking a perk | Emberville "townsfolk" pillar | Persisted per citizen; perk visible in roster |
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
