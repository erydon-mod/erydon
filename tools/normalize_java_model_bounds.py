#!/usr/bin/env python3
"""Clean Blockbench Java models without moving their rendered geometry.

The tool is deliberately dry-run by default.  With ``--write`` it can:

* translate rotated element boxes toward the vanilla 0..16 coordinate range;
* compensate the rotation pivot so all eight rendered corners stay fixed;
* remove Blockbench editor metadata and authored UV rectangles;
* add an explicit UV rectangle only where vanilla's derived UV would leave the
  current sprite (and therefore sample a neighbouring atlas sprite);
* rewrite face texture references; and
* complete the shared surround-item display contexts.

It emits a machine-readable report and refuses transformations that cannot be
proved geometrically equivalent.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import itertools
import json
import math
import sys
from pathlib import Path
from typing import Any, Iterable


EPSILON = 1.0e-9
ROUND_DIGITS = 10
MAX_GEOMETRY_DRIFT = 1.0e-8
AXIS_INDEX = {"x": 0, "y": 1, "z": 2}


def clean_number(value: float) -> int | float:
    rounded = round(float(value), ROUND_DIGITS)
    if abs(rounded) < 10 ** -ROUND_DIGITS:
        return 0
    integer = round(rounded)
    if abs(rounded - integer) < 10 ** -ROUND_DIGITS:
        return int(integer)
    return rounded


def rotation_matrix(axis: str, angle_degrees: float) -> tuple[tuple[float, ...], ...]:
    angle = math.radians(angle_degrees)
    c = math.cos(angle)
    s = math.sin(angle)
    if axis == "x":
        return ((1.0, 0.0, 0.0), (0.0, c, -s), (0.0, s, c))
    if axis == "y":
        return ((c, 0.0, s), (0.0, 1.0, 0.0), (-s, 0.0, c))
    if axis == "z":
        return ((c, -s, 0.0), (s, c, 0.0), (0.0, 0.0, 1.0))
    raise ValueError(f"Unsupported rotation axis: {axis!r}")


def rotate_point(point: list[float], origin: list[float], axis: str, angle: float) -> list[float]:
    matrix = rotation_matrix(axis, angle)
    relative = [point[i] - origin[i] for i in range(3)]
    return [
        sum(matrix[row][column] * relative[column] for column in range(3)) + origin[row]
        for row in range(3)
    ]


def rendered_corners(element: dict[str, Any]) -> list[list[float]]:
    bounds = [[float(v) for v in element[key]] for key in ("from", "to")]
    rotation = element.get("rotation") or {}
    angle = float(rotation.get("angle", 0.0))
    axis = str(rotation["axis"]) if rotation else "y"
    origin = [float(v) for v in rotation.get("origin", (8.0, 8.0, 8.0))]
    points = []
    for choices in itertools.product((0, 1), repeat=3):
        point = [bounds[choices[i]][i] for i in range(3)]
        points.append(rotate_point(point, origin, axis, angle) if abs(angle) > EPSILON else point)
    return points


def maximum_corner_drift(before: dict[str, Any], after: dict[str, Any]) -> float:
    return max(
        abs(a - b)
        for old, new in zip(rendered_corners(before), rendered_corners(after))
        for a, b in zip(old, new)
    )


def nearest_range_shift(low: float, high: float) -> float:
    if high - low > 16.0 + EPSILON:
        raise ValueError(f"Element span {high - low:g} cannot fit inside 0..16")
    if low < -EPSILON:
        return -low
    if high > 16.0 + EPSILON:
        return 16.0 - high
    return 0.0


def pivot_shift(axis: str, angle_degrees: float, box_shift: list[float]) -> list[float]:
    """Solve R*d + (I-R)*s = 0 for pivot shift s.

    The component parallel to the rotation axis is fixed at zero.  The other
    two components are the exact inverse of d = (I - R^-1)s.
    """
    angle = math.radians(angle_degrees)
    if abs(math.sin(angle / 2.0)) < EPSILON:
        raise ValueError("A zero-angle element cannot use pivot compensation")
    k = 0.5 / math.tan(angle / 2.0)
    dx, dy, dz = box_shift
    if axis == "x":
        return [0.0, dy / 2.0 + k * dz, dz / 2.0 - k * dy]
    if axis == "y":
        return [dx / 2.0 - k * dz, 0.0, dz / 2.0 + k * dx]
    if axis == "z":
        return [dx / 2.0 + k * dy, dy / 2.0 - k * dx, 0.0]
    raise ValueError(f"Unsupported rotation axis: {axis!r}")


def default_uv(element: dict[str, Any], face: str) -> list[float]:
    x1, y1, z1 = (float(v) for v in element["from"])
    x2, y2, z2 = (float(v) for v in element["to"])
    return {
        "down": [x1, 16.0 - z2, x2, 16.0 - z1],
        "up": [x1, z1, x2, z2],
        "north": [16.0 - x2, 16.0 - y2, 16.0 - x1, 16.0 - y1],
        "south": [x1, 16.0 - y2, x2, 16.0 - y1],
        "west": [z1, 16.0 - y2, z2, 16.0 - y1],
        "east": [16.0 - z2, 16.0 - y2, 16.0 - z1, 16.0 - y1],
    }[face]


def pair_into_sprite(first: float, second: float) -> tuple[float, float]:
    """Move one UV interval by an exact whole-sprite translation.

    Arbitrary translations can make an out-of-range rectangle look valid while
    silently changing its texture phase.  A single face is therefore safe only
    when both endpoints fit after adding the same integer multiple of 16.  An
    interval that crosses a 16-unit boundary must be split or deliberately
    re-authored instead.
    """
    low, high = sorted((first, second))
    if high - low > 16.0 + EPSILON:
        raise ValueError(f"UV span {high - low:g} cannot fit inside one sprite")
    if low >= -EPSILON and high <= 16.0 + EPSILON:
        return first, second

    minimum_multiplier = math.ceil((-low - EPSILON) / 16.0)
    maximum_multiplier = math.floor((16.0 - high + EPSILON) / 16.0)
    candidates = [
        multiplier
        for multiplier in range(minimum_multiplier, maximum_multiplier + 1)
        if multiplier != 0
    ]
    if not candidates:
        raise ValueError(
            f"UV interval {first:g}..{second:g} crosses a 16-unit sprite boundary"
        )
    multiplier = min(candidates, key=lambda value: (abs(value), value))
    shift = 16.0 * multiplier
    return first + shift, second + shift


def safe_explicit_uv(uv: list[float]) -> list[int | float]:
    u0, u1 = pair_into_sprite(uv[0], uv[2])
    v0, v1 = pair_into_sprite(uv[1], uv[3])
    result = [clean_number(v) for v in (u0, v0, u1, v1)]
    if any(float(value) < -EPSILON or float(value) > 16.0 + EPSILON for value in result):
        raise AssertionError(f"UV repair escaped the sprite: {result}")
    return result


def uv_leaves_sprite(uv: Iterable[float]) -> bool:
    return any(float(value) < -EPSILON or float(value) > 16.0 + EPSILON for value in uv)


def complete_surround_display(model: dict[str, Any]) -> None:
    authored = copy.deepcopy(model.get("display") or {})
    gui = authored.get("gui", {})
    third_right = authored.get("thirdperson_righthand", {})

    third_left = copy.deepcopy(third_right)
    if "rotation" in third_left and len(third_left["rotation"]) >= 2:
        third_left["rotation"][1] = clean_number(float(third_left["rotation"][1]) + 180.0)

    defaults = {
        "gui": gui,
        "fixed": copy.deepcopy(gui),
        "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.8, 0.8, 0.8]},
        "firstperson_lefthand": {"rotation": [0, -135, 0], "scale": [0.8, 0.8, 0.8]},
        "thirdperson_righthand": third_right,
        "thirdperson_lefthand": third_left,
    }
    model["display"] = {
        key: copy.deepcopy(authored[key] if key in authored else value)
        for key, value in defaults.items()
    }
    for key, value in authored.items():
        model["display"].setdefault(key, value)


def normalize_element(
    element: dict[str, Any], *, texture: str | None, reset_uv: bool, strip_editor_data: bool
) -> dict[str, Any]:
    stats: dict[str, Any] = {
        "pivot_normalized": False,
        "source_explicit_uv_faces": 0,
        "fallback_uv_faces": 0,
        "implicit_uv_risks_after": 0,
        "out_of_range_after": False,
        "max_geometry_drift": 0.0,
    }
    for key in ("from", "to"):
        if len(element.get(key, ())) != 3 or not all(math.isfinite(float(value)) for value in element[key]):
            raise ValueError(f"Element has an invalid {key} vector: {element.get(key)!r}")
    if any(float(element["from"][i]) > float(element["to"][i]) + EPSILON for i in range(3)):
        raise ValueError("Reversed element bounds are not safe to normalize")

    before = copy.deepcopy(element)
    rotation = element.get("rotation") or {}
    if rotation and ("angle" not in rotation or "axis" not in rotation):
        raise ValueError(f"Element rotation must define both angle and axis: {rotation!r}")
    angle = float(rotation["angle"]) if rotation else 0.0
    axis = str(rotation["axis"]) if rotation else "y"
    if not math.isfinite(angle) or axis not in AXIS_INDEX:
        raise ValueError(f"Element has an invalid rotation: {rotation!r}")
    if "origin" in rotation and (
        len(rotation["origin"]) != 3 or not all(math.isfinite(float(value)) for value in rotation["origin"])
    ):
        raise ValueError(f"Element has an invalid rotation origin: {rotation.get('origin')!r}")

    if abs(angle) > EPSILON:
        axis_index = AXIS_INDEX[axis]
        shift = [0.0, 0.0, 0.0]
        for index in range(3):
            if index != axis_index:
                shift[index] = nearest_range_shift(float(element["from"][index]), float(element["to"][index]))
        if any(abs(value) > EPSILON for value in shift):
            if rotation.get("rescale"):
                raise ValueError("Pivot normalization for rescale=true elements is intentionally unsupported")
            origin_shift = pivot_shift(axis, angle, shift)
            element["from"] = [clean_number(float(value) + shift[i]) for i, value in enumerate(element["from"])]
            element["to"] = [clean_number(float(value) + shift[i]) for i, value in enumerate(element["to"])]
            origin = [float(value) for value in rotation.get("origin", (8.0, 8.0, 8.0))]
            rotation["origin"] = [clean_number(value + origin_shift[i]) for i, value in enumerate(origin)]
            stats["pivot_normalized"] = True

    stats["max_geometry_drift"] = maximum_corner_drift(before, element)
    if stats["max_geometry_drift"] > MAX_GEOMETRY_DRIFT:
        raise AssertionError(f"Geometry drift {stats['max_geometry_drift']:.12g} exceeds proof tolerance")

    if strip_editor_data:
        element.pop("color", None)

    for face_name, face in (element.get("faces") or {}).items():
        if face_name not in {"down", "up", "north", "south", "west", "east"}:
            raise ValueError(f"Unsupported face direction: {face_name!r}")
        if "uv" in face:
            stats["source_explicit_uv_faces"] += 1
        if reset_uv:
            face.pop("uv", None)
        if texture is not None:
            face["texture"] = texture
        derived = default_uv(element, face_name)
        if reset_uv and uv_leaves_sprite(derived):
            face["uv"] = safe_explicit_uv(derived)
            stats["fallback_uv_faces"] += 1
        if "uv" not in face and uv_leaves_sprite(derived):
            stats["implicit_uv_risks_after"] += 1

    stats["out_of_range_after"] = any(
        float(value) < -EPSILON or float(value) > 16.0 + EPSILON
        for key in ("from", "to")
        for value in element[key]
    )
    return stats


def normalize_model(path: Path, args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    original_bytes = path.read_bytes()
    model = json.loads(original_bytes.decode("utf-8-sig"))
    report: dict[str, Any] = {
        "path": str(path.resolve()),
        "source_sha256": hashlib.sha256(original_bytes).hexdigest(),
        "elements": len(model.get("elements", [])),
        "faces": 0,
        "pivot_normalized_elements": 0,
        "out_of_range_elements_after": 0,
        "source_explicit_uv_faces": 0,
        "fallback_uv_faces": 0,
        "implicit_uv_risks_after": 0,
        "max_geometry_drift": 0.0,
    }

    if args.strip_editor_data:
        model.pop("groups", None)
    for element in model.get("elements", []):
        report["faces"] += len(element.get("faces", {}))
        stats = normalize_element(
            element,
            texture=args.texture,
            reset_uv=args.reset_uv,
            strip_editor_data=args.strip_editor_data,
        )
        report["pivot_normalized_elements"] += int(stats["pivot_normalized"])
        report["out_of_range_elements_after"] += int(stats["out_of_range_after"])
        report["source_explicit_uv_faces"] += stats["source_explicit_uv_faces"]
        report["fallback_uv_faces"] += stats["fallback_uv_faces"]
        report["implicit_uv_risks_after"] += stats["implicit_uv_risks_after"]
        report["max_geometry_drift"] = max(report["max_geometry_drift"], stats["max_geometry_drift"])

    if args.complete_surround_icon_display and "icon" in path.stem:
        complete_surround_display(model)

    output = encode_model(model, args.compact)

    # Prove serialization did not alter the result, then prove a second run is
    # byte-stable.  This catches rounding and fallback-UV regressions early.
    reloaded = json.loads(output.decode("utf-8"))
    if len(reloaded.get("elements", [])) != report["elements"] or sum(
        len(element.get("faces", {})) for element in reloaded.get("elements", [])
    ) != report["faces"]:
        raise AssertionError("Element or face count changed during serialization")
    second = copy.deepcopy(reloaded)
    if args.strip_editor_data:
        second.pop("groups", None)
    for element in second.get("elements", []):
        normalize_element(
            element,
            texture=args.texture,
            reset_uv=args.reset_uv,
            strip_editor_data=args.strip_editor_data,
        )
    if args.complete_surround_icon_display and "icon" in path.stem:
        complete_surround_display(second)
    second_output = encode_model(second, args.compact)
    if second_output != output:
        raise AssertionError("Normalization is not byte-idempotent")

    report["output_sha256"] = hashlib.sha256(output).hexdigest()
    report["changed"] = output != original_bytes
    if args.write and report["changed"]:
        path.write_bytes(output)
    return model, report


def encode_model(model: dict[str, Any], compact: bool) -> bytes:
    if compact:
        return json.dumps(model, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return (json.dumps(model, indent="\t", ensure_ascii=False) + "\n").encode("utf-8")


def collect_paths(inputs: list[str]) -> list[Path]:
    paths: list[Path] = []
    for raw in inputs:
        path = Path(raw)
        if path.is_dir():
            paths.extend(sorted(path.glob("*.json")))
        elif path.suffix.lower() == ".json":
            paths.append(path)
        else:
            raise ValueError(f"Expected a JSON file or directory: {path}")
    unique = {path.resolve(): path for path in paths}
    return [unique[key] for key in sorted(unique, key=lambda item: str(item).lower())]


def self_test() -> None:
    screenshot_shift = [-0.8826, 2.5625, 0.0]
    solved = pivot_shift("z", 22.5, screenshot_shift)
    expected = [6.0, 3.5, 0.0]
    # The screenshot values are rounded to four decimals, so allow that input
    # rounding while still checking the exact 6, 3.5 pivot relationship.
    assert max(abs(a - b) for a, b in zip(solved, expected)) < 2.5e-4, (solved, expected)

    element = {
        "from": [-1.0, 14.5, 4.0],
        "to": [1.0, 17.0, 6.0],
        "rotation": {"angle": 22.5, "axis": "z", "origin": [0.0, 15.0, 5.0]},
        "faces": {"south": {"uv": [-1.0, -1.0, 1.0, 1.5], "texture": "#missing"}},
    }
    stats = normalize_element(element, texture="#stone", reset_uv=True, strip_editor_data=True)
    assert stats["pivot_normalized"]
    assert stats["max_geometry_drift"] <= MAX_GEOMETRY_DRIFT
    assert stats["implicit_uv_risks_after"] == 0
    assert all(0.0 <= float(value) <= 16.0 for value in element["faces"]["south"].get("uv", [0, 0, 16, 16]))
    assert safe_explicit_uv([-1.0, 18.0, 0.0, 19.0]) == [15, 2, 16, 3]
    try:
        safe_explicit_uv([-2.0, 15.0, 3.0, 17.0])
    except ValueError as error:
        assert "crosses a 16-unit sprite boundary" in str(error)
    else:
        raise AssertionError("Boundary-crossing UVs must not be force-fitted")

    authored_first_person = {
        "display": {
            "gui": {"rotation": [1, 2, 3]},
            "thirdperson_righthand": {"rotation": [4, 5, 6]},
            "firstperson_righthand": {"rotation": [7, 8, 9]},
        }
    }
    complete_surround_display(authored_first_person)
    assert authored_first_person["display"]["firstperson_righthand"]["rotation"] == [7, 8, 9]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="JSON model files or non-recursive directories")
    parser.add_argument("--write", action="store_true", help="write normalized JSON; default is dry-run")
    parser.add_argument("--compact", action="store_true", help="emit load-efficient minified JSON")
    parser.add_argument("--reset-uv", action="store_true", help="remove authored UVs and add only atlas-safe fallbacks")
    parser.add_argument("--texture", help="replace every face texture reference, for example #stone")
    parser.add_argument("--strip-editor-data", action="store_true", help="remove Blockbench groups and element colors")
    parser.add_argument(
        "--complete-surround-icon-display",
        action="store_true",
        help="preserve authored icon views and add the established missing display contexts",
    )
    parser.add_argument("--report", type=Path, help="write the combined JSON report to this path")
    parser.add_argument("--self-test", action="store_true", help="run arithmetic regression checks before processing")
    args = parser.parse_args()

    if args.self_test:
        self_test()
    if not args.paths:
        if args.self_test:
            print("Self-test passed")
            return 0
        parser.error("at least one model path is required")

    reports = []
    for path in collect_paths(args.paths):
        _, report = normalize_model(path, args)
        reports.append(report)

    totals = {
        key: sum(report[key] for report in reports)
        for key in (
            "elements",
            "faces",
            "pivot_normalized_elements",
            "out_of_range_elements_after",
            "source_explicit_uv_faces",
            "fallback_uv_faces",
            "implicit_uv_risks_after",
        )
    }
    totals["files"] = len(reports)
    totals["changed_files"] = sum(bool(report["changed"]) for report in reports)
    totals["max_geometry_drift"] = max((report["max_geometry_drift"] for report in reports), default=0.0)
    combined = {"mode": "write" if args.write else "dry-run", "totals": totals, "files": reports}
    rendered = json.dumps(combined, indent=2)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
