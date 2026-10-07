# Small allied settlement template

The Milestone 1 settlement is a technical placeholder. It contains a Town Hall,
three houses, a Blacksmith, a Barracks, a central marker, and the Quest Board.
The village is an exploration and RPG hub; these buildings do not manage workers
or implement a colony economy.

Runtime asset: `data/livingkingdoms/structure/allied/test_settlement.nbt`.
Template identifier: `livingkingdoms:allied/test_settlement`.
This is a gzip-compressed **vanilla Minecraft structure template**, with one
`palette`, `blocks`, an empty `entities` list, `size`, and `DataVersion = 3955`
(Java Edition 1.21.1). It is not a WorldEdit `.schem` file.

## Footprint and local coordinates

The template size is exactly **31 × 7 × 31 blocks**. Its origin `(0, 0, 0)` is
the northwest corner of its foundation. Positive X is east; positive Z is south.
All coordinates below are local, before translation into world coordinates.
For a world origin `(ox, oy, oz)`, the marker is `(ox + 15, oy + 1, oz + 15)`.
The generator places the foundation above the highest original ground column;
the player walks one block above it. Lower columns receive supports, with no
excavation of the original ground.

| Feature | X range | Z range | Entrance / identifying block |
| --- | --- | --- | --- |
| Town Hall | 11–19 | 2–8 | South door at `(15, 1, 8)` |
| House 1 | 2–6 | 2–6 | South door at `(4, 1, 6)` |
| House 2 | 24–28 | 2–6 | South door at `(26, 1, 6)` |
| House 3 | 2–6 | 10–14 | East door at `(6, 1, 12)` |
| Blacksmith | 22–28 | 10–16 | West door at `(22, 1, 13)` |
| Barracks | 11–19 | 22–28 | North door at `(15, 1, 22)` |
| Central marker | 15 | 15 | Exactly one lodestone at `(15, 1, 15)` |
| Quest Board | 18 | 15 | Exactly one `livingkingdoms:quest_board` at `(18, 1, 15)`, facing west |

The full contiguous foundation at Y = 0 is cobblestone, with oak plank building
floors and a stone brick plaza. Walls are three blocks high. Shallow spruce roofs
and the Blacksmith chimney fit within Y = 6. Glass windows, wooden doors, six
labeled signs, and four lantern posts make the layout legible. The Blacksmith has
an unlit furnace at `(27, 1, 12)` facing west, an anvil at `(26, 1, 12)` facing
north, and a crafting table at `(24, 1, 15)`. The Barracks contains three beds.

Sign positions are useful for checking block-entity placement:

| Label | Position | Facing |
| --- | --- | --- |
| Town Hall | `(13, 2, 9)` | south |
| House 1 | `(3, 2, 7)` | south |
| House 2 | `(25, 2, 7)` | south |
| House 3 | `(7, 2, 11)` | east |
| Blacksmith | `(21, 2, 11)` | west |
| Barracks | `(13, 2, 21)` | north |

Each sign uses Java 1.21.1 `front_text` and `back_text` compounds, four JSON text
components per side, black text, and a waxed state. Its front displays
`Living Kingdoms` on line 1 and the label above on line 2.

Every one of the **6,727 positions** is represented, including explicit air
above the foundation. Site validation must protect the entire volume before
placement: the air entries can clear existing blocks. There are no saved
entities, chest loot, redstone, lava, or fire. The template defines no natural
world generation frequency or biome registration by itself.

## Rebuild the placeholder

Python 3.10+ with its standard library is sufficient; no NBT library or WorldEdit
installation is needed:

```text
python tools/generate_settlement_template.py
python tools/generate_settlement_template.py --check
```

The script resolves the repository relative to its own location, so it can run
from another directory. `--output <path>` selects an alternate destination.
`--check` compares the existing bytes with a fresh generation without writing.
The gzip header has a fixed timestamp and no filename; palette and block order
are stable. Repeated generation with the same Python/zlib runtime produces the
same bytes. The Java build consumes the checked-in binary and does not require
Python at runtime or during an ordinary build.

## Replace with a WorldEdit-designed build

WorldEdit, JourneyMap, and JEI belong in the development profile only. No runtime
loading or placement code should import them. WorldEdit's clipboard can load and
paste saved schematics; see the official
[clipboard documentation](https://worldedit.enginehub.org/en/latest/usage/clipboard/).
Use a vanilla structure block as the final export step:

1. Build or paste the village in a disposable creative Java 1.21.1 development
   world with Living Kingdoms installed. For a saved WorldEdit schematic, use
   `//schematic load <name>`, then `//paste`. Choose an empty staging area and keep
   the foundation's minimum corner at a known coordinate `(ox, oy, oz)`.
2. Keep this prototype's size, complete solid foundation, marker, Quest Board,
   and their local coordinates unchanged. Replacing these contracts requires a
   matching update to the template descriptor and its placement tests. Keep the
   saved area free of entities, hazards, loot, and unrelated blocks.
3. Obtain a structure block with `/give @s minecraft:structure_block`. Place it
   outside the selected volume, for example at `(ox - 1, oy, oz - 1)`. In **SAVE**
   mode use name `livingkingdoms:allied/test_settlement`, relative position
   `(1, 0, 1)`, and size `(31, 7, 31)`. Disable **Include entities**. Confirm the
   bounding box includes the foundation and roof but excludes the structure block.
4. Click **SAVE** in the structure block UI to write the file to the world save:
   `generated/livingkingdoms/structures/allied/test_settlement.nbt`. The generated
   world's directory is plural `structures`; the 1.21.1 mod resource directory is
   singular `structure`.
5. Copy that `.nbt` into
   `src/main/resources/data/livingkingdoms/structure/allied/test_settlement.nbt`.
   Do not rename a `.schem` to `.nbt`: their formats differ. Reload the development
   resources or restart the game, run the settlement command, and rerun the
   placement GameTests before committing.

Minecraft checks a world's `generated/<namespace>/structures` templates before
the packaged mod resources. The export in the design world therefore shadows
the packaged file with the same identifier. Validate the packaged replacement
in a fresh test world, or back up and move the generated export out of the design
world after copying it; then reload resources/restart to clear the template cache.

The procedural Python script documents the temporary template. Once a hand-built
export replaces it, update or retire the script's `--check` expectation instead
of accidentally regenerating the old placeholder over the designed asset.

Natural generation can later reuse the same template descriptor and server-side
placement path. Authoring tools do not become runtime dependencies.
