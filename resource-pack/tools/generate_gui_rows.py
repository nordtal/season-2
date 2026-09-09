#!/usr/bin/env python3
"""Draws the row furniture the list menus are built from, and the six `nordtal:gui_rN` fonts.

WHY SIX FONTS AND NOT SIX HUNDRED CODE POINTS. A menu panel is a glyph in the inventory
title (docs/presentation.md section 2), and the only way a glyph moves vertically is its
font's `ascent`. A list menu wants the *same* picture - a pill, an icon, a line of text -
on any of the six chest rows, so the row has to be carried by something. Carrying it in
the code point costs one code point per (picture, row) and is impossible for text, which
is not a code point we choose. Carrying it in the FONT costs one font per row and nothing
per picture: `nordtal:gui_r2` is "everything, drawn on chest row 2".

So each of the six files below declares exactly the same characters at the three ascents a
row has, and a renderer picks the font instead of picking a glyph:

    furniture (14 px tall, top y = 19 + 18r)   ascent  -6 - 18r
    icons      (8 px tall, top y = 22 + 18r)   ascent  -9 - 18r
    text       (5 px tall, top y = 23 + 18r)   ascent -10 - 18r

All three are centred in the same 18 px cell: 14 is the pill's own height inset 2 from the
cell, and 8 and 5 are centred inside that pill. The one that surprises is the text: five
rows centred in fourteen lands at 23 and not 22, which is the same "+1" the artifact's own
renderer carries, and getting it wrong puts every line one pixel high in every menu at
once.

THE 5 PX SHEET IS NOT NEW ART. It is the table from the owner's design artifact
(`bloecke/menue-entwuerfe.html`, `SMALL_SRC`), transcribed character for character - the
decision on 2026-09-08 was "this sheet, unchanged", not "a sheet in this style". It is all
capitals with no descenders: at 8 px almost every POI name was cut off, at 5 px thirty-eight
characters fit across a window. Lower case is folded onto capitals on the way in, except ß,
which has no single-character upper case; `MenuFont` in :common is the Java side of that
fold and reads its widths from the table this tool exports.

Pure standard library. The PNG codec is pngio.py, shared with the other generators.

Usage:
    python3 resource-pack/tools/generate_gui_rows.py
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import read_png, rightmost_drawn_column, write_png  # noqa: E402  (path set above)
from generate_gui_panels import (  # noqa: E402  (path set above)
    PALETTE, ROW_PITCH, SLOT_ORIGIN_Y, blank, chamfer, outline, px, rect,
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(REPO_ROOT, "resource-pack", "src", "assets")
TEXTURES = os.path.join(ASSETS, "nordtal", "textures", "ui", "gui")
FONTS = os.path.join(ASSETS, "nordtal", "font")
ADVANCES_OUT = os.path.join(REPO_ROOT, "common", "src", "main", "resources", "nordtal", "menu",
                            "gui-row-advances.properties")

ROWS = 6

# --- Where a row's three layers sit, in window pixels. ------------------------------
#
# The pill is inset 2 from its slot cell, exactly as a balloon card is, so a row of
# furniture ends within two pixels of the slots that make it clickable.
INSET = 2
FURNITURE_TOP = SLOT_ORIGIN_Y + INSET                 # 19
FURNITURE_HEIGHT = ROW_PITCH - 2 * INSET              # 14
ICON_SIZE = 8
ICON_TOP = FURNITURE_TOP + (FURNITURE_HEIGHT - ICON_SIZE) // 2      # 22
TEXT_HEIGHT = 5
TEXT_TOP = FURNITURE_TOP + (FURNITURE_HEIGHT - TEXT_HEIGHT) // 2    # 23

# The title's baseline. A glyph's top lands at BASELINE - ascent, which is the one piece of
# arithmetic every ascent below is derived from rather than tabulated.
BASELINE = 13

# The full width of a one-per-row list entry: the window's slot area inset 2 on each side.
ROW_WIDTH = 158

# --- Code points. The block the owner reserved for these building blocks is U+FE100+. ---
CP_PILL = 0xFE100
CP_FRAME = 0xFE101
CP_BUTTON_WIDE = 0xFE102
CP_BUTTON_SMALL = 0xFE103
CP_BUTTON_SMALL_OFF = 0xFE104
CP_ICONS = 0xFE110          # the icon sheet's first cell; the rest follow in order

BUTTON_WIDE_WIDTH = 52
BUTTON_SMALL_WIDTH = 14

# --- The palette. Everything here is the panel's own, plus the three button styles. ---
#
# The buttons are art and not text, so they carry their own colours the way the balloon's
# four world cards do; docs/presentation.md's five-colour rule is about what a *sentence*
# is painted, and none of these is a sentence.
PILL_FILL = (214, 214, 218, 255)
PILL_LINE = (150, 150, 156, 255)
PILL_LIGHT = (232, 232, 236, 255)
HERE_FRAME = (255, 255, 255, 235)          # the same white travel_here uses

BUTTONS = {
    # fill, inner light (top/left), inner dark (bottom/right), outline
    "wide": ((176, 74, 66, 255), (222, 130, 120, 255), (110, 40, 34, 255), (70, 20, 16, 255)),
    "small": ((172, 172, 178, 255), (206, 207, 212, 255), (118, 118, 124, 255), (60, 60, 66, 255)),
    "small_off": ((188, 188, 192, 255), (204, 205, 208, 255), (160, 160, 164, 255), (140, 140, 144, 255)),
}

CHAMFER = (1,)              # one pixel off each corner, the size a 14 px tall tile can carry

# --- The 5 px sheet, transcribed from the artifact's SMALL_SRC. ----------------------
#
# Five rows per glyph, '#' a pixel and '.' nothing. Widths vary: most are three, M and W
# five, N four, and the punctuation as narrow as one. The advance is the drawn width plus
# one, which the client works out for itself from the pixels - so this table is the whole
# specification of the font, and MenuFont's copy of the widths is exported from it below.
SMALL = {
    'A': '.#.|#.#|###|#.#|#.#', 'B': '##.|#.#|##.|#.#|##.', 'C': '.##|#..|#..|#..|.##',
    'D': '##.|#.#|#.#|#.#|##.', 'E': '###|#..|##.|#..|###', 'F': '###|#..|##.|#..|#..',
    'G': '.##|#..|#.#|#.#|.##', 'H': '#.#|#.#|###|#.#|#.#', 'I': '###|.#.|.#.|.#.|###',
    'J': '..#|..#|..#|#.#|.#.', 'K': '#.#|#.#|##.|#.#|#.#', 'L': '#..|#..|#..|#..|###',
    'M': '#...#|##.##|#.#.#|#...#|#...#', 'N': '#..#|##.#|#.##|#..#|#..#',
    'O': '.#.|#.#|#.#|#.#|.#.', 'P': '##.|#.#|##.|#..|#..', 'Q': '.#.|#.#|#.#|#.#|.##',
    'R': '##.|#.#|##.|#.#|#.#', 'S': '.##|#..|.#.|..#|##.', 'T': '###|.#.|.#.|.#.|.#.',
    'U': '#.#|#.#|#.#|#.#|.#.', 'V': '#.#|#.#|#.#|#.#|.#.',
    'W': '#...#|#...#|#.#.#|#.#.#|.#.#.', 'X': '#.#|#.#|.#.|#.#|#.#',
    'Y': '#.#|#.#|.#.|.#.|.#.', 'Z': '###|..#|.#.|#..|###',
    '0': '.#.|#.#|#.#|#.#|.#.', '1': '.#.|##.|.#.|.#.|###', '2': '##.|..#|.#.|#..|###',
    '3': '##.|..#|.#.|..#|##.', '4': '#.#|#.#|###|..#|..#', '5': '###|#..|##.|..#|##.',
    '6': '.##|#..|##.|#.#|.#.', '7': '###|..#|.#.|.#.|.#.', '8': '.#.|#.#|.#.|#.#|.#.',
    '9': '.#.|#.#|.##|..#|##.',
    '.': '.|.|.|.|#', ',': '.|.|.|#|#', ':': '.|#|.|#|.', '/': '..#|..#|.#.|#..|#..',
    '-': '...|...|###|...|...', '+': '...|.#.|###|.#.|...', '%': '#.#|..#|.#.|#..|#.#',
    '(': '.#|#.|#.|#.|.#', ')': '#.|.#|.#|.#|#.', '!': '#|#|#|.|#', '?': '##.|..#|.#.|...|.#.',
    "'": '#|#|.|.|.', '"': '#.#|#.#|...|...|...',
    'Ä': '#.#|.#.|#.#|###|#.#', 'Ö': '#.#|.#.|#.#|#.#|.#.', 'Ü': '#.#|...|#.#|#.#|.#.',
    'ß': '.#.|#.#|##.|#.#|##.',
    '∙': '..|..|.#|..|..',
    '█': '####|####|####|####|####', '░': '#.#.|.#.#|#.#.|.#.#|#.#.',
}

# The sheet's own layout: eight columns, seven rows, 56 cells and 56 characters, so no cell
# is unclaimed - ResourcePackTest fails both a declared character with no pixels and a drawn
# cell no character points at, and an exact grid is the cheapest way to satisfy both.
SHEET_ROWS = [
    "ABCDEFGH",
    "IJKLMNOP",
    "QRSTUVWX",
    "YZ012345",
    "6789.,:/",
    "-+%()!?'",
    '"ÄÖÜß∙█░',
]

# A space is drawn by nothing at all, so it cannot be a cell in the sheet: an empty cell has
# no rightmost drawn column and the client would advance it one pixel. It is a `space`
# provider instead, at the width the artifact gives it (2) plus the usual one.
SPACE_ADVANCE = 3

# The negative and positive cursor moves, the same powers of two nordtal:gui carries. Every
# row font needs them too: a row is composed left to right - pill, then icon, then text -
# and the walk between two pictures inside one font has to happen without leaving it.
#
# The code point is the block plus the DECIMAL digits of the step, which is the convention
# gui.json, board.json and bossbar.json already use: -16 is U+FF016 and not U+FF010. The
# positive block mirrors it one bit higher, so a positive advance is its negative with the
# 0x800 set. Positive advances did not exist anywhere in this pack until now, because
# nothing in a menu title ever moved right - a row does, between its pill and its label.
SHIFTS = (1, 2, 4, 8, 16, 32, 64, 128)
SHIFT_MINUS = 0xFF000
SHIFT_PLUS = 0xFF800


def shift_code_point(step, negative):
    return (SHIFT_MINUS if negative else SHIFT_PLUS) + int(f"{step:03d}", 16)


def icon(rows):
    """An 8x8 pictogram from the artifact's PICT table, white so a component can tint it."""
    buf = blank(ICON_SIZE, ICON_SIZE, (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell == '#':
                px(buf, ICON_SIZE, x, y, (255, 255, 255, 255))
    return buf


# The three entry kinds /navigate can point at, then the three controls. Drawn white and
# tinted by the component, which is the rule section 5 of docs/presentation.md states: white
# art can be painted any colour, dark art cannot be painted lighter.
ICONS = {
    # A house: the world's spawn, the one place everybody knows.
    "spawn": ('...##...', '..####..', '.######.', '########',
              '.######.', '.##..##.', '.##..##.', '.##..##.'),
    # The artifact's skull, verbatim.
    "death": ('..####..', '.######.', '##.##.##', '########',
              '.######.', '..####..', '..#..#..', '..#..#..'),
    # The artifact's map pin, verbatim.
    "poi": ('..####..', '.##..##.', '.##..##.', '.######.',
            '..####..', '...##...', '...##...', '....#...'),
    # The artifact's cross.
    "stop": ('##....##', '.##..##.', '..####..', '...##...',
             '..####..', '.##..##.', '##....##', '........'),
    # Both arrows span columns 2..5 of their cell, so the two are mirror images and land on the
    # same middle when a page button centres them. Drawn on an even width they cannot come to a
    # single apex, which is why the tip is two rows tall.
    "prev": ('.....#..', '....##..', '...###..', '..####..',
             '..####..', '...###..', '....##..', '.....#..'),
    "next": ('..#.....', '..##....', '..###...', '..####..',
             '..####..', '..###...', '..##....', '..#.....'),
}

ICON_ORDER = ("spawn", "death", "poi", "stop", "prev", "next")


def text_sheet():
    """The 5 px sheet: eight columns of five pixels, seven rows of five."""
    cell = 5
    width, height = cell * len(SHEET_ROWS[0]), cell * len(SHEET_ROWS)
    buf = blank(width, height, (0, 0, 0, 0))
    for row_index, row in enumerate(SHEET_ROWS):
        for column_index, character in enumerate(row):
            glyph = SMALL[character].split('|')
            assert len(glyph) == TEXT_HEIGHT, character
            assert len({len(line) for line in glyph}) == 1, character
            assert len(glyph[0]) <= cell, character
            for y, line in enumerate(glyph):
                for x, pixel in enumerate(line):
                    if pixel == '#':
                        px(buf, width, column_index * cell + x, row_index * cell + y,
                           (255, 255, 255, 255))
    return width, height, bytes(buf)


def icon_sheet():
    """The six 8 x 8 pictograms, side by side in one row."""
    width, height = ICON_SIZE * len(ICON_ORDER), ICON_SIZE
    buf = blank(width, height, (0, 0, 0, 0))
    for index, name in enumerate(ICON_ORDER):
        for y, row in enumerate(ICONS[name]):
            for x, pixel in enumerate(row):
                if pixel == '#':
                    px(buf, width, index * ICON_SIZE + x, y, (255, 255, 255, 255))
    return width, height, bytes(buf)


def pill(width):
    """One list entry's plate: the panel's own pill, with transparent corners.

    The artifact paints the four chamfered corners in the panel's ground grey, because it
    draws onto the panel. A glyph does not - it is laid over one - so the corners are cut
    out and the ground shows through. Painting them grey would work today and be a grey
    notch the day a pill lands on anything but flat ground.
    """
    height = FURNITURE_HEIGHT
    buf = blank(width, height, PILL_FILL)
    outline(buf, width, 0, 0, width - 1, height - 1, PILL_LINE)
    rect(buf, width, 1, 1, width - 2, 1, PILL_LIGHT)
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, CHAMFER, PILL_LINE)
    return width, height, bytes(buf)


def frame(width):
    """The 'this is the active one' marker: a 2 px white frame, hollow, over a pill."""
    height = FURNITURE_HEIGHT
    buf = blank(width, height, (0, 0, 0, 0))
    outline(buf, width, 0, 0, width - 1, height - 1, HERE_FRAME)
    outline(buf, width, 1, 1, width - 2, height - 2, HERE_FRAME)
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, CHAMFER, (0, 0, 0, 0))
    for cx, cy, dx, dy in ((0, 0, 1, 1), (width - 1, 0, -1, 1),
                           (0, height - 1, 1, -1), (width - 1, height - 1, -1, -1)):
        for row, count in enumerate(CHAMFER):
            px(buf, width, cx + dx * count, cy + dy * row, HERE_FRAME)
    return width, height, bytes(buf)


def button(width, style):
    """A pressable plate, the shape vanilla's own buttons have: lit top-left, shaded bottom-right."""
    height = FURNITURE_HEIGHT
    fill, light, dark, edge = BUTTONS[style]
    buf = blank(width, height, fill)
    rect(buf, width, 1, 1, width - 2, 1, light)
    rect(buf, width, 1, 1, 1, height - 2, light)
    rect(buf, width, 1, height - 2, width - 2, height - 2, dark)
    rect(buf, width, width - 2, 1, width - 2, height - 2, dark)
    outline(buf, width, 0, 0, width - 1, height - 1, edge)
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, CHAMFER, edge)
    return width, height, bytes(buf)


def assert_full_width(path, expected):
    """A glyph's advance is its rightmost drawn column plus two; the Java side assumes width + 1.

    Every plate here is opaque to its own right edge, so this holds - the check is here for
    the day somebody redraws one with a transparent margin, which would silently narrow the
    advance and pull everything drawn after it left.
    """
    width, height, rgba = read_png(path)
    rightmost = rightmost_drawn_column(rgba, width, height)
    assert rightmost == expected - 1, \
        f"{path}: rightmost drawn column is {rightmost}, so its advance is {rightmost + 2}" \
        f" and not {expected + 1}"


def font(row, out):
    """One `nordtal:gui_rN`: the same characters as every other row, three ascents lower."""
    furniture = BASELINE - (FURNITURE_TOP + ROW_PITCH * row)
    icons = BASELINE - (ICON_TOP + ROW_PITCH * row)
    text = BASELINE - (TEXT_TOP + ROW_PITCH * row)

    advances = {" ": SPACE_ADVANCE}
    for step in SHIFTS:
        advances[chr(shift_code_point(step, True))] = -step
        advances[chr(shift_code_point(step, False))] = step

    providers = [{"type": "space", "advances": advances}]
    for code_point, name, width in (
            (CP_PILL, "row_pill", ROW_WIDTH),
            (CP_FRAME, "row_frame", ROW_WIDTH),
            (CP_BUTTON_WIDE, "row_button_wide", BUTTON_WIDE_WIDTH),
            (CP_BUTTON_SMALL, "row_button_small", BUTTON_SMALL_WIDTH),
            (CP_BUTTON_SMALL_OFF, "row_button_small_off", BUTTON_SMALL_WIDTH)):
        providers.append({
            "type": "bitmap",
            "file": f"nordtal:ui/gui/{name}.png",
            "ascent": furniture,
            "height": FURNITURE_HEIGHT,
            "chars": [chr(code_point)],
        })
    providers.append({
        "type": "bitmap",
        "file": "nordtal:ui/gui/row_icons.png",
        "ascent": icons,
        "height": ICON_SIZE,
        "chars": ["".join(chr(CP_ICONS + index) for index in range(len(ICON_ORDER)))],
    })
    providers.append({
        "type": "bitmap",
        "file": "nordtal:ui/gui/row_text.png",
        "ascent": text,
        "height": TEXT_HEIGHT,
        "chars": list(SHEET_ROWS),
    })

    path = os.path.join(out, f"gui_r{row}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"providers": providers}, f, indent=4)
        f.write("\n")
    print(f"wrote {os.path.relpath(path, REPO_ROOT)} (ascents {furniture} / {icons} / {text})")


def export_advances():
    """Writes the row fonts' advances where :common's MenuFont can read them.

    Same rule and same reason as export_bossbar_advances.py: the server composes the row, so
    the server has to know how wide "BAECKEREI AM FLUSS" is - and it can, because every
    advance here is a property of a PNG in this repository. A redrawn glyph whose rightmost
    column moved is caught by MenuFontTest at `check` rather than by somebody noticing a
    line overflowing its pill on a client.
    """
    table = {ord(" "): SPACE_ADVANCE}

    width, height, rgba = read_png(os.path.join(TEXTURES, "row_text.png"))
    cell_width, cell_height = width // len(SHEET_ROWS[0]), height // len(SHEET_ROWS)
    for row_index, row in enumerate(SHEET_ROWS):
        for column_index, character in enumerate(row):
            rightmost = rightmost_drawn_column(rgba, width, height, column_index * cell_width,
                                               row_index * cell_height, cell_width, cell_height)
            table[ord(character)] = rightmost + 2

    width, height, rgba = read_png(os.path.join(TEXTURES, "row_icons.png"))
    for index in range(len(ICON_ORDER)):
        rightmost = rightmost_drawn_column(rgba, width, height, index * ICON_SIZE, 0,
                                           ICON_SIZE, ICON_SIZE)
        table[CP_ICONS + index] = rightmost + 2

    for code_point, name, plate in ((CP_PILL, "row_pill", ROW_WIDTH),
                                    (CP_FRAME, "row_frame", ROW_WIDTH),
                                    (CP_BUTTON_WIDE, "row_button_wide", BUTTON_WIDE_WIDTH),
                                    (CP_BUTTON_SMALL, "row_button_small", BUTTON_SMALL_WIDTH),
                                    (CP_BUTTON_SMALL_OFF, "row_button_small_off",
                                     BUTTON_SMALL_WIDTH)):
        table[code_point] = plate + 1

    for step in SHIFTS:
        table[shift_code_point(step, True)] = -step
        table[shift_code_point(step, False)] = step

    os.makedirs(os.path.dirname(ADVANCES_OUT), exist_ok=True)
    with open(ADVANCES_OUT, "w", encoding="utf-8") as f:
        f.write("# GENERATED by resource-pack/tools/generate_gui_rows.py - do not edit.\n")
        f.write("# How far each nordtal:gui_rN code point moves the cursor, in GUI pixels,\n")
        f.write("# derived from the row fonts and their PNGs the way the client derives it.\n")
        f.write("# MenuFontTest fails the build if this file and the pack disagree.\n")
        for code_point in sorted(table):
            f.write(f"{code_point:X}={table[code_point]}\n")
    print(f"wrote {os.path.relpath(ADVANCES_OUT, REPO_ROOT)} ({len(table)} code points)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=TEXTURES)
    parser.add_argument("--fonts", default=FONTS)
    arguments = parser.parse_args()

    for name, (width, height, data) in (
            ("row_text", text_sheet()),
            ("row_icons", icon_sheet()),
            ("row_pill", pill(ROW_WIDTH)),
            ("row_frame", frame(ROW_WIDTH)),
            ("row_button_wide", button(BUTTON_WIDE_WIDTH, "wide")),
            ("row_button_small", button(BUTTON_SMALL_WIDTH, "small")),
            ("row_button_small_off", button(BUTTON_SMALL_WIDTH, "small_off"))):
        write_png(os.path.join(arguments.out, f"{name}.png"), width, height, data, REPO_ROOT)

    for name, expected in (("row_pill", ROW_WIDTH), ("row_frame", ROW_WIDTH),
                           ("row_button_wide", BUTTON_WIDE_WIDTH),
                           ("row_button_small", BUTTON_SMALL_WIDTH),
                           ("row_button_small_off", BUTTON_SMALL_WIDTH)):
        assert_full_width(os.path.join(arguments.out, f"{name}.png"), expected)

    for row in range(ROWS):
        font(row, arguments.fonts)

    export_advances()


if __name__ == "__main__":
    main()
