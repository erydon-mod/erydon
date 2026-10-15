#!/usr/bin/env python3
"""Render current Georgian assemblies from their actual generated JSON faces.

This optional software preview uses Pillow and NumPy. --check compares images
without writing; models, textures and editable Blockbench sources are untouched.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, PngImagePlugin

import render_georgian_alcove_preview as software

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "authoring/arches/georgian"
UNIT_SCALE = 11.0
BOARDS = (
    ((1, 2, 3), (350, 560, 810), 660, "assemblies"),
    ((4, 5, 6), (850, 1050, 1250), 820, "assemblies_wide"),
)
VIEWS = {"preview": (0.6, 0.25, 1.0), "front_preview": (0.0, 0.0, 1.0)}


def render_boards():
    paths = {width: DESIGN / f"arch_georgian_current_{width}wide.json" for width in range(1, 7)}
    models = {}
    for width, path in paths.items():
        if width <= 3 or path.exists():
            models[width] = software.read_faces(path)

    boards = [board for board in BOARDS if all(width in models for width in board[0])]
    axes_by_view = {name: software.view_axes(camera) for name, camera in VIEWS.items()}
    scale = UNIT_SCALE
    for widths, panel_widths, panel_height, _ in boards:
        for axes in axes_by_view.values():
            for width, panel_width in zip(widths, panel_widths):
                points = np.asarray([point for face in models[width] for point in face.vertices])
                horizontal, vertical = points @ axes[1], points @ axes[2]
                scale = min(scale, (panel_width-40)/(horizontal.max()-horizontal.min()),
                            (panel_height-25)/(vertical.max()-vertical.min()))
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text("model_sources", json.dumps({
        paths[width].name: hashlib.sha256(paths[width].read_bytes()).hexdigest()
        for width in models
    }, sort_keys=True))
    metadata.add_text("model_unit_scale", str(scale))
    output = {}
    for widths, panel_widths, panel_height, prefix in boards:
        software.PANEL_HEIGHT = panel_height
        for view, axes in axes_by_view.items():
            image_size = (sum(panel_widths)+40+20*len(widths), panel_height+190)
            canvas = Image.new("RGB", image_size, software.BACKGROUND)
            draw = ImageDraw.Draw(canvas)
            draw.text((38, 26), "Georgian arches — updated design",
                      fill=(41, 47, 45), font=software.font(28))
            draw.text((38, 68), "Actual model geometry; same scale across widths 1–6",
                      fill=(81, 87, 83), font=software.font(21))
            x_offset = 30
            for width, panel_width in zip(widths, panel_widths):
                canvas.paste(software.render_panel(models[width], panel_width, axes, scale), (x_offset, 150))
                label = f"{width}-wide"
                label_font = software.font(21)
                label_width = draw.textlength(label, font=label_font)
                draw.text((x_offset+(panel_width-label_width)/2, 112), label,
                          fill=(64, 70, 66), font=label_font)
                x_offset += panel_width+20
            stream = io.BytesIO()
            canvas.save(stream, format="PNG", pnginfo=metadata, compress_level=9)
            output[DESIGN / f"{prefix}_{view}.png"] = stream.getvalue()
    return output, {width: len(faces) for width, faces in models.items()}, scale


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output, counts, scale = render_boards()
    output.update(render_short_boards(scale))
    changed = [path for path, data in output.items() if not path.is_file() or path.read_bytes() != data]
    if args.check:
        if changed:
            print("Previews need regeneration: " + ", ".join(path.name for path in changed))
            return 1
    else:
        for path in changed:
            path.write_bytes(output[path])
    print(f"Georgian previews match actual geometry: faces={counts}, pixels/model-unit={scale:.6f}.")
    return 0


def render_short_boards(scale):
    paths = {w:DESIGN/f'arch_georgian_current_{w}wide_short.json' for w in (4,5,6)}
    models = {w:software.read_faces(path) for w,path in paths.items()}
    panel_widths = (850,1050,1250)
    software.PANEL_HEIGHT = 330
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text('model_sources',json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths.values()},sort_keys=True))
    metadata.add_text('model_unit_scale',str(scale))
    output = {}
    for view,camera in VIEWS.items():
        canvas = Image.new('RGB',(sum(panel_widths)+100,520),software.BACKGROUND)
        draw = ImageDraw.Draw(canvas)
        draw.text((38,26),'Georgian arches — short crowns',fill=(41,47,45),font=software.font(28))
        draw.text((38,68),'One-row profiles; same scale as the full arches',fill=(81,87,83),font=software.font(21))
        offset = 30
        for width,panel_width in zip((4,5,6),panel_widths):
            canvas.paste(software.render_panel(models[width],panel_width,software.view_axes(camera),scale),(offset,150))
            label = f'{width}-wide'
            font = software.font(21)
            draw.text((offset+(panel_width-draw.textlength(label,font=font))/2,112),label,fill=(64,70,66),font=font)
            offset += panel_width+20
        stream = io.BytesIO()
        canvas.save(stream,format='PNG',pnginfo=metadata,compress_level=9)
        output[DESIGN/f'short_crowns_{view}.png'] = stream.getvalue()
    return output


if __name__ == "__main__":
    raise SystemExit(main())
