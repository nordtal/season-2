#!/usr/bin/env python3
"""Draws the German characters into the nordtal:bossbar font's own ascii.png.

Why this exists: nordtal/font/bossbar.json carries its own 128x128 ascii sheet so the
readable half of a boss bar line is rendered by the same font as the glyphs beside it
(see season-2/CLAUDE.md on why the whole line goes in one component). That sheet was
drawn with the pure-ASCII rows only - 166 characters, and not one of a o u A O U s.
A German HUD line is one umlaut away from a row of missing-glyph boxes, and nothing in
the build would have said so; today no German string in smp.hud.* or hg.hud.* happens to
contain one, which is luck rather than design.

The sheet's character map is Minecraft's own ascii.png map (verified 2026-09-04: the
drawn cells at rows 9, 10 and 14 land exactly on the vanilla CP437 positions for the
pound sign, the ordinals and the guillemets). The seven characters are therefore drawn
at their canonical positions rather than at free ones - so a future pass that drops the
real vanilla sheet in here still lines up with bossbar.json.

The art is derived from the base letters already on the sheet: lowercase keeps its shape
and takes the diaeresis on the free top row; uppercase is one row too tall for that, so
it is compressed by one duplicated row, which is what the vanilla font does too.

Idempotent - it clears each target cell before drawing, so re-running changes nothing.

Usage:
    python3 resource-pack/tools/draw_ascii_extras.py
"""

import os
import struct
import zlib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHEET = os.path.join(REPO_ROOT, "resource-pack", "src", "assets",
                     "nordtal", "textures", "font", "ascii.png")

CELL = 8
WHITE = (255, 255, 255, 255)

# character -> (row, column) in the 16x16 sheet, from Minecraft's own ascii.png map.
#   row  8: C u e a a a a c e e e i i i A A
#   row  9: E ae AE o o o u u y O U o £ O x f
#   row 14: a ss G p S s u t F O O d oo o e n
PLACEMENT = {
    "ü": (8, 1),
    "ä": (8, 4),
    "Ä": (8, 14),
    "ö": (9, 4),
    "Ö": (9, 9),
    "Ü": (9, 10),
    "ß": (14, 1),
}

# 8x8 art. '#' is an opaque white pixel, '.' is transparent. The glyph's advance is
# derived by Minecraft from the rightmost occupied column, so the trailing dots are not
# padding that costs anything - they are just the rest of the cell.
ART = {
    # Lowercase: the base letter untouched at rows 2..6, diaeresis on row 0.
    "ä": [".#.#....",
          "........",
          ".###....",
          "....#...",
          ".####...",
          "#...#...",
          ".####...",
          "........"],
    "ö": [".#.#....",
          "........",
          ".###....",
          "#...#...",
          "#...#...",
          "#...#...",
          ".###....",
          "........"],
    "ü": [".#.#....",
          "........",
          "#...#...",
          "#...#...",
          "#...#...",
          "#...#...",
          ".####...",
          "........"],
    # Uppercase: seven rows compressed into six by dropping one repeated stem row, so
    # the diaeresis fits on row 0 without the letter growing out of its cell.
    "Ä": [".#.#....",
          ".###....",
          "#...#...",
          "#####...",
          "#...#...",
          "#...#...",
          "#...#...",
          "........"],
    "Ö": [".#.#....",
          ".###....",
          "#...#...",
          "#...#...",
          "#...#...",
          "#...#...",
          ".###....",
          "........"],
    "Ü": [".#.#....",
          "#...#...",
          "#...#...",
          "#...#...",
          "#...#...",
          "#...#...",
          ".###....",
          "........"],
    # Eszett: an ascender-height stem with the upper bowl closed and the lower one open
    # to the right, which is what distinguishes it from a B at this size.
    "ß": ["........",
          ".##.....",
          "#..#....",
          "#.#.....",
          "#..#....",
          "#..#....",
          "#.#.....",
          "........"],
}


def decode(path):
    """Returns (width, height, rows) where rows is a list of RGBA bytearrays."""
    data = open(path, "rb").read()
    width, height = struct.unpack(">II", data[16:24])
    depth, colour, interlace = data[24], data[25], data[28]
    if (depth, colour, interlace) != (8, 6, 0):
        raise SystemExit("%s is not 8-bit RGBA non-interlaced" % path)

    idat, i = b"", 8
    while i < len(data):
        length = struct.unpack(">I", data[i:i + 4])[0]
        if data[i + 4:i + 8] == b"IDAT":
            idat += data[i + 8:i + 8 + length]
        i += 12 + length

    raw = zlib.decompress(idat)
    bpp, stride = 4, width * 4
    rows, previous, p = [], bytearray(stride), 0
    for _ in range(height):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = previous[x]
            c = previous[x - bpp] if x >= bpp else 0
            if filt == 1:
                line[x] = (line[x] + a) & 255
            elif filt == 2:
                line[x] = (line[x] + b) & 255
            elif filt == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif filt == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pred) & 255
        rows.append(line)
        previous = line
    return width, height, rows


def encode(path, width, height, rows):
    def chunk(tag, payload):
        body = tag + payload
        return (struct.pack(">I", len(payload)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    raw = bytearray()
    for line in rows:
        raw.append(0)  # filter type 0 (none), same as generate_dummy_textures.py
        raw.extend(line)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def main():
    width, height, rows = decode(SHEET)
    if (width, height) != (128, 128):
        raise SystemExit("expected a 128x128 sheet, got %dx%d" % (width, height))

    for character, (cell_y, cell_x) in sorted(PLACEMENT.items()):
        art = ART[character]
        for y in range(CELL):
            for x in range(CELL):
                pixel = WHITE if art[y][x] == "#" else (0, 0, 0, 0)
                row = rows[cell_y * CELL + y]
                base = (cell_x * CELL + x) * 4
                row[base:base + 4] = bytes(pixel)
        print("drew %s at row %d column %d" % (character, cell_y, cell_x))

    encode(SHEET, width, height, rows)
    print("wrote %s" % os.path.relpath(SHEET, REPO_ROOT))
    print("")
    print("bossbar.json's chars table has to name them at the same positions:")
    for character, (cell_y, cell_x) in sorted(PLACEMENT.items(), key=lambda e: e[1]):
        print("   row %2d, column %2d -> %s" % (cell_y, cell_x, character))


if __name__ == "__main__":
    main()
