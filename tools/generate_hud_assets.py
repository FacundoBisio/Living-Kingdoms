#!/usr/bin/env python3
"""Native 8-pixel HUD icons; deterministic RGBA, no external image or runtime dependency."""
import argparse
import json
from pathlib import Path
import struct
import zlib
import zipfile

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/livingkingdoms'
COLORS = {'.': (0, 0, 0, 0), '#': (23, 32, 38, 255), 'w': (242, 228, 182, 255),
          'g': (137, 225, 166, 255), 'b': (167, 211, 232, 255), 'r': (237, 167, 141, 255)}
ICONS = [
    ['.w....w.', '.ww..ww.', '.w####w.', '.wwwwww.', '.w.ww.w.', '.w.##.w.', '.ww##ww.', '.######.'],
    ['...gg...', '..gwwg..', '.gwwwwg.', 'gwwwwwwg', 'gwwwwwwg', '.ggwwgg.', '..gggg..', '...gg...'],
    ['.bb..bb.', '.bb..bb.', '........', '.ww..ww.', 'wwwwwwww', 'wwwwwwww', '.ww..ww.', '........'],
    ['.wwwwww.', 'wwbbbbww', 'wwbbbbww', '.wbbbbw.', '.wbbbbw.', '..wbbw..', '...ww...', '........'],
    ['.wwwwww.', 'wwwwwww.', '.w###w..', '.wwwww..', '.w###w..', '.wwwww..', '.wwwww.w', '.wwwwwww'],
    ['...bb...', '...bb...', '...bb...', 'bbbbbbbb', 'bbbbbbbb', '...bb...', '...bb...', '........'],
    ['.......g', '......gg', '.....gg.', 'g...gg..', 'gg.gg...', '.ggg....', '..g.....', '........'],
    ['..rrrr..', '.rrrrrr.', '.rr..rr.', '.rr..rr.', '..rrrr..', '...rr...', '...rr...', '........'],
]

def assets():
    assert all(len(icon) == 8 and all(len(row) == 8 for row in icon) for icon in ICONS)
    pixels = [[COLORS[pixel] for icon in ICONS for pixel in icon[y]] for y in range(8)]
    raw = b''.join(b'\0' + bytes(channel for color in row for channel in color) for row in pixels)
    def chunk(kind, contents):
        return struct.pack('>I', len(contents)) + kind + contents + struct.pack('>I', zlib.crc32(kind + contents))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 8, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    return {'textures/gui/hud_icons.png': png,
            'textures/gui/hud_icons.png.mcmeta': (json.dumps({'texture': {'blur': False, 'clamp': True}}, indent=2) + '\n').encode()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    for relative, content in assets().items():
        path = ROOT / relative
        if args.check or args.jar: assert path.read_bytes() == content, 'Stale HUD asset: ' + relative
        else: path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
    if args.jar:
        with zipfile.ZipFile(args.jar) as jar:
            for relative, content in assets().items():
                assert jar.read('assets/livingkingdoms/' + relative) == content, 'Stale or absent packaged HUD asset'
    print('Verified eight native pixel icons and nearest-filter texture metadata' + (' in ' + args.jar.name if args.jar else ''))

if __name__ == '__main__': main()
