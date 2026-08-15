#!/usr/bin/env python3
"""Generate and verify Georgian wall pier, stub, wrapper, and multipart assets."""

from __future__ import annotations

import argparse
import copy
import csv
import itertools
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BLOCKSTATES = ROOT / "src/main/resources/assets/erydon/blockstates"
MODELS = ROOT / "src/main/resources/assets/erydon/models/block/wall/georgian"
PIER_MASTER = MODELS / "wall_georgian_pier.json"
STUB_MASTER = MODELS / "wall_georgian_pier_stub.json"
OLD_CORNER_MASTER = MODELS / "wall_georgian_corner.json"
ID_MIGRATION_MANIFEST = ROOT / "src/main/resources/data/erydon/id_migration.tsv"

CONNECTED = "low|tall"
CARDINAL_DIRECTIONS = ("north", "east", "south", "west")
DIAGONAL_PROPERTIES = ("north_east", "south_east", "south_west", "north_west")
DIAGONAL_TURN_PAIRS = (
    ("north_east", "south_east"),
    ("south_east", "south_west"),
    ("south_west", "north_west"),
    ("north_west", "north_east"),
)
SELECTOR_LEAVES = 34


def fail(message: str) -> None:
    raise SystemExit(message)


def load_legacy_resource_paths() -> dict[str, str]:
    try:
        with ID_MIGRATION_MANIFEST.open("r", encoding="utf-8", newline="") as stream:
            rows = csv.DictReader(stream, delimiter="\t")
            return {
                row["canonical_path"]: row["old_path"]
                for row in rows
                if row["mode"] == "PERMANENT_ALIAS"
            }
    except (OSError, KeyError, csv.Error) as exc:
        fail(f"Cannot read ID migration manifest: {exc}")


LEGACY_RESOURCE_PATHS = load_legacy_resource_paths()


def legacy_resource_path(block_id: str) -> str:
    return LEGACY_RESOURCE_PATHS.get(block_id, block_id)

POST_WHEN = {
    "up": "true",
    **{direction: "none|low" for direction in CARDINAL_DIRECTIONS},
}
ISOLATED_PIER_WHEN = {
    "up": "false",
    **{direction: "none" for direction in CARDINAL_DIRECTIONS},
    **{diagonal: "false" for diagonal in DIAGONAL_PROPERTIES},
}
PIER_WHEN = {
    "OR": (
        [{direction: "tall"} for direction in CARDINAL_DIRECTIONS]
        + [
            {
                "up": "false",
                **{direction: "none" for direction in CARDINAL_DIRECTIONS},
                first: "true",
                second: "true",
            }
            for first, second in DIAGONAL_TURN_PAIRS
        ]
        + [ISOLATED_PIER_WHEN]
    )
}
def display_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path.resolve())


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_bytes().decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        fail(f"Cannot read {display_path(path)}: {exc}")
    if not isinstance(value, dict):
        fail(f"Expected a JSON object in {display_path(path)}")
    return value


def flatten_children(node: dict[str, Any]) -> list[int]:
    result: list[int] = []
    for child in node.get("children", []):
        if isinstance(child, int):
            result.append(child)
        elif isinstance(child, dict):
            result.extend(flatten_children(child))
        else:
            fail("Georgian wall source has an invalid group child")
    return result


def remap_group(node: dict[str, Any], index_map: dict[int, int]) -> dict[str, Any]:
    result = copy.deepcopy(node)
    children: list[Any] = []
    for child in node.get("children", []):
        if isinstance(child, int):
            if child not in index_map:
                fail(f"Group references element {child} outside its own membership")
            children.append(index_map[child])
        elif isinstance(child, dict):
            children.append(remap_group(child, index_map))
        else:
            fail("Georgian wall source has an invalid group child")
    result["children"] = children
    return result


def is_compact(value: Any) -> bool:
    if isinstance(value, list):
        return all(not isinstance(item, (dict, list)) for item in value)
    if isinstance(value, dict):
        return all(
            not isinstance(item, dict)
            and (not isinstance(item, list) or is_compact(item))
            for item in value.values()
        )
    return True


def render_json(value: Any, level: int = 0) -> str:
    if is_compact(value):
        return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))
    indent = "\t" * level
    child_indent = "\t" * (level + 1)
    if isinstance(value, dict):
        parts = [
            f"{child_indent}{json.dumps(key)}: {render_json(child, level + 1)}"
            for key, child in value.items()
        ]
        return "{\n" + ",\n".join(parts) + f"\n{indent}}}"
    if isinstance(value, list):
        parts = [f"{child_indent}{render_json(child, level + 1)}" for child in value]
        return "[\n" + ",\n".join(parts) + f"\n{indent}]"
    return json.dumps(value, ensure_ascii=False)


def extract_master(
    source_path: Path,
    target_path: Path,
    group_name: str,
    expected_elements: int,
    kind: str,
) -> None:
    source = load_json(source_path)
    elements = source.get("elements")
    groups = source.get("groups")
    if not isinstance(elements, list) or not isinstance(groups, list):
        fail(f"{kind.title()} source must contain elements and groups")

    matches = [
        group for group in groups
        if isinstance(group, dict) and group.get("name") == group_name
    ]
    if len(matches) != 1:
        fail(f"{kind.title()} source must contain exactly one {group_name} group")
    group = matches[0]
    source_indices = flatten_children(group)
    if len(source_indices) != expected_elements or len(set(source_indices)) != expected_elements:
        fail(f"{kind.title()} source group must contain exactly {expected_elements} unique elements")
    if any(index < 0 or index >= len(elements) for index in source_indices):
        fail(f"{kind.title()} source group contains an out-of-range element index")

    extracted = [copy.deepcopy(elements[index]) for index in source_indices]
    for element in extracted:
        faces = element.get("faces")
        if not isinstance(faces, dict):
            fail(f"{kind.title()} source contains an element without faces")
        for face in faces.values():
            if not isinstance(face, dict):
                fail(f"{kind.title()} source contains an invalid face")
            face.pop("uv", None)
            face["texture"] = "#wall"

    if kind == "pier":
        lower_planes = [12.255, 12.257, 12.254, 12.253, 12.256, 12.252]
        upper_planes = [15.994, 15.997, 15.995, 15.998, 15.996, 15.999, 15.993, 16]
        if any(extracted[index].get("from", [None, None])[1] != 12.25 for index in range(6, 12)):
            fail("Pier source lower circular geometry no longer matches the approved overlap set")
        if any(extracted[index].get("to", [None, None])[1] != 16 for index in range(12, 20)):
            fail("Pier source upper circular geometry no longer matches the approved overlap set")
        for index, plane in enumerate(lower_planes, start=6):
            extracted[index]["from"][1] = plane
        for index, plane in enumerate(upper_planes, start=12):
            extracted[index]["to"][1] = plane
    elif kind != "stub":
        fail(f"Unknown Georgian wall master kind: {kind}")

    index_map = {source_index: target_index for target_index, source_index in enumerate(source_indices)}
    master: dict[str, Any] = {}
    for metadata_key in ("format_version", "credit"):
        if metadata_key in source:
            master[metadata_key] = source[metadata_key]
    master["elements"] = extracted
    master["groups"] = [remap_group(group, index_map)]
    target_path.write_bytes((render_json(master) + "\n").encode("utf-8"))


def validate_master(
    path: Path,
    group_name: str,
    expected_elements: int,
    expected_faces: int,
    expected_face_rotations: int,
    expected_cullfaces: int,
    kind: str,
) -> None:
    if not path.is_file():
        fail(f"Missing Georgian wall {kind} master: {display_path(path)}")
    model = load_json(path)
    elements = model.get("elements")
    groups = model.get("groups")
    if not isinstance(elements, list) or len(elements) != expected_elements:
        fail(f"{kind.title()} master must contain the supplied {expected_elements} elements")
    if not isinstance(groups, list) or len(groups) != 1 or groups[0].get("name") != group_name:
        fail(f"{kind.title()} master must retain one {group_name} group")
    if flatten_children(groups[0]) != list(range(expected_elements)):
        fail(f"{kind.title()} master group membership or element order changed")

    face_count = 0
    face_rotations = 0
    cullfaces = 0
    for index, element in enumerate(elements):
        if not isinstance(element, dict) or not isinstance(element.get("faces"), dict):
            fail(f"{kind.title()} master element {index} has invalid faces")
        for face_name, face in element["faces"].items():
            face_count += 1
            if not isinstance(face, dict) or face.get("texture") != "#wall":
                fail(f"{kind.title()} master element {index} face {face_name} does not use #wall")
            if "uv" in face:
                fail(f"{kind.title()} master element {index} face {face_name} retained an explicit UV")
            face_rotations += int("rotation" in face)
            cullfaces += int("cullface" in face)
    if face_count != expected_faces:
        fail(f"{kind.title()} master face count changed: expected {expected_faces}, found {face_count}")
    if face_rotations != expected_face_rotations:
        fail(f"{kind.title()} master face rotations changed: expected {expected_face_rotations}, found {face_rotations}")
    if cullfaces != expected_cullfaces:
        fail(f"{kind.title()} master cullface count changed: expected {expected_cullfaces}, found {cullfaces}")

    if kind == "pier":
        lower_planes = [elements[index]["from"][1] for index in range(6, 12)]
        upper_planes = [elements[index]["to"][1] for index in range(12, 20)]
        if len(set(lower_planes)) != 6 or min(lower_planes) < 12.252 or max(lower_planes) > 12.257:
            fail("Pier lower circular faces lost their approved micro-stagger")
        if len(set(upper_planes)) != 8 or min(upper_planes) < 15.993 or max(upper_planes) > 16:
            fail("Pier upper circular faces lost their approved micro-stagger")
def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))


def blockstate_bytes(parts: list[dict[str, Any]]) -> bytes:
    lines = ["{", '  "multipart": [']
    for index, part in enumerate(parts):
        lines.extend((
            "    {",
            f'      "when": {compact_json(part["when"])},',
            f'      "apply": {compact_json(part["apply"])}',
            "    }" + ("," if index + 1 < len(parts) else ""),
        ))
    lines.extend(("  ]", "}"))
    return ("\n".join(lines) + "\n").encode("utf-8")


def part_model(part: dict[str, Any]) -> str | None:
    apply = part.get("apply")
    return apply.get("model") if isinstance(apply, dict) else None


def side_key(when: dict[str, Any], block_id: str) -> tuple[str, str]:
    alternatives = when.get("OR")
    conditions = alternatives if isinstance(alternatives, list) else [when]
    found = {
        (direction, shape)
        for condition in conditions
        if isinstance(condition, dict)
        for direction in CARDINAL_DIRECTIONS
        if (shape := condition.get(direction)) in ("low", "tall")
    }
    if len(found) != 1:
        fail(f"Blockstate {block_id} has an ambiguous cardinal side condition")
    return next(iter(found))


def diagonal_key(when: dict[str, Any], block_id: str) -> str:
    alternatives = when.get("OR")
    conditions = alternatives if isinstance(alternatives, list) else [when]
    found = {
        diagonal
        for condition in conditions
        if isinstance(condition, dict)
        for diagonal in DIAGONAL_PROPERTIES
        if condition.get(diagonal) == "true"
    }
    if len(found) != 1:
        fail(f"Blockstate {block_id} has an ambiguous diagonal side condition")
    return next(iter(found))


def expected_side_when(direction: str) -> dict[str, str]:
    condition = {direction: "low"}
    condition.update({
        other: "none|low"
        for other in CARDINAL_DIRECTIONS
        if other != direction
    })
    return condition


def expected_pier_part(block_id: str) -> dict[str, Any]:
    return {
        "when": PIER_WHEN,
        "apply": {"model": f"erydon:block/wall/georgian/{block_id}_pier"},
    }


def expected_stub_parts(block_id: str) -> list[dict[str, Any]]:
    model = f"erydon:block/wall/georgian/{block_id}_pier_stub"
    result: list[dict[str, Any]] = []
    for direction, rotation in zip(CARDINAL_DIRECTIONS, (0, 90, 180, 270)):
        alternatives = []
        for marker in CARDINAL_DIRECTIONS:
            condition = {direction: CONNECTED}
            condition[marker] = "tall"
            alternatives.append(condition)
        apply: dict[str, Any] = {"model": model}
        if rotation:
            apply["y"] = rotation
        apply["uvlock"] = True
        result.append({"when": {"OR": alternatives}, "apply": apply})
    return result


def canonical_parts(state: dict[str, Any], block_id: str) -> list[dict[str, Any]]:
    multipart = state.get("multipart")
    if not isinstance(multipart, list):
        fail(f"Blockstate {block_id} is not multipart")

    post_model = f"erydon:block/wall/georgian/{block_id}_post"
    side_model = f"erydon:block/wall/georgian/{block_id}_side"
    diagonal_model = f"erydon:block/wall/georgian/{block_id}_side_diagonal"
    pier_model = f"erydon:block/wall/georgian/{block_id}_pier"
    stub_model = f"erydon:block/wall/georgian/{block_id}_pier_stub"
    old_corner_model = f"erydon:block/wall/georgian/{block_id}_corner"
    known_models = {post_model, side_model, diagonal_model, pier_model, stub_model, old_corner_model}
    unknown = [model for part in multipart if (model := part_model(part)) not in known_models]
    if unknown:
        fail(f"Blockstate {block_id} contains unexpected multipart models: {unknown}")

    post_parts = [part for part in multipart if part_model(part) == post_model]
    if len(post_parts) != 1 or not isinstance(post_parts[0].get("apply"), dict):
        fail(f"Blockstate {block_id} must contain exactly one post entry")

    side_applies: dict[tuple[str, str], dict[str, Any]] = {}
    for part in multipart:
        if part_model(part) != side_model:
            continue
        when = part.get("when")
        apply = part.get("apply")
        if not isinstance(when, dict) or not isinstance(apply, dict):
            fail(f"Blockstate {block_id} has an invalid cardinal side entry")
        key = side_key(when, block_id)
        if key in side_applies:
            fail(f"Blockstate {block_id} has a duplicate {key} side entry")
        side_applies[key] = copy.deepcopy(apply)
    required_side_keys = {(direction, "low") for direction in CARDINAL_DIRECTIONS}
    transitional_side_keys = required_side_keys | {
        (direction, "tall") for direction in CARDINAL_DIRECTIONS
    }
    if not required_side_keys.issubset(side_applies) or not set(side_applies).issubset(transitional_side_keys):
        fail(f"Blockstate {block_id} has incomplete cardinal side coverage")

    diagonal_applies: dict[str, dict[str, Any]] = {}
    for part in multipart:
        if part_model(part) != diagonal_model:
            continue
        when = part.get("when")
        apply = part.get("apply")
        if not isinstance(when, dict) or not isinstance(apply, dict):
            fail(f"Blockstate {block_id} has an invalid diagonal side entry")
        diagonal = diagonal_key(when, block_id)
        if diagonal in diagonal_applies:
            fail(f"Blockstate {block_id} has a duplicate {diagonal} side entry")
        diagonal_applies[diagonal] = copy.deepcopy(apply)
    if set(diagonal_applies) != set(DIAGONAL_PROPERTIES):
        fail(f"Blockstate {block_id} has incomplete diagonal side coverage")

    parts: list[dict[str, Any]] = [
        {"when": POST_WHEN, "apply": copy.deepcopy(post_parts[0]["apply"])}
    ]
    for direction in CARDINAL_DIRECTIONS:
        parts.append({
            "when": expected_side_when(direction),
            "apply": side_applies[(direction, "low")],
        })
    for diagonal in DIAGONAL_PROPERTIES:
        parts.append({
            "when": {diagonal: "true"},
            "apply": diagonal_applies[diagonal],
        })
    parts.append(expected_pier_part(block_id))
    parts.extend(expected_stub_parts(block_id))
    return parts


def condition_matches(condition: dict[str, Any], state: dict[str, str]) -> bool:
    alternatives = condition.get("OR")
    if alternatives is not None:
        return isinstance(alternatives, list) and any(
            isinstance(alternative, dict) and condition_matches(alternative, state)
            for alternative in alternatives
        )
    return all(
        isinstance(value, str) and state.get(property_name) in value.split("|")
        for property_name, value in condition.items()
    )


def selector_leaf_count(parts: list[dict[str, Any]]) -> int:
    total = 0
    for part in parts:
        alternatives = part["when"].get("OR")
        total += len(alternatives) if isinstance(alternatives, list) else 1
    return total


def validate_model_selection(parts: list[dict[str, Any]], block_id: str) -> None:
    post_model = f"erydon:block/wall/georgian/{block_id}_post"
    side_model = f"erydon:block/wall/georgian/{block_id}_side"
    diagonal_model = f"erydon:block/wall/georgian/{block_id}_side_diagonal"
    pier_model = f"erydon:block/wall/georgian/{block_id}_pier"
    stub_model = f"erydon:block/wall/georgian/{block_id}_pier_stub"
    by_model = {
        model: [part for part in parts if part_model(part) == model]
        for model in (post_model, side_model, diagonal_model, pier_model, stub_model)
    }
    expected_counts = {post_model: 1, side_model: 4, diagonal_model: 4, pier_model: 1, stub_model: 4}
    for model, expected in expected_counts.items():
        if len(by_model[model]) != expected:
            fail(f"Blockstate {block_id} has {len(by_model[model])} entries for {model}, expected {expected}")
    if selector_leaf_count(parts) != SELECTOR_LEAVES:
        fail(f"Blockstate {block_id} selector fan-out changed from the approved {SELECTOR_LEAVES} leaves")

    for values in itertools.product(("none", "low", "tall"), repeat=4):
        for up in ("false", "true"):
            for diagonals in itertools.product(("false", "true"), repeat=4):
                candidate = dict(zip(CARDINAL_DIRECTIONS, values))
                candidate["up"] = up
                candidate.update(zip(DIAGONAL_PROPERTIES, diagonals))
                selected = {
                    model: [part for part in model_parts if condition_matches(part["when"], candidate)]
                    for model, model_parts in by_model.items()
                }

                has_tall = any(value == "tall" for value in values)
                expected_sides = 0 if has_tall else sum(value == "low" for value in values)
                if len(selected[side_model]) != expected_sides:
                    fail(f"Blockstate {block_id} has incorrect side selection for {candidate}")

                expected_diagonals = sum(value == "true" for value in diagonals)
                if len(selected[diagonal_model]) != expected_diagonals:
                    fail(f"Blockstate {block_id} has incorrect diagonal selection for {candidate}")

                diagonal_turn = any(
                    candidate[first] == "true" and candidate[second] == "true"
                    for first, second in DIAGONAL_TURN_PAIRS
                )
                isolated_pier = (
                    up == "false"
                    and all(value == "none" for value in values)
                    and all(value == "false" for value in diagonals)
                )
                expected_pier = has_tall or (
                    up == "false"
                    and all(value == "none" for value in values)
                    and diagonal_turn
                ) or isolated_pier
                if len(selected[pier_model]) != int(expected_pier):
                    fail(f"Blockstate {block_id} has incorrect pier selection for {candidate}")

                expected_stub_count = (
                    sum(value != "none" for value in values)
                    if has_tall
                    else 0
                )
                if len(selected[stub_model]) != expected_stub_count:
                    fail(f"Blockstate {block_id} has incorrect pier stub selection for {candidate}")

                expected_post_count = int(up == "true" and not has_tall)
                if len(selected[post_model]) != expected_post_count:
                    fail(f"Blockstate {block_id} has incorrect post selection for {candidate}")


def expected_wrapper(base: dict[str, Any], parent: str) -> dict[str, Any]:
    textures = base.get("textures")
    if not isinstance(textures, dict) or "wall" not in textures or "particle" not in textures:
        fail("A Georgian wall base wrapper is missing wall/particle textures")
    return {"parent": parent, "textures": textures}


def wrapper_bytes(wrapper: dict[str, Any]) -> bytes:
    return (json.dumps(wrapper, indent=2) + "\n").encode("utf-8")


def write_or_verify(path: Path, expected: bytes, check: bool, description: str) -> bool:
    if path.is_file() and path.read_bytes() == expected:
        return False
    if check:
        fail(f"Missing or stale {description}: {display_path(path)}")
    path.write_bytes(expected)
    return True


def remove_old_corner_assets(check: bool) -> int:
    stale = []
    if OLD_CORNER_MASTER.is_file():
        stale.append(OLD_CORNER_MASTER)
    stale.extend(sorted(MODELS.glob("*_wall_georgian_corner.json")))
    if stale and check:
        fail(f"Stale Georgian wall corner asset remains: {display_path(stale[0])}")
    for path in stale:
        path.unlink()
    return len(stale)


def process(check: bool) -> tuple[int, int]:
    blockstates = sorted([
        *BLOCKSTATES.glob("*_wall_georgian.json"),
        *BLOCKSTATES.glob("*_wall_georgian_aged.json"),
    ])
    if not blockstates:
        fail("No Georgian wall blockstates were found")

    writes = remove_old_corner_assets(check)
    verified = 0
    for blockstate_path in blockstates:
        block_id = blockstate_path.stem
        resource_id = legacy_resource_path(block_id)
        base_path = MODELS / f"{resource_id}.json"
        pier_wrapper_path = MODELS / f"{resource_id}_pier.json"
        stub_wrapper_path = MODELS / f"{resource_id}_pier_stub.json"
        if not base_path.is_file():
            fail(f"Missing base wrapper for {block_id} (resource {resource_id})")

        state = load_json(blockstate_path)
        parts = canonical_parts(state, resource_id)
        validate_model_selection(parts, resource_id)
        writes += int(write_or_verify(
            blockstate_path,
            blockstate_bytes(parts),
            check,
            f"Georgian wall blockstate {block_id}",
        ))

        base = load_json(base_path)
        pier_wrapper = expected_wrapper(base, "erydon:block/wall/georgian/wall_georgian_pier")
        stub_wrapper = expected_wrapper(base, "erydon:block/wall/georgian/wall_georgian_pier_stub")
        writes += int(write_or_verify(
            pier_wrapper_path,
            wrapper_bytes(pier_wrapper),
            check,
            f"pier wrapper for {block_id}",
        ))
        writes += int(write_or_verify(
            stub_wrapper_path,
            wrapper_bytes(stub_wrapper),
            check,
            f"pier stub wrapper for {block_id}",
        ))
        verified += 1

    expected_pier_wrappers = {
        MODELS / f"{legacy_resource_path(path.stem)}_pier.json"
        for path in blockstates
    }
    actual_pier_wrappers = {
        *MODELS.glob("*_wall_georgian_pier.json"),
        *MODELS.glob("*_wall_georgian_aged_pier.json"),
    }
    if actual_pier_wrappers != expected_pier_wrappers:
        fail("Pier wrapper count does not match Georgian wall blockstate count")
    expected_stub_wrappers = {
        MODELS / f"{legacy_resource_path(path.stem)}_pier_stub.json"
        for path in blockstates
    }
    actual_stub_wrappers = {
        *MODELS.glob("*_wall_georgian_pier_stub.json"),
        *MODELS.glob("*_wall_georgian_aged_pier_stub.json"),
    }
    if actual_stub_wrappers != expected_stub_wrappers:
        fail("Pier stub wrapper count does not match Georgian wall blockstate count")
    return writes, verified


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify without changing files")
    parser.add_argument("--pier-source", type=Path, help="extract wall_georgian_pier from Blockbench JSON")
    parser.add_argument("--stub-source", type=Path, help="extract wall_georgian_pier_stub from Blockbench JSON")
    args = parser.parse_args()

    if args.check and (args.pier_source is not None or args.stub_source is not None):
        fail("--check cannot be combined with source imports")
    if args.pier_source is not None:
        extract_master(args.pier_source, PIER_MASTER, "wall_georgian_pier", 58, "pier")
    if args.stub_source is not None:
        extract_master(args.stub_source, STUB_MASTER, "wall_georgian_pier_stub_side", 12, "stub")

    validate_master(PIER_MASTER, "wall_georgian_pier", 58, 179, 9, 9, "pier")
    validate_master(STUB_MASTER, "wall_georgian_pier_stub_side", 12, 45, 0, 2, "stub")
    writes, verified = process(args.check)
    action = "verified" if args.check else "generated"
    print(f"Georgian wall piers {action}: variants={verified}, writes={writes}, selector_leaves={SELECTOR_LEAVES}")


if __name__ == "__main__":
    main()
