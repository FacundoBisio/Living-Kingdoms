"""Original Living Kingdoms 64x64 humanoid skins. Standard library only; no downloaded art.

Run from anywhere with Python 3. Every pixel is derived from this authored palette and
UV drawing code. --check verifies committed files without writing them.
"""
from pathlib import Path
import argparse
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/livingkingdoms/textures/entity/citizen"
PALETTES = {
    "unassigned": ((104, 118, 96), (217, 203, 164)),
    "farmer": ((120, 137, 61), (230, 198, 108)),
    "guard": ((65, 90, 116), (185, 189, 180)),
    "builder": ((171, 122, 69), (236, 205, 127)),
    "blacksmith": ((77, 71, 68), (175, 117, 66)),
    "merchant": ((113, 67, 111), (213, 171, 92)),
    "captain": ((56, 78, 100), (223, 183, 96)),
    "mayor": ((66, 94, 108), (236, 192, 96)),
    "lumberjack": ((140, 68, 54), (99, 124, 71)),
    "miner": ((84, 111, 118), (220, 166, 72)),
}
SKIN = [(225, 179, 139), (153, 100, 67), (109, 69, 49), (243, 201, 164),
        (188, 130, 91), (211, 159, 119), (135, 92, 70), (229, 187, 151)]
HAIR = [(73, 44, 27), (32, 26, 24), (50, 35, 30), (158, 98, 40),
        (204, 177, 108), (102, 55, 34), (45, 34, 31), (165, 160, 144)]


def tone(color, delta):
    return tuple(max(0, min(255, value + delta)) for value in color)


def pixels(job, variant):
    image = [[(0, 0, 0, 0) for _ in range(64)] for _ in range(64)]
    shirt, accent = PALETTES[job]
    shirt = tone(shirt, (variant % 3 - 1) * 5)
    skin, hair = SKIN[variant], HAIR[variant]
    boots, trousers, belt = (58, 44, 35), (65, 66, 60), (81, 55, 34)

    def rect(x, y, w, h, color, texture=False):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                rgb = tone(color, ((xx * 17 + yy * 7 + variant) % 5 - 2) if texture else 0)
                image[yy][xx] = (*rgb, 255)

    # Head UV shell, with hand-authored hairlines/sideburns and face details.
    rect(0, 0, 32, 16, skin, True)
    rect(8, 0, 8, 8, hair, True)
    rect(0, 8, 8, 8, hair, True)
    rect(16, 8, 16, 8, hair, True)
    rect(8, 8, 8, 2 + variant % 2, hair, True)
    rect(8, 10, 1, 3, hair)
    rect(15, 10, 1, 2 + variant % 3, hair)
    rect(6, 11, 2, 3, skin); rect(16, 11, 2, 3, skin)
    rect(9, 11, 2, 1, (235, 225, 205)); rect(13, 11, 2, 1, (235, 225, 205))
    eye = (53, 77, 63) if variant % 2 else (66, 51, 37)
    rect(10, 11, 1, 1, eye); rect(13, 11, 1, 1, eye)
    rect(11, 12, 2, 1, tone(skin, -20)); rect(11, 14, 2, 1, tone(skin, -46))
    if variant in (1, 4, 7):
        rect(9, 14, 1, 2, hair); rect(14, 14, 1, 2, hair); rect(10, 15, 4, 1, hair)
    if variant in (3, 6):
        rect(8, 9, 2, 2, hair); rect(14, 9, 2, 2, hair)

    # Torso, independent arms and legs use the standard 64x64 player UVs.
    rect(16, 16, 24, 16, shirt, True)
    rect(20, 20, 8, 2, accent); rect(23, 20, 2, 2, skin)
    rect(16, 29, 24, 2, belt); rect(23, 29, 2, 2, accent)
    for ax, ay in ((40, 16), (32, 48)):
        rect(ax, ay, 16, 16, shirt, True)
        rect(ax, ay + 10, 16, 2, accent)
        rect(ax, ay + 12, 16, 4, skin, True)
    for lx, ly in ((0, 16), (16, 48)):
        rect(lx, ly, 16, 16, trousers, True)
        rect(lx, ly + 12, 16, 4, boots, True)
        rect(lx + 4, ly + 12, 4, 1, tone(boots, 14))

    # Profession silhouettes read at a distance; detail stays in vanilla pixel scale.
    if job in ("blacksmith", "builder"):
        rect(21, 23, 6, 7, accent, True)  # leather work apron
        rect(21, 21, 1, 3, accent); rect(26, 21, 1, 3, accent)
        rect(22, 27, 4, 2, tone(accent, -30))
        rect(21, 23, 1, 1, (223, 211, 170)); rect(26, 23, 1, 1, (223, 211, 170))
    elif job in ("guard", "captain"):
        rect(20, 22, 8, 6, accent, True)
        for y in (23, 25, 27): rect(20, y, 8, 1, tone(accent, -32))
        rect(23, 23, 2, 4, shirt)
        rect(44, 20, 4, 2, accent); rect(36, 52, 4, 2, accent)
    elif job == "lumberjack":
        for x in (21, 25, 33, 37): rect(x, 22, 1, 7, tone(shirt, -30))
        for y in (23, 26): rect(16, y, 24, 1, tone(shirt, -24))
        rect(21, 20, 1, 9, accent); rect(26, 20, 1, 9, accent)
    elif job == "farmer":
        rect(21, 24, 6, 5, tone(shirt, -28)); rect(21, 20, 1, 5, accent); rect(26, 20, 1, 5, accent)
        rect(22, 25, 4, 2, accent)
    elif job == "miner":
        rect(20, 22, 2, 7, accent); rect(26, 22, 2, 7, accent)
        rect(8, 8, 8, 2, accent); rect(11, 8, 2, 2, (240, 226, 153))
    elif job in ("merchant", "mayor"):
        rect(20, 22, 2, 7, accent); rect(26, 22, 2, 7, accent)
        rect(23, 22, 2, 5, tone(shirt, -26))
        for y in (23, 26, 28): rect(24, y, 1, 1, accent)
        rect(32, 20, 8, 2, accent)
        if job == "merchant": rect(26, 27, 2, 3, belt)
    else:
        rect(23, 22, 1, 6, accent); rect(20, 26, 2, 2, tone(shirt, -20))
    if job == "mayor":
        # Original gold circlet occupies the outer head layer; all other overlay pixels stay transparent.
        rect(32, 8, 32, 1, accent)
        rect(43, 7, 2, 2, accent); rect(43, 8, 2, 1, (84, 144, 132))
    if job == "captain": rect(44, 20, 4, 1, (235, 187, 88))
    return image


def png(image):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b"".join(b"\0" + bytes(v for pixel in row for v in pixel) for row in image)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", len(image[0]), len(image), 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not args.check: OUT.mkdir(parents=True, exist_ok=True)
    expected = set()
    for job in PALETTES:
        for variant in range(8):
            path = OUT / f"{job}_{variant}.png"
            expected.add(path.name)
            data = png(pixels(job, variant))
            if args.check:
                if not path.exists() or path.read_bytes() != data: raise SystemExit(f"Asset differs: {path}")
            else: path.write_bytes(data)
    if args.check and {p.name for p in OUT.glob("*.png")} != expected: raise SystemExit("Unexpected citizen PNG files")
    print(f"{'Verified' if args.check else 'Generated'} {len(expected)} original 64x64 RGBA skins.")


if __name__ == "__main__": main()
