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

### 4.15 A town that grows more beautiful (0.30)
- Every kingdom rank from 2 to 10 unlocks a decoration tier: lanterns along paths (2), flower beds (3), banners
  (4), a plaza fountain (5), benches (6), braziers (7), hedges (8) and a golden hero statue (10). The rank-up
  splash lists what was unlocked, and the tier is placed right away with particles and a chime.
- Tiers are a bitmask in `livingkingdoms_rpg.dat`; each tier is applied once, only into empty, supported,
  loaded spots outside building footprints (+1) and off path columns. Missed tiers catch up when a member next
  talks to the Mayor. Operators: `/kingdom beautify`.

### 4.16 Timed kingdom events (0.30)
- One event per kingdom at a time, scheduled every 8–15 minutes only while a member is in the territory:
  **Harvest Festival** (contracts, quest XP and whetstones pay double, fireworks), **Merchant Caravan** (a
  trader with whetstones, chests, tomes and a totem) and **Treasure Hunt** (a loot chest somewhere in the
  territory, with a HUD compass). A one-minute warning and the remaining time show in the HUD.
- Events are transient: a restart removes the caravan and the unclaimed chest. Operators:
  `/kingdom event <festival|caravan|treasure>`.

### 4.17 Techniques taught by loyal villagers (0.30)
- The first time a worker becomes Loyal they teach their craft once per player: Master Smith (whetstones cost
  one level less), Hearty Meals (+2 hearts), Masonry (+1.5 reach), Guard Drill (+1 damage), Timber (+15% break
  speed), Prospector (+1.5 luck), Haggling (+15% contract emeralds), Shortcuts (+5% speed).
- Stored per player in `livingkingdoms_rpg.dat` schema 3; attribute modifiers are reapplied on login/respawn.
  The Contracts screen lists learned techniques.

### 4.18 Kingdom defense (0.30)
- **Walls:** Management → *Walls*. Three tiers paid from Kingdom Storage (palisade: wood; stone wall: stone +
  4 iron; fortress: stone bricks + 16 iron; costs scale with the ring and stay under the 256 base capacity).
  A ring three blocks inside the border rises four columns a second; paths get doors and every 12th column is a
  flat archer post. Only air, replaceable blocks, leaves and our own wall blocks are ever written.
- **Archers:** a Guard handed a bow or crossbow (sneak-use) climbs to a free post — watchtower tops first, then
  wall posts — and shoots monsters within 24 blocks every 1.5 s. Damage grows with the tower level or wall tier;
  level 3 shoots fire arrows. Archer arrows never hurt villagers or players and cannot be picked up.
- **Siege and repairs:** while a raid threatens the kingdom, invaders hugging the wall may knock pieces out;
  once safe, breaches are repaired from storage (one wood or stone each). Breaches are transient.
- **Alarm bell:** ringing any bell inside your kingdom sounds a two-minute alarm: civilians shelter, guards stay
  on watch at night and monsters glow. Operators: `/kingdom alarm`, `/kingdom wall`.
- **Spike traps:** craft 4 from 3 iron nuggets, 1 iron ingot and 3 planks; monsters crossing them take 3
  damage every half second and are slowed. Players, villagers and animals are unaffected.
- Trials still pit enemies against the player only; there is no wave defense against the base.

### 4.19 Emberville-style presentation (0.30)
- All kingdom screens use textured RPG frames: parchment body, carved frame, ribbon header and stateful
  buttons. The dialogue portrait is a framed card with a name plaque.
- Reward banners show item cards that flip in one by one with a rarity glow; rank-ups get a splash with
  rotating rays and the list of unlocks. The HUD shows events, construction sites, contracts ready to claim,
  rising walls, breaches and the alarm countdown.

### 4.20 Residents up close (0.31)
- Right-clicking a resident (sneak-click for Merchants) still counts as the daily talk, then opens the roster
  focused on them with a random line of small talk (job, weather, night, festival or danger aware, plus odd
  everyday thoughts). From there the player reads their job, home, bond and wish, hires a free resident or
  sends them on an expedition.
- The roster has an *Away* tab listing residents on expeditions with a live "back in m:ss" clock; the HUD shows
  how many are away and when the next one returns.
- Any resident whose job is inactive (retired by the game or removed) is free: they can take any job or travel.

### 4.21 Market stalls (0.33)
- The Market building was redrawn as an open market hall with a striped awning, stalls, a bell and lanterns. It
  keeps the same footprint and the barrel/lectern stations, so markets in existing worlds keep working.
- Every Merchant runs one stall. Plain click: the kingdom stock screen, now with **Stall** and **Change** buttons.
  The stall opens the vanilla trading screen with the classic villager trades of its specialty (13 vanilla
  specialties, two trades per tier, more tiers as the Merchant levels up) plus today's **rare kingdom goods**.
- Rare goods are hard to get and time-limited: they need emeralds plus a rare ingredient (diamonds, gold,
  netherite scrap), sell out after 1–3 trades and change every real-time day. Market level 2 and 3 unlock rarer
  goods and more slots; a festival adds one more. Offers and used counts are stored on the Merchant entity.

### 4.22 Kingdom stages and the kingdom map (0.33)
- Stages derive from rank: Hamlet (1), Village (3), Town (6), City (9). Reaching a stage is announced to the
  kingdom and upgrades **every house**: Village adds a lantern post by the door and flower beds; Town adds a
  smoking brick chimney and a cobbled skirt; City adds a banner by the door and flowering hedges. Applied once,
  only into free spots outside footprints and off paths. The HUD shows the stage next to the rank.
- **Kingdom map** (key M, remappable, or `/kingdom map`): terrain painted from the client's loaded chunks
  with vanilla map colours, buildings by type and level, construction sites with progress, residents with their
  job and what they are doing, walls, the player and a fuzzy treasure area. Hover for details.

### 4.23 Playtest round 3 (0.34)
- **Resident conversation screen:** right-click a resident to open their own screen: portrait, small talk, job
  level with an XP bar and what the next level improves, bond hearts, today's wish with a *Give* button (from
  any inventory slot), and buttons to assign a job, send on an expedition or open the roster.
- **Mayor:** never takes a house bed; once the Town Hall exists, walks into it and stays there.
- **Beds:** one bed per resident inside their own home.
- **Guards:** attack trial enemies, any monster inside the kingdom, and whatever a member hit in the last 30 s.
- **Defense alerts:** fighting counts without accepting; unaccepted offers disappear when the threat ends.
- **Builders** move around the site while working; completion shows a construction banner.
- **Tuning:** treasure farther from the centre, trial enemies spawn 24–32 blocks out, expeditions give 8 kingdom
  XP, fewer flowers, a rank-4 tier lights the outskirts, Warlord trials have a 25% legendary chest, building
  costs closer to the blocks used (new configs only), Esc leaves the construction preview.

### 4.24 Living buildings (0.35)
- **Building Ledger:** every kingdom building gets a ledger desk inside (farms: beside the door). Using it opens
  management focused on that building: level, workers, costs and its own order. Placed on completion, when a
  member talks to the Mayor (throttled) or with `/kingdom ledgers`. A broken desk is placed again.
- **Visual upgrades:** level 2 refines materials (cobblestone → stone bricks, oak → spruce, white glass); level
  3 goes stately (deepslate bricks, dark oak timber and roof, banners by the door). Only that building's
  footprint and only those exact materials change; orientation is kept; containers, beds and doors are untouched.
- **Tavern** (new building): ×2 immigration chance (×2.5, ×3 per level) and *Hire* a traveller for 8/6/4
  emeralds from its ledger. Hiring still needs a free bed.
- **Warehouse:** its ledger has *Deposit all* (every log, stone, iron and wheat you carry), it gets a Kingdom
  Storage block, and anything left in its barrels and chests moves into Kingdom Storage every 30 seconds.
- **Guard roles:** from the guards tab (or a tower ledger), *Make archer* issues a bow and the guard mans tower
  tops and wall posts; *Make patrol* switches back to the sword. A player-given weapon is handed back first.
- Failed upgrades now explain that Kingdom Storage lacks materials.

### 4.25 Fortress kingdom (0.36)
- **No raids on a camp:** threats only target ESTABLISHED kingdoms, so the first steps (house, Town Hall) are
  learned before defending. Peaceful difficulty ends any running threat instead of leaving "under attack".
- **Calling the patrol:** accepting a defense alert brings the linked patrol's loaded members to the
  kingdom's edge; an active alert has an *Call patrol* button. If no member is loaded anywhere the alert ends.
- **Walls tab** in Management (also *Walls* in the construction view): raise/upgrade tiers, choose **1–4 main
  gates** (3-wide openings with a lintel, evenly spaced from the south, rebuilt instantly), **corner towers**
  (four 3×3 towers on the diagonals, 6 tall, archer posts on top; 160 stone + 32 wood) and **gatehouses** (flanking
  towers, double lintel and banners on every gate; stone tier, 48 stone + 4 iron per gate). A new wall tier
  re-dresses towers and gatehouses. Layout is stored with the wall (older files default to two gates).
- **Mayor:** sits about two thirds into the Town Hall, off the door line, facing the door.

### 4.26 Founders' Lodge and Chapter II (0.37)
- **Founders' Lodge:** when the Town Hall finishes, the founding camp is rebuilt in place as a roofed 9×9 timber
  hall (hearth, chimney, guest beds, ledger). The marker lodestone and the quest board keep their positions, so
  layout and claims do not change. It works as a small inn: +1 tavern level and hiring from its ledger. Existing
  worlds convert when the camp's heart is ensured (talking to the Mayor) or with `/kingdom lodge`.
- **Chapters:** a fourth board tab with 12 main missions in order (clear outskirts, Hall level 2, palisade,
  tavern, 8 residents, hunt, towers, stone walls, three upgrades, become a Town, gatehouses, fortress). One is
  accepted at a time; progress is read from real state (kills are counted inside your own kingdom); rewards are
  emeralds, kingdom XP, a kingdom chest and Royal Whetstones. Progress is stored per player.
- **Second floors:** designed, not applied. See `docs/designs/second-floors.md`.

### 4.27 Hardening and "always see the next step" (0.38)
- **Walls on real terrain:** columns stand on the ground through plants, logs (felled, refunded as Wood) and up
  to 8 blocks of water; gates face the cardinal directions and towers slide clear of them; the ring never waits
  for an unloaded chunk (a repair sweep finishes skipped columns without charging twice, and restores breaches
  after a restart, one Wood/Stone per block). Tower and gatehouse orders that cannot be built are refused free.
- **Moving the wall:** after a territory expansion the next order moves the ring to the new border, demolishes
  the old ring gradually and refunds 50%. Planned plots stay off the wall footprint and gate corridors.
- **Finding and reading walls:** a *Walls* button on the Mayor's main page; the tab shows cost, column progress,
  the tier ladder and "how it works" in the body; *Show on the ground* previews ring, gates and towers for 30 s.
- **Construction next step:** the site view has the plan → supply → Builder → wait checklist; the HUD row names
  the one next step (missing materials, assign a Builder, or the real stall reason); a stall is announced once
  per reason in chat.
- **Missions and trials:** the HUD shows an arrow and distance to the tracked mission (chapter mission as
  fallback; only missions that send coordinates); the trial bar shows range against the 48-block limit and time
  left; the Trials button always opens the picker and each locked tier names the quest that unlocks it.
- **Guide:** a "How do I…?" screen (`?` on the Mayor page and the journal) with four short guides.
- **Founders' Lodge:** residents and players are moved to verified ground and stored items are dropped outside
  before the template is placed. Bed positions are identical in the camp and lodge templates.

## 5. Roadmap (next steps, in priority order)

Done in 0.22: kingdom rank with HUD bar, daily/weekly contracts with streak, rarity loot chests.
Done in 0.24: citizen bonds with daily wishes and perks, expeditions with survivor rescue.
Done in 0.30: rank decorations, timed events with HUD countdown, techniques, walls/archers/alarm/traps, RPG frames.
Done in 0.31: resident life sheet with small talk, Away tab, playtest fixes (housing, retired jobs, ground).
Done in 0.33 (includes the 0.32 polish pass): market stalls with classic and rare daily trades, new market
building, kingdom stages (Hamlet → Village → Town → City) that upgrade every home, kingdom map (M), archers walk
to their posts, `/kingdom perf`.

Done in 0.38: wall hardening on real terrain, wall relocation, visible next step for construction, mission
bearing, trial range/timer, in-game guide (see 4.27). Manual checklist: `docs/playtest-v038-checklist.md`.

**Process rule (from the 0.30–0.31 playtests):** ship smaller versions and let the owner play each one before the
next. Every playtest so far found blocking bugs that unit tests and GameTests could not see. A full GameTest run
happens once per version, before the merge, not after every change.

**Deferred on purpose:** multiplayer (shared kingdoms, roles, concurrency) is out of scope until a single-player
end-to-end polish pass is done. Full sieges against the player's base stay out (trials attack the player only).
New mob models wait until there is an art pipeline again (Argon 4 is no longer available); prefer blocks,
structures and vanilla-texture geometry.

| Version | Feature | Why | Acceptance |
|---|---|---|---|
| 0.34 | Owner playtest + single-player polish pass end to end (found → City with walls) | Every system works together | Checklist passes on a real client; `/kingdom perf` stays under 1 ms/tick with 30 residents |
| ~~0.35~~ done | **Building heart blocks**: one admin block inside every building showing level, workers and orders (tower: patrol or archers on top; market: stall setup) | Every building is interactive | Placed by the template, survives rebuilds |
| ~~0.35~~ done (materials; camp → Founders' Lodge in 0.37; second floors designed, next) | **Building upgrades with visual tiers** | Visible progress | Upgrade = new template stage, same footprint rules |
| ~~0.35~~ done | **Tavern** (immigrants arrive over time; hire for emeralds) and a functional **Warehouse** (real chests feeding Kingdom Storage) | Population growth and logistics | Immigration cadence visible in the UI |
| ~~0.36/0.37~~ done (traps open) | More accepted main missions (hunts, upgrades), wall gatehouse and trap set-pieces | Clear goals | Quest checklists from real state |
| 0.36 | Resident happiness and 3 traits (hard-working, lazy, brave) affecting work and small talk | Residents feel like people | One bar, three traits, bounded effects; shown in the life sheet |
| 0.35 | Expeditions that discover ruins: a map item leads to a real dungeon with a boss and a trophy | Exploration pays more than mining | Structure placed once, cleaned up, trophy only from the boss |
| 0.36 | Production chains (bakery: wheat → bread; carpentry: logs → furniture that raises happiness) | Every building matters | Uses Kingdom Storage, no free items |
| 0.36 | Diplomacy with NPC kingdoms: envoys with requests, trade treaties, caravans between kingdoms | The world is alive | Reputation per kingdom, reversible, no forced war |
| 0.37 | Player titles (Squire → Knight → Lord → King) with a small talent choice each and regalia | Build choice | Respec rules, bounded passives |
| 0.37 | Codex / collections (bosses, trophies, enemies, residents) with set rewards | Completionism | One-time set receipts |
| later | Multiplayer kingdoms (co-owners, permissions) | Requested later | Separate design pass first |
| later | Blood moon event, trial modifiers, wall tier 4 cosmetics, rally destinations, blacksmith rerolls | Backlog | As before |

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
