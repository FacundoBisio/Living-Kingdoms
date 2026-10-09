# Local defense quests and settlement alerts — 0.14.0

Branch: `codex/local-defense-quests`. This milestone makes no commit or push.

## Architecture, detection and resolution

`SettlementThreatEvent` is shared server metadata linked to one settlement and one real hostile party. It stores faction, snapshotted difficulty, full/remaining member counts, timestamps, debug/reward eligibility and frozen successful player UUIDs. DETECTED immediately activates; actual party defeat becomes RESOLVED/VICTORY. Timeout, absent party/settlement or administrative cancellation becomes FAILED. Temporary SAFE/THREATENED is separate from FOUNDING/ESTABLISHED. One party can produce only one event, and a settlement has at most one active event. If several parties approach together, another can be detected after the current event ends.

Loaded encounter members check their existing authoritative identity every 80 ticks. Guards can detect a target immediately through their ordinary local AI. Member death also detects before updating progress, so a fast player kill cannot bypass a nearby threat. The indexed nearest allied settlement is selected using the actual member position, not the potentially stale party origin. Current faction relations support PILLAGER, BANDIT and UNDEAD; current playable party definitions remain Pillager Patrol and Undead Horde. This milestone creates no natural spawn rule or new faction entity/model.

Detection uses the smaller of settlement territory radius and the existing Guard defense range, plus up to four blocks at Security 100 and twelve for an active Watchtower. The configured maximum is 72 blocks. Multiple towers do not stack. Watchtower detection does not increase Guard combat/search/chase range or hostile stats. Pending construction does not supply the bonus. Earlier warnings may therefore require the player to intercept enemies before Guards engage.

Victory requires actual defeat of the linked party. Guard/environment kills update shared progress; unrelated deaths do not. Retreat is deferred. A threat that remains alive for 12,000 server ticks expires without settlement destruction. The surviving mobs still use ordinary AI; this party does not reopen another defense event. At most sixteen active records are reconciled per configured interval for timeout/reload recovery, with immediate reconciliation on death and UI access. No world-wide entity/settlement scan or forced chunk loading is added. Unloaded mobs are not simulated or assumed dead.

## Alerts, Board and Mayor

A newly persisted event sends one concise localized warning to living nonspectating players inside its settlement. Repeated members/ticks and reloads cannot issue a new event warning for that party. Success/failure produces one local follow-up. No global chat broadcast, title storm or offline alert replay exists.

Defense appears immediately on the ordinary Quest Board, including during its ordinary rotation cooldown. Choose Accept before victory (acceptance Option B); Mayor dialogue directs players to the Board. The defense reader shows settlement, faction, threat rating, remaining/total enemies, status and snapshotted rewards. The current request is selected on Board opening. Native detail scrolling keeps long descriptions readable. Mayor dialogue distinguishes ongoing threats, personal contribution, victories by other defenders and practice/failed defenses. The existing metrics expose SAFE/THREATENED. No party/event UUID is displayed.

`LOCAL_DEFENSE` is an ordinary dynamic `QuestInstance`, with its existing `QuestObjective.Party`, acceptance, state, reward, UI session and inventory transaction. Its deterministic event/player identity prevents repeated offers from creating a second receipt. A separate event stores shared progress rather than a second per-player quest engine. Ordinary generation excludes this contextual template; rotation preserves its live offer and accepted/ready quests. Accepted contributors become ACTIVE/ready on success; unaccepted requests and accepted nonparticipants close as EXPIRED. A failed event closes its live defense quests as FAILED.

## Guards, Watchtower and rewards

Guards retain their existing native navigation, local search, hostility policy, equipment, damage and profession XP. They receive no scripted quest-specific combat behavior. Their kills may save the settlement without creating any player credit.

An established settlement can now use **Mayor → Construction → Plan Watchtower**. The ordinary resource/timer/protected native building flow defaults to 48 logs, 32 stone, 4 iron and 3,600 ticks. Completed construction registers its existing DEFENSE_SUPPORT capability, +6 Security and detection bonus. Another Watchtower plan is rejected. The construction choices use two rows (House/Farm, Barracks/Watchtower). Tower combat remains deferred.

Player eligibility reuses existing encounter damage contributions: by default accumulated post-reduction damage of at least four, including a hit in the last 6,000 ticks. Guard damage, spectators, proximity and unrelated mobs give no credit. On victory, eligibility freezes individually. Each accepted eligible player can claim its own modest emerald/reputation reward through the existing difficulty/reward policy. Default combat reward starts at four emeralds and three reputation, adding one of each per existing difficulty step, with caps of 32 emeralds and 50 reputation. The normal once-per-party roaming reputation remains a separate reward under its existing regional attribution; it is not broadcast to every settlement. A participant who did not accept before victory receives no defense quest reward, but can still receive existing encounter reputation and the defense advancement.

**Hold the Line / Mantener la línea** uses native player advancement persistence and only grants after meaningful successful participation. The event store retains a successful participation receipt for offline players to receive it at login or UI access. Repeating the award does not duplicate the native advancement. Guard-only victory grants neither personal defense rewards nor this advancement.

## Development commands

Stand inside an allied settlement with operator permission 2:

```text
/kingdom defense trigger
/kingdom defense trigger pillager
/kingdom defense trigger undead
/kingdom defense info
/kingdom defense resolve
```

Trigger first attaches an eligible existing loaded party of the requested type; otherwise it uses the existing controlled spawner. Newly spawned fixtures are debug encounters with zero personal rewards/advancement. To explicitly test rewards in a copied QA world:

```text
/kingdom defense trigger pillager reward_test
/kingdom defense trigger undead reward_test
```

If an existing natural party is attached, its original eligibility remains authoritative. `resolve` cancels a living event as DEBUG_CANCELED and closes its defense quests; it does not manufacture victory, kill mobs or award rewards. Defeat enemies through real combat to test success. Normal gameplay detects existing naturally spawned encounters and requires no commands.

## Multiplayer, persistence and compatibility

The event is settlement-shared; accepted quests, contribution, claim, reputation and advancement are individual. Frozen eligible UUIDs do not change on reconnect. Server-owned stores, exact event/player/party identity and existing physical Board/session checks reject another player's/stale client actions and duplicate claims. Resolving the same event again is harmless. Resolution can be replayed into quest metadata on later UI access after reload, without replaying inventory rewards.

`livingkingdoms_defense.dat` is a new additive schema-1 store. Existing settlement, encounter, profession, citizen, quest, construction, reputation and progression schema numbers remain unchanged; quest serialization appends the known template/source values. Missing new data in an old world starts empty. Corrupt/unreadable/future defense data fails closed rather than overwriting it. Native entity NBT continues to own mob positions, health, equipment and identity. Missing linked party metadata fails cleanly; unloaded live members retain the event until its normal deadline.

Terminal events can be pruned after 168,000 ticks only after their party is absent and no live defense quest needs them. Ready accepted quests protect frozen claim eligibility. Successful player advancement receipts remain. Bounded indexes cap total events at 65,536, active events at 4,096 and successful player receipts at 100,000; reaching a cap refuses new records/rewards rather than corrupting data. The ordinary encounter tracked-party cap also remains in force. Do not downgrade a world containing new defense quest template values to 0.13.

## Automated verification

Unit tests cover strict event/faction serialization, UUID uniqueness, state transitions, deduplication, bounded indexes, frozen multiplayer eligibility, one-time rewards, debug/Guard-only cases, physical persistence, corrupt/future files, contextual quest generation, Board rotation, exact claim identity and UI presentation/layout. All pre-existing tests are retained.

GameTests cover normal Guard/Pillager and Guard/Undead combat, player damage plus Guard finish, actual Board acceptance/claim/replay, multiplayer eligibility and cross-session rejection, linked member progress versus unrelated deaths, timeout/missing-party cleanup, active metadata/native entity round trips, default debug commands, periodic alert deduplication and native Watchtower construction/detection without distant chunk loading. Graphical Save & Quit and screenshot acceptance are pending hands-on QA; automated native NBT/file persistence checks are not a claim that the GUI was manually tested.

Validation commands (Java 21):

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test build
.\gradlew.bat --gradle-user-home .gradle-user-home runGameTestServer
git diff --check
```

Final results on 2026-10-09: **289 unit tests passed**, **115/115 required GameTests passed** (all 249 previous unit tests and 105 previous GameTests retained), Gradle build passed, tracked/staged `git diff --check` passed and new source/doc files passed the whitespace scan. English/Spanish localization has 366 matching keys. The initial run exposed same-tick event ordering and an obstructed combat fixture; the ordering regression now has a unit test, the fixture Board was moved out of the combat path, and the complete suite passed after correction. Artifact: `build/libs/livingkingdoms-0.14.0.jar`. Native client/manual Save & Quit QA remains pending.

## Future PC gamer checklist

Use matching 0.14.0 client/server JARs and a copied test save. Default debug encounters intentionally cannot test personal rewards; use explicit `reward_test` for steps 9–13.

1. Establish a settlement through Charter founding or vanilla village conversion.
2. Build Barracks through Mayor → Construction, using displayed resources and normal build time.
3. Assign an eligible housed citizen as Guard; recruit enough defenders for a full group.
4. Optionally build Watchtower. Record Security and compare warning distance before/after completion.
5. Stand inside the settlement and run `/kingdom defense trigger pillager reward_test`.
6. Verify one localized warning and THREATENED status; wait through several checks to confirm no repeated warning.
7. Open the Quest Board normally.
8. Verify the DEFENSE reader, settlement/faction/threat, remaining count and rewards; accept it.
9. Let Guards defeat the group alone, without dealing player damage.
10. Verify SAFE status, Mayor's defenders dialogue, no personal combat reward/advancement and no claimable defense quest.
11. Trigger another Pillager or Undead threat with explicit `reward_test`; open/accept its new defense request.
12. Deal meaningful damage to actual group members and help defeat them; Guards may land the last hit.
13. Verify linked progress, success feedback, personal advancement and one Board claim. Replay Claim and compare another nearby nonparticipant's eligibility/reputation.
14. Trigger another threat, accept its quest and Save & Quit while members remain alive.
15. Reload the same save with the same mod version.
16. Verify the same threat/quest resumes, or expires cleanly if its linked party is absent/deadline passed. Finish it and verify no duplicate warning, event, reward or advancement.

Also inspect English/Spanish, keyboard/scrolling and small-screen GUI scales. Confirm Farmers/Food, housing, immigration, Guard jobs and ordinary main/resource quests still work. Previous native client attempts crashed in the AMD OpenGL driver before gameplay; graphical QA remains pending on a working gamer PC.

## Limits and next milestone

There are no raid waves, retreat resolution, siege engines, destruction, walls/gates, occupation, conquest, liberation or bosses. One nearby party threatens one settlement; detection prefers the nearest indexed allied center. Bandit is accepted by faction policy/persistence but still needs its own party definition. No combat is simulated in unloaded chunks. Multiple players who qualify can each receive the modest accepted quest reward; large-party reward scaling and more sophisticated contribution weighting remain future balancing work.

The next logical milestone is field testing and tuning travel encounters and defense readability: validate night Undead behavior, full patrol versus small Guard teams, Watchtower warning lead time and reward balance on a real client/multiplayer save. After that, a small data-driven Bandit roaming party fits the faction foundation. Do not start full raids automatically.
