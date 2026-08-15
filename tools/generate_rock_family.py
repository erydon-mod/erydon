#!/usr/bin/env python3
"""Generate and verify ERYDON's native 16x Rock texture family.

Rock deliberately mirrors the complete Hewn block family. The authored inputs
are 6x6 tile sheets; this tool splits them into Continuity repeat tiles and
creates the matching blockstate/model/tag/language/registration resources.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter


REPO_ROOT = Path(__file__).resolve().parents[1]
RESOURCES = REPO_ROOT / "src" / "main" / "resources"
JAVA_ROOT = REPO_ROOT / "src" / "main" / "java" / "com" / "oliver" / "erydon"

HEWN_TAG = RESOURCES / "data" / "erydon" / "tags" / "blocks" / "hewn.json"
ROCK_TAG = HEWN_TAG.with_name("rock.json")

JSON_MIRROR_ROOTS = (
    RESOURCES / "assets" / "erydon" / "blockstates",
    RESOURCES / "assets" / "erydon" / "models" / "block",
    RESOURCES / "assets" / "erydon" / "models" / "item",
)

EXPECTED_FORMS = {
    "alcove_georgian",
    "alcove_gothic",
    "arch_gothic",
    "arch_modern",
    "arch_romanesque",
    "block",
    "layer",
    "layer_vertical",
    "post",
    "slab",
    "slice_horizontal",
    "slice_vertical",
    "slope",
    "slope_shallow_lower",
    "slope_shallow_upper",
    "slope_steep_lower",
    "slope_steep_upper",
    "slope_vertical",
    "slope_vertical_shallow_broad",
    "slope_vertical_shallow_narrow",
    "stairs",
    "wall",
}

BASE_CTM_FORMS = (
    "block",
    "slab",
    "layer",
    "layer_vertical",
    "slope",
    "slope_shallow_lower",
    "slope_shallow_upper",
    "slope_steep_lower",
    "slope_steep_upper",
    "slope_vertical",
    "slope_vertical_shallow_narrow",
    "slope_vertical_shallow_broad",
    "arch_romanesque",
    "slice_vertical",
    "slice_horizontal",
    "post",
    "stairs",
    "wall",
    "alcove_georgian",
    "alcove_gothic",
)

ARCH_CTM_FORMS = ("arch_modern", "arch_gothic")

LANG_REPLACEMENTS = {
    "en_us.json": ("Hewn", "Rock"),
    "de_de.json": ("Behauen", "Fels"),
    "es_es.json": ("Labrado", "Roca"),
}

# Iris POM reads the normal map's alpha channel as height. Apply the approved
# edge-aware Rock smoothing only to that alpha channel, preserving every RGB
# normal value. These radii are tuned for the native 16x pixel grid, while the
# 190-255 output range matches the approved Collection treatment. Tiling before
# every filter keeps the 6x6 repeat sheet seamless at its outer edges.
ROCK_HEIGHT_BASE_BLUR_RADIUS = 0.75
ROCK_HEIGHT_EDGE_BLUR_RADIUS = 0.375
ROCK_HEIGHT_EDGE_DETECTION_BLUR_RADIUS = 0.5625
ROCK_HEIGHT_EDGE_WINDOW = 3
ROCK_HEIGHT_EDGE_LOW = 10
ROCK_HEIGHT_EDGE_HIGH = 14
ROCK_HEIGHT_TARGET_MINIMUM = 190
ROCK_HEIGHT_TARGET_MAXIMUM = 255


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value: dict, *, indent: int = 2) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=indent) + "\n",
        encoding="utf-8",
    )


def hewn_ids_and_materials() -> tuple[list[str], list[str]]:
    values = load_json(HEWN_TAG)["values"]
    hewn_ids = [value for value in values if isinstance(value, str) and "_hewn_" in value]
    if len(hewn_ids) != 594:
        raise RuntimeError(f"Expected 594 Hewn IDs, found {len(hewn_ids)}")

    materials: list[str] = []
    forms_by_material: dict[str, set[str]] = {}
    pattern = re.compile(r"^erydon:([a-z0-9_]+)_hewn_(.+)$")
    for block_id in hewn_ids:
        match = pattern.fullmatch(block_id)
        if match is None:
            raise RuntimeError(f"Unexpected Hewn ID: {block_id}")
        material, form = match.groups()
        if material not in forms_by_material:
            materials.append(material)
            forms_by_material[material] = set()
        forms_by_material[material].add(form)

    if len(materials) != 27:
        raise RuntimeError(f"Expected 27 Hewn materials, found {len(materials)}")
    for material, forms in forms_by_material.items():
        if forms != EXPECTED_FORMS:
            missing = sorted(EXPECTED_FORMS - forms)
            extra = sorted(forms - EXPECTED_FORMS)
            raise RuntimeError(f"Unexpected Hewn forms for {material}: missing={missing}, extra={extra}")
    return hewn_ids, materials


def rock_path_from_hewn(path: Path) -> Path:
    relative = path.relative_to(REPO_ROOT)
    parts = [part.replace("_hewn_", "_rock_") for part in relative.parts]
    return REPO_ROOT.joinpath(*parts)


def rock_bytes_from_hewn(data: bytes) -> bytes:
    return data.replace(b"_hewn_", b"_rock_")


def wrap_filter(channel: Image.Image, image_filter: ImageFilter.Filter) -> Image.Image:
    width, height = channel.size
    tiled = Image.new("L", (width * 3, height * 3))
    for tile_y in range(3):
        for tile_x in range(3):
            tiled.paste(channel, (tile_x * width, tile_y * height))
    return tiled.filter(image_filter).crop(
        (width, height, width * 2, height * 2)
    )


def wrap_gaussian_blur(channel: Image.Image, radius: float) -> Image.Image:
    return wrap_filter(channel, ImageFilter.GaussianBlur(radius))


def scale_height_range(source: Image.Image, filtered: Image.Image) -> Image.Image:
    source_minimum, source_maximum = source.getextrema()
    filtered_minimum, filtered_maximum = filtered.getextrema()

    if source_minimum == source_maximum or filtered_minimum == filtered_maximum:
        return source.copy()

    target_range = ROCK_HEIGHT_TARGET_MAXIMUM - ROCK_HEIGHT_TARGET_MINIMUM
    scale = target_range / (filtered_maximum - filtered_minimum)
    height_lut = [
        max(
            0,
            min(
                255,
                round(
                    ROCK_HEIGHT_TARGET_MINIMUM
                    + (value - filtered_minimum) * scale
                ),
            ),
        )
        for value in range(256)
    ]
    return filtered.point(height_lut)


def smooth_rock_height(normal_sheet: Image.Image) -> Image.Image:
    red, green, blue, height = normal_sheet.convert("RGBA").split()
    base = wrap_gaussian_blur(height, ROCK_HEIGHT_BASE_BLUR_RADIUS)
    edge_detail = wrap_gaussian_blur(height, ROCK_HEIGHT_EDGE_BLUR_RADIUS)
    edge_reference = wrap_gaussian_blur(
        height, ROCK_HEIGHT_EDGE_DETECTION_BLUR_RADIUS
    )
    local_maximum = wrap_filter(
        edge_reference, ImageFilter.MaxFilter(ROCK_HEIGHT_EDGE_WINDOW)
    )
    local_minimum = wrap_filter(
        edge_reference, ImageFilter.MinFilter(ROCK_HEIGHT_EDGE_WINDOW)
    )
    local_range = ImageChops.subtract(local_maximum, local_minimum)
    edge_mask_lut = [
        max(
            0,
            min(
                255,
                round(
                    (value - ROCK_HEIGHT_EDGE_LOW)
                    * 255
                    / (ROCK_HEIGHT_EDGE_HIGH - ROCK_HEIGHT_EDGE_LOW)
                ),
            ),
        )
        for value in range(256)
    ]
    edge_mask = local_range.point(edge_mask_lut)
    filtered_height = Image.composite(edge_detail, base, edge_mask)
    return Image.merge(
        "RGBA", (red, green, blue, scale_height_range(height, filtered_height))
    )


def prepare_sheet(sheet_suffix: str, sheet: Image.Image) -> Image.Image:
    if sheet_suffix == "_n":
        return smooth_rock_height(sheet)
    return sheet.copy()


def clone_json_assets() -> None:
    expected_counts = (594, 4077, 594)
    for root, expected in zip(JSON_MIRROR_ROOTS, expected_counts, strict=True):
        sources = sorted(path for path in root.rglob("*.json") if "_hewn_" in path.name)
        if len(sources) != expected:
            raise RuntimeError(f"Expected {expected} Hewn JSON files under {root}, found {len(sources)}")
        for source in sources:
            target = rock_path_from_hewn(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(rock_bytes_from_hewn(source.read_bytes()))


def split_texture_sheets(source_root: Path, materials: list[str]) -> None:
    ctm_root = RESOURCES / "assets" / "minecraft" / "textures" / "optifine" / "ctm"
    block_texture_root = RESOURCES / "assets" / "erydon" / "textures" / "block"
    block_texture_root.mkdir(parents=True, exist_ok=True)

    sheet_variants = (
        ("", "", "RGB"),
        ("_n", "_n", "RGBA"),
        ("_s", "_s", "RGB"),
    )
    for material in materials:
        target_dir = ctm_root / f"{material}_rock"
        target_dir.mkdir(parents=True, exist_ok=True)
        for sheet_suffix, tile_suffix, expected_mode in sheet_variants:
            source = source_root / f"{material}_rock{sheet_suffix}.png"
            if not source.is_file():
                raise RuntimeError(f"Missing authored Rock sheet: {source}")
            with Image.open(source) as authored_sheet:
                authored_sheet.load()
                if authored_sheet.size != (96, 96):
                    raise RuntimeError(f"Expected 96x96 native sheet, found {authored_sheet.size}: {source}")
                if authored_sheet.mode != expected_mode:
                    raise RuntimeError(f"Expected {expected_mode} sheet, found {authored_sheet.mode}: {source}")
                sheet = prepare_sheet(sheet_suffix, authored_sheet)
                for tile_index in range(36):
                    tile_x = (tile_index % 6) * 16
                    tile_y = (tile_index // 6) * 16
                    tile = sheet.crop((tile_x, tile_y, tile_x + 16, tile_y + 16))
                    tile.save(target_dir / f"{tile_index}{tile_suffix}.png")

            tile_zero = target_dir / f"0{tile_suffix}.png"
            shutil.copyfile(tile_zero, block_texture_root / f"{material}_rock_block{tile_suffix}.png")


def validate_tiles_against_source(source_root: Path, materials: list[str]) -> None:
    texture_root = (
        RESOURCES / "assets" / "minecraft" / "textures" / "optifine" / "ctm"
    )
    sheet_variants = (
        ("", ""),
        ("_n", "_n"),
        ("_s", "_s"),
    )
    for material in materials:
        for sheet_suffix, tile_suffix in sheet_variants:
            source = source_root / f"{material}_rock{sheet_suffix}.png"
            with Image.open(source) as authored_sheet:
                authored_sheet.load()
                sheet = prepare_sheet(sheet_suffix, authored_sheet)
                for tile_index in range(36):
                    tile_x = (tile_index % 6) * 16
                    tile_y = (tile_index // 6) * 16
                    expected = sheet.crop(
                        (tile_x, tile_y, tile_x + 16, tile_y + 16)
                    )
                    target = (
                        texture_root
                        / f"{material}_rock"
                        / f"{tile_index}{tile_suffix}.png"
                    )
                    with Image.open(target) as actual:
                        actual.load()
                        if (
                            actual.mode != expected.mode
                            or actual.size != expected.size
                            or actual.tobytes() != expected.tobytes()
                        ):
                            raise RuntimeError(
                                "Native Rock tile does not match its prepared "
                                f"sheet region: {target}"
                            )


def ctm_properties(material: str, forms: tuple[str, ...]) -> str:
    tiles = " ".join(f"textures/optifine/ctm/{material}_rock/{index}" for index in range(36))
    matches = [f"erydon:{material}_rock_{form}" for form in forms]
    continuation = " \\" + "\n  "
    match_lines = continuation.join(matches)
    return (
        "method=repeat\n"
        "width=6\n"
        "height=6\n"
        f"tiles={tiles}\n"
        f"matchBlocks={match_lines}\n"
        "faces=all\n"
        "connect=block\n"
        "innerSeams=true\n"
        "priority=10\n"
    )


def write_ctm_properties(materials: list[str]) -> None:
    root = RESOURCES / "assets" / "minecraft" / "optifine" / "ctm"
    for material in materials:
        target = root / f"{material}_rock"
        target.mkdir(parents=True, exist_ok=True)
        (target / f"a_{material}_rock_base.properties").write_text(
            ctm_properties(material, BASE_CTM_FORMS), encoding="utf-8", newline="\n"
        )
        (target / f"a_{material}_rock_arches.properties").write_text(
            ctm_properties(material, ARCH_CTM_FORMS), encoding="utf-8", newline="\n"
        )


def update_tags() -> None:
    hewn_tag = load_json(HEWN_TAG)
    rock_tag = {
        **hewn_tag,
        "values": [
            value.replace("_hewn_", "_rock_") if isinstance(value, str) else value
            for value in hewn_tag["values"]
        ],
    }
    write_json(ROCK_TAG, rock_tag)

    for path in sorted((RESOURCES / "data").rglob("*.json")):
        if "tags" not in path.parts or "blocks" not in path.parts or path in {HEWN_TAG, ROCK_TAG}:
            continue
        document = load_json(path)
        values = document.get("values")
        if not isinstance(values, list):
            continue
        hewn_values = [value for value in values if isinstance(value, str) and "_hewn_" in value]
        if not hewn_values:
            continue
        generated_rock_values = {value.replace("_hewn_", "_rock_") for value in hewn_values}
        without_generated = [value for value in values if value not in generated_rock_values]
        expanded: list[object] = []
        for value in without_generated:
            expanded.append(value)
            if isinstance(value, str) and "_hewn_" in value:
                expanded.append(value.replace("_hewn_", "_rock_"))
        document["values"] = expanded
        write_json(path, document)


def update_languages() -> None:
    language_root = RESOURCES / "assets" / "erydon" / "lang"
    for filename, (hewn_word, rock_word) in LANG_REPLACEMENTS.items():
        path = language_root / filename
        document = load_json(path)
        hewn_keys = [key for key in document if "_hewn_" in key]
        if len(hewn_keys) != 594:
            raise RuntimeError(f"Expected 594 Hewn language keys in {filename}, found {len(hewn_keys)}")

        generated_keys = {key.replace("_hewn_", "_rock_") for key in hewn_keys}
        generated_keys.add("option.erydon.gallery.variant.rock")
        expanded: dict[str, str] = {}
        for key, value in document.items():
            if key in generated_keys:
                continue
            expanded[key] = value
            if "_hewn_" in key:
                if hewn_word not in value:
                    raise RuntimeError(f"Expected {hewn_word!r} in {filename} value for {key}")
                expanded[key.replace("_hewn_", "_rock_")] = value.replace(hewn_word, rock_word)
            if key == "option.erydon.gallery.variant.hewn":
                expanded["option.erydon.gallery.variant.rock"] = rock_word
        write_json(path, expanded, indent=4)


def update_mod_blocks() -> None:
    path = JAVA_ROOT / "ModBlocks.java"
    lines = path.read_bytes().splitlines(keepends=True)

    rock_declaration = re.compile(rb"^    public static Block [A-Z0-9_]*ROCK[A-Z0-9_]*;\r?\n?$")
    rock_registration = re.compile(
        rb'^        [A-Z0-9_]*ROCK[A-Z0-9_]* = registerBlock\("[a-z0-9_]*_rock_[a-z0-9_]*",\r?\n?$'
    )
    dynamic_rock_markers = (b'_rock_arch_modern"', b'_rock_arch_gothic"', b'_rock_alcove_"')

    cleaned: list[bytes] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if rock_declaration.match(line):
            index += 1
            continue
        if rock_registration.match(line):
            if index + 1 >= len(lines) or not lines[index + 1].lstrip().startswith(b"new "):
                raise RuntimeError("Malformed Rock registration in ModBlocks.java")
            index += 2
            continue
        if any(marker in line for marker in dynamic_rock_markers):
            index += 1
            continue
        cleaned.append(line)
        index += 1

    hewn_declaration = re.compile(rb"^    public static Block [A-Z0-9_]*HEWN[A-Z0-9_]*;\r?\n?$")
    hewn_registration = re.compile(
        rb'^        [A-Z0-9_]*HEWN[A-Z0-9_]* = registerBlock\("[a-z0-9_]*_hewn_[a-z0-9_]*",\r?\n?$'
    )
    dynamic_hewn_markers = (b'_hewn_arch_modern"', b'_hewn_arch_gothic"', b'_hewn_alcove_"')

    expanded: list[bytes] = []
    index = 0
    while index < len(cleaned):
        line = cleaned[index]
        if hewn_registration.match(line):
            if index + 1 >= len(cleaned):
                raise RuntimeError("Truncated Hewn registration in ModBlocks.java")
            continuation = cleaned[index + 1]
            if not continuation.lstrip().startswith(b"new ") or b"));" not in continuation:
                raise RuntimeError("Unexpected Hewn registration shape in ModBlocks.java")
            expanded.extend((line, continuation))
            expanded.extend(
                (
                    line.replace(b"HEWN", b"ROCK").replace(b"_hewn_", b"_rock_"),
                    continuation.replace(b"HEWN", b"ROCK").replace(b"_hewn_", b"_rock_"),
                )
            )
            index += 2
            continue

        expanded.append(line)
        if hewn_declaration.match(line):
            expanded.append(line.replace(b"HEWN", b"ROCK"))
        elif any(marker in line for marker in dynamic_hewn_markers):
            expanded.append(line.replace(b"_hewn_", b"_rock_").replace(b'material + "_block"', b'material + "_rock_block"'))
        index += 1

    output = b"".join(expanded)
    output = output.replace(
        b'toSlitherId(slabId, "vertical_slice")', b'toSlitherId(slabId, "slice_vertical")'
    ).replace(
        b'toSlitherId(slabId, "horizontal_slice")', b'toSlitherId(slabId, "slice_horizontal")'
    )

    legacy_slither_helper = re.compile(
        rb'    private static String toSlitherId\(String slabId, String variant\) \{\r?\n'
        rb'        String resourceSlabId = ErydonIdMigration\.legacyResourcePath\(slabId\);\r?\n'
        rb'        if \(resourceSlabId\.endsWith\("_slab_aged"\)\) \{\r?\n'
        rb'            return resourceSlabId\.substring\(0, resourceSlabId\.length\(\) - 10\)\r?\n'
        rb'                    \+ "_" \+ variant \+ "_aged";\r?\n'
        rb'        \}\r?\n'
        rb'        return resourceSlabId\.substring\(0, resourceSlabId\.length\(\) - 5\) \+ "_" \+ variant;\r?\n'
        rb'    \}'
    )
    canonical_slither_helper = (
        b'    private static String toSlitherId(String slabId, String variant) {\n'
        b'        return ErydonIdMigration.canonicalSlitherPath(slabId, variant);\n'
        b'    }'
    )
    output = legacy_slither_helper.sub(canonical_slither_helper, output)

    if len(re.findall(rb"(?m)^    public static Block [A-Z0-9_]*ROCK[A-Z0-9_]*;", output)) != 405:
        raise RuntimeError("Rock declaration generation did not produce 405 fields")
    if len(re.findall(rb'(?m)^        [A-Z0-9_]*ROCK[A-Z0-9_]* = registerBlock\("[a-z0-9_]*_rock_', output)) != 405:
        raise RuntimeError("Rock registration generation did not produce 405 direct blocks")
    path.write_bytes(output)


def generate(source_root: Path) -> None:
    _, materials = hewn_ids_and_materials()
    if not source_root.is_dir():
        raise RuntimeError(f"Rock source directory does not exist: {source_root}")
    clone_json_assets()
    split_texture_sheets(source_root, materials)
    validate_tiles_against_source(source_root, materials)
    write_ctm_properties(materials)
    update_tags()
    update_languages()
    update_mod_blocks()


def validate_outputs() -> None:
    hewn_ids, materials = hewn_ids_and_materials()
    expected_rock_ids = [block_id.replace("_hewn_", "_rock_") for block_id in hewn_ids]
    rock_document = load_json(ROCK_TAG)
    if rock_document.get("values") != expected_rock_ids:
        raise RuntimeError("rock.json is not an exact Hewn-family mirror")

    expected_counts = (594, 4077, 594)
    for root, expected in zip(JSON_MIRROR_ROOTS, expected_counts, strict=True):
        sources = sorted(path for path in root.rglob("*.json") if "_hewn_" in path.name)
        targets = sorted(path for path in root.rglob("*.json") if "_rock_" in path.name)
        if len(targets) != expected:
            raise RuntimeError(f"Expected {expected} Rock JSON files under {root}, found {len(targets)}")
        for source in sources:
            target = rock_path_from_hewn(source)
            if not target.is_file() or target.read_bytes() != rock_bytes_from_hewn(source.read_bytes()):
                raise RuntimeError(f"Rock mirror mismatch: {target}")

    ctm_texture_root = RESOURCES / "assets" / "minecraft" / "textures" / "optifine" / "ctm"
    ctm_property_root = RESOURCES / "assets" / "minecraft" / "optifine" / "ctm"
    block_texture_root = RESOURCES / "assets" / "erydon" / "textures" / "block"
    for material in materials:
        tile_dir = ctm_texture_root / f"{material}_rock"
        pngs = sorted(tile_dir.glob("*.png"))
        if len(pngs) != 108:
            raise RuntimeError(f"Expected 108 native CTM tiles for {material}, found {len(pngs)}")
        for suffix, mode in (("", "RGB"), ("_n", "RGBA"), ("_s", "RGB")):
            height_extrema: list[tuple[int, int]] = []
            for tile_index in range(36):
                tile_path = tile_dir / f"{tile_index}{suffix}.png"
                with Image.open(tile_path) as tile:
                    if tile.size != (16, 16) or tile.mode != mode:
                        raise RuntimeError(f"Unexpected native tile format: {tile_path} {tile.size} {tile.mode}")
                    if suffix == "_n":
                        height_extrema.append(tile.getchannel("A").getextrema())
            if suffix == "_n":
                combined_extrema = (
                    min(minimum for minimum, _ in height_extrema),
                    max(maximum for _, maximum in height_extrema),
                )
                if combined_extrema != (
                    ROCK_HEIGHT_TARGET_MINIMUM,
                    ROCK_HEIGHT_TARGET_MAXIMUM,
                ):
                    raise RuntimeError(
                        f"Unexpected native Rock height range for {material}: "
                        f"{combined_extrema}"
                    )
            root_texture = block_texture_root / f"{material}_rock_block{suffix}.png"
            if root_texture.read_bytes() != (tile_dir / f"0{suffix}.png").read_bytes():
                raise RuntimeError(f"Root Rock texture is not CTM tile 0: {root_texture}")

        property_dir = ctm_property_root / f"{material}_rock"
        expected_properties = {
            f"a_{material}_rock_base.properties": ctm_properties(material, BASE_CTM_FORMS),
            f"a_{material}_rock_arches.properties": ctm_properties(material, ARCH_CTM_FORMS),
        }
        actual_names = {path.name for path in property_dir.glob("*.properties")}
        if actual_names != set(expected_properties):
            raise RuntimeError(f"Unexpected CTM property files for {material}: {sorted(actual_names)}")
        for filename, expected_text in expected_properties.items():
            data = (property_dir / filename).read_bytes()
            if data.startswith(b"\xef\xbb\xbf") or data.decode("utf-8") != expected_text:
                raise RuntimeError(f"Invalid CTM properties: {property_dir / filename}")

    for path in sorted((RESOURCES / "data").rglob("*.json")):
        if "tags" not in path.parts or "blocks" not in path.parts or path in {HEWN_TAG, ROCK_TAG}:
            continue
        values = load_json(path).get("values", [])
        for value in values:
            if isinstance(value, str) and "_hewn_" in value:
                counterpart = value.replace("_hewn_", "_rock_")
                if counterpart not in values:
                    raise RuntimeError(f"Missing Rock tag counterpart in {path}: {counterpart}")

    for filename, (hewn_word, rock_word) in LANG_REPLACEMENTS.items():
        document = load_json(RESOURCES / "assets" / "erydon" / "lang" / filename)
        if document.get("option.erydon.gallery.variant.rock") != rock_word:
            raise RuntimeError(f"Missing Rock gallery translation in {filename}")
        rock_keys = [key for key in document if "_rock_" in key]
        if len(rock_keys) != 594:
            raise RuntimeError(f"Expected 594 Rock language keys in {filename}, found {len(rock_keys)}")
        for key, value in document.items():
            if "_hewn_" not in key:
                continue
            rock_key = key.replace("_hewn_", "_rock_")
            if document.get(rock_key) != value.replace(hewn_word, rock_word):
                raise RuntimeError(f"Rock language mismatch in {filename}: {rock_key}")

    mod_blocks = (JAVA_ROOT / "ModBlocks.java").read_text(encoding="utf-8")
    if len(re.findall(r"^    public static Block [A-Z0-9_]*ROCK[A-Z0-9_]*;", mod_blocks, re.MULTILINE)) != 405:
        raise RuntimeError("Expected 405 direct Rock fields")
    if len(re.findall(r'^        [A-Z0-9_]*ROCK[A-Z0-9_]* = registerBlock\("[a-z0-9_]*_rock_', mod_blocks, re.MULTILINE)) != 405:
        raise RuntimeError("Expected 405 direct Rock registrations")
    required_dynamic = (
        'registerModernArch(material + "_rock_arch_modern", material + "_rock_block");',
        'registerGothicArch(material + "_rock_arch_gothic", material + "_rock_block");',
        'registerAlcove(material + "_rock_alcove_" + style, material + "_rock_block", maxClusterWidth);',
        'toSlitherId(slabId, "slice_vertical")',
        'toSlitherId(slabId, "slice_horizontal")',
        'return ErydonIdMigration.canonicalSlitherPath(slabId, variant);',
    )
    for snippet in required_dynamic:
        if snippet not in mod_blocks:
            raise RuntimeError(f"Missing Rock registration hook: {snippet}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Directory containing the 96x96 native Rock sheets")
    parser.add_argument("--check", action="store_true", help="Verify committed Rock outputs without rewriting them")
    args = parser.parse_args()

    if args.check:
        validate_outputs()
        print("Rock family outputs verified: 27 materials x 22 forms, with native CTM/PBR tiles.")
        return 0
    if args.source is None:
        parser.error("--source is required unless --check is used")
    generate(args.source.resolve())
    validate_outputs()
    print("Generated and verified the native ERYDON Rock family: 27 materials x 22 forms.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
