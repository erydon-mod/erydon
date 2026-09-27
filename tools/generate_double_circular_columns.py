"""Generate the public 2x2 circular-column variants from the current 1x models."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"
ASSETS = ROOT / "assets/erydon"
DATA = ROOT / "data/erydon"
BLOCKSTATES = ASSETS / "blockstates"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    existing = path.read_bytes() if path.exists() else b""
    if b"\r\n" in existing:
        content = content.replace("\n", "\r\n")
    if existing != content.encode("utf-8"):
        path.write_bytes(content.encode("utf-8"))


def add_lang_entry(path: Path, source: str, target: str, suffix: str) -> None:
    content = path.read_bytes().decode("utf-8")
    source_key = f"block.erydon.{source}"
    target_key = f"block.erydon.{target}"
    if f'"{target_key}"' in content:
        return
    lines = content.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if f'"{source_key}"' not in line:
            continue
        match = re.search(r'^(\s*)"[^"]+":\s*("(?:\\.|[^"])*")(,?)(\r?\n)$', line)
        if not match:
            raise ValueError(f"Unexpected language format: {path}:{index + 1}")
        label = json.loads(match.group(2)) + suffix
        comma = match.group(3)
        if not comma:
            lines[index] = line.rstrip("\r\n") + "," + match.group(4)
        lines.insert(index + 1, f'{match.group(1)}"{target_key}": {json.dumps(label, ensure_ascii=False)}{comma}{match.group(4)}')
        path.write_bytes("".join(lines).encode("utf-8"))
        return
    raise ValueError(f"Missing source language entry {source_key} in {path}")


def add_to_tags(source: str, target: str) -> None:
    for path in (DATA / "tags/blocks").glob("*.json"):
        content = path.read_bytes().decode("utf-8")
        old_id, new_id = f"erydon:{source}", f"erydon:{target}"
        if old_id not in content or new_id in content:
            continue
        payload = json.loads(content)
        values = payload.get("values", [])
        if old_id not in values:
            continue
        values.insert(values.index(old_id) + 1, new_id)
        write_json(path, payload)


def add_to_ctm(source: str, target: str) -> None:
    legacy = source
    match = re.fullmatch(r"([a-z]+)_aged_column_circular", source)
    if match:
        legacy = f"{match.group(1)}_column_circular_aged"
    for path in (ROOT / "assets/minecraft/optifine/ctm").rglob("*.properties"):
        content = path.read_bytes().decode("utf-8-sig")
        if f"erydon:{target}" in content or f"erydon:{legacy}" not in content:
            continue
        newline = "\r\n" if "\r\n" in content else "\n"
        replacement = f"erydon:{legacy} \\{newline}  erydon:{target}"
        content = content.replace(f"erydon:{legacy}", replacement, 1)
        path.write_bytes(content.encode("utf-8"))


def generate() -> None:
    sources = sorted(path.stem for path in BLOCKSTATES.glob("*_column_circular.json"))
    if len(sources) != 54:
        raise RuntimeError(f"Expected 54 circular material variants, found {len(sources)}")
    for source in sources:
        target = source + "_double"
        write_json(BLOCKSTATES / f"{target}.json", {
            "multipart": [{"apply": {"model": f"erydon:block/internal/wrapped/{target}"}}]
        })
        old_blockstate = json.loads((BLOCKSTATES / f"{source}.json").read_text(encoding="utf-8"))
        old_model = old_blockstate["multipart"][0]["apply"]["model"].split(":", 1)[1]
        old_wrapper = json.loads((ASSETS / f"models/{old_model}.json").read_text(encoding="utf-8"))
        write_json(ASSETS / f"models/block/internal/wrapped/{target}.json", old_wrapper)
        source_item_path = ASSETS / f"models/item/{source}.json"
        source_item = json.loads(source_item_path.read_text(encoding="utf-8"))
        source_item["display"] = {"gui": {"rotation": [20, 45, 0], "scale": [0.55, 0.55, 0.55]}}
        write_json(source_item_path, source_item)
        write_json(ASSETS / f"models/item/{target}.json", {
            "parent": f"erydon:item/{source}",
            "display": {"gui": {"rotation": [20, 45, 0], "scale": [0.65, 0.65, 0.65]}}
        })
        write_json(DATA / f"loot_tables/blocks/{target}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"erydon:{target}",
                "conditions": [{"condition": "minecraft:block_state_property", "block": f"erydon:{target}",
                                "properties": {"part_x": "0", "part_z": "0"}}]}],
                "conditions": [{"condition": "minecraft:survives_explosion"}]}]
        })
        add_to_tags(source, target)
        for locale in ("en_us", "de_de", "es_es"):
            add_lang_entry(ASSETS / f"lang/{locale}.json", source, target, " (2×2)")
        add_to_ctm(source, target)
    print(f"Generated {len(sources)} double circular columns")


if __name__ == "__main__":
    generate()
