#!/usr/bin/env python3
"""Generate material wrappers for editable Georgian coping. No texture files are rewritten.

The six authoring models are editable sources. --derive-slopes deliberately recreates
the five pitched sources from the flat source; ordinary generation preserves edits.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
ASSETS = RESOURCES / "assets/erydon"
RAW = ASSETS / "authoring_models/block/coping/georgian"
PROFILES = {"flat": (0, 1, 0, 0), "slope": (0, 1, 0, -1),
            "shallow_lower": (0, 1, -.5, -1), "shallow_upper": (0, 1, 0, -.5),
            "steep_lower": (0, .5, 0, -1), "steep_upper": (.5, 1, 0, -1)}
FINISHES = ("", "aged", "rusticated", "hewn", "ashlar")


def materials() -> list[str]:
    source = (ROOT / "src/main/java/com/oliver/erydon/item/ErydonMaterialSources.java").read_text(encoding="utf-8")
    return re.findall(r'material\("([a-z]+)"', source)


def variants():
    for material in materials():
        for finish in FINISHES:
            prefix = material + ("_" + finish if finish else "")
            texture = material + "_block_aged" if finish == "aged" else prefix + "_block"
            yield material, finish, prefix + "_coping_georgian", texture


def encoded(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def append_tag(path: Path, additions: list[str]) -> bytes:
    if not path.exists(): return encoded({"replace": False, "values": additions})
    source = path.read_bytes()
    values = json.loads(source)["values"]
    missing = [value for value in additions if value not in values]
    if not missing: return source
    closing = source.rfind(b"]")
    head = source[:closing].rstrip()
    if not head.endswith(b"["): head += b","
    return head + b"\n" + b",\n".join(("    " + json.dumps(value)).encode() for value in missing) + b"\n  " + source[closing:]


def derive_slopes() -> None:
    flat = json.loads((RAW / "coping_georgian_flat.json").read_text(encoding="utf-8"))
    for name, (start, end, high, low) in PROFILES.items():
        if name == "flat":
            continue
        model = copy.deepcopy(flat)
        span, rise = end - start, high - low
        scale = math.hypot(span, rise)
        for element in model["elements"]:
            # End bevels keep their authored angle and width. Move the far end
            # along the longer pitched run instead of stretching its cuboid.
            edge = element.get("name", "")
            end_bevel = edge in ("edge_west", "edge_east")
            end_shift = (scale - 1) * 16 if edge == "edge_east" else 0
            for key in ("from", "to"):
                element[key][0] = round(start * 16 + (element[key][0] + end_shift if end_bevel else element[key][0] * scale), 8)
                element[key][1] = round(high * 16 + element[key][1], 8)
            origin = element.get("rotation", {}).get("origin")
            if origin:
                origin[0] = round(start * 16 + (origin[0] + end_shift if end_bevel else origin[0] * scale), 8)
                origin[1] = round(high * 16 + origin[1], 8)
        model["groups"] = [{"name": "coping_profile", "origin": [start * 16, high * 16, 0],
                            "rotation": [0, 0, -math.degrees(math.atan2(rise, span))],
                            "children": list(range(len(model["elements"])))}]
        (RAW / f"coping_georgian_{name}.json").write_bytes(encoded(model))


def outputs() -> dict[Path, bytes]:
    result = {}
    ids = []
    for material, finish, block_id, texture in variants():
        ids.append("erydon:" + block_id)
        wrapper = "block/internal/wrapped/" + block_id
        result[ASSETS / f"blockstates/{block_id}.json"] = encoded({"variants": {"": {"model": "erydon:" + wrapper}}})
        result[ASSETS / f"models/{wrapper}.json"] = encoded({"parent": "minecraft:block/block", "textures": {"particle": "erydon:block/" + texture}})
        result[ASSETS / f"models/item/{block_id}.json"] = encoded({"parent": "erydon:" + wrapper,
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 5, 0], "scale": [.65, .65, .65]}}})
        result[RESOURCES / f"data/erydon/loot_tables/blocks/{block_id}.json"] = encoded({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "erydon:" + block_id}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    for kind in ("blocks", "items"):
        result[RESOURCES / f"data/erydon/tags/{kind}/coping.json"] = encoded({"replace": False, "values": ids})
        for tag in ("georgian", "aged", "rusticated", "hewn", "ashlar"):
            path = RESOURCES / f"data/erydon/tags/{kind}/{tag}.json"
            selected = ["#erydon:coping"] if tag == "georgian" else ["erydon:" + block_id for _, finish, block_id, _ in variants() if finish == tag]
            result[path] = append_tag(path,selected)
    pickaxe = RESOURCES / "data/minecraft/tags/blocks/mineable/pickaxe.json"
    result[pickaxe] = append_tag(pickaxe,["#erydon:coping"])
    translations = {
        "en_us": ("Georgian Coping", {"": "Polished", "aged": "Aged", "rusticated": "Rusticated", "hewn": "Hewn", "ashlar": "Ashlar"},
                  "Automatically fits full cubes, upward slopes and diagonal walls made from vertical slopes.",
                  "Neighbouring coping pieces join into a continuous wall cap."),
        "de_de": ("Georgianische Mauerabdeckung", {"": "Poliert", "aged": "Gealtert", "rusticated": "Rustiziert", "hewn": "Behauen", "ashlar": "Quader"},
                  "Passt sich automatisch an volle Würfel, aufwärts gerichtete Schrägen und diagonale Mauern aus vertikalen Schrägen an.",
                  "Benachbarte Abdeckungen bilden einen durchgehenden Mauerabschluss."),
        "es_es": ("Albardilla georgiana", {"": "Pulido", "aged": "Envejecido", "rusticated": "Rusticado", "hewn": "Labrado", "ashlar": "Sillería"},
                  "Se adapta automáticamente a cubos completos, pendientes ascendentes y muros diagonales de pendientes verticales.",
                  "Las piezas contiguas forman una coronación continua del muro.")}
    for locale, (family, finishes, first, second) in translations.items():
        path = ASSETS / f"lang/{locale}.json"
        # Keep existing formatting, line endings and unrelated working-tree edits.
        source = path.read_bytes()
        additions = {"tooltip.erydon.family.coping_georgian.1": first, "tooltip.erydon.family.coping_georgian.2": second}
        additions.update({"block.erydon."+block_id: f"{material.capitalize()} {finishes[finish]} {family}" for material, finish, block_id, _ in variants()})
        present = json.loads(source)
        missing = {key: value for key, value in additions.items() if key not in present}
        for key, value in additions.items():
            if key in present and present[key] != value:
                pattern = rb'("' + re.escape(key.encode()) + rb'"\s*:\s*)"(?:\\.|[^"\\])*"'
                source = re.sub(pattern, lambda m: m.group(1) + json.dumps(value, ensure_ascii=False).encode(), source)
        if missing:
            closing = source.rfind(b"}")
            head = source[:closing].rstrip()
            if not head.endswith(b","): head += b","
            source = head + b"\n" + b",\n".join(("  "+json.dumps(key)+": "+json.dumps(value, ensure_ascii=False)).encode() for key, value in missing.items()) + b"\n}" + source[closing+1:]
        result[path] = source
    # Add each registered coping to its existing native material rule; optional packs are untouched.
    ctm = RESOURCES / "assets/minecraft/optifine/ctm"
    for material, finish, block_id, _ in variants():
        directory = ctm / (material + ("_" + finish if finish else ""))
        candidates = sorted(directory.glob("a_*_base.properties"))
        if len(candidates) != 1: raise ValueError(f"Expected one native CTM rule in {directory}")
        path = candidates[0]
        source = path.read_bytes()
        term = ("erydon:"+block_id).encode()
        if term not in source:
            lines = source.splitlines(keepends=True)
            start = next(i for i, line in enumerate(lines) if line.startswith(b"matchBlocks="))
            end = start
            while lines[end].rstrip().endswith(b"\\"): end += 1
            lines[end] = lines[end].rstrip() + b" \\\n  " + term + b"\n"
            source = b"".join(lines)
        result[path] = source
        # Packs may override the older material rule without the new coping IDs.
        # A distinct native rule survives that override and resolves the pack's tiles.
        lines = source.splitlines(keepends=True)
        start = next(i for i, line in enumerate(lines) if line.startswith(b"matchBlocks="))
        end = start
        while lines[end].rstrip().endswith(b"\\"): end += 1
        coping_rule = b"".join(lines[:start] + [b"matchBlocks=" + term + b"\n"] + lines[end+1:])
        result[directory / "coping.properties"] = coping_rule
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--derive-slopes", action="store_true")
    args = parser.parse_args()
    if args.check and args.derive_slopes: parser.error("--check does not rewrite authoring sources")
    if args.derive_slopes: derive_slopes()
    for name in PROFILES:
        if not (RAW / f"coping_georgian_{name}.json").is_file(): raise ValueError("Missing editable profile: " + name)
    changed = []
    for path, payload in outputs().items():
        if path.exists() and path.read_bytes() == payload: continue
        changed.append(path.relative_to(ROOT).as_posix())
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
    print(json.dumps({"copings": len(materials()) * len(FINISHES), "profiles": len(PROFILES), "changed": len(changed), "paths": changed[:12]}))
    return int(args.check and bool(changed))


if __name__ == "__main__": raise SystemExit(main())
