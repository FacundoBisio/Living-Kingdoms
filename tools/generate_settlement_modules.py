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
                put(b, x, y, z, material, **({"axis": "y" if post else "x" if z in (z0,z1) else "z"} if "log" in material else {}))
    for x, z in ((x0, (z0+z1)//2), (x1, (z0+z1)//2), ((x0+x1)//2, z0)):
        side = x in (x0,x1)
        put(b, x, 2, z, "glass_pane", north=str(side).lower(), south=str(side).lower(),
            east=str(not side).lower(), west=str(not side).lower(), waterlogged="false")
        # Small vanilla shutters sit in the reserved one-block eave margin.
        dx,dz,facing = (-1,0,"west") if x==x0 else (1,0,"east") if x==x1 else (0,-1,"north")
        put(b,x+dx,2,z+dz,"spruce_trapdoor",facing=facing,half="bottom",open="true",powered="false",waterlogged="false")
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
            # Stair courses previously met only diagonally between gable ends.
            # Continuous boarding joins every course to the walls, including the ridge.
            if rise > 0 and b.palette[b.blocks[(x,roof-1,z)]]["Name"][1] == "minecraft:air":
                put(b,x,roof-1,z,"spruce_planks")
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
            for y in (1,2): put(b,11,y,z,"spruce_fence")
        for x in (9,10,11):
            for z in (8,9,10): put(b,x,3,z,"spruce_slab",type="bottom",waterlogged="false")
        put(b,11,2,9,"lantern",hanging="true",waterlogged="false")
        for x in (2,3): stair(b,x,1,11,"north")
        put(b,11,1,11,"barrel",facing="up",open="false")
        for x in (3,9):
            put(b,x,3,7,"blue_wall_banner",facing="south")
            stair(b,x,1,3,"east" if x==3 else "west")
        for z in (2,3,4): put(b,6,1,z,"oak_slab",type="top",waterlogged="false")
        put(b,8,1,2,"bookshelf")
        put(b,3,1,2,"crafting_table")
        lantern(b,6,2,3)
    elif kind == "founding_camp":
        for x in range(width):
            for z in range(depth): put(b, x, 0, z, "spruce_planks")
        b.put(4, 1, 4, "minecraft:lodestone")
        b.put(7, 1, 4, "livingkingdoms:quest_board", facing="west")
        # A canvas shelter, two beds, and supplies; plaza NPC positions stay clear.
        for x in (0, 3):
            for z in (0, 3):
                for y in (1, 2): put(b, x, y, z, "spruce_fence")
        for x in range(0, 4):
            for z in range(0, 4): put(b, x, 3, z, "white_wool")
        bed(b, 1, 1)
        bed(b, 2, 1, "light_blue")
        put(b, 1, 1, 6, "barrel", facing="up", open="false")
        put(b, 2, 1, 6, "crafting_table")
        put(b, 7, 1, 1, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
        lantern(b, 7, 1, 7)
    elif kind == "watchtower":
        # One accessible lookout, continuous corner posts and a compact gable.
        for x in (1,5):
            for z in (1,5):
                for y in range(1,9): put(b,x,y,z,"stone_bricks" if y<3 else "spruce_log",**({"axis":"y"} if y>=3 else {}))
        for x in range(1,6):
            for z in range(1,6):
                put(b,x,5,z,"spruce_planks")
                if (x in (1,5) or z in (1,5)) and (x,z) not in ((1,1),(1,5),(5,1),(5,5)):
                    put(b,x,6,z,"spruce_fence",north=str(z<5).lower(),south=str(z>1).lower(),
                        east=str(x<5).lower(),west=str(x>1).lower(),waterlogged="false")
        for y in range(1,8): put(b,3,y,2,"ladder",facing="south",waterlogged="false")
        for y in range(1,9): put(b,3,y,1,"stripped_oak_log",axis="y")
        for x in range(7):
            for z in range(7):
                rise=min(x,6-x)
                if rise:
                    put(b,x,7+rise,z,"spruce_planks")
                if z in (1,5):
                    for y in range(8,8+rise): put(b,x,y,z,"oak_planks")
                if x==3: put(b,x,8+rise,z,"spruce_slab",type="bottom",waterlogged="false")
                else: stair(b,x,8+rise,z,"east" if x<3 else "west")
        for x in range(1,6): put(b,x,8,1,"spruce_log",axis="x")
        for y in (6,7): put(b,5,y,3,"stripped_oak_log",axis="y")
        put(b,6,6,3,"blue_wall_banner",facing="east")
        put(b,2,8,4,"lantern",hanging="true",waterlogged="false")
        for x in (1,5):
            for z in (2,3,4): put(b,x,2,z,"cobblestone_wall",up="true",north="low",south="low",east="none",west="none",waterlogged="false")
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
            put(b,width-1,2,(1+depth-2)//2,"air")  # No shutter on the open arcade.
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
                put(b,x,2,depth-2,"blue_wall_banner",facing="south")
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
                                       ("blacksmith", 7, 7, 10), ("barracks", 9, 9, 10), ("watchtower", 7, 7, 12),
                                       ("founding_camp", 9, 9, 5)):
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
