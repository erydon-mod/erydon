#!/usr/bin/env python3
"""Audit overlapping faces and apply one explicitly reviewed safe nudge.

Audit is the default and never edits a model.  Write modes require an exact
SHA-locked plan: either one reviewed outward nudge or the explicitly opted-in
full-recess transaction.  Geometry edits replace only numeric ``from``/``to``
tokens; the raw path may additionally insert one locked boolean on a cullface
whose prior boundary culling would otherwise be lost.  Every change is
validated in memory before writing, then read back and audited.  Any failed
postcondition rolls the original bytes back atomically, including across
standard and raw-authoring roots.

The scanner understands vanilla single-axis element rotations, including
``rescale``.  The ordinary audit and opt-in full-recess mode both use the exact
``ErydonRawModelLoadingPlugin`` transform order for ERYDON's explicitly
registered raw-authoring models; any unregistered raw file remains fail-closed
and report-only.
"""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import io
import itertools
import json
import math
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Sequence

from model_geometry_common import (
    ModelAuditError,
    atomic_write_bytes,
    atomic_write_text,
    canonical_models_root,
    classify_uv_rect,
    clean_number,
    default_face_uv,
    ensure_reports_outside_sources,
    model_file_from_canonical_path,
    numeric_vector,
    path_is_within,
    scan_manifest_sha256,
    sha256_bytes,
)
from model_uv_safety import JsonSpanLocator, _pointer_get, _pointer_set


Vec3 = tuple[float, float, float]
Vec2 = tuple[float, float]

FACE_ORDER = ("north", "south", "west", "east", "down", "up")
FACE_INDEX = {name: index for index, name in enumerate(FACE_ORDER)}

# The broad tier intentionally matches the existing Blockbench resolver.
NEAR_NORMAL_DOT = 0.99995
NEAR_PLANE_TOLERANCE = 1.0e-4
MINIMUM_OVERLAP = 1.0e-8

# Only this tighter tier can ever be considered for automatic repair.
EXACT_NORMAL_DOT = 1.0 - 1.0e-8
EXACT_PLANE_TOLERANCE = 1.0e-5
FULL_COVERAGE = 1.0 - 1.0e-6

# A coarse normal bucket plus exact checks gives a cheap, safe broad phase.
NORMAL_BUCKET_SIZE = 0.02

SCHEMA_VERSION = 1
APPLY_PLAN_SCHEMA_VERSION = 1
APPLY_REPORT_SCHEMA_VERSION = 1
BULK_PLAN_SCHEMA_VERSION = 3
NUDGE_AMOUNT = 0.001
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
DEFAULT_BLOCK_SOURCE_ROOT = Path("src/main/resources/assets/erydon/models/block")
CANONICAL_BLOCK_PREFIX = "src/main/resources/assets/erydon/models/block/"
DEFAULT_RAW_BLOCK_SOURCE_ROOT = Path(
    "src/main/resources/assets/erydon/authoring_models/block"
)
RAW_CANONICAL_BLOCK_PREFIX = "src/main/resources/assets/erydon/authoring_models/block/"
REGISTERED_RAW_MODEL_PATHS = (
    "alcove/alcove_georgian_double_side_left.json",
    "alcove/alcove_georgian_double_side_right.json",
    "alcove/alcove_georgian_double_top_left.json",
    "alcove/alcove_georgian_double_top_right.json",
    "alcove/alcove_georgian_icon.json",
    "alcove/alcove_georgian_single_back.json",
    "alcove/alcove_georgian_single_base.json",
    "alcove/alcove_georgian_single_sides.json",
    "alcove/alcove_georgian_single_top.json",
    "alcove/alcove_georgian_triple_side_center.json",
    "alcove/alcove_georgian_triple_side_left.json",
    "alcove/alcove_georgian_triple_side_right.json",
    "alcove/alcove_georgian_triple_top_center.json",
    "alcove/alcove_georgian_triple_top_left.json",
    "alcove/alcove_georgian_triple_top_right.json",
    "alcove/alcove_gothic_double_side_left.json",
    "alcove/alcove_gothic_double_side_right.json",
    "alcove/alcove_gothic_double_top_left.json",
    "alcove/alcove_gothic_double_top_right.json",
    "alcove/alcove_gothic_icon.json",
    "alcove/alcove_gothic_single_back.json",
    "alcove/alcove_gothic_single_base.json",
    "alcove/alcove_gothic_single_sides.json",
    "alcove/alcove_gothic_single_top.json",
    "alcove/alcove_gothic_triple_side_center.json",
    "alcove/alcove_gothic_triple_side_left.json",
    "alcove/alcove_gothic_triple_side_right.json",
    "alcove/alcove_gothic_triple_top_center.json",
    "alcove/alcove_gothic_triple_top_left.json",
    "alcove/alcove_gothic_triple_top_right.json",
    "arch/gothic/arch_gothic_corner_large_lower.json",
    "arch/gothic/arch_gothic_corner_large_upper.json",
    "arch/gothic/arch_gothic_corner_medium.json",
    "arch/gothic/arch_gothic_corner_small.json",
    "arch/gothic/arch_gothic_icon.json",
    "arch/gothic/arch_gothic_side_large.json",
    "arch/gothic/arch_gothic_side_medium.json",
    "arch/gothic/arch_gothic_side_small.json",
    "arch/gothic/arch_gothic_top_large.json",
    "column/gothic/column_gothic_base.json",
    "column/gothic/column_gothic_capital.json",
    "column/gothic/column_gothic_pillar.json",
    "column/gothic/column_gothic_plinth.json",
    "wall/georgian/wall_georgian_27_lower.json",
    "wall/georgian/wall_georgian_27_lower_onramp.json",
    "wall/georgian/wall_georgian_27_upper.json",
    "wall/georgian/wall_georgian_27_upper_offramp.json",
    "wall/georgian/wall_georgian_45.json",
    "wall/georgian/wall_georgian_45_offramp.json",
    "wall/georgian/wall_georgian_45_onramp.json",
)
RAW_TRANSFORM_SEMANTICS = (
    "element Euler X->Y->Z, then groups innermost->outermost, each Euler X->Y->Z"
)
RAW_LOADER_EPSILON = 0.0005
DEFAULT_APPLY_REPORT = Path("build/reports/model-geometry/zfight-nudge-apply.json")
DEFAULT_BULK_APPLY_REPORT = Path(
    "build/reports/model-geometry/zfight-full-recess-apply.json"
)
BULK_PLAN_MODE = "deterministic_zfight_bulk_nudge_plan"
FULL_RECESS_MAX_ROUNDS = 64

BULK_FAILURE_PRIORITY = (
    "unsupported_post_geometry",
    "uv_analysis_error",
    "opposite_facing_contact",
    "excluded_finding_contact",
    "bounds_regression",
    "uv_regression",
    "new_conflict",
    "opposite_facing_join_removed",
    "unplanned_finding_removed",
    "target_finding_remaining",
    "finding_reduction_mismatch",
)

# Local axis and endpoint changed by Blockbench's nudgeFace(record, ..., true).
OUTWARD_ENDPOINT = {
    "north": ("from", 2, -1.0),
    "south": ("to", 2, 1.0),
    "west": ("from", 0, -1.0),
    "east": ("to", 0, 1.0),
    "down": ("from", 1, -1.0),
    "up": ("to", 1, 1.0),
}


class UnsupportedGeometry(ValueError):
    """Geometry that this report-only scanner intentionally refuses to infer."""


@dataclass(frozen=True)
class FaceRecord:
    sequence: int
    element_index: int
    face_name: str
    vertices: tuple[Vec3, Vec3, Vec3, Vec3]
    normal: Vec3
    plane_d: float
    area: float
    bounds_min: Vec3
    bounds_max: Vec3
    element: dict[str, Any]
    face: dict[str, Any]


@dataclass(frozen=True)
class RawRotation:
    """One raw-loader Euler transform, stored in degrees like the JSON source."""

    origin: Vec3
    x_degrees: float
    y_degrees: float
    z_degrees: float

    @property
    def identity(self) -> bool:
        return all(
            abs(value) <= RAW_LOADER_EPSILON
            for value in (self.x_degrees, self.y_degrees, self.z_degrees)
        )


def _add(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _sub(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _mul(a: Vec3, scalar: float) -> Vec3:
    return (a[0] * scalar, a[1] * scalar, a[2] * scalar)


def _dot(a: Vec3, b: Vec3) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _cross(a: Vec3, b: Vec3) -> Vec3:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def _length(a: Vec3) -> float:
    return math.sqrt(_dot(a, a))


def _normalize(a: Vec3) -> Vec3:
    length = _length(a)
    if length <= 1.0e-15:
        raise UnsupportedGeometry("degenerate face normal")
    return _mul(a, 1.0 / length)


def _finite_vec(value: Any, label: str) -> Vec3:
    if not isinstance(value, list) or len(value) != 3:
        raise UnsupportedGeometry(f"{label} must be a three-number array")
    try:
        result = tuple(float(component) for component in value)
    except (TypeError, ValueError) as error:
        raise UnsupportedGeometry(f"{label} must contain only numbers") from error
    if not all(math.isfinite(component) for component in result):
        raise UnsupportedGeometry(f"{label} contains a non-finite number")
    return result  # type: ignore[return-value]


def _rotate_axis(vector: Vec3, axis: str, angle_radians: float) -> Vec3:
    x, y, z = vector
    cosine = math.cos(angle_radians)
    sine = math.sin(angle_radians)
    if axis == "x":
        return (x, y * cosine - z * sine, y * sine + z * cosine)
    if axis == "y":
        return (x * cosine + z * sine, y, -x * sine + z * cosine)
    if axis == "z":
        return (x * cosine - y * sine, x * sine + y * cosine, z)
    raise UnsupportedGeometry(f"unsupported vanilla rotation axis {axis!r}")


def _parse_vanilla_rotation(rotation: Any) -> tuple[str, float, Vec3, bool] | None:
    if rotation is None:
        return None
    if not isinstance(rotation, dict):
        raise UnsupportedGeometry("raw Euler/array rotation is unsupported")
    if any(key in rotation for key in ("x", "y", "z", "angles")):
        raise UnsupportedGeometry("raw Euler rotation is unsupported")
    angle_value = rotation.get("angle")
    if isinstance(angle_value, list):
        raise UnsupportedGeometry("raw Euler angle array is unsupported")
    axis = rotation.get("axis")
    if axis not in ("x", "y", "z"):
        raise UnsupportedGeometry("rotation must contain a vanilla x/y/z axis")
    try:
        angle = float(angle_value)
    except (TypeError, ValueError) as error:
        raise UnsupportedGeometry("rotation angle must be a number") from error
    if not math.isfinite(angle):
        raise UnsupportedGeometry("rotation angle is non-finite")
    origin = _finite_vec(rotation.get("origin", [8.0, 8.0, 8.0]), "rotation origin")
    rescale = rotation.get("rescale", False)
    if not isinstance(rescale, bool):
        raise UnsupportedGeometry("rotation rescale must be boolean")
    return axis, math.radians(angle), origin, rescale


def _transform_vertex(vertex: Vec3, rotation: tuple[str, float, Vec3, bool] | None) -> Vec3:
    if rotation is None:
        return vertex
    axis, angle, origin, rescale = rotation
    local = _sub(vertex, origin)
    if rescale:
        cosine = abs(math.cos(angle))
        if cosine <= 1.0e-8:
            raise UnsupportedGeometry("rescale is undefined at a 90-degree rotation")
        scale = 1.0 / cosine
        if axis == "x":
            local = (local[0], local[1] * scale, local[2] * scale)
        elif axis == "y":
            local = (local[0] * scale, local[1], local[2] * scale)
        else:
            local = (local[0] * scale, local[1] * scale, local[2])
    return _add(_rotate_axis(local, axis, angle), origin)


def _raw_vector3(value: Any, label: str, default: Vec3 | None = None) -> Vec3:
    """Mirror the raw loader's vector3/vector3OrDefault helpers.

    Gson accepts arrays with trailing values and consumes the first three, so
    this parser deliberately does the same instead of using the stricter
    vanilla-model vector helper.
    """

    if value is None and default is not None:
        return default
    if not isinstance(value, list) or len(value) < 3:
        raise UnsupportedGeometry(f"{label} must contain at least three numbers")
    try:
        result = tuple(float(value[index]) for index in range(3))
    except (TypeError, ValueError) as error:
        raise UnsupportedGeometry(f"{label} must contain only numbers") from error
    if not all(math.isfinite(component) for component in result):
        raise UnsupportedGeometry(f"{label} contains a non-finite number")
    return result  # type: ignore[return-value]


def _raw_number_or_zero(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0.0
    result = float(value)
    if not math.isfinite(result):
        raise UnsupportedGeometry("raw Euler rotation contains a non-finite number")
    return result


def _parse_raw_rotation(value: Any, default_origin: Vec3) -> RawRotation:
    """Parse every rotation spelling accepted by ErydonRawModelLoadingPlugin."""

    if value is None:
        return RawRotation((8.0, 8.0, 8.0), 0.0, 0.0, 0.0)
    if isinstance(value, list):
        angles = _raw_vector3(value, "raw rotation")
        return RawRotation(default_origin, *angles)
    if not isinstance(value, dict):
        # RawRotation.parse returns NONE for non-object/non-array values.
        return RawRotation((8.0, 8.0, 8.0), 0.0, 0.0, 0.0)

    origin = _raw_vector3(value.get("origin"), "raw rotation origin", default_origin)
    if isinstance(value.get("angles"), list):
        angles = _raw_vector3(value["angles"], "raw rotation angles")
        return RawRotation(origin, *angles)
    if isinstance(value.get("angle"), list):
        angles = _raw_vector3(value["angle"], "raw rotation angle")
        return RawRotation(origin, *angles)
    if any(
        isinstance(value.get(axis), (int, float))
        and not isinstance(value.get(axis), bool)
        for axis in ("x", "y", "z")
    ):
        return RawRotation(
            origin,
            _raw_number_or_zero(value.get("x")),
            _raw_number_or_zero(value.get("y")),
            _raw_number_or_zero(value.get("z")),
        )
    if "axis" in value and "angle" in value:
        axis = value.get("axis")
        angle = value.get("angle")
        if axis not in ("x", "y", "z"):
            return RawRotation((8.0, 8.0, 8.0), 0.0, 0.0, 0.0)
        if isinstance(angle, bool) or not isinstance(angle, (int, float)):
            raise UnsupportedGeometry("raw axis rotation angle must be a number")
        numeric_angle = float(angle)
        if not math.isfinite(numeric_angle):
            raise UnsupportedGeometry("raw axis rotation angle is non-finite")
        angles = {
            "x": (numeric_angle, 0.0, 0.0),
            "y": (0.0, numeric_angle, 0.0),
            "z": (0.0, 0.0, numeric_angle),
        }[axis]
        return RawRotation(origin, *angles)
    return RawRotation((8.0, 8.0, 8.0), 0.0, 0.0, 0.0)


def _transform_raw_rotation(vertex: Vec3, rotation: RawRotation) -> Vec3:
    if rotation.identity:
        return vertex
    transformed = _sub(vertex, rotation.origin)
    # These checks intentionally use the Java loader's EPSILON against radians.
    # Although unusual, matching it avoids a scanner/runtime semantic split.
    for axis, degrees in (
        ("x", rotation.x_degrees),
        ("y", rotation.y_degrees),
        ("z", rotation.z_degrees),
    ):
        radians = math.radians(degrees)
        if abs(radians) > RAW_LOADER_EPSILON:
            transformed = _rotate_axis(transformed, axis, radians)
    return _add(transformed, rotation.origin)


def _transform_raw_vertex(vertex: Vec3, rotations: Sequence[RawRotation]) -> Vec3:
    transformed = vertex
    for rotation in rotations:
        transformed = _transform_raw_rotation(transformed, rotation)
    return transformed


def _raw_group_state(
    document: dict[str, Any], element_count: int
) -> tuple[dict[int, tuple[RawRotation, ...]], dict[str, Any]]:
    """Collect raw group transforms and a stable membership lock.

    The traversal intentionally mirrors collectGroupRotations in the Java
    loader.  Rotation chains are accumulated outer-to-inner while walking and
    reversed when attached to an element, yielding innermost-to-outermost.
    Primitive indices at the top level are Blockbench outliner entries and are
    ignored by the loader, so they are recorded but receive no group transform.
    """

    groups = document.get("groups")
    if groups is None:
        groups = []
    if not isinstance(groups, list):
        raise UnsupportedGeometry("raw groups must be an array")

    rotations_by_element: dict[int, list[RawRotation]] = {}
    memberships: dict[int, list[str]] = {}
    top_level_elements: list[int] = []

    def numeric_index(value: Any, label: str) -> int:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise UnsupportedGeometry(f"{label} must be a numeric element index")
        numeric = float(value)
        if not math.isfinite(numeric):
            raise UnsupportedGeometry(f"{label} is non-finite")
        # Gson's getAsInt truncates numeric primitives toward zero.
        index = int(numeric)
        if index < 0 or index >= element_count:
            raise UnsupportedGeometry(f"{label} is outside the element array: {index}")
        return index

    def visit(
        group: dict[str, Any],
        inherited: tuple[RawRotation, ...],
        path: str,
    ) -> None:
        default_origin = _raw_vector3(
            group.get("origin"), "raw group origin", (8.0, 8.0, 8.0)
        )
        group_rotation = _parse_raw_rotation(group.get("rotation"), default_origin)
        accumulated = inherited
        if not group_rotation.identity:
            accumulated = inherited + (group_rotation,)
        children = group.get("children")
        if children is None:
            return
        if not isinstance(children, list):
            raise UnsupportedGeometry(f"raw group children must be an array at {path}")
        for child_offset, child in enumerate(children):
            child_path = f"{path}/children/{child_offset}"
            if isinstance(child, (int, float)) and not isinstance(child, bool):
                index = numeric_index(child, child_path)
                memberships.setdefault(index, []).append(path)
                if accumulated:
                    rotations_by_element.setdefault(index, []).extend(reversed(accumulated))
            elif isinstance(child, dict):
                visit(child, accumulated, child_path)
            # Java ignores other primitive/null children.

    for group_offset, group in enumerate(groups):
        path = f"/groups/{group_offset}"
        if isinstance(group, dict):
            visit(group, (), path)
        elif isinstance(group, (int, float)) and not isinstance(group, bool):
            top_level_elements.append(numeric_index(group, path))
        # Java ignores other top-level values.

    membership_payload = {
        "memberships": {
            str(index): paths for index, paths in sorted(memberships.items())
        },
        "topLevelElementsIgnoredByGroupCollector": top_level_elements,
    }
    membership_sha = hashlib.sha256(
        json.dumps(
            membership_payload,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
    ).hexdigest()
    metadata = {
        "transformSemantics": RAW_TRANSFORM_SEMANTICS,
        "groupMembershipSha256": membership_sha,
        "groupedElementReferences": sum(len(paths) for paths in memberships.values()),
        "topLevelElementReferences": len(top_level_elements),
    }
    return (
        {index: tuple(rotations) for index, rotations in rotations_by_element.items()},
        metadata,
    )


def _local_face_vertices(start: Vec3, end: Vec3, face_name: str) -> tuple[Vec3, Vec3, Vec3, Vec3]:
    x1, y1, z1 = start
    x2, y2, z2 = end
    # Winding is outward-facing.  It is used to distinguish same-facing
    # overlaps from ordinary opposite-facing internal contacts.
    vertices: dict[str, tuple[Vec3, Vec3, Vec3, Vec3]] = {
        "north": ((x2, y1, z1), (x1, y1, z1), (x1, y2, z1), (x2, y2, z1)),
        "south": ((x1, y1, z2), (x2, y1, z2), (x2, y2, z2), (x1, y2, z2)),
        "west": ((x1, y1, z1), (x1, y1, z2), (x1, y2, z2), (x1, y2, z1)),
        "east": ((x2, y1, z2), (x2, y1, z1), (x2, y2, z1), (x2, y2, z2)),
        "down": ((x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)),
        "up": ((x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)),
    }
    return vertices[face_name]


def _triangle_area(a: Vec3, b: Vec3, c: Vec3) -> float:
    return _length(_cross(_sub(b, a), _sub(c, a))) * 0.5


def _quad_area(vertices: Sequence[Vec3]) -> float:
    return _triangle_area(vertices[0], vertices[1], vertices[2]) + _triangle_area(
        vertices[0], vertices[2], vertices[3]
    )


def _bounds(vertices: Sequence[Vec3]) -> tuple[Vec3, Vec3]:
    return (
        tuple(min(vertex[axis] for vertex in vertices) for axis in range(3)),
        tuple(max(vertex[axis] for vertex in vertices) for axis in range(3)),
    )  # type: ignore[return-value]


def _build_faces(
    elements: list[Any],
    *,
    raw_group_rotations: dict[int, tuple[RawRotation, ...]] | None = None,
) -> tuple[list[FaceRecord], list[dict[str, Any]]]:
    records: list[FaceRecord] = []
    unsupported: list[dict[str, Any]] = []
    sequence = 0
    for element_index, raw_element in enumerate(elements):
        if not isinstance(raw_element, dict):
            unsupported.append({"element": element_index, "reason": "element is not an object"})
            continue
        try:
            if raw_group_rotations is None:
                start = _finite_vec(raw_element.get("from"), "from")
                end = _finite_vec(raw_element.get("to"), "to")
            else:
                start = _raw_vector3(raw_element.get("from"), "raw from")
                end = _raw_vector3(raw_element.get("to"), "raw to")
            if any(start[axis] > end[axis] for axis in range(3)):
                raise UnsupportedGeometry("inverted element bounds are unsupported")
            zero_thickness = any(
                abs(start[axis] - end[axis]) <= 1.0e-12 for axis in range(3)
            )
            if zero_thickness:
                # Minecraft can still bake the non-degenerate faces of a flat
                # element.  Include those in the report, but fail closed for
                # every repair classification involving this model.
                unsupported.append(
                    {"element": element_index, "reason": "zero-thickness element is unsupported"}
                )
            if raw_group_rotations is None:
                rotation = _parse_vanilla_rotation(raw_element.get("rotation"))
                raw_rotations: tuple[RawRotation, ...] | None = None
            else:
                default_origin = _raw_vector3(
                    raw_element.get("origin"),
                    "raw element origin",
                    (8.0, 8.0, 8.0),
                )
                element_rotation = _parse_raw_rotation(
                    raw_element.get("rotation"), default_origin
                )
                raw_rotations = (
                    (() if element_rotation.identity else (element_rotation,))
                    + raw_group_rotations.get(element_index, ())
                )
                rotation = None
        except UnsupportedGeometry as error:
            unsupported.append({"element": element_index, "reason": str(error)})
            continue

        raw_faces = raw_element.get("faces", {})
        if not isinstance(raw_faces, dict):
            unsupported.append({"element": element_index, "reason": "faces is not an object"})
            continue
        for face_name in FACE_ORDER:
            if face_name not in raw_faces:
                continue
            raw_face = raw_faces[face_name]
            if raw_face is None:
                continue
            if not isinstance(raw_face, dict):
                unsupported.append(
                    {"element": element_index, "face": face_name, "reason": "face is not an object"}
                )
                continue
            if (
                raw_group_rotations is not None
                and "erydon_cull_boundary_override" in raw_face
                and not isinstance(raw_face["erydon_cull_boundary_override"], bool)
            ):
                unsupported.append(
                    {
                        "element": element_index,
                        "face": face_name,
                        "reason": "erydon_cull_boundary_override must be boolean",
                    }
                )
                continue
            if raw_group_rotations is None and raw_face.get("texture", object()) is None:
                continue
            try:
                local_vertices = _local_face_vertices(start, end, face_name)
                if raw_rotations is None:
                    vertices = tuple(
                        _transform_vertex(vertex, rotation) for vertex in local_vertices
                    )
                else:
                    vertices = tuple(
                        _transform_raw_vertex(vertex, raw_rotations)
                        for vertex in local_vertices
                    )
                first_edge = _sub(vertices[1], vertices[0])
                second_edge = _sub(vertices[2], vertices[0])
                normal = _normalize(_cross(first_edge, second_edge))
                area = _quad_area(vertices)
                if not math.isfinite(area) or area <= 1.0e-12:
                    # Expected for the collapsed faces of a flat element.  It
                    # is already tagged unsupported above and has no drawable
                    # polygon to compare.
                    if zero_thickness:
                        continue
                    raise UnsupportedGeometry("face has zero or non-finite area")
                bounds_min, bounds_max = _bounds(vertices)
            except UnsupportedGeometry as error:
                unsupported.append(
                    {"element": element_index, "face": face_name, "reason": str(error)}
                )
                continue
            records.append(
                FaceRecord(
                    sequence=sequence,
                    element_index=element_index,
                    face_name=face_name,
                    vertices=vertices,  # type: ignore[arg-type]
                    normal=normal,
                    plane_d=_dot(normal, vertices[0]),
                    area=area,
                    bounds_min=bounds_min,
                    bounds_max=bounds_max,
                    element=raw_element,
                    face=raw_face,
                )
            )
            sequence += 1
    return records, unsupported


def _signed_area_2d(polygon: Sequence[Vec2]) -> float:
    return 0.5 * sum(
        polygon[index][0] * polygon[(index + 1) % len(polygon)][1]
        - polygon[(index + 1) % len(polygon)][0] * polygon[index][1]
        for index in range(len(polygon))
    )


def _cross_2d(a: Vec2, b: Vec2, c: Vec2) -> float:
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def _line_intersection(start: Vec2, end: Vec2, clip_a: Vec2, clip_b: Vec2) -> Vec2:
    ray_x, ray_y = end[0] - start[0], end[1] - start[1]
    side_x, side_y = clip_b[0] - clip_a[0], clip_b[1] - clip_a[1]
    denominator = ray_x * side_y - ray_y * side_x
    if abs(denominator) <= 1.0e-15:
        return end
    distance = ((clip_a[0] - start[0]) * side_y - (clip_a[1] - start[1]) * side_x) / denominator
    return (start[0] + distance * ray_x, start[1] + distance * ray_y)


def _clip_convex(subject: Sequence[Vec2], clip_polygon: Sequence[Vec2]) -> list[Vec2]:
    clip = list(clip_polygon)
    if _signed_area_2d(clip) < 0.0:
        clip.reverse()
    output = list(subject)
    for index, clip_a in enumerate(clip):
        clip_b = clip[(index + 1) % len(clip)]
        input_polygon = output
        output = []
        if not input_polygon:
            break
        start = input_polygon[-1]
        for end in input_polygon:
            end_inside = _cross_2d(clip_a, clip_b, end) >= -1.0e-10
            start_inside = _cross_2d(clip_a, clip_b, start) >= -1.0e-10
            if end_inside:
                if not start_inside:
                    output.append(_line_intersection(start, end, clip_a, clip_b))
                output.append(end)
            elif start_inside:
                output.append(_line_intersection(start, end, clip_a, clip_b))
            start = end
    return output


def _overlap_area(first: FaceRecord, second: FaceRecord) -> float:
    origin = first.vertices[0]
    axis_u = _normalize(_sub(first.vertices[1], origin))
    axis_v = _normalize(_cross(first.normal, axis_u))

    def project(vertices: Sequence[Vec3]) -> list[Vec2]:
        return [
            (_dot(_sub(vertex, origin), axis_u), _dot(_sub(vertex, origin), axis_v))
            for vertex in vertices
        ]

    intersection = _clip_convex(project(first.vertices), project(second.vertices))
    return abs(_signed_area_2d(intersection)) if len(intersection) >= 3 else 0.0


def _canonical_plane(face: FaceRecord) -> tuple[Vec3, float]:
    normal = face.normal
    plane_d = face.plane_d
    dominant = max(range(3), key=lambda axis: abs(normal[axis]))
    if normal[dominant] < 0.0:
        normal = _mul(normal, -1.0)
        plane_d = -plane_d
    return normal, plane_d


def _bucket_key(face: FaceRecord) -> tuple[int, int, int, int]:
    normal, plane_d = _canonical_plane(face)
    return (
        math.floor(normal[0] / NORMAL_BUCKET_SIZE),
        math.floor(normal[1] / NORMAL_BUCKET_SIZE),
        math.floor(normal[2] / NORMAL_BUCKET_SIZE),
        math.floor(plane_d / NEAR_PLANE_TOLERANCE),
    )


def _broad_phase_pairs(faces: Sequence[FaceRecord]) -> Iterator[tuple[FaceRecord, FaceRecord]]:
    buckets: dict[tuple[int, int, int, int], list[FaceRecord]] = {}
    for face in faces:
        normal_x, normal_y, normal_z, plane_bin = _bucket_key(face)
        seen: set[int] = set()
        for delta_x, delta_y, delta_z in itertools.product((-1, 0, 1), repeat=3):
            for delta_plane in (-1, 0, 1):
                key = (
                    normal_x + delta_x,
                    normal_y + delta_y,
                    normal_z + delta_z,
                    plane_bin + delta_plane,
                )
                for other in buckets.get(key, ()):
                    if other.sequence in seen:
                        continue
                    seen.add(other.sequence)
                    yield other, face
        buckets.setdefault((normal_x, normal_y, normal_z, plane_bin), []).append(face)


def _boxes_overlap(first: FaceRecord, second: FaceRecord) -> bool:
    return not any(
        first.bounds_max[axis] + NEAR_PLANE_TOLERANCE < second.bounds_min[axis]
        or second.bounds_max[axis] + NEAR_PLANE_TOLERANCE < first.bounds_min[axis]
        for axis in range(3)
    )


def _semantic_duplicate(first: FaceRecord, second: FaceRecord) -> bool:
    if first.face_name != second.face_name or first.face != second.face:
        return False

    def element_signature(element: dict[str, Any]) -> dict[str, Any]:
        # Name and the other faces do not alter this face.  Everything else is
        # retained so editor/export metadata cannot accidentally be assumed
        # render-identical.
        return {key: value for key, value in element.items() if key not in ("name", "faces")}

    return element_signature(first.element) == element_signature(second.element)


def _stable_id(
    relative_path: str,
    first: FaceRecord,
    second: FaceRecord,
    orientation: str,
    overlap_class: str,
) -> str:
    identity = "\0".join(
        (
            relative_path,
            f"{first.element_index}:{first.face_name}",
            f"{second.element_index}:{second.face_name}",
            orientation,
            overlap_class,
        )
    )
    return "zf-" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20]


def _inspect_pair(
    first: FaceRecord,
    second: FaceRecord,
    relative_path: str,
    source_sha256: str,
    model_repair_eligible: bool,
) -> dict[str, Any] | None:
    if first.element_index == second.element_index or not _boxes_overlap(first, second):
        return None
    normal_dot = _dot(first.normal, second.normal)
    absolute_dot = abs(normal_dot)
    if absolute_dot < NEAR_NORMAL_DOT:
        return None

    aligned_second_d = second.plane_d if normal_dot >= 0.0 else -second.plane_d
    nominal_plane_gap = abs(first.plane_d - aligned_second_d)
    if nominal_plane_gap > NEAR_PLANE_TOLERANCE:
        return None
    plane_error = max(
        max(abs(_dot(first.normal, vertex) - first.plane_d) for vertex in second.vertices),
        max(abs(_dot(second.normal, vertex) - second.plane_d) for vertex in first.vertices),
    )
    if plane_error > NEAR_PLANE_TOLERANCE:
        return None

    overlap_area = _overlap_area(first, second)
    if overlap_area <= MINIMUM_OVERLAP:
        return None
    coverage_first = min(1.0, overlap_area / first.area)
    coverage_second = min(1.0, overlap_area / second.area)
    exact_plane = absolute_dot >= EXACT_NORMAL_DOT and plane_error <= EXACT_PLANE_TOLERANCE
    if not exact_plane:
        overlap_class = "near"
    elif coverage_first >= FULL_COVERAGE and coverage_second >= FULL_COVERAGE:
        overlap_class = "exact"
    elif coverage_first >= FULL_COVERAGE or coverage_second >= FULL_COVERAGE:
        overlap_class = "contained"
    else:
        overlap_class = "partial"

    orientation = "same" if normal_dot >= 0.0 else "opposite"
    identical_render_duplicate = (
        orientation == "same"
        and overlap_class == "exact"
        and _semantic_duplicate(first, second)
    )
    auto_candidate = model_repair_eligible and identical_render_duplicate
    if auto_candidate:
        repair_classification = "AUTO_CANDIDATE"
        reason = "literal duplicate same-facing element face with identical render metadata"
        target = second
        proposed_action: dict[str, Any] | None = {
            "action": "remove_face",
            "element": target.element_index,
            "face": target.face_name,
        }
    elif not model_repair_eligible:
        repair_classification = "UNSUPPORTED"
        reason = "model contains unsupported or raw-authoring geometry"
        proposed_action = None
    elif orientation == "opposite":
        repair_classification = "REPORT_ONLY"
        reason = "opposite-facing contact may be an intentional internal boundary"
        proposed_action = None
    else:
        repair_classification = "REPORT_ONLY"
        reason = "overlap is not a literal identical-render duplicate"
        proposed_action = None

    finding_id = _stable_id(
        relative_path, first, second, orientation, overlap_class
    )
    return {
        "finding_id": finding_id,
        "candidate_id": finding_id if auto_candidate else None,
        "repair_classification": repair_classification,
        "reason": reason,
        "orientation": orientation,
        "overlap_class": overlap_class,
        "plane_quality": "exact" if exact_plane else "near",
        "normal_dot": normal_dot,
        "nominal_plane_gap": nominal_plane_gap,
        "maximum_plane_error": plane_error,
        "overlap_area": overlap_area,
        "coverage_a": coverage_first,
        "coverage_b": coverage_second,
        "a": {"element": first.element_index, "face": first.face_name, "area": first.area},
        "b": {"element": second.element_index, "face": second.face_name, "area": second.area},
        "proposed_action": proposed_action,
    }


def _relative_path(path: Path, base: Path) -> str:
    try:
        return path.resolve().relative_to(base.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def scan_model_document(
    document: dict[str, Any],
    *,
    relative_path: str,
    source_sha256: str,
    raw_authoring: bool = False,
    enable_raw_transforms: bool = False,
) -> dict[str, Any]:
    elements = document.get("elements")
    if not isinstance(elements, list):
        return {
            "path": relative_path,
            "sha256": source_sha256,
            "status": "no_geometry",
            "repair_eligible": False,
            "unsupported": [],
            "elements": 0,
            "faces": 0,
            "findings": [],
        }

    raw_metadata: dict[str, Any] | None = None
    if raw_authoring and enable_raw_transforms:
        try:
            raw_group_rotations, raw_metadata = _raw_group_state(document, len(elements))
            faces, unsupported = _build_faces(
                elements, raw_group_rotations=raw_group_rotations
            )
        except UnsupportedGeometry as error:
            faces = []
            unsupported = [{"reason": str(error)}]
    else:
        faces, unsupported = _build_faces(elements)
    if raw_authoring and not enable_raw_transforms:
        unsupported.insert(0, {"reason": "raw authoring models are report-only"})
    repair_eligible = not unsupported and (not raw_authoring or enable_raw_transforms)
    findings: list[dict[str, Any]] = []
    for first, second in _broad_phase_pairs(faces):
        finding = _inspect_pair(
            first, second, relative_path, source_sha256, repair_eligible
        )
        if finding is not None:
            findings.append(finding)
    findings.sort(key=lambda finding: finding["finding_id"])
    result = {
        "path": relative_path,
        "sha256": source_sha256,
        "status": "unsupported" if unsupported else "scanned",
        "repair_eligible": repair_eligible,
        "unsupported": unsupported,
        "elements": len(elements),
        "faces": len(faces),
        "findings": findings,
    }
    if raw_metadata is not None:
        result["rawAuthoring"] = raw_metadata
    return result


def scan_model_file(path: Path, *, base: Path) -> dict[str, Any]:
    relative_path = _relative_path(path, base)
    try:
        source = path.read_bytes()
    except OSError as error:
        return {
            "path": relative_path,
            "sha256": None,
            "status": "read_error",
            "repair_eligible": False,
            "unsupported": [{"reason": str(error)}],
            "elements": 0,
            "faces": 0,
            "findings": [],
        }
    source_sha256 = hashlib.sha256(source).hexdigest()
    try:
        document = json.loads(source.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        return {
            "path": relative_path,
            "sha256": source_sha256,
            "status": "parse_error",
            "repair_eligible": False,
            "unsupported": [{"reason": str(error)}],
            "elements": 0,
            "faces": 0,
            "findings": [],
        }
    if not isinstance(document, dict):
        return {
            "path": relative_path,
            "sha256": source_sha256,
            "status": "parse_error",
            "repair_eligible": False,
            "unsupported": [{"reason": "model root is not an object"}],
            "elements": 0,
            "faces": 0,
            "findings": [],
        }
    lowered_parts = tuple(part.lower() for part in path.parts)
    raw_authoring = "authoring_models" in lowered_parts
    registered_raw = False
    if raw_authoring:
        for index in range(len(lowered_parts) - 1):
            if lowered_parts[index : index + 2] != ("authoring_models", "block"):
                continue
            raw_relative = PurePosixPath(*path.parts[index + 2 :]).as_posix()
            registered_raw = raw_relative in REGISTERED_RAW_MODEL_PATHS
            break
    return scan_model_document(
        document,
        relative_path=relative_path,
        source_sha256=source_sha256,
        raw_authoring=raw_authoring,
        enable_raw_transforms=registered_raw,
    )


def discover_model_files(roots: Iterable[Path], maximum_files: int | None = None) -> list[Path]:
    if maximum_files is not None and maximum_files <= 0:
        raise ModelAuditError("--max-files must be a positive integer")
    discovered: set[Path] = set()
    for root in roots:
        resolved = root.resolve()
        if not resolved.exists():
            raise ModelAuditError(f"Audit root does not exist: {resolved}")
        if resolved.is_file() and resolved.suffix.lower() == ".json":
            discovered.add(resolved)
        elif resolved.is_dir():
            discovered.update(path.resolve() for path in resolved.rglob("*.json") if path.is_file())
        else:
            raise ModelAuditError(f"Audit root must be a JSON file or directory: {resolved}")
    ordered = sorted(discovered, key=lambda path: path.as_posix().lower())
    return ordered[:maximum_files] if maximum_files is not None else ordered


def validate_report_destinations(report_paths: Sequence[Path], roots: Sequence[Path]) -> None:
    """Prevent reports from replacing, or being placed among, audited sources."""

    resolved_reports = [path.resolve() for path in report_paths]
    if len(set(resolved_reports)) != len(resolved_reports):
        raise ModelAuditError("JSON and CSV report destinations must be different files")
    resolved_roots = [path.resolve() for path in roots]
    for report in resolved_reports:
        for root in resolved_roots:
            if root.is_file() and report == root:
                raise ModelAuditError(f"Report destination equals an audited source file: {report}")
            if root.is_dir() and path_is_within(report, root):
                raise ModelAuditError(f"Report destination is inside an audited source root: {report}")


def _summary(models: Sequence[dict[str, Any]]) -> dict[str, Any]:
    findings = [finding for model in models for finding in model["findings"]]
    by_orientation: dict[str, int] = {}
    by_overlap_class: dict[str, int] = {}
    by_repair_classification: dict[str, int] = {}
    for finding in findings:
        for target, key in (
            (by_orientation, "orientation"),
            (by_overlap_class, "overlap_class"),
            (by_repair_classification, "repair_classification"),
        ):
            value = finding[key]
            target[value] = target.get(value, 0) + 1
    return {
        "files": len(models),
        "files_with_geometry": sum(model["status"] != "no_geometry" for model in models),
        "files_with_findings": sum(bool(model["findings"]) for model in models),
        "unsupported_files": sum(model["status"] == "unsupported" for model in models),
        "parse_or_read_errors": sum(model["status"] in ("parse_error", "read_error") for model in models),
        "elements": sum(model["elements"] for model in models),
        "faces": sum(model["faces"] for model in models),
        "findings": len(findings),
        "auto_candidates": sum(
            finding["repair_classification"] == "AUTO_CANDIDATE" for finding in findings
        ),
        "by_orientation": dict(sorted(by_orientation.items())),
        "by_overlap_class": dict(sorted(by_overlap_class.items())),
        "by_repair_classification": dict(sorted(by_repair_classification.items())),
    }


def build_report(models: Sequence[dict[str, Any]], roots: Sequence[Path], base: Path) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "mode": "report-only",
        "sourceWrites": False,
        "notice": "The default audit never modifies source models.",
        "policy": {
            "automaticCandidates": "Classification only; automatic candidates are never applied.",
            "reviewedApply": (
                "Source writes require the separate SHA-locked --apply-plan mode and its full "
                "pre-write and post-write validation."
            ),
            "rawTransforms": (
                f"The {len(REGISTERED_RAW_MODEL_PATHS)} registered raw-authoring models use "
                "loader-matched element Euler and "
                "nested-group transforms in the ordinary audit; unregistered raw files remain "
                "fail-closed and report-only."
            ),
        },
        "roots": [_relative_path(root, base) for root in roots],
        "settings": {
            "near_normal_dot": NEAR_NORMAL_DOT,
            "near_plane_tolerance": NEAR_PLANE_TOLERANCE,
            "exact_normal_dot": EXACT_NORMAL_DOT,
            "exact_plane_tolerance": EXACT_PLANE_TOLERANCE,
            "minimum_overlap": MINIMUM_OVERLAP,
            "full_coverage": FULL_COVERAGE,
        },
        "summary": _summary(models),
        "models": list(models),
    }


CSV_FIELDS = (
    "path",
    "file_sha256",
    "finding_id",
    "candidate_id",
    "repair_classification",
    "reason",
    "orientation",
    "overlap_class",
    "plane_quality",
    "element_a",
    "face_a",
    "element_b",
    "face_b",
    "overlap_area",
    "coverage_a",
    "coverage_b",
    "maximum_plane_error",
    "proposed_action",
)


def write_csv_report(path: Path, models: Sequence[dict[str, Any]]) -> None:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    for model in models:
        for finding in model["findings"]:
            writer.writerow(
                {
                    "path": model["path"],
                    "file_sha256": model["sha256"],
                    "finding_id": finding["finding_id"],
                    "candidate_id": finding["candidate_id"] or "",
                    "repair_classification": finding["repair_classification"],
                    "reason": finding["reason"],
                    "orientation": finding["orientation"],
                    "overlap_class": finding["overlap_class"],
                    "plane_quality": finding["plane_quality"],
                    "element_a": finding["a"]["element"],
                    "face_a": finding["a"]["face"],
                    "element_b": finding["b"]["element"],
                    "face_b": finding["b"]["face"],
                    "overlap_area": format(finding["overlap_area"], ".12g"),
                    "coverage_a": format(finding["coverage_a"], ".12g"),
                    "coverage_b": format(finding["coverage_b"], ".12g"),
                    "maximum_plane_error": format(finding["maximum_plane_error"], ".12g"),
                    "proposed_action": json.dumps(
                        finding["proposed_action"], sort_keys=True, separators=(",", ":")
                    )
                    if finding["proposed_action"]
                    else "",
                }
            )
    atomic_write_text(path, stream.getvalue())


PLAN_ENTRY_FIELDS = {
    "canonicalPath",
    "sourceSha256",
    "findingId",
    "targetEndpoint",
    "element",
    "face",
    "jsonPointer",
    "expectedNumber",
    "proposedNumber",
    "amount",
    "action",
    "expectedFindingReduction",
}


def _valid_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise ModelAuditError(f"{label} must be a 64-digit SHA-256 value")
    return value.lower()


def _plan_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelAuditError(f"{label} must be a finite JSON number")
    result = float(value)
    if not math.isfinite(result):
        raise ModelAuditError(f"{label} must be finite")
    return result


def _load_nudge_plan(plan_path: Path, expected_sha256: str) -> tuple[dict[str, Any], str]:
    expected = _valid_sha256(expected_sha256, "--expect-plan-sha256")
    if not plan_path.exists() or not plan_path.is_file():
        raise ModelAuditError(f"Apply plan is not a file: {plan_path.resolve()}")
    plan_bytes = plan_path.read_bytes()
    actual = sha256_bytes(plan_bytes)
    if actual != expected:
        raise ModelAuditError(f"Apply-plan SHA-256 mismatch: expected {expected}, actual {actual}")
    try:
        plan = json.loads(plan_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Apply plan is not valid UTF-8 JSON: {error}") from error
    if not isinstance(plan, dict) or set(plan) != {"schemaVersion", "candidate"}:
        raise ModelAuditError("Apply plan must contain exactly schemaVersion and candidate")
    if plan.get("schemaVersion") != APPLY_PLAN_SCHEMA_VERSION:
        raise ModelAuditError(f"Apply plan schemaVersion must be {APPLY_PLAN_SCHEMA_VERSION}")
    if not isinstance(plan.get("candidate"), dict):
        raise ModelAuditError("Apply plan candidate must be an object")
    return plan, actual


def _validate_plan_entry(raw_entry: dict[str, Any]) -> dict[str, Any]:
    missing = sorted(PLAN_ENTRY_FIELDS - set(raw_entry))
    extra = sorted(set(raw_entry) - PLAN_ENTRY_FIELDS)
    if missing or extra:
        raise ModelAuditError(f"Plan candidate fields are not exact; missing={missing}, extra={extra}")
    entry = dict(raw_entry)
    entry["sourceSha256"] = _valid_sha256(entry["sourceSha256"], "sourceSha256")
    for key in ("canonicalPath", "findingId", "targetEndpoint", "face", "jsonPointer", "action"):
        if not isinstance(entry[key], str) or not entry[key]:
            raise ModelAuditError(f"Plan candidate {key} must be a non-empty string")
    if entry["targetEndpoint"] not in ("a", "b"):
        raise ModelAuditError("Plan targetEndpoint must be 'a' or 'b'")
    if entry["face"] not in FACE_ORDER:
        raise ModelAuditError(f"Plan face is invalid: {entry['face']!r}")
    if isinstance(entry["element"], bool) or not isinstance(entry["element"], int) or entry["element"] < 0:
        raise ModelAuditError("Plan element must be a non-negative integer")
    if (
        isinstance(entry["expectedFindingReduction"], bool)
        or not isinstance(entry["expectedFindingReduction"], int)
        or entry["expectedFindingReduction"] <= 0
    ):
        raise ModelAuditError("expectedFindingReduction must be a positive integer")
    entry["expectedNumber"] = _plan_number(entry["expectedNumber"], "expectedNumber")
    entry["proposedNumber"] = _plan_number(entry["proposedNumber"], "proposedNumber")
    entry["amount"] = _plan_number(entry["amount"], "amount")
    if not math.isclose(entry["amount"], NUDGE_AMOUNT, rel_tol=0.0, abs_tol=1.0e-12):
        raise ModelAuditError(f"Plan amount must be exactly {NUDGE_AMOUNT}")
    if entry["action"] != "nudge_face_outward":
        raise ModelAuditError("Plan action must be nudge_face_outward")
    return entry


def _model_from_project_canonical_path(root: Path, canonical_path: str) -> tuple[Path, str]:
    if not canonical_path.startswith(CANONICAL_BLOCK_PREFIX):
        raise ModelAuditError(
            f"canonicalPath must begin with {CANONICAL_BLOCK_PREFIX!r}: {canonical_path!r}"
        )
    relative = canonical_path[len(CANONICAL_BLOCK_PREFIX) :]
    model_file, normalized_relative = model_file_from_canonical_path(root, relative)
    normalized = CANONICAL_BLOCK_PREFIX + normalized_relative
    if normalized != canonical_path:
        raise ModelAuditError(
            f"canonicalPath is not canonical: {canonical_path!r} should be {normalized!r}"
        )
    return model_file, normalized


def _canonical_raw_models_root(path: Path) -> Path:
    """Resolve a raw-authoring root without weakening the common standard-root guard."""

    lexical = path.absolute()
    if not lexical.exists() or not lexical.is_dir():
        raise ModelAuditError(f"Canonical raw models root is not a directory: {lexical}")
    if lexical.is_symlink():
        raise ModelAuditError(f"Canonical raw models root must not be a symlink: {lexical}")
    return lexical.resolve(strict=True)


def _raw_model_file_from_canonical_path(root: Path, relative: str) -> tuple[Path, str]:
    if not isinstance(relative, str) or not relative:
        raise ModelAuditError("Raw model path must be a non-empty string")
    pure = PurePosixPath(relative)
    if (
        pure.is_absolute()
        or any(part in {"", ".", ".."} for part in pure.parts)
        or "\\" in relative
        or pure.suffix.lower() != ".json"
    ):
        raise ModelAuditError(f"Raw model path is not canonical: {relative!r}")
    raw_root = _canonical_raw_models_root(root)
    lexical = raw_root.joinpath(*pure.parts)
    current = raw_root
    for part in pure.parts:
        current = current / part
        if current.is_symlink():
            raise ModelAuditError(f"Symlinked raw model paths are not allowed: {current}")
    try:
        resolved = lexical.resolve(strict=True)
        normalized = resolved.relative_to(raw_root).as_posix()
    except (OSError, ValueError) as error:
        raise ModelAuditError(f"Raw model path escapes or is missing: {lexical}") from error
    if not resolved.is_file() or resolved.suffix.lower() != ".json":
        raise ModelAuditError(f"Expected a raw JSON model file: {resolved}")
    return resolved, normalized


def _model_from_bulk_canonical_path(
    standard_root: Path,
    raw_root: Path | None,
    canonical_path: str,
) -> tuple[Path, str, bool]:
    if canonical_path.startswith(CANONICAL_BLOCK_PREFIX):
        model_file, normalized = _model_from_project_canonical_path(
            standard_root, canonical_path
        )
        return model_file, normalized, False
    if canonical_path.startswith(RAW_CANONICAL_BLOCK_PREFIX):
        if raw_root is None:
            raise ModelAuditError(
                f"Bulk plan references raw-authoring source without a raw root: {canonical_path}"
            )
        relative = canonical_path[len(RAW_CANONICAL_BLOCK_PREFIX) :]
        if relative not in REGISTERED_RAW_MODEL_PATHS:
            raise ModelAuditError(
                f"Bulk plan references an unregistered raw-authoring model: {canonical_path}"
            )
        model_file, normalized_relative = _raw_model_file_from_canonical_path(
            raw_root, relative
        )
        normalized = RAW_CANONICAL_BLOCK_PREFIX + normalized_relative
        if normalized != canonical_path:
            raise ModelAuditError(
                f"Raw canonicalPath is not canonical: {canonical_path!r} should be {normalized!r}"
            )
        return model_file, normalized, True
    raise ModelAuditError(
        "Bulk canonicalPath is outside the standard and registered raw roots: "
        + repr(canonical_path)
    )


def _deterministic_smaller_endpoint(finding: dict[str, Any]) -> str:
    first, second = finding["a"], finding["b"]
    if abs(first["area"] - second["area"]) > 1.0e-9:
        return "a" if first["area"] < second["area"] else "b"
    # This mirrors the Blockbench graph's area-descending, cube-index-ascending
    # order: when areas tie, the later element receives the non-zero colour.
    first_key = (first["element"], FACE_INDEX[first["face"]])
    second_key = (second["element"], FACE_INDEX[second["face"]])
    return "a" if first_key > second_key else "b"


def _outward_pointer(element_index: int, face: str) -> tuple[str, float]:
    endpoint, axis, sign = OUTWARD_ENDPOINT[face]
    return f"/elements/{element_index}/{endpoint}/{axis}", sign


BulkNode = tuple[int, str]


def _bulk_node(endpoint: dict[str, Any]) -> BulkNode:
    return int(endpoint["element"]), str(endpoint["face"])


def _element_uses_rescale(document: dict[str, Any], node: BulkNode) -> bool:
    elements = document.get("elements")
    if not isinstance(elements, list) or node[0] >= len(elements):
        return True
    element = elements[node[0]]
    if not isinstance(element, dict):
        return True
    rotation = element.get("rotation")
    return isinstance(rotation, dict) and rotation.get("rescale", False) is True


def _unsupported_disposition(audit: dict[str, Any]) -> str:
    reasons = " ".join(
        str(item.get("reason", "")) for item in audit.get("unsupported", ()) if isinstance(item, dict)
    ).lower()
    if "zero-thickness" in reasons:
        return "unsupported_zero_thickness"
    if "raw euler" in reasons or "raw authoring" in reasons:
        return "unsupported_raw_euler"
    if "rescale" in reasons:
        return "unsupported_rescale"
    return "unsupported_geometry"


def _only_zero_thickness_is_unsupported(audit: dict[str, Any]) -> bool:
    unsupported = audit.get("unsupported")
    if not isinstance(unsupported, list) or not unsupported:
        return False
    zero_elements = {
        item.get("element")
        for item in unsupported
        if isinstance(item, dict)
        and "zero-thickness" in str(item.get("reason", "")).lower()
    }
    if not zero_elements:
        return False
    return all(
        isinstance(item, dict)
        and item.get("element") in zero_elements
        and (
            "zero-thickness" in str(item.get("reason", "")).lower()
            or "degenerate face normal" in str(item.get("reason", "")).lower()
        )
        for item in unsupported
    )


def _bulk_finding_exclusion(
    finding: dict[str, Any], audit: dict[str, Any], document: dict[str, Any]
) -> str | None:
    if finding["orientation"] == "opposite":
        return "opposite_facing_intentional_join"
    if finding["plane_quality"] != "exact":
        return "near_not_exact_coplanar"
    if not audit["repair_eligible"] or audit["status"] != "scanned":
        return _unsupported_disposition(audit)
    if _element_uses_rescale(document, _bulk_node(finding["a"])) or _element_uses_rescale(
        document, _bulk_node(finding["b"])
    ):
        return "rescaled_element"
    return None


def _bulk_component_id(canonical_path: str, finding_ids: Sequence[str]) -> str:
    payload = canonical_path + "\0" + "\0".join(sorted(finding_ids))
    return "zfc-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:20]


def _build_bulk_components(
    document: dict[str, Any],
    canonical_path: str,
    findings: Sequence[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Reproduce the safe Blockbench resolver's deterministic graph colouring."""

    node_areas: dict[BulkNode, float] = {}
    adjacency: dict[BulkNode, set[BulkNode]] = {}
    edge_nodes: dict[str, tuple[BulkNode, BulkNode]] = {}
    for finding in findings:
        first = _bulk_node(finding["a"])
        second = _bulk_node(finding["b"])
        for node, area in ((first, finding["a"]["area"]), (second, finding["b"]["area"])):
            numeric_area = float(area)
            previous = node_areas.get(node)
            if previous is not None and not math.isclose(
                previous, numeric_area, rel_tol=0.0, abs_tol=1.0e-9
            ):
                raise ModelAuditError(
                    f"Face area changed between findings for {canonical_path} {node}: "
                    f"{previous} != {numeric_area}"
                )
            node_areas[node] = numeric_area
            adjacency.setdefault(node, set())
        adjacency[first].add(second)
        adjacency[second].add(first)
        edge_nodes[finding["finding_id"]] = (first, second)

    node_order = lambda node: (node[0], FACE_INDEX[node[1]])
    components: list[dict[str, Any]] = []
    visited: set[BulkNode] = set()
    for seed in sorted(node_areas, key=node_order):
        if seed in visited:
            continue
        pending = [seed]
        visited.add(seed)
        nodes: set[BulkNode] = set()
        while pending:
            current = pending.pop()
            nodes.add(current)
            for neighbour in sorted(adjacency[current], key=node_order, reverse=True):
                if neighbour not in visited:
                    visited.add(neighbour)
                    pending.append(neighbour)

        finding_ids = sorted(
            finding_id
            for finding_id, (first, second) in edge_nodes.items()
            if first in nodes and second in nodes
        )
        component_id = _bulk_component_id(canonical_path, finding_ids)
        colours: dict[BulkNode, int] = {}
        for node in sorted(
            nodes,
            key=lambda item: (-node_areas[item], item[0], FACE_INDEX[item[1]]),
        ):
            used = {colours[neighbour] for neighbour in adjacency[node] if neighbour in colours}
            colour = 0
            while colour in used:
                colour += 1
            colours[node] = colour

        operations: list[dict[str, Any]] = []
        for node in sorted(nodes, key=node_order):
            colour = colours[node]
            if colour <= 0:
                continue
            pointer, sign = _outward_pointer(node[0], node[1])
            expected = _pointer_get(document, pointer)
            if isinstance(expected, bool) or not isinstance(expected, (int, float)):
                raise ModelAuditError(f"Bulk nudge pointer is not numeric: {pointer}")
            amount = NUDGE_AMOUNT * colour
            proposed = float(expected) + sign * amount
            incident = sorted(
                finding_id
                for finding_id in finding_ids
                if node in edge_nodes[finding_id]
            )
            operations.append(
                {
                    "action": "nudge_face_outward",
                    "amount": clean_number(amount),
                    "componentId": component_id,
                    "element": node[0],
                    "face": node[1],
                    "findingIds": incident,
                    "jsonPointer": pointer,
                    "offsetSteps": colour,
                    "expectedNumber": clean_number(float(expected)),
                    "proposedNumber": clean_number(proposed),
                }
            )
        if not operations:
            raise ModelAuditError(f"Overlap component has no positive colour: {component_id}")
        components.append(
            {
                "componentId": component_id,
                "findingIds": finding_ids,
                "nodes": sorted(nodes, key=node_order),
                "colours": colours,
                "edgeNodes": {
                    finding_id: edge_nodes[finding_id] for finding_id in finding_ids
                },
                "operations": operations,
            }
        )
    return components


def _apply_bulk_operations(
    document: dict[str, Any], operations: Sequence[dict[str, Any]]
) -> dict[str, Any]:
    result = copy.deepcopy(document)
    seen: set[str] = set()
    for operation in operations:
        pointer = operation["jsonPointer"]
        if pointer in seen:
            raise ModelAuditError(f"Bulk plan repeats a scalar pointer: {pointer}")
        seen.add(pointer)
        current = _pointer_get(result, pointer)
        if isinstance(current, bool) or not isinstance(current, (int, float)):
            raise ModelAuditError(f"Bulk plan pointer is not numeric: {pointer}")
        if not math.isclose(
            float(current), float(operation["expectedNumber"]), rel_tol=0.0, abs_tol=1.0e-12
        ):
            raise ModelAuditError(
                f"Bulk plan expected number is stale at {pointer}: "
                f"{operation['expectedNumber']} != {current}"
            )
        _pointer_set(result, pointer, operation["proposedNumber"])
    return result


def _apply_raw_cull_override_insertions(
    document: dict[str, Any], insertions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
    result = copy.deepcopy(document)
    seen: set[str] = set()
    suffix = "/erydon_cull_boundary_override"
    for insertion in insertions:
        pointer = insertion.get("jsonPointer")
        if not isinstance(pointer, str) or not pointer.endswith(suffix):
            raise ModelAuditError("Raw cull override insertion has an invalid JSON pointer")
        if pointer in seen:
            raise ModelAuditError(f"Raw cull override insertion repeats {pointer}")
        seen.add(pointer)
        if insertion.get("action") != "insert_raw_cull_boundary_override":
            raise ModelAuditError("Raw cull override insertion action is invalid")
        if insertion.get("expectedAbsent") is not True or insertion.get("proposedValue") is not True:
            raise ModelAuditError("Raw cull override insertion must lock absent -> true")
        face_pointer = pointer[: -len(suffix)]
        if insertion.get("facePointer") != face_pointer:
            raise ModelAuditError("Raw cull override face pointer does not match its JSON pointer")
        face = _pointer_get(result, face_pointer)
        if not isinstance(face, dict):
            raise ModelAuditError(f"Raw cull override target is not a face object: {face_pointer}")
        if "erydon_cull_boundary_override" in face:
            raise ModelAuditError(f"Raw cull override is no longer absent: {pointer}")
        if face.get("cullface") not in FACE_ORDER:
            raise ModelAuditError(f"Raw cull override target has no valid cullface: {face_pointer}")
        _pointer_set(result, pointer, True)
    return result


def _coordinate_range_distance(value: Any) -> float:
    numeric = float(value)
    return max(0.0, -numeric, numeric - 16.0)


def _bounds_regressions(
    before_document: dict[str, Any], after_document: dict[str, Any]
) -> list[dict[str, Any]]:
    before_elements = before_document.get("elements")
    after_elements = after_document.get("elements")
    if not isinstance(before_elements, list) or not isinstance(after_elements, list):
        raise ModelAuditError("Model elements must be arrays for bounds validation")
    if len(before_elements) != len(after_elements):
        raise ModelAuditError("Bulk simulation changed the element count")
    regressions: list[dict[str, Any]] = []
    for element_index, (before_element, after_element) in enumerate(
        zip(before_elements, after_elements)
    ):
        if not isinstance(before_element, dict) or not isinstance(after_element, dict):
            raise ModelAuditError(f"Element {element_index} is not an object")
        for endpoint in ("from", "to"):
            before = numeric_vector(
                before_element.get(endpoint), 3, f"elements[{element_index}].{endpoint}"
            )
            after = numeric_vector(
                after_element.get(endpoint), 3, f"elements[{element_index}].{endpoint}"
            )
            for axis, (old_value, new_value) in enumerate(zip(before, after)):
                old_distance = _coordinate_range_distance(old_value)
                new_distance = _coordinate_range_distance(new_value)
                if new_distance > old_distance + 1.0e-9:
                    regressions.append(
                        {
                            "jsonPointer": f"/elements/{element_index}/{endpoint}/{axis}",
                            "before": clean_number(old_value),
                            "after": clean_number(new_value),
                            "rangeDistanceBefore": clean_number(old_distance),
                            "rangeDistanceAfter": clean_number(new_distance),
                        }
                    )
    return regressions


def _inversion_violations(document: dict[str, Any]) -> list[int]:
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ModelAuditError("Model elements must be an array")
    violations: list[int] = []
    for index, element in enumerate(elements):
        if not isinstance(element, dict):
            raise ModelAuditError(f"Element {index} is not an object")
        start = numeric_vector(element.get("from"), 3, f"elements[{index}].from")
        end = numeric_vector(element.get("to"), 3, f"elements[{index}].to")
        if any(start[axis] >= end[axis] - 1.0e-12 for axis in range(3)):
            violations.append(index)
    return violations


def _inversion_regressions(
    before_document: dict[str, Any], after_document: dict[str, Any]
) -> list[dict[str, Any]]:
    before_elements = before_document.get("elements")
    after_elements = after_document.get("elements")
    if not isinstance(before_elements, list) or not isinstance(after_elements, list):
        raise ModelAuditError("Model elements must be arrays for inversion validation")
    regressions: list[dict[str, Any]] = []
    for index, (before_element, after_element) in enumerate(zip(before_elements, after_elements)):
        before_start = numeric_vector(before_element.get("from"), 3, "before from")
        before_end = numeric_vector(before_element.get("to"), 3, "before to")
        after_start = numeric_vector(after_element.get("from"), 3, "after from")
        after_end = numeric_vector(after_element.get("to"), 3, "after to")
        for axis in range(3):
            before_width = before_end[axis] - before_start[axis]
            after_width = after_end[axis] - after_start[axis]
            if after_width < -1.0e-12 or (
                before_width > 1.0e-12 and after_width <= 1.0e-12
            ):
                regressions.append(
                    {
                        "element": index,
                        "axis": axis,
                        "widthBefore": clean_number(before_width),
                        "widthAfter": clean_number(after_width),
                    }
                )
    return regressions


def _raw_closest_direction(vertices: Sequence[Vec3]) -> str:
    first_edge = _sub(vertices[1], vertices[0])
    second_edge = _sub(vertices[2], vertices[0])
    normal = _cross(first_edge, second_edge)
    if _dot(normal, normal) <= RAW_LOADER_EPSILON:
        return "up"
    # Direction.values() is DOWN, UP, NORTH, SOUTH, WEST, EAST. Java updates
    # only on a strictly greater dot product, so this order also preserves its
    # deterministic tie behaviour.
    directions: tuple[tuple[str, Vec3], ...] = (
        ("down", (0.0, -1.0, 0.0)),
        ("up", (0.0, 1.0, 0.0)),
        ("north", (0.0, 0.0, -1.0)),
        ("south", (0.0, 0.0, 1.0)),
        ("west", (-1.0, 0.0, 0.0)),
        ("east", (1.0, 0.0, 0.0)),
    )
    selected = "north"
    selected_dot = sys.float_info.min
    for name, direction in directions:
        candidate = _dot(normal, direction)
        if candidate > selected_dot:
            selected = name
            selected_dot = candidate
    return selected


def _raw_default_uv(vertices: Sequence[Vec3], nominal_face: str) -> tuple[float, ...]:
    values: list[float] = []
    for x, y, z in vertices:
        u, v = {
            "north": (16.0 - x, 16.0 - y),
            "south": (x, 16.0 - y),
            "west": (z, 16.0 - y),
            "east": (16.0 - z, 16.0 - y),
            "up": (x, z),
            "down": (x, 16.0 - z),
        }[nominal_face]
        values.extend((u, v))
    return tuple(values)


def _raw_effective_face_uv(
    face: dict[str, Any], vertices: Sequence[Vec3]
) -> tuple[float, ...]:
    if "uv" in face and isinstance(face["uv"], list) and len(face["uv"]) >= 4:
        uv = numeric_vector(face["uv"][:4], 4, "raw face uv")
        values: tuple[float, ...] = (
            uv[0], uv[3], uv[2], uv[3], uv[2], uv[1], uv[0], uv[1]
        )
    else:
        values = _raw_default_uv(vertices, _raw_closest_direction(vertices))
    offset_value = face.get("erydon_uv_offset")
    if offset_value is not None:
        offset = numeric_vector(offset_value, 2, "raw face erydon_uv_offset")
        values = tuple(
            value + offset[index % 2] for index, value in enumerate(values)
        )
    return values


def _raw_element_rotation_chains(
    document: dict[str, Any]
) -> tuple[list[Any], dict[int, tuple[RawRotation, ...]], dict[str, Any]]:
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ModelAuditError("Raw model elements must be an array")
    try:
        group_rotations, metadata = _raw_group_state(document, len(elements))
    except UnsupportedGeometry as error:
        raise ModelAuditError(f"Raw transform analysis failed: {error}") from error
    chains: dict[int, tuple[RawRotation, ...]] = {}
    for element_index, element in enumerate(elements):
        if not isinstance(element, dict):
            raise ModelAuditError(f"Raw element {element_index} is not an object")
        try:
            default_origin = _raw_vector3(
                element.get("origin"),
                "raw element origin",
                (8.0, 8.0, 8.0),
            )
            element_rotation = _parse_raw_rotation(
                element.get("rotation"), default_origin
            )
        except UnsupportedGeometry as error:
            raise ModelAuditError(
                f"Raw element {element_index} transform failed: {error}"
            ) from error
        chains[element_index] = (
            (() if element_rotation.identity else (element_rotation,))
            + group_rotations.get(element_index, ())
        )
    return elements, chains, metadata


def _raw_cull_boundary_state(
    document: dict[str, Any], *, honor_override: bool = True
) -> dict[str, bool]:
    """Match the raw loader's physical/effective cull-boundary predicate."""

    elements, rotation_chains, _ = _raw_element_rotation_chains(document)
    axes = {
        "down": (1, 0.0),
        "up": (1, 16.0),
        "north": (2, 0.0),
        "south": (2, 16.0),
        "west": (0, 0.0),
        "east": (0, 16.0),
    }
    state: dict[str, bool] = {}
    for element_index, element in enumerate(elements):
        faces = element.get("faces", {})
        if not isinstance(faces, dict):
            raise ModelAuditError(f"Raw element {element_index} faces is not an object")
        try:
            start = _raw_vector3(element.get("from"), "raw element from")
            end = _raw_vector3(element.get("to"), "raw element to")
        except UnsupportedGeometry as error:
            raise ModelAuditError(
                f"Raw element {element_index} cull-boundary analysis failed: {error}"
            ) from error
        for face_name in FACE_ORDER:
            face = faces.get(face_name)
            if not isinstance(face, dict):
                continue
            cullface = face.get("cullface")
            if cullface not in axes:
                continue
            override = face.get("erydon_cull_boundary_override", False)
            if not isinstance(override, bool):
                raise ModelAuditError(
                    "Raw erydon_cull_boundary_override must be boolean at "
                    f"element {element_index} face {face_name}"
                )
            vertices = tuple(
                _transform_raw_vertex(vertex, rotation_chains[element_index])
                for vertex in _local_face_vertices(start, end, face_name)
            )
            axis, expected = axes[cullface]
            physical_boundary = all(
                abs(vertex[axis] - expected) <= RAW_LOADER_EPSILON
                for vertex in vertices
            )
            state[f"/elements/{element_index}/faces/{face_name}"] = (
                physical_boundary or (honor_override and override)
            )
    return state


def _raw_cull_boundary_transition(
    before_document: dict[str, Any], after_document: dict[str, Any]
) -> dict[str, Any]:
    before_physical = _raw_cull_boundary_state(
        before_document, honor_override=False
    )
    after_physical = _raw_cull_boundary_state(after_document, honor_override=False)
    before = _raw_cull_boundary_state(before_document, honor_override=True)
    after = _raw_cull_boundary_state(after_document, honor_override=True)
    if not (set(before_physical) == set(after_physical) == set(before) == set(after)):
        raise ModelAuditError("Raw cullface membership changed during geometry simulation")
    moved_off_physical_boundary = sorted(
        pointer
        for pointer in before_physical
        if before_physical[pointer] and not after_physical[pointer]
    )
    moved_onto_physical_boundary = sorted(
        pointer
        for pointer in before_physical
        if not before_physical[pointer] and after_physical[pointer]
    )
    newly_hidden = sorted(
        pointer for pointer in before if not before[pointer] and after[pointer]
    )
    newly_unculled = sorted(
        pointer for pointer in before if before[pointer] and not after[pointer]
    )
    return {
        "authoredCullfaces": len(before),
        "boundaryEligibleBefore": sum(before.values()),
        "boundaryEligibleAfter": sum(after.values()),
        "physicalBoundaryEligibleBefore": sum(before_physical.values()),
        "physicalBoundaryEligibleAfter": sum(after_physical.values()),
        "movedOffPhysicalBoundaryFacePointers": moved_off_physical_boundary,
        "movedOntoPhysicalBoundaryFacePointers": moved_onto_physical_boundary,
        "newlyHiddenFacePointers": newly_hidden,
        "newlyUnculledFacePointers": newly_unculled,
    }


def _effective_uv_range_distances(
    document: dict[str, Any], *, raw_authoring: bool = False
) -> dict[str, float]:
    output: dict[str, float] = {}
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ModelAuditError("Model elements must be an array")
    raw_group_rotations: dict[int, tuple[RawRotation, ...]] = {}
    if raw_authoring:
        _, raw_group_rotations, _ = _raw_element_rotation_chains(document)
    for element_index, element in enumerate(elements):
        if not isinstance(element, dict):
            raise ModelAuditError(f"Element {element_index} is not an object")
        raw_rotations: tuple[RawRotation, ...] = ()
        if raw_authoring:
            raw_rotations = raw_group_rotations[element_index]
        faces = element.get("faces", {})
        if not isinstance(faces, dict):
            raise ModelAuditError(f"Element {element_index} faces is not an object")
        for face_name in FACE_ORDER:
            face = faces.get(face_name)
            if face is None:
                continue
            if not isinstance(face, dict):
                raise ModelAuditError(f"Element {element_index} face {face_name} is not an object")
            if not raw_authoring and face.get("texture", object()) is None:
                continue
            if raw_authoring:
                try:
                    start = _raw_vector3(element.get("from"), "raw element from")
                    end = _raw_vector3(element.get("to"), "raw element to")
                    vertices = tuple(
                        _transform_raw_vertex(vertex, raw_rotations)
                        for vertex in _local_face_vertices(start, end, face_name)
                    )
                    values = _raw_effective_face_uv(face, vertices)
                except (UnsupportedGeometry, ModelAuditError) as error:
                    raise ModelAuditError(
                        f"Raw face {element_index}:{face_name} UV analysis failed: {error}"
                    ) from error
            else:
                uv = face["uv"] if "uv" in face else default_face_uv(element, face_name)
                values = numeric_vector(
                    uv, 4, f"elements[{element_index}].faces.{face_name}.uv"
                )
            output[f"/elements/{element_index}/faces/{face_name}"] = max(
                _coordinate_range_distance(value) for value in values
            )
    return output


def _uv_regressions(
    before_document: dict[str, Any],
    after_document: dict[str, Any],
    *,
    raw_authoring: bool = False,
) -> list[dict[str, Any]]:
    before = _effective_uv_range_distances(
        before_document, raw_authoring=raw_authoring
    )
    after = _effective_uv_range_distances(after_document, raw_authoring=raw_authoring)
    return [
        {
            "facePointer": pointer,
            "rangeDistanceBefore": clean_number(before.get(pointer, 0.0)),
            "rangeDistanceAfter": clean_number(distance),
        }
        for pointer, distance in sorted(after.items())
        if distance > before.get(pointer, 0.0) + 1.0e-9
    ]


def _effective_uv_out_of_range(document: dict[str, Any]) -> dict[str, str]:
    output: dict[str, str] = {}
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ModelAuditError("Model elements must be an array")
    for element_index, element in enumerate(elements):
        if not isinstance(element, dict):
            raise ModelAuditError(f"Element {element_index} is not an object")
        faces = element.get("faces", {})
        if not isinstance(faces, dict):
            raise ModelAuditError(f"Element {element_index} faces is not an object")
        for face_name in FACE_ORDER:
            face = faces.get(face_name)
            if face is None:
                continue
            if not isinstance(face, dict):
                raise ModelAuditError(f"Element {element_index} face {face_name} is not an object")
            if face.get("texture", object()) is None:
                continue
            uv = face["uv"] if "uv" in face else default_face_uv(element, face_name)
            analysis = classify_uv_rect(uv)
            if analysis.classification != "in_range":
                output[f"/elements/{element_index}/faces/{face_name}"] = analysis.classification
    return output


def _validate_all_bounds(document: dict[str, Any]) -> None:
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ModelAuditError("Model elements must be an array")
    for index, element in enumerate(elements):
        if not isinstance(element, dict):
            raise ModelAuditError(f"Element {index} is not an object")
        start = numeric_vector(element.get("from"), 3, f"elements[{index}].from")
        end = numeric_vector(element.get("to"), 3, f"elements[{index}].to")
        if any(value < -1.0e-9 or value > 16.0 + 1.0e-9 for value in (*start, *end)):
            raise ModelAuditError(f"Nudge would leave element {index} outside the 0..16 model bounds")
        if any(start[axis] >= end[axis] - 1.0e-12 for axis in range(3)):
            raise ModelAuditError(f"Element {index} is inverted or zero-thickness")


def _prepare_scalar_token_replacement(
    source_bytes: bytes,
    before_document: dict[str, Any],
    pointer: str,
    expected_number: float,
    proposed_number: float,
) -> tuple[bytes, dict[str, Any], dict[str, Any]]:
    bom = b"\xef\xbb\xbf" if source_bytes.startswith(b"\xef\xbb\xbf") else b""
    body = source_bytes[len(bom) :]
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ModelAuditError(f"Source model is not UTF-8: {error}") from error
    spans = JsonSpanLocator(text).locate()
    if pointer not in spans:
        raise ModelAuditError(f"Planned scalar JSON pointer has no source token: {pointer}")
    current = _pointer_get(before_document, pointer)
    if isinstance(current, bool) or not isinstance(current, (int, float)):
        raise ModelAuditError(f"Planned pointer is not a JSON number: {pointer}")
    if float(current) != expected_number:
        raise ModelAuditError(
            f"Source number no longer matches plan at {pointer}: expected {expected_number}, got {current}"
        )

    char_start, char_end = spans[pointer]

    def byte_offset(character_offset: int) -> int:
        return len(bom) + len(text[:character_offset].encode("utf-8"))

    byte_start, byte_end = byte_offset(char_start), byte_offset(char_end)
    original_token = source_bytes[byte_start:byte_end]
    try:
        parsed_token = json.loads(original_token.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Located scalar token is invalid JSON at {pointer}") from error
    if isinstance(parsed_token, bool) or not isinstance(parsed_token, (int, float)):
        raise ModelAuditError(f"Located token is not numeric at {pointer}")
    if float(parsed_token) != expected_number:
        raise ModelAuditError(f"Located token differs from expected number at {pointer}")

    replacement_token = json.dumps(
        proposed_number, ensure_ascii=False, allow_nan=False, separators=(",", ":")
    ).encode("utf-8")
    after_bytes = source_bytes[:byte_start] + replacement_token + source_bytes[byte_end:]
    try:
        after_document = json.loads(after_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError("Scalar token replacement produced invalid JSON") from error
    expected_document = copy.deepcopy(before_document)
    _pointer_set(expected_document, pointer, proposed_number)
    if after_document != expected_document:
        raise ModelAuditError("Deep comparison failed: content outside the selected scalar changed")
    if after_bytes == source_bytes:
        raise ModelAuditError("Apply plan produced no source-byte change")
    replacement = {
        "jsonPointer": pointer,
        "byteStart": byte_start,
        "byteEnd": byte_end,
        "originalToken": original_token.decode("utf-8"),
        "replacementToken": replacement_token.decode("utf-8"),
    }
    return after_bytes, after_document, replacement


def _scan_transition(
    before_document: dict[str, Any],
    after_document: dict[str, Any],
    *,
    canonical_path: str,
    source_sha_before: str,
    source_sha_after: str,
    selected_finding_id: str,
    expected_reduction: int,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    before = scan_model_document(
        before_document,
        relative_path=canonical_path,
        source_sha256=source_sha_before,
        raw_authoring=False,
    )
    after = scan_model_document(
        after_document,
        relative_path=canonical_path,
        source_sha256=source_sha_after,
        raw_authoring=False,
    )
    if not before["repair_eligible"] or before["status"] != "scanned":
        raise ModelAuditError("Pre-apply model is not eligible for reviewed nudge repair")
    if not after["repair_eligible"] or after["status"] != "scanned":
        raise ModelAuditError("Proposed model is no longer eligible for reviewed nudge repair")

    before_ids = {finding["finding_id"] for finding in before["findings"]}
    after_ids = {finding["finding_id"] for finding in after["findings"]}
    if selected_finding_id not in before_ids:
        raise ModelAuditError(f"Selected finding is stale or absent: {selected_finding_id}")
    if selected_finding_id in after_ids:
        raise ModelAuditError("Proposed nudge does not remove the selected finding")
    new_ids = sorted(after_ids - before_ids)
    if new_ids:
        raise ModelAuditError(f"Proposed nudge creates new z-fighting findings: {new_ids[:5]}")
    reduction = len(before["findings"]) - len(after["findings"])
    if reduction != expected_reduction:
        raise ModelAuditError(
            f"Finding reduction mismatch: expected {expected_reduction}, observed {reduction}"
        )

    before_oor = _effective_uv_out_of_range(before_document)
    after_oor = _effective_uv_out_of_range(after_document)
    new_oor = sorted(set(after_oor) - set(before_oor))
    if new_oor:
        raise ModelAuditError(
            "Proposed nudge introduces new effective vanilla UV out-of-range faces: "
            + ", ".join(new_oor[:5])
        )
    _validate_all_bounds(after_document)
    transition = {
        "findingsBefore": len(before["findings"]),
        "findingsAfter": len(after["findings"]),
        "findingReduction": reduction,
        "newFindingIds": new_ids,
        "uvOutOfRangeBefore": before_oor,
        "uvOutOfRangeAfter": after_oor,
        "newUvOutOfRangeFaces": new_oor,
    }
    return before, after, transition


def _canonical_document_sha256(document: dict[str, Any]) -> str:
    encoded = json.dumps(
        document,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return sha256_bytes(encoded)


def _ordered_bulk_failures(reasons: Iterable[str]) -> list[str]:
    unique = set(reasons)
    priority = {reason: index for index, reason in enumerate(BULK_FAILURE_PRIORITY)}
    return sorted(unique, key=lambda reason: (priority.get(reason, len(priority)), reason))


def _compact_bulk_diagnostics(details: dict[str, Any], limit: int = 12) -> dict[str, Any]:
    compact: dict[str, Any] = {}
    for key in sorted(details):
        value = details[key]
        if isinstance(value, list):
            compact[key + "Count"] = len(value)
            if value:
                compact[key + "Sample"] = value[:limit]
        else:
            compact[key] = value
    return compact


def _bulk_transition_validation(
    original_document: dict[str, Any],
    proposed_document: dict[str, Any],
    initial_audit: dict[str, Any],
    target_finding_ids: set[str],
) -> tuple[list[str], dict[str, Any], dict[str, Any]]:
    canonical_path = initial_audit["path"]
    proposed_audit = scan_model_document(
        proposed_document,
        relative_path=canonical_path,
        source_sha256=_canonical_document_sha256(proposed_document),
        raw_authoring=False,
    )
    reasons: list[str] = []
    details: dict[str, Any] = {}
    if not proposed_audit["repair_eligible"] or proposed_audit["status"] != "scanned":
        reasons.append("unsupported_post_geometry")
        details["postUnsupported"] = proposed_audit["unsupported"]

    initial_findings = {finding["finding_id"]: finding for finding in initial_audit["findings"]}
    before_ids = set(initial_findings)
    after_ids = {finding["finding_id"] for finding in proposed_audit["findings"]}
    new_ids = sorted(after_ids - before_ids)
    remaining_targets = sorted(target_finding_ids & after_ids)
    removed_ids = before_ids - after_ids
    unplanned_removed = sorted(removed_ids - target_finding_ids)
    opposite_removed = sorted(
        finding_id
        for finding_id in unplanned_removed
        if initial_findings[finding_id]["orientation"] == "opposite"
    )
    if new_ids:
        reasons.append("new_conflict")
        details["newFindingIds"] = new_ids
    if opposite_removed:
        reasons.append("opposite_facing_join_removed")
        details["oppositeFindingIdsRemoved"] = opposite_removed
    other_unplanned = sorted(set(unplanned_removed) - set(opposite_removed))
    if other_unplanned:
        reasons.append("unplanned_finding_removed")
        details["otherFindingIdsRemoved"] = other_unplanned
    if remaining_targets:
        reasons.append("target_finding_remaining")
        details["targetFindingIdsRemaining"] = remaining_targets
    observed_reduction = len(before_ids) - len(after_ids)
    if observed_reduction != len(target_finding_ids):
        reasons.append("finding_reduction_mismatch")
        details["expectedFindingReduction"] = len(target_finding_ids)
        details["observedFindingReduction"] = observed_reduction

    bounds = _bounds_regressions(original_document, proposed_document)
    if bounds:
        reasons.append("bounds_regression")
        details["boundsRegressions"] = bounds
    try:
        uv = _uv_regressions(original_document, proposed_document)
    except ModelAuditError as error:
        reasons.append("uv_analysis_error")
        details["uvAnalysisError"] = str(error)
    else:
        if uv:
            reasons.append("uv_regression")
            details["uvRegressions"] = uv
    return _ordered_bulk_failures(reasons), details, proposed_audit


def _bulk_component_static_validation(
    original_document: dict[str, Any],
    component: dict[str, Any],
    protected_findings: Sequence[dict[str, Any]],
) -> tuple[list[str], dict[str, Any]]:
    moved_nodes = {
        (int(operation["element"]), str(operation["face"]))
        for operation in component["operations"]
    }
    contact_findings = [
        finding
        for finding in protected_findings
        if _bulk_node(finding["a"]) in moved_nodes or _bulk_node(finding["b"]) in moved_nodes
    ]
    reasons: list[str] = []
    details: dict[str, Any] = {}
    opposite_contacts = sorted(
        finding["finding_id"]
        for finding in contact_findings
        if finding["orientation"] == "opposite"
    )
    other_contacts = sorted(
        finding["finding_id"]
        for finding in contact_findings
        if finding["orientation"] != "opposite"
    )
    if opposite_contacts:
        reasons.append("opposite_facing_contact")
        details["oppositeContactFindingIds"] = opposite_contacts
    if other_contacts:
        reasons.append("excluded_finding_contact")
        details["otherContactFindingIds"] = other_contacts

    proposed = _apply_bulk_operations(original_document, component["operations"])
    bounds = _bounds_regressions(original_document, proposed)
    if bounds:
        reasons.append("bounds_regression")
        details["boundsRegressions"] = bounds
    try:
        uv = _uv_regressions(original_document, proposed)
    except ModelAuditError as error:
        reasons.append("uv_analysis_error")
        details["uvAnalysisError"] = str(error)
    else:
        if uv:
            reasons.append("uv_regression")
            details["uvRegressions"] = uv
    return _ordered_bulk_failures(reasons), details


def _blocked_bulk_component(
    *,
    canonical_path: str,
    source_sha256: str,
    component: dict[str, Any],
    reasons: Sequence[str],
    details: dict[str, Any],
) -> dict[str, Any]:
    return {
        "canonicalPath": canonical_path,
        "sourceSha256": source_sha256,
        "componentId": component["componentId"],
        "findingIds": component["findingIds"],
        "operationCount": len(component["operations"]),
        "operations": component["operations"],
        "reasons": list(reasons),
        "diagnostics": _compact_bulk_diagnostics(details),
    }


def _plan_bulk_model(
    document: dict[str, Any],
    audit: dict[str, Any],
) -> dict[str, Any]:
    canonical_path = audit["path"]
    source_sha256 = audit["sha256"]
    dispositions: dict[str, str] = {}
    candidates: list[dict[str, Any]] = []
    for finding in audit["findings"]:
        exclusion = _bulk_finding_exclusion(finding, audit, document)
        if exclusion is None:
            candidates.append(finding)
        else:
            dispositions[finding["finding_id"]] = exclusion

    components = _build_bulk_components(document, canonical_path, candidates)
    candidate_ids = {finding["finding_id"] for finding in candidates}
    protected_findings = [
        finding for finding in audit["findings"] if finding["finding_id"] not in candidate_ids
    ]
    blocked: list[dict[str, Any]] = []
    component_failure_counts: dict[str, int] = {}

    def record_block(
        component: dict[str, Any], reasons: Sequence[str], details: dict[str, Any]
    ) -> None:
        ordered = _ordered_bulk_failures(reasons)
        if not ordered:
            raise AssertionError("Blocked bulk component requires a reason")
        blocked.append(
            _blocked_bulk_component(
                canonical_path=canonical_path,
                source_sha256=source_sha256,
                component=component,
                reasons=ordered,
                details=details,
            )
        )
        for reason in ordered:
            component_failure_counts[reason] = component_failure_counts.get(reason, 0) + 1
        primary = ordered[0]
        for finding_id in component["findingIds"]:
            dispositions[finding_id] = primary

    statically_safe: list[dict[str, Any]] = []
    for component in components:
        reasons, details = _bulk_component_static_validation(
            document, component, protected_findings
        )
        if reasons:
            record_block(component, reasons, details)
        else:
            statically_safe.append(component)

    accepted: list[dict[str, Any]] = []
    working_document = document
    working_audit = audit
    if statically_safe:
        batch_operations = [
            operation
            for component in statically_safe
            for operation in component["operations"]
        ]
        batch_target_ids = {
            finding_id
            for component in statically_safe
            for finding_id in component["findingIds"]
        }
        batch_document = _apply_bulk_operations(document, batch_operations)
        batch_reasons, _, batch_audit = _bulk_transition_validation(
            document, batch_document, audit, batch_target_ids
        )
        if not batch_reasons:
            accepted = statically_safe
            working_document = batch_document
            working_audit = batch_audit
        else:
            accepted_ids: set[str] = set()
            for component in statically_safe:
                proposed = _apply_bulk_operations(working_document, component["operations"])
                proposed_ids = accepted_ids | set(component["findingIds"])
                reasons, details, proposed_audit = _bulk_transition_validation(
                    document, proposed, audit, proposed_ids
                )
                if reasons:
                    record_block(component, reasons, details)
                else:
                    accepted.append(component)
                    accepted_ids = proposed_ids
                    working_document = proposed
                    working_audit = proposed_audit

    eligible_ids = sorted(
        finding_id for component in accepted for finding_id in component["findingIds"]
    )
    for finding_id in eligible_ids:
        dispositions[finding_id] = "eligible"
    if len(dispositions) != len(audit["findings"]):
        missing = sorted(
            {finding["finding_id"] for finding in audit["findings"]} - set(dispositions)
        )
        raise AssertionError(f"Bulk finding disposition is incomplete for {canonical_path}: {missing[:5]}")

    plan_model: dict[str, Any] | None = None
    if accepted:
        operations = sorted(
            (operation for component in accepted for operation in component["operations"]),
            key=lambda operation: (
                int(operation["element"]), FACE_INDEX[str(operation["face"])], operation["componentId"]
            ),
        )
        before_count = len(audit["findings"])
        after_count = len(working_audit["findings"])
        if before_count - after_count != len(eligible_ids):
            raise AssertionError(f"Final bulk reduction mismatch for {canonical_path}")
        plan_model = {
            "canonicalPath": canonical_path,
            "sourceSha256": source_sha256,
            "findingsBefore": before_count,
            "findingsAfter": after_count,
            "sameFacingFindingsBefore": sum(
                finding["orientation"] == "same" for finding in audit["findings"]
            ),
            "sameFacingFindingsAfter": sum(
                finding["orientation"] == "same" for finding in working_audit["findings"]
            ),
            "expectedFindingReduction": len(eligible_ids),
            "predictedNewFindings": 0,
            "predictedBoundsRegressions": 0,
            "predictedUvRegressions": 0,
            "eligibleFindingIds": eligible_ids,
            "simulatedCanonicalJsonSha256": _canonical_document_sha256(working_document),
            "components": [
                {
                    "componentId": component["componentId"],
                    "findingIds": component["findingIds"],
                    "operationCount": len(component["operations"]),
                }
                for component in accepted
            ],
            "operations": operations,
        }

    return {
        "planModel": plan_model,
        "blockedComponents": blocked,
        "dispositions": dispositions,
        "candidateFindings": len(candidates),
        "components": len(components),
        "eligibleComponents": len(accepted),
        "componentFailureCounts": component_failure_counts,
    }


def _full_recess_round_operations(
    document: dict[str, Any], components: Sequence[dict[str, Any]], round_number: int
) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    translated_flat_planes: set[tuple[int, int]] = set()
    for component in components:
        colours: dict[BulkNode, int] = component["colours"]
        maximum_colour = max(colours.values())
        edge_nodes: dict[str, tuple[BulkNode, BulkNode]] = component["edgeNodes"]
        for node in sorted(colours, key=lambda item: (item[0], FACE_INDEX[item[1]])):
            signed_steps = colours[node] - maximum_colour
            if signed_steps == 0:
                continue
            endpoint, axis, outward_sign = OUTWARD_ENDPOINT[node[1]]
            elements = document["elements"]
            element = elements[node[0]]
            start = numeric_vector(element.get("from"), 3, "full-recess from")
            end = numeric_vector(element.get("to"), 3, "full-recess to")
            collapsed_plane = abs(start[axis] - end[axis]) <= 1.0e-12
            if collapsed_plane and (node[0], axis) in translated_flat_planes:
                continue
            if collapsed_plane:
                translated_flat_planes.add((node[0], axis))
                pointers = [
                    f"/elements/{node[0]}/from/{axis}",
                    f"/elements/{node[0]}/to/{axis}",
                ]
            else:
                pointers = [f"/elements/{node[0]}/{endpoint}/{axis}"]
            incident = sorted(
                finding_id
                for finding_id, endpoints in edge_nodes.items()
                if node in endpoints
            )
            for pointer in pointers:
                expected = _pointer_get(document, pointer)
                if isinstance(expected, bool) or not isinstance(expected, (int, float)):
                    raise ModelAuditError(f"Full-recess pointer is not numeric: {pointer}")
                proposed = round(
                    float(expected) + outward_sign * NUDGE_AMOUNT * signed_steps, 12
                )
                operations.append(
                    {
                        "action": (
                            "translate_zero_thickness_face_plane"
                            if collapsed_plane
                            else "recess_face_inward"
                        ),
                        "amount": clean_number(abs(NUDGE_AMOUNT * signed_steps)),
                        "componentId": component["componentId"],
                        "element": node[0],
                        "face": node[1],
                        "findingIds": incident,
                        "jsonPointer": pointer,
                        "round": round_number,
                        "signedOffsetSteps": signed_steps,
                        "expectedNumber": clean_number(float(expected)),
                        "proposedNumber": clean_number(proposed),
                    }
                )
    return sorted(
        operations,
        key=lambda operation: (
            int(operation["element"]), FACE_INDEX[str(operation["face"])], operation["componentId"]
        ),
    )


def _full_recess_blocked_record(
    audit: dict[str, Any], finding_ids: Sequence[str], reason: str, details: dict[str, Any]
) -> dict[str, Any]:
    identity = audit["path"] + "\0" + reason + "\0" + "\0".join(sorted(finding_ids))
    return {
        "canonicalPath": audit["path"],
        "sourceSha256": audit["sha256"],
        "componentId": "zfrb-" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20],
        "findingIds": sorted(finding_ids),
        "operationCount": 0,
        "operations": [],
        "reasons": [reason],
        "diagnostics": _compact_bulk_diagnostics(details),
    }


def _plan_full_recess_model(
    document: dict[str, Any],
    audit: dict[str, Any],
    *,
    raw_authoring: bool = False,
) -> dict[str, Any]:
    initial_targets = [
        finding
        for finding in audit["findings"]
        if finding["orientation"] == "same" and finding["plane_quality"] == "exact"
    ]
    if not initial_targets:
        return {"planModel": None, "blocked": None, "finalAudit": audit}
    target_ids = sorted(finding["finding_id"] for finding in initial_targets)
    zero_thickness_only = _only_zero_thickness_is_unsupported(audit)
    if (
        (not audit["repair_eligible"] or audit["status"] != "scanned")
        and not zero_thickness_only
    ):
        return {
            "planModel": None,
            "blocked": _full_recess_blocked_record(
                audit,
                target_ids,
                _unsupported_disposition(audit),
                {"unsupported": audit["unsupported"]},
            ),
            "finalAudit": audit,
        }
    if not raw_authoring and any(
        _element_uses_rescale(document, _bulk_node(finding[endpoint]))
        for finding in initial_targets
        for endpoint in ("a", "b")
    ):
        return {
            "planModel": None,
            "blocked": _full_recess_blocked_record(
                audit, target_ids, "rescaled_element", {}
            ),
            "finalAudit": audit,
        }

    original_document = document
    working_document = document
    working_audit = audit
    accumulated: dict[str, dict[str, Any]] = {}
    rounds: list[dict[str, Any]] = []
    failure: tuple[str, dict[str, Any]] | None = None
    for round_number in range(1, FULL_RECESS_MAX_ROUNDS + 1):
        round_findings = [
            finding
            for finding in working_audit["findings"]
            if finding["orientation"] == "same" and finding["plane_quality"] == "exact"
        ]
        if not round_findings:
            break
        if not raw_authoring and any(
            _element_uses_rescale(working_document, _bulk_node(finding[endpoint]))
            for finding in round_findings
            for endpoint in ("a", "b")
        ):
            failure = ("rescaled_element", {})
            break
        components = _build_bulk_components(
            working_document, audit["path"], round_findings
        )
        round_operations = _full_recess_round_operations(
            working_document, components, round_number
        )
        if not round_operations:
            failure = (
                "full_recess_no_progress",
                {"round": round_number, "findingCount": len(round_findings)},
            )
            break
        proposed_document = _apply_bulk_operations(working_document, round_operations)
        proposed_audit = scan_model_document(
            proposed_document,
            relative_path=audit["path"],
            source_sha256=_canonical_document_sha256(proposed_document),
            raw_authoring=raw_authoring,
            enable_raw_transforms=raw_authoring,
        )
        bounds = _bounds_regressions(original_document, proposed_document)
        try:
            uv = _uv_regressions(
                original_document,
                proposed_document,
                raw_authoring=raw_authoring,
            )
        except ModelAuditError as error:
            failure = ("uv_analysis_error", {"error": str(error), "round": round_number})
            break
        inversions = _inversion_regressions(original_document, proposed_document)
        raw_cull_transition = (
            _raw_cull_boundary_transition(original_document, proposed_document)
            if raw_authoring
            else None
        )
        post_supported = (
            proposed_audit["repair_eligible"] and proposed_audit["status"] == "scanned"
        ) or _only_zero_thickness_is_unsupported(proposed_audit)
        if not post_supported:
            failure = (
                "unsupported_post_geometry",
                {"round": round_number, "unsupported": proposed_audit["unsupported"]},
            )
            break
        if raw_authoring and proposed_audit.get("rawAuthoring") != audit.get("rawAuthoring"):
            failure = (
                "raw_transform_semantics_changed",
                {
                    "round": round_number,
                    "before": audit.get("rawAuthoring"),
                    "after": proposed_audit.get("rawAuthoring"),
                },
            )
            break
        if bounds:
            failure = (
                "bounds_regression",
                {"round": round_number, "boundsRegressions": bounds},
            )
            break
        if uv:
            failure = (
                "uv_regression",
                {"round": round_number, "uvRegressions": uv},
            )
            break
        if inversions:
            failure = (
                "inversion_regression",
                {"round": round_number, "elementIndices": inversions},
            )
            break
        if raw_cull_transition and raw_cull_transition["newlyHiddenFacePointers"]:
            failure = (
                "raw_cullface_newly_hidden",
                {
                    "round": round_number,
                    "facePointers": raw_cull_transition["newlyHiddenFacePointers"],
                },
            )
            break

        for operation in round_operations:
            pointer = operation["jsonPointer"]
            aggregate = accumulated.get(pointer)
            if aggregate is None:
                aggregate = {
                    "action": operation["action"],
                    "componentIds": set(),
                    "element": operation["element"],
                    "face": operation["face"],
                    "findingIds": set(),
                    "jsonPointer": pointer,
                    "rounds": [],
                    "signedOffsetSteps": 0,
                    "expectedNumber": clean_number(
                        float(_pointer_get(original_document, pointer))
                    ),
                    "proposedNumber": operation["proposedNumber"],
                }
                accumulated[pointer] = aggregate
            aggregate["componentIds"].add(operation["componentId"])
            aggregate["findingIds"].update(operation["findingIds"])
            aggregate["rounds"].append(round_number)
            aggregate["signedOffsetSteps"] += operation["signedOffsetSteps"]
            aggregate["proposedNumber"] = operation["proposedNumber"]
        remaining_exact = sum(
            finding["orientation"] == "same" and finding["plane_quality"] == "exact"
            for finding in proposed_audit["findings"]
        )
        rounds.append(
            {
                "round": round_number,
                "components": len(components),
                "findingsBefore": len(round_findings),
                "findingsAfter": remaining_exact,
                "scalarTouches": len(round_operations),
            }
        )
        working_document = proposed_document
        working_audit = proposed_audit
        if remaining_exact == 0:
            break
    else:
        failure = (
            "full_recess_non_convergent",
            {"maximumRounds": FULL_RECESS_MAX_ROUNDS},
        )

    remaining_targets = [
        finding
        for finding in working_audit["findings"]
        if finding["orientation"] == "same" and finding["plane_quality"] == "exact"
    ]
    if failure is None and remaining_targets:
        failure = (
            "full_recess_non_convergent",
            {"remainingFindingIds": [finding["finding_id"] for finding in remaining_targets]},
        )
    if failure is not None:
        reason, details = failure
        return {
            "planModel": None,
            "blocked": _full_recess_blocked_record(audit, target_ids, reason, details),
            "finalAudit": audit,
        }

    raw_cull_override_insertions: list[dict[str, Any]] = []
    raw_physical_cull_transition: dict[str, Any] | None = None
    if raw_authoring:
        raw_physical_cull_transition = _raw_cull_boundary_transition(
            original_document, working_document
        )
        for face_pointer in raw_physical_cull_transition[
            "movedOffPhysicalBoundaryFacePointers"
        ]:
            original_face = _pointer_get(original_document, face_pointer)
            if not isinstance(original_face, dict):
                raise AssertionError(f"Raw cullface pointer is not an object: {face_pointer}")
            existing_override = original_face.get("erydon_cull_boundary_override")
            if existing_override is True:
                continue
            if "erydon_cull_boundary_override" in original_face:
                return {
                    "planModel": None,
                    "blocked": _full_recess_blocked_record(
                        audit,
                        target_ids,
                        "raw_cull_override_not_absent",
                        {"facePointer": face_pointer, "value": existing_override},
                    ),
                    "finalAudit": audit,
                }
            tokens = face_pointer.strip("/").split("/")
            if len(tokens) != 4 or tokens[0] != "elements" or tokens[2] != "faces":
                raise AssertionError(f"Unexpected raw face pointer: {face_pointer}")
            raw_cull_override_insertions.append(
                {
                    "action": "insert_raw_cull_boundary_override",
                    "element": int(tokens[1]),
                    "face": tokens[3],
                    "facePointer": face_pointer,
                    "jsonPointer": face_pointer
                    + "/erydon_cull_boundary_override",
                    "expectedAbsent": True,
                    "proposedValue": True,
                    "reason": (
                        "face was loader boundary-eligible before recess and moved off the "
                        "physical 0/16 boundary solely because of the planned micro-recess"
                    ),
                }
            )
        raw_cull_override_insertions.sort(
            key=lambda item: (int(item["element"]), FACE_INDEX[str(item["face"])])
        )
        if raw_cull_override_insertions:
            working_document = _apply_raw_cull_override_insertions(
                working_document, raw_cull_override_insertions
            )
            working_audit = scan_model_document(
                working_document,
                relative_path=audit["path"],
                source_sha256=_canonical_document_sha256(working_document),
                raw_authoring=True,
                enable_raw_transforms=True,
            )
        effective_cull_transition = _raw_cull_boundary_transition(
            original_document, working_document
        )
        if (
            effective_cull_transition["newlyHiddenFacePointers"]
            or effective_cull_transition["newlyUnculledFacePointers"]
        ):
            return {
                "planModel": None,
                "blocked": _full_recess_blocked_record(
                    audit,
                    target_ids,
                    "raw_cull_override_postcondition",
                    {
                        "newlyHiddenFacePointers": effective_cull_transition[
                            "newlyHiddenFacePointers"
                        ],
                        "newlyUnculledFacePointers": effective_cull_transition[
                            "newlyUnculledFacePointers"
                        ],
                    },
                ),
                "finalAudit": audit,
            }

    operations: list[dict[str, Any]] = []
    maximum_displacement = 0.0
    for pointer in sorted(
        accumulated,
        key=lambda value: tuple(
            int(token) if token.isdigit() else token for token in value.strip("/").split("/")
        ),
    ):
        aggregate = accumulated[pointer]
        expected = float(aggregate["expectedNumber"])
        proposed = float(aggregate["proposedNumber"])
        displacement = abs(int(aggregate["signedOffsetSteps"])) * NUDGE_AMOUNT
        maximum_displacement = max(maximum_displacement, displacement)
        operations.append(
            {
                "action": aggregate["action"],
                "amount": clean_number(displacement),
                "componentIds": sorted(aggregate["componentIds"]),
                "element": aggregate["element"],
                "face": aggregate["face"],
                "findingIds": sorted(aggregate["findingIds"]),
                "jsonPointer": pointer,
                "rounds": aggregate["rounds"],
                "signedOffsetSteps": aggregate["signedOffsetSteps"],
                "expectedNumber": aggregate["expectedNumber"],
                "proposedNumber": aggregate["proposedNumber"],
            }
        )

    count = lambda findings, orientation=None, plane=None: sum(
        (orientation is None or finding["orientation"] == orientation)
        and (plane is None or finding["plane_quality"] == plane)
        for finding in findings
    )
    before_findings = audit["findings"]
    after_findings = working_audit["findings"]
    model = {
        "canonicalPath": audit["path"],
        "sourceKind": "raw_authoring" if raw_authoring else "standard_java_model",
        "sourceSha256": audit["sha256"],
        "findingsBefore": len(before_findings),
        "findingsAfter": len(after_findings),
        "exactSameFacingFindingsBefore": len(initial_targets),
        "exactSameFacingFindingsAfter": 0,
        "nearFindingsBefore": count(before_findings, plane="near"),
        "nearFindingsAfter": count(after_findings, plane="near"),
        "oppositeFacingFindingsBefore": count(before_findings, orientation="opposite"),
        "oppositeFacingFindingsAfter": count(after_findings, orientation="opposite"),
        "oppositeContactsMicroSeparated": (
            count(before_findings, orientation="opposite")
            - count(after_findings, orientation="opposite")
        ),
        "roundCount": len(rounds),
        "rounds": rounds,
        "maximumScalarDisplacement": clean_number(maximum_displacement),
        "predictedBoundsRegressions": 0,
        "predictedUvRegressions": 0,
        "predictedInversions": 0,
        "simulatedCanonicalJsonSha256": _canonical_document_sha256(working_document),
        "operations": operations,
    }
    if raw_authoring:
        model["rawAuthoring"] = audit["rawAuthoring"]
        final_raw_cull = _raw_cull_boundary_transition(
            original_document, working_document
        )
        if raw_physical_cull_transition is None:
            raise AssertionError("Raw physical cull transition was not initialized")
        model["rawCullBoundaryOverrideInsertions"] = raw_cull_override_insertions
        model["rawCullBoundary"] = {
            **final_raw_cull,
            "physicallyMovedOffBoundaryFacePointers": raw_physical_cull_transition[
                "movedOffPhysicalBoundaryFacePointers"
            ],
            "physicallyMovedOffBoundaryFaceCount": len(
                raw_physical_cull_transition[
                    "movedOffPhysicalBoundaryFacePointers"
                ]
            ),
            "overrideInsertionCount": len(raw_cull_override_insertions),
            "newlyHiddenFaceCount": len(
                final_raw_cull["newlyHiddenFacePointers"]
            ),
            "newlyUnculledFaceCount": len(
                final_raw_cull["newlyUnculledFacePointers"]
            ),
        }
    return {"planModel": model, "blocked": None, "finalAudit": working_audit}


def _generate_full_recess_plan(
    root: Path, raw_root: Path | None = None
) -> dict[str, Any]:
    standard_files = sorted(
        (path for path in root.rglob("*.json") if path.is_file()),
        key=lambda path: path.as_posix().lower(),
    )
    source_files: list[tuple[Path, str, bool]] = [
        (path, CANONICAL_BLOCK_PREFIX + path.relative_to(root).as_posix(), False)
        for path in standard_files
    ]
    raw_files: list[Path] = []
    if raw_root is not None:
        for relative in REGISTERED_RAW_MODEL_PATHS:
            model_file, normalized = _raw_model_file_from_canonical_path(
                raw_root, relative
            )
            if normalized != relative or not model_file.is_file():
                raise ModelAuditError(
                    "Registered raw-authoring model is missing or non-canonical: "
                    + RAW_CANONICAL_BLOCK_PREFIX
                    + relative
                )
            raw_files.append(model_file)
            source_files.append(
                (model_file, RAW_CANONICAL_BLOCK_PREFIX + relative, True)
            )
    source_files.sort(key=lambda item: item[1].lower())
    manifest_rows: list[tuple[str, str]] = []
    planned_models: list[dict[str, Any]] = []
    blocked: list[dict[str, Any]] = []
    dispositions: dict[str, int] = {}
    totals = {
        "findingsBefore": 0,
        "findingsAfter": 0,
        "exactSameBefore": 0,
        "exactSameAfter": 0,
        "nearBefore": 0,
        "nearAfter": 0,
        "oppositeBefore": 0,
        "oppositeAfter": 0,
    }
    files_with_findings = 0
    unsupported_models = 0
    raw_exact_same_before = 0
    raw_exact_same_after = 0
    raw_near_before = 0
    raw_near_after = 0
    raw_planned_models = 0
    raw_cullfaces_authored = 0
    raw_cull_boundary_before = 0
    raw_cull_boundary_after = 0
    raw_cull_newly_unculled = 0
    raw_cull_newly_hidden = 0
    raw_cull_physically_moved_off_boundary = 0
    raw_cull_override_insertions = 0
    for model_file, canonical_path, raw_authoring in source_files:
        source = model_file.read_bytes()
        source_sha = sha256_bytes(source)
        manifest_rows.append((canonical_path, source_sha))
        try:
            document = json.loads(source.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ModelAuditError(f"Full-recess planner requires valid JSON: {canonical_path}: {error}")
        if not isinstance(document, dict):
            raise ModelAuditError(f"Full-recess model root must be an object: {canonical_path}")
        audit = scan_model_document(
            document,
            relative_path=canonical_path,
            source_sha256=source_sha,
            raw_authoring=raw_authoring,
            enable_raw_transforms=raw_authoring,
        )
        if audit["status"] == "unsupported":
            unsupported_models += 1
        if audit["findings"]:
            files_with_findings += 1
        result = _plan_full_recess_model(
            document, audit, raw_authoring=raw_authoring
        )
        final_audit = result["finalAudit"]
        if result["planModel"] is not None:
            planned_models.append(result["planModel"])
            if raw_authoring:
                raw_planned_models += 1
        if result["blocked"] is not None:
            blocked.append(result["blocked"])

        for finding in audit["findings"]:
            if finding["orientation"] == "opposite":
                disposition = "opposite_facing_intentional_join_disclosed"
            elif finding["plane_quality"] != "exact":
                disposition = "near_not_exact_coplanar"
            elif not audit["repair_eligible"] and not _only_zero_thickness_is_unsupported(audit):
                disposition = _unsupported_disposition(audit)
            elif not raw_authoring and (
                _element_uses_rescale(document, _bulk_node(finding["a"])) or _element_uses_rescale(
                document, _bulk_node(finding["b"])
                )
            ):
                disposition = "rescaled_element"
            elif result["blocked"] is not None:
                disposition = "blocked_full_recess_" + result["blocked"]["reasons"][0]
            else:
                disposition = "eligible_full_recess"
            dispositions[disposition] = dispositions.get(disposition, 0) + 1

        def add_counts(target: str, findings: Sequence[dict[str, Any]]) -> None:
            totals["findings" + target] += len(findings)
            totals["exactSame" + target] += sum(
                finding["orientation"] == "same" and finding["plane_quality"] == "exact"
                for finding in findings
            )
            totals["near" + target] += sum(
                finding["plane_quality"] == "near" for finding in findings
            )
            totals["opposite" + target] += sum(
                finding["orientation"] == "opposite" for finding in findings
            )

        add_counts("Before", audit["findings"])
        add_counts("After", final_audit["findings"])
        if raw_authoring:
            raw_exact_same_before += sum(
                finding["orientation"] == "same"
                and finding["plane_quality"] == "exact"
                for finding in audit["findings"]
            )
            raw_exact_same_after += sum(
                finding["orientation"] == "same"
                and finding["plane_quality"] == "exact"
                for finding in final_audit["findings"]
            )
            raw_near_before += sum(
                finding["plane_quality"] == "near" for finding in audit["findings"]
            )
            raw_near_after += sum(
                finding["plane_quality"] == "near"
                for finding in final_audit["findings"]
            )
            raw_cull = (
                result["planModel"]["rawCullBoundary"]
                if result["planModel"] is not None
                else _raw_cull_boundary_transition(document, document)
            )
            raw_cullfaces_authored += raw_cull["authoredCullfaces"]
            raw_cull_boundary_before += raw_cull["boundaryEligibleBefore"]
            raw_cull_boundary_after += raw_cull["boundaryEligibleAfter"]
            raw_cull_newly_unculled += len(raw_cull["newlyUnculledFacePointers"])
            raw_cull_newly_hidden += len(raw_cull["newlyHiddenFacePointers"])
            raw_cull_physically_moved_off_boundary += int(
                raw_cull.get("physicallyMovedOffBoundaryFaceCount", 0)
            )
            raw_cull_override_insertions += int(
                raw_cull.get("overrideInsertionCount", 0)
            )

    for disposition in (
        "eligible_full_recess",
        "opposite_facing_intentional_join_disclosed",
        "near_not_exact_coplanar",
        "rescaled_element",
        "unsupported_zero_thickness",
        "unsupported_raw_euler",
        "unsupported_rescale",
        "unsupported_geometry",
    ):
        dispositions.setdefault(disposition, 0)
    eligible = sum(
        int(model["exactSameFacingFindingsBefore"]) for model in planned_models
    )
    coordinate_operation_count = sum(
        len(model["operations"]) for model in planned_models
    )
    override_insertion_count = sum(
        len(model.get("rawCullBoundaryOverrideInsertions", ()))
        for model in planned_models
    )
    if override_insertion_count != raw_cull_override_insertions:
        raise AssertionError("Raw cull override insertion summary is inconsistent")
    operation_count = coordinate_operation_count + override_insertion_count
    maximum_displacement = max(
        (float(model["maximumScalarDisplacement"]) for model in planned_models), default=0.0
    )
    maximum_rounds = max((int(model["roundCount"]) for model in planned_models), default=0)
    return {
        "schemaVersion": BULK_PLAN_SCHEMA_VERSION,
        "mode": BULK_PLAN_MODE,
        "sourceWrites": False,
        "sourceRoots": [
            CANONICAL_BLOCK_PREFIX.removesuffix("/"),
            *(
                [RAW_CANONICAL_BLOCK_PREFIX.removesuffix("/")]
                if raw_root is not None
                else []
            ),
        ],
        "sourceManifestSha256": scan_manifest_sha256(manifest_rows),
        "algorithm": {
            "strategy": "full_recess",
            "explicitOptInFlag": "--include-full-recess-repair",
            "baseOffset": NUDGE_AMOUNT,
            "candidate": "same-facing exact-plane coplanar overlap only",
            "graphColourOrder": "face area descending, then element index and face order ascending",
            "layerTranslation": (
                "subtract each component maximum colour so layers are non-positive; the top layer "
                "stays fixed and underlying layers recess"
            ),
            "maximumRounds": FULL_RECESS_MAX_ROUNDS,
            "rawTransformSemantics": RAW_TRANSFORM_SEMANTICS,
            "rawGroupMembershipValidation": (
                "source manifest and per-model SHA lock the complete groups tree; planned raw "
                "models additionally lock a deterministic element-membership hash"
            ),
            "rawEffectiveUvValidation": (
                "explicit rect or transformed default UV, followed by optional "
                "erydon_uv_offset, may not gain out-of-range distance"
            ),
            "rawCullBoundaryValidation": (
                "match loader 0.0005 boundary eligibility; insert a strict true override only "
                "for a previously eligible cullface moved off-boundary solely by recess; require "
                "zero newly unculled and zero newly hidden faces"
            ),
            "oppositeFacingDisclosure": (
                "opposite-facing contacts are never classified as z-fighting, but recessing an "
                "underlying face may micro-separate and remove their audit contact"
            ),
            "validation": (
                "repeat full in-memory scans to convergence; require zero repair-eligible exact "
                "same-facing findings, no bounds/UV regression, and no inversion"
            ),
        },
        "summary": {
            "filesScanned": len(source_files),
            "standardFilesScanned": len(standard_files),
            "registeredRawFilesScanned": len(raw_files),
            "filesWithFindings": files_with_findings,
            "modelsWithGeometryAdvisories": unsupported_models,
            "eligibleModels": len(planned_models),
            "eligibleRawModels": raw_planned_models,
            "eligibleFindings": eligible,
            "eligibleOperations": operation_count,
            "eligibleCoordinateOperations": coordinate_operation_count,
            "rawCullBoundaryOverrideInsertions": override_insertion_count,
            "blockedModels": len(blocked),
            "blockedExactSameFacingFindings": sum(
                len(item["findingIds"]) for item in blocked
            ),
            "findingsBefore": totals["findingsBefore"],
            "predictedFindingsAfter": totals["findingsAfter"],
            "eligibleExactSameFacingFindingsBefore": eligible,
            "predictedEligibleExactSameFacingFindingsAfter": 0,
            "exactSameFacingFindingsBefore": totals["exactSameBefore"],
            "predictedExactSameFacingFindingsAfter": totals["exactSameAfter"],
            "rawExactSameFacingFindingsBefore": raw_exact_same_before,
            "predictedRawExactSameFacingFindingsAfter": raw_exact_same_after,
            "rawNearFindingsBefore": raw_near_before,
            "predictedRawNearFindingsAfter": raw_near_after,
            "rawCullfacesAuthored": raw_cullfaces_authored,
            "rawCullBoundaryEligibleBefore": raw_cull_boundary_before,
            "predictedRawCullBoundaryEligibleAfter": raw_cull_boundary_after,
            "rawCullfacesNewlyUnculled": raw_cull_newly_unculled,
            "predictedRawCullfacesNewlyHidden": raw_cull_newly_hidden,
            "rawCullfacesPhysicallyMovedOffBoundary": (
                raw_cull_physically_moved_off_boundary
            ),
            "nearFindingsBefore": totals["nearBefore"],
            "predictedNearFindingsAfter": totals["nearAfter"],
            "oppositeFacingFindingsBefore": totals["oppositeBefore"],
            "predictedOppositeFacingFindingsAfter": totals["oppositeAfter"],
            "oppositeContactsMicroSeparated": totals["oppositeBefore"] - totals["oppositeAfter"],
            "maximumRoundsUsed": maximum_rounds,
            "maximumScalarDisplacement": clean_number(maximum_displacement),
            "predictedBoundsRegressions": 0,
            "predictedUvRegressions": 0,
            "predictedInversions": 0,
            "findingDisposition": dict(sorted(dispositions.items())),
        },
        "models": sorted(planned_models, key=lambda model: model["canonicalPath"]),
        "blockedComponents": sorted(
            blocked, key=lambda item: (item["canonicalPath"], item["componentId"])
        ),
    }


def generate_bulk_nudge_plan(
    *,
    canonical_root: Path = DEFAULT_BLOCK_SOURCE_ROOT,
    raw_canonical_root: Path | None = None,
    include_full_recess_repair: bool = False,
) -> dict[str, Any]:
    """Generate a deterministic, source-locked, read-only bulk nudge plan."""

    root = canonical_models_root(canonical_root)
    if include_full_recess_repair:
        raw_root = (
            _canonical_raw_models_root(raw_canonical_root)
            if raw_canonical_root is not None
            else None
        )
        return _generate_full_recess_plan(root, raw_root)
    lexical_files = sorted(
        (path for path in root.rglob("*.json") if path.is_file()),
        key=lambda path: path.as_posix().lower(),
    )
    manifest_rows: list[tuple[str, str]] = []
    planned_models: list[dict[str, Any]] = []
    blocked_components: list[dict[str, Any]] = []
    disposition_counts: dict[str, int] = {}
    component_failure_counts: dict[str, int] = {}
    total_findings = 0
    files_with_findings = 0
    unsupported_models = 0
    candidate_findings = 0
    total_components = 0
    eligible_components = 0
    same_facing_findings = 0
    exact_same_facing_findings = 0
    opposite_facing_findings = 0

    for lexical_file in lexical_files:
        relative = lexical_file.relative_to(root).as_posix()
        model_file, normalized_relative = model_file_from_canonical_path(root, relative)
        if normalized_relative != relative:
            raise ModelAuditError(f"Non-canonical model path during bulk scan: {relative}")
        canonical_path = CANONICAL_BLOCK_PREFIX + relative
        try:
            source = model_file.read_bytes()
        except OSError as error:
            raise ModelAuditError(f"Could not read {canonical_path}: {error}") from error
        source_sha = sha256_bytes(source)
        manifest_rows.append((canonical_path, source_sha))
        try:
            document = json.loads(source.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ModelAuditError(f"Bulk planner requires valid UTF-8 JSON: {canonical_path}: {error}")
        if not isinstance(document, dict):
            raise ModelAuditError(f"Bulk planner requires an object model root: {canonical_path}")
        audit = scan_model_document(
            document,
            relative_path=canonical_path,
            source_sha256=source_sha,
            raw_authoring=False,
        )
        if audit["status"] == "unsupported":
            unsupported_models += 1
        if not audit["findings"]:
            continue
        files_with_findings += 1
        total_findings += len(audit["findings"])
        same_facing_findings += sum(
            finding["orientation"] == "same" for finding in audit["findings"]
        )
        exact_same_facing_findings += sum(
            finding["orientation"] == "same" and finding["plane_quality"] == "exact"
            for finding in audit["findings"]
        )
        opposite_facing_findings += sum(
            finding["orientation"] == "opposite" for finding in audit["findings"]
        )
        result = _plan_bulk_model(document, audit)
        candidate_findings += result["candidateFindings"]
        total_components += result["components"]
        eligible_components += result["eligibleComponents"]
        if result["planModel"] is not None:
            planned_models.append(result["planModel"])
        blocked_components.extend(result["blockedComponents"])
        for disposition in result["dispositions"].values():
            disposition_counts[disposition] = disposition_counts.get(disposition, 0) + 1
        for reason, count in result["componentFailureCounts"].items():
            component_failure_counts[reason] = component_failure_counts.get(reason, 0) + count

    eligible_findings = disposition_counts.get("eligible", 0)
    eligible_operations = sum(len(model["operations"]) for model in planned_models)
    for disposition in (
        "eligible",
        "opposite_facing_intentional_join",
        "near_not_exact_coplanar",
        "rescaled_element",
        "unsupported_zero_thickness",
        "unsupported_raw_euler",
        "unsupported_rescale",
        "unsupported_geometry",
    ):
        disposition_counts.setdefault(disposition, 0)
    if sum(disposition_counts.values()) != total_findings:
        raise AssertionError("Bulk plan disposition totals do not match the scanned findings")
    if candidate_findings != eligible_findings + sum(
        len(component["findingIds"]) for component in blocked_components
    ):
        raise AssertionError("Bulk candidate findings are not fully covered by plan or blocked manifest")

    manifest_sha = scan_manifest_sha256(manifest_rows)
    return {
        "schemaVersion": BULK_PLAN_SCHEMA_VERSION,
        "mode": BULK_PLAN_MODE,
        "sourceWrites": False,
        "sourceRoots": [CANONICAL_BLOCK_PREFIX.removesuffix("/")],
        "sourceManifestSha256": manifest_sha,
        "algorithm": {
            "strategy": "conservative_outward",
            "baseOffset": NUDGE_AMOUNT,
            "candidate": "same-facing exact-plane coplanar overlap",
            "graphColourOrder": "face area descending, then element index and face order ascending",
            "layerDistance": "baseOffset multiplied by the non-zero graph colour",
            "oppositeFacing": "excluded and preserved as intentional joins",
            "unsupported": "zero-thickness, rescaled, and raw-Euler geometry is excluded",
            "alternatives": (
                "No inward move or alternate recolouring is emitted automatically; any outward layer "
                "that fails validation remains in blockedComponents for explicit review."
            ),
            "validation": (
                "full in-memory final scan, exact finding reduction, no new conflict, no removed "
                "excluded finding, and no bounds or effective vanilla UV regression"
            ),
        },
        "summary": {
            "filesScanned": len(lexical_files),
            "filesWithFindings": files_with_findings,
            "unsupportedModels": unsupported_models,
            "findings": total_findings,
            "sameFacingFindings": same_facing_findings,
            "exactSameFacingFindings": exact_same_facing_findings,
            "oppositeFacingFindings": opposite_facing_findings,
            "candidateFindings": candidate_findings,
            "eligibleModels": len(planned_models),
            "eligibleComponents": eligible_components,
            "eligibleFindings": eligible_findings,
            "eligibleOperations": eligible_operations,
            "blockedComponents": len(blocked_components),
            "blockedCandidateFindings": candidate_findings - eligible_findings,
            "findingDisposition": dict(sorted(disposition_counts.items())),
            "componentValidationFailures": dict(sorted(component_failure_counts.items())),
            "predictedFindingsAfter": total_findings - eligible_findings,
            "predictedSameFacingFindingsAfter": same_facing_findings - eligible_findings,
            "predictedExactSameFacingFindingsAfter": (
                exact_same_facing_findings - eligible_findings
            ),
            "predictedNewFindings": 0,
            "predictedOppositeFacingFindingsAfter": opposite_facing_findings,
            "predictedBoundsRegressions": 0,
            "predictedUvRegressions": 0,
        },
        "models": sorted(planned_models, key=lambda model: model["canonicalPath"]),
        "blockedComponents": sorted(
            blocked_components,
            key=lambda component: (component["canonicalPath"], component["componentId"]),
        ),
    }


def _validate_bulk_plan_shape(plan: Any) -> dict[str, Any]:
    required = {
        "schemaVersion",
        "mode",
        "sourceWrites",
        "sourceRoots",
        "sourceManifestSha256",
        "algorithm",
        "summary",
        "models",
        "blockedComponents",
    }
    if not isinstance(plan, dict) or set(plan) != required:
        raise ModelAuditError("Bulk plan has an unexpected top-level schema")
    if plan.get("schemaVersion") != BULK_PLAN_SCHEMA_VERSION or plan.get("mode") != BULK_PLAN_MODE:
        raise ModelAuditError("Bulk plan schemaVersion or mode is unsupported")
    if plan.get("sourceWrites") is not False:
        raise ModelAuditError("Bulk plan must be read-only")
    if not isinstance(plan.get("sourceRoots"), list) or not all(
        isinstance(value, str) and value for value in plan["sourceRoots"]
    ):
        raise ModelAuditError("Bulk plan sourceRoots must be a non-empty string array")
    _valid_sha256(plan.get("sourceManifestSha256"), "sourceManifestSha256")
    if not isinstance(plan.get("models"), list) or not isinstance(plan.get("blockedComponents"), list):
        raise ModelAuditError("Bulk plan models and blockedComponents must be arrays")
    return plan


def write_bulk_nudge_plan(
    output_path: Path,
    *,
    canonical_root: Path = DEFAULT_BLOCK_SOURCE_ROOT,
    raw_canonical_root: Path | None = None,
    include_full_recess_repair: bool = False,
) -> tuple[dict[str, Any], str]:
    root = canonical_models_root(canonical_root)
    raw_root = (
        _canonical_raw_models_root(raw_canonical_root)
        if raw_canonical_root is not None
        else None
    )
    source_roots = (root,) + ((raw_root,) if raw_root is not None else ())
    ensure_reports_outside_sources((output_path,), source_roots)
    plan = generate_bulk_nudge_plan(
        canonical_root=root,
        raw_canonical_root=raw_root,
        include_full_recess_repair=include_full_recess_repair,
    )
    payload = (json.dumps(plan, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")
    output_path.resolve().parent.mkdir(parents=True, exist_ok=True)
    atomic_write_bytes(output_path, payload)
    readback = output_path.resolve().read_bytes()
    if readback != payload:
        raise ModelAuditError("Bulk-plan atomic write readback differs from generated bytes")
    readback_plan = json.loads(readback.decode("utf-8"))
    _validate_bulk_plan_shape(readback_plan)
    if readback_plan != plan:
        raise ModelAuditError("Bulk-plan readback differs from generated document")
    return plan, sha256_bytes(payload)


def validate_bulk_nudge_plan(
    plan_path: Path,
    expected_plan_sha256: str,
    *,
    canonical_root: Path = DEFAULT_BLOCK_SOURCE_ROOT,
    raw_canonical_root: Path | None = None,
    include_full_recess_repair: bool = False,
) -> dict[str, Any]:
    root = canonical_models_root(canonical_root)
    raw_root = (
        _canonical_raw_models_root(raw_canonical_root)
        if raw_canonical_root is not None
        else None
    )
    source_roots = (root,) + ((raw_root,) if raw_root is not None else ())
    ensure_reports_outside_sources((plan_path,), source_roots)
    expected_sha = _valid_sha256(expected_plan_sha256, "--expect-plan-sha256")
    try:
        payload = plan_path.read_bytes()
    except OSError as error:
        raise ModelAuditError(f"Could not read bulk plan: {plan_path.resolve()}: {error}") from error
    actual_sha = sha256_bytes(payload)
    if actual_sha != expected_sha:
        raise ModelAuditError(
            f"Bulk-plan SHA-256 mismatch: expected {expected_sha}, actual {actual_sha}"
        )
    try:
        plan = _validate_bulk_plan_shape(json.loads(payload.decode("utf-8-sig")))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Bulk plan is not valid UTF-8 JSON: {error}") from error
    strategy = plan.get("algorithm", {}).get("strategy")
    is_full_recess = strategy == "full_recess"
    if is_full_recess and not include_full_recess_repair:
        raise ModelAuditError(
            "Full-recess bulk plan requires explicit --include-full-recess-repair opt-in"
        )
    if include_full_recess_repair and not is_full_recess:
        raise ModelAuditError("Explicit full-recess opt-in was supplied for a conservative plan")
    regenerated = generate_bulk_nudge_plan(
        canonical_root=root,
        raw_canonical_root=raw_root,
        include_full_recess_repair=is_full_recess,
    )
    if plan.get("sourceManifestSha256") != regenerated["sourceManifestSha256"]:
        raise ModelAuditError(
            "Bulk plan is stale: canonical source manifest SHA-256 no longer matches"
        )
    if plan != regenerated:
        raise ModelAuditError("Bulk plan content does not match deterministic regeneration")
    return {
        "planSha256": actual_sha,
        "sourceManifestSha256": plan["sourceManifestSha256"],
        "summary": plan["summary"],
        "result": "validated_read_only",
    }


def _prepare_bulk_scalar_replacements(
    source_bytes: bytes,
    operations: Sequence[dict[str, Any]],
    insertions: Sequence[dict[str, Any]] = (),
) -> tuple[bytes, dict[str, Any], list[dict[str, Any]]]:
    bom = b"\xef\xbb\xbf" if source_bytes.startswith(b"\xef\xbb\xbf") else b""
    try:
        text = source_bytes[len(bom) :].decode("utf-8")
        before_document = json.loads(source_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Bulk source cannot be decoded for scalar replacement: {error}")
    if not isinstance(before_document, dict):
        raise ModelAuditError("Bulk source model root must be an object")
    spans = JsonSpanLocator(text).locate()
    expected_document = _apply_bulk_operations(before_document, operations)
    expected_document = _apply_raw_cull_override_insertions(
        expected_document, insertions
    )
    replacements: list[dict[str, Any]] = []
    seen_spans: set[tuple[int, int]] = set()

    def byte_offset(character_offset: int) -> int:
        return len(bom) + len(text[:character_offset].encode("utf-8"))

    for operation in operations:
        pointer = operation["jsonPointer"]
        if pointer not in spans:
            raise ModelAuditError(f"Bulk scalar pointer has no source token: {pointer}")
        char_start, char_end = spans[pointer]
        byte_start, byte_end = byte_offset(char_start), byte_offset(char_end)
        if (byte_start, byte_end) in seen_spans:
            raise ModelAuditError(f"Bulk scalar plan repeats a token span: {pointer}")
        seen_spans.add((byte_start, byte_end))
        original = source_bytes[byte_start:byte_end]
        replacement = json.dumps(
            operation["proposedNumber"],
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode("utf-8")
        replacements.append(
            {
                "editKind": "replace_numeric_token",
                "jsonPointer": pointer,
                "byteStart": byte_start,
                "byteEnd": byte_end,
                "originalToken": original.decode("utf-8"),
                "replacementToken": replacement.decode("utf-8"),
            }
        )

    for insertion in insertions:
        pointer = insertion["jsonPointer"]
        face_pointer = insertion["facePointer"]
        if pointer in spans:
            raise ModelAuditError(f"Raw cull override token is no longer absent: {pointer}")
        if face_pointer not in spans:
            raise ModelAuditError(
                f"Raw cull override face object has no source span: {face_pointer}"
            )
        char_start, char_end = spans[face_pointer]
        if char_end <= char_start or text[char_end - 1] != "}":
            raise ModelAuditError(
                f"Raw cull override target span is not a JSON object: {face_pointer}"
            )
        insertion_character = char_end - 1
        while (
            insertion_character > char_start
            and text[insertion_character - 1] in " \t\r\n"
        ):
            insertion_character -= 1
        if insertion_character <= char_start + 1:
            raise ModelAuditError(
                f"Raw cull override target face object is unexpectedly empty: {face_pointer}"
            )
        trailing = text[insertion_character : char_end - 1]
        if "\n" in trailing or "\r" in trailing:
            newline = "\r\n" if "\r\n" in text[char_start:char_end] else "\n"
            line_start = max(
                text.rfind("\n", char_start, insertion_character),
                text.rfind("\r", char_start, insertion_character),
            ) + 1
            member_indent_match = re.match(r"[ \t]*", text[line_start:insertion_character])
            member_indent = member_indent_match.group(0) if member_indent_match else ""
            insertion_text = (
                ","
                + newline
                + member_indent
                + '"erydon_cull_boundary_override": true'
            )
        else:
            insertion_text = ', "erydon_cull_boundary_override": true'
        byte_position = byte_offset(insertion_character)
        if (byte_position, byte_position) in seen_spans:
            raise ModelAuditError(
                f"Raw cull override insertion repeats a source position: {face_pointer}"
            )
        seen_spans.add((byte_position, byte_position))
        replacements.append(
            {
                "editKind": "insert_object_member",
                "jsonPointer": pointer,
                "byteStart": byte_position,
                "byteEnd": byte_position,
                "originalToken": "",
                "replacementToken": insertion_text,
            }
        )

    after_bytes = source_bytes
    for replacement in sorted(replacements, key=lambda item: item["byteStart"], reverse=True):
        after_bytes = (
            after_bytes[: replacement["byteStart"]]
            + replacement["replacementToken"].encode("utf-8")
            + after_bytes[replacement["byteEnd"] :]
        )
    try:
        after_document = json.loads(after_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Bulk scalar replacement produced invalid JSON: {error}")
    if after_document != expected_document:
        raise ModelAuditError("Bulk scalar deep comparison found an unplanned document change")
    return after_bytes, after_document, sorted(
        replacements, key=lambda item: item["jsonPointer"]
    )


def apply_full_recess_bulk_plan(
    plan_path: Path,
    expected_plan_sha256: str,
    *,
    canonical_root: Path = DEFAULT_BLOCK_SOURCE_ROOT,
    raw_canonical_root: Path | None = None,
    apply_report_path: Path = DEFAULT_BULK_APPLY_REPORT,
    include_full_recess_repair: bool = False,
) -> dict[str, Any]:
    """Atomically apply a fully validated opt-in recess plan, rolling back on any failure."""

    if not include_full_recess_repair:
        raise ModelAuditError(
            "Full-recess apply requires explicit --include-full-recess-repair opt-in"
        )
    root = canonical_models_root(canonical_root)
    raw_root = (
        _canonical_raw_models_root(raw_canonical_root)
        if raw_canonical_root is not None
        else None
    )
    source_roots = (root,) + ((raw_root,) if raw_root is not None else ())
    ensure_reports_outside_sources((plan_path, apply_report_path), source_roots)
    validation = validate_bulk_nudge_plan(
        plan_path,
        expected_plan_sha256,
        canonical_root=root,
        raw_canonical_root=raw_root,
        include_full_recess_repair=True,
    )
    plan_bytes = plan_path.read_bytes()
    plan = _validate_bulk_plan_shape(json.loads(plan_bytes.decode("utf-8-sig")))
    prepared: list[dict[str, Any]] = []
    for model in plan["models"]:
        model_file, canonical_path, raw_authoring = _model_from_bulk_canonical_path(
            root, raw_root, model["canonicalPath"]
        )
        expected_kind = "raw_authoring" if raw_authoring else "standard_java_model"
        if model.get("sourceKind") != expected_kind:
            raise ModelAuditError(
                f"Full-recess source kind mismatch for {canonical_path}: "
                f"{model.get('sourceKind')!r} != {expected_kind!r}"
            )
        before_bytes = model_file.read_bytes()
        before_sha = sha256_bytes(before_bytes)
        if before_sha != model["sourceSha256"]:
            raise ModelAuditError(
                f"Full-recess source SHA-256 mismatch for {canonical_path}: "
                f"{model['sourceSha256']} != {before_sha}"
            )
        after_bytes, after_document, replacements = _prepare_bulk_scalar_replacements(
            before_bytes,
            model["operations"],
            model.get("rawCullBoundaryOverrideInsertions", ()),
        )
        before_document = json.loads(before_bytes.decode("utf-8-sig"))
        before_audit = scan_model_document(
            before_document,
            relative_path=canonical_path,
            source_sha256=before_sha,
            raw_authoring=raw_authoring,
            enable_raw_transforms=raw_authoring,
        )
        if raw_authoring and before_audit.get("rawAuthoring") != model.get("rawAuthoring"):
            raise ModelAuditError(
                f"Full-recess raw group-membership/transform lock mismatch for {canonical_path}"
            )
        if _canonical_document_sha256(after_document) != model["simulatedCanonicalJsonSha256"]:
            raise ModelAuditError(
                f"Full-recess simulated document hash mismatch for {canonical_path}"
            )
        after_audit = scan_model_document(
            after_document,
            relative_path=canonical_path,
            source_sha256=sha256_bytes(after_bytes),
            raw_authoring=raw_authoring,
            enable_raw_transforms=raw_authoring,
        )
        if raw_authoring and after_audit.get("rawAuthoring") != model.get("rawAuthoring"):
            raise ModelAuditError(
                f"Full-recess raw transform semantics changed for {canonical_path}"
            )
        if raw_authoring:
            raw_cull = _raw_cull_boundary_transition(
                before_document, after_document
            )
            raw_cull_locked = {
                **raw_cull,
                "physicallyMovedOffBoundaryFacePointers": raw_cull[
                    "movedOffPhysicalBoundaryFacePointers"
                ],
                "physicallyMovedOffBoundaryFaceCount": len(
                    raw_cull["movedOffPhysicalBoundaryFacePointers"]
                ),
                "overrideInsertionCount": len(
                    model.get("rawCullBoundaryOverrideInsertions", ())
                ),
                "newlyHiddenFaceCount": len(
                    raw_cull["newlyHiddenFacePointers"]
                ),
                "newlyUnculledFaceCount": len(
                    raw_cull["newlyUnculledFacePointers"]
                ),
            }
            if raw_cull_locked != model.get("rawCullBoundary"):
                raise ModelAuditError(
                    f"Full-recess raw cull-boundary prediction mismatch for {canonical_path}"
                )
            if raw_cull["newlyHiddenFacePointers"]:
                raise ModelAuditError(
                    f"Full-recess would newly hide a raw cullface for {canonical_path}"
                )
        exact_same_after = sum(
            finding["orientation"] == "same" and finding["plane_quality"] == "exact"
            for finding in after_audit["findings"]
        )
        if exact_same_after != 0 or len(after_audit["findings"]) != model["findingsAfter"]:
            raise ModelAuditError(f"Full-recess post-audit mismatch for {canonical_path}")
        if _bounds_regressions(before_document, after_document):
            raise ModelAuditError(f"Full-recess bounds regression for {canonical_path}")
        if _uv_regressions(
            before_document, after_document, raw_authoring=raw_authoring
        ):
            raise ModelAuditError(f"Full-recess UV regression for {canonical_path}")
        if _inversion_regressions(before_document, after_document):
            raise ModelAuditError(f"Full-recess inversion for {canonical_path}")
        prepared.append(
            {
                "path": model_file,
                "canonicalPath": canonical_path,
                "beforeBytes": before_bytes,
                "afterBytes": after_bytes,
                "sourceSha256Before": before_sha,
                "sourceSha256After": sha256_bytes(after_bytes),
                "replacements": replacements,
            }
        )

    for item in prepared:
        if item["path"].read_bytes() != item["beforeBytes"]:
            raise ModelAuditError(
                f"Full-recess source changed before transaction: {item['canonicalPath']}"
            )
    written: list[dict[str, Any]] = []
    try:
        for item in prepared:
            written.append(item)
            atomic_write_bytes(item["path"], item["afterBytes"])
            if item["path"].read_bytes() != item["afterBytes"]:
                raise ModelAuditError(
                    f"Full-recess post-write readback failed: {item['canonicalPath']}"
                )
        report = {
            "schemaVersion": 1,
            "mode": "full_recess_bulk_apply",
            "sourceWrites": True,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "plan": {
                "path": str(plan_path.resolve()),
                "sha256": validation["planSha256"],
                "sourceManifestSha256": validation["sourceManifestSha256"],
            },
            "summary": plan["summary"],
            "modelsWritten": len(prepared),
            "sourceEditsChanged": sum(len(item["replacements"]) for item in prepared),
            "scalarTokensChanged": sum(
                replacement["editKind"] == "replace_numeric_token"
                for item in prepared
                for replacement in item["replacements"]
            ),
            "objectMembersInserted": sum(
                replacement["editKind"] == "insert_object_member"
                for item in prepared
                for replacement in item["replacements"]
            ),
            "oppositeFacingDisclosure": plan["algorithm"]["oppositeFacingDisclosure"],
            "files": [
                {
                    "canonicalPath": item["canonicalPath"],
                    "sourceSha256Before": item["sourceSha256Before"],
                    "sourceSha256After": item["sourceSha256After"],
                    "replacementCount": len(item["replacements"]),
                }
                for item in prepared
            ],
            "safety": {
                "explicitFullRecessOptIn": True,
                "planShaLocked": True,
                "sourceManifestLocked": True,
                "perModelSourceShaLocked": True,
                "rawGroupMembershipLocked": True,
                "rawTransformOrderVerified": True,
                "noRawCullfaceNewlyHidden": True,
                "rawCullfacesRemainDirectionallyCulled": True,
                "rawCullOverridesInsertedOnlyForProvenBoundaryLoss": True,
                "coordinateChangesReplaceScalarTokensOnly": True,
                "nonCoordinateChangesInsertLockedBooleanMembersOnly": True,
                "zeroExactSameFacingPostcondition": True,
                "noUvRegression": True,
                "noBoundsRegression": True,
                "noInversion": True,
                "rollbackOnFailure": True,
            },
            "result": "applied_and_verified",
        }
        atomic_write_text(apply_report_path, json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        return report
    except BaseException:
        rollback_errors: list[str] = []
        for item in reversed(written):
            try:
                atomic_write_bytes(item["path"], item["beforeBytes"])
                if item["path"].read_bytes() != item["beforeBytes"]:
                    raise OSError("rollback readback differs")
            except OSError as error:
                rollback_errors.append(f"{item['canonicalPath']}: {error}")
        if rollback_errors:
            raise ModelAuditError(
                "Full-recess apply failed and rollback was incomplete: "
                + "; ".join(rollback_errors)
            )
        raise


def apply_nudge_plan(
    plan_path: Path,
    expected_plan_sha256: str,
    *,
    canonical_root: Path = DEFAULT_BLOCK_SOURCE_ROOT,
    apply_report_path: Path = DEFAULT_APPLY_REPORT,
) -> dict[str, Any]:
    """Apply and verify one exact reviewed outward-nudge plan."""

    root = canonical_models_root(canonical_root)
    if plan_path.resolve() == apply_report_path.resolve():
        raise ModelAuditError("Apply plan and apply report must be different files")
    ensure_reports_outside_sources((plan_path, apply_report_path), (root,))
    plan, plan_sha = _load_nudge_plan(plan_path, expected_plan_sha256)
    entry = _validate_plan_entry(plan["candidate"])
    model_file, canonical_path = _model_from_project_canonical_path(root, entry["canonicalPath"])

    before_bytes = model_file.read_bytes()
    source_sha_before = sha256_bytes(before_bytes)
    if source_sha_before != entry["sourceSha256"]:
        raise ModelAuditError(
            f"Source SHA-256 mismatch for {canonical_path}: "
            f"expected {entry['sourceSha256']}, actual {source_sha_before}"
        )
    try:
        before_document = json.loads(before_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelAuditError(f"Source model is invalid UTF-8 JSON: {error}") from error
    if not isinstance(before_document, dict):
        raise ModelAuditError("Source model root must be an object")

    pre_scan = scan_model_document(
        before_document,
        relative_path=canonical_path,
        source_sha256=source_sha_before,
        raw_authoring=False,
    )
    if not pre_scan["repair_eligible"] or pre_scan["status"] != "scanned":
        raise ModelAuditError("Source model is not repair-eligible")
    matching = [
        finding for finding in pre_scan["findings"] if finding["finding_id"] == entry["findingId"]
    ]
    if len(matching) != 1:
        raise ModelAuditError(f"findingId is stale or ambiguous: {entry['findingId']}")
    finding = matching[0]
    if finding["orientation"] != "same" or finding["plane_quality"] != "exact":
        raise ModelAuditError("Reviewed nudge requires an exact-plane same-facing finding")
    expected_target = _deterministic_smaller_endpoint(finding)
    if entry["targetEndpoint"] != expected_target:
        raise ModelAuditError(
            f"targetEndpoint must select the deterministic smaller face: {expected_target}"
        )
    target = finding[entry["targetEndpoint"]]
    if entry["element"] != target["element"] or entry["face"] != target["face"]:
        raise ModelAuditError("Plan element/face does not match the selected finding endpoint")

    elements = before_document.get("elements")
    if not isinstance(elements, list) or entry["element"] >= len(elements):
        raise ModelAuditError("Plan element index is outside the model")
    element = elements[entry["element"]]
    if not isinstance(element, dict):
        raise ModelAuditError("Target element is not an object")
    rotation = element.get("rotation")
    if isinstance(rotation, dict) and rotation.get("rescale", False):
        raise ModelAuditError("Reviewed nudge does not permit rescaled elements")
    correct_pointer, outward_sign = _outward_pointer(entry["element"], entry["face"])
    if entry["jsonPointer"] != correct_pointer:
        raise ModelAuditError(
            f"jsonPointer is not the outward endpoint for {entry['face']}: expected {correct_pointer}"
        )
    expected_proposed = entry["expectedNumber"] + outward_sign * NUDGE_AMOUNT
    if not math.isclose(
        entry["proposedNumber"], expected_proposed, rel_tol=0.0, abs_tol=1.0e-12
    ):
        raise ModelAuditError(
            f"proposedNumber moves in the wrong direction or amount; expected {expected_proposed}"
        )

    after_bytes, after_document, replacement = _prepare_scalar_token_replacement(
        before_bytes,
        before_document,
        entry["jsonPointer"],
        entry["expectedNumber"],
        entry["proposedNumber"],
    )
    source_sha_after = sha256_bytes(after_bytes)
    pre_audit, proposed_audit, transition = _scan_transition(
        before_document,
        after_document,
        canonical_path=canonical_path,
        source_sha_before=source_sha_before,
        source_sha_after=source_sha_after,
        selected_finding_id=entry["findingId"],
        expected_reduction=entry["expectedFindingReduction"],
    )

    written = False
    try:
        if model_file.read_bytes() != before_bytes:
            raise ModelAuditError("Source changed between validation and atomic write")
        atomic_write_bytes(model_file, after_bytes)
        written = True
        readback = model_file.read_bytes()
        if readback != after_bytes or sha256_bytes(readback) != source_sha_after:
            raise ModelAuditError("Post-write byte/SHA readback verification failed")
        try:
            readback_document = json.loads(readback.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ModelAuditError("Post-write model is invalid JSON") from error
        if readback_document != after_document:
            raise ModelAuditError("Post-write deep comparison failed")
        post_pre_audit, post_audit, post_transition = _scan_transition(
            before_document,
            readback_document,
            canonical_path=canonical_path,
            source_sha_before=source_sha_before,
            source_sha_after=source_sha_after,
            selected_finding_id=entry["findingId"],
            expected_reduction=entry["expectedFindingReduction"],
        )
        if post_pre_audit["findings"] != pre_audit["findings"]:
            raise ModelAuditError("Post-readback pre-audit comparison changed unexpectedly")
        if post_audit["findings"] != proposed_audit["findings"] or post_transition != transition:
            raise ModelAuditError("Post-readback geometry audit differs from the validated proposal")

        report = {
            "schemaVersion": APPLY_REPORT_SCHEMA_VERSION,
            "mode": "reviewed_nudge_apply",
            "sourceWrites": True,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "plan": {"path": str(plan_path.resolve()), "sha256": plan_sha},
            "canonicalBlockModelsRoot": str(root),
            "canonicalPath": canonical_path,
            "findingId": entry["findingId"],
            "target": {
                "endpoint": entry["targetEndpoint"],
                "element": entry["element"],
                "face": entry["face"],
            },
            "sourceSha256Before": source_sha_before,
            "sourceSha256After": source_sha_after,
            "replacement": replacement,
            "transition": transition,
            "safety": {
                "planShaLocked": True,
                "sourceShaLocked": True,
                "canonicalPathLocked": True,
                "singleScalarTokenOnly": True,
                "deepCompareOnlySelectedScalarChanged": True,
                "sameFacingExactPlane": True,
                "deterministicSmallerFace": True,
                "noNewFindings": True,
                "expectedFindingReduction": entry["expectedFindingReduction"],
                "boundsWithin0To16": True,
                "noNewEffectiveUvOutOfRangeFaces": True,
                "postReadbackShaAndAudit": True,
                "rollbackOnFailure": True,
            },
            "result": "applied_and_verified",
        }
        atomic_write_text(apply_report_path, json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        return report
    except BaseException:
        if written:
            try:
                atomic_write_bytes(model_file, before_bytes)
                if model_file.read_bytes() != before_bytes:
                    raise OSError("rollback readback differs from original bytes")
            except OSError as rollback_error:
                raise ModelAuditError(f"Apply failed and rollback was incomplete: {rollback_error}")
        raise


def run_self_test() -> None:
    face = {"texture": "#stone"}
    duplicate = {
        "elements": [
            {"from": [0, 0, 0], "to": [4, 4, 4], "faces": {"north": face}},
            {"from": [0, 0, 0], "to": [4, 4, 4], "faces": {"north": face}},
        ]
    }
    result = scan_model_document(
        duplicate,
        relative_path="self-test.json",
        source_sha256=hashlib.sha256(b"self-test").hexdigest(),
    )
    assert len(result["findings"]) == 1
    assert result["findings"][0]["repair_classification"] == "AUTO_CANDIDATE"

    raw = {
        "elements": [
            {
                "from": [0, 0, 0],
                "to": [4, 4, 4],
                "rotation": {"x": 1, "y": 2, "z": 3, "origin": [8, 8, 8]},
                "faces": {"north": face},
            }
        ]
    }
    unsupported = scan_model_document(
        raw,
        relative_path="raw.json",
        source_sha256=hashlib.sha256(b"raw").hexdigest(),
        raw_authoring=True,
    )
    assert unsupported["status"] == "unsupported"
    assert unsupported["repair_eligible"] is False


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "roots",
        nargs="*",
        type=Path,
        help="model files or directories; defaults to ERYDON standard and raw block-model roots",
    )
    parser.add_argument("--base", type=Path, default=Path.cwd(), help="base path used in stable report IDs")
    parser.add_argument(
        "--json-report",
        type=Path,
        default=Path("build/reports/erydon-z-fighting.json"),
    )
    parser.add_argument(
        "--csv-report",
        type=Path,
        default=Path("build/reports/erydon-z-fighting.csv"),
    )
    def positive_integer(value: str) -> int:
        try:
            result = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError("must be a positive integer") from error
        if result <= 0:
            raise argparse.ArgumentTypeError("must be a positive integer")
        return result

    parser.add_argument(
        "--max-files", type=positive_integer, help="scan only the first N stable-sorted files"
    )
    parser.add_argument("--self-test", action="store_true", help="run embedded safety checks and exit")
    parser.add_argument(
        "--apply-plan",
        type=Path,
        help="apply one SHA-locked reviewed nudge plan instead of running the read-only audit",
    )
    parser.add_argument(
        "--expect-plan-sha256",
        help="required SHA-256 of the exact bytes supplied with --apply-plan",
    )
    parser.add_argument(
        "--apply-report",
        type=Path,
        default=DEFAULT_APPLY_REPORT,
        help="verified apply report (must remain outside the source-model root)",
    )
    parser.add_argument(
        "--generate-bulk-plan",
        type=Path,
        help=(
            "write a deterministic read-only graph-coloured plan; full-recess mode also includes "
            f"the {len(REGISTERED_RAW_MODEL_PATHS)} registered raw-authoring models"
        ),
    )
    parser.add_argument(
        "--validate-bulk-plan",
        type=Path,
        help="regenerate and validate a bulk plan against the live source manifest",
    )
    parser.add_argument(
        "--apply-bulk-plan",
        type=Path,
        help="apply a validated full-recess bulk plan (requires explicit opt-in and plan SHA)",
    )
    parser.add_argument(
        "--bulk-apply-report",
        type=Path,
        default=DEFAULT_BULK_APPLY_REPORT,
        help="verified full-recess transaction report outside the source root",
    )
    parser.add_argument(
        "--include-full-recess-repair",
        action="store_true",
        help="explicitly opt into inward micro-separation, including disclosed opposite contacts",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.apply_bulk_plan is not None:
        if args.expect_plan_sha256 is None or not args.include_full_recess_repair:
            print(
                "Full-recess bulk apply refused: --expect-plan-sha256 and "
                "--include-full-recess-repair are both required",
                file=sys.stderr,
            )
            return 2
        if (
            args.generate_bulk_plan is not None
            or args.validate_bulk_plan is not None
            or args.apply_plan is not None
            or args.roots
            or args.max_files is not None
            or args.self_test
        ):
            print(
                "Full-recess bulk apply refused: audit and other plan modes cannot be combined",
                file=sys.stderr,
            )
            return 2
        try:
            report = apply_full_recess_bulk_plan(
                args.apply_bulk_plan,
                args.expect_plan_sha256,
                canonical_root=DEFAULT_BLOCK_SOURCE_ROOT,
                raw_canonical_root=DEFAULT_RAW_BLOCK_SOURCE_ROOT,
                apply_report_path=args.bulk_apply_report,
                include_full_recess_repair=True,
            )
        except (ModelAuditError, OSError, ValueError) as error:
            print(f"Full-recess bulk apply refused: {error}", file=sys.stderr)
            return 2
        print(
            "Full-recess bulk plan applied and verified: "
            f"{report['modelsWritten']} models, {report['sourceEditsChanged']} source edits."
        )
        print(f"Apply report: {args.bulk_apply_report}")
        return 0
    if args.generate_bulk_plan is not None:
        if (
            args.validate_bulk_plan is not None
            or args.apply_plan is not None
            or args.apply_bulk_plan is not None
            or args.expect_plan_sha256 is not None
            or args.roots
            or args.max_files is not None
            or args.self_test
        ):
            print(
                "Bulk-plan generation refused: use it alone without audit/apply/validation options",
                file=sys.stderr,
            )
            return 2
        try:
            plan, plan_sha = write_bulk_nudge_plan(
                args.generate_bulk_plan,
                canonical_root=DEFAULT_BLOCK_SOURCE_ROOT,
                raw_canonical_root=(
                    DEFAULT_RAW_BLOCK_SOURCE_ROOT
                    if args.include_full_recess_repair
                    else None
                ),
                include_full_recess_repair=args.include_full_recess_repair,
            )
        except (ModelAuditError, OSError, ValueError) as error:
            print(f"Bulk-plan generation refused: {error}", file=sys.stderr)
            return 2
        summary = plan["summary"]
        blocked_findings = summary.get(
            "blockedCandidateFindings", summary.get("blockedExactSameFacingFindings", 0)
        )
        print(
            "Bulk nudge plan generated read-only: "
            f"{summary['eligibleModels']} models, {summary['eligibleFindings']} findings, "
            f"{blocked_findings} blocked candidate findings."
        )
        print(f"Plan:   {args.generate_bulk_plan}")
        print(f"SHA-256: {plan_sha}")
        return 0
    if args.validate_bulk_plan is not None:
        if args.expect_plan_sha256 is None:
            print(
                "Bulk-plan validation refused: --expect-plan-sha256 is required",
                file=sys.stderr,
            )
            return 2
        if args.apply_plan is not None or args.roots or args.max_files is not None or args.self_test:
            print(
                "Bulk-plan validation refused: audit and apply options cannot be combined",
                file=sys.stderr,
            )
            return 2
        try:
            result = validate_bulk_nudge_plan(
                args.validate_bulk_plan,
                args.expect_plan_sha256,
                canonical_root=DEFAULT_BLOCK_SOURCE_ROOT,
                raw_canonical_root=(
                    DEFAULT_RAW_BLOCK_SOURCE_ROOT
                    if args.include_full_recess_repair
                    else None
                ),
                include_full_recess_repair=args.include_full_recess_repair,
            )
        except (ModelAuditError, OSError, ValueError) as error:
            print(f"Bulk-plan validation refused: {error}", file=sys.stderr)
            return 2
        print(
            "Bulk nudge plan validated read-only: "
            f"{result['summary']['eligibleModels']} models, "
            f"{result['summary']['eligibleFindings']} eligible findings."
        )
        return 0
    if args.apply_plan is not None:
        if args.expect_plan_sha256 is None:
            print(
                "Reviewed nudge apply refused: --expect-plan-sha256 is required with --apply-plan",
                file=sys.stderr,
            )
            return 2
        if (
            args.roots
            or args.max_files is not None
            or args.self_test
            or args.generate_bulk_plan is not None
            or args.validate_bulk_plan is not None
            or args.apply_bulk_plan is not None
            or args.include_full_recess_repair
        ):
            print(
                "Reviewed nudge apply refused: roots, --max-files, and --self-test are audit-only",
                file=sys.stderr,
            )
            return 2
        try:
            report = apply_nudge_plan(
                args.apply_plan,
                args.expect_plan_sha256,
                canonical_root=DEFAULT_BLOCK_SOURCE_ROOT,
                apply_report_path=args.apply_report,
            )
        except (ModelAuditError, OSError) as error:
            print(f"Reviewed nudge apply refused: {error}", file=sys.stderr)
            return 2
        print(
            "Reviewed nudge applied and verified: "
            f"{report['canonicalPath']} {report['replacement']['jsonPointer']}"
        )
        print(f"Apply report: {args.apply_report}")
        return 0
    if args.expect_plan_sha256 is not None:
        print(
            "Reviewed nudge apply refused: --expect-plan-sha256 requires --apply-plan",
            file=sys.stderr,
        )
        return 2
    if args.include_full_recess_repair:
        print(
            "Full-recess opt-in requires bulk plan generation, validation, or apply",
            file=sys.stderr,
        )
        return 2
    if args.self_test:
        run_self_test()
        print("model_z_fighting_safety self-test passed")
        return 0
    roots = args.roots or [
        Path("src/main/resources/assets/erydon/models/block"),
        Path("src/main/resources/assets/erydon/authoring_models/block"),
    ]
    try:
        paths = discover_model_files(roots, args.max_files)
        validate_report_destinations((args.json_report, args.csv_report), roots)
        models = [scan_model_file(path, base=args.base) for path in paths]
        report = build_report(models, roots, args.base)
        atomic_write_text(
            args.json_report, json.dumps(report, indent=2, sort_keys=True) + "\n"
        )
        write_csv_report(args.csv_report, models)
    except ModelAuditError as error:
        print(f"Z-fighting audit refused: {error}", file=sys.stderr)
        return 2
    summary = report["summary"]
    print(
        "Z-fighting report complete: "
        f"{summary['files']} files, {summary['findings']} findings, "
        f"{summary['auto_candidates']} automatic candidates."
    )
    print(f"JSON: {args.json_report}")
    print(f"CSV:  {args.csv_report}")
    return 1 if summary["parse_or_read_errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
