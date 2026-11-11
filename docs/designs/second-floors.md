# Second floors (design only, not applied)

Status: **design + prototypes**. Nothing in this folder is loaded by the mod; the `.nbt` files here live outside
`src/main/resources` on purpose. Preview: `second-floors-preview.png` (front and side elevations).

## Goal

A house that grows *up* instead of out. A grown kingdom on a tight plot should not need new land to add residents,
and a second storey is the most readable "this home is better now" signal in Minecraft (pillar: every claim is
celebrated).

## Rules

- **Same footprint and door.** The upgrade keeps `size.x`, `size.z`, the south-center door and the y=0 floor, so
  layout metadata, ports, paths and the claim never change. Only `size.y` grows by 3 (house 9 → 12, variant
  10 → 13, third 10 → 13). `BuildingTemplate.validate` must raise the height cap from 12 to 13 for this stage only,
  or the variant roofs must lose one layer.
- **Ground storey is untouched.** Walls `y1..3` are copied as-is: beds, chests, the job block and the building
  ledger keep their positions, so residents, Kingdom Storage links and the ledger survive the rebuild.
- **New storey:** plank floor at `y4` (stripped log rim), walls `y5..6` with log corners and glass panes on the
  third column, then the original roof shifted up by 3. A ladder in the inner north-west corner (`x0+1, z0+1`)
  climbs from `y1` through a hole in the new floor. One extra bed and a lantern upstairs.
- **Capacity:** +1 resident (the extra bed). Housing counts beds already, so no new counter is needed.

## Gameplay

- Unlocks at **house level 3** (after the two material tiers from 0.35) and **kingdom rank Town**.
- Cost: 96 planks, 32 logs, 8 glass, 1 bed, 24 emeralds (tunable in `BuildingUpgrades`).
- Built by the builder like any other stage (scaffold + progress), not instantly: it is a big visible project.
- Ledger row: "Second floor — +1 resident". Celebration: the usual upgrade ceremony plus a firework.
- A natural chapter mission later: "Raise two second floors".

## Implementation notes (when applied)

1. Generate real stage templates `allied/plains/upgrade/<house>_floor2.nbt` from the base templates with the
   prototype script approach (copy `y<=3`, insert storey, shift roof).
2. Before placing: clear `y4..sy+3` inside the footprint (old roof would otherwise leave stairs in the room) and
   move non-player entities standing above `y3` to the doorstep, as `FoundersLodge.convert` does.
3. Containers on the ground storey are preserved by placing with `StructurePlaceSettings` that skip air
   (`setIgnoreEntities(true)` plus a processor that keeps existing block entities for `y<=3`).
4. Chunk check: both footprint chunks loaded, otherwise retry on the next ensure tick (same as the lodge).
5. Persist only the stage index in the existing building upgrade data (no schema bump needed).

## Known issues in the prototypes

- `house_third` has an outside chimney; shifting the roof leaves a 3-block gap under the chimney top. The real
  template must extend the chimney column through `y4..6` or StructureQa will flag detached blocks.
- Window placement uses a `(x+z) % 3` rule; hand-tune per template so no pane sits next to a corner log.
- Height 13 exceeds the current validator cap of 12 for the two taller houses (see Rules).
