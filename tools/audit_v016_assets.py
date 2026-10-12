#!/usr/bin/env python3
"""Verify authored board/house resources and, optionally, their exact final JAR bytes.

This is an asset/geometry check. Minecraft lighting, text layout and appearance
still require the graphical QA checklist; a successful build is not visual QA.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile
import audit_settlement_modules as structures
import generate_quest_board_assets as board

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"


def audit():
    files = {}
    for relative, expected in board.assets().items():
        name = "assets/livingkingdoms/" + relative
        content = (RESOURCES / name).read_bytes()
        assert content == expected, "Generated resource differs: " + name
        files[name] = content
    for relative in ("blockstates/quest_board.json", "models/item/quest_board.json"):
        name = "assets/livingkingdoms/" + relative
        files[name] = (RESOURCES / name).read_bytes()
    variants = json.loads(files["assets/livingkingdoms/blockstates/quest_board.json"])["variants"]
    assert len(variants) == 4
    for index, facing in enumerate(("north", "east", "south", "west")):
        variant = variants["facing=" + facing]
        assert variant["model"] == "livingkingdoms:block/quest_board" and variant.get("y", 0) == index * 90
    assert json.loads(files["assets/livingkingdoms/models/item/quest_board.json"])["parent"] == "livingkingdoms:block/quest_board"
    model = json.loads(files["assets/livingkingdoms/models/block/quest_board.json"])
    for element in model["elements"]:
        assert all(0 <= low < high <= 16 for low, high in zip(element["from"], element["to"]))
        for face in element["faces"].values(): assert face["texture"][1:] in model["textures"]
    for value in model["textures"].values():
        path = "assets/livingkingdoms/textures/" + value.split(":", 1)[1] + ".png"
        data = files[path]
        assert data.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", data[16:24]) == (16, 16)
        assert data[24:26] == bytes((8, 6)), "Textures must be RGBA pixel art"
    for kind, expected_size, front in (("core", [13, 11, 13], 6), ("town_hall", [11, 11, 9], 7)):
        name = "data/livingkingdoms/structure/allied/plains/" + kind + ".nbt"
        content = (RESOURCES / name).read_bytes(); files[name] = content
        native = structures.read_nbt(content)
        result, blocks = structures.audit(native)
        assert result["size"] == expected_size and result["detached_count"] == 0
        center = expected_size[0] // 2
        for x in (center - 2, center + 2):
            for y in (2, 3): assert blocks[(x, y, front)]["Name"] == "minecraft:glass_pane"
        for y, half in ((1, "lower"), (2, "upper")):
            door = blocks[(center, y, front)]
            assert door["Name"] == "minecraft:spruce_door" and door["Properties"]["half"] == half
            assert door["Properties"]["facing"] == "south"
            for z in (front - 1, front + 1): assert blocks[(center, y, z)]["Name"] == "minecraft:air"
        if kind == "core":
            assert blocks[(6, 1, 9)]["Name"] == "minecraft:lodestone"
            assert blocks[(9, 1, 9)]["Name"] == "livingkingdoms:quest_board"
            sign = next(entry for entry in native["blocks"] if entry["pos"] == [6, 3, 7])
            assert blocks[(6, 3, 7)]["Name"] == "minecraft:oak_wall_sign"
            assert blocks[(6, 3, 7)]["Properties"]["facing"] == "south"
            assert blocks[(6, 3, 6)]["Name"] == "minecraft:oak_planks", "Sign needs a solid attachment above the doorway"
            assert sign["nbt"]["id"] == "minecraft:sign"
            assert json.loads(sign["nbt"]["front_text"]["messages"][0])["text"] == "Living Kingdoms"
            assert json.loads(sign["nbt"]["front_text"]["messages"][1])["text"] == "Town Hall"
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, help="Built distributable JAR to inspect")
    args = parser.parse_args()
    files = audit()
    print(f"Verified {len(files)} source assets: model, pixel art, four facings, entrances and connected civic roofs")
    if args.jar:
        with zipfile.ZipFile(args.jar) as jar:
            assert jar.testzip() is None, "Corrupted JAR entry"
            for name, source in files.items():
                assert jar.read(name) == source, "Final JAR has missing or stale asset: " + name
        print(f"Verified all {len(files)} exact assets inside {args.jar.name}")
    print("Graphical Minecraft QA remains required; see the 0.16.0 QA checklist.")


if __name__ == "__main__": main()
