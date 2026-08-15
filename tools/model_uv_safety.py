#!/usr/bin/env python3
"""Audit ERYDON Java-model UVs and apply explicitly reviewed UV-only plans.

Audit is the default and never changes a model. It recursively scans standard
Java-model JSON files, identifies UV rectangles outside the current 0..16
sprite, and reports integer-16 arithmetic candidates separately from mappings
that cross a sprite boundary or span more than one sprite.

``--generate-rotated-plan PLAN`` writes a deterministic, full-root locked
inventory without changing models. The only model-write mode remains
``--apply-plan PLAN --expect-plan-sha256 HASH``; reviewed flagged plans apply
only their locked UV-array and from/to/origin scalar tokens after recomputing
every proof, with full-root post-audit and multi-file rollback.
"""

from __future__ import annotations

import argparse
import copy
import csv
import io
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence

from model_geometry_common import (
    FACE_DIRECTIONS,
    ModelAuditError,
    atomic_write_bytes,
    atomic_write_text,
    canonical_models_root,
    classify_interval,
    classify_uv_rect,
    clean_number,
    default_face_uv,
    discover_json_files,
    element_fingerprint,
    ensure_reports_outside_sources,
    model_file_from_canonical_path,
    numeric_vector,
    safe_canonical_model_file,
    scan_manifest_sha256,
    sha256_bytes,
    stable_hash,
    stable_candidate_id,
)
from normalize_java_model_bounds import (
    MAX_GEOMETRY_DRIFT,
    clean_number as geometry_clean_number,
    maximum_corner_drift,
    nearest_range_shift,
    pivot_shift,
    rendered_corners,
)


SCHEMA_VERSION = 1
APPLY_PLAN_SCHEMA_VERSION = 1
APPLY_REPORT_SCHEMA_VERSION = 1
DEFAULT_SOURCE_ROOT = Path("src/main/resources/assets/erydon/models")
DEFAULT_JSON_REPORT = Path("build/reports/model-geometry/uv-audit.json")
DEFAULT_CSV_REPORT = Path("build/reports/model-geometry/uv-candidates.csv")
DEFAULT_APPLY_REPORT = Path("build/reports/model-geometry/uv-apply.json")
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
ROTATED_BULK_SELECTION_POLICY = "rotated_explicit_uv_and_geometry_review_v1"
AXIS_INDEX = {"x": 0, "y": 1, "z": 2}


def _pointer_escape(token: str) -> str:
    return token.replace("~", "~0").replace("/", "~1")


def _pointer_unescape(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


def _face_uv_pointer(element_index: int, face: str) -> str:
    return f"/elements/{element_index}/faces/{_pointer_escape(face)}/uv"


def _new_summary() -> dict[str, int]:
    return {
        "filesDiscovered": 0,
        "filesScanned": 0,
        "geometryModels": 0,
        "modelsWithEmptyElements": 0,
        "modelsWithoutElements": 0,
        "elements": 0,
        "faces": 0,
        "facesInRange": 0,
        "explicitOutOfRangeFaces": 0,
        "derivedOutOfRangeFaces": 0,
        "integer16ArithmeticCandidates": 0,
        "explicitApplyEligibleCandidates": 0,
        "derivedReviewCandidates": 0,
        "rotatedOutOfRangeFaces": 0,
        "rotatedExplicitOutOfRangeFaces": 0,
        "rotatedDerivedOutOfRangeFaces": 0,
        "rotatedExplicitTokenShiftCandidates": 0,
        "rotatedExplicitTokenShiftFiles": 0,
        "rotatedDerivedGeometryShiftFaces": 0,
        "rotatedDerivedGeometryShiftElements": 0,
        "rotatedDerivedGeometryShiftFiles": 0,
        "rotatedExplicitReportOnlyFaces": 0,
        "rotatedDerivedReportOnlyFaces": 0,
        "rotatedReportOnlyFaces": 0,
        "reviewPhaseReanchorFaces": 0,
        "reviewPhaseReanchorFiles": 0,
        "reviewGeometryReanchorFaces": 0,
        "reviewGeometryReanchorElements": 0,
        "reviewGeometryReanchorFiles": 0,
        "rotatedUnresolvedReportOnlyFaces": 0,
        "blockedBoundaryCrossing": 0,
        "blockedSpanOver16": 0,
        "parseFailures": 0,
        "validationFailures": 0,
    }


def _report_number_list(values: Iterable[float] | None) -> list[int | float] | None:
    if values is None:
        return None
    return [clean_number(value) for value in values]


def _geometry_number_list(values: Iterable[float]) -> list[int | float]:
    """Round computed geometry deltas exactly as the normalization proof tool."""

    return [geometry_clean_number(value) for value in values]


def _model_kind(model_path: str) -> str:
    first = model_path.split("/", 1)[0]
    return first if first in {"block", "item"} else "other"


def _finding_reason(classification: str, uv_source: str) -> str:
    prefix = "Authored" if uv_source == "explicit" else "Vanilla-derived"
    if classification == "integer_16_shift_candidate":
        return (
            f"{prefix} UV endpoints fit one sprite after integer multiples of 16. "
            "This is an arithmetic phase-preserving candidate, not a CTM-safety claim."
        )
    if classification == "blocked_boundary_crossing":
        return (
            f"{prefix} UV endpoints straddle a 16-unit sprite boundary; one translation "
            "cannot keep the whole face inside 0..16 without changing texture phase."
        )
    if classification == "blocked_span_over_16":
        return (
            f"{prefix} UV span exceeds 16 units; a single Java-model face cannot preserve "
            "that mapping inside one atlas sprite."
        )
    return f"Unsupported UV classification: {classification}"


def _review_reason(classification: str, uv_source: str) -> str | None:
    if classification != "integer_16_shift_candidate":
        return "Blocked arithmetic case; no automatic apply is permitted."
    if uv_source == "derived":
        return (
            "Applying this arithmetic candidate would create a new explicit UV. "
            "That can change CTM/world-alignment behavior and requires geometry and CTM review."
        )
    return None


def _rotation_state(element: dict[str, Any]) -> tuple[bool, dict[str, Any] | None, str | None]:
    """Identify a structurally valid non-zero Java-model element rotation."""

    rotation = element.get("rotation")
    if rotation is None:
        return False, None, "Element has no Java-model rotation."
    if not isinstance(rotation, dict):
        return False, None, "Element rotation is not a JSON object."
    angle = rotation.get("angle")
    if isinstance(angle, bool) or not isinstance(angle, (int, float)) or not math.isfinite(float(angle)):
        return False, None, "Element rotation angle is missing or invalid."
    axis = rotation.get("axis")
    if axis not in AXIS_INDEX:
        return False, None, "Element rotation axis is missing or invalid."
    try:
        origin = numeric_vector(rotation.get("origin", [8, 8, 8]), 3, "rotation origin")
    except ModelAuditError as exception:
        return False, None, str(exception)
    if "rescale" in rotation and not isinstance(rotation["rescale"], bool):
        return False, None, "Element rotation rescale must be boolean when present."
    if abs(float(angle)) <= 1.0e-9:
        return False, None, "Element rotation angle is zero."
    if not any(abs(float(angle) - allowed) <= 1.0e-9 for allowed in (-45.0, -22.5, 22.5, 45.0)):
        return False, None, "Element rotation angle is outside vanilla Java-model values."
    return (
        True,
        {
            "axis": axis,
            "angle": float(angle),
            "origin": origin,
            "rescale": bool(rotation.get("rescale", False)),
        },
        None,
    )


def _geometry_block(code: str, reason: str) -> tuple[None, str, str]:
    return None, code, reason


def _analyze_geometry_origin_shift(
    *,
    element: dict[str, Any],
    model_path: str,
    source_sha: str,
    element_index: int,
    element_hash: str,
    element_occurrence: int,
    element_findings: Sequence[dict[str, Any]],
    rotated: bool,
    rotation_metadata: dict[str, Any] | None,
    rotation_review_reason: str | None,
) -> tuple[dict[str, Any] | None, str | None, str | None]:
    """Prove one report-only pivot-compensated repair for UV-free faces.

    The box is translated only perpendicular to its rotation axis. The pivot
    shift solves the inverse rotation equation used by
    ``normalize_java_model_bounds.py``. All implicit faces must remain implicit
    and derive wholly in-range UVs after the proposal; otherwise the entire
    element stays report-only.
    """

    derived_out_of_range = [finding for finding in element_findings if finding["uvSource"] == "derived"]
    if not derived_out_of_range:
        return None, None, None
    if not rotated or rotation_metadata is None:
        return _geometry_block(
            "not_rotated",
            rotation_review_reason or "Element is not a valid non-zero rotated Java-model element.",
        )
    if rotation_metadata["rescale"]:
        return _geometry_block(
            "rescale_unsupported",
            "Pivot-equivalent normalization for rotation rescale=true is intentionally report-only and unproved.",
        )
    if "origin" not in element.get("rotation", {}):
        return _geometry_block(
            "rotation_origin_token_missing",
            "Pivot compensation would need to add a missing rotation origin; scalar-token apply requires an authored origin array.",
        )

    try:
        from_vector = numeric_vector(element.get("from"), 3, "element from")
        to_vector = numeric_vector(element.get("to"), 3, "element to")
        axis_index = AXIS_INDEX[rotation_metadata["axis"]]
        box_shift = [0.0, 0.0, 0.0]
        for coordinate in range(3):
            if coordinate != axis_index:
                box_shift[coordinate] = nearest_range_shift(
                    from_vector[coordinate], to_vector[coordinate]
                )
    except (ModelAuditError, ValueError) as exception:
        return _geometry_block("bounds_do_not_fit", str(exception))

    if not any(abs(value) > 1.0e-9 for value in box_shift):
        return _geometry_block(
            "rotation_axis_dependency",
            "The out-of-range derived UV depends on the rotation-axis coordinate. "
            "A box shift along that axis cannot be pivot-compensated without moving rendered geometry.",
        )

    try:
        origin_shift = pivot_shift(
            rotation_metadata["axis"], rotation_metadata["angle"], box_shift
        )
    except ValueError as exception:
        return _geometry_block("pivot_solution_failed", str(exception))

    proposed_element = copy.deepcopy(element)
    proposed_element["from"] = [
        geometry_clean_number(from_vector[index] + box_shift[index]) for index in range(3)
    ]
    proposed_element["to"] = [
        geometry_clean_number(to_vector[index] + box_shift[index]) for index in range(3)
    ]
    proposed_rotation = proposed_element["rotation"]
    proposed_rotation["origin"] = [
        geometry_clean_number(rotation_metadata["origin"][index] + origin_shift[index])
        for index in range(3)
    ]

    try:
        geometry_drift = maximum_corner_drift(element, proposed_element)
    except (KeyError, TypeError, ValueError) as exception:
        return _geometry_block("geometry_proof_failed", str(exception))
    if geometry_drift > MAX_GEOMETRY_DRIFT:
        return _geometry_block(
            "geometry_drift",
            f"Maximum transformed-corner drift {geometry_drift:.12g} exceeds {MAX_GEOMETRY_DRIFT:g}.",
        )

    findings_by_face = {finding["face"]: finding for finding in derived_out_of_range}
    checked_faces: list[dict[str, Any]] = []
    remaining_out_of_range: list[str] = []
    faces = element.get("faces", {})
    for face in sorted(faces):
        face_data = faces[face]
        if not isinstance(face_data, dict) or "uv" in face_data:
            continue
        try:
            before = classify_uv_rect(default_face_uv(element, face))
            after = classify_uv_rect(default_face_uv(proposed_element, face))
        except ModelAuditError as exception:
            return _geometry_block("derived_uv_validation_failed", str(exception))
        row = {
            "face": face,
            "texture": face_data.get("texture"),
            "findingId": findings_by_face.get(face, {}).get("id"),
            "classificationBefore": before.classification,
            "derivedUvBefore": _report_number_list(before.before),
            "derivedUvAfter": _report_number_list(after.before),
            "inRangeAfter": after.classification == "in_range",
            "remainsUvFree": True,
        }
        checked_faces.append(row)
        if after.classification != "in_range":
            remaining_out_of_range.append(f"{face}:{after.classification}")

    if remaining_out_of_range:
        return _geometry_block(
            "remaining_implicit_out_of_range",
            "Pivot-equivalent translation would leave UV-free faces out of range: "
            + ", ".join(remaining_out_of_range),
        )

    repaired_faces = [
        row for row in checked_faces if row["classificationBefore"] != "in_range"
    ]
    repaired_ids = {row["findingId"] for row in repaired_faces}
    expected_ids = {finding["id"] for finding in derived_out_of_range}
    if repaired_ids != expected_ids:
        return _geometry_block(
            "finding_coverage_mismatch",
            "Geometry proposal does not account for every out-of-range implicit face on the element.",
        )

    expected_geometry = {
        "from": _report_number_list(from_vector),
        "to": _report_number_list(to_vector),
        "rotation": copy.deepcopy(element["rotation"]),
    }
    proposed_geometry = {
        "from": copy.deepcopy(proposed_element["from"]),
        "to": copy.deepcopy(proposed_element["to"]),
        "rotation": copy.deepcopy(proposed_rotation),
    }
    geometry_id = "uvgeom-" + stable_hash(
        {
            "model": model_path,
            "element": element_hash,
            "occurrence": element_occurrence,
            "expected": expected_geometry,
            "proposed": proposed_geometry,
            "repairedFindingIds": sorted(expected_ids),
        }
    )[:24]
    candidate = {
        "id": geometry_id,
        "operation": "geometry_origin_shift",
        "status": "report_only",
        "applyEligible": False,
        "canonicalPath": model_path,
        "sourceSha256": source_sha,
        "jsonPointer": f"/elements/{element_index}",
        "elementIndex": element_index,
        "elementFingerprint": element_hash,
        "elementOccurrence": element_occurrence,
        "expectedGeometry": expected_geometry,
        "proposedGeometry": proposed_geometry,
        "boxShift": _geometry_number_list(box_shift),
        "originShift": _geometry_number_list(origin_shift),
        "rotationAxis": rotation_metadata["axis"],
        "rotationAngle": clean_number(rotation_metadata["angle"]),
        "maxTransformedCornerDrift": geometry_clean_number(geometry_drift),
        "geometryProofTolerance": MAX_GEOMETRY_DRIFT,
        "geometryPreserved": True,
        "facesRemainUvFree": True,
        "derivedFacesChecked": checked_faces,
        "repairedDerivedFaces": repaired_faces,
        "repairedDerivedFaceCount": len(repaired_faces),
        "reviewReason": (
            "Geometry proposal preserves transformed corners and keeps implicit faces UV-free. It is writable only as "
            "part of a complete SHA-locked rotated plan generated with reviewed reanchors enabled."
        ),
    }
    return candidate, None, None


def _analyze_phase_reanchor(finding: dict[str, Any]) -> dict[str, Any] | None:
    """Propose a minimum non-exact translation for one explicit seam case."""

    if (
        not finding["rotatedElement"]
        or finding["uvSource"] != "explicit"
        or finding["classification"] != "blocked_boundary_crossing"
    ):
        return None
    uv = numeric_vector(finding["uvBefore"], 4, "phase reanchor UV")
    try:
        u_shift = nearest_range_shift(min(uv[0], uv[2]), max(uv[0], uv[2]))
        v_shift = nearest_range_shift(min(uv[1], uv[3]), max(uv[1], uv[3]))
    except ValueError:
        return None
    proposed = (uv[0] + u_shift, uv[1] + v_shift, uv[2] + u_shift, uv[3] + v_shift)
    analysis = classify_uv_rect(proposed)
    if analysis.classification != "in_range":
        return None
    if abs((uv[2] - uv[0]) - (proposed[2] - proposed[0])) > 1.0e-9 or abs(
        (uv[3] - uv[1]) - (proposed[3] - proposed[1])
    ) > 1.0e-9:
        raise AssertionError("Phase reanchor changed UV span or orientation")
    if u_shift % 16 == 0 and v_shift % 16 == 0:
        raise AssertionError("A phase reanchor must be non-exact; integer-16 cases use the exact path")
    shifts = {"u": geometry_clean_number(u_shift), "v": geometry_clean_number(v_shift)}
    proposed_uv = [geometry_clean_number(value) for value in proposed]
    candidate_id = "uvphase-" + stable_hash(
        {
            "candidate": finding["id"],
            "model": finding["canonicalPath"],
            "element": finding["elementFingerprint"],
            "occurrence": finding["elementOccurrence"],
            "face": finding["face"],
            "expected": finding["uvBefore"],
            "proposed": proposed_uv,
            "shifts": shifts,
        }
    )[:24]
    return {
        "id": candidate_id,
        "operation": "phase_reanchor",
        "status": "review_required",
        "applyEligible": False,
        "nonExact": True,
        "canonicalPath": finding["canonicalPath"],
        "sourceSha256": finding["sourceSha256"],
        "candidateId": finding["id"],
        "jsonPointer": finding["jsonPointer"],
        "elementFingerprint": finding["elementFingerprint"],
        "elementOccurrence": finding["elementOccurrence"],
        "face": finding["face"],
        "texture": finding["texture"],
        "expectedExplicitUv": finding["uvBefore"],
        "proposedUv": proposed_uv,
        "shifts": shifts,
        "spanPreserved": True,
        "orientationPreserved": True,
        "phasePreserved": False,
        "reviewReason": (
            "Review required: this minimum whole-interval translation keeps the rectangle inside 0..16 and preserves "
            "span/orientation, but its non-16 shift intentionally re-anchors texture phase."
        ),
    }


def _analyze_geometry_reanchor(
    *,
    element: dict[str, Any],
    model_path: str,
    source_sha: str,
    element_index: int,
    element_hash: str,
    element_occurrence: int,
    element_findings: Sequence[dict[str, Any]],
    rotation_metadata: dict[str, Any] | None,
    geometry_block_code: str | None,
) -> dict[str, Any] | None:
    """Propose a review-only translation along the rotation axis."""

    derived_findings = [finding for finding in element_findings if finding["uvSource"] == "derived"]
    if (
        not derived_findings
        or rotation_metadata is None
        or rotation_metadata["rescale"]
        or geometry_block_code != "rotation_axis_dependency"
    ):
        return None
    from_vector = numeric_vector(element.get("from"), 3, "element from")
    to_vector = numeric_vector(element.get("to"), 3, "element to")
    axis_index = AXIS_INDEX[rotation_metadata["axis"]]
    try:
        axis_shift = nearest_range_shift(from_vector[axis_index], to_vector[axis_index])
    except ValueError:
        return None
    if abs(axis_shift) <= 1.0e-9:
        return None

    translation = [0.0, 0.0, 0.0]
    translation[axis_index] = axis_shift
    proposed_element = copy.deepcopy(element)
    proposed_element["from"] = [
        geometry_clean_number(from_vector[index] + translation[index]) for index in range(3)
    ]
    proposed_element["to"] = [
        geometry_clean_number(to_vector[index] + translation[index]) for index in range(3)
    ]
    before_corners = rendered_corners(element)
    after_corners = rendered_corners(proposed_element)
    translation_error = max(
        abs((after[index] - before[index]) - translation[index])
        for before, after in zip(before_corners, after_corners)
        for index in range(3)
    )
    if translation_error > MAX_GEOMETRY_DRIFT:
        raise AssertionError(
            f"Rotation-axis reanchor is not a uniform rendered translation: {translation_error:.12g}"
        )
    max_drift = maximum_corner_drift(element, proposed_element)

    findings_by_face = {finding["face"]: finding for finding in derived_findings}
    checked_faces: list[dict[str, Any]] = []
    remaining: list[str] = []
    for face in sorted(element.get("faces", {})):
        face_data = element["faces"][face]
        if not isinstance(face_data, dict) or "uv" in face_data:
            continue
        before = classify_uv_rect(default_face_uv(element, face))
        after = classify_uv_rect(default_face_uv(proposed_element, face))
        checked_faces.append(
            {
                "face": face,
                "texture": face_data.get("texture"),
                "findingId": findings_by_face.get(face, {}).get("id"),
                "classificationBefore": before.classification,
                "derivedUvBefore": _report_number_list(before.before),
                "derivedUvAfter": _report_number_list(after.before),
                "inRangeAfter": after.classification == "in_range",
                "remainsUvFree": True,
            }
        )
        if after.classification != "in_range":
            remaining.append(f"{face}:{after.classification}")
    if remaining:
        return None
    repaired_faces = [
        row for row in checked_faces if row["classificationBefore"] != "in_range"
    ]
    repaired_ids = {row["findingId"] for row in repaired_faces}
    expected_ids = {finding["id"] for finding in derived_findings}
    if repaired_ids != expected_ids:
        return None

    expected_geometry = {
        "from": _report_number_list(from_vector),
        "to": _report_number_list(to_vector),
        "rotation": copy.deepcopy(element["rotation"]),
    }
    proposed_geometry = {
        "from": copy.deepcopy(proposed_element["from"]),
        "to": copy.deepcopy(proposed_element["to"]),
        "rotation": copy.deepcopy(proposed_element["rotation"]),
    }
    candidate_id = "uvgreanchor-" + stable_hash(
        {
            "model": model_path,
            "element": element_hash,
            "occurrence": element_occurrence,
            "expected": expected_geometry,
            "proposed": proposed_geometry,
            "repairedFindingIds": sorted(expected_ids),
        }
    )[:24]
    return {
        "id": candidate_id,
        "operation": "geometry_reanchor",
        "status": "review_required",
        "applyEligible": False,
        "canonicalPath": model_path,
        "sourceSha256": source_sha,
        "jsonPointer": f"/elements/{element_index}",
        "elementIndex": element_index,
        "elementFingerprint": element_hash,
        "elementOccurrence": element_occurrence,
        "expectedGeometry": expected_geometry,
        "proposedGeometry": proposed_geometry,
        "rotationAxis": rotation_metadata["axis"],
        "rotationAngle": clean_number(rotation_metadata["angle"]),
        "axisShift": geometry_clean_number(axis_shift),
        "renderedTranslation": _geometry_number_list(translation),
        "maxRenderedPointDrift": geometry_clean_number(max_drift),
        "translationProofError": geometry_clean_number(translation_error),
        "geometryPreserved": False,
        "geometryReanchored": True,
        "facesRemainUvFree": True,
        "derivedFacesChecked": checked_faces,
        "repairedDerivedFaces": repaired_faces,
        "repairedDerivedFaceCount": len(repaired_faces),
        "reviewReason": (
            "Review required: shifting along the rotation axis brings implicit UVs into 0..16 and keeps faces UV-free, "
            "but the pivot cannot compensate this component, so rendered geometry is intentionally translated."
        ),
    }


def _validation_failure(
    *, file_path: str, source_sha: str, element_index: int | None, face: str | None, message: str
) -> dict[str, Any]:
    return {
        "file": file_path,
        "canonicalPath": file_path,
        "sourceSha256": source_sha,
        "elementIndex": element_index,
        "face": face,
        "message": message,
    }


def audit_models(
    inputs: Iterable[Path],
    max_files: int | None = None,
    *,
    canonical_root: Path = DEFAULT_SOURCE_ROOT,
) -> dict[str, Any]:
    """Recursively audit standard model inputs against one canonical root."""

    input_paths = [Path(path) for path in inputs]
    if not input_paths:
        raise ModelAuditError("At least one model file or directory is required")
    if max_files is not None and max_files <= 0:
        raise ModelAuditError("--max-files must be positive")

    root = canonical_models_root(canonical_root)
    all_discovered = discover_json_files(input_paths)
    discovered = all_discovered[:max_files] if max_files is not None else all_discovered
    files: list[tuple[Path, str]] = [safe_canonical_model_file(path, root) for path in discovered]

    summary = _new_summary()
    summary["filesDiscovered"] = len(all_discovered)
    summary["filesScanned"] = len(files)
    findings: list[dict[str, Any]] = []
    geometry_candidates: list[dict[str, Any]] = []
    phase_reanchor_candidates: list[dict[str, Any]] = []
    geometry_reanchor_candidates: list[dict[str, Any]] = []
    parse_failures: list[dict[str, Any]] = []
    validation_failures: list[dict[str, Any]] = []
    file_summaries: list[dict[str, Any]] = []
    manifest_rows: list[tuple[str, str]] = []

    for model_file, model_path in files:
        source_bytes = model_file.read_bytes()
        source_sha = sha256_bytes(source_bytes)
        manifest_rows.append((model_path, source_sha))
        try:
            model = json.loads(source_bytes.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            parse_failures.append(
                {"file": model_path, "canonicalPath": model_path, "sourceSha256": source_sha, "message": str(exception)}
            )
            continue

        if not isinstance(model, dict):
            validation_failures.append(
                _validation_failure(
                    file_path=model_path,
                    source_sha=source_sha,
                    element_index=None,
                    face=None,
                    message="Model root must be a JSON object",
                )
            )
            continue

        elements = model.get("elements")
        if elements is None:
            summary["modelsWithoutElements"] += 1
            continue
        if not isinstance(elements, list):
            validation_failures.append(
                _validation_failure(
                    file_path=model_path,
                    source_sha=source_sha,
                    element_index=None,
                    face=None,
                    message="Model elements must be an array",
                )
            )
            continue

        if elements:
            summary["geometryModels"] += 1
        else:
            summary["modelsWithEmptyElements"] += 1
        summary["elements"] += len(elements)
        file_face_count = 0
        file_finding_count = 0
        element_occurrences: Counter[str] = Counter()

        for element_index, element in enumerate(elements):
            if not isinstance(element, dict):
                validation_failures.append(
                    _validation_failure(
                        file_path=model_path,
                        source_sha=source_sha,
                        element_index=element_index,
                        face=None,
                        message="Element must be a JSON object",
                    )
                )
                continue

            element_hash = element_fingerprint(element)
            occurrence = element_occurrences[element_hash]
            element_occurrences[element_hash] += 1
            rotated, rotation_metadata, rotation_review_reason = _rotation_state(element)
            element_finding_start = len(findings)
            faces = element.get("faces", {})
            if not isinstance(faces, dict):
                validation_failures.append(
                    _validation_failure(
                        file_path=model_path,
                        source_sha=source_sha,
                        element_index=element_index,
                        face=None,
                        message="Element faces must be an object",
                    )
                )
                continue

            for face, face_data in faces.items():
                summary["faces"] += 1
                file_face_count += 1
                if face not in FACE_DIRECTIONS or not isinstance(face_data, dict):
                    validation_failures.append(
                        _validation_failure(
                            file_path=model_path,
                            source_sha=source_sha,
                            element_index=element_index,
                            face=str(face),
                            message="Face direction or payload is invalid",
                        )
                    )
                    continue

                uv_source = "explicit" if "uv" in face_data else "derived"
                try:
                    uv_before = face_data["uv"] if uv_source == "explicit" else default_face_uv(element, face)
                    analysis = classify_uv_rect(uv_before)
                except ModelAuditError as exception:
                    validation_failures.append(
                        _validation_failure(
                            file_path=model_path,
                            source_sha=source_sha,
                            element_index=element_index,
                            face=face,
                            message=str(exception),
                        )
                    )
                    continue

                if analysis.classification == "in_range":
                    summary["facesInRange"] += 1
                    continue

                if uv_source == "explicit":
                    summary["explicitOutOfRangeFaces"] += 1
                else:
                    summary["derivedOutOfRangeFaces"] += 1

                if analysis.classification == "integer_16_shift_candidate":
                    status = "arithmetic_candidate"
                    summary["integer16ArithmeticCandidates"] += 1
                    if uv_source == "explicit":
                        summary["explicitApplyEligibleCandidates"] += 1
                    else:
                        summary["derivedReviewCandidates"] += 1
                elif analysis.classification == "blocked_boundary_crossing":
                    status = "blocked"
                    summary["blockedBoundaryCrossing"] += 1
                elif analysis.classification == "blocked_span_over_16":
                    status = "blocked"
                    summary["blockedSpanOver16"] += 1
                else:
                    raise AssertionError(f"Unexpected UV analysis: {analysis.classification}")

                candidate_id = stable_candidate_id(
                    model_path=model_path,
                    element_hash=element_hash,
                    element_occurrence=occurrence,
                    face=face,
                    uv_source=uv_source,
                    uv_before=analysis.before,
                    face_data=face_data,
                )
                apply_eligible = analysis.classification == "integer_16_shift_candidate" and uv_source == "explicit"
                finding = {
                    "id": candidate_id,
                    "status": status,
                    "classification": analysis.classification,
                    "file": model_path,
                    "canonicalPath": model_path,
                    "modelKind": _model_kind(model_path),
                    "iconOrItem": _model_kind(model_path) == "item" or "icon" in model_file.stem.lower(),
                    "sourceSha256": source_sha,
                    "jsonPointer": _face_uv_pointer(element_index, face),
                    "elementIndex": element_index,
                    "elementFingerprint": element_hash,
                    "elementOccurrence": occurrence,
                    "face": face,
                    "texture": face_data.get("texture"),
                    "textureResolution": "not_performed",
                    "uvSource": uv_source,
                    "uvBefore": _report_number_list(analysis.before),
                    "proposedUv": _report_number_list(analysis.proposed),
                    "integerShift": {
                        "u": clean_number(analysis.u.shift) if analysis.u.shift is not None else None,
                        "v": clean_number(analysis.v.shift) if analysis.v.shift is not None else None,
                    },
                    "span": {"u": clean_number(analysis.u.span), "v": clean_number(analysis.v.span)},
                    "axisClassification": {
                        "u": analysis.u.classification,
                        "v": analysis.v.classification,
                    },
                    "rotatedElement": rotated,
                    "elementRotation": copy.deepcopy(element.get("rotation")),
                    "rotationReviewReason": rotation_review_reason,
                    "applyEligible": apply_eligible,
                    "reviewReason": _review_reason(analysis.classification, uv_source),
                    "reason": _finding_reason(analysis.classification, uv_source),
                }
                findings.append(finding)
                file_finding_count += 1

            element_findings = findings[element_finding_start:]
            geometry_candidate, geometry_block_code, geometry_block_reason = _analyze_geometry_origin_shift(
                element=element,
                model_path=model_path,
                source_sha=source_sha,
                element_index=element_index,
                element_hash=element_hash,
                element_occurrence=occurrence,
                element_findings=element_findings,
                rotated=rotated,
                rotation_metadata=rotation_metadata,
                rotation_review_reason=rotation_review_reason,
            )
            if geometry_candidate is not None:
                geometry_candidates.append(geometry_candidate)
            geometry_reanchor = _analyze_geometry_reanchor(
                element=element,
                model_path=model_path,
                source_sha=source_sha,
                element_index=element_index,
                element_hash=element_hash,
                element_occurrence=occurrence,
                element_findings=element_findings,
                rotation_metadata=rotation_metadata,
                geometry_block_code=geometry_block_code,
            )
            if geometry_reanchor is not None:
                geometry_reanchor_candidates.append(geometry_reanchor)
            phase_reanchors_by_finding: dict[str, dict[str, Any]] = {}
            for finding in element_findings:
                phase_reanchor = _analyze_phase_reanchor(finding)
                if phase_reanchor is not None:
                    phase_reanchor_candidates.append(phase_reanchor)
                    phase_reanchors_by_finding[finding["id"]] = phase_reanchor
            geometry_repaired_ids = {
                row["findingId"] for row in (geometry_candidate or {}).get("repairedDerivedFaces", [])
            }
            geometry_reanchor_ids = {
                row["findingId"] for row in (geometry_reanchor or {}).get("repairedDerivedFaces", [])
            }
            for finding in element_findings:
                explicit_bulk_candidate = (
                    finding["rotatedElement"]
                    and finding["uvSource"] == "explicit"
                    and finding["classification"] == "integer_16_shift_candidate"
                )
                finding["bulkPlanEligible"] = explicit_bulk_candidate
                finding["geometryCandidateId"] = None
                finding["geometryBlockCode"] = None
                finding["geometryReviewReason"] = None
                finding["reviewCandidateId"] = None
                finding["reviewCandidateKind"] = None
                if explicit_bulk_candidate:
                    finding["bulkCorrectionKind"] = "explicit_uv_token_shift"
                elif finding["uvSource"] == "derived" and finding["id"] in geometry_repaired_ids:
                    finding["bulkCorrectionKind"] = "geometry_origin_shift"
                    finding["geometryCandidateId"] = geometry_candidate["id"]
                    finding["geometryReviewReason"] = geometry_candidate["reviewReason"]
                elif finding["id"] in phase_reanchors_by_finding:
                    finding["bulkCorrectionKind"] = "phase_reanchor_review_required"
                    finding["reviewCandidateId"] = phase_reanchors_by_finding[finding["id"]]["id"]
                    finding["reviewCandidateKind"] = "phase_reanchor"
                    finding["geometryReviewReason"] = phase_reanchors_by_finding[finding["id"]]["reviewReason"]
                elif finding["uvSource"] == "derived" and finding["id"] in geometry_reanchor_ids:
                    finding["bulkCorrectionKind"] = "geometry_reanchor_review_required"
                    finding["reviewCandidateId"] = geometry_reanchor["id"]
                    finding["reviewCandidateKind"] = "geometry_reanchor"
                    finding["geometryBlockCode"] = geometry_block_code
                    finding["geometryReviewReason"] = geometry_reanchor["reviewReason"]
                else:
                    finding["bulkCorrectionKind"] = "report_only"
                    if finding["uvSource"] == "derived":
                        finding["geometryBlockCode"] = geometry_block_code
                        finding["geometryReviewReason"] = geometry_block_reason
                    elif not finding["rotatedElement"]:
                        finding["geometryReviewReason"] = (
                            "Unrotated elements are excluded from the rotated bulk correction plan."
                        )
                    else:
                        finding["geometryReviewReason"] = (
                            "Explicit seam-crossing or span-over-16 UVs cannot use an exact whole-sprite token shift."
                        )

        file_summaries.append(
            {
                "file": model_path,
                "canonicalPath": model_path,
                "sourceSha256": source_sha,
                "elements": len(elements),
                "faces": file_face_count,
                "findings": file_finding_count,
            }
        )

    summary["parseFailures"] = len(parse_failures)
    summary["validationFailures"] = len(validation_failures)
    rotated_explicit_files: set[str] = set()
    rotated_geometry_files: set[str] = set()
    phase_reanchor_files: set[str] = set()
    geometry_reanchor_files: set[str] = set()
    for finding in findings:
        if not finding["rotatedElement"]:
            continue
        summary["rotatedOutOfRangeFaces"] += 1
        if finding["uvSource"] == "explicit":
            summary["rotatedExplicitOutOfRangeFaces"] += 1
        else:
            summary["rotatedDerivedOutOfRangeFaces"] += 1
        if finding["bulkCorrectionKind"] == "explicit_uv_token_shift":
            summary["rotatedExplicitTokenShiftCandidates"] += 1
            rotated_explicit_files.add(finding["canonicalPath"])
        elif finding["bulkCorrectionKind"] == "geometry_origin_shift":
            summary["rotatedDerivedGeometryShiftFaces"] += 1
            rotated_geometry_files.add(finding["canonicalPath"])
        else:
            summary["rotatedReportOnlyFaces"] += 1
            if finding["uvSource"] == "explicit":
                summary["rotatedExplicitReportOnlyFaces"] += 1
            else:
                summary["rotatedDerivedReportOnlyFaces"] += 1
            if finding["bulkCorrectionKind"] == "phase_reanchor_review_required":
                summary["reviewPhaseReanchorFaces"] += 1
                phase_reanchor_files.add(finding["canonicalPath"])
            elif finding["bulkCorrectionKind"] == "geometry_reanchor_review_required":
                summary["reviewGeometryReanchorFaces"] += 1
                geometry_reanchor_files.add(finding["canonicalPath"])
            else:
                summary["rotatedUnresolvedReportOnlyFaces"] += 1
    summary["rotatedExplicitTokenShiftFiles"] = len(rotated_explicit_files)
    summary["rotatedDerivedGeometryShiftElements"] = len(geometry_candidates)
    summary["rotatedDerivedGeometryShiftFiles"] = len(rotated_geometry_files)
    summary["reviewPhaseReanchorFiles"] = len(phase_reanchor_files)
    summary["reviewGeometryReanchorElements"] = len(geometry_reanchor_candidates)
    summary["reviewGeometryReanchorFiles"] = len(geometry_reanchor_files)
    findings.sort(key=lambda row: (row["canonicalPath"], row["elementIndex"], row["face"], row["id"]))
    geometry_candidates.sort(
        key=lambda row: (row["canonicalPath"], row["elementIndex"], row["id"])
    )
    phase_reanchor_candidates.sort(
        key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["id"])
    )
    geometry_reanchor_candidates.sort(
        key=lambda row: (row["canonicalPath"], row["elementIndex"], row["id"])
    )
    file_summaries.sort(key=lambda row: row["canonicalPath"])

    return {
        "schemaVersion": SCHEMA_VERSION,
        "mode": "audit",
        "sourceWrites": False,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "objective": "Inventory standard Java-model UVs that leave the current 0..16 atlas sprite",
        "policy": {
            "arithmeticCandidate": (
                "An exact integer-multiple-of-16 translation is reported as an arithmetic candidate only; "
                "it is not described as CTM-safe."
            ),
            "applyEligibility": (
                "Only already-explicit arithmetic candidates may enter a complete SHA-locked apply plan. "
                "Derived candidates remain review-only because adding explicit UVs may affect CTM/world alignment."
            ),
            "blockedBoundaryCrossing": "Boundary-crossing mappings need split geometry or deliberate custom handling.",
            "blockedSpanOver16": "Mappings wider than one sprite cannot be represented inside one atlas sprite by one face.",
            "textureAndParentResolution": (
                "Not performed. Face texture tokens, parent models, material wrappers, and inherited textures are unresolved; "
                "the audit makes no CTM-safety claim."
            ),
            "rawAuthoringModels": (
                "Excluded. assets/erydon/authoring_models uses nested Blockbench group rotations and requires a separate audit."
            ),
            "duplicateElementOccurrence": (
                "Stable IDs use a geometry fingerprint plus an occurrence ordinal for exact duplicate elements. "
                "Reordering otherwise indistinguishable duplicate elements can change that ordinal; source SHA and JSON pointer "
                "locks still prevent applying a stale plan."
            ),
            "rotatedBulkCorrection": (
                "Rotated explicit arithmetic candidates use UV-token translations only. Rotated implicit faces may instead "
                "receive a report-only pivot-compensated from/to/origin proposal that proves transformed-corner equivalence "
                "and leaves every face UV-free. Unrotated, seam-crossing explicit, span-over-16, rescale=true, and "
                "rotation-axis-dependent cases remain report-only."
            ),
            "reviewedReanchors": (
                "When explicitly requested during plan generation, non-exact seam cases receive span-preserving phase "
                "reanchor proposals and rotation-axis-dependent implicit cases receive element-level geometry reanchor "
                "proposals with their exact rendered translation disclosed. They are writable only through the explicitly "
                "flagged complete SHA-locked plan after every proof is regenerated."
            ),
        },
        "sourceRoots": [str(path.resolve()) for path in input_paths],
        "canonicalModelsRoot": str(root),
        "scanLimitedToFiles": max_files,
        "scanManifestSha256": scan_manifest_sha256(manifest_rows),
        "summary": summary,
        "fileSummaries": file_summaries,
        "findings": findings,
        "geometryOriginShiftCandidates": geometry_candidates,
        "phaseReanchorCandidates": phase_reanchor_candidates,
        "geometryReanchorCandidates": geometry_reanchor_candidates,
        "parseFailures": parse_failures,
        "validationFailures": validation_failures,
    }


CSV_FIELDS = (
    "id",
    "status",
    "classification",
    "applyEligible",
    "bulkPlanEligible",
    "bulkCorrectionKind",
    "reviewReason",
    "geometryCandidateId",
    "geometryBlockCode",
    "geometryReviewReason",
    "reviewCandidateId",
    "reviewCandidateKind",
    "canonicalPath",
    "sourceSha256",
    "jsonPointer",
    "modelKind",
    "iconOrItem",
    "elementIndex",
    "elementFingerprint",
    "elementOccurrence",
    "rotatedElement",
    "elementRotation",
    "rotationReviewReason",
    "face",
    "texture",
    "textureResolution",
    "uvSource",
    "uvBefore",
    "proposedUv",
    "uShift",
    "vShift",
    "uSpan",
    "vSpan",
    "uClassification",
    "vClassification",
    "reason",
)


def render_csv(report: dict[str, Any]) -> str:
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    for finding in report["findings"]:
        writer.writerow(
            {
                "id": finding["id"],
                "status": finding["status"],
                "classification": finding["classification"],
                "applyEligible": str(finding["applyEligible"]).lower(),
                "bulkPlanEligible": str(finding["bulkPlanEligible"]).lower(),
                "bulkCorrectionKind": finding["bulkCorrectionKind"],
                "reviewReason": finding["reviewReason"],
                "geometryCandidateId": finding["geometryCandidateId"],
                "geometryBlockCode": finding["geometryBlockCode"],
                "geometryReviewReason": finding["geometryReviewReason"],
                "reviewCandidateId": finding["reviewCandidateId"],
                "reviewCandidateKind": finding["reviewCandidateKind"],
                "canonicalPath": finding["canonicalPath"],
                "sourceSha256": finding["sourceSha256"],
                "jsonPointer": finding["jsonPointer"],
                "modelKind": finding["modelKind"],
                "iconOrItem": str(finding["iconOrItem"]).lower(),
                "elementIndex": finding["elementIndex"],
                "elementFingerprint": finding["elementFingerprint"],
                "elementOccurrence": finding["elementOccurrence"],
                "rotatedElement": str(finding["rotatedElement"]).lower(),
                "elementRotation": json.dumps(finding["elementRotation"], separators=(",", ":")),
                "rotationReviewReason": finding["rotationReviewReason"],
                "face": finding["face"],
                "texture": finding["texture"],
                "textureResolution": finding["textureResolution"],
                "uvSource": finding["uvSource"],
                "uvBefore": json.dumps(finding["uvBefore"], separators=(",", ":")),
                "proposedUv": json.dumps(finding["proposedUv"], separators=(",", ":")),
                "uShift": finding["integerShift"]["u"],
                "vShift": finding["integerShift"]["v"],
                "uSpan": finding["span"]["u"],
                "vSpan": finding["span"]["v"],
                "uClassification": finding["axisClassification"]["u"],
                "vClassification": finding["axisClassification"]["v"],
                "reason": finding["reason"],
            }
        )
    return output.getvalue()


def write_reports(
    report: dict[str, Any], json_report: Path, csv_report: Path, source_inputs: Iterable[Path]
) -> None:
    ensure_reports_outside_sources((json_report, csv_report), source_inputs)
    atomic_write_text(json_report, json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    atomic_write_text(csv_report, render_csv(report))


def plan_entry_from_finding(finding: dict[str, Any]) -> dict[str, Any]:
    """Create the exact apply-plan entry for one explicit eligible finding."""

    if not finding.get("applyEligible") or finding.get("uvSource") != "explicit":
        raise ModelAuditError("Only explicit apply-eligible findings can be placed in an apply plan")
    return {
        "canonicalPath": finding["canonicalPath"],
        "sourceSha256": finding["sourceSha256"],
        "candidateId": finding["id"],
        "jsonPointer": finding["jsonPointer"],
        "elementFingerprint": finding["elementFingerprint"],
        "elementOccurrence": finding["elementOccurrence"],
        "face": finding["face"],
        "texture": finding["texture"],
        "expectedExplicitUv": finding["uvBefore"],
        "proposedUv": finding["proposedUv"],
        "shifts": finding["integerShift"],
    }


def _report_only_plan_entry(finding: dict[str, Any]) -> dict[str, Any]:
    return {
        "canonicalPath": finding["canonicalPath"],
        "sourceSha256": finding["sourceSha256"],
        "candidateId": finding["id"],
        "jsonPointer": finding["jsonPointer"],
        "elementFingerprint": finding["elementFingerprint"],
        "elementOccurrence": finding["elementOccurrence"],
        "face": finding["face"],
        "texture": finding["texture"],
        "uvSource": finding["uvSource"],
        "uvBefore": finding["uvBefore"],
        "classification": finding["classification"],
        "geometryBlockCode": finding["geometryBlockCode"],
        "reviewCandidateId": finding["reviewCandidateId"],
        "reviewCandidateKind": finding["reviewCandidateKind"],
        "reviewReason": finding["geometryReviewReason"] or finding["reviewReason"] or finding["reason"],
    }


def generate_rotated_bulk_plan(
    report: dict[str, Any], *, include_reviewed_reanchors: bool = False
) -> dict[str, Any]:
    """Build a byte-deterministic, full-root locked rotated-UV plan.

    ``candidates`` contains only exact integer-16 explicit UV-token shifts.
    Geometry-origin and reviewed reanchor operations are separately locked;
    the apply engine accepts them only in a complete flagged plan.
    """

    if report.get("mode") != "audit" or report.get("sourceWrites") is not False:
        raise ModelAuditError("Rotated bulk plans require a read-only UV audit report")
    summary = report.get("summary")
    if not isinstance(summary, dict):
        raise ModelAuditError("UV audit report has no summary")
    if summary.get("parseFailures") or summary.get("validationFailures"):
        raise ModelAuditError("Rotated bulk plans require an audit with no parse or validation failures")
    if report.get("scanLimitedToFiles") is not None:
        raise ModelAuditError("Rotated bulk plans require an unlimited canonical-root audit")
    canonical_root = report.get("canonicalModelsRoot")
    source_roots = report.get("sourceRoots")
    if not isinstance(canonical_root, str) or not isinstance(source_roots, list) or len(source_roots) != 1:
        raise ModelAuditError("Rotated bulk plans require exactly one canonical source root")
    if Path(source_roots[0]).resolve() != Path(canonical_root).resolve():
        raise ModelAuditError("Rotated bulk plan source root must equal the canonical models root")
    if summary.get("filesScanned") != summary.get("filesDiscovered"):
        raise ModelAuditError("Rotated bulk plans require every discovered canonical model file")

    findings = report.get("findings")
    geometry_candidates = report.get("geometryOriginShiftCandidates")
    phase_reanchor_candidates = report.get("phaseReanchorCandidates")
    geometry_reanchor_candidates = report.get("geometryReanchorCandidates")
    if not all(
        isinstance(value, list)
        for value in (
            findings,
            geometry_candidates,
            phase_reanchor_candidates,
            geometry_reanchor_candidates,
        )
    ):
        raise ModelAuditError("UV audit report lacks rotated correction inventories")
    rotated_findings = [finding for finding in findings if finding.get("rotatedElement")]
    explicit_findings = [finding for finding in rotated_findings if finding.get("bulkPlanEligible")]
    explicit_entries = [plan_entry_from_finding(finding) for finding in explicit_findings]
    explicit_entries.sort(
        key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["candidateId"])
    )

    locked_geometry = copy.deepcopy(geometry_candidates)
    locked_geometry.sort(key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["id"]))
    geometry_finding_ids = {
        face["findingId"]
        for candidate in locked_geometry
        for face in candidate["repairedDerivedFaces"]
    }
    explicit_finding_ids = {finding["id"] for finding in explicit_findings}
    if explicit_finding_ids & geometry_finding_ids:
        raise ModelAuditError("A rotated face cannot use both UV-token and geometry-origin correction")
    rotated_ids = {finding["id"] for finding in rotated_findings}
    if not geometry_finding_ids <= rotated_ids:
        raise ModelAuditError("Geometry proposal references a face outside the rotated audit inventory")

    locked_phase_reanchors = (
        sorted(
            copy.deepcopy(phase_reanchor_candidates),
            key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["id"]),
        )
        if include_reviewed_reanchors
        else []
    )
    locked_geometry_reanchors = (
        sorted(
            copy.deepcopy(geometry_reanchor_candidates),
            key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["id"]),
        )
        if include_reviewed_reanchors
        else []
    )
    phase_reanchor_finding_ids = {
        candidate["candidateId"] for candidate in locked_phase_reanchors
    }
    geometry_reanchor_finding_ids = {
        face["findingId"]
        for candidate in locked_geometry_reanchors
        for face in candidate["repairedDerivedFaces"]
    }
    reviewed_finding_ids = phase_reanchor_finding_ids | geometry_reanchor_finding_ids
    if reviewed_finding_ids & (explicit_finding_ids | geometry_finding_ids):
        raise ModelAuditError("A rotated face cannot use both an exact correction and a reviewed reanchor")
    if not reviewed_finding_ids <= rotated_ids:
        raise ModelAuditError("Reviewed reanchor references a face outside the rotated audit inventory")

    report_only_findings = [
        finding
        for finding in rotated_findings
        if finding["id"] not in explicit_finding_ids | geometry_finding_ids | reviewed_finding_ids
    ]
    report_only_entries = [_report_only_plan_entry(finding) for finding in report_only_findings]
    report_only_entries.sort(
        key=lambda row: (row["canonicalPath"], row["jsonPointer"], row["candidateId"])
    )
    covered_ids = explicit_finding_ids | geometry_finding_ids | reviewed_finding_ids | {
        finding["id"] for finding in report_only_findings
    }
    if covered_ids != rotated_ids:
        raise ModelAuditError("Rotated bulk plan does not cover every rotated out-of-range face exactly once")

    source_sha_by_file: dict[str, str] = {}
    file_explicit_ids: dict[str, list[str]] = defaultdict(list)
    file_geometry_ids: dict[str, list[str]] = defaultdict(list)
    file_phase_reanchor_ids: dict[str, list[str]] = defaultdict(list)
    file_geometry_reanchor_ids: dict[str, list[str]] = defaultdict(list)
    file_report_only_ids: dict[str, list[str]] = defaultdict(list)
    for finding in rotated_findings:
        path = finding["canonicalPath"]
        existing_sha = source_sha_by_file.setdefault(path, finding["sourceSha256"])
        if existing_sha != finding["sourceSha256"]:
            raise ModelAuditError(f"Audit uses conflicting source SHA values for {path}")
    for entry in explicit_entries:
        file_explicit_ids[entry["canonicalPath"]].append(entry["candidateId"])
    for candidate in locked_geometry:
        path = candidate["canonicalPath"]
        existing_sha = source_sha_by_file.setdefault(path, candidate["sourceSha256"])
        if existing_sha != candidate["sourceSha256"]:
            raise ModelAuditError(f"Audit uses conflicting source SHA values for {path}")
        file_geometry_ids[path].append(candidate["id"])
    for candidate in locked_phase_reanchors:
        file_phase_reanchor_ids[candidate["canonicalPath"]].append(candidate["id"])
    for candidate in locked_geometry_reanchors:
        file_geometry_reanchor_ids[candidate["canonicalPath"]].append(candidate["id"])
    for entry in report_only_entries:
        file_report_only_ids[entry["canonicalPath"]].append(entry["candidateId"])

    file_locks = [
        {
            "canonicalPath": path,
            "sourceSha256": source_sha_by_file[path],
            "explicitUvCandidateIds": sorted(file_explicit_ids[path]),
            "geometryOriginCandidateIds": sorted(file_geometry_ids[path]),
            "phaseReanchorCandidateIds": sorted(file_phase_reanchor_ids[path]),
            "geometryReanchorCandidateIds": sorted(file_geometry_reanchor_ids[path]),
            "reportOnlyFindingIds": sorted(file_report_only_ids[path]),
        }
        for path in sorted(source_sha_by_file)
    ]
    geometry_face_count = sum(
        candidate["repairedDerivedFaceCount"] for candidate in locked_geometry
    )
    geometry_reanchor_face_count = sum(
        candidate["repairedDerivedFaceCount"] for candidate in locked_geometry_reanchors
    )
    counts = {
        "rotatedOutOfRangeFaces": len(rotated_findings),
        "explicitUvTokenShiftFaces": len(explicit_entries),
        "geometryOriginShiftElements": len(locked_geometry),
        "geometryOriginShiftFaces": geometry_face_count,
        "phaseReanchorFaces": len(locked_phase_reanchors),
        "geometryReanchorElements": len(locked_geometry_reanchors),
        "geometryReanchorFaces": geometry_reanchor_face_count,
        "reportOnlyFaces": len(report_only_entries),
        "lockedFiles": len(file_locks),
    }
    if (
        counts["explicitUvTokenShiftFaces"]
        + counts["geometryOriginShiftFaces"]
        + counts["phaseReanchorFaces"]
        + counts["geometryReanchorFaces"]
        + counts["reportOnlyFaces"]
        != counts["rotatedOutOfRangeFaces"]
    ):
        raise ModelAuditError("Rotated bulk plan operation counts do not cover the rotated face inventory")

    return {
        "schemaVersion": APPLY_PLAN_SCHEMA_VERSION,
        "selectionPolicy": ROTATED_BULK_SELECTION_POLICY,
        "description": (
            "All rotated standard Java-model UV defects: exact integer-16 explicit token shifts, "
            "pivot-equivalent UV-free geometry proposals, and locked report-only blockers."
        ),
        "includeReviewedReanchors": include_reviewed_reanchors,
        "scanScope": {
            "kind": "canonical_models_root",
            "scanManifestSha256": report["scanManifestSha256"],
            "filesDiscovered": summary["filesDiscovered"],
            "filesScanned": summary["filesScanned"],
        },
        "operationCounts": counts,
        "expectedCandidateCount": len(explicit_entries),
        "expectedFileCount": len(file_locks),
        "files": file_locks,
        "candidates": explicit_entries,
        "geometryOriginShifts": locked_geometry,
        "phaseReanchors": locked_phase_reanchors,
        "geometryReanchors": locked_geometry_reanchors,
        "reportOnlyFaces": report_only_entries,
    }


def render_rotated_bulk_plan(plan: dict[str, Any]) -> str:
    return json.dumps(plan, indent=2, ensure_ascii=False) + "\n"


PLAN_ENTRY_FIELDS = {
    "canonicalPath",
    "sourceSha256",
    "candidateId",
    "jsonPointer",
    "elementFingerprint",
    "elementOccurrence",
    "face",
    "texture",
    "expectedExplicitUv",
    "proposedUv",
    "shifts",
}


def _valid_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise ModelAuditError(f"{label} must be a 64-digit SHA-256 value")
    return value.lower()


def _validate_plan_entry(entry: Any, index: int) -> dict[str, Any]:
    if not isinstance(entry, dict):
        raise ModelAuditError(f"Plan candidate {index} must be an object")
    missing = sorted(PLAN_ENTRY_FIELDS - set(entry))
    if missing:
        raise ModelAuditError(f"Plan candidate {index} is incomplete; missing: {', '.join(missing)}")
    if not isinstance(entry["candidateId"], str) or not entry["candidateId"]:
        raise ModelAuditError(f"Plan candidate {index} candidateId must be a non-empty string")
    if not isinstance(entry["jsonPointer"], str) or not entry["jsonPointer"].startswith("/elements/"):
        raise ModelAuditError(f"Plan candidate {index} jsonPointer is invalid")
    _valid_sha256(entry["sourceSha256"], f"Plan candidate {index} sourceSha256")
    _valid_sha256(entry["elementFingerprint"], f"Plan candidate {index} elementFingerprint")
    if isinstance(entry["elementOccurrence"], bool) or not isinstance(entry["elementOccurrence"], int) or entry["elementOccurrence"] < 0:
        raise ModelAuditError(f"Plan candidate {index} elementOccurrence must be a non-negative integer")
    if entry["face"] not in FACE_DIRECTIONS:
        raise ModelAuditError(f"Plan candidate {index} face is invalid")
    if entry["texture"] is not None and not isinstance(entry["texture"], str):
        raise ModelAuditError(f"Plan candidate {index} texture must be a string or null")
    expected = list(numeric_vector(entry["expectedExplicitUv"], 4, "expectedExplicitUv"))
    proposed = list(numeric_vector(entry["proposedUv"], 4, "proposedUv"))
    if not isinstance(entry["shifts"], dict) or set(entry["shifts"]) != {"u", "v"}:
        raise ModelAuditError(f"Plan candidate {index} shifts must contain exactly u and v")
    u_shift = numeric_vector([entry["shifts"]["u"]], 1, "shifts.u")[0]
    v_shift = numeric_vector([entry["shifts"]["v"]], 1, "shifts.v")[0]
    if u_shift % 16 != 0 or v_shift % 16 != 0:
        raise ModelAuditError(f"Plan candidate {index} shifts must be integer multiples of 16")
    normalized = dict(entry)
    normalized["sourceSha256"] = entry["sourceSha256"].lower()
    normalized["elementFingerprint"] = entry["elementFingerprint"].lower()
    normalized["expectedExplicitUv"] = _report_number_list(expected)
    normalized["proposedUv"] = _report_number_list(proposed)
    normalized["shifts"] = {"u": clean_number(u_shift), "v": clean_number(v_shift)}
    return normalized


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
            child = pointer + "/" + _pointer_escape(key)
            if key in keys:
                self.duplicate_pointers.add(child)
            keys.add(key)
            self._skip_whitespace()
            if not self._consume(":"):
                raise ModelAuditError(f"Expected ':' at character {self.position}")
            self._parse_value(child)
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
                raise ModelAuditError(f"JSON pointer list index is invalid: {pointer}")
            current = current[int(token)]
        elif isinstance(current, dict) and token in current:
            current = current[token]
        else:
            raise ModelAuditError(f"JSON pointer does not exist: {pointer}")
    return current


def _pointer_set(document: Any, pointer: str, value: Any) -> None:
    tokens = _pointer_tokens(pointer)
    if not tokens:
        raise ModelAuditError("Replacing the model root is not allowed")
    parent = document
    for token in tokens[:-1]:
        if isinstance(parent, list):
            parent = parent[int(token)]
        else:
            parent = parent[token]
    final = tokens[-1]
    if isinstance(parent, list):
        parent[int(final)] = value
    else:
        parent[final] = value


def _prepare_json_token_replacements(
    source_bytes: bytes, specs: Sequence[dict[str, Any]]
) -> tuple[bytes, list[dict[str, Any]]]:
    """Replace only pre-locked JSON value tokens and prove deep equivalence."""

    bom = b"\xef\xbb\xbf" if source_bytes.startswith(b"\xef\xbb\xbf") else b""
    body = source_bytes[len(bom) :]
    try:
        text = body.decode("utf-8")
        before_document = json.loads(source_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ModelAuditError(f"Source model cannot be decoded for token replacement: {exception}") from exception

    locator = JsonSpanLocator(text)
    spans = locator.locate()
    expected_document = copy.deepcopy(before_document)
    replacements: list[dict[str, Any]] = []
    seen_spans: set[tuple[int, int]] = set()

    def byte_offset(character_offset: int) -> int:
        return len(bom) + len(text[:character_offset].encode("utf-8"))

    for spec in specs:
        pointer = spec["jsonPointer"]
        expected_value = spec["expectedValue"]
        proposed_value = spec["proposedValue"]
        value_kind = spec["valueKind"]
        if value_kind == "uv_array":
            numeric_vector(expected_value, 4, "expected explicit UV")
            numeric_vector(proposed_value, 4, "proposed explicit UV")
        elif value_kind == "number":
            numeric_vector([expected_value], 1, "expected geometry scalar")
            numeric_vector([proposed_value], 1, "proposed geometry scalar")
        else:
            raise ModelAuditError(f"Unsupported planned token value kind: {value_kind!r}")
        if expected_value == proposed_value:
            raise ModelAuditError(f"Planned token replacement is a no-op at {pointer}")
        ambiguous_duplicates = [
            duplicate
            for duplicate in locator.duplicate_pointers
            if pointer == duplicate
            or pointer.startswith(duplicate + "/")
            or duplicate.startswith(pointer + "/")
        ]
        if ambiguous_duplicates:
            raise ModelAuditError(
                f"Planned JSON pointer intersects duplicate object keys: {pointer}; duplicates={sorted(ambiguous_duplicates)}"
            )
        if pointer not in spans:
            raise ModelAuditError(f"Planned JSON pointer has no source token: {pointer}")
        char_start, char_end = spans[pointer]
        byte_start, byte_end = byte_offset(char_start), byte_offset(char_end)
        if (byte_start, byte_end) in seen_spans:
            raise ModelAuditError(f"Duplicate planned token span: {pointer}")
        seen_spans.add((byte_start, byte_end))
        current = _pointer_get(before_document, pointer)
        if current != expected_value:
            raise ModelAuditError(f"Source token no longer matches plan at {pointer}")
        original_token = source_bytes[byte_start:byte_end]
        try:
            parsed_token = json.loads(original_token.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise ModelAuditError(f"Located token is not valid JSON at {pointer}") from exception
        if parsed_token != expected_value:
            raise ModelAuditError(f"Located token is not the expected locked value at {pointer}")
        if value_kind == "uv_array" and not isinstance(parsed_token, list):
            raise ModelAuditError(f"Located token is not the expected explicit UV array at {pointer}")
        if value_kind == "number" and (
            isinstance(parsed_token, bool) or not isinstance(parsed_token, (int, float))
        ):
            raise ModelAuditError(f"Located token is not the expected geometry number at {pointer}")
        replacement_token = json.dumps(
            proposed_value, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        _pointer_set(expected_document, pointer, proposed_value)
        replacements.append(
            {
                "candidateId": spec["operationId"],
                "operationId": spec["operationId"],
                "operationClass": spec["operationClass"],
                "valueKind": value_kind,
                "jsonPointer": pointer,
                "byteStart": byte_start,
                "byteEnd": byte_end,
                "originalToken": original_token.decode("utf-8"),
                "replacementToken": replacement_token.decode("utf-8"),
                "replacementBytes": replacement_token,
            }
        )

    ordered = sorted(replacements, key=lambda row: row["byteStart"])
    for previous, current in zip(ordered, ordered[1:]):
        if previous["byteEnd"] > current["byteStart"]:
            raise ModelAuditError("Planned JSON token spans overlap")

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
        raise ModelAuditError("Token replacement produced invalid JSON") from exception
    if after_document != expected_document:
        raise ModelAuditError("Deep comparison failed: content outside selected planned values changed")
    if source_bytes == output:
        raise ModelAuditError("Apply plan produced no source-byte change")

    report_rows = [
        {key: value for key, value in replacement.items() if key != "replacementBytes"}
        for replacement in ordered
    ]
    return output, report_rows


def _prepare_uv_token_replacements(
    source_bytes: bytes, entries: Sequence[dict[str, Any]]
) -> tuple[bytes, list[dict[str, Any]]]:
    specs = [
        {
            "operationId": entry["candidateId"],
            "operationClass": "explicit_uv_token_shift",
            "jsonPointer": entry["jsonPointer"],
            "expectedValue": entry["expectedExplicitUv"],
            "proposedValue": entry["proposedUv"],
            "valueKind": "uv_array",
        }
        for entry in entries
    ]
    return _prepare_json_token_replacements(source_bytes, specs)


def _load_plan(plan_path: Path, expected_sha256: str) -> tuple[dict[str, Any], str, bytes]:
    expected = _valid_sha256(expected_sha256, "--expect-plan-sha256")
    plan_bytes = plan_path.read_bytes()
    actual = sha256_bytes(plan_bytes)
    if actual != expected:
        raise ModelAuditError(f"Apply-plan SHA-256 mismatch: expected {expected}, actual {actual}")
    try:
        plan = json.loads(plan_bytes.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ModelAuditError(f"Apply plan is not valid UTF-8 JSON: {exception}") from exception
    if not isinstance(plan, dict) or plan.get("schemaVersion") != APPLY_PLAN_SCHEMA_VERSION:
        raise ModelAuditError(f"Apply plan schemaVersion must be {APPLY_PLAN_SCHEMA_VERSION}")
    if not isinstance(plan.get("candidates"), list):
        raise ModelAuditError("Apply plan candidates must be an array")
    has_flagged_operations = (
        plan.get("selectionPolicy") == ROTATED_BULK_SELECTION_POLICY
        and plan.get("includeReviewedReanchors") is True
        and any(
            isinstance(plan.get(key), list) and plan[key]
            for key in ("geometryOriginShifts", "phaseReanchors", "geometryReanchors")
        )
    )
    if not plan["candidates"] and not has_flagged_operations:
        raise ModelAuditError("Apply plan contains no applicable operations")
    return plan, actual, plan_bytes


def _geometry_operation_specs(operation: dict[str, Any]) -> list[dict[str, Any]]:
    operation_id = operation.get("id")
    operation_class = operation.get("operation")
    pointer = operation.get("jsonPointer")
    if not isinstance(operation_id, str) or not operation_id:
        raise ModelAuditError("Geometry operation ID must be a non-empty string")
    if operation_class not in {"geometry_origin_shift", "geometry_reanchor"}:
        raise ModelAuditError(f"Unsupported geometry operation class: {operation_class!r}")
    if not isinstance(pointer, str) or not re.fullmatch(r"/elements/[0-9]+", pointer):
        raise ModelAuditError(f"Geometry operation has an invalid element JSON pointer: {pointer!r}")
    expected = operation.get("expectedGeometry")
    proposed = operation.get("proposedGeometry")
    if not isinstance(expected, dict) or not isinstance(proposed, dict):
        raise ModelAuditError(f"Geometry operation {operation_id} lacks locked expected/proposed geometry")
    if set(expected) != {"from", "to", "rotation"} or set(proposed) != {"from", "to", "rotation"}:
        raise ModelAuditError(f"Geometry operation {operation_id} geometry fields are incomplete")
    expected_rotation = expected["rotation"]
    proposed_rotation = proposed["rotation"]
    if not isinstance(expected_rotation, dict) or not isinstance(proposed_rotation, dict):
        raise ModelAuditError(f"Geometry operation {operation_id} rotation lock is invalid")
    expected_without_origin = {key: value for key, value in expected_rotation.items() if key != "origin"}
    proposed_without_origin = {key: value for key, value in proposed_rotation.items() if key != "origin"}
    if expected_without_origin != proposed_without_origin:
        raise ModelAuditError(f"Geometry operation {operation_id} changes rotation fields other than origin")

    vector_pairs: list[tuple[str, tuple[float, ...], tuple[float, ...]]] = [
        ("from", numeric_vector(expected["from"], 3, "expected from"), numeric_vector(proposed["from"], 3, "proposed from")),
        ("to", numeric_vector(expected["to"], 3, "expected to"), numeric_vector(proposed["to"], 3, "proposed to")),
    ]
    if "origin" in expected_rotation or "origin" in proposed_rotation:
        if "origin" not in expected_rotation or "origin" not in proposed_rotation:
            raise ModelAuditError(f"Geometry operation {operation_id} cannot add or remove an origin token")
        vector_pairs.append(
            (
                "rotation/origin",
                numeric_vector(expected_rotation["origin"], 3, "expected rotation origin"),
                numeric_vector(proposed_rotation["origin"], 3, "proposed rotation origin"),
            )
        )
    specs: list[dict[str, Any]] = []
    for relative_pointer, expected_vector, proposed_vector in vector_pairs:
        for index, (expected_value, proposed_value) in enumerate(
            zip(expected_vector, proposed_vector)
        ):
            expected_number = clean_number(expected_value)
            proposed_number = clean_number(proposed_value)
            if expected_number == proposed_number:
                continue
            specs.append(
                {
                    "operationId": operation_id,
                    "operationClass": operation_class,
                    "jsonPointer": f"{pointer}/{relative_pointer}/{index}",
                    "expectedValue": expected_number,
                    "proposedValue": proposed_number,
                    "valueKind": "number",
                }
            )
    if not specs:
        raise ModelAuditError(f"Geometry operation {operation_id} changes no scalar tokens")
    return specs


def _bulk_locked_sources(
    plan: dict[str, Any], root: Path
) -> tuple[dict[str, Path], dict[str, bytes]]:
    file_locks = plan.get("files")
    if not isinstance(file_locks, list) or not file_locks:
        raise ModelAuditError("Rotated bulk plan files must be a non-empty array")
    if plan.get("expectedFileCount") != len(file_locks):
        raise ModelAuditError("Rotated bulk plan expectedFileCount does not match its file locks")
    file_paths: dict[str, Path] = {}
    source_bytes: dict[str, bytes] = {}
    for index, lock in enumerate(file_locks):
        if not isinstance(lock, dict):
            raise ModelAuditError(f"Rotated bulk file lock {index} must be an object")
        canonical_path = lock.get("canonicalPath")
        expected_sha = _valid_sha256(
            lock.get("sourceSha256"), f"Rotated bulk file lock {index} sourceSha256"
        )
        model_file, resolved_path = model_file_from_canonical_path(root, canonical_path)
        if resolved_path != canonical_path:
            raise ModelAuditError(
                f"Plan canonicalPath is not canonical: {canonical_path!r} should be {resolved_path!r}"
            )
        if canonical_path in file_paths:
            raise ModelAuditError(f"Rotated bulk plan repeats file lock: {canonical_path}")
        payload = model_file.read_bytes()
        actual_sha = sha256_bytes(payload)
        if actual_sha != expected_sha:
            raise ModelAuditError(
                f"Source SHA-256 mismatch for {canonical_path}: expected {expected_sha}, actual {actual_sha}"
            )
        file_paths[canonical_path] = model_file
        source_bytes[canonical_path] = payload
    return file_paths, source_bytes


def apply_plan(
    plan_path: Path,
    expected_plan_sha256: str,
    *,
    canonical_root: Path = DEFAULT_SOURCE_ROOT,
    apply_report_path: Path = DEFAULT_APPLY_REPORT,
) -> dict[str, Any]:
    """Validate, precompute, and transactionally apply one locked UV plan."""

    root = canonical_models_root(canonical_root)
    if plan_path.resolve() == apply_report_path.resolve():
        raise ModelAuditError("Apply plan and apply report must be different files")
    ensure_reports_outside_sources((apply_report_path,), (root,))
    plan, plan_sha, _ = _load_plan(plan_path, expected_plan_sha256)
    selection_policy = plan.get("selectionPolicy")
    if selection_policy not in {None, ROTATED_BULK_SELECTION_POLICY}:
        raise ModelAuditError(f"Unsupported apply-plan selectionPolicy: {selection_policy!r}")
    rotated_bulk_plan = selection_policy == ROTATED_BULK_SELECTION_POLICY
    include_reviewed_reanchors = plan.get("includeReviewedReanchors", False)
    if rotated_bulk_plan and not isinstance(include_reviewed_reanchors, bool):
        raise ModelAuditError("Rotated bulk plan includeReviewedReanchors must be boolean")
    normalized_entries = [_validate_plan_entry(entry, index) for index, entry in enumerate(plan["candidates"])]

    candidate_ids = [entry["candidateId"] for entry in normalized_entries]
    pointers = [(entry["canonicalPath"], entry["jsonPointer"]) for entry in normalized_entries]
    if len(candidate_ids) != len(set(candidate_ids)):
        raise ModelAuditError("Apply plan contains duplicate candidate IDs")
    if len(pointers) != len(set(pointers)):
        raise ModelAuditError("Apply plan contains duplicate file/JSON-pointer selections")

    entries_by_file: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in normalized_entries:
        entries_by_file[entry["canonicalPath"]].append(entry)

    if rotated_bulk_plan:
        file_paths, locked_source_bytes = _bulk_locked_sources(plan, root)
        for canonical_path, entries in entries_by_file.items():
            if canonical_path not in file_paths:
                raise ModelAuditError(f"Bulk candidate has no full-file lock: {canonical_path}")
            expected_shas = {entry["sourceSha256"] for entry in entries}
            if expected_shas != {sha256_bytes(locked_source_bytes[canonical_path])}:
                raise ModelAuditError(f"Bulk candidate SHA locks disagree for {canonical_path}")
    else:
        file_paths = {}
        locked_source_bytes = {}
        for canonical_path, entries in entries_by_file.items():
            model_file, resolved_path = model_file_from_canonical_path(root, canonical_path)
            if resolved_path != canonical_path:
                raise ModelAuditError(
                    f"Plan canonicalPath is not canonical: {canonical_path!r} should be {resolved_path!r}"
                )
            source_bytes = model_file.read_bytes()
            actual_sha = sha256_bytes(source_bytes)
            expected_shas = {entry["sourceSha256"] for entry in entries}
            if len(expected_shas) != 1:
                raise ModelAuditError(f"Plan uses conflicting source SHA values for {canonical_path}")
            expected_sha = next(iter(expected_shas))
            if actual_sha != expected_sha:
                raise ModelAuditError(
                    f"Source SHA-256 mismatch for {canonical_path}: expected {expected_sha}, actual {actual_sha}"
                )
            file_paths[canonical_path] = model_file
            locked_source_bytes[canonical_path] = source_bytes

    pre_audit_inputs = [root] if rotated_bulk_plan else list(file_paths.values())
    pre_audit = audit_models(pre_audit_inputs, canonical_root=root)
    if pre_audit["summary"]["parseFailures"] or pre_audit["summary"]["validationFailures"]:
        raise ModelAuditError("Pre-apply UV audit contains parse or validation failures")
    if rotated_bulk_plan:
        expected_bulk_plan = generate_rotated_bulk_plan(
            pre_audit, include_reviewed_reanchors=include_reviewed_reanchors
        )
        if plan != expected_bulk_plan:
            raise ModelAuditError(
                "Rotated bulk plan no longer exactly matches the full canonical-root file and candidate inventory"
            )
        if include_reviewed_reanchors and plan["operationCounts"]["reportOnlyFaces"] != 0:
            raise ModelAuditError(
                "Complete reviewed-reanchor apply requires zero unresolved rotated report-only faces"
            )
    findings_by_id = {finding["id"]: finding for finding in pre_audit["findings"]}
    eligible_by_file: dict[str, set[str]] = defaultdict(set)
    eligibility_field = "bulkPlanEligible" if rotated_bulk_plan else "applyEligible"
    for finding in pre_audit["findings"]:
        if finding[eligibility_field]:
            eligible_by_file[finding["canonicalPath"]].add(finding["id"])

    compared_fields = {
        "canonicalPath": "canonicalPath",
        "sourceSha256": "sourceSha256",
        "candidateId": "id",
        "jsonPointer": "jsonPointer",
        "elementFingerprint": "elementFingerprint",
        "elementOccurrence": "elementOccurrence",
        "face": "face",
        "texture": "texture",
        "expectedExplicitUv": "uvBefore",
        "proposedUv": "proposedUv",
        "shifts": "integerShift",
    }
    for index, entry in enumerate(normalized_entries):
        finding = findings_by_id.get(entry["candidateId"])
        if finding is None:
            raise ModelAuditError(f"Plan candidate {index} is stale or an extra selection: {entry['candidateId']}")
        if finding["uvSource"] != "explicit" or not finding[eligibility_field]:
            raise ModelAuditError(f"Plan candidate {index} is implicit or otherwise not apply-eligible")
        for plan_field, finding_field in compared_fields.items():
            if entry[plan_field] != finding[finding_field]:
                raise ModelAuditError(
                    f"Plan candidate {index} mismatch for {plan_field}: "
                    f"expected current audit value {finding[finding_field]!r}"
                )

    for canonical_path, entries in entries_by_file.items():
        planned_ids = {entry["candidateId"] for entry in entries}
        current_ids = eligible_by_file.get(canonical_path, set())
        missing = sorted(current_ids - planned_ids)
        extra = sorted(planned_ids - current_ids)
        if missing or extra:
            raise ModelAuditError(
                f"Plan selection for {canonical_path} must be complete and exact; "
                f"missing={missing[:5]}, extra={extra[:5]}"
            )

    token_specs_by_file: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in normalized_entries:
        token_specs_by_file[entry["canonicalPath"]].append(
            {
                "operationId": entry["candidateId"],
                "operationClass": "explicit_uv_token_shift",
                "jsonPointer": entry["jsonPointer"],
                "expectedValue": entry["expectedExplicitUv"],
                "proposedValue": entry["proposedUv"],
                "valueKind": "uv_array",
            }
        )

    if rotated_bulk_plan and include_reviewed_reanchors:
        for phase in plan["phaseReanchors"]:
            token_specs_by_file[phase["canonicalPath"]].append(
                {
                    "operationId": phase["id"],
                    "operationClass": "phase_reanchor",
                    "jsonPointer": phase["jsonPointer"],
                    "expectedValue": phase["expectedExplicitUv"],
                    "proposedValue": phase["proposedUv"],
                    "valueKind": "uv_array",
                }
            )
        for geometry in plan["geometryOriginShifts"] + plan["geometryReanchors"]:
            token_specs_by_file[geometry["canonicalPath"]].extend(
                _geometry_operation_specs(geometry)
            )

    if not token_specs_by_file:
        raise ModelAuditError("Apply plan contains no token replacements")
    unknown_spec_files = set(token_specs_by_file) - set(file_paths)
    if unknown_spec_files:
        raise ModelAuditError(f"Operations lack full-file locks: {sorted(unknown_spec_files)}")
    if rotated_bulk_plan and include_reviewed_reanchors and set(token_specs_by_file) != set(file_paths):
        missing_files = sorted(set(file_paths) - set(token_specs_by_file))
        raise ModelAuditError(
            f"Complete flagged plan did not precompute operations for every locked file: {missing_files[:5]}"
        )

    prepared: dict[str, dict[str, Any]] = {}
    all_operation_ids: set[str] = set()
    for canonical_path in sorted(token_specs_by_file):
        specs = token_specs_by_file[canonical_path]
        before = locked_source_bytes[canonical_path]
        after, replacement_rows = _prepare_json_token_replacements(before, specs)
        operation_ids = sorted({spec["operationId"] for spec in specs})
        duplicate_ids = all_operation_ids & set(operation_ids)
        if duplicate_ids:
            raise ModelAuditError(f"Apply plan repeats operation IDs across files: {sorted(duplicate_ids)}")
        all_operation_ids.update(operation_ids)
        prepared[canonical_path] = {
            "path": file_paths[canonical_path],
            "before": before,
            "after": after,
            "sourceSha256Before": sha256_bytes(before),
            "sourceSha256After": sha256_bytes(after),
            "candidateIds": [entry["candidateId"] for entry in entries_by_file.get(canonical_path, [])],
            "operationIds": operation_ids,
            "replacements": replacement_rows,
        }

    written: list[str] = []
    try:
        for canonical_path in sorted(prepared):
            row = prepared[canonical_path]
            if row["path"].read_bytes() != row["before"]:
                raise ModelAuditError(f"Source changed between validation and atomic write: {canonical_path}")
            atomic_write_bytes(row["path"], row["after"])
            written.append(canonical_path)
            if row["path"].read_bytes() != row["after"]:
                raise ModelAuditError(f"Atomic write readback mismatch: {canonical_path}")

        post_audit_inputs = [root] if rotated_bulk_plan else list(file_paths.values())
        post_audit = audit_models(post_audit_inputs, canonical_root=root)
        if post_audit["summary"]["parseFailures"] or post_audit["summary"]["validationFailures"]:
            raise ModelAuditError("Post-apply UV audit contains parse or validation failures")
        post_ids = {finding["id"] for finding in post_audit["findings"]}
        if any(candidate_id in post_ids for candidate_id in candidate_ids):
            raise ModelAuditError("Post-apply audit still contains a selected pre-apply candidate")
        if rotated_bulk_plan and include_reviewed_reanchors:
            remaining_rotated = post_audit["summary"]["rotatedOutOfRangeFaces"]
            if remaining_rotated != 0:
                raise ModelAuditError(
                    f"Complete flagged post-audit still has {remaining_rotated} rotated out-of-range faces"
                )
        for canonical_path, row in prepared.items():
            actual_after = row["path"].read_bytes()
            if sha256_bytes(actual_after) != row["sourceSha256After"]:
                raise ModelAuditError(f"Post-apply SHA verification failed: {canonical_path}")

        apply_report = {
            "schemaVersion": APPLY_REPORT_SCHEMA_VERSION,
            "mode": "apply",
            "sourceWrites": True,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "plan": {"path": str(plan_path.resolve()), "sha256": plan_sha},
            "canonicalModelsRoot": str(root),
            "safety": {
                "explicitOnly": not include_reviewed_reanchors,
                "completePerFileSelection": True,
                "completeFullRootManifest": rotated_bulk_plan,
                "sourceShaLocked": True,
                "tokenSpanOnly": True,
                "deepCompareOnlySelectedUvPointersChanged": True,
                "deepCompareOnlySelectedPointersChanged": True,
                "allFilesPrecomputedBeforeWrite": True,
                "atomicMultiFileWithRollback": True,
                "writeReadbackVerified": True,
                "preAndPostAudit": True,
                "selectionPolicy": selection_policy or "all_explicit_integer_16_candidates_per_file",
                "geometryOriginShiftsApplied": bool(
                    rotated_bulk_plan and include_reviewed_reanchors and plan["geometryOriginShifts"]
                ),
                "phaseReanchorsApplied": bool(
                    rotated_bulk_plan and include_reviewed_reanchors and plan["phaseReanchors"]
                ),
                "geometryReanchorsApplied": bool(
                    rotated_bulk_plan and include_reviewed_reanchors and plan["geometryReanchors"]
                ),
            },
            "operationCounts": (
                copy.deepcopy(plan.get("operationCounts"))
                if rotated_bulk_plan
                else {"explicitUvTokenShiftFaces": len(candidate_ids)}
            ),
            "operationProofs": (
                {
                    "exactExplicitUvTokenShifts": copy.deepcopy(plan["candidates"]),
                    "exactGeometryOriginShifts": copy.deepcopy(plan["geometryOriginShifts"]),
                    "phaseReanchors": copy.deepcopy(plan["phaseReanchors"]),
                    "geometryReanchors": copy.deepcopy(plan["geometryReanchors"]),
                }
                if rotated_bulk_plan and include_reviewed_reanchors
                else None
            ),
            "preAudit": pre_audit,
            "files": [
                {
                    "canonicalPath": canonical_path,
                    "sourceSha256Before": row["sourceSha256Before"],
                    "sourceSha256After": row["sourceSha256After"],
                    "candidateIds": row["candidateIds"],
                    "operationIds": row["operationIds"],
                    "replacements": row["replacements"],
                }
                for canonical_path, row in sorted(prepared.items())
            ],
            "postAudit": post_audit,
            "result": "applied_and_verified",
        }
        atomic_write_text(apply_report_path, json.dumps(apply_report, indent=2, ensure_ascii=False) + "\n")
        return apply_report
    except BaseException:
        rollback_failures: list[str] = []
        for canonical_path in reversed(written):
            try:
                atomic_write_bytes(prepared[canonical_path]["path"], prepared[canonical_path]["before"])
                if prepared[canonical_path]["path"].read_bytes() != prepared[canonical_path]["before"]:
                    raise OSError("rollback readback mismatch")
            except BaseException as rollback_exception:
                rollback_failures.append(f"{canonical_path}: {rollback_exception}")
        if rollback_failures:
            raise ModelAuditError("Apply failed and rollback was incomplete: " + "; ".join(rollback_failures))
        raise


def self_test() -> None:
    shifted = classify_interval(18, 20)
    assert shifted.classification == "integer_16_shift"
    assert shifted.shift == -16
    assert shifted.after == (2, 4)
    assert classify_interval(-1, 1).classification == "boundary_crossing"
    assert classify_interval(0, 17).classification == "span_over_16"
    explicit = classify_uv_rect([18, -16, 20, 0])
    assert explicit.classification == "integer_16_shift_candidate"
    assert explicit.proposed == (2, 0, 4, 16)
    assert default_face_uv({"from": [16, 0, 0], "to": [20, 4, 4]}, "south") == (16, 12, 20, 16)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help=f"standard model JSON files or recursive directories (default: {DEFAULT_SOURCE_ROOT})",
    )
    parser.add_argument("--canonical-models-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--json-report", type=Path, default=DEFAULT_JSON_REPORT)
    parser.add_argument("--csv-report", type=Path, default=DEFAULT_CSV_REPORT)
    parser.add_argument("--max-files", type=int, help="audit only the first N sorted files for a bounded diagnostic run")
    parser.add_argument("--self-test", action="store_true", help="run arithmetic checks before auditing")
    parser.add_argument(
        "--generate-rotated-plan",
        type=Path,
        help="audit the complete canonical root and write a deterministic rotated correction plan",
    )
    parser.add_argument(
        "--include-reviewed-reanchors",
        action="store_true",
        help="include non-exact phase and geometry reanchors as locked review-only plan entries",
    )
    parser.add_argument("--apply-plan", type=Path, help="apply one complete reviewed SHA-locked plan")
    parser.add_argument("--expect-plan-sha256", help="required exact SHA-256 of --apply-plan")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.apply_plan is not None:
        if args.generate_rotated_plan is not None:
            raise ModelAuditError("--apply-plan and --generate-rotated-plan are mutually exclusive")
        if args.expect_plan_sha256 is None:
            raise ModelAuditError("--apply-plan requires --expect-plan-sha256")
        if args.include_reviewed_reanchors:
            raise ModelAuditError("--include-reviewed-reanchors is only valid with --generate-rotated-plan")
        if args.paths or args.max_files is not None or args.self_test:
            raise ModelAuditError("Apply mode does not accept audit paths, --max-files, or --self-test")
        report = apply_plan(
            args.apply_plan,
            args.expect_plan_sha256,
            canonical_root=args.canonical_models_root,
            apply_report_path=DEFAULT_APPLY_REPORT,
        )
        print(
            "Model UV apply complete: "
            f"files={len(report['files'])}, candidates={sum(len(row['candidateIds']) for row in report['files'])}"
        )
        print(f"Apply report: {DEFAULT_APPLY_REPORT.resolve()}")
        return 0
    if args.expect_plan_sha256 is not None:
        raise ModelAuditError("--expect-plan-sha256 is only valid with --apply-plan")
    if args.include_reviewed_reanchors and args.generate_rotated_plan is None:
        raise ModelAuditError("--include-reviewed-reanchors requires --generate-rotated-plan")

    if args.generate_rotated_plan is not None:
        if args.paths or args.max_files is not None or args.self_test:
            raise ModelAuditError(
                "Rotated-plan generation scans the complete canonical root and does not accept paths, --max-files, or --self-test"
            )
        inputs = [args.canonical_models_root]
        report = audit_models(inputs, canonical_root=args.canonical_models_root)
        plan = generate_rotated_bulk_plan(
            report, include_reviewed_reanchors=args.include_reviewed_reanchors
        )
        ensure_reports_outside_sources(
            (args.json_report, args.csv_report, args.generate_rotated_plan), inputs
        )
        write_reports(report, args.json_report, args.csv_report, inputs)
        rendered_plan = render_rotated_bulk_plan(plan)
        atomic_write_text(args.generate_rotated_plan, rendered_plan)
        plan_sha = sha256_bytes(rendered_plan.encode("utf-8"))
        counts = plan["operationCounts"]
        print(
            "Rotated UV plan generated: "
            f"files={counts['lockedFiles']}, explicitTokenShifts={counts['explicitUvTokenShiftFaces']}, "
            f"geometryOriginElements={counts['geometryOriginShiftElements']}, "
            f"geometryOriginFaces={counts['geometryOriginShiftFaces']}, "
            f"phaseReanchors={counts['phaseReanchorFaces']}, "
            f"geometryReanchorElements={counts['geometryReanchorElements']}, "
            f"geometryReanchorFaces={counts['geometryReanchorFaces']}, "
            f"reportOnlyFaces={counts['reportOnlyFaces']}"
        )
        print(f"Plan SHA-256: {plan_sha}")
        print(f"Plan: {args.generate_rotated_plan.resolve()}")
        return 0

    if args.self_test:
        self_test()
        if not args.paths:
            print("Model UV safety self-test passed")
            return 0

    inputs = args.paths or [args.canonical_models_root]
    report = audit_models(inputs, max_files=args.max_files, canonical_root=args.canonical_models_root)
    write_reports(report, args.json_report, args.csv_report, inputs)
    summary = report["summary"]
    print(
        "Model UV audit complete: "
        f"files={summary['filesScanned']}/{summary['filesDiscovered']}, "
        f"faces={summary['faces']}, arithmeticCandidates={summary['integer16ArithmeticCandidates']}, "
        f"applyEligibleExplicit={summary['explicitApplyEligibleCandidates']}, "
        f"derivedReview={summary['derivedReviewCandidates']}, "
        f"rotatedExplicit={summary['rotatedExplicitTokenShiftCandidates']}, "
        f"rotatedDerivedGeometry={summary['rotatedDerivedGeometryShiftFaces']}, "
        f"rotatedReportOnly={summary['rotatedReportOnlyFaces']}, "
        f"boundaryBlocked={summary['blockedBoundaryCrossing']}, spanBlocked={summary['blockedSpanOver16']}, "
        f"parseFailures={summary['parseFailures']}, validationFailures={summary['validationFailures']}"
    )
    print(f"JSON report: {args.json_report.resolve()}")
    print(f"CSV report: {args.csv_report.resolve()}")
    return 1 if summary["parseFailures"] or summary["validationFailures"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ModelAuditError, OSError, AssertionError) as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        raise SystemExit(1)
