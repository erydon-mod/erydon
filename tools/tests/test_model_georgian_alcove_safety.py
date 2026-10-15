from __future__ import annotations

from collections import Counter
import copy
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import model_z_fighting_safety as geometry

ASSETS = ROOT / "src/main/resources/assets/erydon"
RAW = ASSETS / "authoring_models/block/alcove"
PARENTS = ASSETS / "models/block/alcove/georgian"
DESIGN = ROOT / "authoring/alcoves/georgian"
MASTER = DESIGN / "alcove_georgian_3wide.bbmodel"
DETAIL_SUFFIXES = tuple(prefix + part + "_detail_" + side
                        for prefix in ("", "double_", "triple_")
                        for part in ("base", "middle") for side in ("left", "right"))


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def rendered_faces(document: dict):
    document = copy.deepcopy(document)
    for element in document["elements"]:
        for face in element["faces"].values():
            face.pop("erydon_normal_offset", None)
    rotations, _ = geometry._raw_group_state(document, len(document["elements"]))
    records, errors = geometry._build_faces(document["elements"], raw_group_rotations=rotations)
    unexpected = [error for error in errors if error["reason"] not in
                  ("zero-thickness element is unsupported", "degenerate face normal")]
    if unexpected:
        raise AssertionError(unexpected)
    return records


def native_owners(project: dict):
    groups = {group["uuid"]: group for group in project["groups"]}
    indices = {cube["uuid"]: index for index, cube in enumerate(project["elements"])}
    owners = {}

    def walk(nodes, names=()):
        for node in nodes:
            if isinstance(node, str):
                owners[indices[node]] = names
            else:
                group = groups[node["uuid"]]
                if any(group.get("rotation", [0, 0, 0])):
                    raise AssertionError("The edited design has an unsupported group rotation")
                walk(node["children"], names + (group["name"],))

    walk(project["outliner"])
    return owners


def rotate_native(vertex, cube):
    """Independent X/Y/Z transform for comparisons with the editable source."""
    origin = cube.get("origin", [8, 8, 8])
    x, y, z = (vertex[axis] - origin[axis] for axis in range(3))
    angles = [math.radians(value) for value in cube.get("rotation", [0, 0, 0])]
    c, s = math.cos(angles[0]), math.sin(angles[0])
    y, z = c * y - s * z, s * y + c * z
    c, s = math.cos(angles[1]), math.sin(angles[1])
    x, z = c * x + s * z, -s * x + c * z
    c, s = math.cos(angles[2]), math.sin(angles[2])
    x, y = c * x - s * y, s * x + c * y
    return (x + origin[0], y + origin[1], z + origin[2])


def native_faces(project, cubes=None, transform=lambda p: p):
    for cube in project["elements"] if cubes is None else cubes:
        if cube.get("type", "cube") != "cube":
            raise AssertionError("The editable master must use supported cube authoring")
        for name, fields in cube["faces"].items():
            if fields.get("texture", "enabled") is None:
                continue
            vertices = tuple(transform(rotate_native(vertex, cube)) for vertex in
                             geometry._local_face_vertices(cube["from"], cube["to"], name))
            yield name, vertices


def face_signature(name, vertices):
    points = tuple(tuple(round(value, 6) for value in vertex) for vertex in vertices)
    # Allow cyclic ordering, preserving winding and duplicate face counts.
    return name, min(points[index:] + points[:index] for index in range(4))


def raw_counter(document):
    return Counter(face_signature(face.face_name, face.vertices) for face in rendered_faces(document))


def native_counter(project, cubes=None, transform=lambda p: p):
    return Counter(face_signature(name, vertices) for name, vertices in native_faces(project, cubes, transform))


def translated(element, shift):
    element = copy.deepcopy(element)
    for key in ("from", "to", "origin"):
        if key in element:
            element[key] = [value + shift[axis] for axis, value in enumerate(element[key])]
    rotation = element.get("rotation", {})
    if "origin" in rotation:
        rotation["origin"] = [value + shift[axis] for axis, value in enumerate(rotation["origin"])]
    for face in element["faces"].values():
        if "erydon_vertices" in face:
            face["erydon_vertices"] = [[value + shift[axis] for axis, value in enumerate(point)]
                                       for point in face["erydon_vertices"]]
    return element


def component_document(width, role):
    prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
    elements = []
    for column in range(width):
        side = "left" if column == 0 else "right" if column == width - 1 else "center"
        suffix = ("single_top" if role == "top" else "single_sides") if width == 1 else prefix + role + "_" + side
        elements.extend(translated(element, (column * 16, 0, 0))
                        for element in load(RAW / f"alcove_georgian_{suffix}.json")["elements"])
    return {"elements": elements}


def runtime_three_wide_assembly():
    elements = []
    back = load(RAW / "alcove_georgian_single_back.json")
    for row, part in enumerate(("base", "middle", "top")):
        for column, side in enumerate(("left", "center", "right")):
            documents = [back]
            if part == "top":
                documents.append(load(RAW / f"alcove_georgian_triple_top_{side}.json"))
            else:
                documents.append(load(RAW / f"alcove_georgian_triple_side_{side}.json"))
                if side != "center":
                    documents.append(load(RAW / f"alcove_georgian_triple_{part}_detail_{side}.json"))
            for document in documents:
                elements.extend(translated(element, (column * 16, row * 16, 0))
                                for element in document["elements"])
    return {"elements": elements}


def ridges(document):
    return sorted((face for face in rendered_faces(document)
                   if face.element["name"].startswith("georgian_triple_front_")
                   and face.face_name == "south" and min(point[2] for point in face.vertices) >= 16.25),
                  key=lambda face: sum(point[0] for point in face.vertices) / 4)


def polygon_area(vertices):
    return abs(sum(a[0] * b[1] - a[1] * b[0]
                   for a, b in zip(vertices, vertices[1:] + vertices[:1]))) / 2


def intersection_polygon(subject, clip):
    """Independent convex clipping used to detect positive-area overlaps."""
    def cross(a, b, p):
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

    def crossing(a, b, p, q):
        cp, cq = cross(a, b, p), cross(a, b, q)
        fraction = cp / (cp - cq)
        return (p[0] + fraction * (q[0] - p[0]), p[1] + fraction * (q[1] - p[1]))

    output = list(subject)
    for a, b in zip(clip, clip[1:] + clip[:1]):
        if not output:
            break
        previous_vertices, output = output, []
        previous = previous_vertices[-1]
        previous_inside = cross(a, b, previous) >= -1e-10
        for point in previous_vertices:
            inside = cross(a, b, point) >= -1e-10
            if inside != previous_inside:
                output.append(crossing(a, b, previous, point))
            if inside:
                output.append(point)
            previous, previous_inside = point, inside
    return output


class GeorgianAlcoveDerivationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.project = load(MASTER)
        cls.owners = native_owners(cls.project)
        cls.manifest = load(DESIGN / "derivation_manifest.json")
        cls.assemblies = {width: load(DESIGN / f"alcove_georgian_{width}wide.json") for width in (1, 2, 3)}

    def assertVectorAlmostEqual(self, actual, expected, message=""):
        self.assertEqual(len(actual), len(expected), message)
        for value, reference in zip(actual, expected):
            self.assertAlmostEqual(value, reference, delta=1e-6, msg=message)

    def assertFaceCountersEqual(self, actual, expected, context):
        self.assertEqual(actual, expected,
                         f"{context}: extra={list((actual - expected).items())[:2]}, "
                         f"missing={list((expected - actual).items())[:2]}")

    def assertFacesAlmostEqual(self, actual, expected, context):
        # Generated affine vertices are rounded to eight decimals. Comparing
        # rounded hash keys can split an otherwise equal pair at a boundary.
        remaining = [(name, vertices, tuple(sum(point[axis] for point in vertices) / 4 for axis in range(3)))
                     for name, vertices in expected]
        self.assertEqual(len(actual), len(remaining), context)
        for face in actual:
            centre = tuple(sum(point[axis] for point in face.vertices) / 4 for axis in range(3))
            candidates = [index for index, (name, vertices, reference) in enumerate(remaining)
                          if name == face.face_name and math.dist(centre, reference) <= 1e-7
                          and all(math.dist(a, b) <= 1e-7 for a, b in zip(face.vertices, vertices))]
            self.assertTrue(candidates, (context, face.face_name, face.vertices))
            remaining.pop(candidates[0])
        self.assertFalse(remaining, context)

    def source_jambs(self, side, kind):
        result = []
        for cube in self.project["elements"]:
            if cube["name"] != f"outer_jamb_{side}":
                continue
            height = cube["to"][1] - cube["from"][1]
            if kind == "flutes" and abs(height - 16) < 1e-6 and cube["from"][1] == 0:
                result.append(cube)
            elif kind == "plinth" and height < 1 and cube["to"][1] <= 1:
                result.append(cube)
            elif kind == "capitals" and height < 1 and cube["from"][1] >= 32:
                result.append(cube)
        return sorted(result, key=lambda cube: (cube["from"][1], cube["from"][0]))

    def test_generator_check_preserves_master_and_matches_current_outputs(self):
        before = MASTER.read_bytes()
        result = subprocess.run([sys.executable, str(TOOLS / "generate_georgian_alcove.py"), "--check"],
                                cwd=ROOT, capture_output=True, text=True, timeout=90)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, MASTER.read_bytes())
        self.assertEqual(hashlib.sha256(before).hexdigest(), self.manifest["master_sha256"])
        self.assertEqual(len(self.project["elements"]), self.manifest["master_cubes"])
        self.assertEqual(162, self.manifest["material_finish_variants"])

    def test_complete_runtime_three_wide_matches_all_authored_faces(self):
        expected = native_counter(self.project)
        self.assertFaceCountersEqual(raw_counter(self.assemblies[3]), expected, "editable export")
        self.assertFaceCountersEqual(raw_counter(runtime_three_wide_assembly()), expected, "placed components")

    def test_every_material_and_finish_binds_every_detail_parent(self):
        variants = {tuple(sorted(load(path)["textures"].items())): path
                    for path in PARENTS.glob("*_alcove_georgian_sides*.json")
                    if not path.name.startswith("alcove_georgian_")}
        registered = sorted((ASSETS / "blockstates").glob("*_alcove_georgian.json"))
        self.assertEqual(162, len(registered))
        self.assertEqual(162, len(variants))
        for state in registered:
            texture_key = tuple(sorted(load(ASSETS / "models/item" / state.name)["textures"].items()))
            self.assertIn(texture_key, variants, state.stem)
            for suffix in DETAIL_SUFFIXES:
                document = load(PARENTS / variants[texture_key].name.replace("_sides", "_" + suffix))
                self.assertEqual(dict(texture_key), document["textures"], state.stem)
                self.assertEqual(f"erydon:block/alcove/georgian/alcove_georgian_{suffix}", document["parent"])
        for path in RAW.glob("alcove_georgian_*.json"):
            document = load(path)
            self.assertTrue(document["erydon_split_world_uv"], path.name)
            # The unused legacy floor retains its published #missing binding.
            if path.name == "alcove_georgian_single_base.json":
                continue
            for element in document["elements"]:
                for face in element["faces"].values():
                    self.assertEqual("#stone", face["texture"])
                    self.assertNotIn("uv", face)

    def test_five_flutes_and_two_plinth_steps_preserve_dimensions_for_every_width(self):
        for side in ("left", "right"):
            flutes = self.source_jambs(side, "flutes")
            plinths = self.source_jambs(side, "plinth")
            self.assertEqual(5, len(flutes), side)
            self.assertEqual(2, len(plinths), side)
            self.assertEqual({16.25, 16.5}, {cube["to"][2] for cube in flutes})
            for width, document in self.assemblies.items():
                x_shift = 0 if side == "left" else (width - 3) * 16
                for row in (0, 1):
                    actual = [element for element in document["elements"]
                              if element["name"] == f"outer_jamb_{side}"
                              and abs(element["to"][1] - element["from"][1] - 16) < 1e-6
                              and element["from"][1] == row * 16]
                    self.assertEqual(5, len(actual), (width, side, row))
                    expected = native_counter(self.project, flutes,
                                              lambda p: (p[0] + x_shift, p[1] + row * 16, p[2]))
                    self.assertFaceCountersEqual(raw_counter({"elements": actual}), expected, (width, side, row))
                actual = [element for element in document["elements"]
                          if element["name"] == f"outer_jamb_{side}"
                          and element["to"][1] - element["from"][1] < 1 and element["to"][1] <= 1]
                self.assertEqual(2, len(actual), (width, side))
                expected = native_counter(self.project, plinths, lambda p: (p[0] + x_shift, p[1], p[2]))
                self.assertFaceCountersEqual(raw_counter({"elements": actual}), expected, (width, side, "plinth"))

    def test_three_capital_layers_keep_the_current_source_profile_and_springing(self):
        for side in ("left", "right"):
            capitals = self.source_jambs(side, "capitals")
            self.assertEqual(3, len(capitals), side)
            self.assertEqual({.5}, {round(cube["to"][1] - cube["from"][1], 6) for cube in capitals})
            for width, document in self.assemblies.items():
                springing = 7.5 if width == 1 else 0
                x_shift = 0 if side == "left" else (width - 3) * 16
                actual = [element for element in document["elements"]
                          if element["name"] == f"outer_jamb_{side}"
                          and element["to"][1] - element["from"][1] < 1 and element["from"][1] >= 32]
                self.assertEqual(3, len(actual), (width, side))
                expected = native_counter(self.project, capitals, lambda p: (p[0] + x_shift, p[1] + springing, p[2]))
                self.assertFaceCountersEqual(raw_counter({"elements": actual}), expected, (width, side, "capital"))

    def test_nine_crest_pieces_are_rigidly_translated_to_the_narrow_apex(self):
        source = [cube for index, cube in enumerate(self.project["elements"])
                  if "ROOF__top_center" in self.owners[index] and cube["name"] == "top"
                  and cube["to"][0] - cube["from"][0] < 8]
        self.assertEqual(9, len(source))
        self.assertAlmostEqual(.62505092, self.manifest["crest_vertical_shifts_units"]["1"], delta=1e-6)
        self.assertEqual(0, self.manifest["crest_vertical_shifts_units"]["2"])
        for width, document in self.assemblies.items():
            actual = [element for element in document["elements"] if element["name"] == "top"
                      and element["to"][0] - element["from"][0] < 8
                      and not any("erydon_vertices" in face for face in element["faces"].values())]
            self.assertEqual(9, len(actual), width)
            shift = self.manifest["crest_vertical_shifts_units"][str(width)]
            expected = native_counter(self.project, source, lambda p: (p[0] + (width - 3) * 8, p[1] + shift, p[2]))
            self.assertFaceCountersEqual(raw_counter({"elements": actual}), expected, (width, "crest"))
            vertices = [point for face in rendered_faces({"elements": actual}) for point in face.vertices]
            self.assertGreaterEqual(min(point[0] for point in vertices), 0)
            self.assertLessEqual(max(point[0] for point in vertices), width * 16)

    def test_narrow_curved_surfaces_are_affine_after_authored_rotation(self):
        source = [cube for cube in self.project["elements"] if cube["name"].startswith("georgian_triple_surface_")]
        self.assertTrue(source)
        anchor = 2.10064
        for width in (1, 2):
            top = component_document(width, "top")
            actual = {"elements": [element for element in top["elements"]
                                   if element["name"].startswith("georgian_triple_surface_")]}
            self.assertEqual(len(source), len(actual["elements"]))
            scale_x = (width * 16 - 2 * anchor) / (48 - 2 * anchor)
            springing = 7.5 if width == 1 else 0
            scale_y = (16 - springing) / 16
            expected = list(native_faces(self.project, source,
                                         lambda p: (anchor + scale_x * (p[0] - anchor),
                                                    springing + scale_y * (p[1] - 32), p[2])))
            self.assertFacesAlmostEqual(rendered_faces(actual), expected, (width, "affine dome"))

    def test_narrow_ridges_keep_two_unit_thickness_and_share_mitred_edges_without_overlap(self):
        for width in (1, 2):
            strips = ridges(self.assemblies[width])
            self.assertEqual(14, len(strips), width)
            polygons = [[(point[0], point[1]) for point in face.vertices] for face in strips]
            for face, polygon in zip(strips, polygons):
                self.assertGreater(polygon_area(polygon), .01)
                a, b, c, d = face.vertices
                length = math.hypot(b[0] - a[0], b[1] - a[1])
                unit = ((b[0] - a[0]) / length, (b[1] - a[1]) / length)
                for outer in (c, d):
                    distance = unit[0] * (outer[1] - a[1]) - unit[1] * (outer[0] - a[0])
                    self.assertAlmostEqual(2, distance, delta=1e-6)
                self.assertEqual({16.5}, {point[2] for point in face.vertices})
                for p, q, r in zip(polygon, polygon[1:] + polygon[:1], polygon[2:] + polygon[:2]):
                    self.assertGreater((q[0] - p[0]) * (r[1] - q[1]) - (q[1] - p[1]) * (r[0] - q[0]), 0)
            for index, (first, second) in enumerate(zip(strips, strips[1:])):
                shared = ({tuple(round(value, 6) for value in point) for point in first.vertices} &
                          {tuple(round(value, 6) for value in point) for point in second.vertices})
                self.assertEqual(2, len(shared), (width, index))
            for first_index, first in enumerate(polygons):
                for second_index in range(first_index + 1, len(polygons)):
                    overlap = intersection_polygon(first, polygons[second_index])
                    self.assertLessEqual(polygon_area(overlap), 1e-7, (width, first_index, second_index))
            for side, face, edge in (("left", strips[0], (0, 3)), ("right", strips[-1], (1, 2))):
                capitals = [element for element in self.assemblies[width]["elements"]
                            if element["name"] == f"outer_jamb_{side}"
                            and element["from"][1] >= 32 and element["to"][1] - element["from"][1] < 1]
                top = max(capitals, key=lambda element: element["to"][1])
                for index in edge:
                    point = face.vertices[index]
                    self.assertAlmostEqual(top["to"][1], point[1], delta=1e-6)
                    self.assertGreaterEqual(point[0], top["from"][0] - 1e-6)
                    self.assertLessEqual(point[0], top["to"][0] + 1e-6)

    def test_native_derived_meshes_and_cubes_match_json_faces_and_are_closed(self):
        for width in (1, 2):
            native = load(DESIGN / f"alcove_georgian_{width}wide.bbmodel")
            raw = self.assemblies[width]
            self.assertEqual(len(raw["elements"]), len(native["elements"]))
            self.assertEqual("free", native["meta"]["model_format"])
            self.assertEqual(14, sum(element["type"] == "mesh" and element["name"].startswith("georgian_triple_front_")
                                     and max(point[2] for point in element["vertices"].values()) >= 16.25
                                     for element in native["elements"]))
            for index, element in enumerate(native["elements"]):
                records = {face.face_name: face for face in rendered_faces({"elements": [raw["elements"][index]]})}
                if element["type"] == "cube":
                    expected = Counter(face_signature(name, vertices) for name, vertices in native_faces(native, [element]))
                    self.assertFaceCountersEqual(Counter(face_signature(face.face_name, face.vertices) for face in records.values()),
                                                 expected, (width, index, "native cube"))
                    continue
                self.assertEqual("mesh", element["type"])
                self.assertEqual([0, 0, 0], element["origin"])
                self.assertEqual([0, 0, 0], element["rotation"])
                self.assertEqual(8, len(element["vertices"]), (width, index))
                self.assertEqual(6, len(element["faces"]), (width, index))
                edges = Counter()
                for face_name, face in element["faces"].items():
                    keys = face["vertices"]
                    self.assertEqual(4, len(set(keys)))
                    self.assertEqual(set(keys), set(face["uv"]))
                    self.assertEqual(0, face["texture"])
                    for key, reference in zip(keys, records[face_name].vertices):
                        self.assertVectorAlmostEqual(element["vertices"][key], reference)
                        self.assertEqual(2, len(face["uv"][key]))
                        self.assertTrue(all(math.isfinite(value) for value in face["uv"][key]))
                    edges.update(tuple(sorted((a, b))) for a, b in zip(keys, keys[1:] + keys[:1]))
                self.assertEqual(12, len(edges), (width, index))
                self.assertEqual({2}, set(edges.values()), (width, index))


if __name__ == "__main__":
    unittest.main()
