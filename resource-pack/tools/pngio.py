#!/usr/bin/env python3
"""Reads and writes PNGs without Pillow, which this repository's tools may not assume.

Why a reader exists here at all: two tools have to *look at* the pack rather than only produce it.
`export_bossbar_advances.py` derives every glyph's advance from the rightmost drawn column of its
cell, the way the client does, and the panel generator reads its own output back to assert the
advance it promises. Both need to read the season-1 sheets too (`ascii.png`, the flags, the
compass), and those were not written by `write_png` - they are palette PNGs at 4 or 8 bits, so the
reader handles colour types 0, 2, 3, 4 and 6 at bit depths 1, 2, 4 and 8, non-interlaced, which is
every PNG in `resource-pack/src/assets` (checked 2026-09-05).

The writer is the one `generate_dummy_textures.py` has carried since 2026-08-31, moved here so the
generators share it rather than each importing the other.
"""
import os
import struct
import zlib

SIGNATURE = b"\x89PNG\r\n\x1a\n"


def write_png(path, width, height, rgba_bytes, repo_root=None):
    """Writes 8-bit RGBA, unfiltered, non-interlaced - the one shape every reader takes."""
    os.makedirs(os.path.dirname(path), exist_ok=True)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # colour type 6 = RGBA
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (none) per scanline
        raw.extend(rgba_bytes[y * stride:(y + 1) * stride])
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(SIGNATURE)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))
    shown = os.path.relpath(path, repo_root) if repo_root else path
    print(f"wrote {shown} ({width}x{height})")


def read_png(path):
    """Returns (width, height, rgba bytes) for any non-interlaced PNG the pack contains."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != SIGNATURE:
        raise ValueError(f"{path} is not a PNG")

    pos = 8
    idat = b""
    width = height = depth = colour_type = None
    palette = None
    transparency = None
    while pos < len(data):
        length, = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, colour_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if interlace:
                raise ValueError(f"{path} is interlaced, which nothing here writes or reads")
        elif tag == b"IDAT":
            idat += body
        elif tag == b"PLTE":
            palette = body
        elif tag == b"tRNS":
            transparency = body

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colour_type]
    if depth != 8 and not (colour_type in (0, 3) and depth in (1, 2, 4)):
        raise ValueError(f"{path}: colour type {colour_type} at {depth} bits is not supported")

    raw = zlib.decompress(idat)
    stride = (width * channels * depth + 7) // 8
    bytes_per_pixel = max(1, channels * depth // 8)
    rows = []
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        filter_type = raw[at]
        at += 1
        line = bytearray(raw[at:at + stride])
        at += stride
        for i in range(stride):
            left = line[i - bytes_per_pixel] if i >= bytes_per_pixel else 0
            up = previous[i]
            up_left = previous[i - bytes_per_pixel] if i >= bytes_per_pixel else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 255
            elif filter_type == 2:
                line[i] = (line[i] + up) & 255
            elif filter_type == 3:
                line[i] = (line[i] + (left + up) // 2) & 255
            elif filter_type == 4:
                guess = left + up - up_left
                d_left, d_up, d_up_left = abs(guess - left), abs(guess - up), abs(guess - up_left)
                if d_left <= d_up and d_left <= d_up_left:
                    predictor = left
                elif d_up <= d_up_left:
                    predictor = up
                else:
                    predictor = up_left
                line[i] = (line[i] + predictor) & 255
        previous = line
        if depth < 8:
            unpacked = bytearray()
            for byte in line:
                for k in range(8 // depth):
                    unpacked.append((byte >> (8 - depth * (k + 1))) & ((1 << depth) - 1))
            line = unpacked[:width]
        rows.append(bytes(line))

    out = bytearray(width * height * 4)
    scale = 255 // ((1 << depth) - 1) if depth < 8 else 1
    for y in range(height):
        line = rows[y]
        for x in range(width):
            if colour_type == 6:
                r, g, b, a = line[x * 4:x * 4 + 4]
            elif colour_type == 2:
                r, g, b = line[x * 3:x * 3 + 3]
                a = 255
            elif colour_type == 0:
                r = g = b = line[x] * scale
                a = 255
            elif colour_type == 4:
                r = g = b = line[x * 2]
                a = line[x * 2 + 1]
            else:  # 3, palette
                index = line[x]
                r, g, b = palette[index * 3:index * 3 + 3]
                a = transparency[index] if transparency and index < len(transparency) else 255
            i = (y * width + x) * 4
            out[i], out[i + 1], out[i + 2], out[i + 3] = r, g, b, a
    return width, height, bytes(out)


def rightmost_drawn_column(rgba, width, height, x0=0, y0=0, cell_width=None, cell_height=None):
    """The rightmost column with any alpha inside a cell, or -1 - the client's own measure.

    A bitmap glyph's advance is this plus two (one for the column itself, one the client adds
    after every glyph), which is what `BoardFrameTest` and `MenuTitleTest` both derive and what
    `export_bossbar_advances.py` writes down for the plugins.
    """
    cell_width = cell_width or width
    cell_height = cell_height or height
    for x in range(x0 + cell_width - 1, x0 - 1, -1):
        for y in range(y0, y0 + cell_height):
            if rgba[(y * width + x) * 4 + 3]:
                return x - x0
    return -1
