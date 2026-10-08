#!/usr/bin/env python3
"""Audit/synchronise player block tags and their item-browser counterparts."""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
TAGS = RES / "data/erydon/tags"
NON_STANDARD_FINISH_TOKENS = {
    "aged", "ashlar", "hewn", "rusticated", "rock", "weave", "herringbone",
    "trim", "guilloche", "quatrefoil", "rosette", "rose", "diaphanes",
}


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def catalogue():
    ids = {key.removeprefix("block.erydon.") for key in read(RES / "assets/erydon/lang/en_us.json")
           if key.startswith("block.erydon.")}
    placed_ids = {path.stem for path in (RES / "assets/erydon/blockstates").glob("*.json")}
    if ids - placed_ids:
        raise ValueError("Display names without placed blocks: " + ", ".join(sorted(ids - placed_ids)))
    return ids


def is_standard_finish(path, materials):
    tokens = path.split("_")
    return len(tokens) > 1 and tokens[0] in materials and not NON_STANDARD_FINISH_TOKENS.intersection(tokens[1:])


def synchronise(check=False):
    ids = catalogue()
    placed_ids = {path.stem for path in (RES / "assets/erydon/blockstates").glob("*.json")}
    with (RES / "data/erydon/id_migration.tsv").open(encoding="utf-8-sig") as stream:
        aliases = {row["old_path"]: row["canonical_path"] for row in csv.DictReader(stream, delimiter="\t")}
    materials = re.findall(r'material\("([a-z]+)"', (ROOT / "src/main/java/com/oliver/erydon/item/ErydonMaterialSources.java").read_text())
    blocks = {path.stem: read(path) for path in (TAGS / "blocks").glob("*.json")}
    items = {path.stem: read(path) for path in (TAGS / "items").glob("*.json")}
    extra_tags = {}

    def resolved(tags, key, ancestors=()):
        if key in ancestors:
            raise ValueError("Cyclic tag: " + " -> ".join((*ancestors, key)))
        result = set()
        for entry in tags.get(key, {}).get("values", []):
            value = entry if isinstance(entry, str) else entry["id"]
            if value.startswith("#erydon:"):
                name = value.removeprefix("#erydon:")
                if name not in tags:
                    raise ValueError("Missing referenced tag: " + name)
                result.update(resolved(tags, name, (*ancestors, key)))
            elif value.startswith("erydon:"):
                path = value.removeprefix("erydon:")
                result.add(aliases.get(path, path))
        return result

    def extend(tags, key, expected):
        tag = tags.setdefault(key, {"replace": False, "values": []})
        missing = expected - resolved(tags, key)
        tag["values"].extend("erydon:" + value for value in sorted(missing))

    extend(blocks, "erydon_blocks", ids)
    material_members = {}
    for material in materials:
        matches = {path for path in ids if path.startswith(material + "_")
                   or ("_weave_" in path and material in path.split("_weave_", 1)[0].split("_"))}
        material_members[material] = matches
        extend(blocks, material, matches)
    for finish in ("aged", "rusticated", "hewn", "ashlar", "rock"):
        extend(blocks, finish, {path for path in ids if finish in path.split("_")})
    standard = {path for path in ids if is_standard_finish(path, materials)}
    extend(blocks, "polished", standard)
    for finish in ("honed", "mirror"):
        blocks.setdefault(finish, {"replace": False, "values": ["#erydon:polished"]})
        items.setdefault(finish, {"replace": False, "values": ["#erydon:polished"]})
    for finish in ("polished", "honed", "mirror"):
        if resolved(blocks, finish) - standard:
            raise ValueError("Standard-finish tag contains an alternate finish: " + finish)
    # Geology tags inherit their existing material membership. New forms of those
    # stones must remain discoverable without inventing new classifications.
    for name in ("marble", "limestone", "sandstone", "onyx", "quartzite", "sodalite", "labradorite", "travertine",
                 "agate", "alabaster", "gemstone", "opal", "quartz", "blue_stone"):
        if name not in blocks:
            continue
        # A mixed weave can carry another stone's colour or geology. Infer the
        # classification from plain material blocks, never its first weave ID.
        members = resolved(blocks, name)
        stone_set = {material for material in materials if material + "_block" in members}
        extend(blocks, name, set().union(*(material_members[material] for material in stone_set)))
    for form in ("slab", "stairs", "layer", "layer_multiface", "layer_vertical", "pane",
                 "slope", "slope_shallow_lower", "slope_shallow_upper", "slope_steep_lower", "slope_steep_upper",
                 "slope_vertical", "slope_vertical_shallow_broad", "slope_vertical_shallow_narrow", "vertical_diagonal",
                 "stairs_shallow_bottom", "stairs_shallow_top", "stairs_spiral_large", "column_square", "column_gothic",
                 "arch_modern", "arch_gothic", "arch_romanesque", "alcove_georgian", "alcove_gothic",
                 "chimney_circular", "window_arch", "window_french_georgian", "post"):
        extend(blocks, form, {path for path in ids if path.endswith("_" + form)})
    for colour in ("black", "white", "bronze", "silver", "crystal", "tinted"):
        extend(blocks, colour, {path for path in ids if colour in path.split("_")})
    for name in ("herringbone", "herringbone_bronze", "herringbone_grout", "weave", "weave_bronze", "weave_grout",
                 "trim", "quatrefoil", "guilloche", "horizontal", "vertical", "diagonal", "broad", "narrow", "shallow", "steep",
                 "circular", "square", "modern", "georgian", "romanesque"):
        extend(blocks, name, {path for path in ids if "_" + name + "_" in "_" + path + "_"})
    weave_ids = {path for path in ids if "_weave_" in path}
    for material, members in material_members.items():
        matches = weave_ids & members
        if matches:
            extend(blocks, material + "_weave", matches)
    blocks.setdefault("rosette", {"replace": False, "values": ["#erydon:rose"]})
    for name, marker in (("column", "_column_"), ("cornice", "_cornice_"), ("surround", "_surround_"),
                         ("surround_parts", "_surround_"), ("arch", "_arch_"), ("alcove", "_alcove_"),
                         ("chimney", "_chimney_"), ("window", "_window_"), ("light", "_light_"),
                         ("ceiling", "_ceiling_coffered_"), ("ceiling_coffered", "_ceiling_coffered_"),
                         ("coffered", "_ceiling_coffered_"), ("column_circular", "_column_circular"),
                         ("slope_shallow", "_slope_shallow_"), ("slope_steep", "_slope_steep_"),
                         ("slope_vertical_shallow", "_slope_vertical_shallow_"), ("stairs_shallow", "_stairs_shallow_")):
        extend(blocks, name, {path for path in ids if marker in path})
    # Framed glazing keeps the established shallow_slope word order.
    for position in ("lower", "upper"):
        matches = {path for path in ids if path.endswith("_shallow_slope_" + position)}
        extend(blocks, "slope_shallow_" + position, matches)
        extend(blocks, "slope_shallow", matches)
    wall_ids = {path for path in ids if path.endswith("_wall_georgian")
                or (path.endswith("_wall") and "_light_" not in path)}
    for name in ("wall", "walls"):
        extend(blocks, name, wall_ids)
    vanilla_walls_path = RES / "data/minecraft/tags/blocks/walls.json"
    vanilla_walls = read(vanilla_walls_path)
    if "#erydon:walls" not in vanilla_walls["values"]:
        vanilla_walls["values"].append("#erydon:walls")
    extra_tags[vanilla_walls_path] = vanilla_walls
    ornate = {path for path in ids if path.endswith("_surround_gothic_ornate")}
    # Gothic Ornate is a separate family, never an alias for plain Gothic.
    for tags in (blocks, items):
        tag = tags.setdefault("gothic", {"replace": False, "values": []})
        tag["values"] = [entry for entry in tag["values"]
                                    if aliases.get((entry if isinstance(entry, str) else entry["id"]).removeprefix("erydon:"),
                                                   (entry if isinstance(entry, str) else entry["id"]).removeprefix("erydon:")) not in ornate]
    extend(blocks, "gothic_ornate", ornate)
    extend(blocks, "gothic", {path for path in ids if "gothic" in path.split("_")} - ornate)
    blocks.setdefault("byzantine", {"replace": False, "values": ["#erydon:guilloche"]})
    for name in ("moulding", "decorative", "facade", "structural"):
        if "#erydon:coping" not in blocks[name]["values"]:
            blocks[name]["values"].append("#erydon:coping")
    full = {path for path in ids if path.endswith("_block")}
    if resolved(blocks, "block") - full:
        raise ValueError("Full-block tag contains a partial shape")
    extend(blocks, "block", full)
    # Preserve existing references/legacy aliases; only append missing members.
    # Item tags exclude internal blocks with no player item or display name.
    for name in blocks:
        extend(items, name, resolved(blocks, name) & ids)

    # Axiom uses block tags; item browsers use item tags. Keep the existing
    # material/style/colour namespace bridges available on both surfaces.
    for namespace in ("material", "style", "color"):
        namespace_blocks = {path.stem: read(path) for path in (RES / f"data/{namespace}/tags/blocks").glob("*.json")}
        if namespace == "material":
            extend(namespace_blocks, "stone", set().union(*material_members.values()))
        if namespace == "style":
            for name in ("byzantine", "gothic_ornate"):
                namespace_blocks.setdefault(name, {"replace": False, "values": ["#erydon:" + name]})
        for name, data in namespace_blocks.items():
            extra_tags[RES / f"data/{namespace}/tags/blocks/{name}.json"] = data
            extra_tags[RES / f"data/{namespace}/tags/items/{name}.json"] = data

    changed = []
    for kind, tags in (("blocks", blocks), ("items", items)):
        for name, data in sorted(tags.items()):
            path = TAGS / kind / (name + ".json")
            extra_tags[path] = data
    for path, data in sorted(extra_tags.items()):
        for entry in data.get("values", []):
            value = entry if isinstance(entry, str) else entry["id"]
            if value.startswith("erydon:"):
                name = value.removeprefix("erydon:")
                if aliases.get(name, name) not in placed_ids:
                    raise ValueError(f"Tag {path.relative_to(ROOT)} references a missing placed block: {value}")
        if path.exists() and read(path) == data:
            continue
        changed.append(str(path.relative_to(ROOT)))
        if not check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"catalogue_items": len(ids), "block_tags": len(blocks), "item_tags": len(items),
                      "full_blocks": len(full), "standard_finish_items": len(standard),
                      "changed": len(changed), "paths": changed if check else None}))
    return bool(changed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    changed = synchronise(args.check)
    raise SystemExit(1 if args.check and changed else 0)
