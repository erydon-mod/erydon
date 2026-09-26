"""Export GUI materials from the owned Portoro photograph; no generated stone.

Usage: python tools/generate_portorium_controls.py --stone SOURCE.png --output DIR
The existing Glacium panel in DIR keeps its centre; only its narrow trim changes.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image


def frame(width: int, height: int) -> Image.Image:
    image = Image.new("RGBA", (width, height))
    pixels = image.load()
    # Four source pixels per GUI pixel. A broad planar stone chamfer surrounds
    # a dark cut channel, polished metal and a fine lower recess highlight.
    lit = [(8, 9, 8, 255), (225, 219, 199, 135), (180, 177, 162, 105),
           (154, 153, 141, 85), (123, 124, 114, 65), (90, 94, 86, 50),
           (35, 38, 34, 90), (6, 8, 6, 165), (0, 0, 0, 220),
           (9, 8, 5, 255), (65, 48, 26, 255), (237, 222, 177, 255),
           (194, 159, 98, 255), (142, 108, 59, 255), (9, 8, 5, 240),
           (218, 199, 147, 65)]
    shaded = [(4, 5, 4, 255), (3, 5, 4, 210), (7, 9, 7, 190),
              (11, 14, 11, 175), (17, 20, 16, 160), (24, 27, 22, 150),
              (54, 57, 47, 100), (94, 96, 77, 70), (0, 0, 0, 230),
              (7, 6, 4, 255), (37, 27, 15, 255), (135, 102, 57, 255),
              (215, 184, 126, 255), (252, 239, 194, 255), (18, 13, 7, 240),
              (248, 231, 183, 110)]
    for y in range(height):
        for x in range(width):
            distance = min(x, y, width - 1 - x, height - 1 - y)
            if distance < 16:
                light_side = min(x, y) <= min(width - 1 - x, height - 1 - y)
                pixels[x, y] = (lit if light_side else shaded)[distance]
    return image


def export(stone: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    with Image.open(stone) as source:
        source.convert("RGB").resize((1024, 1024), Image.Resampling.LANCZOS).save(
            output / "portorium_control_background.png")
    frame(128, 80).save(output / "portorium_control_frame.png")
    filtering = json.dumps({"texture": {"blur": True, "clamp": True}}, indent=2) + "\n"
    for name in ("portorium_control_background.png", "portorium_control_frame.png"):
        (output / (name + ".mcmeta")).write_text(filtering, encoding="utf-8")
    panel_path = output / "glacium_config_background.png"
    if panel_path.exists():
        with Image.open(panel_path) as original:
            panel = original.convert("RGBA")
        pixels = panel.load()
        # Match the button inlay's warm, pale metal without changing Glacium.
        light = [(31, 26, 17), (115, 87, 47), (243, 227, 184),
                 (204, 173, 115), (131, 100, 55), (36, 28, 17), (103, 91, 66)]
        dark = [(28, 23, 16), (66, 48, 26), (145, 109, 59),
                (205, 173, 115), (249, 233, 189), (39, 29, 17), (157, 143, 113)]
        for y in range(panel.height):
            for x in range(panel.width):
                distance = min(x, y, panel.width - 1 - x, panel.height - 1 - y)
                if 5 <= distance <= 11:
                    palette = light if min(x, y) <= min(panel.width - 1 - x, panel.height - 1 - y) else dark
                    pixels[x, y] = (*palette[distance - 5], 255)
        panel.save(panel_path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stone", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.stone, args.output)
