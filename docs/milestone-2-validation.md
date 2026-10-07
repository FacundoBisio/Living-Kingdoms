# Milestone 2 validation

Validated locally on 2026-10-06 with Java 21, Minecraft 1.21.1 and NeoForge
21.1.252. Mod version: **0.3.0**. Branch:
`codex/milestone-2-first-quest`, based on Milestone 1 commit `e6ee55c`.
The original branch was clean `main`, matching `origin/main` before changes.

## Result and architecture

The playable loop is: discover the existing physical settlement, meet its Mayor,
inspect and accept Iron Shortage at the existing Quest Board, deliver Iron Ingots,
receive Emeralds and gain player-specific settlement reputation. The original
settlement model, settlement persistence schema, structure asset, terrain planner,
`create` and `info` remain intact. Generation now associates one Mayor.

All gameplay runs on the server thread. Quest data is keyed by player UUID,
settlement UUID and stable quest ID; display names do not identify relationships.
Available offers are derived from the local Iron Shortage definition for allied
settlements. Acceptance creates one persistent ACTIVE entry and snapshots the
configured terms. Inventory delivery preflights exact consumption and complete
reward capacity on copied slots before the once-only completion transition.
Player progress is independent across players and settlements. Reputation is
shared by that player's quests in one settlement, not across settlements.

The new guarded Overworld SavedData file is `data/livingkingdoms_quests.dat`,
schema 1. It stores progress, reputation and settlement-to-Mayor UUID associations.
Unsupported/corrupt saves cannot silently become an empty replacement store.
Settlement schema 1 is unchanged; existing worlds require no settlement migration.

NPCs reuse vanilla Villagers. Names and ordinary entity state are synchronized by
Minecraft; role and settlement UUID are server-owned NeoForge persistent entity
data. No new entity renderer, custom GUI, custom networking or villager profession
internals are introduced. The Mayor uses deterministic localized before/after
dialogue and has no trades. Role concepts also include Blacksmith and Guard.

## Important files changed

| File / area | Purpose |
| --- | --- |
| `src/main/java/dev/livingkingdoms/quest/domain/QuestId.java`, `QuestType.java`, `QuestState.java`, `QuestTerms.java`, `PlayerSettlementProgress.java` | Stable quest identity/type, lifecycle and immutable accepted terms/progress. |
| `src/main/java/dev/livingkingdoms/quest/persistence/QuestSavedData.java` | UUID relationships, independent quest state/reputation, Mayor uniqueness and guarded NBT persistence. |
| `src/main/java/dev/livingkingdoms/quest/QuestService.java` | Board inspection, acceptance, delivery, live inventory progress and feedback. |
| `src/main/java/dev/livingkingdoms/quest/DeliveryInventory.java` | Exact main/offhand iron consumption and all-or-none Emerald insertion plan. |
| `src/main/java/dev/livingkingdoms/quest/QuestInteractions.java` | Allow crouch-click board interaction while holding items through the vanilla pipeline. |
| `src/main/java/dev/livingkingdoms/npc/NpcRole.java`, `NpcIdentity.java`, `NpcService.java`, `NpcInteractions.java` | Persistent roles/settlement identity, safe Mayor spawn/association and local dialogue without trades. |
| `src/main/java/dev/livingkingdoms/block/QuestBoardBlock.java` | Replace placeholder feedback with the quest service; consume offhand without duplicate actions. |
| `src/main/java/dev/livingkingdoms/settlement/SettlementGenerator.java` | Load guarded relationship storage before world mutation and associate a Mayor after physical generation. |
| `src/main/java/dev/livingkingdoms/command/SettlementCommands.java` | Preserve settlement commands and add optional `/kingdom reputation`. |
| `src/main/java/dev/livingkingdoms/LivingKingdoms.java`, `config/KingdomConfig.java` | Register interaction adapters and configurable quest quantities/rewards. |
| `src/main/resources/assets/livingkingdoms/lang/en_us.json`, `es_ar.json` | Quest states, controls, progress/rewards, NPC names and deterministic dialogue. |
| `src/test/java/dev/livingkingdoms/quest/domain/QuestDomainTest.java`, `quest/persistence/QuestSavedDataTest.java` | Fifteen new JUnit tests. |
| `src/gametest/java/dev/livingkingdoms/gametest/QuestGameTests.java` | Six new real server inventory/interaction tests. |
| `src/gametest/java/dev/livingkingdoms/gametest/PhysicalSettlementGameTests.java` | Retain existing physical tests; replace obsolete placeholder assertions and extend generated-settlement Mayor/quest coverage. |
| `gradle.properties`, production/test mod metadata | Version 0.3.0 and milestone description. |
| `README.md`, this report | Exact play instructions, storage/configuration, scope and validation. |

No existing test method was removed. No runtime dependency on WorldEdit,
JourneyMap or JEI was added.

## Automated results

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build runGameTestServer
python tools/generate_settlement_template.py --check
git diff --check
```

- **BUILD SUCCESSFUL** on the final coordinated run.
- **26 JUnit tests passed**, with zero failures/errors/skips: the original 11 plus
  3 quest-domain and 12 quest/reputation persistence tests.
- **11 required GameTests passed** in 3.942 seconds: the original 5 physical and
  command/storage tests plus 6 quest tests. The server saved all dimensions and
  shut down cleanly.
- The native structure asset still matches the deterministic generator bytes.
- `git diff --check` passed.
- Production JAR inspection found the quest/NPC classes, unchanged native
  template and valid JSON resources; GameTest content is excluded and no optional
  development-mod dependencies exist.

The tests cover disk reload, independent UUID relationships, fixed accepted terms,
terminal lifecycle states, signed reputation, duplicate transitions, malformed
data protection, Mayor UUID uniqueness and persistent entity identity. Actual
board use verifies insufficient resources, exact removal across slots/offhand,
unrelated items/armor preservation, reward/reputation once, full inventory refusal,
capacity freed by consumed iron, normal reconnect using the same player UUID,
board territory/reach checks and multiplayer independence. A real
`ServerPlayerGameMode` interaction exercises crouching while holding iron.

One delivery test uses accepted terms of 16 iron / 80 Emeralds / 10 reputation
despite default live settings of 16 / 8 / 10. It verifies preserved terms, merging
60 existing Emeralds into legal 64/64/12 stacks, and no repeated reward.
The generated-settlement test checks Mayor spawn/idempotence, no trades, vanilla
entity NBT persistence, independent before/after dialogue, completed quest state,
reputation and reloaded associations.

Reports are in `build/test-results/test`, `build/reports/tests/test/index.html`
and `runs/gametest/logs/latest.log`. Installable artifact:
`build/libs/livingkingdoms-0.3.0.jar` (the sources JAR is not for installation).

## Client result

`runClient` was retried once for Milestone 2. It again crashed in native AMD
`atio6axx.dll+0x193ca0` with `EXCEPTION_ACCESS_VIOLATION` during early GLFW/OpenGL
window creation, before mod gameplay. Report: `run/hs_err_pid11196.log`.
Client log: `run/logs/latest.log`. No architecture or graphics-driver workaround
was made. Headless checks above succeeded.

Visual rendering, actual mouse/keyboard client interaction and graphical
Save and Quit/reopen therefore remain unverified on this machine. Both logical
interaction paths were checked against the exact Minecraft/NeoForge 1.21.1
sources, and server GameTests exercised the inventory/interaction behavior.

## Manual playable check

1. Install 0.3.0 with matching Minecraft/NeoForge. Generate a settlement as an
   operator in open, dry, loaded terrain and follow its reported coordinates.
2. Right-click the named Mayor near the lodestone. Expect a welcome mentioning
   this settlement and a request to check the board.
3. Right-click the Quest Board normally. Expect Iron Shortage, AVAILABLE,
   inventory progress, 16 required Iron Ingots, 8 Emeralds and +10 reputation.
4. Crouch + right-click to accept. Repeat with fewer than 16 iron; no items or
   rewards should change. Normal right-click must show ACTIVE/live progress.
5. Collect at least 16 Iron Ingots in the main inventory/offhand and make room
   for the reward. Crouch + right-click to deliver. Verify exactly 16 consumed,
   8 Emeralds received, COMPLETED and reputation 10. Talk to the Mayor again.
6. Repeat delivery, `/reload`, reconnect and then save/reopen the world normally.
   Verify the same completed state/reputation and no second reward.
7. Have a second player complete their own instance. In a second settlement,
   verify each player's quest/reputation starts independently.

Quest settings live in the existing per-world server config under `[quests]`:
`requiredIron = 16`, `rewardEmeralds = 8`, `reputationReward = 10`. Changes affect
new acceptances. `/kingdom reputation` is optional; gameplay requires no commands
after the settlement has been generated.

## Known limits and next milestone

There is one non-repeatable resource-delivery quest per player per allied
settlement. FAILED is a supported state, but there is no failure timer, abandonment
or reset gameplay. Progress reflects held inventory when inspected; resources are
not reserved on acceptance. Other quest objectives need a new focused handler.

Only Mayors spawn. Blacksmith/Guard are persisted role concepts without behavior.
Mayors have NoAI, no trades, persistence and ordinary-combat invulnerability for
this prototype; pushes can move them and creative removal still works. Recorded
missing/unloaded Mayor UUIDs are never replaced automatically to avoid duplicates.
Old M1 settlements can acquire a Mayor on first board use while the central
lodestone remains and the plaza has a safe loaded position. Spawn failure is logged
and can retry on later board use while no association exists; it does not roll back
an already persisted physical settlement.

No natural distribution, advanced professions, upgrades, economy, combat systems,
conquest, armies, LLMs or GUI were added. Existing conservative terrain limitations
remain. Normal reconnect/reload/save is covered; independent player/entity/world
files are not an atomic crash-proof transaction. Use normal save/shutdown for
durability, as with the existing Minecraft world storage.

Next logical milestone: unlock recruitment of one basic Guard through settlement
reputation, with persistent player/settlement association. Milestone 3 has not been
started.
