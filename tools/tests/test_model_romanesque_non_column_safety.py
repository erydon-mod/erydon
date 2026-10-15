"""Regression coverage for editable base-setting geometry and owner splitting."""
from __future__ import annotations

from collections import defaultdict
import hashlib
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import derive_romanesque_non_column as non_column
import generate_romanesque_arch as arch
import export_romanesque_non_column_authoring as exporter

ROOT = TOOLS.parent


def surface_measure(elements):
    """Area and first moments grouped by oriented surface normal."""
    result = defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    for face in arch.faces(elements):
        vertices = face.vertices
        for i in range(1, len(vertices) - 1):
            triangle = [vertices[0], vertices[i], vertices[i + 1]]
            area = arch.polygon_area(triangle)
            key = tuple(round(value, 6) for value in face.normal)
            result[key][0] += area
            for axis in range(3):
                result[key][axis + 1] += area * sum(point[axis] for point in triangle) / 3
    return result


class GenericFaceImportTest(unittest.TestCase):
    def test_exporter_rejects_edited_master_before_loading_runtime_models(self):
        with tempfile.TemporaryDirectory(prefix="erydon-authoring-guard-test-") as temporary:
            root = Path(temporary)
            directory = root / exporter.DESTINATION
            directory.mkdir(parents=True)
            master = directory / (exporter.NAME + ".bbmodel")
            master.write_bytes(b'{"test":"edited"}')
            manifest = {"master": master.name, "editable_master_sha256": hashlib.sha256(b'{"test":"initial"}').hexdigest()}
            (directory / "non_column_assembly_manifest.json").write_text(exporter.json.dumps(manifest), encoding="utf-8")
            with mock.patch.object(exporter, "ROOT", root), mock.patch.object(sys, "argv", ["exporter", "--check"]), \
                    mock.patch.object(exporter, "output_files", side_effect=AssertionError("Edited master reached source export")) as output:
                with self.assertRaisesRegex(SystemExit, "refusing to overwrite"):
                    exporter.main()
                output.assert_not_called()
            self.assertEqual(master.read_bytes(), b'{"test":"edited"}')

    def test_unassigned_faces_remain_enabled_and_explicit_null_stays_disabled(self):
        cube = {"name": "untextured detail", "from": [0, 0, 0], "to": [1, 2, 3],
                "faces": {"north": {"uv": [4, 5, 6, 7]}, "south": {"texture": None},
                          "up": {"texture": 0, "cullface": "up"}}}
        result, = non_column.native_elements(cube)
        self.assertEqual(set(result["faces"]), {"north", "up"})
        self.assertNotIn("uv", result["faces"]["north"])
        self.assertEqual(result["faces"]["up"]["cullface"], "up")

    def test_generic_mesh_face_names_and_triangles_keep_winding(self):
        mesh = {"name": "cut keystone", "type": "mesh", "origin": [0, 0, 0],
                "vertices": {"a": [0, 0, 0], "b": [0, 1, 0], "c": [1, 1, 0]},
                "faces": {"editor_face_23": {"vertices": ["a", "b", "c"]}}}
        result, = non_column.native_elements(mesh)
        face, = arch.faces([result])
        self.assertEqual(face.face_name, "north")
        self.assertLess(math.dist(face.normal, [0, 0, -1]), 1e-8)
        self.assertEqual(face.vertices[2], face.vertices[3])


class DerivedNonColumnGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows, cls.report = non_column.read_master(ROOT)
        cls.models, _ = non_column.derive_components(ROOT, arch)

    def test_three_wide_partition_preserves_authored_visible_surface(self):
        original = [element for group in ("WALL__base_left", "WALL__body_left", "WALL__curve_lower_left",
                                         "WALL__crown_left", "CURVE__curve_lower_left", "CURVE__crown_left")
                    for element in self.rows[group]]
        placed = []
        for suffix, y in (("side_large_base", 0), ("side_large", 16), ("corner_large_lower", 32),
                          ("side_large_lower", 32), ("corner_large_upper", 48), ("side_large_crown", 48)):
            placed.extend(arch.placed_element(element, False, [0, y, 0])
                          for element in self.models["non_column_" + suffix]["elements"])

        before, after = surface_measure(original), surface_measure(placed)
        self.assertEqual(before.keys(), after.keys())
        for normal in before:
            for old, new in zip(before[normal], after[normal]):
                self.assertAlmostEqual(old, new, delta=max(1e-5, abs(old) * 1e-8))

    def test_repeatable_body_collision_stays_in_its_owner_height(self):
        for suffix in ("non_column_side_large", "non_column_side_medium", "non_column_side_small"):
            for box in arch.collision_boxes(self.models[suffix]["elements"]):
                self.assertGreaterEqual(box[1], 0)
                self.assertLessEqual(box[4], 16)

    def test_narrow_crown_has_no_faces_or_backing_volume_below_its_owner(self):
        for suffix in ("non_column_corner_small", "non_column_corner_medium"):
            elements = self.models[suffix]["elements"]
            self.assertTrue(elements)
            for face in arch.faces(elements):
                self.assertGreaterEqual(min(point[1] for point in face.vertices), -1e-8)
            for box in arch.collision_boxes(elements):
                self.assertGreaterEqual(box[1], 0)

    def test_narrow_crown_lining_keeps_only_saved_walls_and_preserves_body_bricks(self):
        wall_names = {element["name"] for element in self.rows["WALL__crown_left"]}
        for size in ("small", "medium"):
            lining = self.models[f"non_column_side_{size}_crown"]["elements"]
            self.assertEqual({element["name"] for element in lining}, wall_names)
            points = [point for face in arch.faces(lining) for point in face.vertices]
            self.assertAlmostEqual(min(point[2] for point in points), 0)
            self.assertAlmostEqual(max(point[2] for point in points), 16)
            self.assertAlmostEqual(min(point[1] for point in points), 0)
            self.assertAlmostEqual(max(point[1] for point in points), 16)
            for ending in ("", "_base"):
                jamb = self.models[f"non_column_side_{size}{ending}"]["elements"]
                self.assertTrue(any(element["name"].startswith("corner_large_lower") for element in jamb))
                jamb_points = [point for face in arch.faces(jamb) for point in face.vertices]
                self.assertAlmostEqual(min(point[2] for point in jamb_points), -.5)
                self.assertAlmostEqual(max(point[2] for point in jamb_points), 16.5)

    def test_every_narrow_height_routes_plain_crown_lining_above_detailed_jamb(self):
        for width, size in ((1, "small"), (2, "medium")):
            for height in range(1, 5):
                for column in range(width):
                    with self.subTest(width=width, height=height, column=column):
                        crown = arch.component_selection(width, height, height - 1, column, False)
                        self.assertIn(f"non_column_side_{size}_crown", [suffix for suffix, _ in crown])
                        self.assertNotIn(f"non_column_side_{size}", [suffix for suffix, _ in crown])
                        if height > 1:
                            base = arch.component_selection(width, height, 0, column, False)
                            self.assertIn(f"non_column_side_{size}_base", [suffix for suffix, _ in base])

    def test_derivation_preserves_both_editable_masters(self):
        paths = [ROOT / non_column.MASTER, ROOT / arch.MASTER]
        hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in paths]
        non_column.derive_components(ROOT, arch)
        self.assertEqual(hashes, [hashlib.sha256(path.read_bytes()).hexdigest() for path in paths])


class FinalGeneratedNonColumnSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.documents = {width: non_column.load(ROOT / non_column.DESIGN / f"arch_romanesque_non_column_current_{width}wide.json")
                         for width in range(1, 7)}
        cls.manifest = non_column.load(ROOT / non_column.DESIGN / "derivation_manifest.json")
        cls.bias = cls.manifest["face_separation"]["max_normal_offset"]
        cls.rows, _ = non_column.read_master(ROOT)

    def test_final_three_wide_preserves_full_saved_left_mirror_and_shared_center(self):
        left = [element for group in ("WALL__base_left", "WALL__body_left", "WALL__curve_lower_left",
                                     "WALL__crown_left", "CURVE__curve_lower_left", "CURVE__crown_left")
                for element in self.rows[group]]
        shared, _ = arch.master_elements(ROOT)
        expected = left + [arch.x_mirror(element, 48) for element in left]
        expected += arch.source_center(shared["KEYSTONE__crown_center"])
        before = surface_measure(expected)
        after = surface_measure(self.documents[3]["elements"])
        self.assertEqual(before.keys(), after.keys())
        for normal, old in before.items():
            current = after[normal]
            self.assertAlmostEqual(old[0], current[0], delta=max(1e-5, old[0] * 1e-8))
            for axis in range(3):
                # A face-separation offset translates a face without changing
                # its area. Its first moment can change by at most area*bias.
                self.assertAlmostEqual(old[axis + 1], current[axis + 1], delta=old[0] * self.bias + 1e-4)

    def test_final_all_widths_keep_x_mirror_without_reversing_depth(self):
        for width, document in self.documents.items():
            with self.subTest(width=width):
                measured = surface_measure(document["elements"])
                for normal, values in measured.items():
                    reflected_normal = (-normal[0], normal[1], normal[2])
                    self.assertIn(reflected_normal, measured)
                    other = measured[reflected_normal]
                    self.assertAlmostEqual(values[0], other[0], delta=max(1e-5, values[0] * 1e-7))
                    for axis in range(3):
                        expected = width * 16 * values[0] - values[axis + 1] if axis == 0 else values[axis + 1]
                        self.assertAlmostEqual(expected, other[axis + 1], delta=2 * values[0] * self.bias + 1e-3)

    def test_final_all_widths_retain_authored_front_and_back_projections(self):
        left = [element for group, elements in self.rows.items() if group.endswith("_left") for element in elements]
        source_z = [point[2] for face in arch.faces(left) for point in face.vertices]
        for width, document in self.documents.items():
            with self.subTest(width=width):
                actual_z = [point[2] for face in arch.faces(document["elements"]) for point in face.vertices]
                self.assertAlmostEqual(min(source_z), min(actual_z), delta=self.bias + 1e-7)
                self.assertAlmostEqual(max(source_z), max(actual_z), delta=self.bias + 1e-7)
                self.assertEqual(document["__erydon_authoring"]["style_set"], "base")

    def test_final_narrow_top_cells_do_not_repeat_projecting_vertical_jamb_bricks(self):
        for width, size in ((1, "small"), (2, "medium")):
            document = self.documents[width]
            crown_row = document["__erydon_authoring"]["height_blocks"] - 1
            components = document["__erydon_authoring"]["components"]
            lining = [component for component in components
                      if component["cell"][1] == crown_row and "side_" in component["suffix"]]
            self.assertEqual(len(lining), 2)
            for component in lining:
                self.assertEqual(component["suffix"], f"non_column_side_{size}_crown")
                elements = document["elements"][component["element_start"]:component["element_start"] + component["element_count"]]
                self.assertTrue(all(element["name"].startswith("side_large_") for element in elements))
                points = [point for face in arch.faces(elements) for point in face.vertices]
                self.assertGreaterEqual(min(point[2] for point in points), -self.bias - 1e-7)
                self.assertLessEqual(max(point[2] for point in points), 16 + self.bias + 1e-7)

    def test_final_base_and_body_faces_stay_in_their_owner_rows(self):
        for width, document in self.documents.items():
            metadata = document["__erydon_authoring"]
            with self.subTest(width=width):
                if width <= 3:
                    checked = 0
                    for component in metadata["components"]:
                        suffix = component["suffix"]
                        if suffix not in {"non_column_side_" + size + ending for size in ("small", "medium", "large") for ending in ("", "_base")}:
                            continue
                        elements = document["elements"][component["element_start"]:component["element_start"] + component["element_count"]]
                        points = [point for face in arch.faces(elements) for point in face.vertices]
                        low = component["cell"][1] * 16
                        # The saved bottom bricks project below zero slightly.
                        projection = .25 if suffix.endswith("_base") else 0
                        self.assertGreaterEqual(min(point[1] for point in points), low - projection - self.bias - 1e-7)
                        self.assertLessEqual(max(point[1] for point in points), low + 16 + self.bias + 1e-7)
                        checked += 1
                    self.assertGreater(checked, 0)
                else:
                    crown_rows = (2 * width + 2) // 3
                    body_top = (metadata["height_blocks"] - crown_rows) * 16
                    body_faces = [face for face in arch.faces(document["elements"])
                                  if max(point[1] for point in face.vertices) <= body_top + 1e-7]
                    self.assertTrue(body_faces)
                    for face in body_faces:
                        low, high = min(point[1] for point in face.vertices), max(point[1] for point in face.vertices)
                        self.assertGreaterEqual(low, -1e-7)
                        self.assertLessEqual(high, body_top + 1e-7)
                        self.assertTrue(any(low >= row * 16 - 1e-7 and high <= (row + 1) * 16 + 1e-7
                                            for row in range(body_top // 16)))

    def test_final_generated_source_hashes_match_both_saved_masters(self):
        for path, expected in ((arch.MASTER, self.manifest["master_sha256"]),
                               (non_column.MASTER, self.manifest["non_column_derivation"]["master_sha256"])):
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), expected)
        self.assertFalse((TOOLS / "tests/test_romanesque_non_column_authoring.py").exists())


if __name__ == "__main__":
    unittest.main()
