#!/usr/bin/env python3
"""Render deterministic Georgian authoring previews from the actual JSON faces.

Requires Pillow and NumPy for this optional authoring utility. It never changes
models, textures or Blockbench sources. --check compares previews without writes.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, PngImagePlugin

import model_z_fighting_safety as geometry


ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "authoring/alcoves/georgian"
BACKGROUND = (239, 240, 238)
STONE = np.array((198, 199, 191), dtype=float)
PANEL_WIDTHS = (350, 560, 810)
PANEL_HEIGHT = 830
IMAGE_SIZE = (1800, 1040)
UNIT_SCALE = 13.0
VIEWS = {
    "assemblies_preview.png": (0.48, 0.30, 1.0),
    "assemblies_front_preview.png": (0.0, 0.0, 1.0),
}


def read_faces(path: Path):
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    rotations, _ = geometry._raw_group_state(document, len(document["elements"]))
    faces, diagnostics = geometry._build_faces(document["elements"], raw_group_rotations=rotations)
    unsupported = [entry for entry in diagnostics if entry["reason"] not in
                   ("zero-thickness element is unsupported", "degenerate face normal")]
    if unsupported:
        raise ValueError(f"Cannot render {path.name}: {unsupported}")
    return faces


def font(size: int):
    for filename in ("DejaVuSans.ttf", "segoeui.ttf"):
        try:
            return ImageFont.truetype(filename, size)
        except OSError:
            pass
    return ImageFont.load_default(size=size)


def view_axes(camera):
    camera = np.asarray(camera, dtype=float)
    camera /= np.linalg.norm(camera)
    right = np.cross((0.0, 1.0, 0.0), camera)
    right /= np.linalg.norm(right)
    return camera, right, np.cross(camera, right)


def render_panel(faces, width, axes, scale):
    camera, right, up = axes
    vertices = np.asarray([point for face in faces for point in face.vertices])
    horizontal, vertical = vertices @ right, vertices @ up
    midpoint_x = (horizontal.min() + horizontal.max()) / 2
    midpoint_y = (vertical.min() + vertical.max()) / 2
    pixels = np.full((PANEL_HEIGHT, width, 3), BACKGROUND, dtype=np.uint8)
    depth = np.full((PANEL_HEIGHT, width), -np.inf)
    light = np.asarray((-0.45, 0.85, 1.0))
    light /= np.linalg.norm(light)

    for face in faces:
        normal = np.asarray(face.normal)
        if normal @ camera < 1e-7:
            continue
        points = np.asarray(face.vertices)
        projected = np.column_stack(((points @ right - midpoint_x) * scale + width / 2,
                                     PANEL_HEIGHT / 2 - (points @ up - midpoint_y) * scale,
                                     points @ camera))
        color = np.clip(STONE * (0.57 + 0.43 * max(0, float(normal @ light))), 0, 255).astype(np.uint8)
        for indices in ((0, 1, 2), (0, 2, 3)):
            triangle = projected[list(indices)]
            left = max(0, int(np.floor(triangle[:, 0].min())))
            right_edge = min(width - 1, int(np.ceil(triangle[:, 0].max())))
            top = max(0, int(np.floor(triangle[:, 1].min())))
            bottom = min(PANEL_HEIGHT - 1, int(np.ceil(triangle[:, 1].max())))
            if left > right_edge or top > bottom:
                continue
            x, y = np.meshgrid(np.arange(left, right_edge + 1) + 0.5,
                               np.arange(top, bottom + 1) + 0.5)
            x1, y1, z1 = triangle[0]
            x2, y2, z2 = triangle[1]
            x3, y3, z3 = triangle[2]
            denominator = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
            if abs(denominator) < 1e-9:
                continue
            a = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / denominator
            b = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / denominator
            c = 1 - a - b
            z = a * z1 + b * z2 + c * z3
            region = depth[top:bottom + 1, left:right_edge + 1]
            visible = (a >= -1e-8) & (b >= -1e-8) & (c >= -1e-8) & (z > region)
            region[visible] = z[visible]
            pixels[top:bottom + 1, left:right_edge + 1][visible] = color
    return Image.fromarray(pixels)


def render_boards():
    paths = [DESIGN / f"alcove_georgian_{width}wide.json" for width in (1, 2, 3)]
    models = [read_faces(path) for path in paths]
    views = {filename: view_axes(camera) for filename, camera in VIEWS.items()}
    scale = UNIT_SCALE
    # Every board and width uses the same scale, including future larger detail.
    for axes in views.values():
        for faces, panel_width in zip(models, PANEL_WIDTHS):
            points = np.asarray([point for face in faces for point in face.vertices])
            x, y = points @ axes[1], points @ axes[2]
            scale = min(scale, (panel_width - 40) / (x.max() - x.min()),
                        (PANEL_HEIGHT - 25) / (y.max() - y.min()))
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text("model_sources", json.dumps({path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                                   for path in paths}, sort_keys=True))
    metadata.add_text("model_unit_scale", str(scale))
    output = {}
    for filename, axes in views.items():
        canvas = Image.new("RGB", IMAGE_SIZE, BACKGROUND)
        draw = ImageDraw.Draw(canvas)
        draw.text((38, 26), "Georgian alcoves — updated design", fill=(41, 47, 45), font=font(28))
        draw.text((38, 68), "Actual model geometry; same scale for every width",
                  fill=(81, 87, 83), font=font(21))
        x_offset = 30
        for width, faces, panel_width in zip((1, 2, 3), models, PANEL_WIDTHS):
            canvas.paste(render_panel(faces, panel_width, axes, scale), (x_offset, 150))
            label = f"{width}-wide"
            label_font = font(21)
            label_width = draw.textlength(label, font=label_font)
            draw.text((x_offset + (panel_width - label_width) / 2, 112), label,
                      fill=(64, 70, 66), font=label_font)
            x_offset += panel_width + 20
        stream = io.BytesIO()
        canvas.save(stream, format="PNG", pnginfo=metadata, compress_level=9)
        output[DESIGN / filename] = stream.getvalue()
    return output, [len(faces) for faces in models], scale


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output, counts, scale = render_boards()
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


if __name__ == "__main__":
    raise SystemExit(main())
