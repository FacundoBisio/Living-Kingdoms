# v0.18 chronicle integration

Minecraft 1.21.1, NeoForge 21.1.252, Java 21. This package creates a separate `livingkingdoms_chronicle.dat` schema 1 store. It does not migrate or rewrite v0.17 settlement, citizen, profession, construction, quest or onboarding stores.

## Wiring

In the common mod constructor, register `modBus.addListener(ChroniclePayloads::register)`. In the physical client `VillageClient` constructor, call `KingdomAlerts.register()` once. Neither registration invokes historical alerts.

`ChronicleService.history(server, settlementId)` returns a `CompoundTag` containing `entries` (a list of compounds), newest first. Each entry has `event` (UUID), `kind` (lowercase string), `tick` (world game time), and `subject` (plain bounded text). `KingdomAlerts.describe(kind, subject)` produces the localized display component, shared by toasts and the management history tab. History reads never send packets.

## Source hooks, after commit only

- `CitizenEvents.onDeath`: capture the registered `Citizen before` while still ACTIVE and active GUARD profession status **before** `FarmerWork`/profession retirement. Only when the non-cancelled real death successfully marks that entity DEAD, call `ChronicleService.citizenDied(server, before, wasGuard)`. The helper verifies prior ACTIVE state, current registered DEAD state, and identical entity/settlement identity. `LivingConversionEvent.Post`, chunk unload, missing entity, reconciliation and startup must not call this death helper. The existing shared `retire` path therefore needs a real-death flag or a death-only wrapper. A guard profession must be captured before other death listeners retire it; register the citizen death listener at a priority preceding profession death listeners if necessary.
- Whenever death, conversion or reassignment removes the last previously assigned worker from an essential workplace: capture its previous workplace ID before retirement; after the profession transition, call `ChronicleService.essentialWorkplaceEmpty(server, settlement, workplace, citizenId + ":" + employmentRevision)`. The helper checks the committed metadata has an active workplace with positive slots, is FARM/BARRACKS/CORE/TOWN_HALL, belongs to this settlement, and has zero workers. Call on occupied-to-empty transitions only; a newly generated unstaffed workplace produces no recurring reminders. The receipt must include the transition revision so a later staffed-to-empty change can produce a fresh alert.
- `DefenseService` threat creation/arrival: after `DefenseSavedData` successfully commits the first significant threat, call `ChronicleService.significantThreat(server, settlementId, event.id(), faction.name().toLowerCase(Locale.ROOT))`. For approaching threats that later arrive, use the **same** threat ID, so arrival cannot duplicate its approach fact. Ignore debug probes, individual mobs outside the existing encounter system, and ordinary HUD refreshes.
- `ConstructionService.finish`: after the placement transaction, updated metadata and COMPLETED project receipt have committed, outside the rollback `try/catch`, call `ChronicleService.buildingCompleted(server, settlement.id(), projectId, entry.project().building())`. Do not emit at funding, stage advancement, blocked placement, cancellation or before `transaction.commit()`.
- Upgrade service: after persisted upgrade level and treasury/resource payment have committed together, call `ChronicleService.buildingUpgraded(server, settlementId, buildingId, committedLevel)`. The receipt is building UUID plus resulting level; retrying cannot emit a duplicate.

`ChronicleService.record(server, settlement, kind, sourceReceipt, subject)` is the general API. The stable source receipt identifies a committed transition, never a freshly generated retry UUID. Existing source receipts remain authoritative after chronicle receipt eviction.

## Personal native delivery and costs

New committed facts notify currently online members whose onboarding stage is JOINED. Unrelated visitors receive no packet. Shared collaborators receive their own S2C packet. Disconnected members see history later; offline notices are not queued and login does not replay old facts. Presentation failures retain the persisted fact and do not retry on login.

History is capped at 128 entries per kingdom, recent deduplication receipts at 512 per kingdom, and stores at 1024 kingdoms. At the kingdom cap new chronicle kingdoms are refused without disrupting gameplay. History and recent receipts evict oldest entries; repeat old source transitions must also be prevented by the source system's durable death/construction/upgrade/threat receipt. The client has eight pending toasts, one current toast, and 128 recent packet IDs; bursts evict the oldest pending notice, retaining its server history. There are no world scans, forced chunks, global AI loops or server tick listeners. New facts perform O(online players) delivery checks. Save/load work is bounded by these caps.

## Advancements

Six native JSON advancements use the 1.21.1 `display.icon.id` schema and `minecraft:impossible` criteria. `ChronicleAdvancements.grant(player, key)` awards `achieved`; vanilla advancement progress persists the one-time receipt. JSON grants no inventory or experience rewards.

Keys: `converted_village`, `first_warehouse`, `all_professions`, `first_upgrade`, `first_trade`, `development3`. Integrate only after successful conversion, completed first warehouse, verified active citizens across all operational professions, first committed upgrade, first successful player trade, and reaching the existing development level 3. Failed operations never grant. `first_trade` is a player's completed trade receipt, not an automatic merchant production pulse.

## Locale additions

Merge `docs/v018-chronicle-locales.json` keys into `en_us.json` and `es_ar.json`; existing construction building names are reused. Add CORE/TOWN_HALL building labels if absent.

| Key suffix (`chronicle.livingkingdoms.`) | English | Spanish |
| --- | --- | --- |
| citizen_died | Citizen %s has died. | El ciudadano %s ha muerto. |
| guard_died | Guard %s has died. | El guardia %s ha muerto. |
| essential_workplace_empty | %s has no assigned workers. | %s no tiene trabajadores asignados. |
| significant_threat | A significant %s threat has been detected. | Se ha detectado una amenaza importante de %s. |
| construction_completed | Construction completed: %s. | Construcción terminada: %s. |
| upgrade_completed | Building upgraded to level %s. | Edificio mejorado al nivel %s. |
| threat.pillager | pillager | saqueadores |
| threat.bandit | bandit | bandidos |
| threat.undead | undead | muertos vivientes |

| Advancement key | English title / description | Spanish title / description |
| --- | --- | --- |
| converted_village | A New Allegiance / Convert a vanilla village into your kingdom. | Una nueva alianza / Convierte una aldea en tu reino. |
| first_warehouse | Room for More / Complete your kingdom's first warehouse. | Espacio para más / Termina el primer almacén de tu reino. |
| all_professions | A Working Community / Employ every operational profession in one kingdom. | Una comunidad activa / Asigna todas las profesiones operativas en un reino. |
| first_upgrade | Building on Success / Complete your first building upgrade. | Construir sobre el éxito / Termina tu primera mejora de edificio. |
| first_trade | A Fair Exchange / Complete your first successful kingdom trade. | Un intercambio justo / Completa tu primer comercio del reino. |
| development3 | A Flourishing Kingdom / Reach development level 3. | Un reino próspero / Alcanza el nivel de desarrollo 3. |

Advancement locale keys are `advancement.livingkingdoms.<key>.title` and `.description`. History UI labels and pagination are owned by the management screen integration.

## Validation ownership

`ChronicleSavedDataTest` covers bounds, persisted receipt dedupe, kingdom/kind scope, fail-closed corrupted loads, new-store creation beside legacy data, and bounded client pending notices. `ChronicleGameTests` covers an actual registered villager death through the production event hook, personal packets, no replay after history read/reload, and all native advancement criteria with repeated grants refused. Root runs coordinated Gradle unit tests, GameTests and build; this integration document does not claim they have already passed.
