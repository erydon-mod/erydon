from __future__ import annotations

import copy
import json
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import model_z_fighting_safety as scanner
import model_raw_uv_safety as raw_uv
import georgian_alcove_coplanar as global_coplanar


class RawNormalOffsetSafetyTests(unittest.TestCase):
    @staticmethod
    def rotated_model() -> dict:
        return {
            "elements": [{
                "from": [2, 3, 4],
                "to": [6, 8, 10],
                "origin": [4, 5, 6],
                "rotation": [17, 31, -22],
                "faces": {"south": {"texture": "#stone"}},
            }],
            "groups": [{"origin": [7, 8, 9], "rotation": [9, -13, 7], "children": [0]}],
        }

    @staticmethod
    def faces(model: dict):
        rotations, _ = scanner._raw_group_state(model, len(model["elements"]))
        return scanner._build_faces(model["elements"], raw_group_rotations=rotations)

    def test_offset_follows_transformed_face_normal_and_keeps_authored_bounds(self):
        model = self.rotated_model()
        original = copy.deepcopy(model)
        before, unsupported = self.faces(model)
        self.assertEqual(unsupported, [])
        model["elements"][0]["faces"]["south"]["erydon_normal_offset"] = 0.02
        after, unsupported = self.faces(model)
        self.assertEqual(unsupported, [])
        for first, second in zip(before[0].vertices, after[0].vertices):
            for axis in range(3):
                self.assertAlmostEqual(second[axis], first[axis] + before[0].normal[axis] * 0.02)
        self.assertAlmostEqual(after[0].plane_d - before[0].plane_d, 0.02)
        self.assertAlmostEqual(after[0].area, before[0].area)
        self.assertEqual(model["elements"][0]["from"], original["elements"][0]["from"])
        self.assertEqual(model["elements"][0]["to"], original["elements"][0]["to"])

    def test_default_uv_audit_uses_geometry_before_normal_offset(self):
        model = self.rotated_model()
        before = scanner._effective_uv_range_distances(model, raw_authoring=True)
        model["elements"][0]["faces"]["south"]["erydon_normal_offset"] = 0.05
        self.assertEqual(before, scanner._effective_uv_range_distances(model, raw_authoring=True))

    def test_offset_removes_exact_coplanar_finding_in_runtime_geometry(self):
        model = {"elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"south": {"texture": "#stone"}}},
            {"from": [2, 2, 0], "to": [14, 14, 16], "faces": {"south": {"texture": "#stone"}}},
        ]}
        args = {"relative_path": "normal_offset_test", "source_sha256": "", "raw_authoring": True,
                "enable_raw_transforms": True}
        self.assertEqual(len(scanner.scan_model_document(model, **args)["findings"]), 1)
        model["elements"][1]["faces"]["south"]["erydon_normal_offset"] = 0.002
        self.assertEqual(scanner.scan_model_document(model, **args)["findings"], [])

    def test_scanner_rejects_invalid_offsets(self):
        for value in (None, True, "0.01", [0.01], {}, -0.001, 0.050000001, math.nan, math.inf):
            with self.subTest(value=value):
                model = self.rotated_model()
                model["elements"][0]["faces"]["south"]["erydon_normal_offset"] = value
                faces, unsupported = self.faces(model)
                self.assertEqual(faces, [])
                self.assertEqual(len(unsupported), 1)
                self.assertIn("erydon_normal_offset", unsupported[0]["reason"])

    def test_cull_boundary_audit_matches_offset_runtime_vertices(self):
        model = {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                               "faces": {"south": {"texture": "#stone", "cullface": "south"}}}]}
        pointer = "/elements/0/faces/south"
        self.assertTrue(scanner._raw_cull_boundary_state(model)[pointer])
        model["elements"][0]["faces"]["south"]["erydon_normal_offset"] = 0.002
        self.assertFalse(scanner._raw_cull_boundary_state(model)[pointer])
        model["elements"][0]["faces"]["south"]["erydon_cull_boundary_override"] = True
        self.assertTrue(scanner._raw_cull_boundary_state(model)[pointer])


class RawFaceVerticesSafetyTests(unittest.TestCase):
    @staticmethod
    def model() -> dict:
        return {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                              "faces": {"south": {"texture": "#stone",
                                                   "erydon_vertices": [[2, 3, 10], [12, 4, 10],
                                                                        [10, 9, 10], [4, 8, 10]]}}}]}

    @staticmethod
    def faces(model: dict):
        rotations, _ = scanner._raw_group_state(model, len(model["elements"]))
        return scanner._build_faces(model["elements"], raw_group_rotations=rotations)

    def test_scanner_uses_trapezoid_instead_of_element_bounding_box(self):
        model = self.model()
        faces, unsupported = self.faces(model)
        self.assertEqual(unsupported, [])
        self.assertEqual(faces[0].vertices, ((2, 3, 10), (12, 4, 10), (10, 9, 10), (4, 8, 10)))
        self.assertAlmostEqual(faces[0].area, 40)
        model["elements"][0]["faces"]["south"]["erydon_normal_offset"] = 0.02
        shifted, unsupported = self.faces(model)
        self.assertEqual(unsupported, [])
        self.assertAlmostEqual(shifted[0].area, 40)
        self.assertTrue(all(abs(vertex[2] - 10.02) < 1e-9 for vertex in shifted[0].vertices))

    def test_vertex_override_obeys_element_then_group_rotation(self):
        model = self.model()
        model["elements"][0]["origin"] = [0, 0, 0]
        model["elements"][0]["rotation"] = [0, 0, 90]
        model["groups"] = [{"origin": [0, 0, 0], "rotation": [0, 90, 0], "children": [0]}]
        faces, unsupported = self.faces(model)
        self.assertEqual(unsupported, [])
        expected = ((10, 2, 3), (10, 12, 4), (10, 10, 9), (10, 4, 8))
        for before, after in zip(expected, faces[0].vertices):
            for a, b in zip(before, after):
                self.assertAlmostEqual(a, b)
        self.assertAlmostEqual(faces[0].normal[0], 1)
        findings, counts = raw_uv._audit_document(model, "quad.json", "test")
        self.assertEqual(findings, [])
        self.assertEqual(counts["rotatedFaces"], 1)

    def test_uv_and_cull_audits_use_face_vertices_not_the_cube_box(self):
        model = self.model()
        face = model["elements"][0]["faces"]["south"]
        face["cullface"] = "south"
        self.assertFalse(scanner._raw_cull_boundary_state(model)["/elements/0/faces/south"])
        face["erydon_vertices"] = [[-2, -1, 16.45], [18, 2, 16.45], [15, 17, 16.45], [0, 15, 16.45]]
        findings, counts = raw_uv._audit_document(model, "quad.json", "test")
        self.assertEqual(counts["outOfRangeFaces"], 1)
        self.assertEqual(findings[0]["uRange"], [-2, 18])
        self.assertEqual(findings[0]["vRange"], [-1, 17])

    def test_invalid_vertex_overrides_are_rejected_by_both_audits(self):
        valid = [[2, 3, 10], [12, 4, 10], [10, 9, 10], [4, 8, 10]]
        invalid = [None, True, [], valid[:3], valid + [[0, 0, 0]],
                   [[0, 0], *valid[1:]], [[0, 0, 0, 0], *valid[1:]],
                   [["0", 0, 0], *valid[1:]], [[False, 0, 0], *valid[1:]],
                   [[math.inf, 0, 0], *valid[1:]], [[math.nan, 0, 0], *valid[1:]],
                   [[1e100, 0, 0], *valid[1:]]]
        for vertices in invalid:
            with self.subTest(vertices=vertices):
                model = self.model()
                model["elements"][0]["faces"]["south"]["erydon_vertices"] = vertices
                faces, unsupported = self.faces(model)
                self.assertEqual(faces, [])
                self.assertEqual(len(unsupported), 1)
                with self.assertRaises(raw_uv.ModelAuditError):
                    raw_uv._audit_document(model, "invalid.json", "test")


class GeorgianPlacedGeometrySafetyTests(unittest.TestCase):
    @staticmethod
    def raw_root() -> Path:
        return Path(__file__).resolve().parents[2] / "src/main/resources/assets/erydon/authoring_models/block/alcove"

    def test_actual_georgian_child_chains_have_no_same_plane_fighting_in_any_part_or_span(self):
        checked = 0
        root = self.raw_root()
        for width in (1, 2, 3):
            prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
            for column in range(width):
                side = "left" if column == 0 else "right" if column == width - 1 else "center"
                sides = "single_sides" if width == 1 else prefix + "side_" + side
                crown = "single_top" if width == 1 else prefix + "top_" + side
                for part in ("base", "middle", "top", "single"):
                    checked += 1
                    with self.subTest(width=width, column=column, part=part):
                        suffixes = ["single_back"]
                        if part in ("single", "top"):
                            suffixes.append(crown)
                        else:
                            suffixes.append(sides)
                            if column == 0:
                                suffixes.append(prefix + part + "_detail_left")
                            if column == width - 1:
                                suffixes.append(prefix + part + "_detail_right")
                        elements = []
                        for suffix in suffixes:
                            document = json.loads((root / f"alcove_georgian_{suffix}.json").read_text(encoding="utf-8"))
                            elements.extend(document["elements"])
                        report = scanner.scan_model_document(
                            {"elements": elements}, relative_path=f"georgian/{width}/{column}/{part}",
                            source_sha256="", raw_authoring=True, enable_raw_transforms=True,
                        )
                        self.assertEqual(report["unsupported"], [])
                        conflicts = [finding for finding in report["findings"]
                                     if finding["orientation"] == "same" and finding["plane_quality"] == "exact"]
                        pairs = [(f["a"], f["b"]) for f in conflicts[:3]]
                        self.assertFalse(conflicts, f"{len(conflicts)} conflicts in {suffixes}: {pairs}")
        self.assertEqual(checked, 24)

    def test_every_generated_georgian_child_has_bounded_face_offsets_and_no_internal_conflicts(self):
        checked = 0
        for path in self.raw_root().glob("alcove_georgian_*.json"):
            checked += 1
            with self.subTest(model=path.name):
                document = json.loads(path.read_text(encoding="utf-8"))
                for element in document["elements"]:
                    for face in element["faces"].values():
                        self.assertGreaterEqual(face.get("erydon_normal_offset", 0), 0)
                        self.assertLessEqual(face.get("erydon_normal_offset", 0), .004)
                report = scanner.scan_model_document(
                    document, relative_path=path.name, source_sha256="",
                    raw_authoring=True, enable_raw_transforms=True,
                )
                expected_unsupported = ([{"element": 0, "reason": "zero-thickness element is unsupported"}]
                                        if path.name == "alcove_georgian_single_base.json" else [])
                self.assertEqual(report["unsupported"], expected_unsupported)
                conflicts = [finding for finding in report["findings"]
                             if finding["orientation"] == "same" and finding["plane_quality"] == "exact"]
                self.assertFalse(conflicts, f"{len(conflicts)} conflicts in {path.name}")
        self.assertEqual(checked, 27)

    @staticmethod
    def translated(element: dict, column: int, row: int) -> dict:
        result = copy.deepcopy(element)
        delta = (column * 16, row * 16, 0)
        for field in ("from", "to", "origin"):
            if field in result:
                result[field] = [result[field][axis] + delta[axis] for axis in range(3)]
        rotation = result.get("rotation")
        if isinstance(rotation, dict) and "origin" in rotation:
            rotation["origin"] = [rotation["origin"][axis] + delta[axis] for axis in range(3)]
        for face in result["faces"].values():
            if "erydon_vertices" in face:
                face["erydon_vertices"] = [[point[axis] + delta[axis] for axis in range(3)]
                                           for point in face["erydon_vertices"]]
        return result

    def test_full_clusters_keep_adjacent_crowns_and_repeated_body_rows_separate(self):
        root = self.raw_root()
        documents = {path.name: json.loads(path.read_text(encoding="utf-8"))
                     for path in root.glob("alcove_georgian_*.json")}
        checked = 0
        for width in (1, 2, 3):
            prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
            for height in (1, 2, 3, 4):
                checked += 1
                with self.subTest(width=width, height=height):
                    parts = ["single"] if height == 1 else ["base"] + ["middle"] * (height - 2) + ["top"]
                    elements = []
                    for row, part in enumerate(parts):
                        for column in range(width):
                            side = "left" if column == 0 else "right" if column == width - 1 else "center"
                            sides = "single_sides" if width == 1 else prefix + "side_" + side
                            crown = "single_top" if width == 1 else prefix + "top_" + side
                            suffixes = ["single_back", crown] if part in ("top", "single") else ["single_back", sides]
                            if part not in ("top", "single"):
                                if column == 0:
                                    suffixes.append(prefix + part + "_detail_left")
                                if column == width - 1:
                                    suffixes.append(prefix + part + "_detail_right")
                            for suffix in suffixes:
                                elements.extend(self.translated(element, column, row)
                                                for element in documents[f"alcove_georgian_{suffix}.json"]["elements"])
                    report = scanner.scan_model_document(
                        {"elements": elements}, relative_path=f"cluster/{width}/{height}", source_sha256="",
                        raw_authoring=True, enable_raw_transforms=True,
                    )
                    self.assertEqual(report["unsupported"], [])
                    conflicts = [finding for finding in report["findings"]
                                 if finding["orientation"] == "same" and finding["plane_quality"] == "exact"]
                    self.assertFalse(conflicts, f"{len(conflicts)} owner-cell conflicts in {width}x{height} cluster")
        self.assertEqual(checked, 12)

    def test_global_face_coloring_is_deterministic_and_preserves_all_design_geometry(self):
        models = {path.name: json.loads(path.read_text(encoding="utf-8"))
                  for path in self.raw_root().glob("alcove_georgian_*.json")}
        def without_bias(documents):
            copy_documents = copy.deepcopy(documents)
            for document in copy_documents.values():
                for element in document["elements"]:
                    for face in element["faces"].values():
                        face.pop("erydon_normal_offset", None)
            return copy_documents
        before = without_bias(models)
        stats = global_coplanar.separate(models)
        first = copy.deepcopy(models)
        self.assertEqual(before, without_bias(models), "Face separation changed authored geometry or mapping")
        self.assertEqual(stats["single_cell_states_checked"], 24)
        self.assertEqual(stats["cluster_layouts_checked"], 12)
        self.assertEqual(stats["remaining_exact_same_plane_overlaps"], 0)
        self.assertLess(stats["max_normal_offset"], .05)
        self.assertEqual(stats, global_coplanar.separate(models))
        self.assertEqual(first, models)


if __name__ == "__main__":
    unittest.main()
