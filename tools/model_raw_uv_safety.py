#!/usr/bin/env python3
"""Audit and safely repair UVs used by ERYDON's raw authoring loader.

The default mode is read-only.  The tool mirrors the loader's element/group
Euler transforms and float arithmetic, then reports effective per-vertex UVs.
It can emit a deterministic, full-root SHA-locked plan for every rotated face:

* explicit UVs are translated directly, using exact whole-sprite shifts where
  possible and a closest uniform boundary reanchor otherwise;
* implicit/default UVs remain implicit and receive ``erydon_uv_offset`` only.

Apply is deliberately separate and opt-in.  It accepts only an externally
SHA-verified plan that exactly regenerates from the locked source inventory,
changes selected numeric tokens or inserts the one offset member, validates an
in-memory full-root postcondition, and uses atomic writes with rollback.
"""

from __future__ import annotations

import argparse
import copy
import csv
import io
import json
import math
import os
import re
import struct
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence

from model_geometry_common import (
    ModelAuditError,
    atomic_write_bytes,
    atomic_write_text,
    clean_number,
    ensure_reports_outside_sources,
    scan_manifest_sha256,
    sha256_bytes,
    stable_hash,
)


SCHEMA_VERSION = 1
PLAN_SCHEMA_VERSION = 1
APPLY_SCHEMA_VERSION = 1
OFFSET_KEY = "erydon_uv_offset"
RAW_SELECTION_POLICY = "rotated_raw_uv_complete_v1"
DEFAULT_RAW_ROOT = Path("src/main/resources/assets/erydon/authoring_models/block")
DEFAULT_LOADER_SOURCE = Path(
    "src/main/java/com/oliver/erydon/client/model/ErydonRawModelLoadingPlugin.java"
)
DEFAULT_JSON_REPORT = Path("build/reports/model-geometry/raw-uv-audit.json")
DEFAULT_CSV_REPORT = Path("build/reports/model-geometry/raw-uv-candidates.csv")
DEFAULT_APPLY_REPORT = Path("build/reports/model-geometry/raw-uv-apply.json")
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
REGISTERED_MODEL_PATTERN = re.compile(
    r'new\s+Identifier\s*\(\s*Erydon\.MOD_ID\s*,\s*"authoring_models/block/([^"\\]+(?:/[^"\\]+)*\.json)"\s*\)'
)
FACE_DIRECTIONS = ("down", "up", "north", "south", "west", "east")
LOADER_EPSILON = struct.unpack("!f", struct.pack("!f", 0.0005))[0]


def _pointer_escape(token: str) -> str:
    return token.replace("~", "~0").replace("/", "~1")


def _pointer_unescape(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


def _face_pointer(element_index: int, face: str) -> str:
    return f"/elements/{element_index}/faces/{_pointer_escape(face)}"


def _absolute_without_resolving(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def canonical_raw_root(path: Path) -> Path:
    """Resolve a non-symlink directory used as the complete raw-model root."""

    lexical = _absolute_without_resolving(path)
    if not lexical.exists() or not lexical.is_dir():
        raise ModelAuditError(f"Canonical raw-model root is not a directory: {lexical}")
    if lexical.is_symlink():
        raise ModelAuditError(f"Canonical raw-model root must not be a symlink: {lexical}")
    return lexical.resolve(strict=True)


def safe_raw_model_file(path: Path, root: Path) -> tuple[Path, str]:
    root_lexical = _absolute_without_resolving(root)
    root_resolved = canonical_raw_root(root_lexical)
    target_lexical = _absolute_without_resolving(path)
    try:
        relative = target_lexical.relative_to(root_lexical)
    except ValueError as exception:
        raise ModelAuditError(f"Raw-model path escapes canonical root: {target_lexical}") from exception
    if not relative.parts:
        raise ModelAuditError(f"Expected a raw-model file below the canonical root: {target_lexical}")
    current = root_lexical
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise ModelAuditError(f"Symlinked raw-model paths are not allowed: {current}")
    try:
        resolved = target_lexical.resolve(strict=True)
        canonical_relative = resolved.relative_to(root_resolved)
    except (OSError, ValueError) as exception:
        raise ModelAuditError(f"Raw-model path is missing or escapes its root: {target_lexical}") from exception
    if not resolved.is_file() or resolved.suffix.lower() != ".json":
        raise ModelAuditError(f"Expected a canonical raw-model JSON file: {resolved}")
    return resolved, canonical_relative.as_posix()


def raw_file_from_canonical_path(root: Path, canonical_path: str) -> tuple[Path, str]:
    if not isinstance(canonical_path, str) or not canonical_path:
        raise ModelAuditError("Plan canonicalPath must be a non-empty string")
    pure = PurePosixPath(canonical_path)
    if pure.is_absolute() or "\\" in canonical_path or any(part in {"", ".", ".."} for part in pure.parts):
        raise ModelAuditError(f"Plan canonicalPath must be a clean relative POSIX path: {canonical_path!r}")
    if pure.suffix.lower() != ".json":
        raise ModelAuditError(f"Plan canonicalPath must name a JSON file: {canonical_path!r}")
    root_lexical = _absolute_without_resolving(root)
    return safe_raw_model_file(root_lexical.joinpath(*pure.parts), root_lexical)


def discover_raw_files(root: Path) -> list[tuple[Path, str]]:
    root = canonical_raw_root(root)
    rows: list[tuple[Path, str]] = []
    for candidate in sorted(root.rglob("*.json"), key=lambda item: item.as_posix().lower()):
        rows.append(safe_raw_model_file(candidate, root))
    return rows


def registered_raw_paths(loader_source: Path) -> tuple[list[str], str]:
    loader = _absolute_without_resolving(loader_source)
    if not loader.exists() or not loader.is_file() or loader.is_symlink():
        raise ModelAuditError(f"Raw loader source is not a regular file: {loader}")
    payload = loader.read_bytes()
    try:
        text = payload.decode("utf-8-sig")
    except UnicodeDecodeError as exception:
        raise ModelAuditError(f"Raw loader source is not UTF-8: {loader}") from exception
    marker = "AUTHORING_MODELS = Map.ofEntries("
    start = text.find(marker)
    if start < 0:
        raise ModelAuditError("Raw loader source does not declare AUTHORING_MODELS")
    end = text.find(");", start)
    if end < 0:
        raise ModelAuditError("Raw loader AUTHORING_MODELS declaration is unterminated")
    matches = REGISTERED_MODEL_PATTERN.findall(text[start:end])
    if not matches:
        raise ModelAuditError("Raw loader AUTHORING_MODELS contains no registered JSON paths")
    normalized = [PurePosixPath(value).as_posix() for value in matches]
    if len(normalized) != len(set(normalized)):
        duplicates = sorted(path for path, count in Counter(normalized).items() if count > 1)
        raise ModelAuditError(f"Raw loader registers duplicate authoring paths: {duplicates}")
    return sorted(normalized), sha256_bytes(payload)


def _f32(value: Any) -> float:
    try:
        result = struct.unpack("!f", struct.pack("!f", float(value)))[0]
    except (OverflowError, TypeError, ValueError, struct.error) as exception:
        raise ModelAuditError(f"Expected a finite float-compatible number, got {value!r}") from exception
    if not math.isfinite(result):
        raise ModelAuditError(f"Expected a finite float-compatible number, got {value!r}")
    return result


def _fadd(a: float, b: float) -> float:
    return _f32(_f32(a) + _f32(b))


def _fsub(a: float, b: float) -> float:
    return _f32(_f32(a) - _f32(b))


def _fmul(a: float, b: float) -> float:
    return _f32(_f32(a) * _f32(b))


def _ffma(a: float, b: float, c: float) -> float:
    """Mirror JOML/Java's single-precision fused multiply-add."""

    fused = getattr(math, "fma", None)
    if fused is None:
        raise ModelAuditError(
            "Exact raw-loader parity requires a Python runtime with math.fma (Python 3.13 or newer)"
        )
    return _f32(fused(_f32(a), _f32(b), _f32(c)))


def _next_f32(value: float, positive: bool) -> float:
    """Return the adjacent finite float32 toward positive/negative infinity."""

    value = _f32(value)
    if value == 0.0:
        return struct.unpack("!f", struct.pack("!I", 1 if positive else 0x80000001))[0]
    bits = struct.unpack("!I", struct.pack("!f", value))[0]
    bits += 1 if (value > 0.0) == positive else -1
    result = struct.unpack("!f", struct.pack("!I", bits))[0]
    if not math.isfinite(result):
        raise ModelAuditError("A UV offset adjustment overflowed float32")
    return result


def _vector(value: Any, length: int, label: str, *, at_least: bool = False) -> tuple[float, ...]:
    valid_length = isinstance(value, list) and (len(value) >= length if at_least else len(value) == length)
    if not valid_length:
        qualifier = "at least " if at_least else "exactly "
        raise ModelAuditError(f"{label} must contain {qualifier}{length} numbers")
    if any(isinstance(value[index], bool) for index in range(length)):
        raise ModelAuditError(f"{label} must not contain boolean values")
    return tuple(_f32(value[index]) for index in range(length))


def _json_number(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(float(value))


class RawRotation:
    __slots__ = ("origin", "degrees", "radians", "identity")

    def __init__(self, origin: Sequence[float], degrees: Sequence[float]):
        self.origin = tuple(_f32(value) for value in origin)
        self.degrees = tuple(_f32(value) for value in degrees)
        self.identity = all(abs(value) <= LOADER_EPSILON for value in self.degrees)
        self.radians = tuple(_f32(math.radians(value)) for value in self.degrees)

    @staticmethod
    def none() -> "RawRotation":
        return RawRotation((8.0, 8.0, 8.0), (0.0, 0.0, 0.0))

    @classmethod
    def parse(cls, value: Any, default_origin: Sequence[float], label: str) -> "RawRotation":
        if value is None:
            return cls.none()
        if isinstance(value, list):
            return cls(default_origin, _vector(value, 3, label, at_least=True))
        if not isinstance(value, dict):
            return cls.none()
        origin = (
            _vector(value["origin"], 3, f"{label}.origin", at_least=True)
            if value.get("origin") is not None
            else tuple(default_origin)
        )
        if isinstance(value.get("angles"), list):
            return cls(origin, _vector(value["angles"], 3, f"{label}.angles", at_least=True))
        if isinstance(value.get("angle"), list):
            return cls(origin, _vector(value["angle"], 3, f"{label}.angle", at_least=True))
        if any(_json_number(value.get(axis)) for axis in "xyz"):
            degrees = tuple(_f32(value.get(axis, 0.0)) if _json_number(value.get(axis)) else 0.0 for axis in "xyz")
            return cls(origin, degrees)
        if "axis" in value and "angle" in value:
            if not _json_number(value["angle"]):
                raise ModelAuditError(f"{label}.angle must be a finite number")
            degrees = [0.0, 0.0, 0.0]
            axis = value["axis"]
            if not isinstance(axis, str) or len(axis) != 1 or axis not in "xyz":
                return cls.none()
            degrees["xyz".index(axis)] = _f32(value["angle"])
            return cls(origin, degrees)
        return cls.none()

    def transform(self, vertex: Sequence[float]) -> tuple[float, float, float]:
        if self.identity:
            return tuple(vertex)  # type: ignore[return-value]
        x = _fsub(vertex[0], self.origin[0])
        y = _fsub(vertex[1], self.origin[1])
        z = _fsub(vertex[2], self.origin[2])
        xr, yr, zr = self.radians
        if abs(xr) > LOADER_EPSILON:
            cosine, sine = _f32(math.cos(xr)), _f32(math.sin(xr))
            y, z = _fsub(_fmul(y, cosine), _fmul(z, sine)), _fadd(_fmul(y, sine), _fmul(z, cosine))
        if abs(yr) > LOADER_EPSILON:
            cosine, sine = _f32(math.cos(yr)), _f32(math.sin(yr))
            x, z = _fadd(_fmul(x, cosine), _fmul(z, sine)), _fsub(_fmul(z, cosine), _fmul(x, sine))
        if abs(zr) > LOADER_EPSILON:
            cosine, sine = _f32(math.cos(zr)), _f32(math.sin(zr))
            x, y = _fsub(_fmul(x, cosine), _fmul(y, sine)), _fadd(_fmul(x, sine), _fmul(y, cosine))
        return (_fadd(x, self.origin[0]), _fadd(y, self.origin[1]), _fadd(z, self.origin[2]))


def _collect_group_rotations(model: dict[str, Any]) -> dict[int, list[RawRotation]]:
    output: dict[int, list[RawRotation]] = defaultdict(list)
    groups = model.get("groups")
    if not isinstance(groups, list):
        return output

    def visit(group: dict[str, Any], inherited: list[RawRotation], label: str) -> None:
        default_origin = (
            _vector(group["origin"], 3, f"{label}.origin", at_least=True)
            if group.get("origin") is not None
            else (8.0, 8.0, 8.0)
        )
        rotation = RawRotation.parse(group.get("rotation"), default_origin, f"{label}.rotation")
        rotations = inherited + ([] if rotation.identity else [rotation])
        children = group.get("children")
        if not isinstance(children, list):
            return
        for child_index, child in enumerate(children):
            if _json_number(child):
                element_index = int(child)
                if rotations:
                    output[element_index].extend(reversed(rotations))
            elif isinstance(child, dict):
                visit(child, rotations, f"{label}.children[{child_index}]")

    for index, group in enumerate(groups):
        if isinstance(group, dict):
            visit(group, [], f"groups[{index}]")
    return output


def _face_vertices(from_vector: Sequence[float], to_vector: Sequence[float], face: str) -> list[tuple[float, float, float]]:
    x1, y1, z1 = from_vector
    x2, y2, z2 = to_vector
    return {
        "north": [(x2, y1, z1), (x1, y1, z1), (x1, y2, z1), (x2, y2, z1)],
        "south": [(x1, y1, z2), (x2, y1, z2), (x2, y2, z2), (x1, y2, z2)],
        "west": [(x1, y1, z1), (x1, y1, z2), (x1, y2, z2), (x1, y2, z1)],
        "east": [(x2, y1, z2), (x2, y1, z1), (x2, y2, z1), (x2, y2, z2)],
        "up": [(x1, y2, z1), (x1, y2, z2), (x2, y2, z2), (x2, y2, z1)],
        "down": [(x1, y1, z2), (x1, y1, z1), (x2, y1, z1), (x2, y1, z2)],
    }[face]


def _sub_vector(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return tuple(_fsub(a[index], b[index]) for index in range(3))  # type: ignore[return-value]


def _cross(a: Sequence[float], b: Sequence[float]) -> tuple[float, float, float]:
    return (
        _ffma(a[1], b[2], -_fmul(a[2], b[1])),
        _ffma(a[2], b[0], -_fmul(a[0], b[2])),
        _ffma(a[0], b[1], -_fmul(a[1], b[0])),
    )


DIRECTION_VECTORS = (
    ("down", (0.0, -1.0, 0.0)),
    ("up", (0.0, 1.0, 0.0)),
    ("north", (0.0, 0.0, -1.0)),
    ("south", (0.0, 0.0, 1.0)),
    ("west", (-1.0, 0.0, 0.0)),
    ("east", (1.0, 0.0, 0.0)),
)


def _closest_direction(vertices: Sequence[Sequence[float]]) -> str:
    normal = _cross(_sub_vector(vertices[1], vertices[0]), _sub_vector(vertices[2], vertices[0]))
    length_squared = _ffma(
        normal[0],
        normal[0],
        _ffma(normal[1], normal[1], _fmul(normal[2], normal[2])),
    )
    if length_squared <= LOADER_EPSILON:
        return "up"
    best_direction, best_score = "up", -float("inf")
    for direction, vector in DIRECTION_VECTORS:
        score = _fadd(_fadd(_fmul(normal[0], vector[0]), _fmul(normal[1], vector[1])), _fmul(normal[2], vector[2]))
        if score > best_score:
            best_direction, best_score = direction, score
    return best_direction


def _default_uv(vertices: Sequence[Sequence[float]], face: str) -> list[float]:
    uv: list[float] = []
    for x, y, z in vertices:
        pair = {
            "north": (_fsub(16.0, x), _fsub(16.0, y)),
            "south": (x, _fsub(16.0, y)),
            "west": (z, _fsub(16.0, y)),
            "east": (_fsub(16.0, z), _fsub(16.0, y)),
            "up": (x, z),
            "down": (x, _fsub(16.0, z)),
        }[face]
        uv.extend(pair)
    return uv


def _rect_uv(uv: Sequence[float]) -> list[float]:
    return [uv[0], uv[3], uv[2], uv[3], uv[2], uv[1], uv[0], uv[1]]


def _effective_uv(base_uv: Sequence[float], offset: Sequence[float]) -> list[float]:
    return [_fadd(value, offset[index % 2]) for index, value in enumerate(base_uv)]


def _in_range(value: float) -> bool:
    return 0.0 <= value <= 16.0


def _uniform_delta(values: Sequence[float]) -> float | None:
    minimum, maximum = min(values), max(values)
    if maximum - minimum > 16.0:
        return None
    low, high = -minimum, 16.0 - maximum
    if low <= 0.0 <= high:
        return 0.0
    return _f32(low if low > 0.0 else high)


def _integer_16_delta(values: Sequence[float]) -> float | None:
    minimum, maximum = min(values), max(values)
    if maximum - minimum > 16.0:
        return None
    lower = math.ceil((-minimum) / 16.0)
    upper = math.floor((16.0 - maximum) / 16.0)
    candidates = [multiplier for multiplier in range(lower, upper + 1) if multiplier != 0]
    if not candidates:
        return None
    multiplier = min(candidates, key=lambda value: (abs(value), value))
    return _f32(multiplier * 16.0)


def _report_numbers(values: Iterable[float]) -> list[int | float]:
    return [clean_number(value) for value in values]


def _new_summary() -> dict[str, int]:
    return {
        "filesDiscovered": 0,
        "filesScanned": 0,
        "registeredFiles": 0,
        "facesScanned": 0,
        "explicitFaces": 0,
        "implicitFaces": 0,
        "offsetFaces": 0,
        "outOfRangeFaces": 0,
        "rotatedFaces": 0,
        "rotatedOutOfRangeFaces": 0,
        "rotatedExplicitOutOfRangeFaces": 0,
        "rotatedImplicitOutOfRangeFaces": 0,
        "explicitInteger16Candidates": 0,
        "explicitBoundaryReanchors": 0,
        "implicitOffsetCandidates": 0,
        "unresolvedRotatedFaces": 0,
    }


def _parse_offset(face_data: dict[str, Any], label: str) -> tuple[float, float]:
    if OFFSET_KEY not in face_data:
        return (0.0, 0.0)
    value = face_data[OFFSET_KEY]
    if not isinstance(value, list) or len(value) != 2 or not all(_json_number(item) for item in value):
        raise ModelAuditError(f"{label}.{OFFSET_KEY} must contain exactly 2 finite JSON numbers")
    return tuple(_f32(item) for item in value)  # type: ignore[return-value]


def _propose_explicit(
    authored: Sequence[float], offset: Sequence[float]
) -> tuple[str, list[float], tuple[float, float]] | None:
    current = _effective_uv(_rect_uv(authored), offset)
    axis_values = (current[0::2], current[1::2])
    deltas: list[float] = []
    all_periodic = True
    for values in axis_values:
        if all(_in_range(value) for value in values):
            deltas.append(0.0)
            continue
        periodic = _integer_16_delta(values)
        if periodic is not None:
            deltas.append(periodic)
        else:
            all_periodic = False
            uniform = _uniform_delta(values)
            if uniform is None:
                return None
            deltas.append(uniform)

    proposed = [_fadd(value, deltas[index % 2]) for index, value in enumerate(authored)]
    for _ in range(64):
        after = _effective_uv(_rect_uv(proposed), offset)
        low_axis = [any(value < 0.0 for value in after[axis::2]) for axis in range(2)]
        high_axis = [any(value > 16.0 for value in after[axis::2]) for axis in range(2)]
        if not any(low_axis) and not any(high_axis):
            operation = "explicit_integer_16_shift" if all_periodic else "explicit_boundary_uniform_reanchor"
            return operation, proposed, (deltas[0], deltas[1])
        for axis in range(2):
            if low_axis[axis] and high_axis[axis]:
                return None
            if low_axis[axis] or high_axis[axis]:
                deltas[axis] = _next_f32(deltas[axis], positive=low_axis[axis])
                for index in range(axis, 4, 2):
                    proposed[index] = _fadd(authored[index], deltas[axis])
    return None


def _propose_implicit(base_uv: Sequence[float], current_offset: Sequence[float]) -> tuple[list[float], tuple[float, float]] | None:
    proposed = [current_offset[0], current_offset[1]]
    deltas = [0.0, 0.0]
    for axis in range(2):
        effective = [_fadd(value, current_offset[axis]) for value in base_uv[axis::2]]
        delta = _uniform_delta(effective)
        if delta is None:
            return None
        deltas[axis] = delta
        proposed[axis] = _fadd(current_offset[axis], delta)
    for _ in range(64):
        after = _effective_uv(base_uv, proposed)
        low_axis = [any(value < 0.0 for value in after[axis::2]) for axis in range(2)]
        high_axis = [any(value > 16.0 for value in after[axis::2]) for axis in range(2)]
        if not any(low_axis) and not any(high_axis):
            return proposed, (deltas[0], deltas[1])
        for axis in range(2):
            if low_axis[axis] and high_axis[axis]:
                return None
            if low_axis[axis] or high_axis[axis]:
                proposed[axis] = _next_f32(proposed[axis], positive=low_axis[axis])
    return None


def _operation_id(canonical_path: str, element_index: int, face: str, source_sha: str) -> str:
    payload = {"model": canonical_path, "element": element_index, "face": face, "source": source_sha}
    return "raw-uv-" + stable_hash(payload)[:24]


def _audit_document(document: Any, canonical_path: str, source_sha: str) -> tuple[list[dict[str, Any]], dict[str, int]]:
    if not isinstance(document, dict):
        raise ModelAuditError(f"Raw model root must be an object: {canonical_path}")
    elements = document.get("elements", [])
    if not isinstance(elements, list):
        raise ModelAuditError(f"Raw model elements must be an array: {canonical_path}")
    group_rotations = _collect_group_rotations(document)
    findings: list[dict[str, Any]] = []
    counts = Counter()

    for element_index, element in enumerate(elements):
        if not isinstance(element, dict):
            continue
        from_vector = _vector(element.get("from"), 3, f"{canonical_path} elements[{element_index}].from", at_least=True)
        to_vector = _vector(element.get("to"), 3, f"{canonical_path} elements[{element_index}].to", at_least=True)
        default_origin = (
            _vector(element["origin"], 3, f"{canonical_path} elements[{element_index}].origin", at_least=True)
            if element.get("origin") is not None
            else (8.0, 8.0, 8.0)
        )
        element_rotation = RawRotation.parse(
            element.get("rotation"), default_origin, f"{canonical_path} elements[{element_index}].rotation"
        )
        rotations = ([] if element_rotation.identity else [element_rotation]) + group_rotations.get(element_index, [])
        rotated = bool(rotations)
        faces = element.get("faces")
        if not isinstance(faces, dict):
            continue
        for face in FACE_DIRECTIONS:
            face_data = faces.get(face)
            if not isinstance(face_data, dict):
                continue
            counts["facesScanned"] += 1
            if rotated:
                counts["rotatedFaces"] += 1
            label = f"{canonical_path} elements[{element_index}].faces.{face}"
            offset = _parse_offset(face_data, label)
            if OFFSET_KEY in face_data:
                counts["offsetFaces"] += 1
            vertices = _face_vertices(from_vector, to_vector, face)
            for rotation in rotations:
                vertices = [rotation.transform(vertex) for vertex in vertices]
            nominal_face = _closest_direction(vertices)
            explicit = isinstance(face_data.get("uv"), list) and len(face_data["uv"]) >= 4
            if explicit:
                if OFFSET_KEY in face_data:
                    raise ModelAuditError(
                        f"{label} is explicit and must not use implicit-only {OFFSET_KEY}"
                    )
                counts["explicitFaces"] += 1
                authored = _vector(face_data["uv"], 4, f"{label}.uv", at_least=True)
                base_uv = _rect_uv(authored)
            else:
                counts["implicitFaces"] += 1
                authored = None
                base_uv = _default_uv(vertices, nominal_face)
            effective = _effective_uv(base_uv, offset)
            out_of_range = any(not _in_range(value) for value in effective)
            if not out_of_range:
                continue
            counts["outOfRangeFaces"] += 1
            if rotated:
                counts["rotatedOutOfRangeFaces"] += 1
                counts["rotatedExplicitOutOfRangeFaces" if explicit else "rotatedImplicitOutOfRangeFaces"] += 1

            operation_class: str | None = None
            proposed_uv: list[float] | None = None
            proposed_offset: list[float] | None = None
            shifts: tuple[float, float] | None = None
            if rotated and explicit:
                proposal = _propose_explicit(authored, offset)
                if proposal is not None:
                    operation_class, proposed_uv, shifts = proposal
                    counts[
                        "explicitInteger16Candidates"
                        if operation_class == "explicit_integer_16_shift"
                        else "explicitBoundaryReanchors"
                    ] += 1
            elif rotated:
                proposal = _propose_implicit(base_uv, offset)
                if proposal is not None:
                    proposed_offset, shifts = proposal
                    operation_class = "implicit_uniform_offset"
                    counts["implicitOffsetCandidates"] += 1
            if rotated and operation_class is None:
                counts["unresolvedRotatedFaces"] += 1

            finding = {
                "id": _operation_id(canonical_path, element_index, face, source_sha),
                "canonicalPath": canonical_path,
                "sourceSha256": source_sha,
                "elementIndex": element_index,
                "face": face,
                "facePointer": _face_pointer(element_index, face),
                "uvSource": "explicit" if explicit else "implicit",
                "rotated": rotated,
                "nominalFace": nominal_face,
                "rotationCount": len(rotations),
                "effectiveUvBefore": _report_numbers(effective),
                "uRange": _report_numbers((min(effective[0::2]), max(effective[0::2]))),
                "vRange": _report_numbers((min(effective[1::2]), max(effective[1::2]))),
                "expectedExplicitUv": list(face_data["uv"][:4]) if explicit else None,
                "expectedOffset": list(face_data[OFFSET_KEY]) if OFFSET_KEY in face_data else None,
                "operationClass": operation_class,
                "proposedExplicitUv": _report_numbers(proposed_uv) if proposed_uv is not None else None,
                "proposedOffset": _report_numbers(proposed_offset) if proposed_offset is not None else None,
                "uniformShift": {"u": clean_number(shifts[0]), "v": clean_number(shifts[1])} if shifts else None,
            }
            findings.append(finding)
    return findings, dict(counts)


def audit_models(
    raw_root: Path = DEFAULT_RAW_ROOT,
    *,
    loader_source: Path = DEFAULT_LOADER_SOURCE,
    source_overrides: dict[str, bytes] | None = None,
) -> dict[str, Any]:
    """Audit the complete loader-registered raw root without writing sources."""

    root = canonical_raw_root(raw_root)
    discovered = discover_raw_files(root)
    registered, loader_sha = registered_raw_paths(loader_source)
    discovered_paths = [canonical_path for _, canonical_path in discovered]
    missing = sorted(set(registered) - set(discovered_paths))
    unregistered = sorted(set(discovered_paths) - set(registered))
    if missing or unregistered:
        raise ModelAuditError(
            "Raw-model source-list parity failed; "
            f"registered-but-missing={missing}, present-but-unregistered={unregistered}"
        )
    overrides = source_overrides or {}
    unknown_overrides = sorted(set(overrides) - set(discovered_paths))
    if unknown_overrides:
        raise ModelAuditError(f"Raw audit overrides unknown canonical paths: {unknown_overrides}")

    summary = _new_summary()
    summary["filesDiscovered"] = len(discovered)
    summary["registeredFiles"] = len(registered)
    files: list[dict[str, Any]] = []
    findings: list[dict[str, Any]] = []
    manifest_rows: list[tuple[str, str]] = []
    for model_file, canonical_path in discovered:
        payload = overrides.get(canonical_path, model_file.read_bytes())
        source_sha = sha256_bytes(payload)
        try:
            document = json.loads(payload.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise ModelAuditError(f"Raw model is not valid UTF-8 JSON: {canonical_path}: {exception}") from exception
        model_findings, counts = _audit_document(document, canonical_path, source_sha)
        summary["filesScanned"] += 1
        for key, value in counts.items():
            summary[key] += value
        findings.extend(model_findings)
        manifest_rows.append((canonical_path, source_sha))
        files.append(
            {
                "canonicalPath": canonical_path,
                "sourceSha256": source_sha,
                "facesScanned": counts.get("facesScanned", 0),
                "outOfRangeFaces": counts.get("outOfRangeFaces", 0),
                "rotatedOutOfRangeFaces": counts.get("rotatedOutOfRangeFaces", 0),
            }
        )
    findings.sort(key=lambda row: (row["canonicalPath"], row["elementIndex"], FACE_DIRECTIONS.index(row["face"])))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "mode": "audit",
        "sourceWrites": False,
        "canonicalRawRoot": str(root),
        "loaderSource": str(_absolute_without_resolving(loader_source).resolve()),
        "loaderSourceSha256": loader_sha,
        "sourceListParity": {
            "matched": True,
            "registeredCanonicalPaths": registered,
            "registeredCount": len(registered),
        },
        "sourceManifestSha256": scan_manifest_sha256(manifest_rows),
        "summary": summary,
        "files": files,
        "findings": findings,
    }


def generate_plan(audit: dict[str, Any]) -> dict[str, Any]:
    """Create the only accepted complete rotated-raw-UV operation inventory."""

    summary = audit.get("summary", {})
    if summary.get("unresolvedRotatedFaces") != 0:
        raise ModelAuditError(
            f"Raw UV plan has {summary.get('unresolvedRotatedFaces')} unresolved rotated faces"
        )
    findings = [finding for finding in audit.get("findings", []) if finding.get("rotated")]
    operations: list[dict[str, Any]] = []
    for finding in findings:
        operation_class = finding.get("operationClass")
        if operation_class is None:
            raise ModelAuditError(f"Rotated raw UV finding has no complete operation: {finding.get('id')}")
        operation = {
            "id": finding["id"],
            "operationClass": operation_class,
            "canonicalPath": finding["canonicalPath"],
            "sourceSha256": finding["sourceSha256"],
            "elementIndex": finding["elementIndex"],
            "face": finding["face"],
            "facePointer": finding["facePointer"],
            "uvSource": finding["uvSource"],
            "nominalFace": finding["nominalFace"],
            "effectiveUvBefore": finding["effectiveUvBefore"],
            "uniformShift": finding["uniformShift"],
            "expectedExplicitUv": finding["expectedExplicitUv"],
            "proposedExplicitUv": finding["proposedExplicitUv"],
            "expectedOffset": finding["expectedOffset"],
            "proposedOffset": finding["proposedOffset"],
        }
        if operation_class.startswith("explicit_"):
            if operation["uvSource"] != "explicit" or operation["proposedExplicitUv"] is None:
                raise ModelAuditError(f"Explicit raw UV operation is internally inconsistent: {finding['id']}")
            if operation["proposedOffset"] is not None:
                raise ModelAuditError(f"Explicit raw UV operation must not depend on {OFFSET_KEY}: {finding['id']}")
        elif operation_class == "implicit_uniform_offset":
            if operation["uvSource"] != "implicit" or operation["proposedOffset"] is None:
                raise ModelAuditError(f"Implicit raw UV operation is internally inconsistent: {finding['id']}")
            if operation["proposedExplicitUv"] is not None:
                raise ModelAuditError(f"Implicit raw UV operation must remain implicit: {finding['id']}")
        else:
            raise ModelAuditError(f"Unsupported raw UV operation class: {operation_class!r}")
        operations.append(operation)

    operations.sort(key=lambda row: (row["canonicalPath"], row["elementIndex"], FACE_DIRECTIONS.index(row["face"])))
    counts = Counter(operation["operationClass"] for operation in operations)
    files = [
        {"canonicalPath": row["canonicalPath"], "sourceSha256": row["sourceSha256"]}
        for row in audit["files"]
    ]
    return {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "mode": "raw_uv_apply_plan",
        "sourceWrites": False,
        "selectionPolicy": RAW_SELECTION_POLICY,
        "loaderSourceSha256": audit["loaderSourceSha256"],
        "sourceManifestSha256": audit["sourceManifestSha256"],
        "registeredCanonicalPaths": audit["sourceListParity"]["registeredCanonicalPaths"],
        "files": files,
        "operationCounts": {
            "explicitInteger16Shifts": counts["explicit_integer_16_shift"],
            "explicitBoundaryUniformReanchors": counts["explicit_boundary_uniform_reanchor"],
            "implicitUniformOffsets": counts["implicit_uniform_offset"],
            "total": len(operations),
        },
        "postcondition": {
            "rotatedOutOfRangeFaces": 0,
            "explicitFacesUseOffset": False,
            "implicitFacesRemainWithoutExplicitUv": True,
        },
        "operations": operations,
    }


AUDIT_CSV_COLUMNS = (
    "id",
    "canonicalPath",
    "elementIndex",
    "face",
    "uvSource",
    "rotated",
    "nominalFace",
    "operationClass",
    "uRange",
    "vRange",
    "uniformShift",
)


def write_audit_reports(audit: dict[str, Any], json_path: Path, csv_path: Path) -> None:
    ensure_reports_outside_sources((json_path, csv_path), (Path(audit["canonicalRawRoot"]),))
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=AUDIT_CSV_COLUMNS, lineterminator="\n")
    writer.writeheader()
    for finding in audit["findings"]:
        writer.writerow(
            {
                column: (
                    json.dumps(finding.get(column), separators=(",", ":"), ensure_ascii=False)
                    if isinstance(finding.get(column), (list, dict))
                    else finding.get(column)
                )
                for column in AUDIT_CSV_COLUMNS
            }
        )
    atomic_write_text(json_path, json.dumps(audit, indent=2, ensure_ascii=False) + "\n")
    atomic_write_text(csv_path, buffer.getvalue())


class JsonSpanLocator:
    """Strict JSON parser that records the character span of every value."""

    NUMBER_CHARS = frozenset("-+0123456789.eE")

    def __init__(self, text: str):
        self.text = text
        self.position = 0
        self.spans: dict[str, tuple[int, int]] = {}
        self.duplicate_pointers: set[str] = set()

    def locate(self) -> dict[str, tuple[int, int]]:
        self._skip_whitespace()
        self._parse_value("")
        self._skip_whitespace()
        if self.position != len(self.text):
            raise ModelAuditError(f"Unexpected JSON content at character {self.position}")
        return self.spans

    def _skip_whitespace(self) -> None:
        while self.position < len(self.text) and self.text[self.position] in " \t\r\n":
            self.position += 1

    def _parse_value(self, pointer: str) -> None:
        self._skip_whitespace()
        start = self.position
        if self.position >= len(self.text):
            raise ModelAuditError("Unexpected end of JSON")
        current = self.text[self.position]
        if current == "{":
            self._parse_object(pointer)
        elif current == "[":
            self._parse_array(pointer)
        elif current == '"':
            self._parse_string_token()
        elif current in "-0123456789":
            self._parse_number()
        else:
            self._parse_literal()
        self.spans[pointer] = (start, self.position)

    def _parse_object(self, pointer: str) -> None:
        self.position += 1
        self._skip_whitespace()
        if self._consume("}"):
            return
        keys: set[str] = set()
        while True:
            self._skip_whitespace()
            key_start = self.position
            self._parse_string_token()
            try:
                key = json.loads(self.text[key_start : self.position])
            except json.JSONDecodeError as exception:
                raise ModelAuditError(f"Invalid JSON object key at character {key_start}") from exception
            child_pointer = pointer + "/" + _pointer_escape(key)
            if key in keys:
                self.duplicate_pointers.add(child_pointer)
            keys.add(key)
            self._skip_whitespace()
            if not self._consume(":"):
                raise ModelAuditError(f"Expected ':' at character {self.position}")
            self._parse_value(child_pointer)
            self._skip_whitespace()
            if self._consume("}"):
                return
            if not self._consume(","):
                raise ModelAuditError(f"Expected ',' at character {self.position}")

    def _parse_array(self, pointer: str) -> None:
        self.position += 1
        self._skip_whitespace()
        if self._consume("]"):
            return
        index = 0
        while True:
            self._parse_value(pointer + f"/{index}")
            index += 1
            self._skip_whitespace()
            if self._consume("]"):
                return
            if not self._consume(","):
                raise ModelAuditError(f"Expected ',' at character {self.position}")

    def _parse_string_token(self) -> None:
        if not self._consume('"'):
            raise ModelAuditError(f"Expected JSON string at character {self.position}")
        while self.position < len(self.text):
            current = self.text[self.position]
            self.position += 1
            if current == '"':
                return
            if current == "\\":
                if self.position >= len(self.text):
                    break
                self.position += 1
        raise ModelAuditError("Unterminated JSON string")

    def _parse_number(self) -> None:
        start = self.position
        while self.position < len(self.text) and self.text[self.position] in self.NUMBER_CHARS:
            self.position += 1
        try:
            json.loads(self.text[start : self.position])
        except json.JSONDecodeError as exception:
            raise ModelAuditError(f"Invalid JSON number at character {start}") from exception

    def _parse_literal(self) -> None:
        for literal in ("true", "false", "null"):
            if self.text.startswith(literal, self.position):
                self.position += len(literal)
                return
        raise ModelAuditError(f"Invalid JSON value at character {self.position}")

    def _consume(self, token: str) -> bool:
        if self.text.startswith(token, self.position):
            self.position += len(token)
            return True
        return False


def _pointer_tokens(pointer: str) -> list[str]:
    if pointer == "":
        return []
    if not pointer.startswith("/"):
        raise ModelAuditError(f"Invalid JSON pointer: {pointer!r}")
    return [_pointer_unescape(token) for token in pointer[1:].split("/")]


def _pointer_get(document: Any, pointer: str) -> Any:
    current = document
    for token in _pointer_tokens(pointer):
        if isinstance(current, list):
            if not token.isdigit() or int(token) >= len(current):
                raise ModelAuditError(f"Invalid JSON pointer list index: {pointer}")
            current = current[int(token)]
        elif isinstance(current, dict) and token in current:
            current = current[token]
        else:
            raise ModelAuditError(f"JSON pointer does not exist: {pointer}")
    return current


def _pointer_set(document: Any, pointer: str, value: Any) -> None:
    tokens = _pointer_tokens(pointer)
    if not tokens:
        raise ModelAuditError("Replacing the JSON root is not allowed")
    parent = document
    for token in tokens[:-1]:
        parent = parent[int(token)] if isinstance(parent, list) else parent[token]
    final = tokens[-1]
    if isinstance(parent, list):
        parent[int(final)] = value
    else:
        parent[final] = value


def _duplicate_intersects(locator: JsonSpanLocator, pointer: str) -> bool:
    return any(
        pointer == duplicate
        or pointer.startswith(duplicate + "/")
        or duplicate.startswith(pointer + "/")
        for duplicate in locator.duplicate_pointers
    )


def _number_token(value: Any) -> bytes:
    if not _json_number(value):
        raise ModelAuditError(f"Planned UV token is not a finite JSON number: {value!r}")
    return json.dumps(value, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _offset_insertion(
    text: str, face_span: tuple[int, int], proposed: Sequence[Any]
) -> tuple[int, str]:
    start, end = face_span
    if start >= end or text[start] != "{" or text[end - 1] != "}":
        raise ModelAuditError("Planned offset parent is not a JSON object")
    closing = end - 1
    insertion = closing
    while insertion > start + 1 and text[insertion - 1] in " \t\r\n":
        insertion -= 1
    has_content = bool(text[start + 1 : insertion].strip())
    face_text = text[start:end]
    serialized = json.dumps(_report_numbers(proposed), separators=(",", ":"), ensure_ascii=False)
    if "\n" not in face_text and "\r" not in face_text:
        prefix = ", " if has_content else ""
        return insertion, f'{prefix}"{OFFSET_KEY}": {serialized}'

    newline = "\r\n" if "\r\n" in face_text else "\n"
    indent_match = re.search(r"(?:\r\n|\n)([ \t]+)\"", face_text)
    if indent_match:
        indent = indent_match.group(1)
    else:
        closing_line = text[text.rfind("\n", start, closing) + 1 : closing]
        closing_indent = re.match(r"[ \t]*", closing_line).group(0)
        indent = closing_indent + "\t"
    prefix = "," if has_content else ""
    return insertion, f'{prefix}{newline}{indent}"{OFFSET_KEY}": {serialized}'


def _prepare_file_edits(
    source_bytes: bytes, operations: Sequence[dict[str, Any]]
) -> tuple[bytes, list[dict[str, Any]]]:
    """Prepare selected scalar replacements/member insertions and deep-compare."""

    bom = b"\xef\xbb\xbf" if source_bytes.startswith(b"\xef\xbb\xbf") else b""
    body = source_bytes[len(bom) :]
    try:
        text = body.decode("utf-8")
        before_document = json.loads(source_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ModelAuditError(f"Raw source cannot be decoded for selected edits: {exception}") from exception
    locator = JsonSpanLocator(text)
    spans = locator.locate()
    expected_document = copy.deepcopy(before_document)
    replacements: list[dict[str, Any]] = []
    selected: set[tuple[int, int]] = set()

    def byte_offset(character_offset: int) -> int:
        return len(bom) + len(text[:character_offset].encode("utf-8"))

    def add_scalar(operation: dict[str, Any], pointer: str, expected: Any, proposed: Any) -> None:
        if expected == proposed:
            return
        if _duplicate_intersects(locator, pointer):
            raise ModelAuditError(f"Planned scalar intersects duplicate JSON keys: {pointer}")
        if pointer not in spans:
            raise ModelAuditError(f"Planned scalar pointer is missing: {pointer}")
        if _pointer_get(before_document, pointer) != expected:
            raise ModelAuditError(f"Planned scalar no longer matches its source token: {pointer}")
        char_start, char_end = spans[pointer]
        byte_start, byte_end = byte_offset(char_start), byte_offset(char_end)
        if (byte_start, byte_end) in selected:
            raise ModelAuditError(f"Duplicate selected JSON token span: {pointer}")
        selected.add((byte_start, byte_end))
        original = source_bytes[byte_start:byte_end]
        try:
            parsed = json.loads(original.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise ModelAuditError(f"Selected scalar token is invalid JSON: {pointer}") from exception
        if parsed != expected or not _json_number(parsed):
            raise ModelAuditError(f"Selected scalar is not the locked finite number: {pointer}")
        replacement = _number_token(proposed)
        _pointer_set(expected_document, pointer, proposed)
        replacements.append(
            {
                "operationId": operation["id"],
                "operationClass": operation["operationClass"],
                "jsonPointer": pointer,
                "byteStart": byte_start,
                "byteEnd": byte_end,
                "originalToken": original.decode("utf-8"),
                "replacementToken": replacement.decode("utf-8"),
                "replacementBytes": replacement,
            }
        )

    for operation in operations:
        face_pointer = operation["facePointer"]
        if operation["operationClass"].startswith("explicit_"):
            expected_uv = operation["expectedExplicitUv"]
            proposed_uv = operation["proposedExplicitUv"]
            if not isinstance(expected_uv, list) or len(expected_uv) < 4:
                raise ModelAuditError(f"Explicit operation has no locked four-value UV: {operation['id']}")
            if not isinstance(proposed_uv, list) or len(proposed_uv) != 4:
                raise ModelAuditError(f"Explicit operation has no four-value proposal: {operation['id']}")
            for index in range(4):
                add_scalar(
                    operation,
                    f"{face_pointer}/uv/{index}",
                    expected_uv[index],
                    proposed_uv[index],
                )
            continue

        if operation["operationClass"] != "implicit_uniform_offset":
            raise ModelAuditError(f"Unsupported operation class in apply: {operation['operationClass']!r}")
        proposed_offset = operation["proposedOffset"]
        if not isinstance(proposed_offset, list) or len(proposed_offset) != 2:
            raise ModelAuditError(f"Implicit operation has no two-value offset proposal: {operation['id']}")
        expected_offset = operation["expectedOffset"]
        if expected_offset is not None:
            if not isinstance(expected_offset, list) or len(expected_offset) != 2:
                raise ModelAuditError(f"Implicit operation has a malformed locked offset: {operation['id']}")
            for index in range(2):
                add_scalar(
                    operation,
                    f"{face_pointer}/{OFFSET_KEY}/{index}",
                    expected_offset[index],
                    proposed_offset[index],
                )
            continue

        face_data = _pointer_get(before_document, face_pointer)
        if not isinstance(face_data, dict) or OFFSET_KEY in face_data:
            raise ModelAuditError(f"Implicit offset insertion target is stale: {face_pointer}")
        if _duplicate_intersects(locator, face_pointer):
            raise ModelAuditError(f"Offset insertion target contains duplicate JSON keys: {face_pointer}")
        if face_pointer not in spans:
            raise ModelAuditError(f"Offset insertion face pointer is missing: {face_pointer}")
        char_position, insertion_text = _offset_insertion(text, spans[face_pointer], proposed_offset)
        byte_position = byte_offset(char_position)
        if (byte_position, byte_position) in selected:
            raise ModelAuditError(f"Duplicate offset insertion position: {face_pointer}")
        selected.add((byte_position, byte_position))
        expected_face = _pointer_get(expected_document, face_pointer)
        expected_face[OFFSET_KEY] = proposed_offset
        replacements.append(
            {
                "operationId": operation["id"],
                "operationClass": operation["operationClass"],
                "jsonPointer": f"{face_pointer}/{OFFSET_KEY}",
                "byteStart": byte_position,
                "byteEnd": byte_position,
                "originalToken": "",
                "replacementToken": insertion_text,
                "replacementBytes": insertion_text.encode("utf-8"),
            }
        )

    ordered = sorted(replacements, key=lambda row: (row["byteStart"], row["byteEnd"]))
    for previous, current in zip(ordered, ordered[1:]):
        if previous["byteEnd"] > current["byteStart"]:
            raise ModelAuditError("Planned raw UV JSON edit spans overlap")
    output = source_bytes
    for replacement in reversed(ordered):
        output = (
            output[: replacement["byteStart"]]
            + replacement["replacementBytes"]
            + output[replacement["byteEnd"] :]
        )
    try:
        after_document = json.loads(output.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ModelAuditError("Selected raw UV edits produced invalid JSON") from exception
    if after_document != expected_document:
        raise ModelAuditError("Deep comparison failed: raw content outside selected edits changed")
    if output == source_bytes:
        raise ModelAuditError("Raw UV apply produced no source-byte change")
    return output, [
        {key: value for key, value in replacement.items() if key != "replacementBytes"}
        for replacement in ordered
    ]


def _valid_sha256(value: str, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise ModelAuditError(f"{label} must be a 64-character SHA-256 digest")
    return value.lower()


def _load_plan(plan_path: Path, expected_sha256: str) -> tuple[dict[str, Any], str]:
    expected = _valid_sha256(expected_sha256, "--expect-plan-sha256")
    try:
        payload = plan_path.read_bytes()
    except OSError as exception:
        raise ModelAuditError(f"Cannot read raw UV apply plan: {plan_path}") from exception
    actual = sha256_bytes(payload)
    if actual != expected:
        raise ModelAuditError(f"Apply-plan SHA-256 mismatch: expected {expected}, actual {actual}")
    try:
        plan = json.loads(
            payload.decode("utf-8-sig"),
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exception:
        raise ModelAuditError(f"Raw UV apply plan is not strict UTF-8 JSON: {exception}") from exception
    if not isinstance(plan, dict):
        raise ModelAuditError("Raw UV apply plan root must be an object")
    if plan.get("schemaVersion") != PLAN_SCHEMA_VERSION or plan.get("mode") != "raw_uv_apply_plan":
        raise ModelAuditError("Unsupported raw UV apply-plan schema or mode")
    if plan.get("selectionPolicy") != RAW_SELECTION_POLICY:
        raise ModelAuditError(f"Unsupported raw UV selection policy: {plan.get('selectionPolicy')!r}")
    return plan, actual


def apply_plan(
    plan_path: Path,
    expected_plan_sha256: str,
    *,
    raw_root: Path = DEFAULT_RAW_ROOT,
    loader_source: Path = DEFAULT_LOADER_SOURCE,
    apply_report_path: Path = DEFAULT_APPLY_REPORT,
) -> dict[str, Any]:
    """Validate, precompute, and transactionally apply one complete raw plan."""

    root = canonical_raw_root(raw_root)
    if plan_path.resolve() == apply_report_path.resolve():
        raise ModelAuditError("Apply plan and apply report must be different files")
    ensure_reports_outside_sources((plan_path, apply_report_path), (root,))
    plan, plan_sha = _load_plan(plan_path, expected_plan_sha256)

    pre_audit = audit_models(root, loader_source=loader_source)
    regenerated = generate_plan(pre_audit)
    if plan != regenerated:
        raise ModelAuditError(
            "Raw UV plan is stale, tampered, incomplete, or no longer exactly matches the full source inventory"
        )
    operations = plan["operations"]
    if not operations:
        raise ModelAuditError("Raw UV apply plan contains no operations")
    ids = [operation["id"] for operation in operations]
    targets = [(operation["canonicalPath"], operation["facePointer"]) for operation in operations]
    if len(ids) != len(set(ids)) or len(targets) != len(set(targets)):
        raise ModelAuditError("Raw UV apply plan repeats an operation ID or face target")

    locked_paths: dict[str, Path] = {}
    locked_bytes: dict[str, bytes] = {}
    for row in plan["files"]:
        canonical_path = row["canonicalPath"]
        model_file, resolved_path = raw_file_from_canonical_path(root, canonical_path)
        if resolved_path != canonical_path or canonical_path in locked_paths:
            raise ModelAuditError(f"Raw UV plan has a duplicate or non-canonical file lock: {canonical_path}")
        payload = model_file.read_bytes()
        if sha256_bytes(payload) != row["sourceSha256"]:
            raise ModelAuditError(f"Raw UV source SHA-256 lock failed: {canonical_path}")
        locked_paths[canonical_path] = model_file
        locked_bytes[canonical_path] = payload
    if sorted(locked_paths) != sorted(plan["registeredCanonicalPaths"]):
        raise ModelAuditError("Raw UV plan does not lock the complete loader-registered source list")
    if scan_manifest_sha256(
        (canonical_path, sha256_bytes(payload)) for canonical_path, payload in locked_bytes.items()
    ) != plan["sourceManifestSha256"]:
        raise ModelAuditError("Raw UV full-source manifest lock failed")

    operations_by_file: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for operation in operations:
        operations_by_file[operation["canonicalPath"]].append(operation)
    if set(operations_by_file) - set(locked_paths):
        raise ModelAuditError("Raw UV plan has operations outside the locked source inventory")

    prepared: dict[str, dict[str, Any]] = {}
    overlay: dict[str, bytes] = {}
    for canonical_path in sorted(operations_by_file):
        before = locked_bytes[canonical_path]
        after, edit_rows = _prepare_file_edits(before, operations_by_file[canonical_path])
        prepared[canonical_path] = {
            "path": locked_paths[canonical_path],
            "before": before,
            "after": after,
            "sourceSha256Before": sha256_bytes(before),
            "sourceSha256After": sha256_bytes(after),
            "operationIds": [operation["id"] for operation in operations_by_file[canonical_path]],
            "edits": edit_rows,
        }
        overlay[canonical_path] = after

    in_memory_post_audit = audit_models(root, loader_source=loader_source, source_overrides=overlay)
    if in_memory_post_audit["summary"]["rotatedOutOfRangeFaces"] != 0:
        raise ModelAuditError(
            "In-memory raw UV postcondition failed: rotated out-of-range faces remain"
        )
    if in_memory_post_audit["summary"]["unresolvedRotatedFaces"] != 0:
        raise ModelAuditError("In-memory raw UV postcondition produced unresolved rotated faces")
    if generate_plan(in_memory_post_audit)["operationCounts"]["total"] != 0:
        raise ModelAuditError("In-memory raw UV postcondition is not idempotent")

    # Close the validation-to-write window over every registered source and the
    # loader contract before the first mutation.
    _, current_loader_sha = registered_raw_paths(loader_source)
    if current_loader_sha != plan["loaderSourceSha256"]:
        raise ModelAuditError("Raw loader source changed after plan validation")
    for canonical_path, model_file in locked_paths.items():
        if model_file.read_bytes() != locked_bytes[canonical_path]:
            raise ModelAuditError(f"Raw source changed after plan validation: {canonical_path}")

    written: list[str] = []
    try:
        for canonical_path in sorted(prepared):
            row = prepared[canonical_path]
            if row["path"].read_bytes() != row["before"]:
                raise ModelAuditError(f"Raw source changed before atomic write: {canonical_path}")
            atomic_write_bytes(row["path"], row["after"])
            written.append(canonical_path)
            if row["path"].read_bytes() != row["after"]:
                raise ModelAuditError(f"Raw source atomic-write readback failed: {canonical_path}")

        post_audit = audit_models(root, loader_source=loader_source)
        if post_audit["summary"]["rotatedOutOfRangeFaces"] != 0:
            raise ModelAuditError("Written raw UV postcondition failed: rotated out-of-range faces remain")
        if post_audit["loaderSourceSha256"] != plan["loaderSourceSha256"]:
            raise ModelAuditError("Raw loader source changed during apply")
        if post_audit["sourceManifestSha256"] != in_memory_post_audit["sourceManifestSha256"]:
            raise ModelAuditError("Written raw UV source manifest differs from the validated in-memory overlay")
        if generate_plan(post_audit)["operationCounts"]["total"] != 0:
            raise ModelAuditError("Written raw UV postcondition is not idempotent")
        for canonical_path, row in prepared.items():
            if row["path"].read_bytes() != row["after"]:
                raise ModelAuditError(f"Raw UV post-write byte readback changed: {canonical_path}")
        for canonical_path, model_file in locked_paths.items():
            expected = overlay.get(canonical_path, locked_bytes[canonical_path])
            if model_file.read_bytes() != expected:
                raise ModelAuditError(f"Raw UV full-inventory final readback changed: {canonical_path}")

        apply_report = {
            "schemaVersion": APPLY_SCHEMA_VERSION,
            "mode": "raw_uv_apply",
            "sourceWrites": True,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "plan": {"path": str(plan_path.resolve()), "sha256": plan_sha},
            "canonicalRawRoot": str(root),
            "safety": {
                "completeRegisteredSourceManifest": True,
                "loaderSourceShaLocked": True,
                "sourceShaLocked": True,
                "planRegeneratedExactlyBeforeApply": True,
                "selectedScalarTokensAndOffsetMemberInsertionsOnly": True,
                "deepComparison": True,
                "allFilesPrecomputedBeforeWrite": True,
                "inMemoryFullRootPostcondition": True,
                "atomicMultiFileWritesWithRollback": True,
                "writeReadbackVerified": True,
                "explicitUvEditedDirectly": True,
                "offsetReservedForImplicitUv": True,
            },
            "operationCounts": copy.deepcopy(plan["operationCounts"]),
            "files": [
                {
                    "canonicalPath": canonical_path,
                    "sourceSha256Before": row["sourceSha256Before"],
                    "sourceSha256After": row["sourceSha256After"],
                    "operationIds": row["operationIds"],
                    "edits": row["edits"],
                }
                for canonical_path, row in sorted(prepared.items())
            ],
            "postAuditSummary": copy.deepcopy(post_audit["summary"]),
            "postSourceManifestSha256": post_audit["sourceManifestSha256"],
            "result": "applied_and_verified",
        }
        atomic_write_text(apply_report_path, json.dumps(apply_report, indent=2, ensure_ascii=False) + "\n")
        return apply_report
    except BaseException:
        rollback_failures: list[str] = []
        for canonical_path in reversed(written):
            row = prepared[canonical_path]
            try:
                atomic_write_bytes(row["path"], row["before"])
                if row["path"].read_bytes() != row["before"]:
                    raise OSError("rollback readback mismatch")
            except BaseException as rollback_exception:
                rollback_failures.append(f"{canonical_path}: {rollback_exception}")
        if rollback_failures:
            raise ModelAuditError(
                "Raw UV apply failed and rollback was incomplete: " + "; ".join(rollback_failures)
            )
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "raw_root",
        nargs="?",
        type=Path,
        default=DEFAULT_RAW_ROOT,
        help=f"complete raw authoring-model root (default: {DEFAULT_RAW_ROOT})",
    )
    parser.add_argument("--loader-source", type=Path, default=DEFAULT_LOADER_SOURCE)
    parser.add_argument("--json-report", type=Path, default=DEFAULT_JSON_REPORT)
    parser.add_argument("--csv-report", type=Path, default=DEFAULT_CSV_REPORT)
    parser.add_argument("--generate-plan", type=Path)
    parser.add_argument("--apply-plan", type=Path)
    parser.add_argument("--expect-plan-sha256")
    parser.add_argument("--apply-report", type=Path, default=DEFAULT_APPLY_REPORT)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.apply_plan is not None:
            if not args.expect_plan_sha256:
                raise ModelAuditError("--apply-plan requires --expect-plan-sha256")
            if args.generate_plan is not None:
                raise ModelAuditError("--generate-plan and --apply-plan are mutually exclusive")
            report = apply_plan(
                args.apply_plan,
                args.expect_plan_sha256,
                raw_root=args.raw_root,
                loader_source=args.loader_source,
                apply_report_path=args.apply_report,
            )
            print(
                "Applied and verified "
                f"{report['operationCounts']['total']} raw UV operations across {len(report['files'])} files."
            )
            return 0
        if args.expect_plan_sha256:
            raise ModelAuditError("--expect-plan-sha256 is valid only with --apply-plan")

        audit = audit_models(args.raw_root, loader_source=args.loader_source)
        write_audit_reports(audit, args.json_report, args.csv_report)
        if args.generate_plan is not None:
            ensure_reports_outside_sources((args.generate_plan,), (canonical_raw_root(args.raw_root),))
            if args.generate_plan.resolve() in {args.json_report.resolve(), args.csv_report.resolve()}:
                raise ModelAuditError("Plan, JSON report, and CSV report destinations must differ")
            plan = generate_plan(audit)
            atomic_write_text(args.generate_plan, json.dumps(plan, indent=2, ensure_ascii=False) + "\n")
            print(
                f"Wrote locked raw UV plan with {plan['operationCounts']['total']} operations: "
                f"{args.generate_plan.resolve()}"
            )
        summary = audit["summary"]
        print(
            "Raw UV audit: "
            f"files={summary['filesScanned']}, faces={summary['facesScanned']}, "
            f"outOfRange={summary['outOfRangeFaces']}, "
            f"rotatedOutOfRange={summary['rotatedOutOfRangeFaces']}, "
            f"whole16={summary['explicitInteger16Candidates']}, "
            f"explicitReanchors={summary['explicitBoundaryReanchors']}, "
            f"implicitOffsets={summary['implicitOffsetCandidates']}, "
            f"unresolved={summary['unresolvedRotatedFaces']}."
        )
        return 0
    except (ModelAuditError, OSError) as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
