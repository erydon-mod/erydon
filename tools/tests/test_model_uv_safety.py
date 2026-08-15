from __future__ import annotations

import csv
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOLS_ROOT = Path(__file__).resolve().parents[1]
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from model_geometry_common import (  # noqa: E402
    ModelAuditError,
    classify_interval,
    classify_uv_rect,
    sha256_bytes,
)
from model_uv_safety import (  # noqa: E402
    apply_plan,
    audit_models,
    generate_rotated_bulk_plan,
    plan_entry_from_finding,
    render_csv,
    render_rotated_bulk_plan,
    self_test,
    write_reports,
)
import model_uv_safety as uv_tool  # noqa: E402


def write_json(path: Path, value: object) -> bytes:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path.write_bytes(payload)
    return payload


def write_plan(path: Path, entries: list[dict]) -> str:
    payload = (json.dumps({"schemaVersion": 1, "candidates": entries}, indent=2) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return sha256_bytes(payload)


def locked_entry_from_finding(finding: dict) -> dict:
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


def model_with_face(*, from_vector, to_vector, face="south", face_data=None):
    return {
        "textures": {"stone": "erydon:block/aganite_block"},
        "elements": [
            {
                "from": list(from_vector),
                "to": list(to_vector),
                "faces": {face: face_data if face_data is not None else {"texture": "#stone"}},
            }
        ],
    }


def rotated_element(*, from_vector, to_vector, faces, axis="z", angle=22.5, origin=(0, 15, 5), rescale=False):
    rotation = {"angle": angle, "axis": axis, "origin": list(origin)}
    if rescale:
        rotation["rescale"] = True
    return {
        "from": list(from_vector),
        "to": list(to_vector),
        "rotation": rotation,
        "faces": copy.deepcopy(faces),
    }


class IntervalClassificationTest(unittest.TestCase):
    def test_integer_period_shift_is_an_arithmetic_candidate(self):
        analysis = classify_interval(18, 20)
        self.assertEqual("integer_16_shift", analysis.classification)
        self.assertEqual(-16, analysis.shift)
        self.assertEqual((2, 4), analysis.after)

    def test_reversed_uv_orientation_is_preserved(self):
        analysis = classify_interval(20, 18)
        self.assertEqual("integer_16_shift", analysis.classification)
        self.assertEqual((4, 2), analysis.after)

    def test_boundary_crossing_is_blocked(self):
        analysis = classify_interval(-1, 1)
        self.assertEqual("boundary_crossing", analysis.classification)
        self.assertIsNone(analysis.after)

    def test_span_over_one_sprite_is_blocked(self):
        analysis = classify_interval(0, 17)
        self.assertEqual("span_over_16", analysis.classification)

    def test_uv_rect_can_shift_each_axis_independently(self):
        analysis = classify_uv_rect([18, -16, 20, 0])
        self.assertEqual("integer_16_shift_candidate", analysis.classification)
        self.assertEqual((2, 0, 4, 16), analysis.proposed)
        self.assertEqual(-16, analysis.u.shift)
        self.assertEqual(16, analysis.v.shift)


class ModelUvAuditTest(unittest.TestCase):
    def test_recursive_scan_reports_explicit_derived_and_blocked_faces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            write_json(
                root / "block" / "explicit.json",
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            write_json(
                root / "block" / "nested" / "derived.json",
                model_with_face(from_vector=(16, 0, 0), to_vector=(20, 4, 4)),
            )
            write_json(
                root / "block" / "blocked.json",
                model_with_face(from_vector=(-1, 0, 0), to_vector=(1, 2, 2)),
            )
            write_json(root / "item" / "parent_only.json", {"parent": "minecraft:item/generated"})
            (root / "ignored.txt").write_text("not json", encoding="utf-8")

            report = audit_models([root], canonical_root=root)
            summary = report["summary"]
            self.assertEqual(4, summary["filesDiscovered"])
            self.assertEqual(3, summary["geometryModels"])
            self.assertEqual(1, summary["modelsWithoutElements"])
            self.assertEqual(3, summary["faces"])
            self.assertEqual(2, summary["integer16ArithmeticCandidates"])
            self.assertEqual(1, summary["explicitApplyEligibleCandidates"])
            self.assertEqual(1, summary["derivedReviewCandidates"])
            self.assertEqual(1, summary["blockedBoundaryCrossing"])
            self.assertEqual(0, summary["parseFailures"])
            self.assertEqual(0, summary["validationFailures"])

            findings = {finding["file"]: finding for finding in report["findings"]}
            explicit = findings["block/explicit.json"]
            self.assertEqual("explicit", explicit["uvSource"])
            self.assertTrue(explicit["applyEligible"])
            self.assertEqual([2, 2, 4, 4], explicit["proposedUv"])
            self.assertEqual(-16, explicit["integerShift"]["u"])

            derived = findings["block/nested/derived.json"]
            self.assertEqual("derived", derived["uvSource"])
            self.assertFalse(derived["applyEligible"])
            self.assertIn("CTM", derived["reviewReason"])
            self.assertIn("geometry", derived["reviewReason"])
            self.assertEqual([16, 12, 20, 16], derived["uvBefore"])
            self.assertEqual([0, 12, 4, 16], derived["proposedUv"])

            blocked = findings["block/blocked.json"]
            self.assertEqual("blocked", blocked["status"])
            self.assertEqual("blocked_boundary_crossing", blocked["classification"])
            self.assertIsNone(blocked["proposedUv"])
            self.assertIn("Not performed", report["policy"]["textureAndParentResolution"])
            self.assertIn("Excluded", report["policy"]["rawAuthoringModels"])

    def test_candidate_id_and_path_are_stable_for_root_and_single_file_scans(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            source = root / "block" / "same.json"
            write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            root_report = audit_models([root], canonical_root=root)
            file_report = audit_models([source], canonical_root=root)
            root_finding = root_report["findings"][0]
            file_finding = file_report["findings"][0]
            self.assertEqual("block/same.json", root_finding["canonicalPath"])
            self.assertEqual(root_finding["canonicalPath"], file_finding["canonicalPath"])
            self.assertEqual(root_finding["id"], file_finding["id"])
            self.assertRegex(root_finding["id"], r"^uv-[0-9a-f]{24}$")

    def test_source_sha_is_reported_and_source_is_never_changed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            source = root / "block" / "source.json"
            original = write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            report = audit_models([root], canonical_root=root)
            reports = Path(temporary) / "reports"
            json_report = reports / "uv.json"
            csv_report = reports / "uv.csv"
            write_reports(report, json_report, csv_report, [root])

            self.assertEqual(original, source.read_bytes())
            self.assertEqual(64, len(report["findings"][0]["sourceSha256"]))
            self.assertEqual(64, len(report["scanManifestSha256"]))
            self.assertEqual(report["summary"], json.loads(json_report.read_text(encoding="utf-8"))["summary"])

            csv_rows = list(csv.DictReader(io.StringIO(csv_report.read_text(encoding="utf-8"))))
            self.assertEqual(1, len(csv_rows))
            self.assertEqual(report["findings"][0]["id"], csv_rows[0]["id"])

    def test_report_destination_inside_source_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            write_json(root / "block" / "safe.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            report = audit_models([root], canonical_root=root)
            with self.assertRaises(ModelAuditError):
                write_reports(report, root / "audit.json", Path(temporary) / "audit.csv", [root])

    def test_identical_report_destinations_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            write_json(root / "block" / "model.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            report = audit_models([root], canonical_root=root)
            destination = Path(temporary) / "reports" / "audit.out"
            with self.assertRaisesRegex(ModelAuditError, "different files"):
                write_reports(report, destination, destination, [root])

    def test_raw_authoring_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "authoring_models"
            write_json(root / "raw.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            with self.assertRaises(ModelAuditError):
                audit_models([root])

    def test_broad_root_containing_raw_authoring_models_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "assets" / "erydon"
            write_json(root / "models" / "block" / "safe.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            write_json(root / "authoring_models" / "block" / "raw.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            with self.assertRaises(ModelAuditError):
                audit_models([root])

    def test_span_over_16_is_reported_not_proposed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            write_json(
                root / "block" / "wide.json",
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [0, 0, 17, 1]},
                ),
            )
            report = audit_models([root], canonical_root=root)
            finding = report["findings"][0]
            self.assertEqual("blocked_span_over_16", finding["classification"])
            self.assertIsNone(finding["proposedUv"])

    def test_csv_has_headers_when_no_findings_exist(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            write_json(root / "block" / "safe.json", model_with_face(from_vector=(0, 0, 0), to_vector=(1, 1, 1)))
            report = audit_models([root], canonical_root=root)
            rows = list(csv.DictReader(io.StringIO(render_csv(report))))
            self.assertEqual([], rows)

    def test_embedded_self_test(self):
        self_test()


class RotatedBulkPlanTest(unittest.TestCase):
    def test_implicit_rotated_faces_get_one_geometry_preserving_uv_free_proposal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            source = root / "block" / "derived.json"
            before = write_json(
                source,
                {
                    "textures": {"stone": "erydon:block/stone"},
                    "elements": [
                        rotated_element(
                            from_vector=(-1, 14.5, 4),
                            to_vector=(1, 17, 6),
                            faces={
                                "south": {"texture": "#stone"},
                                "up": {"texture": "#stone"},
                            },
                        )
                    ],
                },
            )

            report = audit_models([root], canonical_root=root)
            self.assertEqual(2, report["summary"]["rotatedDerivedOutOfRangeFaces"])
            self.assertEqual(2, report["summary"]["rotatedDerivedGeometryShiftFaces"])
            self.assertEqual(1, report["summary"]["rotatedDerivedGeometryShiftElements"])
            self.assertEqual(0, report["summary"]["rotatedReportOnlyFaces"])
            self.assertTrue(all(row["bulkCorrectionKind"] == "geometry_origin_shift" for row in report["findings"]))

            candidate = report["geometryOriginShiftCandidates"][0]
            self.assertFalse(candidate["applyEligible"])
            self.assertTrue(candidate["geometryPreserved"])
            self.assertTrue(candidate["facesRemainUvFree"])
            self.assertLessEqual(candidate["maxTransformedCornerDrift"], candidate["geometryProofTolerance"])
            self.assertEqual([0, 13.5, 4], candidate["proposedGeometry"]["from"])
            self.assertEqual([2, 16, 6], candidate["proposedGeometry"]["to"])
            self.assertEqual(2, candidate["repairedDerivedFaceCount"])
            self.assertTrue(all(row["inRangeAfter"] for row in candidate["derivedFacesChecked"]))
            self.assertTrue(all(row["remainsUvFree"] for row in candidate["derivedFacesChecked"]))
            self.assertEqual(before, source.read_bytes())
            parsed = json.loads(source.read_text(encoding="utf-8"))
            self.assertTrue(all("uv" not in face for face in parsed["elements"][0]["faces"].values()))

    def test_rotation_axis_rescale_and_span_blockers_stay_report_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "models"
            source = root / "block" / "blocked.json"
            write_json(
                source,
                {
                    "elements": [
                        rotated_element(
                            from_vector=(0, -0.125, 0),
                            to_vector=(2, 1, 2),
                            axis="y",
                            origin=(1, 0, 1),
                            faces={"south": {"texture": "#stone"}},
                        ),
                        rotated_element(
                            from_vector=(-1, 14.5, 4),
                            to_vector=(1, 17, 6),
                            rescale=True,
                            faces={"south": {"texture": "#stone"}},
                        ),
                        rotated_element(
                            from_vector=(-1, 0, 4),
                            to_vector=(17, 2, 6),
                            faces={"up": {"texture": "#stone"}},
                        ),
                    ]
                },
            )

            report = audit_models([root], canonical_root=root)
            self.assertEqual(0, len(report["geometryOriginShiftCandidates"]))
            self.assertEqual(3, report["summary"]["rotatedDerivedReportOnlyFaces"])
            self.assertEqual(1, report["summary"]["reviewGeometryReanchorElements"])
            self.assertEqual(1, report["summary"]["reviewGeometryReanchorFaces"])
            reanchor = report["geometryReanchorCandidates"][0]
            self.assertEqual(0.125, reanchor["axisShift"])
            self.assertEqual([0, 0.125, 0], reanchor["renderedTranslation"])
            self.assertEqual(0.125, reanchor["maxRenderedPointDrift"])
            self.assertTrue(reanchor["facesRemainUvFree"])
            self.assertFalse(reanchor["geometryPreserved"])
            codes = {finding["geometryBlockCode"] for finding in report["findings"]}
            self.assertEqual(
                {"rotation_axis_dependency", "rescale_unsupported", "bounds_do_not_fit"},
                codes,
            )
            plan = generate_rotated_bulk_plan(report, include_reviewed_reanchors=True)
            self.assertEqual(1, plan["operationCounts"]["geometryReanchorElements"])
            self.assertEqual(1, plan["operationCounts"]["geometryReanchorFaces"])
            self.assertEqual(2, plan["operationCounts"]["reportOnlyFaces"])
            self.assertEqual(reanchor["id"], plan["geometryReanchors"][0]["id"])

    def test_flagged_bulk_plan_applies_all_four_operation_classes(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "mixed.json"
            before = write_json(
                source,
                {
                    "elements": [
                        rotated_element(
                            from_vector=(0, 0, 0),
                            to_vector=(1, 1, 1),
                            origin=(0, 0, 0),
                            faces={"south": {"texture": "#stone", "uv": [18, 2, 20, 4]}},
                        ),
                        {
                            "from": [0, 0, 0],
                            "to": [1, 1, 1],
                            "faces": {"south": {"texture": "#stone", "uv": [18, 2, 20, 4]}},
                        },
                        rotated_element(
                            from_vector=(-1, 14.5, 4),
                            to_vector=(1, 17, 6),
                            faces={"south": {"texture": "#stone"}},
                        ),
                        rotated_element(
                            from_vector=(0, 0, 0),
                            to_vector=(1, 1, 1),
                            origin=(0, 0, 0),
                            faces={"south": {"texture": "#stone", "uv": [-1, 0, 1, 2]}},
                        ),
                        rotated_element(
                            from_vector=(0, -0.125, 0),
                            to_vector=(2, 1, 2),
                            axis="y",
                            origin=(1, 0, 1),
                            faces={"south": {"texture": "#stone"}},
                        ),
                    ]
                },
            )

            first_report = audit_models([root], canonical_root=root)
            second_report = audit_models([root], canonical_root=root)
            plan = generate_rotated_bulk_plan(first_report)
            second_plan = generate_rotated_bulk_plan(second_report)
            self.assertEqual(render_rotated_bulk_plan(plan), render_rotated_bulk_plan(second_plan))
            self.assertNotIn(str(root.resolve()), render_rotated_bulk_plan(plan))
            self.assertEqual(
                {
                    "rotatedOutOfRangeFaces": 4,
                    "explicitUvTokenShiftFaces": 1,
                    "geometryOriginShiftElements": 1,
                    "geometryOriginShiftFaces": 1,
                    "phaseReanchorFaces": 0,
                    "geometryReanchorElements": 0,
                    "geometryReanchorFaces": 0,
                    "reportOnlyFaces": 2,
                    "lockedFiles": 1,
                },
                plan["operationCounts"],
            )
            self.assertEqual("/elements/0/faces/south/uv", plan["candidates"][0]["jsonPointer"])
            self.assertEqual(1, len(plan["geometryOriginShifts"]))
            self.assertEqual(2, len(plan["reportOnlyFaces"]))
            self.assertEqual(before, source.read_bytes())

            reviewed_plan = generate_rotated_bulk_plan(
                first_report, include_reviewed_reanchors=True
            )
            self.assertTrue(reviewed_plan["includeReviewedReanchors"])
            self.assertEqual(1, reviewed_plan["operationCounts"]["phaseReanchorFaces"])
            self.assertEqual(1, reviewed_plan["operationCounts"]["geometryReanchorElements"])
            self.assertEqual(1, reviewed_plan["operationCounts"]["geometryReanchorFaces"])
            self.assertEqual(0, reviewed_plan["operationCounts"]["reportOnlyFaces"])
            phase = reviewed_plan["phaseReanchors"][0]
            self.assertEqual([-1, 0, 1, 2], phase["expectedExplicitUv"])
            self.assertEqual([0, 0, 2, 2], phase["proposedUv"])
            self.assertEqual({"u": 1, "v": 0}, phase["shifts"])
            self.assertTrue(phase["spanPreserved"])
            self.assertFalse(phase["phasePreserved"])

            reviewed_path = base / "review" / "reviewed.json"
            reviewed_path.parent.mkdir(parents=True, exist_ok=True)
            reviewed_bytes = render_rotated_bulk_plan(reviewed_plan).encode("utf-8")
            reviewed_path.write_bytes(reviewed_bytes)
            tampered = copy.deepcopy(reviewed_plan)
            tampered["phaseReanchors"][0]["proposedUv"][3] = 3
            tampered_path = base / "review" / "tampered-proof.json"
            tampered_bytes = render_rotated_bulk_plan(tampered).encode("utf-8")
            tampered_path.write_bytes(tampered_bytes)
            with self.assertRaisesRegex(ModelAuditError, "full canonical-root file and candidate inventory"):
                apply_plan(
                    tampered_path,
                    sha256_bytes(tampered_bytes),
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "tampered-proof.json",
                )
            self.assertEqual(before, source.read_bytes())

            apply_report = apply_plan(
                reviewed_path,
                sha256_bytes(reviewed_bytes),
                canonical_root=root,
                apply_report_path=base / "build" / "reports" / "reviewed-apply.json",
            )
            parsed = json.loads(source.read_text(encoding="utf-8"))
            self.assertEqual([2, 2, 4, 4], parsed["elements"][0]["faces"]["south"]["uv"])
            self.assertEqual([18, 2, 20, 4], parsed["elements"][1]["faces"]["south"]["uv"])
            self.assertNotIn("uv", parsed["elements"][2]["faces"]["south"])
            self.assertEqual([0, 0, 2, 2], parsed["elements"][3]["faces"]["south"]["uv"])
            self.assertNotIn("uv", parsed["elements"][4]["faces"]["south"])
            self.assertEqual(0, parsed["elements"][4]["from"][1])
            self.assertTrue(apply_report["safety"]["geometryOriginShiftsApplied"])
            self.assertTrue(apply_report["safety"]["phaseReanchorsApplied"])
            self.assertTrue(apply_report["safety"]["geometryReanchorsApplied"])
            self.assertEqual(0, apply_report["postAudit"]["summary"]["rotatedOutOfRangeFaces"])
            operation_classes = {
                replacement["operationClass"]
                for replacement in apply_report["files"][0]["replacements"]
            }
            self.assertEqual(
                {
                    "explicit_uv_token_shift",
                    "geometry_origin_shift",
                    "phase_reanchor",
                    "geometry_reanchor",
                },
                operation_classes,
            )
            with self.assertRaisesRegex(ModelAuditError, "Source SHA-256 mismatch"):
                apply_plan(
                    reviewed_path,
                    sha256_bytes(reviewed_bytes),
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "reviewed-apply.json",
                )

    def test_bulk_plan_locks_full_scan_and_candidate_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "target.json"
            original = write_json(
                source,
                {
                    "elements": [
                        rotated_element(
                            from_vector=(0, 0, 0),
                            to_vector=(1, 1, 1),
                            origin=(0, 0, 0),
                            faces={"south": {"texture": "#stone", "uv": [18, 2, 20, 4]}},
                        )
                    ]
                },
            )
            unrelated = root / "item" / "parent.json"
            write_json(unrelated, {"parent": "minecraft:item/generated"})
            report = audit_models([root], canonical_root=root)
            plan = generate_rotated_bulk_plan(report, include_reviewed_reanchors=True)

            tampered = copy.deepcopy(plan)
            tampered["expectedFileCount"] += 1
            tampered_path = base / "review" / "tampered.json"
            tampered_path.parent.mkdir(parents=True, exist_ok=True)
            tampered_bytes = render_rotated_bulk_plan(tampered).encode("utf-8")
            tampered_path.write_bytes(tampered_bytes)
            with self.assertRaisesRegex(ModelAuditError, "expectedFileCount"):
                apply_plan(
                    tampered_path,
                    sha256_bytes(tampered_bytes),
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "tampered.json",
                )
            self.assertEqual(original, source.read_bytes())

            plan_path = base / "review" / "locked.json"
            plan_bytes = render_rotated_bulk_plan(plan).encode("utf-8")
            plan_path.write_bytes(plan_bytes)
            unrelated.write_bytes(unrelated.read_bytes() + b" ")
            with self.assertRaisesRegex(ModelAuditError, "full canonical-root file and candidate inventory"):
                apply_plan(
                    plan_path,
                    sha256_bytes(plan_bytes),
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "locked.json",
                )
            self.assertEqual(original, source.read_bytes())

            partial = audit_models([source], canonical_root=root)
            with self.assertRaisesRegex(ModelAuditError, "source root must equal"):
                generate_rotated_bulk_plan(partial)

    def test_flagged_multi_file_write_failure_rolls_back_every_written_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            sources = [root / "block" / "a.json", root / "block" / "b.json"]
            originals: dict[Path, bytes] = {}
            for source in sources:
                originals[source.resolve()] = write_json(
                    source,
                    {
                        "elements": [
                            rotated_element(
                                from_vector=(0, 0, 0),
                                to_vector=(1, 1, 1),
                                origin=(0, 0, 0),
                                faces={
                                    "south": {
                                        "texture": "#stone",
                                        "uv": [18, 2, 20, 4],
                                    }
                                },
                            )
                        ]
                    },
                )
            report = audit_models([root], canonical_root=root)
            plan = generate_rotated_bulk_plan(report, include_reviewed_reanchors=True)
            plan_path = base / "review" / "rollback.json"
            plan_path.parent.mkdir(parents=True, exist_ok=True)
            plan_bytes = render_rotated_bulk_plan(plan).encode("utf-8")
            plan_path.write_bytes(plan_bytes)
            apply_report_path = base / "build" / "reports" / "rollback.json"

            real_atomic_write = uv_tool.atomic_write_bytes
            forward_writes = 0

            def fail_second_forward_write(path, payload):
                nonlocal forward_writes
                resolved = Path(path).resolve()
                if payload != originals[resolved]:
                    forward_writes += 1
                    if forward_writes == 2:
                        raise OSError("injected second-file write failure")
                return real_atomic_write(path, payload)

            with mock.patch.object(
                uv_tool, "atomic_write_bytes", side_effect=fail_second_forward_write
            ):
                with self.assertRaisesRegex(OSError, "injected second-file write failure"):
                    apply_plan(
                        plan_path,
                        sha256_bytes(plan_bytes),
                        canonical_root=root,
                        apply_report_path=apply_report_path,
                    )

            self.assertEqual(2, forward_writes)
            for source in sources:
                self.assertEqual(originals[source.resolve()], source.read_bytes())
            self.assertFalse(apply_report_path.exists())


class ModelUvApplyTest(unittest.TestCase):
    def test_duplicate_keys_outside_selected_tokens_are_preserved_but_selected_duplicates_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "duplicate-metadata.json"
            source.parent.mkdir(parents=True, exist_ok=True)
            original = (
                b'{"elements":[{"from":[0,0,0],"to":[1,1,1],"faces":{"south":'
                b'{"texture":"#stone","tintindex":0,"tintindex":1,"uv":[18,2,20,4]}}}]}'
            )
            source.write_bytes(original)
            finding = audit_models([root], canonical_root=root)["findings"][0]
            plan_path = base / "review" / "metadata.json"
            plan_sha = write_plan(plan_path, [plan_entry_from_finding(finding)])
            apply_plan(
                plan_path,
                plan_sha,
                canonical_root=root,
                apply_report_path=base / "build" / "reports" / "metadata.json",
            )
            after = source.read_bytes()
            self.assertEqual(2, after.count(b'"tintindex"'))
            self.assertIn(b'"uv":[2,2,4,4]', after)

            duplicate_uv = root / "block" / "duplicate-uv.json"
            duplicate_payload = (
                b'{"elements":[{"from":[0,0,0],"to":[1,1,1],"faces":{"south":'
                b'{"texture":"#stone","uv":[18,2,20,4],"uv":[34,2,36,4]}}}]}'
            )
            duplicate_uv.write_bytes(duplicate_payload)
            duplicate_finding = audit_models([duplicate_uv], canonical_root=root)["findings"][0]
            duplicate_plan = base / "review" / "duplicate-uv.json"
            duplicate_sha = write_plan(duplicate_plan, [plan_entry_from_finding(duplicate_finding)])
            with self.assertRaisesRegex(ModelAuditError, "intersects duplicate object keys"):
                apply_plan(
                    duplicate_plan,
                    duplicate_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "duplicate-uv.json",
                )
            self.assertEqual(duplicate_payload, duplicate_uv.read_bytes())

    def test_apply_plan_cannot_be_overwritten_by_its_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "explicit.json"
            write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            entry = plan_entry_from_finding(audit_models([source], canonical_root=root)["findings"][0])
            plan = base / "review" / "plan.json"
            plan_sha = write_plan(plan, [entry])
            with self.assertRaisesRegex(ModelAuditError, "different files"):
                apply_plan(plan, plan_sha, canonical_root=root, apply_report_path=plan)

    def test_explicit_plan_changes_only_uv_token_and_second_run_fails_sha_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "explicit.json"
            model = {
                "credit": "é before the UV token",
                **model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            }
            before = write_json(source, model)
            audit = audit_models([source], canonical_root=root)
            entry = plan_entry_from_finding(audit["findings"][0])
            plan = base / "review" / "plan.json"
            plan_sha = write_plan(plan, [entry])
            report_path = base / "build" / "reports" / "model-geometry" / "uv-apply.json"

            apply_report = apply_plan(
                plan,
                plan_sha,
                canonical_root=root,
                apply_report_path=report_path,
            )

            after = source.read_bytes()
            replacement = apply_report["files"][0]["replacements"][0]
            start = replacement["byteStart"]
            end = replacement["byteEnd"]
            replacement_bytes = replacement["replacementToken"].encode("utf-8")
            self.assertEqual(before[:start], after[:start])
            self.assertEqual(before[end:], after[start + len(replacement_bytes) :])
            self.assertEqual([2, 2, 4, 4], json.loads(after.decode("utf-8"))["elements"][0]["faces"]["south"]["uv"])
            self.assertEqual(sha256_bytes(before), apply_report["files"][0]["sourceSha256Before"])
            self.assertEqual(sha256_bytes(after), apply_report["files"][0]["sourceSha256After"])
            self.assertEqual("applied_and_verified", apply_report["result"])
            self.assertTrue(apply_report["safety"]["preAndPostAudit"])
            self.assertTrue(report_path.is_file())

            with self.assertRaisesRegex(ModelAuditError, "Source SHA-256 mismatch"):
                apply_plan(plan, plan_sha, canonical_root=root, apply_report_path=report_path)

    def test_derived_candidate_cannot_be_planned_or_applied(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "derived.json"
            before = write_json(source, model_with_face(from_vector=(16, 0, 0), to_vector=(20, 4, 4)))
            finding = audit_models([source], canonical_root=root)["findings"][0]
            self.assertFalse(finding["applyEligible"])
            with self.assertRaisesRegex(ModelAuditError, "Only explicit"):
                plan_entry_from_finding(finding)

            plan = base / "review" / "plan.json"
            plan_sha = write_plan(plan, [locked_entry_from_finding(finding)])
            with self.assertRaisesRegex(ModelAuditError, "implicit.*not apply-eligible"):
                apply_plan(
                    plan,
                    plan_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "uv-apply.json",
                )
            self.assertEqual(before, source.read_bytes())

    def test_changed_source_sha_is_rejected_before_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "changed.json"
            write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            finding = audit_models([source], canonical_root=root)["findings"][0]
            plan = base / "review" / "plan.json"
            plan_sha = write_plan(plan, [plan_entry_from_finding(finding)])
            changed = source.read_bytes() + b" "
            source.write_bytes(changed)

            with self.assertRaisesRegex(ModelAuditError, "Source SHA-256 mismatch"):
                apply_plan(
                    plan,
                    plan_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "uv-apply.json",
                )
            self.assertEqual(changed, source.read_bytes())

    def test_plan_must_select_every_eligible_candidate_in_each_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "two.json"
            model = model_with_face(
                from_vector=(0, 0, 0),
                to_vector=(1, 1, 1),
                face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
            )
            model["elements"][0]["faces"]["north"] = {"texture": "#stone", "uv": [18, 2, 20, 4]}
            before = write_json(source, model)
            findings = audit_models([source], canonical_root=root)["findings"]
            self.assertEqual(2, len(findings))
            plan = base / "review" / "plan.json"
            plan_sha = write_plan(plan, [plan_entry_from_finding(findings[0])])

            with self.assertRaisesRegex(ModelAuditError, "complete and exact"):
                apply_plan(
                    plan,
                    plan_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "uv-apply.json",
                )
            self.assertEqual(before, source.read_bytes())

    def test_duplicate_incomplete_extra_and_mismatched_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "one.json"
            before = write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            entry = plan_entry_from_finding(audit_models([source], canonical_root=root)["findings"][0])
            cases = []

            cases.append(("duplicate", [entry, copy.deepcopy(entry)], "duplicate candidate"))
            incomplete = copy.deepcopy(entry)
            incomplete.pop("texture")
            cases.append(("incomplete", [incomplete], "incomplete"))
            extra = copy.deepcopy(entry)
            extra["candidateId"] = "uv-000000000000000000000000"
            extra["jsonPointer"] = entry["jsonPointer"] + "/0"
            cases.append(("extra", [entry, extra], "stale or an extra selection"))
            mismatched = copy.deepcopy(entry)
            mismatched["proposedUv"] = [1, 2, 4, 4]
            cases.append(("mismatched", [mismatched], "mismatch for proposedUv"))

            for label, entries, message in cases:
                with self.subTest(label=label):
                    plan = base / "review" / f"{label}.json"
                    plan_sha = write_plan(plan, entries)
                    with self.assertRaisesRegex(ModelAuditError, message):
                        apply_plan(
                            plan,
                            plan_sha,
                            canonical_root=root,
                            apply_report_path=base / "build" / "reports" / f"{label}.json",
                        )
                    self.assertEqual(before, source.read_bytes())

    def test_path_escape_and_symlinked_model_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "models"
            source = root / "block" / "target.json"
            write_json(
                source,
                model_with_face(
                    from_vector=(0, 0, 0),
                    to_vector=(1, 1, 1),
                    face_data={"texture": "#stone", "uv": [18, 2, 20, 4]},
                ),
            )
            entry = plan_entry_from_finding(audit_models([source], canonical_root=root)["findings"][0])

            escaping = copy.deepcopy(entry)
            escaping["canonicalPath"] = "../escape.json"
            escape_plan = base / "review" / "escape.json"
            escape_sha = write_plan(escape_plan, [escaping])
            with self.assertRaisesRegex(ModelAuditError, "clean relative POSIX path"):
                apply_plan(
                    escape_plan,
                    escape_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "escape.json",
                )

            link = root / "block" / "link.json"
            try:
                link.symlink_to(source.name)
            except OSError as exception:
                self.skipTest(f"Symlink creation is unavailable: {exception}")
            linked = copy.deepcopy(entry)
            linked["canonicalPath"] = "block/link.json"
            link_plan = base / "review" / "link.json"
            link_sha = write_plan(link_plan, [linked])
            with self.assertRaisesRegex(ModelAuditError, "Symlinked model paths"):
                apply_plan(
                    link_plan,
                    link_sha,
                    canonical_root=root,
                    apply_report_path=base / "build" / "reports" / "link.json",
                )


if __name__ == "__main__":
    unittest.main()
