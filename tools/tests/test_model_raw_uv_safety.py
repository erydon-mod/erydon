from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOLS = Path(__file__).resolve().parents[1]
PROJECT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import model_raw_uv_safety as raw_uv
from model_geometry_common import ModelAuditError


def _write_json(path: Path, document: object, *, compact: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = (
        json.dumps(document, separators=(",", ":"), ensure_ascii=False)
        if compact
        else json.dumps(document, indent=2, ensure_ascii=False)
    )
    path.write_text(text + "\n", encoding="utf-8", newline="")


def _write_loader(path: Path, canonical_paths: list[str]) -> None:
    entries = "\n".join(
        "    Map.entry(\"fixture/%d\", new Identifier(Erydon.MOD_ID, "
        "\"authoring_models/block/%s\"))," % (index, canonical_path)
        for index, canonical_path in enumerate(canonical_paths)
    )
    path.write_text(
        "final class Fixture {\n"
        "  Object AUTHORING_MODELS = Map.ofEntries(\n"
        f"{entries}\n"
        "  );\n"
        "}\n",
        encoding="utf-8",
        newline="",
    )


def _complete_fixture() -> dict[str, object]:
    return {
        "credit": "untouched sentinel",
        "textures": {"stone": "erydon:block/stone"},
        "custom": {"keep": [1, 2, 3]},
        "elements": [
            {
                "name": "whole sprite explicit",
                "from": [0, 0, 0],
                "to": [2, 2, 2],
                "rotation": {"axis": "y", "angle": 22.5, "origin": [8, 8, 8]},
                "faces": {"north": {"texture": "#stone", "uv": [18, 0, 20, 2]}},
            },
            {
                "name": "boundary explicit",
                "from": [0, 0, 0],
                "to": [2, 2, 2],
                "rotation": {"angles": [5, 22.5, 2], "origin": [8, 8, 8]},
                "faces": {"south": {"texture": "#stone", "uv": [-1, 0, 1, 2]}},
            },
            {
                "name": "implicit group Euler",
                "from": [15, 0, 0],
                "to": [17, 2, 2],
                "faces": {"up": {"texture": "#stone"}},
            },
        ],
        "groups": [
            {
                "name": "outer",
                "origin": [8, 8, 8],
                "rotation": {"angles": [10, 0, 0]},
                "children": [
                    {
                        "name": "inner",
                        "origin": [8, 8, 8],
                        "rotation": {"angle": [0, 22.5, 5]},
                        "children": [2],
                    }
                ],
            }
        ],
    }


class RawUvSafetyTests(unittest.TestCase):
    def test_gothic_column_components_use_safe_implicit_world_uvs(self) -> None:
        component_root = (
            PROJECT
            / "src/main/resources/assets/erydon/authoring_models/block/column/gothic"
        )
        total_faces = 0
        for name in ("plinth", "base", "pillar", "capital"):
            path = component_root / f"column_gothic_{name}.json"
            source = path.read_bytes()
            document = json.loads(source.decode("utf-8"))
            findings, counts = raw_uv._audit_document(
                document,
                f"column/gothic/{path.name}",
                hashlib.sha256(source).hexdigest(),
            )
            self.assertEqual(0, counts.get("explicitFaces", 0), path.name)
            self.assertEqual(0, counts.get("outOfRangeFaces", 0), path.name)
            self.assertEqual([], findings, path.name)
            total_faces += counts.get("facesScanned", 0)
        self.assertEqual(2592, total_faces)

    def _workspace(self, documents: dict[str, object]) -> tuple[tempfile.TemporaryDirectory[str], Path, Path]:
        temporary = tempfile.TemporaryDirectory()
        base = Path(temporary.name)
        root = base / "raw"
        for canonical_path, document in documents.items():
            _write_json(root / canonical_path, document, compact=canonical_path.startswith("compact"))
        loader = base / "ErydonRawModelLoadingPlugin.java"
        _write_loader(loader, sorted(documents))
        return temporary, root, loader

    def _write_plan(self, base: Path, audit: dict[str, object]) -> tuple[Path, str, dict[str, object]]:
        plan = raw_uv.generate_plan(audit)
        plan_path = base / "raw-uv-plan.json"
        payload = (json.dumps(plan, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        plan_path.write_bytes(payload)
        return plan_path, hashlib.sha256(payload).hexdigest(), plan

    def test_audit_has_all_three_complete_operation_classes(self) -> None:
        temporary, root, loader = self._workspace({"fixture.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)

        audit = raw_uv.audit_models(root, loader_source=loader)

        self.assertEqual(1, audit["summary"]["filesDiscovered"])
        self.assertEqual(3, audit["summary"]["rotatedOutOfRangeFaces"])
        self.assertEqual(1, audit["summary"]["explicitInteger16Candidates"])
        self.assertEqual(1, audit["summary"]["explicitBoundaryReanchors"])
        self.assertEqual(1, audit["summary"]["implicitOffsetCandidates"])
        self.assertEqual(0, audit["summary"]["unresolvedRotatedFaces"])
        plan = raw_uv.generate_plan(audit)
        self.assertEqual(
            {
                "explicitInteger16Shifts": 1,
                "explicitBoundaryUniformReanchors": 1,
                "implicitUniformOffsets": 1,
                "total": 3,
            },
            plan["operationCounts"],
        )
        implicit = next(operation for operation in plan["operations"] if operation["uvSource"] == "implicit")
        self.assertIsNone(implicit["expectedExplicitUv"])
        self.assertIsNone(implicit["proposedExplicitUv"])
        self.assertEqual("implicit_uniform_offset", implicit["operationClass"])

    def test_group_rotations_are_inner_to_outer_after_element_rotation(self) -> None:
        document = _complete_fixture()
        rotations = raw_uv._collect_group_rotations(document)
        self.assertEqual([(0.0, 22.5, 5.0), (10.0, 0.0, 0.0)], [rotation.degrees for rotation in rotations[2]])

        element = document["elements"][2]
        element_rotation = raw_uv.RawRotation.parse(
            {"angles": [0, 0, 7]}, (8, 8, 8), "fixture element rotation"
        )
        vertex = (15.0, 2.0, 0.0)
        expected = rotations[2][1].transform(rotations[2][0].transform(element_rotation.transform(vertex)))
        wrong_order = element_rotation.transform(rotations[2][0].transform(rotations[2][1].transform(vertex)))
        self.assertNotEqual(expected, wrong_order)

    def test_element_and_nested_group_euler_chain_has_golden_float_uvs(self) -> None:
        document = _complete_fixture()
        document["elements"][2]["rotation"] = {"angles": [3, 4, 7], "origin": [8, 8, 8]}
        findings, _ = raw_uv._audit_document(document, "fixture.json", "0" * 64)
        finding = next(row for row in findings if row["elementIndex"] == 2)
        self.assertEqual("up", finding["nominalFace"])
        self.assertEqual(3, finding["rotationCount"])
        expected = [
            11.545252799987793,
            -3.4060230255126953,
            12.451395034790039,
            -1.6515617370605469,
            14.199573516845703,
            -2.4558544158935547,
            13.29343318939209,
            -4.210315704345703,
        ]
        for actual, golden in zip(finding["effectiveUvBefore"], expected):
            self.assertAlmostEqual(golden, actual, places=6)

    def test_malformed_non_string_axis_follows_loader_identity_fallback(self) -> None:
        rotation = raw_uv.RawRotation.parse({"axis": 123, "angle": 22.5}, (8, 8, 8), "rotation")
        self.assertTrue(rotation.identity)
        self.assertEqual((1.0, 2.0, 3.0), rotation.transform((1.0, 2.0, 3.0)))

    def test_rotation_identity_threshold_is_the_exact_java_float_constant(self) -> None:
        # Both the authored angle and Java's 0.0005F constant round to the same
        # float32 even though the JSON decimal is slightly larger than 0.0005.
        rotation = raw_uv.RawRotation.parse([0.00050000001, 0, 0], (8, 8, 8), "rotation")
        self.assertEqual(raw_uv.LOADER_EPSILON, rotation.degrees[0])
        self.assertTrue(rotation.identity)

    def test_joml_fused_float_math_does_not_regress_to_two_roundings(self) -> None:
        a = raw_uv._f32(10.644217491149902)
        b = raw_uv._f32(-29.01972198486328)
        c = raw_uv._f32(91.61296081542969)
        fused = raw_uv._ffma(a, b, c)
        unfused = raw_uv._fadd(raw_uv._fmul(a, b), c)
        self.assertEqual(-217.27926635742188, fused)
        self.assertEqual(-217.27928161621094, unfused)
        self.assertNotEqual(fused, unfused)

    def test_apply_repairs_explicit_tokens_and_keeps_implicit_face_implicit(self) -> None:
        temporary, root, loader = self._workspace({"fixture.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        source = root / "fixture.json"
        before = source.read_bytes()
        audit = raw_uv.audit_models(root, loader_source=loader)
        plan_path, plan_sha, _ = self._write_plan(base, audit)

        report = raw_uv.apply_plan(
            plan_path,
            plan_sha,
            raw_root=root,
            loader_source=loader,
            apply_report_path=base / "apply.json",
        )

        self.assertEqual(3, report["operationCounts"]["total"])
        self.assertNotEqual(before, source.read_bytes())
        after = json.loads(source.read_text(encoding="utf-8"))
        for index in (0, 1):
            face = next(iter(after["elements"][index]["faces"].values()))
            self.assertNotIn(raw_uv.OFFSET_KEY, face)
            self.assertTrue(all(0 <= value <= 16 for value in face["uv"][:4]))
        implicit_face = after["elements"][2]["faces"]["up"]
        self.assertNotIn("uv", implicit_face)
        self.assertEqual(2, len(implicit_face[raw_uv.OFFSET_KEY]))
        self.assertEqual("untouched sentinel", after["credit"])
        self.assertEqual({"keep": [1, 2, 3]}, after["custom"])

        post = raw_uv.audit_models(root, loader_source=loader)
        self.assertEqual(0, post["summary"]["rotatedOutOfRangeFaces"])
        self.assertEqual(0, raw_uv.generate_plan(post)["operationCounts"]["total"])
        with self.assertRaisesRegex(ModelAuditError, "stale|tampered|incomplete"):
            raw_uv.apply_plan(
                plan_path,
                plan_sha,
                raw_root=root,
                loader_source=loader,
                apply_report_path=base / "second-apply.json",
            )

    def test_tampered_plan_is_rejected_with_original_and_recomputed_hash(self) -> None:
        temporary, root, loader = self._workspace({"fixture.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        audit = raw_uv.audit_models(root, loader_source=loader)
        plan_path, original_sha, plan = self._write_plan(base, audit)
        before = (root / "fixture.json").read_bytes()

        plan["operations"][0]["uniformShift"]["u"] = 123
        tampered = (json.dumps(plan, indent=2) + "\n").encode("utf-8")
        plan_path.write_bytes(tampered)
        with self.assertRaisesRegex(ModelAuditError, "SHA-256 mismatch"):
            raw_uv.apply_plan(
                plan_path,
                original_sha,
                raw_root=root,
                loader_source=loader,
                apply_report_path=base / "apply-a.json",
            )
        tampered_sha = hashlib.sha256(tampered).hexdigest()
        with self.assertRaisesRegex(ModelAuditError, "stale|tampered|incomplete"):
            raw_uv.apply_plan(
                plan_path,
                tampered_sha,
                raw_root=root,
                loader_source=loader,
                apply_report_path=base / "apply-b.json",
            )
        self.assertEqual(before, (root / "fixture.json").read_bytes())

    def test_stale_source_lock_is_rejected_without_writes(self) -> None:
        temporary, root, loader = self._workspace({"fixture.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        plan_path, plan_sha, _ = self._write_plan(base, raw_uv.audit_models(root, loader_source=loader))
        source = root / "fixture.json"
        source.write_bytes(source.read_bytes() + b" \n")
        stale = source.read_bytes()
        with self.assertRaisesRegex(ModelAuditError, "stale|tampered|incomplete"):
            raw_uv.apply_plan(
                plan_path,
                plan_sha,
                raw_root=root,
                loader_source=loader,
                apply_report_path=base / "apply.json",
            )
        self.assertEqual(stale, source.read_bytes())

    def test_loader_source_sha_drift_is_rejected_without_model_writes(self) -> None:
        temporary, root, loader = self._workspace({"fixture.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        plan_path, plan_sha, _ = self._write_plan(base, raw_uv.audit_models(root, loader_source=loader))
        source = root / "fixture.json"
        before = source.read_bytes()
        loader.write_text(loader.read_text(encoding="utf-8") + "// harmless loader drift\n", encoding="utf-8")

        with self.assertRaisesRegex(ModelAuditError, "stale|tampered|incomplete|loader"):
            raw_uv.apply_plan(
                plan_path,
                plan_sha,
                raw_root=root,
                loader_source=loader,
                apply_report_path=base / "apply.json",
            )
        self.assertEqual(before, source.read_bytes())

    def test_multi_file_failure_rolls_back_every_written_source(self) -> None:
        first = _complete_fixture()
        second = copy.deepcopy(_complete_fixture())
        second["elements"] = second["elements"][:1]
        second.pop("groups")
        temporary, root, loader = self._workspace({"a.json": first, "compact-b.json": second})
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        plan_path, plan_sha, _ = self._write_plan(base, raw_uv.audit_models(root, loader_source=loader))
        originals = {path.name: path.read_bytes() for path in root.glob("*.json")}
        real_atomic_write = raw_uv.atomic_write_bytes
        calls = 0

        def fail_second_write(path: Path, payload: bytes) -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("injected second-file failure")
            real_atomic_write(path, payload)

        with mock.patch.object(raw_uv, "atomic_write_bytes", side_effect=fail_second_write):
            with self.assertRaisesRegex(OSError, "injected second-file failure"):
                raw_uv.apply_plan(
                    plan_path,
                    plan_sha,
                    raw_root=root,
                    loader_source=loader,
                    apply_report_path=base / "apply.json",
                )
        self.assertGreaterEqual(calls, 3)
        self.assertEqual(originals, {path.name: path.read_bytes() for path in root.glob("*.json")})

    def test_malformed_offset_and_explicit_offset_are_rejected(self) -> None:
        malformed = _complete_fixture()
        malformed["elements"][2]["faces"]["up"][raw_uv.OFFSET_KEY] = [1, 2, 3]
        temporary, root, loader = self._workspace({"fixture.json": malformed})
        self.addCleanup(temporary.cleanup)
        with self.assertRaisesRegex(ModelAuditError, "exactly 2"):
            raw_uv.audit_models(root, loader_source=loader)

        explicit = _complete_fixture()
        explicit["elements"][0]["faces"]["north"][raw_uv.OFFSET_KEY] = [0, 0]
        temporary2, root2, loader2 = self._workspace({"fixture.json": explicit})
        self.addCleanup(temporary2.cleanup)
        with self.assertRaisesRegex(ModelAuditError, "explicit.*implicit-only"):
            raw_uv.audit_models(root2, loader_source=loader2)

    def test_offset_rejects_boolean_and_numeric_string_primitives(self) -> None:
        for invalid in ([True, 0], ["1", 0]):
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(ModelAuditError, "finite JSON numbers"):
                    raw_uv._parse_offset({raw_uv.OFFSET_KEY: invalid}, "face")

        with self.assertRaisesRegex(ModelAuditError, "boolean"):
            raw_uv._vector([True, 0, 0], 3, "generic loader vector")
        self.assertEqual((1.0, 0.0, 0.0), raw_uv._vector(["1", 0, 0], 3, "generic loader vector"))

    def test_registered_source_list_must_match_complete_root(self) -> None:
        temporary, root, loader = self._workspace({"registered.json": _complete_fixture()})
        self.addCleanup(temporary.cleanup)
        _write_json(root / "unregistered.json", {"elements": []})
        with self.assertRaisesRegex(ModelAuditError, "source-list parity"):
            raw_uv.audit_models(root, loader_source=loader)


if __name__ == "__main__":
    unittest.main()
