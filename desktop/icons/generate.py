#!/usr/bin/env python3
"""
Renders the desktop icons from the same quill artwork the Android launcher uses.

Without an `iconFile`, jpackage substitutes Compose's own logo, so the installed app appears as a
Kotlin icon. The source of truth is app/src/main/res/drawable/ic_launcher_foreground.xml; the paths
are transcribed here rather than parsed so this has no SVG-renderer dependency, and the shapes are
simple enough that transcription is honest.

The Android icon is an adaptive one, drawn inside a 72dp safe zone of a 108dp canvas. A desktop
icon has no such mask, so the artwork is fitted to its own bounding box instead — otherwise the
quill would sit small in the middle of a teal square.

    python3 desktop/icons/generate.py
"""

import struct
from pathlib import Path

from PIL import Image, ImageDraw

BACKGROUND = (0x1E, 0x4D, 0x4A, 0xFF)

# (fill, subpaths) transcribed from ic_launcher_foreground.xml, in its 108x108 viewport.
# Each subpath is a start point followed by segments: ("C", c1, c2, end) or ("L", end).
PATHS = [
    (
        (0xF7, 0xF6, 0xF3, 0xFF),  # Feather body.
        (32, 76),
        [("C", (32, 54), (44, 36), (74, 28)), ("C", (70, 54), (58, 70), (38, 76))],
    ),
    (
        (0x7F, 0xD1, 0xC4, 0xFF),  # Nib shoulder, the darker under-side of the vane.
        (32, 76),
        [("C", (40, 60), (54, 44), (74, 28)), ("C", (58, 50), (44, 64), (38, 76))],
    ),
    (
        (0xF7, 0xF6, 0xF3, 0xFF),  # Shaft running down to the writing tip.
        (31, 79),
        [("L", (74, 28)), ("L", (76, 30)), ("L", (34, 80))],
    ),
    (
        (0xE8, 0xA8, 0x7C, 0xFF),  # The ink point.
        (28, 84),
        [("L", (34, 77)), ("L", (36, 79))],
    ),
]

SUPERSAMPLE = 4
MARGIN = 0.14  # Fraction of the icon left clear around the artwork.
CORNER = 0.18  # Corner radius as a fraction of the icon, so it reads as an app icon.


def cubic(p0, c1, c2, p3, steps=48):
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u**2 * t * c1[0] + 3 * u * t**2 * c2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u**2 * t * c1[1] + 3 * u * t**2 * c2[1] + t**3 * p3[1]
        yield (x, y)


def flatten(start, segments):
    points = [start]
    current = start
    for segment in segments:
        if segment[0] == "C":
            points.extend(cubic(current, segment[1], segment[2], segment[3]))
            current = segment[3]
        else:
            points.append(segment[1])
            current = segment[1]
    return points


def render(size):
    scale = size * SUPERSAMPLE
    image = Image.new("RGBA", (scale, scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(
        [0, 0, scale - 1, scale - 1], radius=int(scale * CORNER), fill=BACKGROUND
    )

    shapes = [(fill, flatten(start, segments)) for fill, start, segments in PATHS]

    # Fit the artwork's own bounds rather than the 108x108 adaptive canvas.
    xs = [x for _, pts in shapes for x, _ in pts]
    ys = [y for _, pts in shapes for _, y in pts]
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    factor = scale * (1 - 2 * MARGIN) / span
    offset_x = (scale - (max(xs) - min(xs)) * factor) / 2 - min(xs) * factor
    offset_y = (scale - (max(ys) - min(ys)) * factor) / 2 - min(ys) * factor

    for fill, points in shapes:
        draw.polygon(
            [(x * factor + offset_x, y * factor + offset_y) for x, y in points], fill=fill
        )

    return image.resize((size, size), Image.LANCZOS)


def write_icns(path, sizes):
    """
    Pillow cannot save ICNS, but the container is simple: a header, then typed PNG chunks.
    These are the PNG-based types macOS has understood since 10.7.
    """
    types = {
        16: b"icp4", 32: b"ic11", 64: b"ic12", 128: b"ic07",
        256: b"ic13", 512: b"ic14", 1024: b"ic10",
    }
    chunks = b""
    for size in sizes:
        code = types.get(size)
        if not code:
            continue
        temp = path.parent / f".icns-{size}.png"
        render(size).save(temp, "PNG")
        data = temp.read_bytes()
        temp.unlink()
        chunks += code + struct.pack(">I", len(data) + 8) + data
    path.write_bytes(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)


# The sizes a freedesktop icon theme is asked for: a panel wants 24, a dash 48, a switcher 128.
HICOLOR = (16, 24, 32, 48, 64, 128, 256)


def main():
    here = Path(__file__).parent

    render(1024).save(here / "plume.png", "PNG")

    # An icon theme, rather than one large PNG for every desktop to shrink itself. Each size is
    # rendered from the artwork, so a 24px panel entry is drawn rather than resampled from 256.
    for size in HICOLOR:
        target = here / "hicolor" / f"{size}x{size}" / "apps"
        target.mkdir(parents=True, exist_ok=True)
        render(size).save(target / "plume.png", "PNG")

    # Windows reads the size it needs out of the .ico, so all of them are embedded.
    render(256).save(
        here / "plume.ico",
        "ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

    write_icns(here / "plume.icns", [16, 32, 64, 128, 256, 512, 1024])

    for name in ("plume.png", "plume.ico", "plume.icns"):
        print(f"{name}: {(here / name).stat().st_size:,} bytes")
    print(f"hicolor: {len(HICOLOR)} sizes")


if __name__ == "__main__":
    main()
