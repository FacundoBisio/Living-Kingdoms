# v0.18 construction preview integration

Registration (main/common mod bus): `modBus.addListener(PreviewPayloads::register)`.
Physical client setup: `ConstructionPreviewClient.register(modBus)`. This installs its own keys and game-bus listeners; no manual Minecraft launch is necessary for registration.

After the existing VillageUiService verifies a **local mayor** UI session, call
`ConstructionPreviewService.begin(player, settlement.id(), BuildingKind.MARKET)` (or another supported kind).
The method returns false if no safe plot/authorization/queue capacity exists. On true, the native preview snapshot closes VillageScreen and shows the projection. Do not call it from shortcut, marker, board, arbitrary settlement UUID payloads, or unvalidated sessions. The capability may be confirmed away from the mayor while placing the plot; current joined standing, territory, alive/spectator state and build permission are revalidated on every update and confirmation.

Supported types: HOUSE, HOUSE_VARIANT, HOUSE_THIRD, FARM, BARRACKS, WATCHTOWER, WAREHOUSE, LUMBERJACK_HUT, MINE, BLACKSMITH, MARKET. Anchor/core and Town Hall remain in the original founding sequence. Existing ConstructionService signatures remain unchanged. New reserveWithMetadata is a shared transaction hook for converted villages: survey metadata is persisted only after the real marker transaction succeeds.

Controls are remappable in vanilla Controls: R rotates the same minimum corner, G moves that corner to the looked-at loaded ground (48-block ray), Enter confirms, Backspace cancels. Rotation and movement updates are server validated at most twice per second; a preview lasts 120 seconds. Green footprint means the last server survey was valid; red means invalid. Blue column marks the entry. Confirmation checks the actual current server world again and rejects changed floor height. Invalid previews ignore the confirm key. Costs are configured project costs; no material is consumed until the original construction funding flow.

Performance: at most 256 weakly-held connection sessions, one NBT-derived render list per active client with at most 1024 non-air cells, existing 15×12×15 native module validation, bounded existing terrain/path planner, no chunk tickets/forced loading, no world scans and no periodic server preview loop. Large modules use deterministic sampling solely for rendering; full native blueprint remains authoritative. Client world rendering uses the original rotated block states, translucent vertices, full light, and loaded nearby chunks. Native beds, chests (including ender chests), signs, banners, shulker boxes, skulls and bells use render-only block entities with their native atlases; atlas batches flush once per used type. Unsupported animated template blocks retain the footprint projection and do not invoke item renderers. Block-entity contents/text are not transmitted or installed. No saved blocks or fake client blocks are written by previews.

Translations to merge into en_us.json (Spanish equivalents immediately below):

```json
{
  "key.livingkingdoms.preview.rotate": "Rotate construction preview",
  "key.livingkingdoms.preview.move": "Move preview to targeted ground",
  "key.livingkingdoms.preview.confirm": "Confirm construction plot",
  "key.livingkingdoms.preview.cancel": "Cancel construction preview",
  "preview.livingkingdoms.title": "Construction preview: %s",
  "preview.livingkingdoms.valid": "Valid plot · confirmation checks again",
  "preview.livingkingdoms.invalid": "Invalid plot · check territory, clearance, slope and road access",
  "preview.livingkingdoms.cost": "Materials:",
  "preview.livingkingdoms.control": "%s — %s",
  "preview.livingkingdoms.entry": "Blue outline: entrance",
  "preview.livingkingdoms.simplified": "Large template: simplified visual preview",
  "preview.livingkingdoms.created": "Construction planned. Supply materials at the project marker.",
  "preview.livingkingdoms.rejected": "Plot changed or is unavailable. Open a new preview at the mayor."
}
```

```json
{
  "key.livingkingdoms.preview.rotate": "Girar vista previa de construcción",
  "key.livingkingdoms.preview.move": "Mover al terreno señalado",
  "key.livingkingdoms.preview.confirm": "Confirmar parcela de construcción",
  "key.livingkingdoms.preview.cancel": "Cancelar vista previa de construcción",
  "preview.livingkingdoms.title": "Vista previa: %s",
  "preview.livingkingdoms.valid": "Parcela válida · se verificará al confirmar",
  "preview.livingkingdoms.invalid": "Parcela inválida · revisá territorio, obstáculos, pendiente y acceso",
  "preview.livingkingdoms.cost": "Materiales:",
  "preview.livingkingdoms.control": "%s — %s",
  "preview.livingkingdoms.entry": "Contorno azul: entrada",
  "preview.livingkingdoms.simplified": "Plantilla grande: vista previa simplificada",
  "preview.livingkingdoms.created": "Construcción planificada. Aportá materiales en el marcador de obra.",
  "preview.livingkingdoms.rejected": "La parcela cambió o no está disponible. Abrí otra vista previa con el alcalde."
}
```

Tests added: PreviewSessionTest (identity/dimension/expiry), ConstructionPreviewGameTests (all native rotations and render budget; unjoined access refusal; converted-village preview has zero blocks/project/layout writes; cross-player and duplicate replay; confirmation creates a real unfunded MARKET project; changed block-entity obstruction and revoked build permission reject without mutation). Root agent coordinates Gradle validation. This audit ran `git diff --check`; visual graphics, texture/translucency and GUI scaling require user manual testing. No runClient or browser automation used.
