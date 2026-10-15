from __future__ import annotations

from collections import Counter
import copy
import json
import math
from pathlib import Path
import re
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import generate_grand_coping as grand
import model_z_fighting_safety as geometry


class GrandCopingSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.master = json.loads(grand.MASTER.read_bytes())
        cls.models = {name: json.loads((grand.RAW / f"{grand.SUFFIX}_{name}.json").read_bytes())
                      for name in grand.PROFILES}

    def test_generated_outputs_are_current_and_add_a_separate_135_variant_family(self):
        variants = list(grand.variants())
        self.assertEqual(135, len(variants))
        self.assertEqual(135, len({row[2] for row in variants}))
        self.assertEqual(27, len({row[0] for row in variants}))
        self.assertEqual(set(grand.common.FINISHES), {row[1] for row in variants})
        for path, payload in grand.outputs().items():
            self.assertEqual(payload, path.read_bytes(), str(path.relative_to(grand.ROOT)))
            if path.name.endswith("_coping_georgian.json") or path.name == "coping.properties":
                self.fail("Grand generation must not replace the original family's assets")

    def test_original_authored_geometry_is_retained_except_semantic_names(self):
        self.assertEqual(12, len(self.master["elements"]))
        flat = self.models["flat"]
        for original, prepared in zip(self.master["elements"], flat["elements"][:12]):
            prepared = copy.deepcopy(prepared)
            prepared.pop("erydon_coping_band", None)
            prepared["name"] = original["name"]
            self.assertEqual(original, prepared)
        self.assertEqual("lower_band", flat["elements"][2]["name"])
        self.assertEqual("lower_lip", flat["elements"][3]["name"])
        self.assertEqual(["edge_north", "edge_south", "edge_east", "edge_west"],
                         [e["name"] for e in flat["elements"][8:12]])

    def test_triangles_have_real_area_outward_winding_and_safe_authored_uvs(self):
        for profile, model in self.models.items():
            group_rotations, _ = geometry._raw_group_state(model, len(model["elements"]))
            faces, unsupported = geometry._build_faces(model["elements"], raw_group_rotations=group_rotations)
            self.assertEqual([], unsupported, profile)
            corners = [face for face in faces if face.element.get("erydon_coping_corner")]
            self.assertEqual(32, len(corners), profile)
            for face in faces:
                self.assertGreater(face.area, 0, (profile, face.element_index))
                self.assertTrue(all(math.isfinite(v) for point in face.vertices for v in point))
                self.assertTrue(all(0 <= value <= 16 for value in face.face["uv"]))
            for element in model["elements"]:
                if not element.get("erydon_coping_corner"):
                    continue
                points = next(iter(element["faces"].values()))["erydon_vertices"]
                self.assertEqual(points[2], points[3], "Raw triangle must use a repeated fourth vertex")
                normal = grand.cross(grand.subtract(points[1], points[0]), grand.subtract(points[2], points[0]))
                start, end, _, _ = grand.PROFILES[profile]
                centre_x = (start + end) * 8
                centre_z = 8
                radial = ((sum(p[0] for p in points[:3]) / 3) - centre_x,
                          0, (sum(p[2] for p in points[:3]) / 3) - centre_z)
                self.assertGreater(sum(v * w for v, w in zip(normal, radial)), 0, (profile, element["name"]))

    def test_each_finished_corner_is_manifold_and_meets_the_authored_strip(self):
        for profile, model in self.models.items():
            group_rotations, _ = geometry._raw_group_state(model, len(model["elements"]))
            all_faces, unsupported = geometry._build_faces(model["elements"], raw_group_rotations=group_rotations)
            self.assertFalse(unsupported)
            source_faces = [face for face in all_faces if face.element_index < 12]
            groups = {}
            for face in all_faces:
                element = face.element
                if element.get("erydon_coping_corner"):
                    groups.setdefault((element["erydon_coping_band"], element["name"]), []).append(face)
            self.assertEqual(8, len(groups))
            for (band, corner), faces in groups.items():
                with self.subTest(profile=profile, band=band, corner=corner):
                    self.assertEqual(4, len(faces))
                    self.check_corner_seams(band, corner, faces, source_faces)

    def check_corner_seams(self, band, corner, faces, source_faces):
        edges = Counter()
        owners = {}
        originals = [face for face in source_faces if face.element.get("erydon_coping_band") == band]
        for face in faces:
            points = list(face.vertices[:3])
            for a, b in zip(points, points[1:] + points[:1]):
                key = tuple(sorted(tuple(round(v, 7) for v in p) for p in (a, b)))
                edges[key] += 1
                owners[key] = face
        self.assertEqual(4, sum(count == 1 for count in edges.values()), (band, corner))
        self.assertEqual(4, sum(count == 2 for count in edges.values()), (band, corner))
        self.assertTrue(all(count in (1, 2) for count in edges.values()))
        for (a, b), count in edges.items():
            if count != 1:
                continue
            midpoint = tuple((x + y) / 2 for x, y in zip(a, b))
            self.assertTrue(any(self.on_face(midpoint, face) and face.face_name == owners[(a, b)].face_name
                                for face in originals),
                            (band, corner, "Unfinished boundary", midpoint))

    @staticmethod
    def on_face(point, face):
        if abs(sum(v * n for v, n in zip(point, face.normal)) - face.plane_d) > .00002:
            return False
        return all(face.bounds_min[i] - .00002 <= point[i] <= face.bounds_max[i] + .00002 for i in range(3))

    def test_triangle_profile_derivation_moves_explicit_vertices_and_keeps_uvs(self):
        flat = self.models["flat"]
        for profile, (start, end, high, low) in grand.PROFILES.items():
            model = self.models[profile]
            self.assertEqual(44, len(model["elements"]))
            scale = math.hypot(end - start, high - low)
            for original, derivative in zip(flat["elements"][12:], model["elements"][12:]):
                edge = original["name"]
                shift = (scale - 1) * 16 if edge.endswith("_east") else 0
                a = next(iter(original["faces"].values()))
                b = next(iter(derivative["faces"].values()))
                self.assertEqual(a["uv"], b["uv"])
                for source, target in zip(a["erydon_vertices"], b["erydon_vertices"]):
                    for expected, actual in zip((start * 16 + source[0] + shift, high * 16 + source[1], source[2]), target):
                        self.assertAlmostEqual(expected, actual, places=8)

    def test_each_native_rule_is_bom_free_and_reuses_existing_pack_tile_paths(self):
        for material, finish, block_id, _ in grand.variants():
            prefix = material + ("_" + finish if finish else "")
            folder = grand.RESOURCES / f"assets/minecraft/optifine/ctm/{prefix}"
            original = (folder / "coping.properties").read_bytes()
            payload = (folder / "coping_georgian_grand.properties").read_bytes()
            self.assertFalse(payload.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(original, re.sub(rb"(?m)^matchBlocks=.*$",
                                             ("matchBlocks=erydon:" + block_id.removesuffix("_grand")).encode(), payload))
            self.assertIn(("matchBlocks=erydon:" + block_id).encode(), payload)
            self.assertEqual(36, len(re.search(rb"(?m)^tiles=(.*)$", payload).group(1).split()))

    def test_translations_items_loot_and_material_tags_cover_every_variant(self):
        ids = {"erydon:" + row[2] for row in grand.variants()}
        for locale in ("en_us", "de_de", "es_es"):
            lang = json.loads((grand.ASSETS / f"lang/{locale}.json").read_bytes())
            for block_id in ids:
                self.assertIn("block.erydon." + block_id.removeprefix("erydon:"), lang)
            for line in (1, 2):
                self.assertIn(f"tooltip.erydon.family.{grand.SUFFIX}.{line}", lang)
        for kind in ("blocks", "items"):
            tag = grand.RESOURCES / f"data/erydon/tags/{kind}/{grand.SUFFIX}.json"
            self.assertEqual(ids, set(json.loads(tag.read_bytes())["values"]))
            common = json.loads((grand.RESOURCES / f"data/erydon/tags/{kind}/coping.json").read_bytes())["values"]
            self.assertEqual(270, len(common))
            self.assertTrue(ids.issubset(common))
        for material, finish, block_id, texture in grand.variants():
            state = json.loads((grand.ASSETS / f"blockstates/{block_id}.json").read_bytes())
            self.assertEqual("erydon:block/internal/wrapped/" + block_id, state["variants"][""]["model"])
            wrapper = json.loads((grand.ASSETS / f"models/block/internal/wrapped/{block_id}.json").read_bytes())
            self.assertEqual("erydon:block/" + texture, wrapper["textures"]["particle"])
            self.assertNotIn("elements", wrapper)
            loot = json.loads((grand.RESOURCES / f"data/erydon/loot_tables/blocks/{block_id}.json").read_bytes())
            self.assertEqual("erydon:" + block_id, loot["pools"][0]["entries"][0]["name"])


if __name__ == "__main__":
    unittest.main()
