import importlib.util
import copy
import itertools
import math
import csv
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("window_corners", ROOT / "tools/generate_georgian_window_corners.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


# Independently check actual rotated solid geometry, rather than accepting the
# generator's component selections or expected cap table as visual proof.
FACE_AXES = {"west": (0, -1), "east": (0, 1), "down": (1, -1),
             "up": (1, 1), "north": (2, -1), "south": (2, 1)}


def rotated(point, element, inverse=False):
    rotation = element.get("rotation", {})
    angle = rotation.get("angle", 0)
    if not angle:
        return point
    axis = "xyz".index(rotation["axis"])
    first, second = (axis + 1) % 3, (axis + 2) % 3
    origin = rotation["origin"]
    angle = math.radians(-angle if inverse else angle)
    local = [point[index] - origin[index] for index in range(3)]
    result = local.copy()
    result[first] = local[first] * math.cos(angle) - local[second] * math.sin(angle)
    result[second] = local[first] * math.sin(angle) + local[second] * math.cos(angle)
    return [result[index] + origin[index] for index in range(3)]


def inside(point, element):
    local = rotated(point, element, inverse=True)
    return all(element["from"][index] - 1e-5 <= local[index] <= element["to"][index] + 1e-5
               for index in range(3))


def corners(element):
    return sorted(tuple(rotated(list(point), element))
                  for point in itertools.product(*zip(element["from"], element["to"])))


def fixed_frame_geometry(model):
    """Independent closed-window reference: keep its fanlight, clear below it."""
    result = []
    for element in model["elements"]:
        if any(face["texture"] != "#stone" for face in element["faces"].values()):
            continue
        vertices = corners(element)
        if max(point[1] for point in vertices) < 5.846 - 1e-5:
            continue  # The lower opening's two decorative uprights move away.
        if element["from"][1] == 0 and element["to"][1] == 14.5:
            element = copy.deepcopy(element)
            element["from"][1] = 5.852
        # The old outer leaf's top only reaches Y5.856. It is not fixed stone.
        if max(point[2] for point in vertices) > 3.001:
            continue
        result.append(corners(element))
    return result


def missing_fixed_frame_solids(reference, actual):
    # Closed/open source pivots differ, but their resulting vertices agree.
    # The authored right jamb starts Z.001 in the open source: allow that
    # existing sub-pixel offset, not a missing stone or a changed silhouette.
    return [expected for expected in reference
            if not any(max(math.dist(a, b) for a, b in zip(expected, candidate)) <= .0011
                       for candidate in actual)]


def uncovered_stone_faces(model):
    solids = [element for element in model["elements"]
              if all(face["texture"] == "#stone" for face in element["faces"].values())]
    uncovered = []
    for index, element in enumerate(model["elements"]):
        if element not in solids:
            continue
        for direction, (axis, sign) in FACE_AXES.items():
            if direction in element["faces"]:
                continue
            other_axes = [other for other in range(3) if other != axis]
            for fractions in itertools.product((.03, .18, .34, .5, .66, .82, .97), repeat=2):
                point = element["from"].copy()
                point[axis] = (element["to"][axis] if sign > 0 else element["from"][axis]) + sign * .002
                for other, fraction in zip(other_axes, fractions):
                    point[other] += fraction * (element["to"][other] - element["from"][other])
                point = rotated(point, element)
                if not any(inside(point, solid) for solid in solids if solid is not element):
                    uncovered.append((index, direction))
                    break
    return uncovered


class GeorgianWindowCornerSafetyTest(unittest.TestCase):
    def test_generated_outputs_are_current_and_bounded(self):
        outputs = generator.outputs()
        self.assertEqual(656, len(outputs))  # Eight shared shapes and 81 material bindings each.
        for path, expected in outputs.items():
            self.assertEqual(expected, generator.load(path), str(path.relative_to(ROOT)))

    def test_fixed_fanlights_retain_exact_geometry_uvs_and_glass_coatings(self):
        for suffix, (removed, _) in generator.WINGS.items():
            original = generator.load(generator.MODELS / f"window_french_georgian_open_upper_{suffix}.json")
            derived = generator.load(generator.MODELS / f"window_french_georgian_open_corner_upper_{suffix}.json")
            retained = [element for index, element in enumerate(original["elements"]) if index not in removed]
            self.assert_authored_elements_preserved(retained, derived["elements"])
            self.assertEqual(10, len(removed), "Remove a whole authored wing, not just its pane")
            for element in derived["elements"]:
                panes = [face for face in element["faces"].values() if face["texture"] == "#pane"]
                if panes:
                    self.assertLessEqual(element["to"][2] - element["from"][2], .3)
                    self.assertGreaterEqual(element["from"][1], 5)
                    self.assertTrue(any(face.get("tintindex") == 0 for face in panes))

    def test_fixed_post_is_one_small_complete_stone_cuboid_with_implicit_uvs(self):
        post = generator.load(generator.MODELS / "window_french_georgian_open_corner_post.json")
        self.assertEqual(1, len(post["elements"]))
        element = post["elements"][0]
        self.assertEqual([0, 0, 0], element["from"])
        self.assertEqual([3, 16, 3], element["to"])
        self.assertEqual({"north", "east", "south", "west", "up", "down"}, set(element["faces"]))
        self.assertTrue(all(face == {"texture": "#stone"} for face in element["faces"].values()))

    def test_open_cluster_keeps_single_fanlights_and_only_original_outer_wings(self):
        source = generator.load(generator.MODELS / "window_french_georgian_open_upper_single_lh.json")
        removed = generator.WINGS["single_lh"][0] | {34}
        fixed = [element for index, element in enumerate(source["elements"]) if index not in removed]
        jamb = copy.deepcopy(source["elements"][34])
        jamb["from"][1] = 5.852
        jamb["faces"]["down"].pop("cullface")
        fixed.append(jamb)
        for suffix in ("mid", "edge_lh", "edge_rh"):
            derived = generator.load(generator.MODELS / f"window_french_georgian_open_upper_single_{suffix}.json")
            self.assert_authored_elements_preserved(fixed, derived["elements"][:len(fixed)])
            if suffix == "mid":
                self.assertEqual(len(fixed), len(derived["elements"]))
                self.assertTrue(all(element["from"][1] >= 4.846 for element in derived["elements"]))
            else:
                wing = suffix.replace("edge_", "multi_")
                original = generator.load(generator.MODELS / f"window_french_georgian_open_upper_{wing}.json")
                self.assertEqual([element for index, element in enumerate(original["elements"])
                                  if index in generator.WINGS[wing][0]], derived["elements"][len(fixed):])

    def assert_authored_elements_preserved(self, originals, derived):
        self.assertEqual(len(originals), len(derived))
        for original, actual in zip(originals, derived):
            self.assertEqual({key: value for key, value in original.items() if key != "faces"},
                             {key: value for key, value in actual.items() if key != "faces"})
            for direction, face in original["faces"].items():
                self.assertEqual(face, actual["faces"][direction], "An authored face/UV/tint changed")
            for direction in actual["faces"].keys() - original["faces"].keys():
                self.assertEqual({"texture": "#stone"}, actual["faces"][direction],
                                 "Cut caps must have implicit CTM UVs and no neighbour culling")

    def test_open_templates_have_no_exposed_missing_stone_faces(self):
        templates = sorted(generator.MODELS.glob("window_french_georgian_open*.json"))
        self.assertEqual(17, len(templates))
        for path in templates:
            self.assertEqual([], uncovered_stone_faces(generator.load(path)), path.name)

    def test_exposure_check_rejects_previous_open_fanlight_cut_holes(self):
        model = copy.deepcopy(generator.load(generator.MODELS / "window_french_georgian_open_upper_single_mid.json"))
        old_holes = {(18, "west"), (18, "east")}
        for index, direction in old_holes:
            del model["elements"][index]["faces"][direction]
        self.assertEqual(old_holes, set(uncovered_stone_faces(model)),
                         "Geometry audit failed to detect the actual uncapped fanlight regression")

    def test_open_fanlights_keep_every_closed_frame_volume_above_the_opening(self):
        closed = generator.load(generator.MODELS / "window_french_georgian_closed_upper_single.json")
        reference = fixed_frame_geometry(closed)
        self.assertEqual(18, len(reference))
        for suffix in ("mid", "edge_lh", "edge_rh"):
            opened = generator.load(generator.MODELS / f"window_french_georgian_open_upper_single_{suffix}.json")
            actual = fixed_frame_geometry(opened)
            self.assertEqual([], missing_fixed_frame_solids(reference, actual), suffix)
            self.assertEqual(len(reference), len(actual), "Only the lower wings may change")
            # Points in the missing upper jamb were visibly open to the sky,
            # even though all remaining cuboids had their cut faces capped.
            for point in itertools.product((.25, .75, 1.25), (6, 8, 10, 12, 14), (.125, 1.5, 2.875)):
                self.assertTrue(any(inside(point, solid) for solid in opened["elements"]), (suffix, point))
            for point in itertools.product((.25, .75, 1.25), (1, 3, 4), (.125, 1.5, 2.875)):
                if suffix != "edge_rh":
                    self.assertFalse(any(inside(point, solid) for solid in opened["elements"]), (suffix, point))

    def test_single_and_corner_fanlights_preserve_the_authored_fixed_frame(self):
        closed = generator.load(generator.MODELS / "window_french_georgian_closed_upper_single.json")
        reference = fixed_frame_geometry(closed)
        for suffix in ("open_upper_single_lh", "open_upper_single_rh",
                       "open_corner_upper_single_lh", "open_corner_upper_single_rh"):
            opened = generator.load(generator.MODELS / f"window_french_georgian_{suffix}.json")
            actual = fixed_frame_geometry(opened)
            self.assertEqual([], missing_fixed_frame_solids(reference, actual), suffix)
            self.assertEqual(len(reference), len(actual), suffix)
        for suffix in ("multi_lh", "multi_rh"):
            # The older wide templates have some deliberate closed/open shape
            # differences. The corner derivation must preserve their entire
            # authored fixed frame, selected geometrically, not by WINGS IDs.
            original = generator.load(generator.MODELS / f"window_french_georgian_open_upper_{suffix}.json")
            corner = generator.load(generator.MODELS / f"window_french_georgian_open_corner_upper_{suffix}.json")
            expected, actual = fixed_frame_geometry(original), fixed_frame_geometry(corner)
            self.assertEqual([], missing_fixed_frame_solids(expected, actual), suffix)
            self.assertEqual(len(expected), len(actual), suffix)

    def test_volume_check_rejects_the_previous_missing_upper_jamb_even_with_all_caps(self):
        closed = generator.load(generator.MODELS / "window_french_georgian_closed_upper_single.json")
        opened = generator.load(generator.MODELS / "window_french_georgian_open_upper_single_mid.json")
        del opened["elements"][26]
        # This was the previous attempted repair: faces capped on the remaining
        # angled stones, with the whole fixed upper-left jamb still absent.
        for index, directions in {22: ("west", "down"), 23: ("down",)}.items():
            for direction in directions:
                opened["elements"][index]["faces"][direction] = {"texture": "#stone"}
        self.assertEqual([], uncovered_stone_faces(opened), "The previous face-only check passes this defective frame")
        missing = missing_fixed_frame_solids(fixed_frame_geometry(closed), fixed_frame_geometry(opened))
        self.assertEqual(1, len(missing), "Independent closed-frame coverage must reject the lost solid")

    def test_all_material_finishes_keep_the_existing_stone_glazing_and_lead(self):
        originals = [path for path in generator.MODELS.glob("*_closed_lower_single*.json")
                     if not path.name.startswith(generator.PREFIX)]
        self.assertEqual(81, len(originals))
        for path in originals:
            source = generator.load(path)
            post = generator.load(path.with_name(path.name.replace("_closed_lower_single", "_open_corner_post")))
            self.assertEqual(source["textures"], post["textures"])

    def test_native_repeat_ctm_covers_every_material_and_finish(self):
        with (ROOT / "src/main/resources/data/erydon/id_migration.tsv").open(encoding="utf-8") as manifest:
            aliases = {row["old_path"]: row["canonical_path"] for row in csv.DictReader(manifest, delimiter="\t")
                       if row["mode"] == "PERMANENT_ALIAS"}
        rules = []
        for path in (ROOT / "src/main/resources/assets/minecraft/optifine/ctm").rglob("*window_french_georgian*.properties"):
            data = path.read_bytes()
            self.assertFalse(data.startswith(b"\xef\xbb\xbf"), str(path))
            properties = dict(line.split("=", 1) for line in data.decode().replace("\\\r\n", " ").replace("\\\n", " ").splitlines()
                              if "=" in line and not line.lstrip().startswith("#"))
            rules.append(properties)
        for path in generator.MODELS.glob("*_open_corner_post*.json"):
            if path.name.startswith(generator.PREFIX):
                continue
            resource = path.stem.replace("_open_corner_post", "")
            canonical = resource
            if canonical.endswith("_aged"):
                canonical = canonical.removesuffix("_window_french_georgian_aged") + "_aged_window_french_georgian"
            accepted_ids = {"erydon:" + canonical}
            if resource != canonical:
                self.assertEqual(canonical, aliases.get(resource), "Legacy CTM ID must resolve through a permanent alias")
                accepted_ids.add("erydon:" + resource)
            stone = generator.load(path)["textures"]["stone"]
            matches = [rule for rule in rules if accepted_ids.intersection(rule.get("matchBlocks", "").split())
                       and stone in rule.get("matchTiles", "").split()]
            self.assertTrue(matches, canonical)
            self.assertTrue(any(rule.get("method") == "repeat" and rule.get("width") == "6"
                                and rule.get("height") == "6" and len(rule.get("tiles", "").split()) == 36
                                for rule in matches), canonical)


if __name__ == "__main__":
    unittest.main()
