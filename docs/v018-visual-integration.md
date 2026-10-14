# 0.18 citizen visual integration

Common mod registration: `modBus.addListener(CitizenVisualPayloads::register)` and `CitizenVisualService.register(NeoForge.EVENT_BUS)`.

Physical client registration from `VillageClient`: `CitizenVisualClient.register(modBus, container)`. Client classes are not referenced by the common visual service/payload/domain. The client registers its config, delegated renderer, tracking cache, interaction prediction and disconnect hooks itself. Existing citizen identity, profession persistence, mayor dialogue, merchant commerce and native use/sneak controls are reused.

Merge these entries into the root-owned `en_us.json`:

```json
{
  "citizen.livingkingdoms.title": "Citizen activity",
  "citizen.livingkingdoms.label": "%s · Level %s",
  "citizen.livingkingdoms.identity": "%s · Citizen level %s",
  "citizen.livingkingdoms.profession_level": "Profession level %s · %s XP",
  "citizen.livingkingdoms.activity": "Activity: %s",
  "citizen.livingkingdoms.assignment": "Assignment: %s",
  "citizen.livingkingdoms.workplace": "Workplace: %s",
  "citizen.livingkingdoms.home": "Housing: %s",
  "citizen.livingkingdoms.travelling": "Travelling",
  "citizen.livingkingdoms.active": "Active",
  "citizen.livingkingdoms.inactive": "Inactive",
  "citizen.livingkingdoms.none": "None",
  "citizen.livingkingdoms.housed": "Home assigned",
  "citizen.livingkingdoms.unhoused": "No home assigned",
  "citizen.livingkingdoms.next": "Next"
}
```

Merge into `es_ar.json`:

```json
{
  "citizen.livingkingdoms.title": "Actividad del ciudadano",
  "citizen.livingkingdoms.label": "%s · Nivel %s",
  "citizen.livingkingdoms.identity": "%s · Nivel ciudadano %s",
  "citizen.livingkingdoms.profession_level": "Nivel de profesión %s · %s XP",
  "citizen.livingkingdoms.activity": "Actividad: %s",
  "citizen.livingkingdoms.assignment": "Asignación: %s",
  "citizen.livingkingdoms.workplace": "Lugar de trabajo: %s",
  "citizen.livingkingdoms.home": "Vivienda: %s",
  "citizen.livingkingdoms.travelling": "En viaje",
  "citizen.livingkingdoms.active": "Activa",
  "citizen.livingkingdoms.inactive": "Inactiva",
  "citizen.livingkingdoms.none": "Ninguno",
  "citizen.livingkingdoms.housed": "Hogar asignado",
  "citizen.livingkingdoms.unhoused": "Sin hogar asignado",
  "citizen.livingkingdoms.next": "Siguiente"
}
```

Existing `profession.livingkingdoms.*`, `work.livingkingdoms.*` and `construction.livingkingdoms.building.*` keys provide the remaining values. No independent UI keybinds or custom sound assets are required.

See `v018-citizen-rendering.md` for native-vs-GeckoLib reasoning, cache/network costs, non-mutating props, compatibility constraints and remaining manual visual checks. Gradle validation is coordinated by root; this agent performed source/API and static asset inspection without launching a graphical client.
