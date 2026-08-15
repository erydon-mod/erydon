#!/usr/bin/env python3
"""Shared, standard-library-only helpers for model-geometry audits.

They provide deterministic path discovery, vanilla Java-model UV arithmetic,
stable hashes, and atomic writing primitives for the tools in ``tools``.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence


UV_MIN = 0.0
UV_MAX = 16.0
UV_PERIOD = 16.0
EPSILON = 1.0e-9
FACE_DIRECTIONS = ("down", "up", "north", "south", "west", "east")


class ModelAuditError(ValueError):
    """Raised when an audit input or report destination is unsafe."""


@dataclass(frozen=True)
class IntervalAnalysis:
    """Classification of one U or V endpoint pair."""

    classification: str
    shift: float | None
    before: tuple[float, float]
    after: tuple[float, float] | None
    span: float


@dataclass(frozen=True)
class UvRectAnalysis:
    """Classification of a four-number Java-model UV rectangle."""

    classification: str
    before: tuple[float, float, float, float]
    proposed: tuple[float, float, float, float] | None
    u: IntervalAnalysis
    v: IntervalAnalysis


def canonical_json(value: Any) -> str:
    """Return a stable JSON representation suitable for hashing."""

    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def stable_hash(value: Any) -> str:
    return sha256_bytes(canonical_json(value).encode("utf-8"))


def clean_number(value: float) -> int | float:
    """Keep reports readable without changing meaningful numeric values."""

    value = float(value)
    if abs(value) <= EPSILON:
        return 0
    rounded_integer = round(value)
    if abs(value - rounded_integer) <= EPSILON:
        return int(rounded_integer)
    return value


def finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelAuditError(f"{label} must be a finite number, got {value!r}")
    result = float(value)
    if not math.isfinite(result):
        raise ModelAuditError(f"{label} must be finite, got {value!r}")
    return result


def numeric_vector(value: Any, length: int, label: str) -> tuple[float, ...]:
    if not isinstance(value, (list, tuple)) or len(value) != length:
        raise ModelAuditError(f"{label} must be a {length}-number array, got {value!r}")
    return tuple(finite_number(item, f"{label}[{index}]") for index, item in enumerate(value))


def is_in_uv_range(value: float) -> bool:
    return UV_MIN - EPSILON <= value <= UV_MAX + EPSILON


def _integer_shift_candidates(low: float, high: float) -> list[int]:
    """Return integer period multipliers that move an interval into 0..16."""

    # Expand the integer bounds by EPSILON solely to absorb JSON/float noise at
    # exact sprite edges. The proposed shift remains an exact multiple of 16.
    minimum = math.ceil((UV_MIN - low - EPSILON) / UV_PERIOD)
    maximum = math.floor((UV_MAX - high + EPSILON) / UV_PERIOD)
    if minimum > maximum:
        return []
    return list(range(minimum, maximum + 1))


def classify_interval(first: Any, second: Any) -> IntervalAnalysis:
    """Classify whether an endpoint pair can be translated into 0..16.

    An arithmetic candidate is a translation by an integer multiple of 16.
    Such a shift changes which atlas copy is sampled without changing texture
    scale, orientation, or phase modulo one sprite. This arithmetic property is
    not a CTM-safety claim. Boundary-crossing intervals are blocked.
    """

    a = finite_number(first, "UV endpoint")
    b = finite_number(second, "UV endpoint")
    low, high = sorted((a, b))
    span = high - low

    if is_in_uv_range(a) and is_in_uv_range(b):
        return IntervalAnalysis("in_range", 0.0, (a, b), (a, b), span)
    if span > UV_PERIOD + EPSILON:
        return IntervalAnalysis("span_over_16", None, (a, b), None, span)

    candidates = _integer_shift_candidates(low, high)
    non_zero = [candidate for candidate in candidates if candidate != 0]
    if not non_zero:
        return IntervalAnalysis("boundary_crossing", None, (a, b), None, span)

    # Prefer the smallest move; on an exact tie use the negative shift first
    # for deterministic output.
    multiplier = min(non_zero, key=lambda candidate: (abs(candidate), candidate))
    shift = multiplier * UV_PERIOD
    after = (a + shift, b + shift)
    if not all(is_in_uv_range(value) for value in after):
        raise AssertionError(f"Integer UV shift escaped the sprite: {(a, b)} + {shift} -> {after}")
    return IntervalAnalysis("integer_16_shift", shift, (a, b), after, span)


def classify_uv_rect(value: Any) -> UvRectAnalysis:
    uv = numeric_vector(value, 4, "face uv")
    u = classify_interval(uv[0], uv[2])
    v = classify_interval(uv[1], uv[3])
    classes = {u.classification, v.classification}

    if classes == {"in_range"}:
        classification = "in_range"
        proposed = uv
    elif "span_over_16" in classes:
        classification = "blocked_span_over_16"
        proposed = None
    elif "boundary_crossing" in classes:
        classification = "blocked_boundary_crossing"
        proposed = None
    else:
        classification = "integer_16_shift_candidate"
        assert u.after is not None and v.after is not None
        proposed = (u.after[0], v.after[0], u.after[1], v.after[1])

    return UvRectAnalysis(classification, uv, proposed, u, v)


def default_face_uv(element: dict[str, Any], face: str) -> tuple[float, float, float, float]:
    """Return Minecraft's default UV rectangle for an unbaked Java element."""

    if face not in FACE_DIRECTIONS:
        raise ModelAuditError(f"Unsupported face direction: {face!r}")
    from_vector = numeric_vector(element.get("from"), 3, "element from")
    to_vector = numeric_vector(element.get("to"), 3, "element to")
    if any(from_vector[index] > to_vector[index] + EPSILON for index in range(3)):
        raise ModelAuditError(f"Reversed element bounds are unsupported: {from_vector!r} -> {to_vector!r}")

    x1, y1, z1 = from_vector
    x2, y2, z2 = to_vector
    return {
        "down": (x1, UV_MAX - z2, x2, UV_MAX - z1),
        "up": (x1, z1, x2, z2),
        "north": (UV_MAX - x2, UV_MAX - y2, UV_MAX - x1, UV_MAX - y1),
        "south": (x1, UV_MAX - y2, x2, UV_MAX - y1),
        "west": (z1, UV_MAX - y2, z2, UV_MAX - y1),
        "east": (UV_MAX - z2, UV_MAX - y2, UV_MAX - z1, UV_MAX - y1),
    }[face]


def element_fingerprint(element: dict[str, Any]) -> str:
    """Hash geometry-affecting element fields, excluding its list index."""

    payload = {
        "from": element.get("from"),
        "to": element.get("to"),
        "rotation": element.get("rotation"),
        "shade": element.get("shade"),
    }
    return stable_hash(payload)


def stable_candidate_id(
    *,
    model_path: str,
    element_hash: str,
    element_occurrence: int,
    face: str,
    uv_source: str,
    uv_before: Sequence[float],
    face_data: dict[str, Any],
) -> str:
    """Build an ID stable across unrelated element reordering and machines."""

    face_metadata = {key: value for key, value in face_data.items() if key != "uv"}
    payload = {
        "model": model_path.replace("\\", "/"),
        "element": element_hash,
        "occurrence": element_occurrence,
        "face": face,
        "uvSource": uv_source,
        "uvBefore": list(uv_before),
        "faceMetadata": face_metadata,
    }
    return "uv-" + stable_hash(payload)[:24]


def discover_json_files(inputs: Iterable[Path]) -> list[Path]:
    """Recursively discover standard Java-model JSON files."""

    discovered: dict[Path, Path] = {}
    for raw_path in inputs:
        path = raw_path.resolve()
        if not path.exists():
            raise ModelAuditError(f"Audit input does not exist: {path}")
        if "authoring_models" in {part.lower() for part in path.parts}:
            raise ModelAuditError(
                f"Raw authoring models require group-aware auditing and are outside this tool's scope: {path}"
            )
        if path.is_dir():
            for candidate in path.rglob("*.json"):
                if candidate.is_file():
                    if "authoring_models" in {part.lower() for part in candidate.parts}:
                        raise ModelAuditError(
                            "A broad input included raw authoring models; pass the standard assets/.../models "
                            f"root instead: {candidate.resolve()}"
                        )
                    discovered[candidate.resolve()] = candidate.resolve()
        elif path.suffix.lower() == ".json":
            discovered[path] = path
        else:
            raise ModelAuditError(f"Expected a JSON model file or directory: {path}")
    return sorted(discovered.values(), key=lambda item: item.as_posix().lower())


def common_input_root(inputs: Iterable[Path], files: Sequence[Path]) -> Path:
    """Choose a deterministic root for portable relative model paths."""

    resolved_inputs = [path.resolve() for path in inputs]
    directory_inputs = [path for path in resolved_inputs if path.is_dir()]
    if len(directory_inputs) == 1 and all(file.is_relative_to(directory_inputs[0]) for file in files):
        return directory_inputs[0]
    if not files:
        return resolved_inputs[0] if resolved_inputs else Path.cwd().resolve()
    return Path(os.path.commonpath([str(file.parent) for file in files])).resolve()


def relative_model_path(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def _absolute_without_resolving(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def canonical_models_root(path: Path) -> Path:
    """Resolve and validate the canonical standard-model root."""

    lexical = _absolute_without_resolving(path)
    if not lexical.exists() or not lexical.is_dir():
        raise ModelAuditError(f"Canonical models root is not a directory: {lexical}")
    if lexical.is_symlink():
        raise ModelAuditError(f"Canonical models root must not be a symlink: {lexical}")
    if "authoring_models" in {part.lower() for part in lexical.parts}:
        raise ModelAuditError(f"Raw authoring models are outside this tool's scope: {lexical}")
    return lexical.resolve(strict=True)


def safe_canonical_model_file(path: Path, root: Path) -> tuple[Path, str]:
    """Return a real standard-model file and its root-relative POSIX path.

    Every lexical component beneath the canonical root is checked before
    resolving. This rejects both escaping paths and symlinks, including a
    symlink that happens to point back inside the root.
    """

    root_resolved = canonical_models_root(root)
    root_lexical = _absolute_without_resolving(root)
    target_lexical = _absolute_without_resolving(path)
    try:
        relative = target_lexical.relative_to(root_lexical)
    except ValueError as exception:
        raise ModelAuditError(f"Model path escapes canonical root: {target_lexical}") from exception
    if not relative.parts:
        raise ModelAuditError(f"Expected a model file beneath canonical root: {target_lexical}")

    current = root_lexical
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ModelAuditError(f"Symlinked model paths are not allowed: {current}")

    try:
        target_resolved = target_lexical.resolve(strict=True)
    except OSError as exception:
        raise ModelAuditError(f"Model path does not exist: {target_lexical}") from exception
    try:
        canonical_relative = target_resolved.relative_to(root_resolved)
    except ValueError as exception:
        raise ModelAuditError(f"Resolved model path escapes canonical root: {target_resolved}") from exception
    if not target_resolved.is_file() or target_resolved.suffix.lower() != ".json":
        raise ModelAuditError(f"Expected a canonical JSON model file: {target_resolved}")
    return target_resolved, canonical_relative.as_posix()


def model_file_from_canonical_path(root: Path, canonical_path: str) -> tuple[Path, str]:
    """Safely resolve a plan's canonical POSIX model path."""

    if not isinstance(canonical_path, str) or not canonical_path:
        raise ModelAuditError("Plan canonicalPath must be a non-empty string")
    pure = PurePosixPath(canonical_path)
    if pure.is_absolute() or any(part in {"", ".", ".."} for part in pure.parts):
        raise ModelAuditError(f"Plan canonicalPath must be a clean relative POSIX path: {canonical_path!r}")
    if "\\" in canonical_path or pure.suffix.lower() != ".json":
        raise ModelAuditError(f"Plan canonicalPath must name a JSON model with '/' separators: {canonical_path!r}")
    root_path = _absolute_without_resolving(root)
    return safe_canonical_model_file(root_path.joinpath(*pure.parts), root_path)


def scan_manifest_sha256(rows: Iterable[tuple[str, str]]) -> str:
    digest = hashlib.sha256()
    for relative_path, source_sha in sorted(rows):
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(source_sha.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def path_is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def ensure_reports_outside_sources(report_paths: Iterable[Path], source_paths: Iterable[Path]) -> None:
    """Refuse report destinations that could overwrite audited source files."""

    sources = [path.resolve() for path in source_paths]
    source_files = {path for path in sources if path.is_file()}
    source_directories = [path for path in sources if path.is_dir()]
    source_directories.extend(path.parent for path in source_files)
    reports = [path.resolve() for path in report_paths]
    if len(reports) != len(set(reports)):
        raise ModelAuditError("JSON, CSV, and apply report destinations must be different files")
    for report in reports:
        if report in source_files or any(path_is_within(report, directory) for directory in source_directories):
            raise ModelAuditError(f"Report destination must be outside audited source roots: {report}")


def atomic_write_text(path: Path, text: str) -> None:
    """Atomically replace a report without ever touching source model files."""

    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="") as output:
            output.write(text)
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def atomic_write_bytes(path: Path, payload: bytes) -> None:
    """Atomically replace a validated file with already prepared bytes."""

    path = path.resolve()
    handle, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(handle, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise
