#!/usr/bin/env python3
"""Draws the `nordtal:bossbar` HUD surfaces: the pill background and the status icons.

A pill is a translucent body with a lighter rim, two caps and power-of-two segments. Each
segment is as wide as its name and the client adds one pixel per glyph, so `BossBarWidth`
steps back after each.

The icons are 10 x 10 with a dark outline and one leading colour each. They contain no white:
the component is drawn in white, so the art's own colours reach the screen.

Usage:
    python3 resource-pack/tools/generate_hud.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import write_png  # noqa: E402  (path set above)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO_ROOT, "resource-pack", "src", "assets", "nordtal", "textures", "ui", "bossbar")

# The pill.

BAR_HEIGHT = 14                       # bossbar.json: height 14, ascent 6
CAP_WIDTH = 4                         # each rounded end; start.png and end.png
SEGMENT_WIDTHS = [1, 2, 4, 8, 16, 32, 64, 128]

RIM = (112, 118, 132, 235)            # the one-pixel edge
FILL = (18, 20, 26, 196)              # ~77 % dark: the world shows through, the text does not

# The left cap, row by row: R rim, F fill, . transparent. The right cap is its mirror.
CAP = [
    "..RR",
    ".RFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    "RFFF",
    ".RFF",
    "..RR",
]


def paint(rows, colours):
    """Turns a list of equal-length strings into RGBA bytes through a colour map."""
    height = len(rows)
    width = len(rows[0])
    out = bytearray(width * height * 4)
    for y, row in enumerate(rows):
        assert len(row) == width, f"row {y} is {len(row)} wide, not {width}"
        for x, key in enumerate(row):
            colour = colours.get(key, (0, 0, 0, 0))
            i = (y * width + x) * 4
            out[i:i + 4] = bytes(colour)
    return width, height, bytes(out)


def pill():
    cap_colours = {"R": RIM, "F": FILL}
    width, height, data = paint(CAP, cap_colours)
    write_png(os.path.join(OUT, "bg", "start.png"), width, height, data, REPO_ROOT)
    width, height, data = paint([row[::-1] for row in CAP], cap_colours)
    write_png(os.path.join(OUT, "bg", "end.png"), width, height, data, REPO_ROOT)

    for segment in SEGMENT_WIDTHS:
        rows = ["R" * segment] + ["F" * segment] * (BAR_HEIGHT - 2) + ["R" * segment]
        width, height, data = paint(rows, cap_colours)
        write_png(os.path.join(OUT, "bg", f"{segment}.png"), width, height, data, REPO_ROOT)


# The icons.

K = (32, 30, 36, 255)                 # the outline every icon shares

ICONS = {
    # Nordtal: a mountain with a snow cap.
    "dim_overworld": ({"G": (86, 171, 74, 255), "g": (54, 122, 48, 255), "w": (240, 244, 248, 255)}, [
        "..........",
        "....kk....",
        "...kwwk...",
        "..kwwwwk..",
        "..kGwwGk..",
        ".kGGGGGGk.",
        ".kGGgGGGk.",
        "kGGgGGGgGk",
        "kkkkkkkkkk",
        "..........",
    ]),
    # Farm world: an ear of wheat.
    "dim_farmworld": ({"Y": (236, 186, 64, 255), "y": (176, 126, 38, 255)}, [
        "....k.....",
        "...kYk....",
        "..kYkYk...",
        "..kYYYk...",
        ".kYkYkYk..",
        ".kYYYYYk..",
        "..kkYkk...",
        "...kyk....",
        "...kyk....",
        "....k.....",
    ]),
    # Nether: a flame with a lighter core.
    "dim_nether": ({"R": (214, 66, 52, 255), "O": (250, 172, 64, 255)}, [
        "....k.....",
        "...kRk....",
        "...kRRk...",
        "..kRRRk...",
        "..kROORk..",
        ".kRROORRk.",
        ".kROOOORk.",
        ".kRROORRk.",
        "..kRRRRk..",
        "...kkkk...",
    ]),
    # End: an ender eye.
    "dim_end": ({"P": (138, 88, 200, 255), "g": (150, 226, 150, 255), "d": (28, 20, 40, 255)}, [
        "..........",
        "...kkkk...",
        ".kkPPPPkk.",
        "kPPPggPPPk",
        "kPPgddgPPk",
        "kPPgddgPPk",
        "kPPPggPPPk",
        ".kkPPPPkk.",
        "...kkkk...",
        "..........",
    ]),
    # Compass: a red needle pointing up-right.
    "compass": ({"W": (232, 234, 238, 255), "G": (150, 154, 164, 255), "R": (222, 60, 56, 255)}, [
        "...kkkk...",
        ".kkWWWWkk.",
        ".kWWWWRWk.",
        "kWWWWRRWWk",
        "kWWWkRWWWk",
        "kWWWGkWWWk",
        "kWWGGWWWWk",
        ".kWGWWWWk.",
        ".kkWWWWkk.",
        "...kkkk...",
    ]),
    # Hunger games, players: a heart.
    "status_alive": ({"R": (226, 58, 70, 255), "p": (250, 150, 160, 255)}, [
        "..........",
        ".kkk..kkk.",
        "kRpRkkRRRk",
        "kpRRRRRRRk",
        "kRRRRRRRRk",
        ".kRRRRRRk.",
        "..kRRRRk..",
        "...kRRk...",
        "....kk....",
        "..........",
    ]),
    # Hunger games, deaths: a skull.
    "status_deaths": ({"W": (236, 236, 240, 255)}, [
        "..kkkkkk..",
        ".kWWWWWWk.",
        "kWWWWWWWWk",
        "kWkkWWkkWk",
        "kWkkWWkkWk",
        "kWWWWWWWWk",
        ".kWWkkWWk.",
        ".kkWWWWkk.",
        "..kWkkWk..",
        "..kkkkkk..",
    ]),
    # Hunger games, loot: a chest with a gold latch.
    "status_loot": ({"B": (156, 104, 52, 255), "b": (104, 66, 30, 255), "Y": (240, 196, 72, 255)}, [
        "..........",
        ".kkkkkkkk.",
        "kBBBBBBBBk",
        "kBBBBBBBBk",
        "kkkkYYkkkk",
        "kbbbkYkbbk",
        "kbbbbYbbbk",
        "kbbbbbbbbk",
        ".kkkkkkkk.",
        "..........",
    ]),
    # Hunger games, border: a dashed square in the border's own cyan.
    "status_border": ({"C": (92, 202, 232, 255)}, [
        "kCkCkCkCkC",
        "C........C",
        "k........k",
        "C........C",
        "k........k",
        "C........C",
        "k........k",
        "C........C",
        "k........k",
        "CkCkCkCkCk",
    ]),
}


def icons():
    for name, (colours, rows) in ICONS.items():
        palette = dict(colours)
        palette["k"] = K
        width, height, data = paint(rows, palette)
        assert (width, height) == (10, 10), name
        write_png(os.path.join(OUT, "icons", f"{name}.png"), width, height, data, REPO_ROOT)


def main():
    pill()
    icons()


if __name__ == "__main__":
    main()
