#!/usr/bin/env python3
"""Generate/check Rusticated multiface layers using the established Ashlar geometry."""
import argparse
import json
import re

import generate_hewn_multiface_layers as shared


def insert_after(data, anchor, addition):
    if addition in data:
        return data
    if data.count(anchor) != 1:
        raise RuntimeError(f"Expected one insertion anchor: {anchor!r}")
    return data.replace(anchor, anchor + addition)


def outputs():
    """Return deterministic new assets; preserve each material's authored geometry."""
    for material in shared.materials():
        sources = [shared.BLOCKSTATES / f"{material}_ashlar_layer_multiface.json",
                   shared.ITEM_MODELS / f"{material}_ashlar_layer_multiface.json"]
        components = sorted(shared.MULTIFACE_MODELS.glob(f"{material}_ashlar_layer_multiface_*.json"))
        if len(components) != shared.EXPECTED_MODELS_PER_BLOCK:
            raise RuntimeError(f"Incomplete Ashlar multiface source: {material}")
        for source in sources + components:
            target = source.with_name(source.name.replace("_ashlar_", "_rusticated_"))
            yield target, shared.translated_multiface_bytes(source, material, "rusticated")


def generate():
    for path, data in outputs():
        shared.write_bytes_if_changed(path, data)
    materials = shared.materials()
    data = shared.MOD_BLOCKS.read_bytes()
    for material in materials:
        upper = material.upper()
        anchor = f"    public static Block {upper}_RUSTICATED_LAYER;".encode()
        data = insert_after(data, anchor, f"\n    public static Block {upper}_RUSTICATED_LAYER_MULTIFACE;".encode())
        pattern = rb'(?m)^        ' + upper.encode() + rb'_RUSTICATED_LAYER = registerBlock\([^\n]+\r?\n[^\n]+\n'
        match = re.search(pattern, data)
        if match is None:
            raise RuntimeError(f"Missing Rusticated layer registration: {material}")
        addition = (f'        {upper}_RUSTICATED_LAYER_MULTIFACE = registerBlock("{material}_rusticated_layer_multiface",\n'
                    f'            new LayerMultifaceBlock(AbstractBlock.Settings.copy({upper}_BLOCK).nonOpaque()));\n').encode()
        data = insert_after(data, match.group(), addition)
        shared.add_ctm_match(shared.ctm_base_path(material, "rusticated"),
                             f"erydon:{material}_rusticated_layer", f"erydon:{material}_rusticated_layer_multiface")
    shared.write_bytes_if_changed(shared.MOD_BLOCKS, data)
    for filename in shared.LANGUAGES:
        path = shared.LANG_ROOT / filename
        document = shared.load_json(path)
        data = path.read_bytes()
        for material in materials:
            key = f"block.erydon.{material}_rusticated_layer_multiface"
            if key in document:
                continue
            anchor = next(line for line in data.splitlines(keepends=True)
                          if f'"block.erydon.{material}_rusticated_layer":'.encode() in line)
            value = shared.localized_multiface_value(document, material, "rusticated")
            addition = ('    ' + json.dumps(key) + ': ' + json.dumps(value, ensure_ascii=False) + ',\n').encode()
            data = insert_after(data, anchor, addition)
        shared.write_bytes_if_changed(path, data)
    for path in sorted((shared.RESOURCES / "data").rglob("*.json")):
        if "tags" not in path.parts or "blocks" not in path.parts:
            continue
        data = path.read_bytes()
        values = shared.load_json(path).get("values", [])
        for material in materials:
            source = f"erydon:{material}_rusticated_layer"
            if path == shared.MULTIFACE_TAG:
                source = f"erydon:{material}_ashlar_layer_multiface"
            target = f"erydon:{material}_rusticated_layer_multiface"
            if source not in values or target in values:
                continue
            anchor = next(line for line in data.splitlines(keepends=True) if f'"{source}"'.encode() in line)
            # Retain the existing line's comma/newline convention.
            addition = anchor.replace(source.encode(), target.encode())
            data = insert_after(data, anchor, addition)
        shared.write_bytes_if_changed(path, data)


def validate_outputs():
    for path, expected in outputs():
        if not path.is_file() or path.read_bytes() != expected:
            raise RuntimeError(f"Missing or mismatched Rusticated multiface asset: {path}")
    source = shared.MOD_BLOCKS.read_text(encoding="utf-8")
    for material in shared.materials():
        block = f"{material}_rusticated_layer_multiface"
        upper = block.upper()
        assert source.count(f"public static Block {upper};") == 1, block
        assert source.count(f'{upper} = registerBlock("{block}",') == 1, block
        for filename in shared.LANGUAGES:
            document = shared.load_json(shared.LANG_ROOT / filename)
            assert document[f"block.erydon.{block}"] == shared.localized_multiface_value(document, material, "rusticated")
        ctm = shared.ctm_base_path(material, "rusticated").read_bytes()
        assert not ctm.startswith(b"\xef\xbb\xbf") and ctm.count(f"erydon:{block}".encode()) == 1, block
    for path in sorted((shared.RESOURCES / "data").rglob("*.json")):
        if "tags" not in path.parts or "blocks" not in path.parts:
            continue
        values = shared.load_json(path).get("values", [])
        for material in shared.materials():
            if f"erydon:{material}_rusticated_layer" in values or path == shared.MULTIFACE_TAG:
                assert values.count(f"erydon:{material}_rusticated_layer_multiface") == 1, path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    if not parser.parse_args().check:
        generate()
    validate_outputs()
    print("Verified Rusticated multiface layers for all 27 materials.")
