#!/usr/bin/env python3
"""Generate and verify Hewn multiface layers and their Rock mirrors."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import generate_rock_family


REPO_ROOT = Path(__file__).resolve().parents[1]
RESOURCES = REPO_ROOT / "src" / "main" / "resources"
JAVA_ROOT = REPO_ROOT / "src" / "main" / "java" / "com" / "oliver" / "erydon"

BLOCKSTATES = RESOURCES / "assets" / "erydon" / "blockstates"
BLOCK_MODELS = RESOURCES / "assets" / "erydon" / "models" / "block"
ITEM_MODELS = RESOURCES / "assets" / "erydon" / "models" / "item"
MULTIFACE_MODELS = BLOCK_MODELS / "layer" / "layer_multiface"
LANG_ROOT = RESOURCES / "assets" / "erydon" / "lang"
BLOCK_TAG_ROOT = RESOURCES / "data" / "erydon" / "tags" / "blocks"
HEWN_TAG = BLOCK_TAG_ROOT / "hewn.json"
ROCK_TAG = BLOCK_TAG_ROOT / "rock.json"
MULTIFACE_TAG = BLOCK_TAG_ROOT / "layer_multiface.json"
MOD_BLOCKS = JAVA_ROOT / "ModBlocks.java"
CTM_ROOT = RESOURCES / "assets" / "minecraft" / "optifine" / "ctm"

LANGUAGES = ("en_us.json", "de_de.json", "es_es.json")
EXPECTED_MATERIALS = 27
EXPECTED_MODELS_PER_BLOCK = 25


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_bytes_if_changed(path: Path, data: bytes) -> None:
    if not path.is_file() or path.read_bytes() != data:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def materials() -> list[str]:
    values = load_json(HEWN_TAG).get("values", [])
    found: list[str] = []
    seen: set[str] = set()
    pattern = re.compile(r"^erydon:([a-z0-9_]+)_hewn_")
    for value in values:
        if not isinstance(value, str):
            continue
        match = pattern.match(value)
        if match is not None and match.group(1) not in seen:
            seen.add(match.group(1))
            found.append(match.group(1))
    if len(found) != EXPECTED_MATERIALS:
        raise RuntimeError(f"Expected {EXPECTED_MATERIALS} Hewn materials, found {len(found)}")
    return found


def translated_multiface_bytes(source: Path, material: str, variant: str) -> bytes:
    source_token = f"{material}_ashlar_layer_multiface".encode()
    target_token = f"{material}_{variant}_layer_multiface".encode()
    texture_source = f"{material}_ashlar_block".encode()
    texture_target = f"{material}_{variant}_block".encode()
    data = source.read_bytes().replace(source_token, target_token).replace(texture_source, texture_target)
    if source_token in data or texture_source in data:
        raise RuntimeError(f"Untranslated Ashlar token remains for {source}")
    return data


def generate_assets(material_names: list[str]) -> None:
    for material in material_names:
        source_blockstate = BLOCKSTATES / f"{material}_ashlar_layer_multiface.json"
        source_item = ITEM_MODELS / f"{material}_ashlar_layer_multiface.json"
        sources = sorted(MULTIFACE_MODELS.glob(f"{material}_ashlar_layer_multiface_*.json"))
        if len(sources) != EXPECTED_MODELS_PER_BLOCK:
            raise RuntimeError(
                f"Expected {EXPECTED_MODELS_PER_BLOCK} Ashlar multiface models for {material}, "
                f"found {len(sources)}"
            )

        hewn_blockstate = BLOCKSTATES / f"{material}_hewn_layer_multiface.json"
        hewn_item = ITEM_MODELS / f"{material}_hewn_layer_multiface.json"
        write_bytes_if_changed(
            hewn_blockstate,
            translated_multiface_bytes(source_blockstate, material, "hewn"),
        )
        write_bytes_if_changed(
            hewn_item,
            translated_multiface_bytes(source_item, material, "hewn"),
        )

        for source in sources:
            suffix = source.name.removeprefix(f"{material}_ashlar_layer_multiface_")
            target = MULTIFACE_MODELS / f"{material}_hewn_layer_multiface_{suffix}"
            write_bytes_if_changed(target, translated_multiface_bytes(source, material, "hewn"))

        for hewn_path in (hewn_blockstate, hewn_item):
            rock_path = hewn_path.with_name(hewn_path.name.replace("_hewn_", "_rock_"))
            write_bytes_if_changed(rock_path, generate_rock_family.rock_bytes_from_hewn(hewn_path.read_bytes()))
        for hewn_path in sorted(MULTIFACE_MODELS.glob(f"{material}_hewn_layer_multiface_*.json")):
            rock_path = hewn_path.with_name(hewn_path.name.replace("_hewn_", "_rock_"))
            write_bytes_if_changed(rock_path, generate_rock_family.rock_bytes_from_hewn(hewn_path.read_bytes()))


def generated_ids(material: str) -> tuple[str, str]:
    return (
        f"erydon:{material}_hewn_layer_multiface",
        f"erydon:{material}_rock_layer_multiface",
    )


def update_tags(material_names: list[str]) -> None:
    all_generated = {block_id for material in material_names for block_id in generated_ids(material)}
    generated_markers = {f'"{block_id}"'.encode() for block_id in all_generated}

    def rewrite(path: Path, insert_after: dict[bytes, tuple[str, ...]]) -> int:
        lines = path.read_bytes().splitlines(keepends=True)
        filtered = [
            line for line in lines if not any(marker in line for marker in generated_markers)
        ]
        expanded: list[bytes] = []
        inserted = 0
        for line in filtered:
            expanded.append(line)
            for anchor, block_ids in insert_after.items():
                if anchor not in line:
                    continue
                newline = b"\n"
                indent = line[: line.index(b'"')]
                expanded.extend(
                    indent + f'"{block_id}",'.encode() + newline
                    for block_id in block_ids
                )
                inserted += len(block_ids)
                break
        output = b"".join(expanded)
        if output != path.read_bytes():
            path.write_bytes(output)
        return inserted

    hewn_anchors = {
        f'"erydon:{material}_hewn_layer"'.encode(): (generated_ids(material)[0],)
        for material in material_names
    }
    rock_anchors = {
        f'"erydon:{material}_rock_layer"'.encode(): (generated_ids(material)[1],)
        for material in material_names
    }
    multiface_anchors = {
        f'"erydon:{material}_layer_multiface"'.encode(): generated_ids(material)
        for material in material_names
    }
    paired_anchors = {
        f'"erydon:{material}_rock_layer"'.encode(): generated_ids(material)
        for material in material_names
    }

    if rewrite(HEWN_TAG, hewn_anchors) != EXPECTED_MATERIALS:
        raise RuntimeError("Could not insert every Hewn multiface block tag value")
    if rewrite(ROCK_TAG, rock_anchors) != EXPECTED_MATERIALS:
        raise RuntimeError("Could not insert every Rock multiface block tag value")
    if rewrite(MULTIFACE_TAG, multiface_anchors) != EXPECTED_MATERIALS * 2:
        raise RuntimeError("Could not insert every multiface-family block tag value")

    for path in sorted((RESOURCES / "data").rglob("*.json")):
        if "tags" not in path.parts or "blocks" not in path.parts or path in {
            HEWN_TAG,
            ROCK_TAG,
            MULTIFACE_TAG,
        }:
            continue
        values = load_json(path).get("values")
        if not isinstance(values, list):
            continue
        expected_pairs = sum(
            f"erydon:{material}_rock_layer" in values for material in material_names
        )
        if expected_pairs:
            inserted = rewrite(path, paired_anchors)
            if inserted != expected_pairs * 2:
                raise RuntimeError(f"Could not insert all Hewn/Rock multiface tag values in {path}")


def localized_multiface_value(document: dict, material: str, variant: str) -> str:
    prefix = "block.erydon."
    generic_layer = document[f"{prefix}{material}_layer"]
    generic_multiface = document[f"{prefix}{material}_layer_multiface"]
    variant_layer = document[f"{prefix}{material}_{variant}_layer"]
    if " " not in generic_layer or " " not in generic_multiface:
        raise RuntimeError(f"Cannot derive localized layer wording for {material}")
    layer_suffix = generic_layer.split(" ", 1)[1]
    multiface_suffix = generic_multiface.split(" ", 1)[1]
    if not variant_layer.endswith(layer_suffix):
        raise RuntimeError(f"Unexpected localized {variant} layer wording for {material}: {variant_layer}")
    return variant_layer[: -len(layer_suffix)] + multiface_suffix


def update_languages(material_names: list[str]) -> None:
    generated_keys = {
        f"block.erydon.{material}_{variant}_layer_multiface"
        for material in material_names
        for variant in ("hewn", "rock")
    }
    for filename in LANGUAGES:
        path = LANG_ROOT / filename
        document = load_json(path)
        lines = path.read_bytes().splitlines(keepends=True)
        filtered = [
            line
            for line in lines
            if not any(f'"{key}":'.encode() in line for key in generated_keys)
        ]
        expanded: list[bytes] = []
        inserted = 0
        for line in filtered:
            expanded.append(line)
            for material in material_names:
                anchor = f'"block.erydon.{material}_rock_layer":'.encode()
                if anchor not in line:
                    continue
                newline = b"\n"
                indent = line[: line.index(b'"')]
                for variant in ("hewn", "rock"):
                    key = f"block.erydon.{material}_{variant}_layer_multiface"
                    value = localized_multiface_value(document, material, variant)
                    expanded.append(
                        indent
                        + json.dumps(key, ensure_ascii=False).encode("utf-8")
                        + b": "
                        + json.dumps(value, ensure_ascii=False).encode("utf-8")
                        + b","
                        + newline
                    )
                inserted += 1
                break
        if inserted != EXPECTED_MATERIALS:
            raise RuntimeError(f"Inserted {inserted} multiface language pairs in {filename}")
        path.write_bytes(b"".join(expanded))


def update_mod_blocks(material_names: list[str]) -> None:
    data = MOD_BLOCKS.read_bytes()
    declaration_markers = [
        f"public static Block {material.upper()}_{variant.upper()}_LAYER_MULTIFACE;".encode()
        for material in material_names
        for variant in ("hewn", "rock")
    ]
    registration_markers = [
        f'{material.upper()}_{variant.upper()}_LAYER_MULTIFACE = registerBlock("{material}_{variant}_layer_multiface",'.encode()
        for material in material_names
        for variant in ("hewn", "rock")
    ]
    present_declarations = sum(marker in data for marker in declaration_markers)
    present_registrations = sum(marker in data for marker in registration_markers)
    if present_declarations == len(declaration_markers) and present_registrations == len(registration_markers):
        return
    if present_declarations or present_registrations:
        raise RuntimeError("ModBlocks.java contains a partial Hewn/Rock multiface registration set")

    lines = data.splitlines(keepends=True)
    expanded: list[bytes] = []
    declaration_anchors = 0
    registration_anchors = 0
    index = 0
    while index < len(lines):
        line = lines[index]
        expanded.append(line)
        handled_registration = False
        for material in material_names:
            upper = material.upper()
            declaration_anchor = f"    public static Block {upper}_ROCK_LAYER;".encode()
            if line.rstrip(b"\r\n") == declaration_anchor:
                newline = b"\n"
                expanded.extend(
                    (
                        newline,
                        f"    public static Block {upper}_HEWN_LAYER_MULTIFACE;".encode() + newline,
                        f"    public static Block {upper}_ROCK_LAYER_MULTIFACE;".encode() + newline,
                    )
                )
                declaration_anchors += 1
                break

            registration_anchor = (
                f'        {upper}_ROCK_LAYER = registerBlock("{material}_rock_layer",'.encode()
            )
            if line.rstrip(b"\r\n") != registration_anchor:
                continue
            if index + 1 >= len(lines) or b"new LayerBlock(" not in lines[index + 1]:
                raise RuntimeError(f"Malformed Rock layer registration for {material}")
            continuation = lines[index + 1]
            expanded.append(continuation)
            newline = b"\n"
            expanded.extend(
                (
                    newline,
                    f'        {upper}_HEWN_LAYER_MULTIFACE = registerBlock("{material}_hewn_layer_multiface",'.encode() + newline,
                    f"            new LayerMultifaceBlock(AbstractBlock.Settings.copy({upper}_BLOCK).nonOpaque()));".encode() + newline,
                    f'        {upper}_ROCK_LAYER_MULTIFACE = registerBlock("{material}_rock_layer_multiface",'.encode() + newline,
                    f"            new LayerMultifaceBlock(AbstractBlock.Settings.copy({upper}_BLOCK).nonOpaque()));".encode() + newline,
                )
            )
            registration_anchors += 1
            index += 1
            handled_registration = True
            break
        index += 1
        if handled_registration:
            continue

    if declaration_anchors != EXPECTED_MATERIALS or registration_anchors != EXPECTED_MATERIALS:
        raise RuntimeError(
            f"Expected {EXPECTED_MATERIALS} ModBlocks anchors, found "
            f"declarations={declaration_anchors}, registrations={registration_anchors}"
        )
    MOD_BLOCKS.write_bytes(b"".join(expanded))


def ctm_base_path(material: str, variant: str) -> Path:
    block_id = f"erydon:{material}_{variant}_layer".encode()
    matches = [
        path
        for path in sorted((CTM_ROOT / f"{material}_{variant}").glob("*.properties"))
        if block_id in path.read_bytes()
    ]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one {variant} base CTM property file for {material}, found {matches}")
    return matches[0]


def add_ctm_match(path: Path, existing_id: str, new_id: str) -> None:
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise RuntimeError(f"CTM properties must not contain a UTF-8 BOM: {path}")
    if new_id.encode() in data:
        return
    pattern = re.compile(
        rb"(?m)^(?P<indent>[ \t]*)"
        + re.escape(existing_id.encode())
        + rb" \\(?P<newline>\r?\n)"
    )
    match = pattern.search(data)
    if match is None:
        raise RuntimeError(f"Missing CTM insertion anchor {existing_id} in {path}")
    replacement = (
        match.group(0)
        + match.group("indent")
        + new_id.encode()
        + b" \\"
        + match.group("newline")
    )
    path.write_bytes(data[: match.start()] + replacement + data[match.end() :])


def update_ctm(material_names: list[str]) -> None:
    for material in material_names:
        for variant in ("hewn", "rock"):
            add_ctm_match(
                ctm_base_path(material, variant),
                f"erydon:{material}_{variant}_layer",
                f"erydon:{material}_{variant}_layer_multiface",
            )


def expected_asset_bytes(source: Path, material: str, variant: str) -> bytes:
    return translated_multiface_bytes(source, material, variant)


def validate_outputs() -> None:
    material_names = materials()
    for material in material_names:
        source_blockstate = BLOCKSTATES / f"{material}_ashlar_layer_multiface.json"
        source_item = ITEM_MODELS / f"{material}_ashlar_layer_multiface.json"
        sources = sorted(MULTIFACE_MODELS.glob(f"{material}_ashlar_layer_multiface_*.json"))
        if len(sources) != EXPECTED_MODELS_PER_BLOCK:
            raise RuntimeError(f"Unexpected Ashlar multiface model count for {material}")

        pairs: list[tuple[Path, Path]] = [
            (source_blockstate, BLOCKSTATES / f"{material}_hewn_layer_multiface.json"),
            (source_item, ITEM_MODELS / f"{material}_hewn_layer_multiface.json"),
        ]
        pairs.extend(
            (
                source,
                MULTIFACE_MODELS
                / source.name.replace(
                    f"{material}_ashlar_layer_multiface_",
                    f"{material}_hewn_layer_multiface_",
                ),
            )
            for source in sources
        )
        for source, hewn_target in pairs:
            expected_hewn = expected_asset_bytes(source, material, "hewn")
            if not hewn_target.is_file() or hewn_target.read_bytes() != expected_hewn:
                raise RuntimeError(f"Hewn multiface asset mismatch: {hewn_target}")
            rock_target = hewn_target.with_name(hewn_target.name.replace("_hewn_", "_rock_"))
            expected_rock = generate_rock_family.rock_bytes_from_hewn(expected_hewn)
            if not rock_target.is_file() or rock_target.read_bytes() != expected_rock:
                raise RuntimeError(f"Rock multiface mirror mismatch: {rock_target}")

    hewn_values = load_json(HEWN_TAG).get("values", [])
    expected_rock_values = [
        value.replace("_hewn_", "_rock_") if isinstance(value, str) else value
        for value in hewn_values
    ]
    if load_json(ROCK_TAG).get("values") != expected_rock_values:
        raise RuntimeError("Rock block tag is not an exact Hewn mirror")

    multiface_values = load_json(MULTIFACE_TAG).get("values", [])
    for material in material_names:
        hewn_id, rock_id = generated_ids(material)
        if hewn_values.count(hewn_id) != 1:
            raise RuntimeError(f"Missing or duplicate Hewn tag value: {hewn_id}")
        if multiface_values.count(hewn_id) != 1 or multiface_values.count(rock_id) != 1:
            raise RuntimeError(f"Missing multiface tag values for {material}")

    for filename in LANGUAGES:
        document = load_json(LANG_ROOT / filename)
        for material in material_names:
            for variant in ("hewn", "rock"):
                key = f"block.erydon.{material}_{variant}_layer_multiface"
                expected = localized_multiface_value(document, material, variant)
                if document.get(key) != expected:
                    raise RuntimeError(f"Language mismatch in {filename}: {key}")

    mod_blocks = MOD_BLOCKS.read_text(encoding="utf-8")
    for material in material_names:
        upper = material.upper()
        for variant in ("hewn", "rock"):
            variant_upper = variant.upper()
            field = f"public static Block {upper}_{variant_upper}_LAYER_MULTIFACE;"
            registration = (
                f'{upper}_{variant_upper}_LAYER_MULTIFACE = registerBlock('
                f'"{material}_{variant}_layer_multiface",'
            )
            if mod_blocks.count(field) != 1 or mod_blocks.count(registration) != 1:
                raise RuntimeError(f"Missing ModBlocks registration for {material} {variant}")

            ctm_path = ctm_base_path(material, variant)
            ctm_data = ctm_path.read_bytes()
            ctm_id = f"erydon:{material}_{variant}_layer_multiface".encode()
            if ctm_data.startswith(b"\xef\xbb\xbf") or ctm_data.count(ctm_id) != 1:
                raise RuntimeError(f"Invalid CTM coverage in {ctm_path}")

    generate_rock_family.validate_outputs()


def generate() -> None:
    material_names = materials()
    generate_assets(material_names)
    update_tags(material_names)
    update_languages(material_names)
    update_mod_blocks(material_names)
    update_ctm(material_names)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify committed outputs without rewriting them")
    args = parser.parse_args()
    if not args.check:
        generate()
    validate_outputs()
    print("Hewn and Rock multiface layers verified: 27 materials x 2 texture families.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
