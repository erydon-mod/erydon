#!/usr/bin/env python3
"""Synchronise Georgian arch catalogue resources without changing geometry.

The existing Modern arch catalogue supplies material and finish bindings. The
Georgian geometry generator owns component models; this tool owns lightweight
placed anchors, item references, translations and dedicated native CTM rules.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path("src/main/resources/assets/erydon")
CTM = Path("src/main/resources/assets/minecraft/optifine/ctm")
SOURCE_SUFFIX = "_arch_modern"
TARGET_SUFFIX = "_arch_georgian"

PROFILE_NAMES = {
    "en_us.json": ("Modern Arch", "Georgian Arch"),
    "de_de.json": ("Modern Bogen", "Georgianischer Bogen"),
    "es_es.json": ("Arco moderno", "Arco georgiano"),
}

ARCH_TOOLTIPS = {
    "en_us.json": {
        "romanesque": "Auto-assembles Romanesque arches up to 6 metres wide; wider curves also grow taller. Longer runs start a new arch.",
        "modern": "Auto-assembles Modern arches up to 6 metres wide; wider curves also grow taller. Longer runs start a new arch.",
        "gothic": "Auto-assembles Gothic arches up to 6 metres wide; wider curves also grow taller. Longer runs start a new arch.",
        "georgian": "Auto-assembles Georgian arches up to 6 metres wide; wider curves also grow taller. Longer runs start a new arch.",
        "frame": "Uses a Georgian frame with detailed pilasters.",
        "width": "Use the debug stick to cap the width at 1–5 metres.",
    },
    "de_de.json": {
        "romanesque": "Baut automatisch romanische Bögen mit bis zu 6 Metern Breite; breitere Bögen werden auch höher. Längere Reihen beginnen einen neuen Bogen.",
        "modern": "Baut automatisch moderne Bögen mit bis zu 6 Metern Breite; breitere Bögen werden auch höher. Längere Reihen beginnen einen neuen Bogen.",
        "gothic": "Baut automatisch gotische Bögen mit bis zu 6 Metern Breite; breitere Bögen werden auch höher. Längere Reihen beginnen einen neuen Bogen.",
        "georgian": "Baut automatisch georgianische Bögen mit bis zu 6 Metern Breite; breitere Bögen werden auch höher. Längere Reihen beginnen einen neuen Bogen.",
        "frame": "Verwendet einen georgianischen Rahmen mit verzierten Pilastern.",
        "width": "Mit dem Debug-Stab lässt sich die Breite auf 1–5 Meter begrenzen.",
    },
    "es_es.json": {
        "romanesque": "Ensambla automáticamente arcos románicos de hasta 6 metros de ancho; las curvas más anchas también crecen en altura. Las filas más largas comienzan un arco nuevo.",
        "modern": "Ensambla automáticamente arcos modernos de hasta 6 metros de ancho; las curvas más anchas también crecen en altura. Las filas más largas comienzan un arco nuevo.",
        "gothic": "Ensambla automáticamente arcos góticos de hasta 6 metros de ancho; las curvas más anchas también crecen en altura. Las filas más largas comienzan un arco nuevo.",
        "georgian": "Ensambla automáticamente arcos georgianos de hasta 6 metros de ancho; las curvas más anchas también crecen en altura. Las filas más largas comienzan un arco nuevo.",
        "frame": "Utiliza un marco georgiano con pilastras decoradas.",
        "width": "Usa el palo de depuración para limitar el ancho a 1–5 metros.",
    },
}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def encoded(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def target_id(source_id: str) -> str:
    if not source_id.endswith(SOURCE_SUFFIX):
        raise ValueError(f"Unexpected Modern arch ID: {source_id}")
    return source_id.removesuffix(SOURCE_SUFFIX) + TARGET_SUFFIX


def modern_ids(repo: Path) -> list[str]:
    result = sorted(path.stem for path in (repo / ASSETS / "blockstates").glob("*_arch_modern.json"))
    if len(result) != 162:
        raise ValueError(f"Expected 162 Modern arch IDs, found {len(result)}")
    return result


def language_bytes(repo: Path, filename: str, source_ids: list[str]) -> bytes:
    path = repo / ASSETS / "lang" / filename
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    document = json.loads(text)
    source_name, profile_name = PROFILE_NAMES[filename]
    names = {}
    for source_id in source_ids:
        source_key = "block.erydon." + source_id
        source_value = document[source_key]
        if source_name not in source_value:
            raise ValueError(f"Missing profile name in {filename}: {source_key}")
        names["block.erydon." + target_id(source_id)] = source_value.replace(source_name, profile_name)
    tooltips = ARCH_TOOLTIPS[filename]
    updates = dict(names)
    for style in ("romanesque", "modern", "gothic", "georgian"):
        updates[f"tooltip.erydon.family.arch_{style}.1"] = tooltips[style]
    updates["tooltip.erydon.family.arch_georgian.2"] = tooltips["frame"]
    updates["tooltip.erydon.family.arch_georgian.3"] = tooltips["width"]
    existing = set(document)
    newline = "\r\n" if b"\r\n" in raw else "\n"
    result = []
    for line in text.splitlines(keepends=True):
        match = re.match(r'^(\s*)"([^"\\]+)"\s*:', line)
        if not match:
            result.append(line)
            continue
        indentation, key = match.groups()
        if key in updates:
            tail = "," if line.rstrip().endswith(",") else ""
            line = f'{indentation}"{key}": {json.dumps(updates[key], ensure_ascii=False)}{tail}{newline}'
        result.append(line)
        if key.startswith("block.erydon.") and key.removeprefix("block.erydon.") in source_ids:
            new_key = "block.erydon." + target_id(key.removeprefix("block.erydon."))
            if new_key not in existing:
                result.append(f'{indentation}"{new_key}": {json.dumps(names[new_key], ensure_ascii=False)},{newline}')
        if key == "tooltip.erydon.family.arch_modern.3":
            for index in (1, 2, 3):
                new_key = f"tooltip.erydon.family.arch_georgian.{index}"
                if new_key not in existing:
                    result.append(f'{indentation}"{new_key}": {json.dumps(updates[new_key], ensure_ascii=False)},{newline}')
    generated = "".join(result)
    readback = json.loads(generated)
    for key, value in updates.items():
        if readback.get(key) != value:
            raise ValueError(f"Missing Georgian arch language binding: {filename}: {key}")
    return generated.encode("utf-8")


def source_ctm_rules(repo: Path, source_ids: list[str]) -> dict[str, dict[str, str]]:
    patterns = {source_id: re.compile(r"(?<![a-z0-9_])erydon:" + re.escape(source_id) + r"(?![a-z0-9_])")
                for source_id in source_ids}
    rules = {}
    for path in sorted((repo / CTM).rglob("*.properties")):
        raw = path.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            raise ValueError(f"CTM properties have a UTF-8 BOM: {path.relative_to(repo)}")
        text = raw.decode("utf-8")
        # Read Java-properties line continuations before selecting block matches.
        joined = re.sub(r"\\\r?\n\s*", " ", text)
        fields = {}
        for line in joined.splitlines():
            if "=" in line and not line.lstrip().startswith(("#", "!")):
                key, value = line.split("=", 1)
                fields[key.strip()] = value.strip()
        matched = fields.get("matchBlocks", "")
        for source_id, pattern in patterns.items():
            if not pattern.search(matched):
                continue
            if source_id in rules:
                raise ValueError(f"Duplicate native CTM rule for {source_id}")
            if fields.get("method") != "repeat" or fields.get("width") != "6" or fields.get("height") != "6":
                raise ValueError(f"Unexpected native arch CTM method: {path.relative_to(repo)}")
            if not fields.get("tiles"):
                raise ValueError(f"Missing native arch CTM tiles: {path.relative_to(repo)}")
            rules[source_id] = fields
    if set(rules) != set(source_ids):
        raise ValueError("Modern arch CTM coverage incomplete: " + ", ".join(sorted(set(source_ids) - set(rules))))
    return rules


def expected_outputs(repo: Path) -> dict[Path, bytes]:
    source_ids = modern_ids(repo)
    outputs = {}
    for source_id in source_ids:
        block_id = target_id(source_id)
        state = load(repo / ASSETS / "blockstates" / f"{source_id}.json")
        outputs[ASSETS / "blockstates" / f"{block_id}.json"] = encoded(
            json.loads(json.dumps(state).replace(SOURCE_SUFFIX, TARGET_SUFFIX)))
        wrapped = load(repo / ASSETS / "models/block/internal/wrapped" / f"{source_id}.json")
        outputs[ASSETS / "models/block/internal/wrapped" / f"{block_id}.json"] = encoded(copy.deepcopy(wrapped))
        # Vanilla JSON parents cannot be raw UnbakedModels. Inherit only the
        # JSON display anchor; the family wrapper emits the material's raw icon.
        outputs[ASSETS / "models/item" / f"{block_id}.json"] = encoded(
            {"parent": "erydon:block/arch/georgian/arch_georgian_icon",
             "textures": {"stone": wrapped["textures"]["particle"]}})
    for filename in PROFILE_NAMES:
        outputs[ASSETS / "lang" / filename] = language_bytes(repo, filename, source_ids)
    for source_id, fields in source_ctm_rules(repo, source_ids).items():
        block_id = target_id(source_id)
        lines = ["method=repeat", "width=6", "height=6", "tiles=" + fields["tiles"],
                 "matchBlocks=erydon:" + block_id, "faces=all", "connect=block", "innerSeams=true", "priority=10"]
        outputs[CTM / "arch_georgian" / f"a_{block_id}.properties"] = ("\n".join(lines) + "\n").encode("utf-8")
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = expected_outputs(ROOT)
    changed = []
    for relative, content in sorted(outputs.items()):
        path = ROOT / relative
        if path.exists() and path.read_bytes() == content:
            continue
        changed.append(relative.as_posix())
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(json.dumps({"georgian_arch_ids": 162, "expected_files": len(outputs), "changed": len(changed),
                      "paths": changed[:10] if args.check else None}, ensure_ascii=False))
    return int(args.check and bool(changed))


if __name__ == "__main__":
    raise SystemExit(main())
