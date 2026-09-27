#!/usr/bin/env python3
"""Draws the `nordtal:gui` menu surfaces: the chest panels, the travel panel and its overlays.

A panel is a glyph with a large ascent in a chest's title, so it rises behind the slots. A
chest window is 114 + 18 * rows high, so each row count needs its own panel. An overlay is a
per-player state of one tile, such as locked, drawn as its own tile-sized glyph.

Standard library only; the PNG codec is pngio.py.

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

# Vanilla's measurements, read off the pixels of gui/container/generic_54.png. Re-measure them
# at every Minecraft version bump.

WIDTH = 176                  # the drawn width of every chest window
HEIGHT_BASE = 114            # imageHeight = HEIGHT_BASE + 18 * rows
ROW_PITCH = 18               # one slot row, and one slot's outer size

# The slot cell's top-left shadow pixel, one up and left of the item area at (8, 18).
SLOT_ORIGIN_X = 7
SLOT_ORIGIN_Y = 17
SLOT_COLUMNS = 9

TITLE_BAR_HEIGHT = 17        # the strip above the first slot cell, where the title sits

# The player's inventory, from the window's bottom edge: one pixel above the texture's rows,
# because ChestScreen#renderBg draws that part one pixel higher.
PLAYER_MAIN_FROM_BOTTOM = 84
PLAYER_HOTBAR_FROM_BOTTOM = 26

# Vanilla's corner: transparent pixels on each of the first three rows, from the corner in.
CORNER_CHAMFER = (3, 2, 1)

# The travel panel: three tiles of 3 rows by 4 columns, inset from their slots, bottom right
# empty. smp's BalloonMenu carries the same slot map, and MenuTitleTest checks it against these.
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

PALETTE = {
    "edge":       (28, 26, 30, 255),      # the outermost line, near-black like vanilla's
    "frame":      (198, 198, 198, 255),   # vanilla's own body grey, so ours and theirs are one
    "highlight":  (242, 242, 242, 255),   # 1 px light line inside the edge, top and left
    "shade":      (128, 128, 132, 255),   # 1 px dark line inside the edge, bottom and right
    "ground":     (198, 198, 198, 255),   # the panel's own field
    "hairline":   (150, 150, 156, 255),   # one line under the title, the pill's own edge colour
    "slot":       (58, 58, 64, 255),      # a slot recess, dark
    "slot_edge":  (150, 150, 156, 255),   # its lit bottom-right
}

# One card per world: fill, outline and top-left highlight. The pictogram is the highlight at
# partial alpha, so it reads as embossed.
TILES = {
    "nordtal": {"fill": (82, 168, 84), "dark": (44, 108, 48), "light": (140, 210, 136)},
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
    """Fills a rectangle, inclusive of both corners."""
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

    `steps` is how many pixels go transparent on each successive row from the corner.
    """
    corners = ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1))
    for cx, cy, dx, dy in corners:
        for row, count in enumerate(steps):
            for column in range(count):
                px(buf, width, cx + dx * column, cy + dy * row, (0, 0, 0, 0))
        # The diagonal just inside the cut, one pixel per row, is the edge.
        for row, count in enumerate(steps):
            px(buf, width, cx + dx * count, cy + dy * row, edge)


def slot_recess(buf, width, x, y):
    """Draws one 18 x 18 slot cell at its shadow corner, lit at x + 17 and y + 17 as vanilla's."""
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


def panel(rows, recessed=True):
    """Draws one chest panel, 176 x (114 + 18 * rows).

    `recessed=False` leaves out the container's slot recesses, for list menus that draw a pill
    across each row; the player's rows keep theirs.
    """
    height = HEIGHT_BASE + ROW_PITCH * rows
    buf = blank(WIDTH, height, PALETTE["ground"])
    frame(buf, WIDTH, height)

    # The hairline spans the container's slot area.
    rect(buf, WIDTH, SLOT_ORIGIN_X, TITLE_BAR_HEIGHT - 1,
         WIDTH - SLOT_ORIGIN_X - 1, TITLE_BAR_HEIGHT - 1, PALETTE["hairline"])

    if recessed:
        for row in range(rows):
            for column in range(SLOT_COLUMNS):
                slot_recess(buf, WIDTH,
                            SLOT_ORIGIN_X + column * ROW_PITCH,
                            SLOT_ORIGIN_Y + row * ROW_PITCH)
    player_inventory(buf, WIDTH, height)
    return WIDTH, height, bytes(buf)


def composite_mask(buf, width, x0, y0, canvas, colour, alpha):
    """Lays a Canvas's anti-aliased coverage over the buffer in one colour at one alpha."""
    coverage = canvas.to_rgba((0, 0, 0))
    for y in range(canvas.h):
        for x in range(canvas.w):
            a = coverage[(y * canvas.w + x) * 4 + 3]
            if a:
                blend(buf, width, x0 + x, y0 + y, (colour[0], colour[1], colour[2], a * alpha // 255))


def pictogram(name):
    """Draws the world's symbol into a tile-sized Canvas."""
    c = Canvas(TILE_WIDTH, TILE_HEIGHT, ss=6)
    cx, cy = TILE_WIDTH / 2, TILE_HEIGHT / 2
    if name == "nordtal":
        # Two peaks behind a small house.
        c.fill_polygon([(cx - 26, cy + 14), (cx - 12, cy - 12), (cx + 2, cy + 14)])
        c.fill_polygon([(cx - 6, cy + 14), (cx + 10, cy - 18), (cx + 26, cy + 14)])
        c.fill_rect(cx - 20, cy + 4, cx - 6, cy + 16)          # house body
        c.fill_polygon([(cx - 22, cy + 5), (cx - 13, cy - 3), (cx - 4, cy + 5)])  # roof
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
        # An ender eye.
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
    """Draws one chamfered world card at (x, y) with its pictogram."""
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
    """Draws the balloon's 6-row panel with three world cards; it has no title, so no hairline."""
    rows = 6
    height = HEIGHT_BASE + ROW_PITCH * rows
    buf = blank(WIDTH, height, PALETTE["ground"])
    frame(buf, WIDTH, height)
    player_inventory(buf, WIDTH, height)
    for name, (column, row) in (("nordtal", (0, 0)), ("nether", (1, 0)),
                                ("end", (0, 1))):
        tile(buf, WIDTH, TILE_X[column], TILE_Y[row], TILES[name], name)
    return WIDTH, height, bytes(buf)


def locked_overlay():
    """Draws a tile-sized shade with a padlock, laid over a card whose milestone is not done."""
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
    # The card's corner cut, so the shade matches its shape.
    chamfer(buf, TILE_WIDTH, TILE_HEIGHT, 0, 0, TILE_WIDTH - 1, TILE_HEIGHT - 1, TILE_CHAMFER,
            (0, 0, 0, 0))
    return TILE_WIDTH, TILE_HEIGHT, bytes(buf)


def here_overlay():
    """Draws a tile-sized 2 px frame, transparent inside, marking the player's world."""
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


# The objective card: four slot columns by two slot rows, inset 2. It spans two rows, so it
# is a gui glyph; what sits on it is row glyphs, and the bar between the bands has its own ascent.
CARD_WIDTH = TILE_COLUMNS * ROW_PITCH - 2 * TILE_INSET      # 68
CARD_HEIGHT = 2 * ROW_PITCH - 2 * TILE_INSET                # 32
CARD_BAR_X = 3
CARD_BAR_Y = 14
CARD_BAR_WIDTH = CARD_WIDTH - 2 * CARD_BAR_X                # 62
CARD_BAR_HEIGHT = 5

# The fill sits one pixel inside the track on every side.
CARD_FILL_WIDTH = CARD_BAR_WIDTH - 2                        # 60
CARD_FILL_HEIGHT = CARD_BAR_HEIGHT - 2                      # 3

# Powers of two, so any fill up to 60 is a few glyphs.
CARD_FILL_STEPS = (1, 2, 4, 8, 16, 32)

CARD_FILL = (214, 214, 218, 255)
CARD_LINE = (150, 150, 156, 255)
CARD_LIGHT = (232, 232, 236, 255)
CARD_TRACK = (46, 46, 52, 255)
CARD_BAR_COLOUR = (82, 168, 84, 255)      # the same green the Nordtal card is
CARD_DONE_VEIL = (82, 168, 84, 71)        # 0.28 alpha


def objective_card():
    """One objective's plate: a pill the size of two slot rows with a bar track sunk into it."""
    buf = blank(CARD_WIDTH, CARD_HEIGHT, CARD_FILL)
    x1, y1 = CARD_WIDTH - 1, CARD_HEIGHT - 1
    outline(buf, CARD_WIDTH, 0, 0, x1, y1, CARD_LINE)
    rect(buf, CARD_WIDTH, 1, 1, x1 - 1, 1, CARD_LIGHT)
    rect(buf, CARD_WIDTH, CARD_BAR_X, CARD_BAR_Y,
         CARD_BAR_X + CARD_BAR_WIDTH - 1, CARD_BAR_Y + CARD_BAR_HEIGHT - 1, CARD_TRACK)
    chamfer(buf, CARD_WIDTH, CARD_HEIGHT, 0, 0, x1, y1, TILE_CHAMFER, CARD_LINE)
    return CARD_WIDTH, CARD_HEIGHT, bytes(buf)


def objective_card_done():
    """Draws the green wash over a finished card, bar included."""
    buf = blank(CARD_WIDTH, CARD_HEIGHT, CARD_DONE_VEIL)
    chamfer(buf, CARD_WIDTH, CARD_HEIGHT, 0, 0, CARD_WIDTH - 1, CARD_HEIGHT - 1, TILE_CHAMFER,
            (0, 0, 0, 0))
    return CARD_WIDTH, CARD_HEIGHT, bytes(buf)


def bar_fill(width):
    """Draws one power-of-two slice of a progress bar's fill."""
    return width, CARD_FILL_HEIGHT, bytes(blank(width, CARD_FILL_HEIGHT, CARD_BAR_COLOUR))


# The hand-in tray is one surface, unlike the grave's cells: you throw into it, not take out.
TRAY_INNER_DARK = (35, 35, 40, 255)


def sunken(width, height):
    """Draws a recessed surface with a slot cell's shading and transparent corners."""
    buf = blank(width, height, PALETTE["slot"])
    rect(buf, width, 0, 0, width - 1, 0, TRAY_INNER_DARK)
    rect(buf, width, 0, 0, 0, height - 1, TRAY_INNER_DARK)
    rect(buf, width, width - 1, 0, width - 1, height - 1, PALETTE["slot_edge"])
    rect(buf, width, 0, height - 1, width - 1, height - 1, PALETTE["slot_edge"])
    chamfer(buf, width, height, 0, 0, width - 1, height - 1, TILE_CHAMFER, PALETTE["slot_edge"])
    return width, height, bytes(buf)


# Three chest rows of slot area, not inset.
TRAY_WIDTH = SLOT_COLUMNS * ROW_PITCH                     # 162
TRAY_ROWS = 3
TRAY_HEIGHT = TRAY_ROWS * ROW_PITCH                       # 54


# The grave slab, one glyph per row count. Forty-one stacks at most is five rows.
GRAVE_STONE = (142, 140, 146, 255)
GRAVE_RECESS = (44, 42, 48, 255)          # darker than a panel's own slot: this is a grave
GRAVE_MAX_ROWS = 5


def grave_slab(rows):
    """Draws `rows` slot rows of dark recesses on stone."""
    width, height = SLOT_COLUMNS * ROW_PITCH, rows * ROW_PITCH
    buf = blank(width, height, GRAVE_STONE)
    for row in range(rows):
        for column in range(SLOT_COLUMNS):
            x, y = column * ROW_PITCH, row * ROW_PITCH
            rect(buf, width, x, y, x + 17, y + 17, GRAVE_RECESS)
            for i in range(ROW_PITCH):
                px(buf, width, x + 17, y + i, PALETTE["slot_edge"])
                px(buf, width, x + i, y + 17, PALETTE["slot_edge"])
    return width, height, bytes(buf)


# The wheel: twelve prize cells around a hub, left of centre so the right columns carry the
# controls. The winning cell wears travel_here's white frame.
WHEEL_ROWS = 5
WHEEL_CENTRE_COLUMN = 2
WHEEL_CENTRE_ROW = 2
WHEEL_OUTER_RADIUS = 46
WHEEL_INNER_RADIUS = 26
WHEEL_HUB_RADIUS = 18

# Clockwise, in WheelStrip's order; the winner stops on cell 0.
WHEEL_CELLS = ((2, 0), (3, 0), (4, 1), (4, 2), (4, 3),
               (3, 4), (2, 4), (1, 4), (0, 3), (0, 2), (0, 1), (1, 0))

WHEEL_BAND = (44, 44, 50, 255)
WHEEL_BAND_EDGE = (35, 35, 40, 255)
WHEEL_HUB = (178, 178, 182, 255)
WHEEL_HUB_FACE = (214, 214, 218, 255)
WHEEL_CELL = (58, 58, 64, 255)
WHEEL_CELL_WINNER = (72, 72, 79, 255)


def ring(buf, width, cx, cy, outer, inner, colour):
    """Fills an annulus by distance."""
    for y in range(cy - outer, cy + outer + 1):
        for x in range(cx - outer, cx + outer + 1):
            distance = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if inner <= distance <= outer:
                px(buf, width, x, y, colour)


def wheel_ring():
    """Draws the wheel's five-row panel."""
    height = HEIGHT_BASE + ROW_PITCH * WHEEL_ROWS
    buf = blank(WIDTH, height, PALETTE["ground"])
    frame(buf, WIDTH, height)
    rect(buf, WIDTH, SLOT_ORIGIN_X, TITLE_BAR_HEIGHT - 1,
         WIDTH - SLOT_ORIGIN_X - 1, TITLE_BAR_HEIGHT - 1, PALETTE["hairline"])
    player_inventory(buf, WIDTH, height)

    cx = SLOT_ORIGIN_X + ROW_PITCH * WHEEL_CENTRE_COLUMN + ROW_PITCH // 2
    cy = SLOT_ORIGIN_Y + ROW_PITCH * WHEEL_CENTRE_ROW + ROW_PITCH // 2

    ring(buf, WIDTH, cx, cy, WHEEL_OUTER_RADIUS, WHEEL_INNER_RADIUS, WHEEL_BAND)
    ring(buf, WIDTH, cx, cy, WHEEL_OUTER_RADIUS, WHEEL_OUTER_RADIUS - 1, WHEEL_BAND_EDGE)
    ring(buf, WIDTH, cx, cy, WHEEL_INNER_RADIUS + 1, WHEEL_INNER_RADIUS, PALETTE["slot_edge"])
    ring(buf, WIDTH, cx, cy, WHEEL_HUB_RADIUS, 0, WHEEL_HUB)
    ring(buf, WIDTH, cx, cy, WHEEL_HUB_RADIUS - 1, 0, WHEEL_HUB_FACE)

    for index, (column, row) in enumerate(WHEEL_CELLS):
        x = SLOT_ORIGIN_X + ROW_PITCH * column
        y = SLOT_ORIGIN_Y + ROW_PITCH * row
        # Only the item area, so the ring stays round between the cells.
        rect(buf, WIDTH, x + 1, y + 1, x + 16, y + 16,
             WHEEL_CELL_WINNER if index == 0 else WHEEL_CELL)


    winner_x = SLOT_ORIGIN_X + ROW_PITCH * WHEEL_CELLS[0][0]
    winner_y = SLOT_ORIGIN_Y + ROW_PITCH * WHEEL_CELLS[0][1]
    outline(buf, WIDTH, winner_x, winner_y, winner_x + 17, winner_y + 17, HERE_FRAME)
    outline(buf, WIDTH, winner_x + 1, winner_y + 1, winner_x + 16, winner_y + 16, HERE_FRAME)
    return WIDTH, height, bytes(buf)


def assert_advance(path, expected_width):
    """Fails unless the rightmost column is drawn; the Java side assumes an advance of width + 1."""
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
        for suffix, recessed in (("", True), ("_plain", False)):
            width, height, data = panel(rows, recessed)
            path = os.path.join(arguments.out, f"panel_{rows}{suffix}.png")
            write_png(path, width, height, data, REPO_ROOT)
            assert_advance(path, WIDTH)

    surfaces = [("travel", travel_panel()),
                ("travel_locked", locked_overlay()),
                ("travel_here", here_overlay()),
                ("objective_card", objective_card()),
                ("objective_card_done", objective_card_done()),
                ("handin_tray", sunken(TRAY_WIDTH, TRAY_HEIGHT))]
    surfaces += [(f"grave_slab_{rows}", grave_slab(rows))
                 for rows in range(1, GRAVE_MAX_ROWS + 1)]
    surfaces.append(("wheel_ring", wheel_ring()))
    surfaces += [(f"bar_fill_{step}", bar_fill(step)) for step in CARD_FILL_STEPS]

    for name, (width, height, data) in surfaces:
        path = os.path.join(arguments.out, f"{name}.png")
        write_png(path, width, height, data, REPO_ROOT)
        assert_advance(path, width)


if __name__ == "__main__":
    main()
