#!/usr/bin/env python3
"""Draws the `nordtal:gui` menu panels - one bitmap glyph per chest size.

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

WHY THE ART IS PROGRAMMATIC. Same reason as generate_dummy_textures.py: this is a
placeholder that is anchored to the *measurements* rather than to an image, so a
hand-drawn panel of the same dimensions drops in without a line of Java changing. The
palette below is the whole design surface - a real art pass is an edit to PALETTE and a
redraw, not a rewrite.

Pure standard library. The PNG writer is imported from generate_dummy_textures.py so
there is one encoder in this directory rather than two.

Usage:
    python3 resource-pack/tools/generate_gui_panels.py [--out DIR]
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_dummy_textures import write_png  # noqa: E402  (path set above)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_OUT = os.path.join(REPO_ROOT, "resource-pack", "src", "assets",
                           "nordtal", "textures", "ui", "gui")

# --- The measurements. Every one of these is vanilla and none of them is ours. ------
#
# Measured against the extracted 26.2 assets on 2026-09-04, not taken from a tutorial.
# RE-MEASURE AT EVERY VERSION BUMP: 1.21.9 moved the villager trading result slot by one
# pixel, so this is not a theoretical risk.

WIDTH = 176                  # the drawn width of every chest window
HEIGHT_BASE = 114            # imageHeight = HEIGHT_BASE + 18 * rows
ROW_PITCH = 18               # one slot row, and one slot's outer size

SLOT_ORIGIN_X = 8            # the container grid's top-left, relative to the window
SLOT_ORIGIN_Y = 18
SLOT_COLUMNS = 9

TITLE_BAR_HEIGHT = 18        # the strip above the first slot row, where the title sits

# The player's own inventory, which every chest screen also draws:
#   main grid  y = imageHeight - 82, three rows
#   hotbar     y = imageHeight - 24, one row
PLAYER_MAIN_FROM_BOTTOM = 82
PLAYER_HOTBAR_FROM_BOTTOM = 24

# --- The palette. This is the design surface; everything else is arithmetic. --------
#
# A cool neutral rather than a pure grey - chosen, not inherited. It reads as slate
# against Minecraft's own warm inventory textures, and the accent is the only saturated
# colour in the set so there is exactly one place the eye is sent.

PALETTE = {
    "edge":       (24, 27, 34, 255),      # the outermost 1 px, darkest
    "frame":      (49, 54, 66, 255),      # the frame body
    "highlight":  (78, 86, 104, 255),     # a 1 px light line inside the frame
    "ground":     (38, 42, 52, 255),      # the panel's own field
    "title_bar":  (30, 33, 41, 255),      # the strip the title text sits on
    "accent":     (176, 138, 74, 255),    # one line under the title bar, and nothing else
    "slot":       (26, 29, 36, 255),      # a slot recess
    "slot_edge":  (62, 69, 84, 255),      # its lit bottom-right
}


def blank(width, height, colour):
    return bytearray(bytes(colour) * width * height)


def px(buf, width, x, y, colour):
    i = (y * width + x) * 4
    buf[i:i + 4] = bytes(colour)


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


def slot_recess(buf, width, x, y):
    """One 18x18 slot, drawn as a recess: dark field, lit bottom and right."""
    rect(buf, width, x, y, x + 17, y + 17, PALETTE["slot"])
    for i in range(18):
        px(buf, width, x + 17, y + i, PALETTE["slot_edge"])
        px(buf, width, x + i, y + 17, PALETTE["slot_edge"])


def panel(rows):
    """One chest panel, 176 x (114 + 18*rows)."""
    height = HEIGHT_BASE + ROW_PITCH * rows
    buf = blank(WIDTH, height, PALETTE["ground"])

    # Frame: a dark outermost pixel, a body, and a light line inside it. Three lines
    # rather than one because a single-pixel border reads as a bug at GUI scale 1.
    outline(buf, WIDTH, 0, 0, WIDTH - 1, height - 1, PALETTE["edge"])
    outline(buf, WIDTH, 1, 1, WIDTH - 2, height - 2, PALETTE["frame"])
    outline(buf, WIDTH, 2, 2, WIDTH - 3, height - 3, PALETTE["highlight"])

    # The title strip, and the one accent line in the whole panel under it.
    rect(buf, WIDTH, 3, 3, WIDTH - 4, TITLE_BAR_HEIGHT - 2, PALETTE["title_bar"])
    rect(buf, WIDTH, 3, TITLE_BAR_HEIGHT - 1, WIDTH - 4, TITLE_BAR_HEIGHT - 1,
         PALETTE["accent"])

    # The container's own slots...
    for row in range(rows):
        for column in range(SLOT_COLUMNS):
            slot_recess(buf, WIDTH,
                        SLOT_ORIGIN_X + column * ROW_PITCH,
                        SLOT_ORIGIN_Y + row * ROW_PITCH)

    # ...and the player's, which every chest screen draws below them.
    main_y = height - PLAYER_MAIN_FROM_BOTTOM
    for row in range(3):
        for column in range(SLOT_COLUMNS):
            slot_recess(buf, WIDTH, SLOT_ORIGIN_X + column * ROW_PITCH,
                        main_y + row * ROW_PITCH)
    hotbar_y = height - PLAYER_HOTBAR_FROM_BOTTOM
    for column in range(SLOT_COLUMNS):
        slot_recess(buf, WIDTH, SLOT_ORIGIN_X + column * ROW_PITCH, hotbar_y)

    return WIDTH, height, bytes(buf)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=DEFAULT_OUT)
    arguments = parser.parse_args()

    for rows in range(1, 7):
        width, height, data = panel(rows)
        # The rightmost column must carry alpha, or Minecraft trims the glyph and every
        # offset computed from "trimmed width + 1" is wrong. An opaque panel always does;
        # the assertion is here so a future transparent design fails loudly instead.
        assert any(data[(y * width + width - 1) * 4 + 3] for y in range(height)), \
            "the panel's rightmost column is fully transparent, so its advance will not be 177"
        write_png(os.path.join(arguments.out, f"panel_{rows}.png"), width, height, data)


if __name__ == "__main__":
    main()
