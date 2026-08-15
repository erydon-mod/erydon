#!/usr/bin/env python3
"""Normalize and integrate ERYDON's Georgian and Gothic alcove authoring assets.

The Blockbench-authored Georgian/Gothic top and triple-width components, plus
the Gothic inventory icon, are the source of truth for their geometry. The
generator removes explicit face UVs, applies the neutral stone texture binding,
and otherwise preserves those models. Gothic single/double side, back, and base
geometry is reused from the Georgian alcove. The script also
creates the matching model anchors, blockstates, item models, language entries,
tags, and CTM matchBlocks entries for every registered Georgian material peer.

Run from any directory:
    python tools/generate_gothic_alcove.py
    python tools/generate_gothic_alcove.py --check
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path


TOOLS_ROOT = Path(__file__).resolve().parent
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

import model_raw_uv_safety as raw_uv


BASE_MODEL_SUFFIXES = (
    "back",
    "sides",
    "base",
    "top",
    "icon",
    "double_side_left",
    "double_side_right",
    "double_top_left",
    "double_top_right",
)

TRIPLE_MODEL_SUFFIXES = (
    "triple_side_left",
    "triple_side_center",
    "triple_side_right",
    "triple_top_left",
    "triple_top_center",
    "triple_top_right",
)

MODEL_SUFFIXES = BASE_MODEL_SUFFIXES + TRIPLE_MODEL_SUFFIXES

COPIED_AUTHORING_MODELS = {
    "alcove_gothic_single_back.json": "alcove_georgian_single_back.json",
    "alcove_gothic_single_sides.json": "alcove_georgian_single_sides.json",
    "alcove_gothic_single_base.json": "alcove_georgian_single_base.json",
    "alcove_gothic_double_side_left.json": "alcove_georgian_double_side_left.json",
    "alcove_gothic_double_side_right.json": "alcove_georgian_double_side_right.json",
}

GOTHIC_MANUAL_AUTHORING_MODELS = (
    "alcove_gothic_single_top.json",
    "alcove_gothic_double_top_left.json",
    "alcove_gothic_double_top_right.json",
    "alcove_gothic_triple_side_left.json",
    "alcove_gothic_triple_side_center.json",
    "alcove_gothic_triple_side_right.json",
    "alcove_gothic_triple_top_left.json",
    "alcove_gothic_triple_top_center.json",
    "alcove_gothic_triple_top_right.json",
    "alcove_gothic_icon.json",
)

GEORGIAN_TRIPLE_AUTHORING_MODELS = tuple(
    f"alcove_georgian_{suffix}.json" for suffix in TRIPLE_MODEL_SUFFIXES
)

MANUAL_AUTHORING_MODELS = (
    GOTHIC_MANUAL_AUTHORING_MODELS + GEORGIAN_TRIPLE_AUTHORING_MODELS
)

STONE_TEXTURE = "erydon:block/aganite_block"

LANGUAGE_PROFILE_NAMES = {
    "de_de.json": ("Georgianische Nische", "Gotische Nische"),
    "en_us.json": ("Georgian Alcove", "Gothic Alcove"),
    "es_es.json": ("Nicho georgiano", "Nicho gótico"),
}


def _normalize_manual_authoring_model(model: dict) -> dict:
    """Apply the raw-loader texture contract without changing model geometry."""
    result = copy.deepcopy(model)
    result["textures"] = {
        "particle": STONE_TEXTURE,
        "stone": STONE_TEXTURE,
    }
    for element in result.get("elements", []):
        for face in element.get("faces", {}).values():
            face.pop("uv", None)
            face["texture"] = "#stone"
    return _repair_rotated_implicit_uvs(result)


def _repair_rotated_implicit_uvs(model: dict) -> dict:
    """Apply the raw UV auditor's deterministic, geometry-preserving offsets."""
    result = copy.deepcopy(model)
    findings, _ = raw_uv._audit_document(result, "generated-alcove.json", "0" * 64)
    for finding in findings:
        if not finding["rotated"]:
            continue
        if (
            finding["uvSource"] != "implicit"
            or finding["operationClass"] != "implicit_uniform_offset"
            or finding["proposedOffset"] is None
        ):
            raise ValueError(
                "Alcove has a rotated UV finding that cannot be repaired "
                f"without changing geometry: {finding['facePointer']}"
            )
        face = result["elements"][finding["elementIndex"]]["faces"][finding["face"]]
        face[raw_uv.OFFSET_KEY] = finding["proposedOffset"]

    _, post_counts = raw_uv._audit_document(
        result, "generated-alcove.json", "0" * 64
    )
    if post_counts.get("rotatedOutOfRangeFaces", 0):
        raise ValueError("Alcove UV repair did not converge")
    return result


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _load_json(path: Path) -> object:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def _family_text(text: str) -> str:
    return text.replace("/georgian/", "/gothic/").replace(
        "alcove_georgian", "alcove_gothic"
    )


def _gothic_id(georgian_id: str) -> str:
    return georgian_id.replace("_alcove_georgian", "_alcove_gothic")


def _split_aged(block_id: str) -> tuple[str, bool]:
    if block_id.endswith("_aged"):
        return block_id[: -len("_aged")], True
    if "_aged_" in block_id:
        return block_id.replace("_aged_", "_", 1), True
    return block_id, False


def _component_filename(block_id: str, suffix: str) -> str:
    base, aged = _split_aged(block_id)
    return f"{base}_{suffix}{'_aged' if aged else ''}.json"


def _registered_georgian_ids(resources: Path) -> list[str]:
    blockstates = resources / "assets" / "erydon" / "blockstates"
    pattern = re.compile(r"^(.+_alcove_georgian(?:_aged)?)\.json$")
    result = []
    for path in sorted(blockstates.glob("*_alcove_georgian*.json")):
        match = pattern.fullmatch(path.name)
        if match:
            result.append(match.group(1))
    if len(result) != 162:
        raise ValueError(
            f"Expected 162 Georgian alcove blockstates, found {len(result)}"
        )
    return result


def _expected_new_files(repo_root: Path, georgian_ids: list[str]) -> dict[Path, bytes]:
    resources = repo_root / "src" / "main" / "resources"
    assets = resources / "assets" / "erydon"
    authoring = assets / "authoring_models" / "block" / "alcove"
    expected: dict[Path, bytes] = {}

    for gothic_name, georgian_name in COPIED_AUTHORING_MODELS.items():
        expected[authoring / gothic_name] = (authoring / georgian_name).read_bytes()

    # These restored Blockbench models are authoritative. Normalization is
    # deliberately face-only so generator runs cannot rebuild their geometry
    # from the older Georgian-derived quadratic formula.
    for filename in MANUAL_AUTHORING_MODELS:
        target = authoring / filename
        if not target.is_file():
            raise ValueError(f"Missing manual alcove authoring model: {target}")
        expected[target] = _json_bytes(
            _normalize_manual_authoring_model(_load_json(target))
        )

    component_source = assets / "models" / "block" / "alcove" / "georgian"
    component_target = assets / "models" / "block" / "alcove" / "gothic"
    for suffix in BASE_MODEL_SUFFIXES:
        source = component_source / f"alcove_georgian_{suffix}.json"
        target = component_target / f"alcove_gothic_{suffix}.json"
        expected[target] = _family_text(source.read_text(encoding="utf-8")).encode("utf-8")

    generic_triple_source = component_source / "alcove_georgian_double_top_left.json"
    generic_georgian_triple_text = generic_triple_source.read_text(encoding="utf-8")
    for suffix in TRIPLE_MODEL_SUFFIXES:
        target = component_source / f"alcove_georgian_{suffix}.json"
        expected[target] = generic_georgian_triple_text.replace(
            "_double_top_left", f"_{suffix}"
        ).encode("utf-8")

    generic_triple_text = _family_text(generic_georgian_triple_text)
    for suffix in TRIPLE_MODEL_SUFFIXES:
        target = component_target / f"alcove_gothic_{suffix}.json"
        expected[target] = generic_triple_text.replace(
            "_double_top_left", f"_{suffix}"
        ).encode("utf-8")

    blockstates = assets / "blockstates"
    wrapped = assets / "models" / "block" / "internal" / "wrapped"
    item_models = assets / "models" / "item"
    for georgian_id in georgian_ids:
        gothic_id = _gothic_id(georgian_id)
        for source_root, target_root in (
            (blockstates, blockstates),
            (wrapped, wrapped),
            (item_models, item_models),
        ):
            source = source_root / f"{georgian_id}.json"
            target = target_root / f"{gothic_id}.json"
            expected[target] = _family_text(source.read_text(encoding="utf-8")).encode("utf-8")

        for suffix in BASE_MODEL_SUFFIXES:
            source = component_source / _component_filename(georgian_id, suffix)
            target = component_target / _component_filename(gothic_id, suffix)
            expected[target] = _family_text(source.read_text(encoding="utf-8")).encode("utf-8")

        triple_source = component_source / _component_filename(
            georgian_id, "double_top_left"
        )
        georgian_triple_text = triple_source.read_text(encoding="utf-8")
        for suffix in TRIPLE_MODEL_SUFFIXES:
            target = component_source / _component_filename(georgian_id, suffix)
            expected[target] = georgian_triple_text.replace(
                "_double_top_left", f"_{suffix}"
            ).encode("utf-8")

        triple_text = _family_text(georgian_triple_text)
        for suffix in TRIPLE_MODEL_SUFFIXES:
            target = component_target / _component_filename(gothic_id, suffix)
            expected[target] = triple_text.replace(
                "_double_top_left", f"_{suffix}"
            ).encode("utf-8")

    return expected


def _tag_updates(
    repo_root: Path,
    georgian_ids: list[str],
) -> dict[Path, bytes]:
    data_root = repo_root / "src" / "main" / "resources" / "data"
    erydon_tags = data_root / "erydon" / "tags" / "blocks"
    mapping = {
        f"erydon:{source}": f"erydon:{_gothic_id(source)}"
        for source in georgian_ids
    }
    updates: dict[Path, bytes] = {}

    georgian_tag = erydon_tags / "alcove_georgian.json"
    gothic_tag = erydon_tags / "alcove_gothic.json"
    updates[gothic_tag] = _json_bytes(
        {
            **_load_json(georgian_tag),
            "values": [mapping[value] for value in _load_json(georgian_tag)["values"]],
        }
    )

    excluded = {georgian_tag.resolve(), (erydon_tags / "georgian.json").resolve()}
    for path in sorted(data_root.rglob("*.json")):
        if path.resolve() in excluded:
            continue
        document = _load_json(path)
        if not isinstance(document, dict) or not isinstance(document.get("values"), list):
            continue
        values = document["values"]
        existing = {value for value in values if isinstance(value, str)}
        changed = False
        expanded = []
        for value in values:
            expanded.append(value)
            gothic_value = mapping.get(value)
            if gothic_value is not None and gothic_value not in existing:
                expanded.append(gothic_value)
                existing.add(gothic_value)
                changed = True
        if changed:
            document["values"] = expanded
            updates[path] = _json_bytes(document)

    gothic_style = erydon_tags / "gothic.json"
    document = _load_json(gothic_style)
    values = document["values"]
    existing = set(values)
    additions = [mapping[f"erydon:{source}"] for source in georgian_ids]
    if any(value not in existing for value in additions):
        document["values"] = values + [value for value in additions if value not in existing]
        updates[gothic_style] = _json_bytes(document)

    return updates


def _language_updates(
    repo_root: Path,
    georgian_ids: list[str],
) -> dict[Path, bytes]:
    lang_root = (
        repo_root / "src" / "main" / "resources" / "assets" / "erydon" / "lang"
    )
    mapping = {source: _gothic_id(source) for source in georgian_ids}
    updates: dict[Path, bytes] = {}

    for filename, (source_name, target_name) in LANGUAGE_PROFILE_NAMES.items():
        path = lang_root / filename
        text = path.read_text(encoding="utf-8")
        existing_keys = set(re.findall(r'^\s*"([^"]+)"\s*:', text, flags=re.MULTILINE))
        lines = text.splitlines()
        expanded: list[str] = []
        inserted_blocks = 0
        inserted_tooltips = 0
        for line in lines:
            expanded.append(line)
            block_match = re.match(
                r'^(\s*)"block\.erydon\.([^"]+_alcove_georgian(?:_aged)?)"\s*:',
                line,
            )
            if block_match:
                source_id = block_match.group(2)
                target_id = mapping.get(source_id)
                target_key = f"block.erydon.{target_id}" if target_id else None
                if target_key and target_key not in existing_keys:
                    duplicated = line.replace(source_id, target_id, 1).replace(
                        source_name, target_name
                    )
                    if duplicated == line:
                        raise ValueError(
                            f"Could not translate Gothic profile name in {path.name}: {line.strip()}"
                        )
                    expanded.append(duplicated)
                    existing_keys.add(target_key)
                    inserted_blocks += 1
                continue

            tooltip_match = re.match(
                r'^(\s*)"(tooltip\.erydon\.family\.alcove_georgian\.[^"]+)"\s*:',
                line,
            )
            if tooltip_match:
                source_key = tooltip_match.group(2)
                target_key = source_key.replace("alcove_georgian", "alcove_gothic")
                if target_key not in existing_keys:
                    expanded.append(line.replace(source_key, target_key, 1))
                    existing_keys.add(target_key)
                    inserted_tooltips += 1

        new_text = "\n".join(expanded) + "\n"
        if new_text != text:
            if inserted_blocks not in {0, len(georgian_ids)}:
                raise ValueError(
                    f"Expected {len(georgian_ids)} Gothic block names in {filename}, inserted {inserted_blocks}"
                )
            if inserted_tooltips not in {0, 2}:
                raise ValueError(
                    f"Expected two Gothic tooltip lines in {filename}, inserted {inserted_tooltips}"
                )
            updates[path] = new_text.encode("utf-8")

    return updates


def _contains_identifier(text: str, identifier: str) -> bool:
    return re.search(
        rf"(?<![a-z0-9_]){re.escape(identifier)}(?![a-z0-9_])",
        text,
    ) is not None


def _add_ctm_matches(text: str, mapping: dict[str, str]) -> tuple[str, int]:
    lines = text.splitlines()
    existing = {
        target for target in mapping.values() if _contains_identifier(text, target)
    }
    expanded: list[str] = []
    inserted = 0
    for line in lines:
        expanded.append(line)
        for source, target in mapping.items():
            if target in existing or not _contains_identifier(line, source):
                continue
            trimmed = expanded[-1].rstrip()
            continued = trimmed.endswith("\\")
            if not continued:
                expanded[-1] = trimmed + " \\"
            indentation = re.match(r"^\s*", line).group(0)
            expanded.append(indentation + target + (" \\" if continued else ""))
            existing.add(target)
            inserted += 1
    return "\n".join(expanded) + "\n", inserted


def _ctm_updates(
    repo_root: Path,
    georgian_ids: list[str],
) -> dict[Path, bytes]:
    mapping = {
        f"erydon:{source}": f"erydon:{_gothic_id(source)}"
        for source in georgian_ids
    }
    roots = (
        (
            repo_root
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "minecraft"
        / "optifine"
        / "ctm",
            True,
        ),
        (
            repo_root
        / "run-dev"
        / "resourcepacks"
        / "erydon-rp-16x-lite"
        / "assets"
        / "minecraft"
        / "optifine"
        / "ctm",
            False,
        ),
        (
            repo_root
        / "run-dev"
        / "resourcepacks"
        / "erydon-rp-64x-pbr"
        / "assets"
        / "minecraft"
        / "optifine"
        / "ctm",
            False,
        ),
    )
    updates: dict[Path, bytes] = {}

    for root, require_complete in roots:
        if not root.exists():
            continue
        source_matches = set()
        for path in sorted(root.rglob("*.properties")):
            raw = path.read_bytes()
            if raw.startswith(b"\xef\xbb\xbf"):
                raise ValueError(f"CTM properties file has a UTF-8 BOM: {path}")
            text = raw.decode("utf-8")
            for source in mapping:
                if _contains_identifier(text, source):
                    source_matches.add(source)
            new_text, inserted = _add_ctm_matches(text, mapping)
            if inserted:
                updates[path] = new_text.encode("utf-8")

        if require_complete and len(source_matches) != len(mapping):
            missing = sorted(set(mapping) - source_matches)
            raise ValueError(
                f"CTM root {root} has Georgian alcove matches but is missing "
                f"{len(missing)} registered IDs (first: {missing[:3]})"
            )

    return updates


def _merge_updates(target: dict[Path, bytes], additions: dict[Path, bytes]) -> None:
    for path, content in additions.items():
        previous = target.get(path)
        if previous is not None and previous != content:
            raise ValueError(f"Conflicting generated content for {path}")
        target[path] = content


def generate(repo_root: Path, *, check: bool) -> list[Path]:
    repo_root = repo_root.resolve()
    resources = repo_root / "src" / "main" / "resources"
    if not (repo_root / "build.gradle").is_file() or not resources.is_dir():
        raise ValueError(f"Not an ERYDON repository root: {repo_root}")

    georgian_ids = _registered_georgian_ids(resources)
    expected = _expected_new_files(repo_root, georgian_ids)
    _merge_updates(expected, _tag_updates(repo_root, georgian_ids))
    _merge_updates(expected, _language_updates(repo_root, georgian_ids))
    _merge_updates(expected, _ctm_updates(repo_root, georgian_ids))

    changed = []
    for path, content in sorted(expected.items(), key=lambda item: str(item[0])):
        current = path.read_bytes() if path.exists() else None
        if current == content:
            continue
        changed.append(path)
        if not check:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("wb") as handle:
                handle.write(content)
    return changed


def _parse_args() -> argparse.Namespace:
    default_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=default_root,
        help="ERYDON repository root (defaults to the parent of tools/)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify generated files without changing the worktree",
    )
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    changed = generate(args.repo_root, check=args.check)
    if args.check:
        if changed:
            print(f"Alcove generation is stale: {len(changed)} file(s) differ")
            for path in changed[:20]:
                print(f"  {path}")
            if len(changed) > 20:
                print(f"  ... and {len(changed) - 20} more")
            return 1
        print("Alcove generation is current")
        return 0

    print(f"Generated or updated {len(changed)} alcove file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
