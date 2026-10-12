#!/usr/bin/env python3
"""Author deterministic 1.21.1 Quest Board model and native-resolution pixel art.

Standard library only. --check validates checked-in outputs without rewriting.
"""
import argparse
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/livingkingdoms"


def png(pixels):
    def chunk(name, body):
        return struct.pack(">I", len(body)) + name + body + struct.pack(">I", zlib.crc32(name + body))
    height, width = len(pixels), len(pixels[0])
    raw = b"".join(b"\0" + bytes(channel for color in row for channel in (*color, 255)) for row in pixels)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def wood():
    shades = [(93, 63, 34), (119, 82, 43), (133, 95, 50), (147, 107, 58), (160, 120, 67)]
    pixels = [[shades[(y // 4 + (x * 3 + y * 7) % 5) % 4 + 1] for x in range(16)] for y in range(16)]
    for y in (0, 4, 8, 12):
        for x in range(16): pixels[y][x] = shades[0]
    for x, y in ((3, 2), (11, 6), (6, 10), (13, 14)):
        pixels[y][x] = shades[0]
        pixels[y][max(0, x-1)] = shades[1]
    return png(pixels)


def notice(kind):
    paper, edge, ink = (228, 214, 170), (179, 158, 113), (86, 68, 47)
    pixels = [[edge if x in (0, 15) or y in (0, 15) else paper for x in range(16)] for y in range(16)]
    # Iron tack, folded corner, handwritten strokes and one colored wax seal.
    for x, y in ((7, 1), (8, 1), (7, 2), (8, 2)): pixels[y][x] = (105, 103, 94)
    for x, y in ((13, 1), (14, 1), (14, 2)): pixels[y][x] = (245, 232, 193)
    for y, length in ((4, 9), (6, 11), (8, 8), (10, 10)):
        for x in range(2, 2 + length):
            if (x + y + kind) % 5 != 0: pixels[y][x] = ink
    seal = [(135, 49, 37), (55, 101, 62), (50, 83, 111)][kind]
    for x, y in ((11, 11), (12, 11), (10, 12), (11, 12), (12, 12), (13, 12), (11, 13), (12, 13)):
        pixels[y][x] = seal
    return png(pixels)


def box(start, end, texture="wood"):
    return {"from": start, "to": end, "faces": {face: {"texture": "#" + texture}
            for face in ("down", "up", "north", "south", "west", "east")}}


def assets():
    elements = [
        box([2, 0, 6], [4, 14.5, 10]), box([12, 0, 6], [14, 14.5, 10]),
        box([1, 0, 5], [5, 1.5, 11]), box([11, 0, 5], [15, 1.5, 11]),
        box([2, 3, 6.5], [14, 4.5, 9.5]),
        box([2, 5.5, 5.5], [14, 14, 8.5]),
        box([1, 5, 5], [15, 6.5, 10]), box([1, 13.5, 5], [15, 15, 10]),
        box([1, 6.5, 5], [2.5, 13.5, 10]), box([13.5, 6.5, 5], [15, 13.5, 10]),
        box([0, 14.5, 4], [16, 15.5, 12]), box([1, 15.5, 5], [15, 16, 11]),
    ]
    for index, (start, end) in enumerate((([3, 8, 5.125], [7, 13, 5.5]),
                                          ([8.25, 9, 5.125], [13, 13, 5.5]),
                                          ([8, 6.75, 5.125], [11, 9.25, 5.5]))):
        sheet = box(start, end, "notice" + str(index))
        for face in sheet["faces"].values(): face["uv"] = [0, 0, 16, 16]
        elements.append(sheet)
    model = {"parent": "minecraft:block/block", "ambientocclusion": True,
             "textures": {"particle": "livingkingdoms:block/quest_board_wood",
                          "wood": "livingkingdoms:block/quest_board_wood",
                          **{"notice" + str(i): "livingkingdoms:block/quest_board_notice_" + str(i) for i in range(3)}},
             "elements": elements,
             "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
                         "ground": {"translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]}}}
    outputs = {"models/block/quest_board.json": (json.dumps(model, indent=2) + "\n").encode(),
               "textures/block/quest_board_wood.png": wood()}
    for i in range(3): outputs["textures/block/quest_board_notice_" + str(i) + ".png"] = notice(i)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, content in assets().items():
        path = ROOT / name
        if args.check:
            if not path.is_file() or path.read_bytes() != content: raise SystemExit("Asset differs: " + str(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        print(("Verified " if args.check else "Wrote ") + name)


if __name__ == "__main__": main()
