from __future__ import annotations

from collections import Counter
import copy
import itertools
import json
import math
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from test_model_georgian_alcove_safety import (
    face_signature, native_faces, native_owners, rendered_faces, rotate_native,
)
import generate_romanesque_arch as generator

DESIGN = ROOT / "authoring/arches/romanesque"
ASSETS = ROOT / "src/main/resources/assets/erydon"
RAW = ASSETS / "authoring_models/block/arch/romanesque"
PARENTS = ASSETS / "models/block/arch/romanesque"
MASTER = DESIGN / "arch_romanesque_3wide.bbmodel"
SUFFIXES = (
    "corner_small", "corner_medium", "corner_large_upper", "corner_large_lower",
    "side_small", "side_medium", "side_large", "side_large_lower",
    "side_medium_upper", "side_large_upper", "side_column", "side_column_upper", "plinth", "top_large", "icon",
    "non_column_side_small",
    "non_column_side_small_base",
    "non_column_side_small_crown",
    "non_column_side_medium",
    "non_column_side_medium_base",
    "non_column_side_medium_crown",
    "non_column_side_large",
    "non_column_side_large_base",
    "non_column_side_large_lower",
    "non_column_side_large_crown",
    "non_column_corner_small",
    "non_column_corner_medium",
    "non_column_corner_large_lower",
    "non_column_corner_large_upper",
)
SOURCE_NAMESPACE = uuid.UUID("a98d8bf6-ef7c-541b-9023-20f4f1c1b475")


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def polygon_signature(vertices):
    return face_signature("", vertices)[1]


def face_counter(document):
    return Counter(polygon_signature(face.vertices) for face in rendered_faces(document))


def left_half_polygons(points, boundary=24):
    """Intersect an authored face with the approved center mirror plane."""
    output = []
    previous = points[-1]
    for current in points:
        if (previous[0] <= boundary) != (current[0] <= boundary):
            fraction = (boundary-previous[0])/(current[0]-previous[0])
            output.append(tuple(previous[i]+fraction*(current[i]-previous[i]) for i in range(3)))
        if current[0] <= boundary:
            output.append(current)
        previous = current
    compact = []
    for point in output:
        if not compact or math.dist(point, compact[-1]) > 1e-9:
            compact.append(point)
    if len(compact) > 1 and math.dist(compact[0], compact[-1]) <= 1e-9:
        compact.pop()
    if len(compact) < 3:
        return []
    area = [0.0]*3
    for a, b in zip(compact, compact[1:]+compact[:1]):
        area[0] += a[1]*b[2]-a[2]*b[1]
        area[1] += a[2]*b[0]-a[0]*b[2]
        area[2] += a[0]*b[1]-a[1]*b[0]
    if math.sqrt(sum(value*value for value in area)) < 1e-9:
        return []
    polygons = [compact] if len(compact) <= 4 else [
        [compact[0], compact[i], compact[i+1]] for i in range(1, len(compact)-1)
    ]
    return [tuple(polygon+[polygon[-1]]) if len(polygon) == 3 else tuple(polygon) for polygon in polygons]


def authored_left_and_center():
    """Use the saved BB face masks and the approved LEFT mirror."""
    project = load(MASTER)
    owners = native_owners(project)
    baseline = load(DESIGN / "arch_romanesque_3wide.json")
    originals = {str(uuid.uuid5(SOURCE_NAMESPACE, f"cube/{index}")): element
             for index, element in enumerate(baseline["elements"])}
    left, center = [], []
    for index, source in enumerate(project["elements"]):
        cube = copy.deepcopy(source)
        if cube["uuid"] in originals:
            original = originals[cube["uuid"]]
            rotation = original["rotation"]
            original_angles = [rotation["angle"] if rotation["axis"] == axis else 0 for axis in "xyz"]
            # A native save rounded untouched rotation origins to five decimals.
            # Retain the original precision only where the cube is still equal
            # within that serialization error, rather than weakening parity.
            unchanged = all(abs(a-b) < 1e-5 for key in ("from", "to")
                            for a, b in zip(cube[key], original[key]))
            unchanged &= all(abs(a-b) < 1e-5 for a, b in zip(cube["origin"], rotation["origin"]))
            unchanged &= cube.get("rotation", [0, 0, 0]) == original_angles
            if unchanged:
                cube["from"], cube["to"] = original["from"], original["to"]
                cube["origin"], cube["rotation"] = rotation["origin"], original_angles
        path = owners[index]
        if any(name.endswith("_left") for name in path):
            left.append(cube)
        elif "KEYSTONE__crown_center" in path:
            center.append(cube)
    authoritative = [points for _, points in native_faces(project, left)]
    authoritative += [polygon for _, points in native_faces(project, center)
                      for polygon in left_half_polygons(points)]
    expected = Counter(polygon_signature(points) for points in authoritative)
    for points in authoritative:
        mirrored = tuple((48-x, y, z) for x, y, z in reversed(points))
        expected[polygon_signature(mirrored)] += 1
    return expected, left, center


def component_counter(component):
    document = load(ROOT / component["source"])
    degrees = component["runtime_y_rotation"]
    assert degrees in (0, 180), component
    reflect_z = component.get("right_reflect_z", False)
    shift = component["translation"]
    output = Counter()
    for face in rendered_faces(document):
        points = []
        for x, y, z in face.vertices:
            if reflect_z:
                z = 16-z
            if degrees == 180:
                x, z = 16-x, 16-z
            points.append((x+shift[0], y+shift[1], z+shift[2]))
        if reflect_z:
            points.reverse()
        output[polygon_signature(points)] += 1
    return output


def project_with_original_geometry_and_masks():
    """Start face-only regressions from the unchanged exported cube geometry."""
    project = load(MASTER)
    baseline = load(DESIGN / "arch_romanesque_3wide.json")
    originals = {str(uuid.uuid5(SOURCE_NAMESPACE, f"cube/{index}")): element
                 for index, element in enumerate(baseline["elements"])}
    for cube in project["elements"]:
        original = originals.get(cube["uuid"])
        if original is None:
            continue
        for bound in ("from", "to"):
            cube[bound] = copy.deepcopy(original[bound])
        rotation = original["rotation"]
        cube["origin"] = copy.deepcopy(rotation["origin"])
        cube["rotation"] = [rotation["angle"] if rotation["axis"] == axis else 0 for axis in "xyz"]
        cube["faces"] = {
            face: {"texture": 0 if face in original["faces"] else None}
            for face in ("north", "east", "south", "west", "up", "down")
        }
    return project, originals


def derive_project(project):
    original_load = generator.load

    def replaced_load(path):
        return copy.deepcopy(project) if path == ROOT / generator.MASTER else original_load(path)

    with patch.object(generator, "load", side_effect=replaced_load):
        models, _ = generator.models_from_master(ROOT)
    return models


class RomanesqueArchFaceMaskTests(unittest.TestCase):
    def test_original_column_and_plinth_face_only_edits_reach_components_and_every_repeat(self):
        project, originals = project_with_original_geometry_and_masks()
        owners = native_owners(project)
        original_models = derive_project(project)
        original_assembly = generator.assembly(original_models, 3)
        for suffix, role, source_y in (
            ("side_column", "COLUMN__base_left", 0),
            ("side_column_upper", "COLUMN__capitals_left", 16),
            ("plinth", "PLINTH__base_left", 0),
        ):
            index = next(i for i, cube in enumerate(project["elements"])
                         if role in owners[i] and cube["uuid"] in originals)
            cube = project["elements"][index]
            for enabled in (True, False):
                with self.subTest(component=suffix, enabled=enabled):
                    face = next(name for name, fields in cube["faces"].items()
                                if (fields["texture"] is None) == enabled)
                    edited = copy.deepcopy(project)
                    # An omitted texture is an enabled, automatically mapped face.
                    edited["elements"][index]["faces"][face] = {} if enabled else {"texture": None}
                    models = derive_project(edited)
                    native = next(points for name, points in native_faces(
                        project, [dict(cube, faces={face: {}})]) if name == face)
                    local = tuple((16-x, y-source_y, 16-z) for x, y, z in native)
                    difference = Counter({polygon_signature(local): 1})
                    before = face_counter(original_models[suffix])
                    after = face_counter(models[suffix])
                    self.assertEqual(after, before+difference if enabled else before-difference)

                    repeated = Counter()
                    for component in original_assembly["__erydon_authoring"]["components"]:
                        if component["suffix"] != suffix:
                            continue
                        points = []
                        for x, y, z in local:
                            if component.get("right_reflect_z", False):
                                z = 16-z
                            if component["runtime_y_rotation"] == 180:
                                x, z = 16-x, 16-z
                            dx, dy, dz = component["translation"]
                            points.append((x+dx, y+dy, z+dz))
                        if component.get("right_reflect_z", False):
                            points.reverse()
                        repeated[polygon_signature(tuple(points))] += 1
                    self.assertEqual(sum(repeated.values()), 2)
                    before = face_counter(original_assembly)
                    after = face_counter(generator.assembly(models, 3))
                    self.assertEqual(after, before+repeated if enabled else before-repeated)

    def test_new_cubes_treat_missing_texture_as_enabled_and_null_as_disabled(self):
        project, originals = project_with_original_geometry_and_masks()
        owners = native_owners(project)
        index = next(i for i, cube in enumerate(project["elements"])
                     if cube["uuid"] not in originals
                     and any(name.endswith("_left") for name in owners[i]))
        cube = project["elements"][index]
        cube["name"] = "face_mask_regression_added_cube"
        cube["faces"] = {"north": {}, "east": {"texture": None}, "south": {"texture": 0}}
        original_load = generator.load
        with patch.object(generator, "load", side_effect=lambda path:
                          copy.deepcopy(project) if path == ROOT / generator.MASTER else original_load(path)):
            rows, _ = generator.master_elements(ROOT)
        element = next(element for elements in rows.values() for element in elements
                       if element["name"] == cube["name"])
        self.assertEqual(set(element["faces"]), {"north", "south"})
        self.assertTrue(all(fields["texture"] == "#stone" for fields in element["faces"].values()))


class RomanesqueArchDerivationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assemblies = {
            width: load(DESIGN / f"arch_romanesque_current_{width}wide.json") for width in (1, 2, 3)
        }

    def assertCountersEqual(self, actual, expected, label):
        self.assertEqual(actual, expected,
                         f"{label}: extra={list((actual-expected).items())[:2]}, "
                         f"missing={list((expected-actual).items())[:2]}")

    def assertPolygonsAlmostEqual(self, actual, expected):
        self.assertEqual(len(actual), len(expected))
        remaining = list(expected)
        maximum_error = 0.0
        for points in actual:
            center = tuple(sum(p[i] for p in points)/4 for i in range(3))
            candidates = [
                (min(max(math.dist(points[j], polygon[(j+k)%4]) for j in range(4)) for k in range(4)), index)
                for index, polygon in enumerate(remaining)
                if math.dist(center, tuple(sum(p[i] for p in polygon)/4 for i in range(3))) < 1e-6
            ]
            self.assertTrue(candidates, points)
            error, index = min(candidates)
            maximum_error = max(maximum_error, error)
            self.assertLess(error, 1e-7, points)
            remaining.pop(index)
        self.assertFalse(remaining)

    def curve_faces(self, width):
        document = self.assemblies[width]
        selected = []
        for part in document["__erydon_authoring"]["components"]:
            if part["suffix"].startswith("corner_") or part["suffix"] == "top_large":
                selected.extend(document["elements"][part["element_start"]:part["element_start"]+part["element_count"]])
        return [face.vertices for face in rendered_faces({"elements": selected})]

    def test_narrow_curves_preserve_the_established_circle_projection_and_authored_depth(self):
        fixture = load(DESIGN / "source_components.json")["models"]

        def center(suffix, index, shift):
            element = fixture[suffix]["document"]["elements"][index]
            rotation = element["rotation"]
            native = dict(element, origin=rotation["origin"], rotation=[
                rotation["angle"] if rotation["axis"] == axis else 0 for axis in "xyz"
            ])
            corners = [rotate_native(point, native) for point in itertools.product(*zip(element["from"], element["to"]))]
            return tuple(sum(p[i] for p in corners)/8+shift[i] for i in range(3))

        source = self.curve_faces(3)
        # Width 1 has only the plain setting; its edited crown is checked by the non-column suite.
        for width, suffix, target_indices in ((2, "corner_medium", (17, 18)),):
            shifts = []
            for source_suffix, source_index, row, target_index in (
                ("corner_large_lower", 3, 32, target_indices[0]),
                ("corner_large_upper", 0, 48, target_indices[1]),
            ):
                original = center(source_suffix, source_index, (32, row, 0))
                narrow = center(suffix, target_index, (16 if width == 2 else 0, 0, 0))
                shifts.append(tuple(narrow[i]-original[i]*width/3 for i in (0, 1)))
            dx, dy = (sum(p[i] for p in shifts)/2 for i in (0, 1))
            projected = [tuple((x*width/3+dx, y*width/3+dy+48, z) for x, y, z in points)
                         for points in source]
            left = [polygon for points in projected for polygon in left_half_polygons(points, width*8)]
            projected = left+[tuple((width*16-x, y, z) for x, y, z in reversed(points)) for points in left]
            self.assertPolygonsAlmostEqual(self.curve_faces(width), projected)

    def test_wider_arches_keep_depth_and_follow_proportional_crown_height_with_owner_clipping(self):
        source = [point for face in self.curve_faces(3) for point in face]
        minimum_y = min(p[1] for p in source)
        depth = (min(p[2] for p in source), max(p[2] for p in source))
        for width in (4, 5, 6):
            document = load(DESIGN / f"arch_romanesque_current_{width}wide.json")
            height = document["__erydon_authoring"]["height_blocks"]
            faces = rendered_faces(document)
            all_points = [point for face in faces for point in face.vertices]
            self.assertTrue(all(-1e-8 <= x <= width*16+1e-8 and -1e-8 <= y <= height*16+1e-8
                                for x, y, z in all_points), width)
            curve_points = [point for face in faces
                            if face.element["name"].startswith(("corner_", "top_large_"))
                            for point in face.vertices]
            self.assertTrue(curve_points, width)
            crown_rows = (2*width+2)//3
            expected_y = max((height-crown_rows)*16, height*16+(minimum_y-64)*width/3)
            self.assertAlmostEqual(min(p[1] for p in curve_points), expected_y, delta=1e-7)
            self.assertAlmostEqual(max(p[1] for p in curve_points), height*16, delta=1e-7)
            self.assertEqual((min(p[2] for p in curve_points), max(p[2] for p in curve_points)), depth)

    def test_generator_check_preserves_the_editable_master_and_initial_reference(self):
        paths = (MASTER, DESIGN / "arch_romanesque_3wide.json", DESIGN / "source_components.json")
        before = {path: path.read_bytes() for path in paths}
        result = subprocess.run([sys.executable, str(TOOLS / "generate_romanesque_arch.py"), "--check"],
                                cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual({path: path.read_bytes() for path in paths}, before)

    def test_three_wide_reproduces_the_authored_left_center_and_approved_mirror(self):
        expected, left, center = authored_left_and_center()
        self.assertTrue(left and center)
        self.assertCountersEqual(face_counter(self.assemblies[3]), expected, "whole 3-wide arch")

    def test_every_assembled_component_reproduces_its_current_raw_model(self):
        for width, assembly in self.assemblies.items():
            expected = Counter()
            for component in assembly["__erydon_authoring"]["components"]:
                expected.update(component_counter(component))
            self.assertCountersEqual(face_counter(assembly), expected, f"{width}-wide component placement")

    def test_all_registered_material_finish_parents_and_icons_use_current_components(self):
        states = list((ASSETS / "blockstates").glob("*_arch_romanesque.json"))
        self.assertEqual(len(states), 162)
        for state in states:
            wrapped = load(state)["multipart"][0]["apply"]["model"].split("/")[-1]
            aged = wrapped.endswith("_aged")
            base = wrapped[:-5] if aged else wrapped
            material = base.removesuffix("_arch_romanesque")
            texture = f"erydon:block/{material}_block" + ("_aged" if aged else "")
            for suffix in SUFFIXES:
                path = PARENTS / f"{base}_{suffix}{'_aged' if aged else ''}.json"
                child = load(path)
                self.assertEqual(child["parent"], f"erydon:block/arch/romanesque/arch_romanesque_{suffix}",
                                 path.name)
                self.assertEqual(child["textures"]["stone"], texture, path.name)
                self.assertNotIn("elements", child, path.name)
            item = load(ASSETS / "models/item" / f"{state.stem}.json")
            self.assertEqual(item["parent"],
                             "erydon:block/arch/romanesque/arch_romanesque_icon",
                             state.stem)
            self.assertEqual(item["textures"]["stone"], texture, state.stem)

    def test_all_raw_components_are_implicit_atlas_split_models_with_current_texture_binding(self):
        for suffix in SUFFIXES:
            document = load(RAW / f"arch_romanesque_{suffix}.json")
            self.assertIs(document.get("erydon_split_world_uv"), True, suffix)
            self.assertTrue(document["elements"], suffix)
            for element in document["elements"]:
                for face in element["faces"].values():
                    self.assertNotIn("uv", face, suffix)
                    self.assertEqual(face["texture"], "#stone", suffix)
            anchor = load(PARENTS / f"arch_romanesque_{suffix}.json")
            self.assertFalse(anchor.get("elements"), suffix)

    def test_columns_and_plinths_keep_the_authored_geometry_and_repeat_below_the_crown(self):
        project = load(MASTER)
        owners = native_owners(project)
        _, left, _ = authored_left_and_center()
        normalized = {cube["uuid"]: cube for cube in left}
        for suffix, role, source_y in (
            ("side_column", "COLUMN__base_left", 0),
            ("side_column_upper", "COLUMN__capitals_left", 16),
            ("plinth", "PLINTH__base_left", 0),
        ):
            cubes = [normalized[cube["uuid"]] for index, cube in enumerate(project["elements"])
                     if role in owners[index]]
            expected = Counter(polygon_signature(tuple((16-x, y-source_y, 16-z) for x, y, z in points))
                               for _, points in native_faces(project, cubes))
            actual = load(RAW / f"arch_romanesque_{suffix}.json")
            self.assertEqual(len(actual["elements"]), len(cubes), suffix)
            self.assertCountersEqual(face_counter(actual), expected, suffix)
        for width, assembly in self.assemblies.items():
            components = assembly["__erydon_authoring"]["components"]
            columns = [part for part in components if part.get("suffix") in ("side_column", "side_column_upper")]
            plinths = [part for part in components if part.get("suffix") == "plinth"]
            if width == 1:
                self.assertFalse(columns)
                self.assertFalse(plinths)
            else:
                self.assertEqual(len(plinths), 2)
                height = assembly["__erydon_authoring"]["height_blocks"]
                crown_rows = 1 if width == 2 else 2
                self.assertEqual(len(columns), 2*(height-crown_rows))
                self.assertEqual(sorted(part["translation"][1] for part in columns),
                                 sorted([16*row for row in range(height-crown_rows)]*2))
                upper_columns = [part for part in columns if part["suffix"] == "side_column_upper"]
                self.assertEqual(len(upper_columns), 2)
                self.assertEqual([part["translation"][1] for part in upper_columns],
                                 [(height-crown_rows-1)*16]*2)


if __name__ == "__main__":
    unittest.main()
