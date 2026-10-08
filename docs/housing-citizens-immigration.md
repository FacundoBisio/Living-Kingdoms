# Housing, citizens and immigration — 0.11.0

Work branch: `codex/housing-citizens-immigration`. No commit or push is performed for this milestone.

## Playable loop

Found a kingdom with the Charter, complete its Town Hall and first House, then build additional housing through **Mayor → Construction → Plan House**. The expansion uses the existing resource contributions, timer, plot reservations and protected placement. A small house has two places by default. The Mayor and two founding residents are citizens, so the first house is full and one resident remains unhoused; the next house provides the first free place. Existing residents receive places before immigrants. No profession work loop is implemented.

Once an established settlement has a free place, a traveler may request entry. The local notification says a traveler wants to join the named settlement. Open **Mayor → Immigration**, or the Immigration shortcut on the Quest Board. Select a traveler to see their persistent name, starting level, future role preview, expiry and occupied/total/free housing. **Accept** creates one native Villager near its assigned house; **Decline** removes the request. The interface also reports stale requests and unavailable housing. Settlement information and `/kingdom settlement info` show actual citizen population, housing and pending travelers.

Operators can use `/kingdom immigration candidate` inside an eligible settlement. This is labelled a development tool and bypasses probability/cooldown only. Enabled immigration, established lifecycle, valid free registered housing and pending limits still apply. Approval remains necessary. Existing `/kingdom construction info`, `complete` and `advance` tools remain available for the initial chain and reserved expansion projects.

## Housing and citizen architecture

`CitizenSavedData` owns citizens, registered housing, shared candidates, migration receipts and opportunity timestamps. Immutable `Housing` records identify their settlement, dimension, template, origin/entrance, capacity and ACTIVE/DISABLED status. A deterministic UUID derived from settlement/template/origin/rotation remains stable across layout reloads and appended buildings. Only completed `HOUSE`, `HOUSE_VARIANT` and `HOUSE_THIRD` metadata counts by default. Reserved blueprints and vanilla beds do not. A founding camp counts only when explicitly enabled in server configuration.

Occupancy and assigned resident IDs are derived from active citizens' single nullable home UUID. There is no second mutable resident list to disagree with that assignment. Capacity changes reconcile homes before allocating free places. Summary APIs expose total, occupied and free capacity. An unhoused population larger than capacity is valid and prevents newcomers from displacing existing citizens.

`Citizen` stores citizen UUID, vanilla entity UUID, settlement UUID, immersive name, `LevelValue`, role, nullable home, ACTIVE/DEAD/MISSING state and server game tick joined time. Active roles are UNASSIGNED and MAYOR; FARMER, GUARD, BUILDER, BLACKSMITH, MERCHANT and CAPTAIN reserve future domain identities. These levels never invoke hostile combat stat scaling. New names draw from 40 first names and 20 surnames and are saved once; valid existing villager names are retained. The Mayor retains its current name, presentation and special behavior.

Population comes from ACTIVE citizen records. The old settlement population field remains serialized for compatibility with existing settlement records. Gameplay UI/commands read the citizen store, with a narrow legacy fallback for the operator's abstract GENERATED records having no layout, Mayor or citizen identities. Vanilla entity persistence owns position, health, inventory and Brain data. Citizen records remain ACTIVE while their chunks are unloaded. A confirmed death or conversion into a non-Villager retires the identity as DEAD, releases its home and updates the derived population. Dead citizens are not respawned. MISSING is reserved for a future explicit reconciliation policy.

Citizens retain vanilla behavior, persistence and visible names. Loaded entities receive a home/settlement restriction. An entity-local event every 40 ticks proposes a native Brain walking target when an active citizen has drifted outside that radius, skipping sleeping, trading, mounted, leashed or NoAI villagers. It reads loaded ground only and does not teleport entities, add chunk tickets or implement a schedule. Obstacles and vanilla Brain decisions can delay a return.

## Immigration and multiplayer authority

Natural checks select at most one active player and their local settlement every configured interval. Initial and subsequent opportunity times persist. Only established allied settlements with sufficient free registered housing can generate a request; chance and the shared pending limit also apply. A failed opportunity spends its cooldown. Candidates have UUID, settlement UUID, name, level, future role preference, created tick and expiry tick. No final citizen entity exists before approval.

`ImmigrationPolicy.Factors`/`Modifier` provide neutral food, security and prosperity placeholders plus local reputation, settlement level and recent-attack input for future policies. The current policy uses configured chance; it does not simulate food, prosperity, regional attacks or an economy. Housing and lifecycle remain hard gates even when a modifier is supplied.

All mutations execute synchronously on the server thread. GUI requests must carry that player's current session capability, match the Immigration view, remain in the same allied territory and within reach of the original live Mayor/board/marker. Acceptance checks current lifecycle, configuration, candidate ownership/expiry and free housing again, prepares a safe loaded Villager position, then atomically reserves the exact home and consumes the shared candidate. A rejected/cancelled entity insertion restores the original request and releases the identity/home. Serialized actions make two-player acceptance, replay, cross-settlement IDs and stale snapshots harmless. Native spawn/player protection still applies; no owner-only settlement rule is introduced.

An accepted immigrant awards **FIRST_CITIZEN**, displayed as **Growing Community** / **Una comunidad en crecimiento**, to the accepting player. Vanilla advancement receipts make this one-time per player and persistent across reloads. Declining, generating a candidate, another player's acceptance and a failed spawn do not award it.

## Converted villages and migration

Successful founding/conversion initializes citizens after the existing reversible transaction commits. Migration reuses the associated Mayor, durable founding resident receipts and matching resident NPCs; it does not spawn replacements. Legacy settlements initialize lazily on relevant UI/debug use. Tagged residents joining later can register without a new entity. Reserved or malformed identities are not stolen.

Conversion registers valid adult villagers from its bounded survey once. Legacy conversion initialization performs one bounded loaded territory query; babies, villagers outside the circular territory and identities belonging elsewhere are excluded. Existing named/professional/trading villagers retain their native NPC identity, profession and offers, while receiving the separate citizen association. The initialization receipt prevents claiming later unrelated visitors. Converted populations may exceed their initial zero LK capacity.

Converted settlements can select Plan House. A loaded, clear floor next to the existing plaza becomes a connection port; the existing adaptive planner checks native obstacles, terrain, paths, territory and protection for the new house. Its layout contains actual new buildings and plaza ports without inventing a Town Hall/core or converting vanilla beds into capacity. If no safe port/plot exists the request fails without replacing existing village structures. Original vanilla buildings remain protected by physical terrain checks rather than fabricated building metadata.

## Server configuration

In the world's `serverconfig/livingkingdoms-server.toml`:

```toml
[citizens.housing]
defaultCapacity = 2
templateCapacities = []
includeTemporaryShelters = false

[citizens.immigration]
enabled = true
checkIntervalTicks = 200
minimumCooldownTicks = 24000
maximumCooldownTicks = 72000
candidateLifetimeTicks = 24000
maximumPendingCandidates = 2
minimumFreeHousing = 1
chance = 0.25
minimumStartingLevel = 1
maximumStartingLevel = 3
```

Template overrides use strings such as `"livingkingdoms:allied/plains/house=4"`; the last matching entry wins. Capacity refreshes during relevant initialization/UI/construction events. At 20 TPS an opportunity waits 20–60 minutes of server time and has 25% chance; a candidate lives 20 minutes. These times use the Overworld's monotonic game time, not daylight time or wall-clock time. Offline time does not advance them. A settlement is evaluated while a player is present; expiration is also enforced when opening/approving its UI. Existing deadlines/candidate levels/names remain saved. Reversed min/max ranges are normalized safely. Starting levels support the shared 1–100 range.

## Persistence and performance

New `data/livingkingdoms_citizens.dat` uses schema 1. Its loader validates identities, names, levels, timestamps, roster bounds, home ownership/capacity and duplicate entity/candidate receipts. Corrupt/future files fail closed rather than silently becoming an empty store. Existing settlement schema 3 and construction/quest/encounter files preserve IDs, layouts, founder/provenance, lifecycle and other gameplay state. New converted plaza layouts are a compatible reader extension for this version; downgrading a world with these layouts to 0.10.0 is unsupported.

No production code force-loads chunks. Housing sync is event/on-demand; entity association uses UUID lookups; initial villager scans are bounded and one-time; immigration uses timestamps and one local metadata lookup per interval. SavedData derives local indexes on load. Ordinary autosave/Save & Quit persist both records and vanilla entities. As with existing construction, independent Minecraft entity/chunk/SavedData files are not a journaled transaction against abrupt power/process failure.

## Automated validation

**2026-10-08:** `gradlew --gradle-user-home .gradle-user-home test build runGameTestServer` passed with **214 unit tests** (190 retained + 24 new), **86 GameTests** (77 retained + 9 new), production/sources JARs and `git diff --check`. No test was skipped. English/Argentine Spanish JSON parses with identical 257 keys and matching format placeholders. The three-row Mayor action geometry is verified across six GUI resolutions; graphical client QA remains manual.

Coverage adds housing capacity/variants, assignments/overbooking, duplicate identity, name/home persistence, migration, missing/unloaded entities, candidate generation/decline/expiry, multiplayer approval/replay, cancelled-spawn rollback, citizen death, converted housing construction and native advancement receipts/file reopen. Strict persistence tests cover corrupt/future-file refusal, home/entity/candidate collisions and off-thread mutation rejection. All prior construction, founding/conversion, quests, encounters, faction/progression and geometry regressions remain intact.

The first full GameTest pass caught two new fixtures missing valid plaza metadata and the existing abstract command population compatibility expectation. Both fixtures now validate complete metadata, and only metadata-only operator settlements retain their legacy count; the final full pass is green. Deliberate Mayor refusal/protection/obstruction logs are retained negative test coverage. Test-world archives are under `build/qa/gametest-world-before-housing-*`; normal development/player worlds were not moved or reset. The distributable is `build/libs/livingkingdoms-0.11.0.jar`; test-only entities, fixtures, tickets and adapters remain outside it.

## Manual QA on the gaming PC

Use Minecraft 1.21.1, Java 21, NeoForge 21.1.252 and the same `livingkingdoms-0.11.0.jar` on client/server. Use a disposable test world and a copy of an older world for migration. The existing development machine's AMD OpenGL startup crash means this milestone does not claim graphical acceptance.

1. Craft/use a Charter in suitable wilderness and verify one Mayor plus two residents, the founding camp and the Town Hall project.
2. Supply the Town Hall's existing materials and finish its timer, or use the labelled operator construction shortcut for QA.
3. Supply/complete the first House and verify ESTABLISHED, unchanged settlement identity and three registered citizens.
4. Verify permanent capacity 2 and free capacity 0; the temporary camp must not count. Select Mayor → Construction → Plan House, supply/complete the second House, and verify capacity 4, occupied 3 and free 1.
5. Wait for the configured eligible opportunity, temporarily shorten settings in the QA world, or run `/kingdom immigration candidate` as an operator. Verify a notification and no new villager yet.
6. Open Mayor → Immigration or the board shortcut. Verify name, level, future-role placeholder, expiry and housing in English/Spanish at GUI scales 2/3/4; check Tab, Enter, Escape and scrolling.
7. Accept one request. Try repeated clicks/Refresh and verify one success message and one Growing Community toast.
8. Verify exactly one named persistent citizen appears near the assigned house and current population increases by one. Check that its level has not increased hostile combat stats.
9. Verify housing occupancy rises by one/free capacity falls by one; no house exceeds its configured capacity. Let the citizen walk and check its return behavior without expecting a full schedule.
10. Save & Quit, or stop the dedicated server normally. Also repeat with one unresolved candidate before saving.
11. Restart the same world/server and return to the settlement without adding chunk-loading tickets/mods.
12. Reopen information/Immigration and verify identical name, level, citizen count, home occupancy and unresolved candidate/cooldown; no duplicated citizen or advancement toast. Leave/reload a citizen chunk and verify population stays stable.
13. Kill an ordinary test citizen in the disposable world, using a real lethal damage command/combat. The special protected Mayor is unsuitable for this check.
14. Verify population falls by one and a housing place becomes available, or is assigned to an older unhoused citizen first. The dead citizen must not respawn on return/reload.
15. Have two players open the same candidate and accept it simultaneously. Verify one citizen/home reservation; the second player receives refreshed/stale feedback. Repeat with Decline, expiry, no housing, leaving reach and another player's session.

Additional regression checks: convert a vanilla village with traders/children/nearby outsiders; verify adult registration once, preserved vanilla professions/offers and zero inferred bed capacity, then build valid LK houses until existing residents are housed and a free place remains. Reopen a copied 0.10.0 settlement during construction, preserve partial resources/timers/quests/reputation and migrate its existing residents once. Verify FOUNDING and disabled immigration refuse debug proposals, then test a cancelled spawn/blocked entrance and pending limit. Existing board/main/dynamic quests and native template geometry remain covered by automated regressions.

## Limitations and next milestone

House status currently follows registered completed metadata, not a structural damage simulation; breaking decorative house blocks does not automatically delete housing. Homeless residents remain valid citizens. Previously dead, unobserved legacy receipt entities cannot be distinguished from unloaded entities during migration. A legacy converted village initializes only its loaded bounded adults; its migration receipt deliberately avoids capturing future unrelated visitors. Candidate probabilities/factors are not an economy. UI updates on actions/Refresh, and natural evaluation needs an active nearby player. Gentle native navigation does not guarantee escape from blocked terrain. There is no custom model, profession loop, physical builder, wage/trade/food economy, tier, raid, conquest or liberation system.

Recommended next milestone: perform the graphical/save/multiplayer QA above, then add a narrowly scoped citizen roster and one Farmer activity with explicit food storage/consumption rules. Define those rules before enabling any economy or additional profession simulation.
