#!/usr/bin/env python3
"""Generate the additional Grand coping family without replacing Georgian coping.

The editable flat master is retained under authoring/coping/georgian_grand.
Pitched profiles preserve the authored bevel widths. Small mitred triangles
finish the exposed corners of both moulding tiers.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
from pathlib import Path
import re

import generate_coping as common
import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
ASSETS = RESOURCES / "assets/erydon"
MASTER = ROOT / "authoring/coping/georgian_grand/coping_georgian_grand_flat.json"
RAW = ASSETS / "authoring_models/block/coping/georgian_grand"
SUFFIX = "coping_georgian_grand"
PROFILES = common.PROFILES
encoded = common.encoded


def variants():
    for material, finish, old_id, texture in common.variants():
        yield material, finish, old_id + "_grand", texture


def rotated_vertices(element):
    rotation = geometry._parse_raw_rotation(element.get("rotation"), (8, 8, 8))
    return [geometry._transform_raw_vertex(tuple(element["to" if i & (1 << axis) else "from"][axis]
                                               for axis in range(3)), [rotation]) for i in range(8)]


def clip_cross_section(points, boundary):
    """Keep only the exposed profile beyond the strip's finite end."""
    points = [(boundary if abs(c - boundary) < .00001 else c, y) for c, y in points]
    result = []
    a = points[-1]
    for b in points:
        inside_a, inside_b = a[0] <= boundary, b[0] <= boundary
        if inside_a != inside_b:
            t = (boundary - a[0]) / (b[0] - a[0])
            result.append((boundary, a[1] + t * (b[1] - a[1])))
        if inside_b:
            result.append(b)
        a = b
    return result


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def subtract(a, b):
    return tuple(x - y for x, y in zip(a, b))


def turn(point, quarter):
    x, y, z = point
    for _ in range(quarter):
        x, z = 16 - z, x
    return (x, y, z)


def corner_elements(element, band, run_end):
    """Loft each exposed cross-section edge around a square mitred corner."""
    vertices = rotated_vertices(element)
    original_section = [(vertices[i][2], vertices[i][1]) for i in (0, 2, 6, 4)]
    section = clip_cross_section(original_section, -run_end)
    result = []
    names = ("edge_north_west", "edge_north_east", "edge_south_east", "edge_south_west")
    for a, b in zip(section, section[1:] + section[:1]):
        if abs(a[0] + run_end) < 1e-10 and abs(b[0] + run_end) < 1e-10:
            continue
        midpoint = tuple((x + y) / 2 for x, y in zip(a, b))
        source_face = None
        for index, (u, v) in enumerate(zip(original_section, original_section[1:] + original_section[:1])):
            dx, dy = v[0] - u[0], v[1] - u[1]
            distance = abs((midpoint[0] - u[0]) * dy - (midpoint[1] - u[1]) * dx) / math.hypot(dx, dy)
            if distance < .00001 and all(min(u[i], v[i]) - .00001 <= midpoint[i] <= max(u[i], v[i]) + .00001 for i in range(2)):
                source_face = ("north", "up", "south", "down")[index]
                break
        if source_face is None:
            raise ValueError("Corner plane has no matching authored bevel face")
        dc, dy = b[0] - a[0], b[1] - a[1]
        for axis in ("north", "west"):
            polygon = [(-run_end, a[1], a[0]), (a[0], a[1], a[0]),
                       (b[0], b[1], b[0]), (-run_end, b[1], b[0])]
            if axis == "west":
                polygon = [(z, y, x) for x, y, z in polygon]
            unique = []
            for p in polygon:
                if not unique or max(abs(v - w) for v, w in zip(p, unique[-1])) > 1e-9:
                    unique.append(p)
            if len(unique) > 1 and max(abs(v - w) for v, w in zip(unique[0], unique[-1])) < 1e-9:
                unique.pop()
            if len(unique) != 3:
                raise ValueError("Expected one non-degenerate mitred triangle per strip plane")
            desired = (0, dc, -dy) if axis == "north" else (-dy, dc, 0)
            normal = cross(subtract(unique[1], unique[0]), subtract(unique[2], unique[0]))
            if sum(v * w for v, w in zip(normal, desired)) < 0:
                unique.reverse()
            for quarter, name in enumerate(names):
                positions = [[round(v, 10) for v in turn(p, quarter)] for p in unique]
                # Raw quads encode triangles with the final vertex repeated.
                positions.append(positions[-1].copy())
                mins = [min(p[i] for p in positions) for i in range(3)]
                maxs = [max(p[i] for p in positions) for i in range(3)]
                direction = source_face
                if axis == "west":
                    direction = {"north": "west", "south": "east", "up": "up", "down": "down"}[direction]
                if direction not in ("up", "down"):
                    horizontal = ("north", "east", "south", "west")
                    direction = horizontal[(horizontal.index(direction) + quarter) % 4]
                # A deliberately cropped, atlas-safe authored mapping. Synapheia
                # retains the explicit face's world-projection plane at runtime.
                width = round(max(abs(b[0] - a[0]), .00001), 8)
                height = round(max(abs(b[1] - a[1]), .00001), 8)
                result.append({"name": name, "from": mins, "to": maxs,
                               "erydon_coping_band": band, "erydon_coping_corner": True,
                               "faces": {direction: {"uv": [0, 0, width, height], "texture": "#stone",
                                                       "erydon_vertices": positions}}})
    return result


def finished_flat(master):
    model = copy.deepcopy(master)
    if len(model.get("elements", [])) != 12:
        raise ValueError("Grand coping master must retain its twelve authored elements")
    elements = model["elements"]
    elements[2]["name"], elements[3]["name"] = "lower_band", "lower_lip"
    for index, edge in zip(range(4, 12), ("east", "west", "south", "north", "north", "south", "east", "west")):
        elements[index]["name"] = "edge_" + edge
        elements[index]["erydon_coping_band"] = "upper_band" if index < 8 else "lower_band"
    elements.extend(corner_elements(elements[7], "upper_band", .5))
    elements.extend(corner_elements(elements[8], "lower_band", 2))
    return model


def pitched(flat, profile):
    if profile == "flat":
        return copy.deepcopy(flat)
    start, end, high, low = PROFILES[profile]
    scale = math.hypot(end - start, high - low)
    model = copy.deepcopy(flat)
    for element in model["elements"]:
        edge = element.get("name", "")
        is_corner = element.get("erydon_coping_corner", False)
        west = edge == "edge_west" or is_corner and edge.endswith("_west")
        east = edge == "edge_east" or is_corner and edge.endswith("_east")

        def transform(point):
            x, y, z = point
            if west or east:
                x += (scale - 1) * 16 if east else 0
            elif x > 16:
                x += (scale - 1) * 16
            elif x >= 0:
                x *= scale
            return [round(start * 16 + x, 10), round(high * 16 + y, 10), z]

        for field in ("from", "to"):
            element[field] = transform(element[field])
        if "origin" in element.get("rotation", {}):
            element["rotation"]["origin"] = transform(element["rotation"]["origin"])
        for face in element["faces"].values():
            if "erydon_vertices" in face:
                face["erydon_vertices"] = [transform(p) for p in face["erydon_vertices"]]
    model["groups"] = [{"name": "coping_profile", "origin": [start * 16, high * 16, 0],
                        "rotation": [0, 0, -math.degrees(math.atan2(high - low, end - start))],
                        "children": list(range(len(model["elements"])))}]
    return model


def language_payload(path, additions):
    source = path.read_bytes()
    existing = json.loads(source)
    missing = {key: value for key, value in additions.items() if key not in existing}
    for key, value in additions.items():
        if key in existing and existing[key] != value:
            pattern = rb'("' + re.escape(key.encode()) + rb'"\s*:\s*)"(?:\\.|[^"\\])*"'
            source = re.sub(pattern, lambda match: match.group(1) + json.dumps(value, ensure_ascii=False).encode(), source)
    if missing:
        closing = source.rfind(b"}")
        head = source[:closing].rstrip()
        if not head.endswith(b","):
            head += b","
        source = head + b"\n" + b",\n".join(("  " + json.dumps(k) + ": " + json.dumps(v, ensure_ascii=False)).encode()
                                             for k, v in missing.items()) + b"\n}" + source[closing + 1:]
    return source


def outputs():
    master = json.loads(MASTER.read_bytes())
    flat = finished_flat(master)
    result = {RAW / f"{SUFFIX}_{profile}.json": encoded(pitched(flat, profile)) for profile in PROFILES}
    ids = []
    for material, finish, block_id, texture in variants():
        ids.append("erydon:" + block_id)
        wrapper = "block/internal/wrapped/" + block_id
        result[ASSETS / f"blockstates/{block_id}.json"] = encoded({"variants": {"": {"model": "erydon:" + wrapper}}})
        result[ASSETS / f"models/{wrapper}.json"] = encoded({"parent": "minecraft:block/block", "textures": {"particle": "erydon:block/" + texture}})
        result[ASSETS / f"models/item/{block_id}.json"] = encoded({"parent": "erydon:" + wrapper,
                    "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 3, 0], "scale": [.6, .6, .6]}}})
        result[RESOURCES / f"data/erydon/loot_tables/blocks/{block_id}.json"] = encoded({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "erydon:" + block_id}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
        prefix = material + ("_" + finish if finish else "")
        native = RESOURCES / f"assets/minecraft/optifine/ctm/{prefix}/coping.properties"
        source = native.read_bytes()
        rule = re.sub(rb"(?m)^matchBlocks=.*$", ("matchBlocks=erydon:" + block_id).encode(), source)
        if rule == source or rule.startswith(b"\xef\xbb\xbf"):
            raise ValueError("Expected the existing BOM-free dedicated coping repeat rule")
        result[native.with_name("coping_georgian_grand.properties")] = rule
    for kind in ("blocks", "items"):
        result[RESOURCES / f"data/erydon/tags/{kind}/{SUFFIX}.json"] = encoded({"replace": False, "values": ids})
        common_tag = RESOURCES / f"data/erydon/tags/{kind}/coping.json"
        result[common_tag] = common.append_tag(common_tag, ids)
        for finish in common.FINISHES[1:]:
            path = RESOURCES / f"data/erydon/tags/{kind}/{finish}.json"
            result[path] = common.append_tag(path, ["erydon:" + block_id for _, f, block_id, _ in variants() if f == finish])
    translations = {
        "en_us": ("Georgian Grand Coping", {"": "Polished", "aged": "Aged", "rusticated": "Rusticated", "hewn": "Hewn", "ashlar": "Ashlar"},
                  "A grand two-tier wall cap that fits full cubes, slabs, half-height layers, upward slopes and diagonal walls.",
                  "Matching Grand coping joins continuously, with finished moulding corners."),
        "de_de": ("Große georgianische Mauerabdeckung", {"": "Poliert", "aged": "Gealtert", "rusticated": "Rustiziert", "hewn": "Behauen", "ashlar": "Quader"},
                  "Eine große zweistufige Mauerabdeckung für volle Würfel, Stufen, halbhohe Schichten, aufwärts gerichtete Schrägen und diagonale Mauern.",
                  "Passende große Abdeckungen verbinden sich durchgehend mit geschlossenen Profilecken."),
        "es_es": ("Gran albardilla georgiana", {"": "Pulido", "aged": "Envejecido", "rusticated": "Rusticado", "hewn": "Labrado", "ashlar": "Sillería"},
                  "Una gran coronación de dos niveles para cubos completos, losas, capas de media altura, pendientes ascendentes y muros diagonales.",
                  "Las grandes albardillas contiguas se unen de forma continua, con esquinas de moldura cerradas.")}
    for locale, (family, finishes, first, second) in translations.items():
        additions = {f"tooltip.erydon.family.{SUFFIX}.1": first, f"tooltip.erydon.family.{SUFFIX}.2": second}
        additions.update({"block.erydon." + block_id: f"{material.capitalize()} {finishes[finish]} {family}"
                          for material, finish, block_id, _ in variants()})
        path = ASSETS / f"lang/{locale}.json"
        result[path] = language_payload(path, additions)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--import-flat", type=Path, help="Import the explicitly supplied Blockbench export as the editable master")
    args = parser.parse_args()
    if args.import_flat:
        if args.check:
            parser.error("--check cannot import a master")
        source = args.import_flat.read_bytes()
        json.loads(source)
        MASTER.parent.mkdir(parents=True, exist_ok=True)
        MASTER.write_bytes(source)
    changed = []
    for path, payload in outputs().items():
        if path.is_file() and path.read_bytes() == payload:
            continue
        changed.append(path.relative_to(ROOT).as_posix())
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
    print(json.dumps({"variants": 135, "profiles": len(PROFILES), "cornerTrianglesPerProfile": 32,
                      "changed": len(changed), "paths": changed[:12]}))
    return int(args.check and bool(changed))


if __name__ == "__main__":
    raise SystemExit(main())
