from __future__ import annotations

import csv
import hashlib
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOLS = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "zfight"
sys.path.insert(0, str(TOOLS))

import model_z_fighting_safety as scanner  # noqa: E402


class ModelZFightingSafetyTests(unittest.TestCase):
    def scan(self, name: str) -> dict:
        return scanner.scan_model_file(FIXTURES / name, base=FIXTURES)

    def prepare_apply_case(self, temporary: Path, fixture_name: str) -> dict:
        root = temporary / "canonical-models"
        model = root / "test" / fixture_name
        model.parent.mkdir(parents=True)
        source = (FIXTURES / fixture_name).read_bytes()
        model.write_bytes(source)
        canonical_path = scanner.CANONICAL_BLOCK_PREFIX + f"test/{fixture_name}"
        source_sha = hashlib.sha256(source).hexdigest()
        document = json.loads(source.decode("utf-8"))
        audit = scanner.scan_model_document(
            document,
            relative_path=canonical_path,
            source_sha256=source_sha,
        )
        finding = audit["findings"][0]
        endpoint = scanner._deterministic_smaller_endpoint(finding)
        target = finding[endpoint]
        pointer, sign = scanner._outward_pointer(target["element"], target["face"])
        current = scanner._pointer_get(document, pointer)
        plan = {
            "schemaVersion": scanner.APPLY_PLAN_SCHEMA_VERSION,
            "candidate": {
                "canonicalPath": canonical_path,
                "sourceSha256": source_sha,
                "findingId": finding["finding_id"],
                "targetEndpoint": endpoint,
                "element": target["element"],
                "face": target["face"],
                "jsonPointer": pointer,
                "expectedNumber": current,
                "proposedNumber": float(current) + sign * scanner.NUDGE_AMOUNT,
                "amount": scanner.NUDGE_AMOUNT,
                "action": "nudge_face_outward",
                "expectedFindingReduction": 1,
            },
        }
        plan_path = temporary / "plans" / f"{fixture_name}.plan.json"
        report_path = temporary / "reports" / f"{fixture_name}.apply.json"
        plan_sha = self.write_plan(plan_path, plan)
        return {
            "root": root,
            "model": model,
            "source": source,
            "plan": plan,
            "plan_path": plan_path,
            "plan_sha": plan_sha,
            "report_path": report_path,
        }

    @staticmethod
    def write_plan(path: Path, plan: dict) -> str:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = (json.dumps(plan, indent=2) + "\n").encode("utf-8")
        path.write_bytes(payload)
        return hashlib.sha256(payload).hexdigest()

    @staticmethod
    def prepare_bulk_root(temporary: Path, fixture_names: list[str]) -> tuple[Path, dict[str, bytes]]:
        root = temporary / "canonical-models"
        copied: dict[str, bytes] = {}
        for fixture_name in fixture_names:
            source = (FIXTURES / fixture_name).read_bytes()
            target = root / "test" / fixture_name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source)
            copied[fixture_name] = source
        return root, copied

    @staticmethod
    def prepare_registered_raw_root(
        temporary: Path,
        fixture_name: str,
        *,
        registered_path: str = "alcove/alcove_georgian_double_side_left.json",
    ) -> tuple[Path, Path, dict[str, bytes]]:
        root = temporary / "raw-authoring-models"
        sources: dict[str, bytes] = {}
        empty = b'{"elements": []}\n'
        fixture = (FIXTURES / fixture_name).read_bytes()
        for relative in scanner.REGISTERED_RAW_MODEL_PATHS:
            source = fixture if relative == registered_path else empty
            target = root.joinpath(*relative.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source)
            sources[relative] = source
        return root, root.joinpath(*registered_path.split("/")), sources

    def test_literal_duplicate_is_the_only_auto_candidate_shape(self) -> None:
        result = self.scan("duplicate.json")
        self.assertEqual(result["status"], "scanned")
        self.assertEqual(len(result["findings"]), 1)
        finding = result["findings"][0]
        self.assertEqual(finding["orientation"], "same")
        self.assertEqual(finding["overlap_class"], "exact")
        self.assertEqual(finding["repair_classification"], "AUTO_CANDIDATE")
        self.assertEqual(
            finding["proposed_action"], {"action": "remove_face", "element": 1, "face": "north"}
        )
        self.assertEqual(finding["candidate_id"], finding["finding_id"])

    def test_contained_same_facing_overlap_remains_report_only(self) -> None:
        finding = self.scan("contained.json")["findings"][0]
        self.assertEqual(finding["orientation"], "same")
        self.assertEqual(finding["overlap_class"], "contained")
        self.assertEqual(finding["repair_classification"], "REPORT_ONLY")
        self.assertIsNone(finding["candidate_id"])
        self.assertIsNone(finding["proposed_action"])

    def test_opposite_facing_internal_contact_remains_report_only(self) -> None:
        finding = self.scan("opposite.json")["findings"][0]
        self.assertEqual(finding["orientation"], "opposite")
        self.assertEqual(finding["overlap_class"], "exact")
        self.assertEqual(finding["repair_classification"], "REPORT_ONLY")
        self.assertIn("internal boundary", finding["reason"])

    def test_near_coplanar_pair_is_separate_warning_class(self) -> None:
        finding = self.scan("near.json")["findings"][0]
        self.assertEqual(finding["orientation"], "same")
        self.assertEqual(finding["overlap_class"], "near")
        self.assertEqual(finding["plane_quality"], "near")
        self.assertEqual(finding["repair_classification"], "REPORT_ONLY")

    def test_vanilla_rotation_with_rescale_is_transformed(self) -> None:
        result = self.scan("rotated_rescale_duplicate.json")
        self.assertEqual(result["status"], "scanned")
        self.assertEqual(result["unsupported"], [])
        self.assertEqual(len(result["findings"]), 1)
        self.assertEqual(result["findings"][0]["repair_classification"], "AUTO_CANDIDATE")
        self.assertAlmostEqual(result["findings"][0]["coverage_a"], 1.0, places=8)

    def test_raw_euler_rotation_fails_closed(self) -> None:
        result = self.scan("raw_euler.json")
        self.assertEqual(result["status"], "unsupported")
        self.assertFalse(result["repair_eligible"])
        self.assertEqual(result["faces"], 0)
        self.assertEqual(result["findings"], [])
        self.assertIn("raw Euler", result["unsupported"][0]["reason"])

    def test_registered_raw_paths_match_java_loader_authoring_models(self) -> None:
        java = (
            TOOLS.parent
            / "src/main/java/com/oliver/erydon/client/model/ErydonRawModelLoadingPlugin.java"
        ).read_text(encoding="utf-8")
        loader_paths = set(
            re.findall(r'authoring_models/block/([^"\\]+\.json)', java)
        )
        self.assertEqual(loader_paths, set(scanner.REGISTERED_RAW_MODEL_PATHS))
        self.assertEqual(len(loader_paths), 43)

    def test_ordinary_file_audit_uses_raw_transforms_only_for_registered_models(self) -> None:
        repository = TOOLS.parent
        relative = "alcove/alcove_georgian_double_side_left.json"
        model_path = (
            repository
            / "src/main/resources/assets/erydon/authoring_models/block"
            / Path(relative)
        )
        source = model_path.read_bytes()
        document = json.loads(source.decode("utf-8-sig"))
        expected = scanner.scan_model_document(
            document,
            relative_path=scanner.RAW_CANONICAL_BLOCK_PREFIX + relative,
            source_sha256=hashlib.sha256(source).hexdigest(),
            raw_authoring=True,
            enable_raw_transforms=True,
        )
        ordinary = scanner.scan_model_file(model_path, base=repository)
        self.assertEqual(ordinary["status"], expected["status"])
        self.assertEqual(ordinary["findings"], expected["findings"])
        self.assertEqual(ordinary["rawAuthoring"], expected["rawAuthoring"])

        with tempfile.TemporaryDirectory() as temporary:
            unregistered = (
                Path(temporary)
                / "assets/erydon/authoring_models/block/test/unregistered.json"
            )
            unregistered.parent.mkdir(parents=True)
            unregistered.write_bytes((FIXTURES / "raw_euler_with_overlap.json").read_bytes())
            refused = scanner.scan_model_file(unregistered, base=Path(temporary))
            self.assertEqual(refused["status"], "unsupported")
            self.assertFalse(refused["repair_eligible"])

    def test_registered_raw_models_match_repaired_geometry_baseline(self) -> None:
        raw_root = (
            TOOLS.parent
            / "src/main/resources/assets/erydon/authoring_models/block"
        )
        intentional_triple_panel_overlaps = {
            "alcove/alcove_georgian_triple_side_center.json": 6,
            "alcove/alcove_georgian_triple_top_left.json": 7,
            "alcove/alcove_georgian_triple_top_right.json": 7,
            "alcove/alcove_gothic_triple_side_center.json": 6,
            "alcove/alcove_gothic_triple_top_left.json": 7,
            "alcove/alcove_gothic_triple_top_right.json": 7,
        }
        observed_intentional_overlaps = {}
        exact_same = 0
        near = 0
        authored_cullfaces = 0
        for relative in scanner.REGISTERED_RAW_MODEL_PATHS:
            source = raw_root.joinpath(*relative.split("/")).read_bytes()
            document = json.loads(source.decode("utf-8-sig"))
            audit = scanner.scan_model_document(
                document,
                relative_path=scanner.RAW_CANONICAL_BLOCK_PREFIX + relative,
                source_sha256=hashlib.sha256(source).hexdigest(),
                raw_authoring=True,
                enable_raw_transforms=True,
            )
            same_findings = [
                finding
                for finding in audit["findings"]
                if finding["orientation"] == "same"
                and finding["plane_quality"] == "exact"
            ]
            if same_findings:
                self.assertIn(relative, intentional_triple_panel_overlaps)
                self.assertTrue(
                    all(
                        finding["overlap_class"] == "partial"
                        and finding["repair_classification"] == "REPORT_ONLY"
                        and finding["reason"]
                        == "overlap is not a literal identical-render duplicate"
                        and "_triple_" in document["elements"][finding["a"]["element"]]["name"]
                        and "_triple_" in document["elements"][finding["b"]["element"]]["name"]
                        for finding in same_findings
                    ),
                    relative,
                )
                observed_intentional_overlaps[relative] = len(same_findings)
            exact_same += sum(
                finding["orientation"] == "same"
                and finding["plane_quality"] == "exact"
                for finding in audit["findings"]
            )
            near += sum(
                finding["plane_quality"] == "near"
                for finding in audit["findings"]
            )
            authored_cullfaces += len(scanner._raw_cull_boundary_state(document))
        self.assertEqual(observed_intentional_overlaps, intentional_triple_panel_overlaps)
        self.assertEqual(exact_same, 40)
        self.assertEqual(near, 26)
        self.assertEqual(authored_cullfaces, 120)

    def test_raw_nested_group_transform_order_matches_golden_vertices(self) -> None:
        source = (FIXTURES / "raw_nested_group_overlap.json").read_bytes()
        document = json.loads(source.decode("utf-8"))
        group_rotations, metadata = scanner._raw_group_state(
            document, len(document["elements"])
        )
        self.assertEqual(
            [
                (rotation.x_degrees, rotation.y_degrees, rotation.z_degrees)
                for rotation in group_rotations[0]
            ],
            [(0.0, 0.0, 45.0), (0.0, 30.0, 0.0)],
        )
        faces, unsupported = scanner._build_faces(
            document["elements"], raw_group_rotations=group_rotations
        )
        self.assertEqual(unsupported, [])
        expected = (
            (3.720927295920, 4.181327831092, 5.318732264383),
            (1.284065043925, 1.016367370147, 5.530221378659),
            (-0.897584334006, 2.882259845352, 8.315692840144),
            (1.539277917989, 6.047220306297, 8.104203725868),
        )
        for actual_vertex, expected_vertex in zip(faces[0].vertices, expected):
            for actual, wanted in zip(actual_vertex, expected_vertex):
                self.assertAlmostEqual(actual, wanted, places=10)
        self.assertEqual(metadata["groupedElementReferences"], 2)
        audit = scanner.scan_model_document(
            document,
            relative_path=(
                scanner.RAW_CANONICAL_BLOCK_PREFIX
                + "alcove/alcove_georgian_double_side_left.json"
            ),
            source_sha256=hashlib.sha256(source).hexdigest(),
            raw_authoring=True,
            enable_raw_transforms=True,
        )
        self.assertEqual(audit["status"], "scanned")
        self.assertEqual(len(audit["findings"]), 1)
        self.assertEqual(audit["findings"][0]["orientation"], "same")
        self.assertEqual(audit["findings"][0]["plane_quality"], "exact")

    def test_zero_thickness_faces_are_reported_but_never_candidates(self) -> None:
        result = self.scan("zero_thickness.json")
        self.assertEqual(result["status"], "unsupported")
        self.assertEqual(result["faces"], 4)
        self.assertGreater(len(result["findings"]), 0)
        self.assertTrue(
            all(finding["repair_classification"] == "UNSUPPORTED" for finding in result["findings"])
        )

    def test_file_sha_and_finding_id_are_stable(self) -> None:
        path = FIXTURES / "duplicate.json"
        expected_sha = hashlib.sha256(path.read_bytes()).hexdigest()
        first = self.scan("duplicate.json")
        second = self.scan("duplicate.json")
        self.assertEqual(first["sha256"], expected_sha)
        self.assertEqual(
            first["findings"][0]["finding_id"], second["findings"][0]["finding_id"]
        )

    def test_finding_id_survives_an_unrelated_file_edit_but_sha_lock_changes(self) -> None:
        document = json.loads((FIXTURES / "duplicate.json").read_text(encoding="utf-8"))
        first = scanner.scan_model_document(
            document,
            relative_path="duplicate.json",
            source_sha256=hashlib.sha256(b"before").hexdigest(),
        )
        edited = dict(document)
        edited["credit"] = "unrelated report metadata"
        second = scanner.scan_model_document(
            edited,
            relative_path="duplicate.json",
            source_sha256=hashlib.sha256(b"after").hexdigest(),
        )
        self.assertNotEqual(first["sha256"], second["sha256"])
        self.assertEqual(
            first["findings"][0]["finding_id"], second["findings"][0]["finding_id"]
        )

    def test_missing_root_is_refused_without_creating_reports(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            json_report = temporary_path / "report.json"
            csv_report = temporary_path / "report.csv"
            exit_code = scanner.main(
                [
                    str(temporary_path / "missing"),
                    "--json-report",
                    str(json_report),
                    "--csv-report",
                    str(csv_report),
                ]
            )
            self.assertEqual(exit_code, 2)
            self.assertFalse(json_report.exists())
            self.assertFalse(csv_report.exists())

    def test_report_inside_source_root_is_refused(self) -> None:
        blocked_report = FIXTURES / "blocked-report.json"
        self.addCleanup(blocked_report.unlink, missing_ok=True)
        with tempfile.TemporaryDirectory() as temporary:
            csv_report = Path(temporary) / "report.csv"
            exit_code = scanner.main(
                [
                    str(FIXTURES),
                    "--json-report",
                    str(blocked_report),
                    "--csv-report",
                    str(csv_report),
                ]
            )
            self.assertEqual(exit_code, 2)
            self.assertFalse(blocked_report.exists())
            self.assertFalse(csv_report.exists())

    def test_report_cannot_equal_an_individual_source_file(self) -> None:
        source = FIXTURES / "duplicate.json"
        before = source.read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            exit_code = scanner.main(
                [
                    str(source),
                    "--json-report",
                    str(source),
                    "--csv-report",
                    str(Path(temporary) / "report.csv"),
                ]
            )
            self.assertEqual(exit_code, 2)
        self.assertEqual(source.read_bytes(), before)

    def test_nonpositive_max_files_is_rejected_by_argument_parser(self) -> None:
        with self.assertRaises(SystemExit) as caught:
            scanner.parse_args(["--max-files", "0"])
        self.assertEqual(caught.exception.code, 2)

    def test_cli_writes_json_and_csv_without_modifying_inputs(self) -> None:
        before = {path.name: path.read_bytes() for path in FIXTURES.glob("*.json")}
        with tempfile.TemporaryDirectory() as temporary:
            json_report = Path(temporary) / "report.json"
            csv_report = Path(temporary) / "report.csv"
            exit_code = scanner.main(
                [
                    str(FIXTURES),
                    "--base",
                    str(FIXTURES),
                    "--json-report",
                    str(json_report),
                    "--csv-report",
                    str(csv_report),
                ]
            )
            self.assertEqual(exit_code, 0)
            report = json.loads(json_report.read_text(encoding="utf-8"))
            self.assertEqual(report["mode"], "report-only")
            self.assertIs(report["sourceWrites"], False)
            self.assertIn("43 registered raw-authoring models", report["policy"]["rawTransforms"])
            self.assertEqual(report["summary"]["files"], len(before))
            self.assertGreaterEqual(report["summary"]["auto_candidates"], 2)
            with csv_report.open(encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(len(rows), report["summary"]["findings"])

        after = {path.name: path.read_bytes() for path in FIXTURES.glob("*.json")}
        self.assertEqual(before, after)

    def test_reviewed_nudge_cli_changes_only_the_locked_scalar_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "safe_nudge.json")
            with mock.patch.object(scanner, "DEFAULT_BLOCK_SOURCE_ROOT", case["root"]):
                exit_code = scanner.main(
                    [
                        "--apply-plan",
                        str(case["plan_path"]),
                        "--expect-plan-sha256",
                        case["plan_sha"],
                        "--apply-report",
                        str(case["report_path"]),
                    ]
                )
            self.assertEqual(exit_code, 0)
            report = json.loads(case["report_path"].read_text(encoding="utf-8"))
            replacement = report["replacement"]
            expected = (
                case["source"][: replacement["byteStart"]]
                + replacement["replacementToken"].encode("utf-8")
                + case["source"][replacement["byteEnd"] :]
            )
            self.assertEqual(case["model"].read_bytes(), expected)
            self.assertTrue(report["sourceWrites"])
            self.assertEqual(report["transition"]["findingsBefore"], 1)
            self.assertEqual(report["transition"]["findingsAfter"], 0)
            self.assertEqual(report["transition"]["newUvOutOfRangeFaces"], [])

    def test_reviewed_nudge_rejects_wrong_plan_sha_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "safe_nudge.json")
            with self.assertRaisesRegex(scanner.ModelAuditError, "Apply-plan SHA-256 mismatch"):
                scanner.apply_nudge_plan(
                    case["plan_path"],
                    "0" * 64,
                    canonical_root=case["root"],
                    apply_report_path=case["report_path"],
                )
            self.assertEqual(case["model"].read_bytes(), case["source"])
            self.assertFalse(case["report_path"].exists())

    def test_reviewed_nudge_rejects_wrong_pointer_and_direction(self) -> None:
        for mutation, message in (
            ({"jsonPointer": "/elements/1/from/0"}, "outward endpoint"),
            ({"proposedNumber": 7.999}, "wrong direction or amount"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                case = self.prepare_apply_case(Path(temporary), "safe_nudge.json")
                case["plan"]["candidate"].update(mutation)
                plan_sha = self.write_plan(case["plan_path"], case["plan"])
                with self.assertRaisesRegex(scanner.ModelAuditError, message):
                    scanner.apply_nudge_plan(
                        case["plan_path"],
                        plan_sha,
                        canonical_root=case["root"],
                        apply_report_path=case["report_path"],
                    )
                self.assertEqual(case["model"].read_bytes(), case["source"])

    def test_reviewed_nudge_rejects_romanesque_style_uv_regression(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "uv_regression_nudge.json")
            with self.assertRaisesRegex(scanner.ModelAuditError, "UV out-of-range"):
                scanner.apply_nudge_plan(
                    case["plan_path"],
                    case["plan_sha"],
                    canonical_root=case["root"],
                    apply_report_path=case["report_path"],
                )
            self.assertEqual(case["model"].read_bytes(), case["source"])

    def test_reviewed_nudge_rejects_a_new_z_fighting_conflict(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "new_conflict_nudge.json")
            with self.assertRaisesRegex(scanner.ModelAuditError, "creates new z-fighting"):
                scanner.apply_nudge_plan(
                    case["plan_path"],
                    case["plan_sha"],
                    canonical_root=case["root"],
                    apply_report_path=case["report_path"],
                )
            self.assertEqual(case["model"].read_bytes(), case["source"])

    def test_reviewed_nudge_rejects_a_report_inside_the_source_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "safe_nudge.json")
            blocked_report = case["root"] / "blocked-report.json"
            with self.assertRaisesRegex(scanner.ModelAuditError, "outside audited source roots"):
                scanner.apply_nudge_plan(
                    case["plan_path"],
                    case["plan_sha"],
                    canonical_root=case["root"],
                    apply_report_path=blocked_report,
                )
            self.assertEqual(case["model"].read_bytes(), case["source"])
            self.assertFalse(blocked_report.exists())

    def test_reviewed_nudge_is_stale_after_one_successful_apply(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            case = self.prepare_apply_case(Path(temporary), "safe_nudge.json")
            scanner.apply_nudge_plan(
                case["plan_path"],
                case["plan_sha"],
                canonical_root=case["root"],
                apply_report_path=case["report_path"],
            )
            applied = case["model"].read_bytes()
            with self.assertRaisesRegex(scanner.ModelAuditError, "Source SHA-256 mismatch"):
                scanner.apply_nudge_plan(
                    case["plan_path"],
                    case["plan_sha"],
                    canonical_root=case["root"],
                    apply_report_path=case["report_path"],
                )
            self.assertEqual(case["model"].read_bytes(), applied)

    def test_bulk_plan_graph_colours_a_three_face_clique_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, before = self.prepare_bulk_root(
                Path(temporary), ["graph_coloring_nudge.json"]
            )
            first = scanner.generate_bulk_nudge_plan(canonical_root=root)
            second = scanner.generate_bulk_nudge_plan(canonical_root=root)
            self.assertEqual(first, second)
            self.assertEqual(first["summary"]["eligibleModels"], 1)
            self.assertEqual(first["summary"]["eligibleFindings"], 3)
            self.assertEqual(first["summary"]["eligibleOperations"], 2)
            self.assertEqual(first["summary"]["blockedCandidateFindings"], 0)
            model = first["models"][0]
            self.assertEqual(model["findingsBefore"], 3)
            self.assertEqual(model["findingsAfter"], 0)
            self.assertEqual(
                [operation["offsetSteps"] for operation in model["operations"]], [1, 2]
            )
            self.assertEqual(
                [operation["proposedNumber"] for operation in model["operations"]],
                [8.001, 8.002],
            )
            self.assertEqual(
                (root / "test" / "graph_coloring_nudge.json").read_bytes(),
                before["graph_coloring_nudge.json"],
            )

    def test_bulk_plan_reports_every_safety_exclusion_and_failed_component(self) -> None:
        fixtures = [
            "uv_regression_nudge.json",
            "new_conflict_nudge.json",
            "protected_opposite_contact.json",
            "rotated_rescale_duplicate.json",
            "raw_euler_with_overlap.json",
            "zero_thickness.json",
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root, before = self.prepare_bulk_root(Path(temporary), fixtures)
            plan = scanner.generate_bulk_nudge_plan(canonical_root=root)
            summary = plan["summary"]
            dispositions = summary["findingDisposition"]
            failures = summary["componentValidationFailures"]
            self.assertEqual(summary["eligibleFindings"], 0)
            self.assertGreater(dispositions["opposite_facing_intentional_join"], 0)
            self.assertGreater(dispositions["rescaled_element"], 0)
            self.assertGreater(dispositions["unsupported_raw_euler"], 0)
            self.assertGreater(dispositions["unsupported_zero_thickness"], 0)
            self.assertGreater(failures["bounds_regression"], 0)
            self.assertGreater(failures["uv_regression"], 0)
            self.assertGreater(failures["new_conflict"], 0)
            self.assertGreater(failures["opposite_facing_contact"], 0)
            self.assertEqual(
                summary["candidateFindings"],
                summary["eligibleFindings"] + summary["blockedCandidateFindings"],
            )
            for fixture_name, source in before.items():
                self.assertEqual((root / "test" / fixture_name).read_bytes(), source)

    def test_bulk_plan_validator_locks_plan_and_complete_source_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            root, _ = self.prepare_bulk_root(
                temporary_path, ["graph_coloring_nudge.json"]
            )
            plan_path = temporary_path / "reports" / "bulk-plan.json"
            plan, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path, canonical_root=root
            )
            validated = scanner.validate_bulk_nudge_plan(
                plan_path, plan_sha, canonical_root=root
            )
            self.assertEqual(validated["result"], "validated_read_only")
            self.assertEqual(
                validated["sourceManifestSha256"], plan["sourceManifestSha256"]
            )
            source_path = root / "test" / "graph_coloring_nudge.json"
            source_path.write_bytes(source_path.read_bytes() + b"\n")
            with self.assertRaisesRegex(scanner.ModelAuditError, "source manifest"):
                scanner.validate_bulk_nudge_plan(
                    plan_path, plan_sha, canonical_root=root
                )

    def test_bulk_plan_output_inside_source_root_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, before = self.prepare_bulk_root(
                Path(temporary), ["graph_coloring_nudge.json"]
            )
            blocked = root / "blocked-bulk-plan.json"
            with self.assertRaisesRegex(scanner.ModelAuditError, "outside audited source roots"):
                scanner.write_bulk_nudge_plan(blocked, canonical_root=root)
            self.assertFalse(blocked.exists())
            self.assertEqual(
                (root / "test" / "graph_coloring_nudge.json").read_bytes(),
                before["graph_coloring_nudge.json"],
            )

    def test_full_recess_plan_converges_and_discloses_opposite_micro_separation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, before = self.prepare_bulk_root(
                Path(temporary),
                ["graph_coloring_nudge.json", "protected_opposite_contact.json"],
            )
            plan = scanner.generate_bulk_nudge_plan(
                canonical_root=root, include_full_recess_repair=True
            )
            summary = plan["summary"]
            self.assertEqual(plan["algorithm"]["strategy"], "full_recess")
            self.assertEqual(summary["eligibleFindings"], 4)
            self.assertEqual(summary["predictedEligibleExactSameFacingFindingsAfter"], 0)
            self.assertEqual(summary["predictedBoundsRegressions"], 0)
            self.assertEqual(summary["predictedUvRegressions"], 0)
            self.assertEqual(summary["predictedInversions"], 0)
            self.assertGreater(summary["oppositeContactsMicroSeparated"], 0)
            clique = next(
                model
                for model in plan["models"]
                if model["canonicalPath"].endswith("graph_coloring_nudge.json")
            )
            self.assertEqual(clique["roundCount"], 1)
            self.assertEqual(
                [operation["signedOffsetSteps"] for operation in clique["operations"]],
                [-2, -1],
            )
            for fixture_name, source in before.items():
                self.assertEqual((root / "test" / fixture_name).read_bytes(), source)

    def test_full_recess_apply_requires_opt_in_and_becomes_stale_after_success(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            root, _ = self.prepare_bulk_root(
                temporary_path, ["graph_coloring_nudge.json"]
            )
            plan_path = temporary_path / "reports" / "full-plan.json"
            report_path = temporary_path / "reports" / "full-apply.json"
            _, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=root,
                include_full_recess_repair=True,
            )
            with self.assertRaisesRegex(scanner.ModelAuditError, "explicit"):
                scanner.apply_full_recess_bulk_plan(
                    plan_path,
                    plan_sha,
                    canonical_root=root,
                    apply_report_path=report_path,
                )
            report = scanner.apply_full_recess_bulk_plan(
                plan_path,
                plan_sha,
                canonical_root=root,
                apply_report_path=report_path,
                include_full_recess_repair=True,
            )
            self.assertEqual(report["modelsWritten"], 1)
            self.assertEqual(report["scalarTokensChanged"], 2)
            post = scanner.scan_model_file(
                root / "test" / "graph_coloring_nudge.json", base=root
            )
            self.assertEqual(
                [
                    finding
                    for finding in post["findings"]
                    if finding["orientation"] == "same"
                    and finding["plane_quality"] == "exact"
                ],
                [],
            )
            with self.assertRaisesRegex(scanner.ModelAuditError, "stale|manifest"):
                scanner.apply_full_recess_bulk_plan(
                    plan_path,
                    plan_sha,
                    canonical_root=root,
                    apply_report_path=report_path,
                    include_full_recess_repair=True,
                )

    def test_full_recess_translates_both_endpoints_of_a_flat_face_plane(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, before = self.prepare_bulk_root(
                Path(temporary), ["zero_thickness.json"]
            )
            plan = scanner.generate_bulk_nudge_plan(
                canonical_root=root, include_full_recess_repair=True
            )
            self.assertEqual(plan["summary"]["eligibleFindings"], 2)
            self.assertEqual(plan["summary"]["blockedExactSameFacingFindings"], 0)
            self.assertEqual(plan["summary"]["predictedExactSameFacingFindingsAfter"], 0)
            operations = plan["models"][0]["operations"]
            self.assertEqual(len(operations), 2)
            self.assertTrue(
                all(
                    operation["action"] == "translate_zero_thickness_face_plane"
                    for operation in operations
                )
            )
            self.assertEqual(
                {operation["jsonPointer"] for operation in operations},
                {"/elements/0/from/2", "/elements/0/to/2"},
            )
            self.assertEqual(
                (root / "test" / "zero_thickness.json").read_bytes(),
                before["zero_thickness.json"],
            )

    def test_full_recess_transaction_rolls_back_all_models_on_write_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            root = temporary_path / "canonical-models"
            source = (FIXTURES / "graph_coloring_nudge.json").read_bytes()
            first = root / "test" / "a.json"
            second = root / "test" / "b.json"
            first.parent.mkdir(parents=True)
            first.write_bytes(source)
            second.write_bytes(source)
            plan_path = temporary_path / "reports" / "full-plan.json"
            _, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=root,
                include_full_recess_repair=True,
            )
            original_atomic_write = scanner.atomic_write_bytes

            def fail_second_model(path: Path, payload: bytes) -> None:
                if Path(path).resolve() == second.resolve() and payload != source:
                    raise OSError("injected second-model failure")
                original_atomic_write(path, payload)

            with mock.patch.object(scanner, "atomic_write_bytes", side_effect=fail_second_model):
                with self.assertRaisesRegex(OSError, "injected"):
                    scanner.apply_full_recess_bulk_plan(
                        plan_path,
                        plan_sha,
                        canonical_root=root,
                        apply_report_path=temporary_path / "reports" / "apply.json",
                        include_full_recess_repair=True,
                    )
            self.assertEqual(first.read_bytes(), source)
            self.assertEqual(second.read_bytes(), source)

    def test_full_recess_raw_grouped_overlap_applies_and_preserves_membership(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            standard_root = temporary_path / "standard-models"
            standard_root.mkdir()
            raw_root, raw_model, _ = self.prepare_registered_raw_root(
                temporary_path, "raw_nested_group_overlap.json"
            )
            before_document = json.loads(raw_model.read_text(encoding="utf-8"))
            plan_path = temporary_path / "reports" / "raw-full-plan.json"
            report_path = temporary_path / "reports" / "raw-full-apply.json"
            plan, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                include_full_recess_repair=True,
            )
            self.assertEqual(plan["summary"]["registeredRawFilesScanned"], 43)
            self.assertEqual(plan["summary"]["rawExactSameFacingFindingsBefore"], 1)
            self.assertEqual(plan["summary"]["predictedRawExactSameFacingFindingsAfter"], 0)
            self.assertEqual(plan["summary"]["predictedUvRegressions"], 0)
            raw_plan = plan["models"][0]
            self.assertEqual(raw_plan["sourceKind"], "raw_authoring")
            self.assertEqual(raw_plan["rawAuthoring"]["groupedElementReferences"], 2)

            report = scanner.apply_full_recess_bulk_plan(
                plan_path,
                plan_sha,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                apply_report_path=report_path,
                include_full_recess_repair=True,
            )
            self.assertEqual(report["modelsWritten"], 1)
            after_document = json.loads(raw_model.read_text(encoding="utf-8"))
            self.assertEqual(after_document["groups"], before_document["groups"])
            self.assertEqual(
                after_document["elements"][0]["faces"]["north"]["erydon_uv_offset"],
                [0, 0],
            )
            after_source = raw_model.read_bytes()
            after_audit = scanner.scan_model_document(
                after_document,
                relative_path=raw_plan["canonicalPath"],
                source_sha256=hashlib.sha256(after_source).hexdigest(),
                raw_authoring=True,
                enable_raw_transforms=True,
            )
            self.assertEqual(
                [
                    finding
                    for finding in after_audit["findings"]
                    if finding["orientation"] == "same"
                    and finding["plane_quality"] == "exact"
                ],
                [],
            )
            self.assertEqual(
                after_audit["rawAuthoring"], raw_plan["rawAuthoring"]
            )

    def test_full_recess_raw_group_membership_change_stales_dual_root_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            standard_root = temporary_path / "standard-models"
            standard_root.mkdir()
            raw_root, raw_model, _ = self.prepare_registered_raw_root(
                temporary_path, "raw_nested_group_overlap.json"
            )
            plan_path = temporary_path / "reports" / "raw-full-plan.json"
            _, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                include_full_recess_repair=True,
            )
            changed = json.loads(raw_model.read_text(encoding="utf-8"))
            changed["groups"][0]["children"][0]["children"] = [0]
            raw_model.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaisesRegex(scanner.ModelAuditError, "stale|manifest"):
                scanner.validate_bulk_nudge_plan(
                    plan_path,
                    plan_sha,
                    canonical_root=standard_root,
                    raw_canonical_root=raw_root,
                    include_full_recess_repair=True,
                )

    def test_full_recess_raw_cull_boundary_change_is_disclosed_not_hidden(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            standard_root = temporary_path / "standard-models"
            standard_root.mkdir()
            raw_root, raw_model, _ = self.prepare_registered_raw_root(
                temporary_path, "raw_cull_boundary_overlap.json"
            )
            plan_path = temporary_path / "reports" / "cull-full-plan.json"
            report_path = temporary_path / "reports" / "cull-full-apply.json"
            plan, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                include_full_recess_repair=True,
            )
            self.assertEqual(plan["summary"]["rawCullfacesAuthored"], 2)
            self.assertEqual(plan["summary"]["rawCullBoundaryEligibleBefore"], 2)
            self.assertEqual(
                plan["summary"]["predictedRawCullBoundaryEligibleAfter"], 2
            )
            self.assertEqual(plan["summary"]["rawCullfacesPhysicallyMovedOffBoundary"], 1)
            self.assertEqual(plan["summary"]["rawCullBoundaryOverrideInsertions"], 1)
            self.assertEqual(plan["summary"]["rawCullfacesNewlyUnculled"], 0)
            self.assertEqual(plan["summary"]["predictedRawCullfacesNewlyHidden"], 0)
            cull = plan["models"][0]["rawCullBoundary"]
            self.assertEqual(cull["physicallyMovedOffBoundaryFaceCount"], 1)
            self.assertEqual(cull["overrideInsertionCount"], 1)
            self.assertEqual(cull["newlyUnculledFaceCount"], 0)
            self.assertEqual(cull["newlyHiddenFaceCount"], 0)
            insertion = plan["models"][0]["rawCullBoundaryOverrideInsertions"][0]
            self.assertTrue(insertion["expectedAbsent"])
            self.assertIs(insertion["proposedValue"], True)

            report = scanner.apply_full_recess_bulk_plan(
                plan_path,
                plan_sha,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                apply_report_path=report_path,
                include_full_recess_repair=True,
            )
            self.assertEqual(report["objectMembersInserted"], 1)
            self.assertEqual(report["scalarTokensChanged"], 1)
            applied = json.loads(raw_model.read_text(encoding="utf-8"))
            overrides = [
                face.get("erydon_cull_boundary_override")
                for element in applied["elements"]
                for face in element["faces"].values()
            ]
            self.assertEqual(overrides.count(True), 1)
            self.assertEqual(
                sum(scanner._raw_cull_boundary_state(applied).values()), 2
            )

    def test_full_recess_raw_effective_uv_offset_regression_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            standard_root = temporary_path / "standard-models"
            standard_root.mkdir()
            raw_root, _, _ = self.prepare_registered_raw_root(
                temporary_path, "raw_uv_offset_regression_overlap.json"
            )
            plan = scanner.generate_bulk_nudge_plan(
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                include_full_recess_repair=True,
            )
            self.assertEqual(plan["summary"]["eligibleRawModels"], 0)
            self.assertEqual(plan["summary"]["blockedModels"], 1)
            self.assertEqual(plan["blockedComponents"][0]["reasons"], ["uv_regression"])
            diagnostics = plan["blockedComponents"][0]["diagnostics"]
            self.assertEqual(diagnostics["uvRegressionsCount"], 1)

    def test_full_recess_dual_root_transaction_rolls_back_raw_and_standard(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            standard_root, standard_before = self.prepare_bulk_root(
                temporary_path, ["graph_coloring_nudge.json"]
            )
            standard_model = standard_root / "test" / "graph_coloring_nudge.json"
            raw_root, raw_model, raw_before = self.prepare_registered_raw_root(
                temporary_path, "raw_cull_boundary_overlap.json"
            )
            plan_path = temporary_path / "reports" / "dual-full-plan.json"
            _, plan_sha = scanner.write_bulk_nudge_plan(
                plan_path,
                canonical_root=standard_root,
                raw_canonical_root=raw_root,
                include_full_recess_repair=True,
            )
            original_atomic_write = scanner.atomic_write_bytes

            def fail_standard_after_raw(path: Path, payload: bytes) -> None:
                if (
                    Path(path).resolve() == standard_model.resolve()
                    and payload != standard_before["graph_coloring_nudge.json"]
                ):
                    raise OSError("injected dual-root failure")
                original_atomic_write(path, payload)

            with mock.patch.object(
                scanner, "atomic_write_bytes", side_effect=fail_standard_after_raw
            ):
                with self.assertRaisesRegex(OSError, "dual-root"):
                    scanner.apply_full_recess_bulk_plan(
                        plan_path,
                        plan_sha,
                        canonical_root=standard_root,
                        raw_canonical_root=raw_root,
                        apply_report_path=temporary_path / "reports" / "apply.json",
                        include_full_recess_repair=True,
                    )
            self.assertEqual(
                standard_model.read_bytes(),
                standard_before["graph_coloring_nudge.json"],
            )
            self.assertEqual(
                raw_model.read_bytes(),
                raw_before["alcove/alcove_georgian_double_side_left.json"],
            )

    def test_embedded_self_test(self) -> None:
        scanner.run_self_test()


if __name__ == "__main__":
    unittest.main()
