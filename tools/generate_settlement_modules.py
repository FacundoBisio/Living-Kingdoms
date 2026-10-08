#!/usr/bin/env python3
"""Generate independent native modules; leaves the legacy settlement asset unchanged."""
import argparse
import gzip
import io
from pathlib import Path
import generate_settlement_template as legacy


def encode(builder, size):
    entries = []
    for position, state in sorted(builder.blocks.items(), key=lambda pair: (pair[0][1], pair[0][2], pair[0][0])):
        entry = {"pos": (legacy.LIST, (legacy.INT, position)), "state": (legacy.INT, state)}
        if position in builder.block_entities:
            entry["nbt"] = (legacy.COMPOUND, builder.block_entities[position])
        entries.append(entry)
    root = {"DataVersion": (legacy.INT, legacy.DATA_VERSION), "size": (legacy.LIST, (legacy.INT, size)),
            "palette": (legacy.LIST, (legacy.COMPOUND, builder.palette)),
            "blocks": (legacy.LIST, (legacy.COMPOUND, entries)), "entities": (legacy.LIST, (legacy.COMPOUND, []))}
    raw = bytes([legacy.COMPOUND]) + legacy.string("") + legacy.payload(legacy.COMPOUND, root)
    output = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=9, mtime=0) as compressed:
        compressed.write(raw)
    return output.getvalue()


def put(b, x, y, z, name, **props):
    b.put(x, y, z, "minecraft:" + name, **props)


def stair(b, x, y, z, facing, material="spruce"):
    put(b, x, y, z, material + "_stairs", facing=facing, half="bottom", shape="straight", waterlogged="false")


def lantern(b, x, y, z):
    put(b, x, y, z, "lantern", hanging="false", waterlogged="false")


def bed(b, x, z, color="white"):
    for dz, part in ((0, "head"), (1, "foot")):
        put(b, x, 1, z + dz, color + "_bed", facing="north", occupied="false", part=part)


def frame(b, x0, z0, x1, z1, stone=False, cross=False, tall=False):
    # Walls are inset from the reserved plot: eaves never trespass onto a path.
    beam = 5 if tall else 4
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            put(b, x, 0, z, "spruce_planks")
            if x not in (x0, x1) and z not in (z0, z1):
                continue
            for y in range(1, beam + 1):
                post = x in (x0, x1) and z in (z0, z1)
                material = "stripped_oak_log" if post else ("stone_bricks" if y == 1 or stone else "oak_planks")
                if y == beam: material = "spruce_log"
                put(b, x, y, z, material, **({"axis": "y" if post else "x"} if "log" in material else {}))
    for x, z in ((x0, (z0+z1)//2), (x1, (z0+z1)//2), ((x0+x1)//2, z0)):
        put(b, x, 2, z, "glass_pane", north="true", south="true", east="true", west="true", waterlogged="false")
    for y, half in ((1, "lower"), (2, "upper")):
        put(b, (x0+x1)//2, y, z1, "spruce_door", facing="south", half=half, hinge="left", open="false", powered="false")
    # Full gables and steep stair courses, ending in a narrow ridge.
    low, high = (z0-1, z1+1) if cross else (x0-1, x1+1)
    for x in range(x0-1, x1+2):
        for z in range(z0-1, z1+2):
            axis = z if cross else x
            rise = min(axis-low, high-axis)
            roof = beam + rise
            if x in (x0, x1) or z in (z0, z1):
                for y in range(beam, roof): put(b, x, y, z, "oak_planks")
            if axis == (low+high)//2:
                put(b, x, roof, z, "dark_oak_slab", type="bottom", waterlogged="false")
            else:
                facing = ("south" if axis < (low+high)/2 else "north") if cross else ("east" if axis < (low+high)/2 else "west")
                stair(b, x, roof, z, facing, "dark_oak" if tall else "spruce")
    lantern(b, x0+1, 1, z1-1)


def module(kind, width, depth, height):
    size = (width, height, depth)
    legacy.SIZE = size
    b = legacy.SettlementTemplate()
    for x in range(width):
        for z in range(depth):
            put(b, x, 0, z, "mossy_cobblestone" if (x*7+z*11)%19 == 0 else "cobblestone")
    if kind == "core":
        frame(b, 2, 1, 10, 6, tall=True)
        for x in range(width):
            for z in range(8, depth):
                put(b, x, 0, z, "chiseled_stone_bricks" if (x,z) in ((6,9),(3,9),(9,9)) else "stone_bricks")
        b.put(6, 1, 9, "minecraft:lodestone")
        b.put(9, 1, 9, "livingkingdoms:quest_board", facing="west")
        b.sign(4, 2, 7, "south", "Town Hall")
        put(b, 4, 2, 6, "stripped_oak_log", axis="y")
        for x in (0,12):
            for y in (1,2): put(b,x,y,7,"spruce_fence")
            lantern(b,x,3,7)
        for z in (8,10):
            for y in (1,2): put(b,10,y,z,"spruce_fence")
        for x in (9,10,11):
            for z in (8,9,10): put(b,x,3,z,"spruce_slab",type="bottom",waterlogged="false")
        for x in (2,3): stair(b,x,1,11,"north")
        put(b,11,1,11,"barrel",facing="up",open="false")
        for x in (3,9):
            put(b,x,3,7,"blue_wall_banner",facing="south")
            stair(b,x,1,3,"east" if x==3 else "west")
        for z in (2,3,4): put(b,6,1,z,"oak_slab",type="top",waterlogged="false")
        put(b,8,1,2,"bookshelf")
        put(b,3,1,2,"crafting_table")
        lantern(b,6,2,3)
    elif kind == "watchtower":
        for x in (1,5):
            for z in (1,5):
                for y in range(1,9): put(b,x,y,z,"stone_bricks" if y<3 else "spruce_log",**({"axis":"y"} if y>=3 else {}))
        for x in range(1,6):
            for z in range(1,6):
                put(b,x,7,z,"spruce_planks")
                if x in (1,5) or z in (1,5): put(b,x,8,z,"spruce_fence")
        put(b,3,7,2,"air")
        for y in range(1,9): put(b,3,y,2,"ladder",facing="south",waterlogged="false")
        for y in range(1,9): put(b,3,y,1,"stripped_oak_log",axis="y")
        for x in range(7):
            for z in range(7):
                rise=min(x,6-x)//2
                put(b,x,9+rise,z,"spruce_slab",type="bottom",waterlogged="false")
        lantern(b,1,9,3)
        put(b,5,6,3,"blue_wall_banner",facing="east")
    else:
        frame(b,1,1,width-2,depth-2,stone=kind=="blacksmith",cross=kind=="house_variant",tall=kind=="town_hall")
        put(b,width-3,1,2,"barrel",facing="up",open="false")
        if kind.startswith("house"):
            bed(b,2,2,"white" if kind=="house" else "light_blue")
            put(b,width-3,1,depth-3,"oak_slab",type="top",waterlogged="false")
            put(b,width-3,2,depth-3,"flower_pot")
            put(b,0,1,2,"composter")
            put(b,0,1,3,"azalea_leaves",persistent="true",distance="1",waterlogged="false")
            if kind=="house_third":
                for y in range(2,9): put(b,width-2,y,2,"stone_bricks")
                put(b,width-2,9,2,"stone_brick_wall")
                put(b,width-1,1,4,"hay_block",axis="y")
        if kind=="blacksmith":
            # Open front-side arcade with sheltered working space.
            for z in range(2,depth-2):
                for y in (1,2,3): put(b,width-2,y,z,"air")
            put(b,5,1,1,"furnace",facing="south",lit="false")
            for y in range(2,9): put(b,5,y,1,"stone_bricks")
            put(b,5,9,1,"stone_brick_wall")
            put(b,4,1,3,"anvil",facing="north")
            put(b,2,1,2,"smithing_table")
            put(b,6,1,4,"grindstone",face="floor",facing="south")
        if kind=="barracks":
            for x in (2,4,6): bed(b,x,2,"red")
            for x in (0,width-1):
                put(b,x,1,depth-3,"target")
                put(b,x,2,depth-3,"spruce_fence")
                put(b,x,3,depth-3,"blue_wall_banner",facing="south")
            put(b,width-2,1,depth-1,"spruce_fence")
        if kind=="town_hall":
            for z in (2,3,4): put(b,width//2,1,z,"oak_slab",type="top",waterlogged="false")
            for x in (2,width-3):
                stair(b,x,1,3,"east" if x==2 else "west")
                put(b,x,3,depth-1,"blue_wall_banner",facing="south")
    return encode(b, size)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "src/main/resources/data/livingkingdoms/structure/allied/plains"
    for kind, width, depth, height in (("core", 13, 13, 11), ("town_hall", 11, 9, 11), ("house", 7, 7, 9),
                                       ("house_variant", 7, 9, 10), ("house_third", 9, 7, 10),
                                       ("blacksmith", 7, 7, 10), ("barracks", 9, 9, 10), ("watchtower", 7, 7, 12)):
        result = module(kind, width, depth, height)
        path = root / f"{kind}.nbt"
        if args.check:
            if not path.is_file() or path.read_bytes() != result:
                raise SystemExit(f"Module differs or is missing: {path}")
        else:
            root.mkdir(parents=True, exist_ok=True)
            path.write_bytes(result)
        print(f"{'Verified' if args.check else 'Wrote'} {kind}: {width}x{height}x{depth}, {len(result)} bytes")


if __name__ == "__main__":
    main()
