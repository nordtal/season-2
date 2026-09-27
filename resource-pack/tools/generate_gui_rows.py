#!/usr/bin/env python3
"""Draws the row furniture the list menus are built from, and the six `nordtal:gui_rN` fonts.

A glyph moves vertically only through its font's `ascent`, so each chest row gets its own font
with the same characters. Each declares them at the three ascents a row has:

    furniture (14 px tall, top y = 19 + 18r)   ascent  -6 - 18r
    icons      (8 px tall, top y = 22 + 18r)   ascent  -9 - 18r
    text       (5 px tall, top y = 23 + 18r)   ascent -10 - 18r

All three are centred in the same 18 px cell; five text rows centred in fourteen land at 23.

The 5 px font is capitals only. `MenuFont` in :common folds lower case onto it, except ss,
and reads the widths this tool exports.

Standard library only; the PNG codec is pngio.py.

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

# Where a row's three layers sit, in window pixels. The pill is inset 2 from its slot cell.
INSET = 2
FURNITURE_TOP = SLOT_ORIGIN_Y + INSET                 # 19
FURNITURE_HEIGHT = ROW_PITCH - 2 * INSET              # 14
ICON_SIZE = 8
ICON_TOP = FURNITURE_TOP + (FURNITURE_HEIGHT - ICON_SIZE) // 2      # 22
TEXT_HEIGHT = 5
TEXT_TOP = FURNITURE_TOP + (FURNITURE_HEIGHT - TEXT_HEIGHT) // 2    # 23

# The title's baseline; a glyph's top lands at BASELINE minus its ascent.
BASELINE = 13

# The full width of a one-per-row list entry: the window's slot area inset 2 on each side.
ROW_WIDTH = 158

# Code points, in the block from U+FE100.
CP_PILL = 0xFE100
CP_FRAME = 0xFE101
CP_BUTTON_WIDE = 0xFE102
CP_BUTTON_SMALL = 0xFE103
CP_BUTTON_SMALL_OFF = 0xFE104
CP_PILL_DARK = 0xFE105
CP_BUTTON_CONFIRM = 0xFE106
CP_BUTTON_TAKE = 0xFE107
CP_PILL_SHORT = 0xFE108
CP_ICONS = 0xFE110          # the icon sheet's first cell; the rest follow in order

BUTTON_WIDE_WIDTH = 52
BUTTON_SMALL_WIDTH = 14
# Three slot cells inset 2 on both sides; BUTTON_WIDE is inset on the left only.
BUTTON_CONFIRM_WIDTH = 3 * ROW_PITCH - 2 * INSET
# Four slot cells inset 2, for the grave's longer "take all".
BUTTON_TAKE_WIDTH = 4 * ROW_PITCH - 2 * INSET
# Seven slot cells inset 2, for a row whose last two cells carry controls.
PILL_SHORT_WIDTH = 7 * ROW_PITCH - 2 * INSET

# The buttons are art, not text, so the five-colour rule does not cover them.
PILL_FILL = (214, 214, 218, 255)
# The darker pill is a heading, so it must not look like an entry you can click.
PILL_DARK_FILL = (178, 178, 182, 255)
PILL_LINE = (150, 150, 156, 255)
PILL_LIGHT = (232, 232, 236, 255)
HERE_FRAME = (255, 255, 255, 235)          # the same white travel_here uses

BUTTONS = {
    # fill, inner light (top/left), inner dark (bottom/right), outline
    "wide": ((176, 74, 66, 255), (222, 130, 120, 255), (110, 40, 34, 255), (70, 20, 16, 255)),
    "small": ((172, 172, 178, 255), (206, 207, 212, 255), (118, 118, 124, 255), (60, 60, 66, 255)),
    "small_off": ((188, 188, 192, 255), (204, 205, 208, 255), (160, 160, 164, 255), (140, 140, 144, 255)),
    # Gold, for the only button whose action cannot be undone by clicking again.
    "primary": ((176, 138, 74, 255), (222, 192, 120, 255), (120, 90, 40, 255), (70, 50, 26, 255)),
}

CHAMFER = (1,)              # one pixel off each corner, the size a 14 px tall tile can carry

# The 5 px font, '#' a pixel. The advance is the drawn width plus one, so this table is the
# whole font and MenuFont's widths are exported from it. `0`, `O` and `8` differ by silhouette
# and stay three pixels wide, so columns of numbers line up.
SMALL = {
    'A': '.#.|#.#|###|#.#|#.#', 'B': '##.|#.#|##.|#.#|##.', 'C': '.##|#..|#..|#..|.##',
    'D': '##.|#.#|#.#|#.#|##.', 'E': '###|#..|##.|#..|###', 'F': '###|#..|##.|#..|#..',
    'G': '.##|#..|#.#|#.#|.##', 'H': '#.#|#.#|###|#.#|#.#', 'I': '###|.#.|.#.|.#.|###',
    'J': '..#|..#|..#|#.#|.#.', 'K': '#.#|#.#|##.|#.#|#.#', 'L': '#..|#..|#..|#..|###',
    'M': '#...#|##.##|#.#.#|#...#|#...#', 'N': '#..#|##.#|#.##|#..#|#..#',
    'O': '.#.|#.#|#.#|#.#|.#.', 'P': '##.|#.#|##.|#..|#..', 'Q': '.#.|#.#|#.#|#.#|.##',
    'R': '##.|#.#|##.|#.#|#.#', 'S': '.##|#..|.#.|..#|##.', 'T': '###|.#.|.#.|.#.|.#.',
    # U is flat-bottomed and V tapers, so the two never render alike.
    'U': '#.#|#.#|#.#|#.#|###', 'V': '#.#|#.#|#.#|#.#|.#.',
    'W': '#...#|#...#|#.#.#|#.#.#|.#.#.', 'X': '#.#|#.#|.#.|#.#|#.#',
    'Y': '#.#|#.#|.#.|.#.|.#.', 'Z': '###|..#|.#.|#..|###',
    '0': '###|#.#|#.#|#.#|###', '1': '.#.|##.|.#.|.#.|###', '2': '##.|..#|.#.|#..|###',
    '3': '##.|..#|.#.|..#|##.', '4': '#.#|#.#|###|..#|..#', '5': '###|#..|##.|..#|##.',
    '6': '.##|#..|##.|#.#|.#.', '7': '###|..#|.#.|.#.|.#.', '8': '###|#.#|.#.|#.#|###',
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

# Eight by seven, exactly one character per cell, since ResourcePackTest fails both an empty
# declared cell and a drawn one nothing points at.
SHEET_ROWS = [
    "ABCDEFGH",
    "IJKLMNOP",
    "QRSTUVWX",
    "YZ012345",
    "6789.,:/",
    "-+%()!?'",
    '"ÄÖÜß∙█░',
]

# A space is a `space` provider, since an empty cell would advance one pixel.
SPACE_ADVANCE = 3

# Cursor moves, so a row composes pill, icon and text without leaving its font. The code point
# is the block plus the step's decimal digits, as in the other fonts: -16 is U+FF016. A positive
# move is its negative with 0x800 set.
SHIFTS = (1, 2, 4, 8, 16, 32, 64, 128)
SHIFT_MINUS = 0xFF000
SHIFT_PLUS = 0xFF800


def shift_code_point(step, negative):
    return (SHIFT_MINUS if negative else SHIFT_PLUS) + int(f"{step:03d}", 16)


def icon(rows):
    """Draws an 8 x 8 pictogram in white, so a component can tint it."""
    buf = blank(ICON_SIZE, ICON_SIZE, (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell == '#':
                px(buf, ICON_SIZE, x, y, (255, 255, 255, 255))
    return buf


# The entry kinds /navigate can point at, the controls, then the objective menu's states.
ICONS = {
    # A house, for spawn.
    "spawn": ('...##...', '..####..', '.######.', '########',
              '.######.', '.##..##.', '.##..##.', '.##..##.'),

    "death": ('..####..', '.######.', '##.##.##', '########',
              '.######.', '..####..', '..#..#..', '..#..#..'),

    "poi": ('..####..', '.##..##.', '.##..##.', '.######.',
            '..####..', '...##...', '...##...', '....#...'),

    "stop": ('##....##', '.##..##.', '..####..', '...##...',
             '..####..', '.##..##.', '##....##', '........'),
    # Both arrows span columns 2 to 5, so they mirror each other and centre alike.
    "prev": ('.....#..', '....##..', '...###..', '..####..',
             '..####..', '...###..', '....##..', '.....#..'),
    "next": ('..#.....', '..##....', '..###...', '..####..',
             '..####..', '..###...', '..##....', '..#.....'),
    # An objective card's icon is its kind, or the tick once it is done.
    "handin": ('...##...', '...##...', '.######.', '..####..',
               '...##...', '........', '##....##', '########'),
    "statistic": ('...#####', '..##...#', '.#.##...', '...##...',
                  '..##....', '.##.....', '##......', '#.......'),
    "advancement": ('.#....#.', '.##..##.', '..####..', '.######.',
                    '##.##.##', '.######.', '..####..', '........'),
    "done": ('.......#', '......##', '.....##.', '#...##..',
             '##.##...', '.###....', '..#.....', '........'),
    # An experience orb, for aura.
    "aura": ('..####..', '.##.####', '##...###', '##..####',
             '########', '########', '.######.', '..####..'),
}

ICON_ORDER = ("spawn", "death", "poi", "stop", "prev", "next",
              "handin", "statistic", "advancement", "done", "aura")


def text_sheet():
    """Draws the 5 px sheet."""
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
    """Draws the pictograms side by side in one row."""
    width, height = ICON_SIZE * len(ICON_ORDER), ICON_SIZE
    buf = blank(width, height, (0, 0, 0, 0))
    for index, name in enumerate(ICON_ORDER):
        for y, row in enumerate(ICONS[name]):
            for x, pixel in enumerate(row):
                if pixel == '#':
                    px(buf, width, index * ICON_SIZE + x, y, (255, 255, 255, 255))
    return width, height, bytes(buf)


def pill(width, fill=PILL_FILL):
    """Draws one list entry's plate, with transparent corners so the ground beneath shows."""
    height = FURNITURE_HEIGHT
    buf = blank(width, height, fill)
    outline(buf, width, 0, 0, width - 1, height - 1, PILL_LINE)
    rect(buf, width, 1, 1, width - 2, 1, PILL_LIGHT)
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, CHAMFER, PILL_LINE)
    return width, height, bytes(buf)


def frame(width):
    """Draws the active entry's marker: a hollow 2 px white frame over a pill."""
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
    """Draws a button plate, lit top-left and shaded bottom-right as vanilla's."""
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


# Every row plate, once, for the font providers, the advance table and the writer alike.
# `builder` takes the width and returns (width, height, pixels).
PLATES = (
    (CP_PILL, "row_pill", ROW_WIDTH, lambda w: pill(w)),
    (CP_FRAME, "row_frame", ROW_WIDTH, lambda w: frame(w)),
    (CP_BUTTON_WIDE, "row_button_wide", BUTTON_WIDE_WIDTH, lambda w: button(w, "wide")),
    (CP_BUTTON_SMALL, "row_button_small", BUTTON_SMALL_WIDTH, lambda w: button(w, "small")),
    (CP_BUTTON_SMALL_OFF, "row_button_small_off", BUTTON_SMALL_WIDTH,
     lambda w: button(w, "small_off")),
    (CP_PILL_DARK, "row_pill_dark", ROW_WIDTH, lambda w: pill(w, PILL_DARK_FILL)),
    (CP_BUTTON_CONFIRM, "row_button_confirm", BUTTON_CONFIRM_WIDTH, lambda w: button(w, "primary")),
    (CP_BUTTON_TAKE, "row_button_take", BUTTON_TAKE_WIDTH, lambda w: button(w, "primary")),
    (CP_PILL_SHORT, "row_pill_short", PILL_SHORT_WIDTH, lambda w: pill(w)),
)


def assert_full_width(path, expected):
    """Fails unless the rightmost column is drawn; the Java side assumes an advance of width + 1."""
    width, height, rgba = read_png(path)
    rightmost = rightmost_drawn_column(rgba, width, height)
    assert rightmost == expected - 1, \
        f"{path}: rightmost drawn column is {rightmost}, so its advance is {rightmost + 2}" \
        f" and not {expected + 1}"


def font(row, out):
    """Writes one `nordtal:gui_rN` font: every row's characters at this row's ascents."""
    furniture = BASELINE - (FURNITURE_TOP + ROW_PITCH * row)
    icons = BASELINE - (ICON_TOP + ROW_PITCH * row)
    text = BASELINE - (TEXT_TOP + ROW_PITCH * row)

    advances = {" ": SPACE_ADVANCE}
    for step in SHIFTS:
        advances[chr(shift_code_point(step, True))] = -step
        advances[chr(shift_code_point(step, False))] = step

    providers = [{"type": "space", "advances": advances}]
    for code_point, name, width, _ in PLATES:
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
    """Writes the row fonts' advances for :common's MenuFont, which MenuFontTest checks."""
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

    for code_point, name, plate, _ in PLATES:
        table[code_point] = plate + 1

    for step in SHIFTS:
        table[shift_code_point(step, True)] = -step
        table[shift_code_point(step, False)] = step

    os.makedirs(os.path.dirname(ADVANCES_OUT), exist_ok=True)
    with open(ADVANCES_OUT, "w", encoding="utf-8") as f:
        f.write("# GENERATED by resource-pack/tools/generate_gui_rows.py; do not edit.\n")
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

    for name, (width, height, data) in (("row_text", text_sheet()),
                                        ("row_icons", icon_sheet())):
        write_png(os.path.join(arguments.out, f"{name}.png"), width, height, data, REPO_ROOT)

    for _, name, expected, builder in PLATES:
        width, height, data = builder(expected)
        write_png(os.path.join(arguments.out, f"{name}.png"), width, height, data, REPO_ROOT)
        assert_full_width(os.path.join(arguments.out, f"{name}.png"), expected)

    for row in range(ROWS):
        font(row, arguments.fonts)

    export_advances()


if __name__ == "__main__":
    main()
