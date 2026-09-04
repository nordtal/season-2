#!/usr/bin/env python3
"""Generates every still-undrawn resource-pack glyph as a dummy PNG at final pixel
dimensions, plus the balloon item-model scaffold and the hunger-games lobby map
placeholders.

Why this exists: resource-pack/README.md's code point allocation table (decided
2026-08-31) fully specifies which code points, which font, and which pixel metrics
every one of these glyphs gets - the only thing missing was the drawing itself. This
script draws simple, deterministic, reproducible placeholder/candidate art so the pack,
the font JSON and Glyphs can be wired up and tested against a running server before the
real design pass happens. Re-run it whenever a metric in the README table changes.

Pure standard library - no Pillow, no ImageMagick (neither is installed on this
machine, checked 2026-08-31). PNG encoding is a minimal RGBA/8 writer; shading is a
supersampled polygon/line/circle rasterizer producing anti-aliased black-on-transparent
icons, which is what "a simple black star" (or arrow, or crest) means at this size.

Usage:
    python3 resource-pack/tools/generate_dummy_textures.py
"""

import math
import os
import struct
import zlib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RP = os.path.join(REPO_ROOT, "resource-pack", "src", "assets")
HG_RES = os.path.join(REPO_ROOT, "hunger-games", "src", "main", "resources")

BLACK = (0, 0, 0)

# The board frame's two colours, taken verbatim from generate_gui_panels.py's PALETTE so the
# boards and the menus read as one server's furniture (2026-09-04). Two things changed here that
# day, and only one of them was about style:
#
#   * the frame was drawn BLACK, which is the module's default and was never a decision. A board
#     hangs in the world on a Text Display's dark translucent background, so a black frame is a
#     frame nobody can see. Nothing had ever rendered one, so nothing had ever noticed.
#   * the divider is now the accent and the border is not. The pack has allocated the two
#     separately since 2026-08-31 precisely so an interior rule could differ from the border, and
#     the panels already spend their one saturated colour on exactly this - a single line under the
#     title bar and nowhere else. Same rule on both surfaces.
BOARD_LINE = (78, 86, 104)      # PALETTE["highlight"]
BOARD_ACCENT = (176, 138, 74)   # PALETTE["accent"]

# The system-line icons are drawn WHITE, and that is the whole trick rather than a taste in
# colours. Minecraft multiplies a glyph's texture by the component's text colour, so white art can
# be tinted to anything a message wants and black art cannot be tinted lighter than black. Every
# one of these six sits in a chat line whose colour the bundle decides - and the board frame spent
# four days invisible for exactly the opposite reason (2026-09-04).
SYSTEM_WHITE = (255, 255, 255)


# --- Minimal PNG writer (RGBA, 8-bit, no filtering) -------------------------------

def write_png(path, width, height, rgba_bytes):
    os.makedirs(os.path.dirname(path), exist_ok=True)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # color type 6 = RGBA
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (none) per scanline
        raw.extend(rgba_bytes[y * stride:(y + 1) * stride])
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))
    print(f"wrote {os.path.relpath(path, REPO_ROOT)} ({width}x{height})")


# --- Supersampled rasterizer -------------------------------------------------------

class Canvas:
    """Coverage-based rasterizer: shapes are drawn into an (w*ss) x (h*ss) boolean
    mask, then downsampled into a per-pixel alpha (0..255) that becomes the glyph's
    anti-aliased opacity. Color is fixed per canvas (black, per the request)."""

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


# --- Shapes -------------------------------------------------------------------------

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
    # A shield outline (thin stroke) with a bottom-up fill gauge proportional to the
    # tier (1..13) - a placeholder that at least visually orders the thirteen tiers,
    # not an attempt at the real coat-of-arms art (design work, out of scope here).
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
LINE_Y = 4.5  # vertically centered row in a 9-tall cell, per the confirmed design
LINE_THICKNESS = 1.1


def board_frame():
    out = os.path.join(RP, "nordtal/textures/ui/board")

    # Corners: an L-shaped stub meeting at the cell's center (4.5, 4.5), so that when
    # placed next to an edge/divider segment (also centered at row 4.5) the lines butt
    # up seamlessly regardless of which line of the board a cell sits on.
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
        # Same geometry as the outer edge, different colour: the divider carries the accent,
        # the way the menu panel's one accent line sits under its title bar. That is what the
        # separate allocation of 2026-08-31 was reserved for.
        c2 = Canvas(w, 9, ss=10)
        c2.stroke_line(0, LINE_Y, w, LINE_Y, LINE_THICKNESS)
        c2.save(os.path.join(out, f"divider_{w}.png"), BOARD_ACCENT)

    for name in ("edge_v_l", "edge_v_r"):
        c = Canvas(9, 9, ss=10)
        c.stroke_line(4.5, 0, 4.5, 9, LINE_THICKNESS)
        c.save(os.path.join(out, f"{name}.png"), BOARD_LINE)


def dimension_icons():
    out = os.path.join(RP, "nordtal/textures/ui/bossbar/icons")

    c = Canvas(10, 10, ss=10)  # Nordtal (overworld) - a simple globe/sun disc
    c.fill_circle(5, 5, 3.6)
    c.save(os.path.join(out, "dim_overworld.png"))

    c = Canvas(10, 10, ss=10)  # farm world - a sprout/leaf triangle
    c.fill_polygon([(5, 1.5), (8.5, 8.5), (1.5, 8.5)])
    c.save(os.path.join(out, "dim_farmworld.png"))

    c = Canvas(10, 10, ss=10)  # Nether - a flame silhouette
    c.fill_polygon([
        (5, 1), (6.6, 4), (8.5, 5.5), (7, 9), (3, 9), (1.5, 5.5), (3.4, 4),
    ])
    c.save(os.path.join(out, "dim_nether.png"))

    c = Canvas(10, 10, ss=10)  # End - a four-point sparkle
    c.fill_polygon(star_points(5, 5, 4, 1.1, points=4, rotation_deg=-90))
    c.save(os.path.join(out, "dim_end.png"))


def status_icons():
    out = os.path.join(RP, "nordtal/textures/ui/bossbar/icons")

    c = Canvas(10, 10, ss=10)  # alive - filled dot
    c.fill_circle(5, 5, 3.4)
    c.save(os.path.join(out, "status_alive.png"))

    c = Canvas(10, 10, ss=10)  # deaths - X mark
    c.stroke_line(1.8, 1.8, 8.2, 8.2, 1.6)
    c.stroke_line(8.2, 1.8, 1.8, 8.2, 1.6)
    c.save(os.path.join(out, "status_deaths.png"))

    c = Canvas(10, 10, ss=10)  # loot point - diamond
    c.fill_polygon([(5, 1), (9, 5), (5, 9), (1, 5)])
    c.save(os.path.join(out, "status_loot.png"))

    c = Canvas(10, 10, ss=10)  # border - square outline
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
    # Flat two-tone placeholder textures - a scaffold for the item-model plumbing,
    # not the balloon's real art (a Blockbench modelling task, out of scope here).
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
    "_comment": "Placeholder geometry only - two flat-shaded cuboids standing in for the envelope and the basket. The real hot-air-balloon model (docs/smp.md#the-nordtal-spawn) is a Blockbench modelling task; this scaffold exists so the item-model plumbing (assets/nordtal/items/balloon.json, an ItemDisplay entity spawning it) can be built and tested before that art exists.",
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
    "_comment": "Item model definition for the hot-air balloon (pack_format 88 / MC 26.2 items model system). Selected in-game by giving the placeholder item an item_model component of \\"nordtal:balloon\\" - the plugin is expected to spawn it on an ItemDisplay entity rather than hand it to a player, per docs/smp.md#the-nordtal-spawn (\\"custom 3D model, barrier-block floor; stepping in opens the travel GUI\\").",
    "model": {
        "type": "minecraft:model",
        "model": "nordtal:item/balloon"
    }
}
"""


def lobby_maps():
    # 3x3 grid @ 128px/map = 384x384, per the 2026-08-31 decision (overriding the
    # code's previous 4x3 default - see HungerGamesSpec.LobbySpec, updated alongside
    # this script). One flat background plus a visible 3x3 grid so frame boundaries
    # are checkable in-game, and a corner swatch distinguishing en/de so the
    # per-language slice in LobbyMaps#renderLanguage is verifiable at a glance.
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
    """The six \uFE080-\uFE085 chat-line icons, in minecraft:default at the 7 x 7 / ascent 7
    metrics every other default-font glyph in this pack uses.

    Six shapes for six lines, and they have to be distinguishable at seven pixels while somebody is
    mining, which is why none of them is a small picture of the thing it means: an arrow in, an
    arrow out, a headstone, a spark, a horn, and a rule that is not an icon at all.
    """
    out = os.path.join(RP, "nordtal/textures/system")

    # A separator, not a character: one hairline, 1 px of air either side.
    c = Canvas(3, 7, ss=10)
    c.fill_rect(1.0, 0.5, 2.0, 6.5)
    c.save(os.path.join(out, "separator.png"), SYSTEM_WHITE)

    # Joined / left: the same triangle, mirrored. Direction is the only thing carrying the meaning,
    # so they are deliberately identical apart from it.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(1.5, 0.8), (6.0, 3.5), (1.5, 6.2)])
    c.save(os.path.join(out, "join.png"), SYSTEM_WHITE)

    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(5.5, 0.8), (1.0, 3.5), (5.5, 6.2)])
    c.save(os.path.join(out, "leave.png"), SYSTEM_WHITE)

    # A headstone rather than a skull: at seven pixels a skull is three grey blobs, and the season
    # already answers a death with a grave standing where you fell.
    c = Canvas(7, 7, ss=10)
    c.fill_circle(3.5, 3.1, 2.3)
    c.fill_rect(1.2, 3.1, 5.8, 6.4)
    c.save(os.path.join(out, "death.png"), SYSTEM_WHITE)

    # Four points, not five: the donor badge is a five-point star in the same chat line.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon(star_points(3.5, 3.5, 3.4, 0.85, points=4))
    c.save(os.path.join(out, "advancement.png"), SYSTEM_WHITE)

    # A horn, for the lines the whole server is told. Convex and asymmetric, so it cannot be
    # confused with the sparkle beside it.
    c = Canvas(7, 7, ss=10)
    c.fill_polygon([(1.3, 2.1), (5.7, 0.7), (5.7, 6.3), (1.3, 4.9)])
    c.save(os.path.join(out, "announce.png"), SYSTEM_WHITE)



def main():
    donor_star()
    prestige_crests()
    board_frame()
    dimension_icons()
    status_icons()
    bearing_arrows()
    system_icons()
    balloon_scaffold()
    lobby_maps()


if __name__ == "__main__":
    main()
