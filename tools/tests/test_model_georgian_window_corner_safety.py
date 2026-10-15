import importlib.util
import csv
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("window_corners", ROOT / "tools/generate_georgian_window_corners.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


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
            self.assertEqual(retained, derived["elements"])
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
        for suffix in ("mid", "edge_lh", "edge_rh"):
            derived = generator.load(generator.MODELS / f"window_french_georgian_open_upper_single_{suffix}.json")
            self.assertEqual(fixed, derived["elements"][:len(fixed)])
            if suffix == "mid":
                self.assertEqual(len(fixed), len(derived["elements"]))
                self.assertTrue(all(element["from"][1] >= 4.846 for element in derived["elements"]))
            else:
                wing = suffix.replace("edge_", "multi_")
                original = generator.load(generator.MODELS / f"window_french_georgian_open_upper_{wing}.json")
                self.assertEqual([element for index, element in enumerate(original["elements"])
                                  if index in generator.WINGS[wing][0]], derived["elements"][len(fixed):])

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
