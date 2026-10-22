"""Preserve the supplied Argon 4 art and reproduce 80 adapted original LK skins.

Initial import: --source <zip>. Subsequent --check needs no external ZIP, Pillow,
Blockbench, GeckoLib or executable code from the supplied archive.

The warband soldier pack (same rig, own manifest) is imported with --warband-source <zip>;
--check verifies both packs.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import math
import struct
import zipfile
import generate_citizen_assets as skins

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "src/main/resources/assets/livingkingdoms"
SOURCE = ROOT / "docs/argon4-source"
OUT = ART / "textures/entity/citizen_argon4"
MANIFEST = SOURCE / "manifest.json"
IMPORTS = {
    "characters/models/citizen.geo.json": ART / "geo/argon4/citizen.geo.json",
    "characters/animations/citizen.animation.json": ART / "animations/argon4/citizen.animation.json",
    "characters/animations/professions.animation.json": ART / "animations/argon4/professions.animation.json",
    "characters/animations/combat.animation.json": ART / "animations/argon4/combat.animation.json",
    "characters/blockbench/citizen.bbmodel": SOURCE / "citizen.bbmodel",
    "characters/textures/base/citizen.png": SOURCE / "textures/citizen.png",
    "characters/textures/base/citizen_female.png": SOURCE / "textures/citizen_female.png",
    "characters/textures/professions/builder.png": ART / "textures/entity/argon4/builder.png",
    "characters/textures/professions/blacksmith.png": ART / "textures/entity/argon4/blacksmith.png",
}


WARBAND_SOURCE = SOURCE / "warband"
WARBAND_MANIFEST = WARBAND_SOURCE / "manifest.json"
WARBAND_IMPORTS = {
    "characters/models/warband_soldier.geo.json": ART / "geo/argon4/warband_soldier.geo.json",
    "characters/animations/warband.animation.json": ART / "animations/argon4/warband.animation.json",
    "characters/textures/warband_soldier.png": ART / "textures/entity/argon4/warband_soldier.png",
    "characters/blockbench/warband_soldier.bbmodel": WARBAND_SOURCE / "warband_soldier.bbmodel",
    "characters/documentation/README.md": WARBAND_SOURCE / "README.md",
}
WARBAND_ARCHIVE = WARBAND_SOURCE / "warband_soldier_pack.zip"
WARBAND_CLIPS = {"axe_attack", "crossbow_aim", "crossbow_reload", "captain_command", "death_stagger"}
# Base body UV islands of the 64x64 layout; they must be fully opaque so no hole shows the inside of the mesh.
BODY_REGIONS = {"head": (0, 0, 32, 16), "torso": (16, 16, 24, 16), "right_arm": (40, 16, 16, 16),
                "right_leg": (0, 16, 16, 16), "left_arm": (32, 48, 16, 16), "left_leg": (16, 48, 16, 16)}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_geometry(data):
    model = json.loads(data)["minecraft:geometry"]
    assert len(model) == 1
    desc = model[0]["description"]
    assert (desc["texture_width"], desc["texture_height"]) == (64, 64)
    bones = model[0]["bones"]
    assert len(bones) <= 32
    names = {bone["name"] for bone in bones}
    assert len(names) == len(bones)
    assert {"body", "torso", "head", "right_arm", "left_arm", "right_leg", "left_leg", "hair", "beard", "head_accessories", "right_hand"} <= names
    cubes = 0
    for bone in bones:
        assert bone.get("parent", "root") in names
        assert all(math.isfinite(value) and abs(value) <= 64 for value in bone["pivot"])
        assert "rotation" not in bone, "Adapter does not support baked bone rotations"
        for cube in bone.get("cubes", []):
            cubes += 1
            assert "rotation" not in cube, "Adapter does not support rotated cubes"
            assert len(cube["origin"]) == len(cube["size"]) == 3
            assert all(math.isfinite(value) and abs(value) <= 64 for value in cube["origin"])
            assert all(math.isfinite(value) and 0 < value <= 32 for value in cube["size"])
            assert 0 <= cube.get("inflate", 0) <= 1
            assert set(cube["uv"]) == {"north", "east", "south", "west", "up", "down"}
            for face in cube["uv"].values():
                for start, extent in zip(face["uv"], face["uv_size"]):
                    assert math.isfinite(start) and math.isfinite(extent)
                    assert 0 <= start <= 64 and 0 <= start + extent <= 64
    assert cubes <= 64
    return names, cubes


def decode_png(data):
    """Minimal stdlib decoder for the 8-bit RGBA, non-interlaced, unfiltered-or-simple PNGs the pack ships."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "Not a PNG"
    position, header, compressed = 8, None, b""
    while position < len(data):
        length, = struct.unpack(">I", data[position:position + 4])
        kind, body = data[position + 4:position + 8], data[position + 8:position + 8 + length]
        assert zlib_crc(kind + body) == struct.unpack(">I", data[position + 8 + length:position + 12 + length])[0], "Bad PNG CRC"
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            compressed += body
        position += 12 + length
    assert header == (64, 64, 8, 6, 0, 0, 0), f"Expected a 64x64 8-bit RGBA PNG, got {header}"
    raw = __import__("zlib").decompress(compressed)
    stride = 64 * 4
    rows, previous = [], bytearray(stride)
    for y in range(64):
        line = raw[y * (stride + 1):(y + 1) * (stride + 1)]
        filter_type, row = line[0], bytearray(line[1:])
        for x in range(stride):
            left = row[x - 4] if x >= 4 else 0
            up = previous[x]
            upper_left = previous[x - 4] if x >= 4 else 0
            if filter_type == 1: row[x] = (row[x] + left) & 255
            elif filter_type == 2: row[x] = (row[x] + up) & 255
            elif filter_type == 3: row[x] = (row[x] + (left + up) // 2) & 255
            elif filter_type == 4:
                estimate = left + up - upper_left
                pick = min((abs(estimate - left), left), (abs(estimate - up), up), (abs(estimate - upper_left), upper_left))[1]
                row[x] = (row[x] + pick) & 255
            else: assert filter_type == 0, "Unknown PNG filter"
        rows.append(row)
        previous = row
    return rows


def zlib_crc(data):
    return __import__("zlib").crc32(data) & 0xFFFFFFFF


def validate_warband_texture(data):
    rows = decode_png(data)
    for name, (x, y, width, height) in BODY_REGIONS.items():
        holes = sum(1 for yy in range(y, y + height) for xx in range(x, x + width) if rows[yy][xx * 4 + 3] != 255)
        assert holes == 0, f"{name} UV island has {holes} transparent pixels"


def verify_warband(import_names):
    """Checks the committed warband pack: hashes, rig contract, clip set and texture."""
    manifest = json.loads(WARBAND_MANIFEST.read_text(encoding="utf-8"))
    for name, entry in manifest["files"].items():
        assert sha((ROOT / name).read_bytes()) == entry["sha256"], f"Source differs: {name}"
    assert sha(WARBAND_ARCHIVE.read_bytes()) == manifest["archive_sha256"], "Warband archive differs from its manifest"
    names, cubes = validate_geometry((ART / "geo/argon4/warband_soldier.geo.json").read_bytes())
    assert names == import_names, "Warband soldier must use exactly the citizen bone set"
    clips = json.loads((ART / "animations/argon4/warband.animation.json").read_bytes())["animations"]
    assert set(clips) == {f"animation.warband.{clip}" for clip in WARBAND_CLIPS}, "Unexpected warband clip set"
    validate_warband_texture((ART / "textures/entity/argon4/warband_soldier.png").read_bytes())
    return cubes, manifest["archive_sha256"]


def import_warband(source):
    data = source.read_bytes()
    with zipfile.ZipFile(source) as archive:
        assert archive.testzip() is None
        entries = archive.infolist()
        assert len({entry.filename for entry in entries}) == len(entries)
        assert sum(entry.file_size for entry in entries) <= 2_000_000
        for entry in entries:
            path = PurePosixPath(entry.filename)
            assert not path.is_absolute() and ".." not in path.parts
            assert not ((entry.external_attr >> 16) & 0o170000) == 0o120000
        validate_geometry(archive.read("characters/models/warband_soldier.geo.json"))
        validate_warband_texture(archive.read("characters/textures/warband_soldier.png"))
        manifest = {"archive_sha256": sha(data), "files": {}}
        for name, target in WARBAND_IMPORTS.items():
            contents = archive.read(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(contents)
            manifest["files"][target.relative_to(ROOT).as_posix()] = {"source": name, "sha256": sha(contents)}
    WARBAND_ARCHIVE.write_bytes(data)
    WARBAND_MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def adapted(job, variant):
    image = skins.pixels(job, variant)
    original = [row[:] for row in image]

    def rect(x, y, width, height, color):
        for yy in range(y, y + height):
            for xx in range(x, x + width):
                image[yy][xx] = (*skins.tone(color, (xx * 17 + yy * 7 + variant) % 5 - 2), 255)

    def copy(x, y, width, height, tx, ty):
        for yy in range(height):
            for xx in range(width):
                image[ty + yy][tx + xx] = original[y + yy][x + xx]

    # Standard 64x64 base shell is retained. Imported clothing volumes require
    # opaque outer tunic, short sleeves and boot UVs absent in the previous skins.
    copy(16, 16, 24, 16, 16, 32)
    copy(40, 16, 16, 16, 40, 32)
    copy(32, 48, 16, 16, 48, 48)
    rect(0, 32, 16, 10, (58, 44, 35))
    rect(0, 48, 16, 10, (58, 44, 35))
    rect(32, 0, 32, 8, skins.HAIR[variant])
    rect(32, 8, 32, 3, skins.HAIR[variant])
    # Separate safe UV islands keep beard pixels off the face and head bands off
    # hair. Exact per-face remaps are applied only to those accessory bones.
    rect(56, 42, 6, 2, skins.HAIR[variant])
    rect(56, 44, 8, 2, skins.PALETTES[job][1])
    rect(56, 36, 8, 4, (95, 62, 39))  # belt pouch
    return image


def hammer():
    image = [[(0, 0, 0, 0) for _ in range(64)] for _ in range(64)]
    for y in range(16, 34):
        for x in range(54, 64):
            color = (107, 72, 42) if y < 28 else (146, 152, 157)
            image[y][x] = (*skins.tone(color, (x + y) % 3 * 8 - 8), 255)
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--warband-source", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.warband_source:
        if args.check:
            parser.error("--warband-source imports assets; use --check separately")
        import_warband(args.warband_source)
    if args.source:
        if args.check:
            parser.error("--source imports assets; use --check separately")
        data = args.source.read_bytes()
        with zipfile.ZipFile(args.source) as archive:
            assert archive.testzip() is None
            entries = archive.infolist()
            assert len({entry.filename for entry in entries}) == len(entries)
            assert sum(entry.file_size for entry in entries) <= 2_000_000
            for entry in entries:
                path = PurePosixPath(entry.filename)
                assert not path.is_absolute() and ".." not in path.parts
                assert not ((entry.external_attr >> 16) & 0o170000) == 0o120000
            validate_geometry(archive.read("characters/models/citizen.geo.json"))
            manifest = {"archive_sha256": sha(data), "files": {}}
            for source, target in IMPORTS.items():
                contents = archive.read(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contents)
                manifest["files"][target.relative_to(ROOT).as_posix()] = {"source": source, "sha256": sha(contents)}
            MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for name, entry in manifest["files"].items():
        assert sha((ROOT / name).read_bytes()) == entry["sha256"], f"Source differs: {name}"
    names, cubes = validate_geometry((ART / "geo/argon4/citizen.geo.json").read_bytes())
    clips = {}
    for path in (ART / "animations/argon4").glob("*.json"):
        parsed = json.loads(path.read_bytes())
        assert parsed["format_version"] == "1.8.0"
        for name, clip in parsed["animations"].items():
            assert set(clip["bones"]) <= names, f"Missing bone in {name}"
            assert 0 < clip["animation_length"] <= 30
            if name in clips:
                assert clips[name] == clip, f"Conflicting animation: {name}"
            clips[name] = clip
    expected = {}
    for job in skins.PALETTES:
        for variant in range(8):
            expected[f"{job}_{variant}.png"] = skins.png(adapted(job, variant))
    expected["hammer.png"] = skins.png(hammer())
    if not args.check:
        OUT.mkdir(parents=True, exist_ok=True)
    for name, contents in expected.items():
        path = OUT / name
        if args.check:
            assert path.is_file() and path.read_bytes() == contents, f"Asset differs: {path}"
        else:
            path.write_bytes(contents)
    assert {path.name for path in OUT.glob("*.png")} == set(expected)
    warband_cubes, warband_sha = verify_warband(names)
    print(f"{'Verified' if args.check else 'Generated'} 80 adapted original skins and one hammer texture; {len(names)} bones, {cubes} cubes, {len(clips)} clips; source SHA-256 {manifest['archive_sha256']}.")
    print(f"Verified warband soldier pack: {warband_cubes} cubes, {len(WARBAND_CLIPS)} warband clips, opaque body UVs; source SHA-256 {warband_sha}.")


if __name__ == "__main__":
    main()
