#!/usr/bin/env python3
"""Generate LabPBR companions for Synapheia's connected metal overlays."""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = (
    REPO_ROOT
    / "src/main/resources/assets/minecraft/textures/optifine/ctm/overlay"
)

# ERYDON's authored overlay companions treat the metal as a shallow inlay:
# transparent stone remains at height 255 and opaque metal sits at 250. Keep
# that established polarity and specular encoding, adding only the missing RGB
# normal detail. The CTM rules use Continuity's cutout-mipped overlay layer so
# transparent texels do not overwrite the stone's PBR response.
NORMAL_STRENGTH = 0.50
INLAY_DEPTH = 5
SMOOTHNESS = 255
METAL_VALUE = 255
METALS = {"bronze", "silver"}


def make_normal(albedo: Image.Image) -> Image.Image:
    mask = albedo.convert("RGBA").getchannel("A")
    # Keep the authored pixel edge intact. The surrounding ERYDON normal maps
    # are crisp, so pre-smoothing this mask makes the inlay visibly inconsistent.
    profile = mask
    width, image_height = profile.size
    profile_pixels = profile.load()
    output = Image.new("RGBA", profile.size)
    output_pixels = output.load()

    for y in range(image_height):
        previous_y = max(0, y - 1)
        next_y = min(image_height - 1, y + 1)
        for x in range(width):
            previous_x = max(0, x - 1)
            next_x = min(width - 1, x + 1)
            dx = (profile_pixels[next_x, y] - profile_pixels[previous_x, y]) / 255
            dy = (profile_pixels[x, next_y] - profile_pixels[x, previous_y]) / 255
            # The visible motif is lower than the surrounding stone, matching
            # the polarity of ERYDON's original overlay height companions.
            nx = dx * NORMAL_STRENGTH
            ny = dy * NORMAL_STRENGTH
            inverse_length = 1.0 / math.sqrt(nx * nx + ny * ny + 1.0)
            nx *= inverse_length
            ny *= inverse_length
            height = round(255 - profile_pixels[x, y] * INLAY_DEPTH / 255)
            output_pixels[x, y] = (
                round((nx * 0.5 + 0.5) * 255),
                round((ny * 0.5 + 0.5) * 255),
                255,  # LabPBR material AO: unoccluded.
                height,
            )
    return output


def make_specular(albedo: Image.Image, metal: str) -> Image.Image:
    # Keep both smoothness and metallic response at full strength through the
    # transparent padding. The cutout-mipped albedo discards those fragments,
    # while its mip levels no longer dilute thin metal details toward a
    # non-metallic green value.
    return Image.new(
        "RGBA", albedo.size, (SMOOTHNESS, METAL_VALUE, 0, 255)
    )


def albedo_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.png")
        if path.stem.isdigit() and path.parent.name in METALS
    )


def update_root(root: Path, check: bool) -> tuple[int, int]:
    if not root.is_dir():
        raise SystemExit(f"Overlay root does not exist: {root}")
    checked = 0
    changed = 0
    for albedo_path in albedo_files(root):
        albedo = Image.open(albedo_path).convert("RGBA")
        outputs = {
            albedo_path.with_name(f"{albedo_path.stem}_n.png"): make_normal(albedo),
            albedo_path.with_name(f"{albedo_path.stem}_s.png"): make_specular(
                albedo, albedo_path.parent.name
            ),
        }
        for output_path, expected in outputs.items():
            checked += 1
            matches = output_path.is_file() and Image.open(output_path).convert(
                "RGBA"
            ).tobytes() == expected.tobytes()
            if matches:
                continue
            changed += 1
            if not check:
                expected.save(output_path, format="PNG", optimize=True, compress_level=9)
    return checked, changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        action="append",
        type=Path,
        help="Overlay texture root; repeat to update mirrored pack sources.",
    )
    parser.add_argument(
        "--check", action="store_true", help="Verify without writing files."
    )
    arguments = parser.parse_args()
    roots = arguments.root or [DEFAULT_ROOT]

    mismatches = 0
    for root in roots:
        checked, changed = update_root(root.resolve(), arguments.check)
        mismatches += changed
        action = "mismatch(es)" if arguments.check else "file(s) updated"
        print(f"{root.resolve()}: {checked} companions checked, {changed} {action}.")
    return 1 if arguments.check and mismatches else 0


if __name__ == "__main__":
    raise SystemExit(main())
