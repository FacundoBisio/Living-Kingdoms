# 0.18 commerce integration

Implemented by commerce agent; root owns final registration and shared UI/localization files.

## Required hooks

* Mod constructor: `container.registerConfig(ModConfig.Type.SERVER, CommerceConfig.SPEC, "livingkingdoms-commerce.toml");`
* Mod bus: `modBus.addListener(CommercePayloads::register);`
* Game event bus: `MerchantWork.register(NeoForge.EVENT_BUS); CommerceService.register(NeoForge.EVENT_BUS);`
* Client setup: `CommerceScreen.register();`; disconnect: `CommerceScreen.clear();`.
* Assignment action: `ProfessionService.assignMerchant(player, settlementId, citizenId)`.
* Roster eligibility: `ProfessionService.canAssignOperational(level,citizen,profile,buildings,jobs,ProfessionType.MERCHANT)`.
* Ordinary merchant click opens commerce. Shift-click is intentionally left to citizen inspection. Client citizen visual prediction must suppress native trading for synchronized MERCHANT profiles.
* Call `CommerceService.dismiss(player)` when opening a competing management/journal/inspection screen, and on disconnect if desired. Weak player keys bound session retention; 1200-tick sessions reject expired requests.
* Commerce opening already dismisses VillageUiService and QuestJournalService sessions. Root should also dismiss citizen inspection if it issues a mutation capability.

## Behavior and cost

Merchant is an original operational profession, independent of vanilla villager careers and economic production. Existing citizen identity, housing, persisted profession XP, career memory and work/home/threat routine are reused. Market supplies finite shared quotas, persistent emerald treasury and actual KingdomEconomy goods. One-time configurable treasury defaults to 32 emeralds. Restock renews quotas only; it does not create money or goods. Prices per canonical item have a strict buy/sell spread. Trade consumes emeralds even in creative; selling damaged/component-modified items is refused. Food sales preserve healthy and pledged reserves. Restocking is lazy when market is opened/refreshed/used, once per configured interval with no offline catch-up multiplication.

Each merchant uses the existing loaded entity tick pattern once per second, bounded physical barrel/lectern validation and native navigation. No global merchant scan, forced chunks, offline production, or periodic network snapshot. There are at most 256 active player capabilities. Each request operates on five fixed offers, 37 carried inventory slots and one settlement account; session tokens, one-use quote tokens, employment revisions, market/economy revisions, the complete food-stock snapshot (including community pledges), dimension, distance, home, physical market, working hours, threat, permissions and death are revalidated server-side. No trade quantity or price supplied by client.

The existing CitizenEvents fallback home guidance now excludes assigned merchants. Existing saved-data schemas are retained; `livingkingdoms_commerce` is a new strict, versioned, guarded store. MARKET gains merchant slots through ProfessionSavedData synchronization; no building template changes required.

## Translation entries

| Key after `commerce.livingkingdoms.` | en_us | es_ar |
|---|---|---|
| title | Kingdom market | Mercado del reino |
| balance | Treasury: %s emeralds · Restock: %s min | Tesoro: %s esmeraldas · Reposición: %s min |
| buy | Buy: %s | Comprar: %s |
| sell | Sell: %s | Vender: %s |
| buy_help | Buy one item for emeralds. Available: %s. Requires room and unreserved kingdom stock. | Comprá una unidad con esmeraldas. Disponible: %s. Requiere espacio y reservas libres del reino. |
| sell_help | Sell one pristine item for emeralds. Demand: %s. Requires treasury and storage room. | Vendé una unidad sin modificar por esmeraldas. Demanda: %s. Requiere tesoro y espacio en el almacén. |
| unavailable | The merchant needs a working market, a home, safety and working hours. | El comerciante necesita un mercado funcional, hogar, seguridad y horario de trabajo. |
| success | Trade completed. | Intercambio realizado. |
| stale | Stock changed. Offers refreshed. | Cambiaron las reservas. Ofertas actualizadas. |
| rejected | Trade unavailable. Check inventory and stock. | Intercambio no disponible. Revisá inventario y reservas. |

Existing resource/profession keys are reused. Root adds merchant assignment and market planning labels in its owned shared UI files.

## Validation

Added `MarketAccountTest` for quota, treasury, restock, malformed persistence, missing owner and overflow boundaries. `CommerceInventoryTest` covers canonical components, damaged equipment, offhand payment, null-owner inventory fixtures and full-inventory exchanges. Added `CommerceGameTests` for real immigrant assignment, native market station, buy/sell inventory/stock conservation, quote replay/cross-player theft/stale concurrent and food quotes, healthy and pledged food preservation, full inventory refusal, NBT/disk reload, lazy restock, destroyed station invalidation and removal. Parent owns coordinated Gradle runs; this audit ran `git diff --check` and did not run Gradle or graphical Minecraft/browser tests.
