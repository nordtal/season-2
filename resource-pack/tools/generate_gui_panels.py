#!/usr/bin/env python3
"""Draws the `nordtal:gui` menu surfaces: the six chest panels, and the balloon's travel panel
with its two state overlays.

WHAT A PANEL IS. A menu on this server is an ordinary chest inventory whose *title*
carries a bitmap glyph large enough to cover the whole window. The glyph is drawn from
a font with a large positive `ascent`, so it rises out of the title's baseline and sits
behind the slots; the slots themselves stay vanilla slots and draw on top of it. The
whole technique, its measurements and the three decisions that follow from it are in
docs/presentation.md section 2 - read that before changing a number here.

WHY SIX PANELS AND NOT ONE. A chest's window height is 114 + 18*rows, so the six sizes
are 132/150/168/186/204/222 px and a panel drawn for one row count is the wrong height
for another. All six are even, which is why they all centre identically; the moment a
menu becomes a hopper (imageHeight 133, odd) that stops being true, and a test asserts
no menu opens a non-chest inventory.

WHAT AN OVERLAY IS (2026-09-05). The travel panel bakes its four world tiles into one
image, because all four are always shown in fixed places. What varies per player is a
tile's *state* - locked, or "you are here" - and each state is a separate small glyph
the size of one tile, declared once per tile ROW in gui.json with the ascent that lands
it on that row, and drawn on top of the panel by walking the cursor back to the tile's
x. One base plus two overlays covers every combination; a panel per combination would
be twelve images that all change together the day the art does.

WHY THE ART IS PROGRAMMATIC. Same reason as generate_dummy_textures.py: it is anchored
to the *measurements* rather than to an image, so a hand-drawn panel of the same
dimensions drops in without a line of Java changing. The palette below is the whole
design surface - a real art pass is an edit to PALETTE and a redraw, not a rewrite.

THE LOOK (2026-09-05, owner's call). Light, in the manner of Origin Realms: vanilla's own
198-grey body so the frame and the player inventory beneath read as one window, a dark
outer line, vanilla's 3 px corner chamfer so nothing of the texture underneath peeks
out, and dark slot recesses. The boards stay dark - they hang in the world on a Text
Display's translucent ground - so generate_dummy_textures.py no longer copies this
palette; see the note there.

Pure standard library. The PNG codec is pngio.py, shared with the other generators.

Usage:
    python3 resource-pack/tools/generate_gui_panels.py [--out DIR]
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import read_png, rightmost_drawn_column, write_png  # noqa: E402  (path set above)
from generate_dummy_textures import Canvas  # noqa: E402  (path set above)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_OUT = os.path.join(REPO_ROOT, "resource-pack", "src", "assets",
                           "nordtal", "textures", "ui", "gui")

# --- The measurements. Every one of these is vanilla and none of them is ours. ------
#
# READ OFF THE PIXELS of the extracted 26.2 gui/container/generic_54.png on 2026-09-04,
# not taken from a tutorial and not from memory. What was actually done: decode the PNG,
# find the bounding box of everything that is not the transparent palette index (176 x
# 222), then find every row carrying the slots' dark shadow line. Those rows came back as
# 17, 35, 53, 71, 89, 107 for the container's six rows and 139, 157, 175, 197 for the
# player's three plus the hotbar - which is where the two FROM_BOTTOM numbers below come
# from, at the 6-row height of 222.
#
# Re-read on 2026-09-05 for the corners: vanilla leaves a diagonal of transparent pixels
# at each corner - three on the top row, two on the second, one on the third, mirrored -
# so a panel with square corners shows its own corner where vanilla shows the world.
# CORNER_CHAMFER below is that shape, and the panels cut exactly it.
#
# The copy that was measured lived in a scratchpad and is not kept, so this comment is the
# record. RE-MEASURE AT EVERY VERSION BUMP: 1.21.9 moved the villager trading result slot
# by one pixel, so this is not a theoretical risk.

WIDTH = 176                  # the drawn width of every chest window
HEIGHT_BASE = 114            # imageHeight = HEIGHT_BASE + 18 * rows
ROW_PITCH = 18               # one slot row, and one slot's outer size

# The slot CELL's top-left - the dark shadow pixel, not the 16 x 16 the item sits in. That
# distinction is the whole reason this was measured rather than remembered: the item area
# is at (8, 18) and every tutorial quotes that, but the cell that has to be drawn starts
# one pixel up and to the left.
SLOT_ORIGIN_X = 7
SLOT_ORIGIN_Y = 17
SLOT_COLUMNS = 9

TITLE_BAR_HEIGHT = 17        # the strip above the first slot cell, where the title sits

# The player's own inventory, which every chest screen also draws, as an offset from the
# bottom edge of the window: 222 - 139 and 222 - 197.
PLAYER_MAIN_FROM_BOTTOM = 83
PLAYER_HOTBAR_FROM_BOTTOM = 25

# Vanilla's corner: how many pixels are transparent on each of the first three rows/columns,
# counted from the corner inwards. Row 0 loses three, row 1 two, row 2 one.
CORNER_CHAMFER = (3, 2, 1)

# --- The travel panel's geometry: four tiles of 3 rows x 4 columns, column 4 the gap. ---
#
# A tile is drawn TILE_INSET pixels inside the slot cells it covers, so the two rows of
# tiles read as separate cards (there is no gap row between slot rows 2 and 3) and the
# clickable area still ends within two pixels of the art. Everything outside the four
# tiles is frame. The four positions are fixed - Nordtal, farm world / Nether, End - and
# the Java side (smp's BalloonMenu) carries the same slot map; TILE_* below is what its
# overlay offsets are derived from, through MenuTitleTest reading these PNGs back.
TILE_COLUMNS = 4
TILE_ROWS = 3
TILE_INSET = 2
TILE_WIDTH = TILE_COLUMNS * ROW_PITCH - 2 * TILE_INSET    # 68
TILE_HEIGHT = TILE_ROWS * ROW_PITCH - 2 * TILE_INSET      # 50
TILE_X = (SLOT_ORIGIN_X + TILE_INSET,                     # 9: columns 0..3
          SLOT_ORIGIN_X + 5 * ROW_PITCH + TILE_INSET)     # 99: columns 5..8
TILE_Y = (SLOT_ORIGIN_Y + TILE_INSET,                     # 19: rows 0..2
          SLOT_ORIGIN_Y + 3 * ROW_PITCH + TILE_INSET)     # 73: rows 3..5
TILE_CHAMFER = (2, 1)

# --- The palette. This is the design surface; everything else is arithmetic. --------

PALETTE = {
    "edge":       (28, 26, 30, 255),      # the outermost line, near-black like vanilla's
    "frame":      (198, 198, 198, 255),   # vanilla's own body grey, so ours and theirs are one
    "highlight":  (242, 242, 242, 255),   # 1 px light line inside the edge, top and left
    "shade":      (128, 128, 132, 255),   # 1 px dark line inside the edge, bottom and right
    "ground":     (198, 198, 198, 255),   # the panel's own field
    "title_bar":  (178, 178, 182, 255),   # the strip the title text sits on, a shade darker
    "accent":     (176, 138, 74, 255),    # one line under the title bar, and nothing else
    "slot":       (58, 58, 64, 255),      # a slot recess, dark - the Origin Realms cue
    "slot_edge":  (150, 150, 156, 255),   # its lit bottom-right
}

# One card per world. Fill, its darker outline, its lighter top-left highlight, and the
# pictogram tint - which is the fill lightened and drawn at partial alpha, so the symbol
# reads as embossed into the card rather than printed on it.
TILES = {
    "nordtal": {"fill": (82, 168, 84), "dark": (44, 108, 48), "light": (140, 210, 136)},
    "farm":    {"fill": (236, 168, 56), "dark": (170, 110, 24), "light": (250, 214, 130)},
    "nether":  {"fill": (206, 66, 58), "dark": (136, 34, 30), "light": (242, 140, 120)},
    "end":     {"fill": (128, 82, 190), "dark": (78, 44, 130), "light": (190, 150, 232)},
}
PICTOGRAM_ALPHA = 110

LOCKED_SHADE = (18, 18, 24, 150)          # laid over the whole tile
LOCK_COLOUR = (236, 236, 240)
HERE_FRAME = (255, 255, 255, 235)        # a 2 px frame, tile colour untouched inside


def blank(width, height, colour):
    return bytearray(bytes(colour) * width * height)


def px(buf, width, x, y, colour):
    i = (y * width + x) * 4
    buf[i:i + 4] = bytes(colour)


def blend(buf, width, x, y, colour):
    """Source-over, so a translucent pictogram or shade sits on what is already drawn."""
    i = (y * width + x) * 4
    r, g, b, a = colour
    if a >= 255:
        buf[i:i + 4] = bytes((r, g, b, 255))
        return
    dr, dg, db, da = buf[i], buf[i + 1], buf[i + 2], buf[i + 3]
    sa = a / 255.0
    out_a = sa + (da / 255.0) * (1 - sa)
    if out_a == 0:
        return

    def channel(s, d):
        return round((s * sa + d * (da / 255.0) * (1 - sa)) / out_a)

    buf[i:i + 4] = bytes((channel(r, dr), channel(g, dg), channel(b, db), round(out_a * 255)))


def rect(buf, width, x0, y0, x1, y1, colour):
    """Filled rectangle, inclusive of both corners - the same convention Boxes uses."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(buf, width, x, y, colour)


def outline(buf, width, x0, y0, x1, y1, colour):
    for x in range(x0, x1 + 1):
        px(buf, width, x, y0, colour)
        px(buf, width, x, y1, colour)
    for y in range(y0, y1 + 1):
        px(buf, width, x0, y, colour)
        px(buf, width, x1, y, colour)


def chamfer(buf, width, height, x0, y0, x1, y1, steps, edge):
    """Cuts vanilla's corner out of the rectangle and redraws the edge along the cut.

    `steps` is how many pixels go transparent on each successive row from the corner. The
    edge colour is then laid one pixel inside the cut, so the outline follows the diagonal
    rather than stopping at it.
    """
    corners = ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1))
    for cx, cy, dx, dy in corners:
        for row, count in enumerate(steps):
            for column in range(count):
                px(buf, width, cx + dx * column, cy + dy * row, (0, 0, 0, 0))
        # The diagonal just inside the cut, one pixel per row, is the edge.
        for row, count in enumerate(steps):
            px(buf, width, cx + dx * count, cy + dy * row, edge)
        # ...and the rows past the cut get their outermost pixel back as edge too, which the
        # rectangle outline already drew; nothing to do.


def slot_recess(buf, width, x, y):
    """One 18 x 18 slot cell at its shadow corner, drawn the way vanilla draws one.

    Vanilla's cell is a 1 px dark shadow along the top and left, a 16 x 16 field, and a
    1 px light edge along the bottom and right - so the lit pixels sit at x+17 and y+17,
    outside the field the item is drawn in. Copying that geometry is what makes our panel
    line up with the items and the hover highlight, both of which the client places from
    its own arithmetic and not from our texture.
    """
    rect(buf, width, x, y, x + 17, y + 17, PALETTE["slot"])
    for i in range(18):
        px(buf, width, x + 17, y + i, PALETTE["slot_edge"])
        px(buf, width, x + i, y + 17, PALETTE["slot_edge"])


def frame(buf, width, height):
    """The window's outer frame: edge, highlight top-left, shade bottom-right, chamfered."""
    outline(buf, width, 0, 0, width - 1, height - 1, PALETTE["edge"])
    for x in range(1, width - 1):
        px(buf, width, x, 1, PALETTE["highlight"])
        px(buf, width, x, height - 2, PALETTE["shade"])
    for y in range(1, height - 1):
        px(buf, width, 1, y, PALETTE["highlight"])
        px(buf, width, width - 2, y, PALETTE["shade"])
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, CORNER_CHAMFER, PALETTE["edge"])


def player_inventory(buf, width, height):
    """The player's three rows and hotbar, which every chest screen draws below the container."""
    main_y = height - PLAYER_MAIN_FROM_BOTTOM
    for row in range(3):
        for column in range(SLOT_COLUMNS):
            slot_recess(buf, width, SLOT_ORIGIN_X + column * ROW_PITCH,
                        main_y + row * ROW_PITCH)
    hotbar_y = height - PLAYER_HOTBAR_FROM_BOTTOM
    for column in range(SLOT_COLUMNS):
        slot_recess(buf, width, SLOT_ORIGIN_X + column * ROW_PITCH, hotbar_y)


def panel(rows):
    """One chest panel, 176 x (114 + 18*rows)."""
    height = HEIGHT_BASE + ROW_PITCH * rows
    buf = blank(WIDTH, height, PALETTE["ground"])
    frame(buf, WIDTH, height)

    # The title strip, and the one accent line in the whole panel under it.
    rect(buf, WIDTH, 3, 3, WIDTH - 4, TITLE_BAR_HEIGHT - 2, PALETTE["title_bar"])
    rect(buf, WIDTH, 3, TITLE_BAR_HEIGHT - 1, WIDTH - 4, TITLE_BAR_HEIGHT - 1,
         PALETTE["accent"])

    for row in range(rows):
        for column in range(SLOT_COLUMNS):
            slot_recess(buf, WIDTH,
                        SLOT_ORIGIN_X + column * ROW_PITCH,
                        SLOT_ORIGIN_Y + row * ROW_PITCH)
    player_inventory(buf, WIDTH, height)
    return WIDTH, height, bytes(buf)


# --- The travel panel ---------------------------------------------------------------

def composite_mask(buf, width, x0, y0, canvas, colour, alpha):
    """Lays a Canvas's anti-aliased coverage over the buffer in one colour at one alpha."""
    coverage = canvas.to_rgba((0, 0, 0))
    for y in range(canvas.h):
        for x in range(canvas.w):
            a = coverage[(y * canvas.w + x) * 4 + 3]
            if a:
                blend(buf, width, x0 + x, y0 + y, (colour[0], colour[1], colour[2], a * alpha // 255))


def pictogram(name):
    """The world's symbol, drawn into a Canvas the size of a tile - large, centred, simple."""
    c = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    cx, cy = TILE_WIDTH / 2, TILE_HEIGHT / 2
    if name == "nordtal":
        # Two peaks behind a small house: the build world, where things stand.
        c.fill_polygon([(cx - 26, cy + 14), (cx - 12, cy - 12), (cx + 2, cy + 14)])
        c.fill_polygon([(cx - 6, cy + 14), (cx + 10, cy - 18), (cx + 26, cy + 14)])
        c.fill_rect(cx - 20, cy + 4, cx - 6, cy + 16)          # house body
        c.fill_polygon([(cx - 22, cy + 5), (cx - 13, cy - 3), (cx - 4, cy + 5)])  # roof
    elif name == "farm":
        # An ear of wheat: a stem, and grains alternating up either side of it.
        c.stroke_line(cx, cy + 20, cx, cy - 16, 2.4)
        for i in range(5):
            y = cy + 10 - i * 6
            c.fill_circle(cx - 5, y - 1, 3.4)
            c.fill_circle(cx + 5, y - 4, 3.4)
        c.fill_circle(cx, cy - 18, 3.6)
    elif name == "nether":
        # The same flame the HUD icon draws, scaled up.
        c.fill_polygon([
            (cx, cy - 20), (cx + 7, cy - 6), (cx + 16, cy), (cx + 12, cy + 12), (cx + 4, cy + 20),
            (cx - 4, cy + 20), (cx - 12, cy + 12), (cx - 16, cy), (cx - 7, cy - 6),
        ])
        hole = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
        hole.fill_polygon([(cx, cy), (cx + 6, cy + 10), (cx, cy + 18), (cx - 6, cy + 10)])
        for i in range(len(c.mask)):
            if hole.mask[i]:
                c.mask[i] = 0
    elif name == "end":
        # An eye: a lens with a vertical pupil, the ender eye every player knows.
        c.fill_polygon([(cx - 24, cy), (cx - 10, cy - 13), (cx + 10, cy - 13), (cx + 24, cy),
                        (cx + 10, cy + 13), (cx - 10, cy + 13)])
        iris = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
        iris.fill_circle(cx, cy, 8)
        pupil = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
        pupil.fill_polygon([(cx, cy - 6), (cx + 3, cy), (cx, cy + 6), (cx - 3, cy)])
        for i in range(len(c.mask)):
            if iris.mask[i] and not pupil.mask[i]:
                c.mask[i] = 0
    else:
        raise ValueError(name)
    return c


def tile(buf, width, x, y, colours, name):
    """One world card at (x, y): outline, fill, a top-left highlight, its pictogram, chamfered."""
    fill = colours["fill"] + (255,)
    dark = colours["dark"] + (255,)
    light = colours["light"] + (255,)
    x1, y1 = x + TILE_WIDTH - 1, y + TILE_HEIGHT - 1
    rect(buf, width, x, y, x1, y1, fill)
    outline(buf, width, x, y, x1, y1, dark)
    for i in range(1, TILE_WIDTH - 1):
        px(buf, width, x + i, y + 1, light)
    for i in range(1, TILE_HEIGHT - 1):
        px(buf, width, x + 1, y + i, light)
    composite_mask(buf, width, x, y, pictogram(name), colours["light"], PICTOGRAM_ALPHA)
    chamfer(buf, width, 0, x, y, x1, y1, TILE_CHAMFER, dark)
    # The chamfer punched transparent corners; behind a card that is the frame, not the world.
    for cx_, cy_, dx, dy in ((x, y, 1, 1), (x1, y, -1, 1), (x, y1, 1, -1), (x1, y1, -1, -1)):
        for row, count in enumerate(TILE_CHAMFER):
            for column in range(count):
                px(buf, width, cx_ + dx * column, cy_ + dy * row, PALETTE["ground"])


def travel_panel():
    """The balloon's panel: a 6-row window with no title strip and four world cards."""
    rows = 6
    height = HEIGHT_BASE + ROW_PITCH * rows
    buf = blank(WIDTH, height, PALETTE["ground"])
    frame(buf, WIDTH, height)
    player_inventory(buf, WIDTH, height)
    for name, (column, row) in (("nordtal", (0, 0)), ("farm", (1, 0)),
                                ("nether", (0, 1)), ("end", (1, 1))):
        tile(buf, WIDTH, TILE_X[column], TILE_Y[row], TILES[name], name)
    return WIDTH, height, bytes(buf)


def locked_overlay():
    """A tile-sized shade with a padlock: laid over a card whose milestone is not done."""
    buf = blank(TILE_WIDTH, TILE_HEIGHT, LOCKED_SHADE)
    cx, cy = TILE_WIDTH / 2, TILE_HEIGHT / 2
    c = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    c.fill_rect(cx - 7, cy - 1, cx + 7, cy + 10)                # body
    shackle = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    shackle.fill_circle(cx, cy - 4, 6)
    inner = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    inner.fill_circle(cx, cy - 4, 3.6)
    inner.fill_rect(cx - 8, cy - 4, cx + 8, cy + 12)            # only the arc above the body shows
    for i in range(len(c.mask)):
        if shackle.mask[i] and not inner.mask[i]:
            c.mask[i] = 1
    keyhole = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    keyhole.fill_circle(cx, cy + 3, 1.6)
    keyhole.fill_rect(cx - 0.8, cy + 3, cx + 0.8, cy + 7)
    for i in range(len(c.mask)):
        if keyhole.mask[i]:
            c.mask[i] = 0
    composite_mask(buf, TILE_WIDTH, 0, 0, c, LOCK_COLOUR, 255)
    # Same corner cut as the card beneath, so the shade does not square off a rounded tile.
    chamfer(buf, TILE_WIDTH, TILE_HEIGHT, 0, 0, TILE_WIDTH - 1, TILE_HEIGHT - 1, TILE_CHAMFER,
            (0, 0, 0, 0))
    return TILE_WIDTH, TILE_HEIGHT, bytes(buf)


def here_overlay():
    """A 2 px frame the size of a tile, transparent inside: 'you are standing here'."""
    buf = blank(TILE_WIDTH, TILE_HEIGHT, (0, 0, 0, 0))
    outline(buf, TILE_WIDTH, 0, 0, TILE_WIDTH - 1, TILE_HEIGHT - 1, HERE_FRAME)
    outline(buf, TILE_WIDTH, 1, 1, TILE_WIDTH - 2, TILE_HEIGHT - 2, HERE_FRAME)
    chamfer(buf, TILE_WIDTH, TILE_HEIGHT, 0, 0, TILE_WIDTH - 1, TILE_HEIGHT - 1, TILE_CHAMFER,
            (0, 0, 0, 0))
    # The chamfer's own diagonal comes back as frame, one pixel in.
    for cx_, cy_, dx, dy in ((0, 0, 1, 1), (TILE_WIDTH - 1, 0, -1, 1),
                             (0, TILE_HEIGHT - 1, 1, -1), (TILE_WIDTH - 1, TILE_HEIGHT - 1, -1, -1)):
        for row, count in enumerate(TILE_CHAMFER):
            px(buf, TILE_WIDTH, cx_ + dx * count, cy_ + dy * row, HERE_FRAME)
    return TILE_WIDTH, TILE_HEIGHT, bytes(buf)


def assert_advance(path, expected_width):
    """A glyph advances by its rightmost drawn column + 2; the Java side assumes width + 1.

    The rightmost column must therefore carry alpha somewhere, or Minecraft trims the glyph
    and every offset computed from "width + 1" is wrong. An opaque panel always does; the
    check is here so a future transparent design fails loudly instead.
    """
    width, height, rgba = read_png(path)
    rightmost = rightmost_drawn_column(rgba, width, height)
    assert rightmost == expected_width - 1, \
        f"{path}: rightmost drawn column is {rightmost}, so the advance is {rightmost + 2}" \
        f" and not {expected_width + 1}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=DEFAULT_OUT)
    arguments = parser.parse_args()

    for rows in range(1, 7):
        width, height, data = panel(rows)
        path = os.path.join(arguments.out, f"panel_{rows}.png")
        write_png(path, width, height, data, REPO_ROOT)
        assert_advance(path, WIDTH)

    for name, (width, height, data) in (("travel", travel_panel()),
                                        ("travel_locked", locked_overlay()),
                                        ("travel_here", here_overlay())):
        path = os.path.join(arguments.out, f"{name}.png")
        write_png(path, width, height, data, REPO_ROOT)
        assert_advance(path, width)


if __name__ == "__main__":
    main()
