#!/usr/bin/env python3
"""Build the small allied settlement as a vanilla 1.21.1 structure NBT.

Python 3.10+ standard library only. Run from any directory; --check verifies the
checked-in asset without modifying it. The gzip header has no time or filename.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct


SIZE = (31, 7, 31)
DATA_VERSION = 3955
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[1]
    / "src/main/resources/data/livingkingdoms/structure/allied/test_settlement.nbt"
)
# NBT payload tags. Only types used by vanilla structure templates are needed.
BYTE, INT, STRING, LIST, COMPOUND = 1, 3, 8, 9, 10


def string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def payload(tag_type: int, value: object) -> bytes:
    if tag_type == BYTE:
        return struct.pack(">b", value)
    if tag_type == INT:
        return struct.pack(">i", value)
    if tag_type == STRING:
        return string(value)
    if tag_type == LIST:
        element_type, elements = value
        return bytes([element_type]) + struct.pack(">i", len(elements)) + b"".join(
            payload(element_type, element) for element in elements
        )
    if tag_type == COMPOUND:
        return b"".join(
            bytes([child_type]) + string(name) + payload(child_type, child)
            for name, (child_type, child) in value.items()
        ) + b"\x00"
    raise ValueError(f"Unsupported NBT tag: {tag_type}")


class SettlementTemplate:
    def __init__(self) -> None:
        self.palette: list[dict] = []
        self.palette_ids: dict[tuple, int] = {}
        self.blocks: dict[tuple[int, int, int], int] = {}
        self.block_entities: dict[tuple[int, int, int], dict] = {}
        # Explicit air ensures a vanilla export-like, complete volume. The site
        # finder must reject occupied space before placement, including this air.
        for y in range(SIZE[1]):
            for z in range(SIZE[2]):
                for x in range(SIZE[0]):
                    self.put(x, y, z, "minecraft:air")

    def put(self, x: int, y: int, z: int, name: str, **properties: str) -> None:
        assert 0 <= x < SIZE[0] and 0 <= y < SIZE[1] and 0 <= z < SIZE[2]
        key = (name, tuple(sorted(properties.items())))
        if key not in self.palette_ids:
            self.palette_ids[key] = len(self.palette)
            state = {"Name": (STRING, name)}
            if properties:
                state["Properties"] = (
                    COMPOUND,
                    {name: (STRING, value) for name, value in sorted(properties.items())},
                )
            self.palette.append(state)
        self.blocks[(x, y, z)] = self.palette_ids[key]
        self.block_entities.pop((x, y, z), None)

    def sign(self, x: int, y: int, z: int, facing: str, label: str) -> None:
        self.put(x, y, z, "minecraft:oak_wall_sign", facing=facing, waterlogged="false")

        def text_side(lines: list[str]) -> dict:
            return {
                "messages": (LIST, (STRING, [json.dumps({"text": line}, separators=(",", ":")) for line in lines])),
                "color": (STRING, "black"),
                "has_glowing_text": (BYTE, 0),
            }

        self.block_entities[(x, y, z)] = {
            "id": (STRING, "minecraft:sign"),
            "x": (INT, x), "y": (INT, y), "z": (INT, z),
            "front_text": (COMPOUND, text_side(["Living Kingdoms", label, "", ""])),
            "back_text": (COMPOUND, text_side(["", "", "", ""])),
            "is_waxed": (BYTE, 1),
        }

    def building(self, x0: int, z0: int, width: int, depth: int,
                 entrance: tuple[int, int, str], stone: bool = False) -> None:
        x1, z1 = x0 + width - 1, z0 + depth - 1
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, 0, z, "minecraft:oak_planks")
                for y in range(1, 4):
                    if x in (x0, x1) or z in (z0, z1):
                        corner = x in (x0, x1) and z in (z0, z1)
                        if corner:
                            self.put(x, y, z, "minecraft:oak_log", axis="y")
                        else:
                            self.put(x, y, z, "minecraft:cobblestone" if stone else "minecraft:oak_planks")
                # A shallow gable fits inside the seven-block vertical envelope.
                distance = min(x - x0, x1 - x)
                roof_y = 4 + min(distance, 2)
                if z in (z0, z1):
                    for y in range(4, roof_y):
                        self.put(x, y, z, "minecraft:oak_planks")
                if distance < 2:
                    self.put(x, roof_y, z, "minecraft:spruce_stairs",
                             facing="east" if x < (x0 + x1) / 2 else "west",
                             half="bottom", shape="straight", waterlogged="false")
                else:
                    self.put(x, roof_y, z, "minecraft:spruce_slab", type="bottom", waterlogged="false")
        for x, z in ((x0, (z0 + z1) // 2), (x1, (z0 + z1) // 2),
                     ((x0 + x1) // 2, z0), ((x0 + x1) // 2, z1)):
            self.put(x, 2, z, "minecraft:glass")
        door_x, door_z, facing = entrance
        for y, half in ((1, "lower"), (2, "upper")):
            self.put(door_x, y, door_z, "minecraft:oak_door", facing=facing,
                     half=half, hinge="left", open="false", powered="false")

    def build(self) -> bytes:
        for z in range(31):
            for x in range(31):
                self.put(x, 0, z, "minecraft:cobblestone")
        # Buildings surround a connected plaza, with all entrances on its paths.
        self.building(11, 2, 9, 7, (15, 8, "south"))
        self.building(2, 2, 5, 5, (4, 6, "south"))
        self.building(24, 2, 5, 5, (26, 6, "south"))
        self.building(2, 10, 5, 5, (6, 12, "east"))
        self.building(22, 10, 7, 7, (22, 13, "west"), stone=True)
        self.building(11, 22, 9, 7, (15, 22, "north"))
        # A visual chimney and workstations; no heat source, loot, or entities.
        for y in range(4, 7):
            self.put(27, y, 11, "minecraft:cobblestone")
        self.put(27, 1, 12, "minecraft:furnace", facing="west", lit="false")
        self.put(26, 1, 12, "minecraft:anvil", facing="north")
        self.put(24, 1, 15, "minecraft:crafting_table")
        self.put(12, 1, 3, "minecraft:crafting_table")
        for x in (12, 15, 18):
            self.put(x, 1, 27, "minecraft:red_bed", facing="south", occupied="false", part="head")
            self.put(x, 1, 26, "minecraft:red_bed", facing="south", occupied="false", part="foot")
        for x, z in ((4, 4), (26, 4), (4, 12)):
            self.put(x, 1, z, "minecraft:crafting_table")
        for z in range(13, 18):
            for x in range(13, 20):
                self.put(x, 0, z, "minecraft:stone_bricks")
        self.put(15, 1, 15, "minecraft:lodestone")
        self.put(18, 1, 15, "livingkingdoms:quest_board", facing="west")
        for x, z in ((10, 10), (20, 10), (10, 20), (20, 20)):
            for y in (1, 2):
                self.put(x, y, z, "minecraft:oak_fence", north="false", south="false",
                         east="false", west="false", waterlogged="false")
            self.put(x, 3, z, "minecraft:lantern", hanging="false", waterlogged="false")
        for x, y, z, facing, label in (
            (13, 2, 9, "south", "Town Hall"),
            (3, 2, 7, "south", "House 1"),
            (25, 2, 7, "south", "House 2"),
            (7, 2, 11, "east", "House 3"),
            (21, 2, 11, "west", "Blacksmith"),
            (13, 2, 21, "north", "Barracks"),
        ):
            self.sign(x, y, z, facing, label)
        assert len(self.blocks) == SIZE[0] * SIZE[1] * SIZE[2]
        assert all(self.palette[self.blocks[(x, 0, z)]]["Name"][1] != "minecraft:air"
                   for x in range(31) for z in range(31))
        names = [self.palette[state]["Name"][1] for state in self.blocks.values()]
        assert names.count("minecraft:lodestone") == names.count("livingkingdoms:quest_board") == 1
        entries = []
        for position, state in sorted(self.blocks.items(), key=lambda pair: (pair[0][1], pair[0][2], pair[0][0])):
            entry = {"pos": (LIST, (INT, position)), "state": (INT, state)}
            if position in self.block_entities:
                entry["nbt"] = (COMPOUND, self.block_entities[position])
            entries.append(entry)
        root = {
            "DataVersion": (INT, DATA_VERSION),
            "size": (LIST, (INT, SIZE)),
            "palette": (LIST, (COMPOUND, self.palette)),
            "blocks": (LIST, (COMPOUND, entries)),
            "entities": (LIST, (COMPOUND, [])),
        }
        uncompressed = bytes([COMPOUND]) + string("") + payload(COMPOUND, root)
        output = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=9, mtime=0) as compressed:
            compressed.write(uncompressed)
        return output.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="Verify output matches, without writing")
    args = parser.parse_args()
    result = SettlementTemplate().build()
    if args.check:
        if not args.output.is_file() or args.output.read_bytes() != result:
            raise SystemExit(f"Template differs or is missing: {args.output}")
        verb = "Verified"
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(result)
        verb = "Wrote"
    print(f"{verb} {args.output}: {len(result)} bytes, SHA-256 {hashlib.sha256(result).hexdigest()}")


if __name__ == "__main__":
    main()
