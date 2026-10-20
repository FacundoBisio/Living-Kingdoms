# Living Kingdoms 0.18.1: Argon 4 comparison and integration

## Prior inspection

`livingkingdoms_citizen_assets.zip` was inspected before modifying the project. File SHA-256: `6380d1c864396168d7df2d1007f83c3a6138cba60d083b801b3772a29b21b2bf`. The ZIP CRC check found no errors. It contains nine files: one geometry, one Blockbench project, three animation files and four 64×64 PNGs; the documentation, faces and clothing folders were empty. It includes no executable scripts or installation instructions. The file supplied by the user is the source of these resources; attributing it to Argon 4 repeats its own description, without claiming an external authorship or license that the file does not document.

`docs/argon4-source/manifest.json` keeps the origin and SHA-256 of each imported file. The `.bbmodel` project and the two base textures remain as reference sources in `docs/argon4-source/`. The original geometry and animation resources and the external builder and blacksmith textures are kept unaltered.

## Comparison

| Aspect | 0.18.0 humanoid | External resources and 0.18.1 integration |
|---|---|---|
| Entity and data | Existing `Villager`, registered identity and synced profession | The same entity, UUID, AI, navigation, inventory, profession and persistence |
| Model | Native `PlayerModel<Villager>` with independent arms and legs | Blockbench geometry with 14 bones and 24 cubes, adapted to the existing native parts |
| Appearance | 80 original skins: eight variants for ten roles | The same 80 identities and base clothing, with 80 derived textures for the geometric layers |
| Details | Hair, beards, clothing and circlet mostly painted | Hair, beard, cuffs, boots, collar, skirt, pouch and sash with volume; details enabled by variant or profession |
| Animation | Vanilla movement, attacks, sleep and equipment; short accents for real work | Nine unique external clips available; work chosen by real activity, keeping native movement where it covers more states |
| Tools | Native held items, real equipment takes priority | Separate external hammer for builder/blacksmith work, keeping real items and the other existing tools |
| Dependencies | Minecraft and NeoForge, no GeckoLib | No new dependency: bounded adaptation of the exported format to the native renderer |

The external geometry adds volume and editable timelines, but it is not better in every respect. The four supplied textures do not replace the variety of the 80 skins, and there is no complete set of external textures for every profession. The clips do not cover every vanilla state, nor do they on their own guarantee correct tools, equipment, sleep, mounts or combat. The integration keeps those behaviors from the previous model.

## UV and bone compatibility

The main head, torso, arm and leg faces use the standard 64×64 Minecraft player coordinates. That keeps every original identity and base-clothing pixel. The extra external cubes read secondary-layer areas that the 0.18.0 skins left transparent; the 0.18.1 generator fills those areas with colors derived from each skin, without modifying the six main regions.

The ZIP cannot be applied without adaptation: the beard reads face pixels, the sash shares UVs with the hair, and the hammer is permanently built into the `right_hand` bone. The adaptation remaps the beard and sash to auxiliary atlas areas and controls their visibility. It also raises the sash two pixels and the hair 0.75 model pixels so they do not cover the eyes; this geometric fix needs visual review in game. The hammer is excluded from the body and rendered separately when appropriate, so a resting citizen, a merchant or a guard does not get a fixed hammer.

The clips are exported in three JSON files with ten entries and nine unique names. `animation.citizen.work_hammer` appears twice with identical content. The other clips are `idle`, `walk`, `run`, `guard_patrol`, `guard_attack`, `guard_block`, `farm_plant` and `lumberjack_chop`, with the `animation.citizen.` prefix. The idle head rotation, the hammer, farming and chopping clips after confirmed progress, and the block clip when the citizen actually uses a shield are selected. Miner and Merchant keep their earlier accents. The locomotion and attack clips are kept and validated as resources, but the vanilla animations that already coordinate steps, equipment, mounts and sleep are kept. Body position tracks are not applied, to keep body, armor and hands aligned; so the adaptation does not reproduce every channel exactly as a GeckoLib player would. No animation runs economic actions or modifies AI.

The existing delegating renderer is reused. Only registered citizens get the humanoid presentation; vanilla villagers outside the system keep their usual renderer. Labels, inspection, trading, the Mayor menu and construction previews keep using their earlier systems. There is no persistent schema change or entity conversion.

## Checks and limits

The resource tests check finite geometry, an acyclic hierarchy, bone references, UVs within 64×64, the nine clips and the equality of the duplicated entry. They check the 80 derived skins and their main regions pixel by pixel against the originals. They also verify the SHA-256 of the four external textures and that the two profession textures are packaged. The generator and the artifact validation complement these checks.

These tests do not validate the appearance inside Minecraft. The graphical client is not run or controlled during this task. The adaptation is specific to these resources: it does not aim to import arbitrary GeckoLib models or Molang expressions. JUnit, GameTest and build results must be read from the delivery log, without inferring that passing server tests equals validated rendering.

## Pending manual comparison

Use `/kingdommodels native` and `/kingdommodels argon4` on the same citizen and world to compare the previous model with Argon 4 without restarting or opening another save. These local commands store the client preference. You can also set `citizenPresentation.model = "NATIVE"` or `"ARGON4"` in `config/livingkingdoms-citizen-visuals.toml`; `ARGON4` is the default. The selection does not change kingdom resources, profession or entity. Keep a copy of the world for the compatibility checks.

1. Compare citizens of the ten professions and eight variants: face, hairstyle, beard, collar, boots, clothing and the Mayor's differences. Check UV seams and surfaces clipping through each other.
2. Watch walking, running, turning, sleeping, mounting and resting. No hammer or work motion should appear during rest.
3. Watch confirmed production by Farmer, Lumberjack, Miner and Blacksmith, and a real Builder construction or repair. Check that hands, tool and target match visually; test left-handed citizens.
4. Check armed and unarmed guards, sword, bow, shield in use and armor. Equipment must match the real inventory. Test invisibility and hiding the HUD.
5. Test labels at short and long distance, twenty or more visible citizens and different GUI scales; check frame times with and without the new model.
6. Open trading, storage, Mayor and inspection; start a 3D preview and cancel it. Save and load a copy of an older world, and check names, professions and XP.
7. With two clients, select different models and check that the observed activity and equipment still match the same server state.
