# 0.18 citizen rendering

The implementation retains each existing vanilla `Villager`: UUID, saved entity type, Brain/navigation, trade career, equipment, inventory, housing and citizen/profession records stay authoritative. Only active registered citizens accepted by `CitizenService.canApply` receive presentation packets. NPC tags alone, ordinary villagers and unrelated mobs cannot opt themselves into the renderer. Mayors use the same registered identity path.

## Renderer decision

| Option | Advantages | Cost for this project |
|---|---|---|
| Native 1.21.1 `PlayerModel<Villager>` / `MobRenderer` | Separate arms/legs, vanilla walking, idle motion, attacks, riding, death, bed orientation, armor and held-item layers; existing entity remains intact | Procedural work accents are authored in Java; no external animation timeline editor |
| GeckoLib 4 for 1.21.1 | Authored bone/keyframe animations and controller system; NeoForge installation path exists | Additional dependency, geo/animation assets, per-entity animation state and replacement-renderer integration must be maintained |

Native rendering is sufficient for the required humanoid presentation, so no dependency or loader changes were made. The [GeckoLib 4 version support table](https://wiki.geckolib.com/docs/geckolib4/) lists 1.21.1 with GeckoLib 4.8.3. The [GeckoLib 4 installation guide](https://github.com/bernie-g/geckolib/wiki/Installation-%28Geckolib4%29) documents its NeoForge artifact. Its [entity guide](https://github.com/bernie-g/geckolib/wiki/Geckolib-Entities-%28Geckolib4%29) describes animatable instances, controllers and geo renderers. These capabilities were compared as an alternative; GeckoLib was neither installed nor compiled here, and its current API examples were not copied into this pinned project.

Native signatures and behavior were inspected in the project's generated `build/moddev/artifacts/neoforge-21.1.252-sources.jar`: `MobRenderer`, `LivingEntityRenderer`, `PlayerModel`, `HumanoidModel`, `ItemInHandLayer`, `HumanoidArmorLayer` and `RenderLivingEvent`. Pins remain Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. There was no cached GeckoLib dependency to validate against locally.

`EntityRenderersEvent.AddLayers` creates one delegated humanoid renderer using baked vanilla player/armor layers. `RenderLivingEvent.Pre` delegates only synchronized citizens, leaves the global `EntityType.VILLAGER` renderer unchanged and guards against recursive delegation. Untracked, expired or unloaded citizens fall back to the existing native renderer. The humanoid mayor has an original gold circlet in its head overlay; the older villager circlet layer remains available with the native fallback and does not double-render.

## Appearance and truthful activity

`tools/generate_citizen_assets.py` authors 80 original 64×64 RGBA skins: eight stable UUID-selected variants for each of ten professions. All pixels are generated from local palettes and UV drawing code, with no downloaded Minecraft/player artwork. Profession change changes clothing while preserving identity variant. Clothing includes farmer overalls, work aprons, guard/captain armor colors, merchant/mayor coats, lumberjack plaid and miner workwear. The source generator is reproducible with `--check`.

Native walking, idle movement, real attacks, riding and death remain in the vanilla model/renderer pipeline. Sleep uses the entity's real native sleeping pose and bed direction, with resting limbs, hidden props and labels. A short ten-tick profession accent requires a server-observed XP increase for the same profession, active work/repair state and no navigation travel. The first tracking packet and heartbeat do not invent a work action. The renderer also checks actual walking speed and respects native main-arm handedness. No animation completes tasks or modifies the world.

Profession props are immutable renderer-owned `ItemStack`s passed directly to the held-item layer. They never enter entity equipment/inventory or loot. Economic/farm/build/merchant props require an active, stationary work/repair state. The mayor can carry a book while active. Guards and captains display their existing actual persisted weapons and offhand items; an unarmed guard receives no decorative sword. Real equipment takes precedence over a prop.

Confirmed work may emit existing vanilla crop/anvil/wood/stone sounds server-side at volume 0.22, at most once per 40 ticks per citizen. There is no client render-frame sound loop. No new sound assets/events were added.

## Labels and inspection

The native depth-tested name label contains the actual saved citizen name. The second line contains translated profession and shared citizen level, with ten-pixel line spacing. Native name/team/invisibility rules, hidden GUI, sleep and client toggles apply. Defaults: name within 24 blocks, secondary line within 12; secondary distance is capped by name distance and zero hides it. The client config is `livingkingdoms-citizen-visuals.toml`, including humanoid, label and work-accent switches.

Use the existing remappable Use action on a citizen to open a read-only activity panel. Normal mayor and merchant clicks retain their existing dialogue/trade behavior; Sneak + Use inspects them. Server checks the real entity, life state, six-block distance and line of sight before opening. The panel closes after entity unload/death/world change or when farther than eight blocks. It shows real name, shared level, profession level/XP, work/travel state, assignment, workplace and housing. Native buttons, wrapped text, clipping, pagination, scroll and narration support GUI scaling. It issues no gameplay mutation payload.

## Bounded work and verification scope

Server work is one indexed lookup per loaded managed villager per 20 ticks, distributed by entity ID; snapshots send only on change or every 200 ticks. Each snapshot codec is under 512 bytes in the exhaustive profession/state test. Tracking packets go only to tracking players. The bounded server history holds at most 4096 citizens. Both client snapshot and derived-view maps hold at most 1024 entries, with UUID + runtime-ID checks and a 400-tick snapshot lifetime. Logout, client-level replacement, entity unload and stop-tracking clear entries. Rendering performs no world/entity scan, forced chunk load or server mutation.

Unit tests cover identity/skin stability, work pulse truth, prop eligibility, cache pressure/expiry/ID reuse, bounded codecs and untracking UUID. The physical GameTest checks ordinary villagers, tag-only impostors, actual citizen/profession projection, native NBT identity/AI/equipment/drop preservation and rejection after conflicting metadata/death. Root coordinates Gradle test, GameTest and build results for this shared branch.

Graphical Minecraft was not launched or controlled. The PNG contact sheet is an asset preview, not proof of in-game rendering. Manual checks remain for skin seams, held-item alignment, bed position, armor, shader/resource-pack interactions, crowded labels, GUI scales and real multiplayer observation. No visual/runtime client validation is claimed.
