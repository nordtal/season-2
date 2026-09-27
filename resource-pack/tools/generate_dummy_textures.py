#!/usr/bin/env python3
"""Draws every undrawn glyph as a placeholder PNG at its final size, plus the balloon model and lobby maps.

Re-run it whenever a glyph metric in the allocation table changes. Standard library only.

Usage:
    python3 resource-pack/tools/generate_dummy_textures.py
"""

import math
import os
import struct
import sys
import zlib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RP = os.path.join(REPO_ROOT, "resource-pack", "src", "assets")
HG_RES = os.path.join(REPO_ROOT, "hunger-games", "src", "main", "resources")

BLACK = (0, 0, 0)

# Not the menus' colours: a board sits on a dark translucent ground, where a light frame glares.
BOARD_LINE = (78, 86, 104)      # PALETTE["highlight"]
BOARD_ACCENT = (176, 138, 74)   # PALETTE["accent"]

# White, because the client multiplies a glyph by its text colour and the bundle picks that colour.
SYSTEM_WHITE = (255, 255, 255)


# The PNG writer lives in pngio.py; the name stays importable here.

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import write_png as _write_png  # noqa: E402


def write_png(path, width, height, rgba_bytes):
    _write_png(path, width, height, rgba_bytes, REPO_ROOT)


class Canvas:
    """Draws shapes into a supersampled mask and downsamples it into per-pixel alpha."""

    def __init__(self, w, h, ss=8):
        self.w, self.h, self.ss = w, h, ss
        self.W, self.H = w * ss, h * ss
        self.mask = bytearray(self.W * self.H)

    def _set(self, x, y):
        if 0 <= x < self.W and 0 <= y < self.H:
            self.mask[y * self.W + x] = 1

    def fill_polygon(self, points):
        pts = [(px * self.ss, py * self.ss) for px, py in points]
        n = len(pts)
        ys = [p[1] for p in pts]
        y0 = max(0, int(math.floor(min(ys))))
        y1 = min(self.H - 1, int(math.ceil(max(ys))))
        for y in range(y0, y1 + 1):
            yc = y + 0.5
            xs = []
            for i in range(n):
                x1_, y1_ = pts[i]
                x2_, y2_ = pts[(i + 1) % n]
                if (y1_ <= yc < y2_) or (y2_ <= yc < y1_):
                    t = (yc - y1_) / (y2_ - y1_)
                    xs.append(x1_ + t * (x2_ - x1_))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                xa = max(0, int(math.ceil(xs[i] - 0.5)))
                xb = min(self.W - 1, int(math.floor(xs[i + 1] - 0.5)))
                row = y * self.W
                for x in range(xa, xb + 1):
                    self.mask[row + x] = 1

    def fill_circle(self, cx, cy, r):
        cx, cy, r = cx * self.ss, cy * self.ss, r * self.ss
        y0, y1 = max(0, int(cy - r)), min(self.H - 1, int(cy + r))
        for y in range(y0, y1 + 1):
            dy = (y + 0.5) - cy
            if abs(dy) > r:
                continue
            dx = math.sqrt(max(0.0, r * r - dy * dy))
            x0, x1 = max(0, int(cx - dx)), min(self.W - 1, int(cx + dx))
            row = y * self.W
            for x in range(x0, x1 + 1):
                self.mask[row + x] = 1

    def stroke_line(self, x1, y1, x2, y2, width):
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy)
        if length == 0:
            return
        nx, ny = -dy / length * width / 2, dx / length * width / 2
        self.fill_polygon([(x1 + nx, y1 + ny), (x2 + nx, y2 + ny),
                            (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)])

    def fill_rect(self, x0, y0, x1, y1):
        self.fill_polygon([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])

    def to_rgba(self, color=BLACK):
        out = bytearray(self.w * self.h * 4)
        ss2 = self.ss * self.ss
        for y in range(self.h):
            for x in range(self.w):
                cov = 0
                for sy in range(self.ss):
                    base = (y * self.ss + sy) * self.W + x * self.ss
                    cov += sum(self.mask[base:base + self.ss])
                a = round(255 * cov / ss2)
                idx = (y * self.w + x) * 4
                out[idx], out[idx + 1], out[idx + 2], out[idx + 3] = color[0], color[1], color[2], a
        return bytes(out)

    def save(self, path, color=BLACK):
        write_png(path, self.w, self.h, self.to_rgba(color))



def star_points(cx, cy, r_outer, r_inner, points=5, rotation_deg=-90):
    pts = []
    for i in range(points * 2):
        r = r_outer if i % 2 == 0 else r_inner
        angle = math.radians(rotation_deg + i * (360 / (points * 2)))
        pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    return pts


def donor_star():
    c = Canvas(7, 7, ss=10)
    c.fill_polygon(star_points(3.5, 3.5, 3.3, 1.35))
    c.save(os.path.join(RP, "nordtal/textures/badges/donor_star.png"))


def prestige_crests():
    # A placeholder shield whose fill rises with the tier, so the thirteen tiers read in order.
    outline = [(1, 1), (8, 1), (8, 5), (4.5, 8.5), (1, 5)]
    for tier in range(1, 14):
        c = Canvas(9, 9, ss=10)
        # outline stroke
        n = len(outline)
        for i in range(n):
            x1, y1 = outline[i]
            x2, y2 = outline[(i + 1) % n]
            c.stroke_line(x1, y1, x2, y2, 0.9)
        # fill gauge: bottom fraction of the interior, tier/13
        frac = tier / 13
        inner_top, inner_bottom = 2.0, 7.6
        fill_top = inner_bottom - frac * (inner_bottom - inner_top)
        c.fill_rect(2.0, fill_top, 7.0, inner_bottom)
        c.save(os.path.join(RP, f"nordtal/textures/prestige/crest_{tier:02d}.png"))


BOARD_WIDTHS = [1, 2, 4, 8, 16, 32, 64, 128]
LINE_Y = 4.5  # the centre row of a 9 px cell
LINE_THICKNESS = 1.1


def board_frame():
    out = os.path.join(RP, "nordtal/textures/ui/board")

    # Each corner meets at the cell's centre, where the edge segments run, so the lines butt up.
    def corner(name, horiz_dir, vert_dir):
        c = Canvas(9, 9, ss=10)
        cx, cy = 4.5, 4.5
        hx = 8.5 if horiz_dir > 0 else 0.5
        vy = 8.5 if vert_dir > 0 else 0.5
        c.stroke_line(cx, cy, hx, cy, LINE_THICKNESS)
        c.stroke_line(cx, cy, cx, vy, LINE_THICKNESS)
        c.save(os.path.join(out, f"{name}.png"), BOARD_LINE)

    corner("corner_tl", +1, +1)  # continues right along the top, down along the left
    corner("corner_tr", -1, +1)  # continues left along the top, down along the right
    corner("corner_bl", +1, -1)  # continues up along the left, right along the bottom
    corner("corner_br", -1, -1)  # continues up along the right, left along the bottom

    for w in BOARD_WIDTHS:
        c = Canvas(w, 9, ss=10)
        c.stroke_line(0, LINE_Y, w, LINE_Y, LINE_THICKNESS)
        c.save(os.path.join(out, f"edge_h_{w}.png"), BOARD_LINE)
        # The divider is the edge in the accent colour.
        c2 = Canvas(w, 9, ss=10)
        c2.stroke_line(0, LINE_Y, w, LINE_Y, LINE_THICKNESS)
        c2.save(os.path.join(out, f"divider_{w}.png"), BOARD_ACCENT)

    for name in ("edge_v_l", "edge_v_r"):
        c = Canvas(9, 9, ss=10)
        c.stroke_line(4.5, 0, 4.5, 9, LINE_THICKNESS)
        c.save(os.path.join(out, f"{name}.png"), BOARD_LINE)


def dimension_icons():
    out = os.path.join(RP, "nordtal/textures/ui/bossbar/icons")

    c = Canvas(10, 10, ss=10)  # Nordtal (overworld): a simple globe/sun disc
    c.fill_circle(5, 5, 3.6)
    c.save(os.path.join(out, "dim_overworld.png"))

    c = Canvas(10, 10, ss=10)  # farm world: a sprout/leaf triangle
    c.fill_polygon([(5, 1.5), (8.5, 8.5), (1.5, 8.5)])
    c.save(os.path.join(out, "dim_farmworld.png"))

    c = Canvas(10, 10, ss=10)  # Nether: a flame silhouette
    c.fill_polygon([
        (5, 1), (6.6, 4), (8.5, 5.5), (7, 9), (3, 9), (1.5, 5.5), (3.4, 4),
    ])
    c.save(os.path.join(out, "dim_nether.png"))

    c = Canvas(10, 10, ss=10)  # End: a four-point sparkle
    c.fill_polygon(star_points(5, 5, 4, 1.1, points=4, rotation_deg=-90))
    c.save(os.path.join(out, "dim_end.png"))


def status_icons():
    out = os.path.join(RP, "nordtal/textures/ui/bossbar/icons")

    c = Canvas(10, 10, ss=10)  # alive: filled dot
    c.fill_circle(5, 5, 3.4)
    c.save(os.path.join(out, "status_alive.png"))

    c = Canvas(10, 10, ss=10)  # deaths: X mark
    c.stroke_line(1.8, 1.8, 8.2, 8.2, 1.6)
    c.stroke_line(8.2, 1.8, 1.8, 8.2, 1.6)
    c.save(os.path.join(out, "status_deaths.png"))

    c = Canvas(10, 10, ss=10)  # loot point: diamond
    c.fill_polygon([(5, 1), (9, 5), (5, 9), (1, 5)])
    c.save(os.path.join(out, "status_loot.png"))

    c = Canvas(10, 10, ss=10)  # border: square outline
    c.fill_rect(1.3, 1.3, 8.7, 8.7)
    inner = Canvas(10, 10, ss=10)
    inner.fill_rect(2.6, 2.6, 7.4, 7.4)
    for i in range(len(c.mask)):
        if inner.mask[i]:
            c.mask[i] = 0
    c.save(os.path.join(out, "status_border.png"))


ARROW_NAMES = [
    "000_0", "022_5", "045_0", "067_5", "090_0", "112_5", "135_0", "157_5",
    "180_0", "202_5", "225_0", "247_5", "270_0", "292_5", "315_0", "337_5",
]


def bearing_arrows():
    out = os.path.join(RP, "nordtal/textures/ui/bossbar/arrows")
    # Arrowhead + shaft, pointing "up" (north) at 0 degrees, relative to center.
    base = [
        (0, -3.6), (2.2, -0.6), (0.8, -0.6), (0.8, 3.6),
        (-0.8, 3.6), (-0.8, -0.6), (-2.2, -0.6),
    ]
    for i, name in enumerate(ARROW_NAMES):
        theta = math.radians(i * 22.5)
        cos_t, sin_t = math.cos(theta), math.sin(theta)
        pts = [(5 + dx * cos_t - dy * sin_t, 5 + dx * sin_t + dy * cos_t) for dx, dy in base]
        c = Canvas(10, 10, ss=10)
        c.fill_polygon(pts)
        c.save(os.path.join(out, f"arrow_{name}.png"))


def balloon_scaffold():
    # Flat placeholder textures for the item model's plumbing, not the balloon's art.
    envelope = Canvas(16, 16, ss=4)
    envelope.fill_rect(0, 0, 16, 16)
    write_png(os.path.join(RP, "nordtal/textures/item/balloon_envelope.png"), 16, 16,
               envelope.to_rgba((214, 122, 43)))  # placeholder orange, fully opaque

    basket = Canvas(16, 16, ss=4)
    basket.fill_rect(0, 0, 16, 16)
    write_png(os.path.join(RP, "nordtal/textures/item/balloon_basket.png"), 16, 16,
               basket.to_rgba((92, 64, 40)))  # placeholder brown, fully opaque

    model = os.path.join(RP, "nordtal/models/item/balloon.json")
    os.makedirs(os.path.dirname(model), exist_ok=True)
    with open(model, "w") as f:
        f.write(BALLOON_MODEL_JSON)
    print(f"wrote {os.path.relpath(model, REPO_ROOT)}")

    item_def = os.path.join(RP, "nordtal/items/balloon.json")
    os.makedirs(os.path.dirname(item_def), exist_ok=True)
    with open(item_def, "w") as f:
        f.write(BALLOON_ITEM_DEFINITION_JSON)
    print(f"wrote {os.path.relpath(item_def, REPO_ROOT)}")


BALLOON_MODEL_JSON = """{
    "_comment": "Placeholder geometry only - two flat-shaded cuboids standing in for the envelope and the basket. The real hot-air-balloon model is a Blockbench modelling task; this scaffold exists so the item-model plumbing (assets/nordtal/items/balloon.json, an ItemDisplay entity spawning it) can be built and tested before that art exists.",
    "parent": "minecraft:item/generated",
    "textures": {
        "envelope": "nordtal:item/balloon_envelope",
        "basket": "nordtal:item/balloon_basket",
        "particle": "nordtal:item/balloon_envelope"
    },
    "elements": [
        {
            "_comment": "envelope - placeholder cube, replace with real balloon geometry",
            "from": [4, 8, 4],
            "to": [12, 16, 12],
            "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#envelope"},
                "south": {"uv": [0, 0, 16, 16], "texture": "#envelope"},
                "east": {"uv": [0, 0, 16, 16], "texture": "#envelope"},
                "west": {"uv": [0, 0, 16, 16], "texture": "#envelope"},
                "up": {"uv": [0, 0, 16, 16], "texture": "#envelope"},
                "down": {"uv": [0, 0, 16, 16], "texture": "#envelope"}
            }
        },
        {
            "_comment": "basket - placeholder cube, replace with real balloon geometry",
            "from": [6, 2, 6],
            "to": [10, 6, 10],
            "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#basket"},
                "south": {"uv": [0, 0, 16, 16], "texture": "#basket"},
                "east": {"uv": [0, 0, 16, 16], "texture": "#basket"},
                "west": {"uv": [0, 0, 16, 16], "texture": "#basket"},
                "up": {"uv": [0, 0, 16, 16], "texture": "#basket"},
                "down": {"uv": [0, 0, 16, 16], "texture": "#basket"}
            }
        }
    ]
}
"""

BALLOON_ITEM_DEFINITION_JSON = """{
    "_comment": "Item model definition for the hot-air balloon (pack_format 88 / MC 26.2 items model system). Selected in-game by giving the placeholder item an item_model component of \\"nordtal:balloon\\"; the plugin spawns it on an ItemDisplay entity rather than handing it to a player.",
    "model": {
        "type": "minecraft:model",
        "model": "nordtal:item/balloon"
    }
}
"""


def lobby_maps():
    # 3 x 3 maps of 128 px, as HungerGamesSpec.LobbySpec expects; the corner swatch tells en from de.
    size = 384
    cell = 128

    def grid_image(accent):
        c = Canvas(size, size, ss=1)  # ss=1: this is a big flat placeholder, no AA needed
        rgba = bytearray(size * size * 4)
        for y in range(size):
            for x in range(size):
                idx = (y * size + x) * 4
                rgba[idx], rgba[idx + 1], rgba[idx + 2], rgba[idx + 3] = 60, 90, 60, 255
        # grid lines every 128px
        for g in (0, cell, cell * 2, size - 1):
            for x in range(size):
                for y in (g, min(g, size - 1)):
                    idx = (y * size + x) * 4
                    rgba[idx], rgba[idx + 1], rgba[idx + 2] = 20, 20, 20
            for y in range(size):
                for x in (g, min(g, size - 1)):
                    idx = (y * size + x) * 4
                    rgba[idx], rgba[idx + 1], rgba[idx + 2] = 20, 20, 20
        # language accent swatch, top-left cell
        for y in range(16, 48):
            for x in range(16, 48):
                idx = (y * size + x) * 4
                rgba[idx], rgba[idx + 1], rgba[idx + 2] = accent
        return bytes(rgba)

    write_png(os.path.join(HG_RES, "lobby/map-en.png"), size, size, grid_image((60, 110, 200)))
    write_png(os.path.join(HG_RES, "lobby/map-de.png"), size, size, grid_image((200, 60, 60)))


def system_icons():
    """The six \uFE080-\uFE085 chat-line icons, 7 x 7 at ascent 7 in minecraft:default."""
    out = os.path.join(RP, "nordtal/textures/system")

    # One hairline with 1 px of air on each side.
    c = Canvas(3, 7, ss=10)
    c.fill_rect(1.0, 0.5, 2.0, 6.5)
    c.save(os.path.join(out, "separator.png"), SYSTEM_WHITE)

    # Joined and left are the same triangle, mirrored.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(1.5, 0.8), (6.0, 3.5), (1.5, 6.2)])
    c.save(os.path.join(out, "join.png"), SYSTEM_WHITE)

    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(5.5, 0.8), (1.0, 3.5), (5.5, 6.2)])
    c.save(os.path.join(out, "leave.png"), SYSTEM_WHITE)

    # A headstone, since a skull at seven pixels is three blobs.
    c = Canvas(7, 7, ss=10)
    c.fill_circle(3.5, 3.1, 2.3)
    c.fill_rect(1.2, 3.1, 5.8, 6.4)
    c.save(os.path.join(out, "death.png"), SYSTEM_WHITE)

    # Four points, not five: the donor badge is a five-point star in the same chat line.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon(star_points(3.5, 3.5, 3.4, 0.85, points=4))
    c.save(os.path.join(out, "advancement.png"), SYSTEM_WHITE)

    # A horn for announcements, asymmetric so it never reads as the sparkle.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(1.3, 2.1), (5.7, 0.7), (5.7, 6.3), (1.3, 4.9)])
    c.save(os.path.join(out, "announce.png"), SYSTEM_WHITE)



def main():
    donor_star()
    prestige_crests()
    board_frame()
    # generate_hud.py draws the dimension and status icons; the functions here are not called.
    bearing_arrows()
    system_icons()
    balloon_scaffold()
    lobby_maps()


if __name__ == "__main__":
    main()
