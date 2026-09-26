from __future__ import annotations

import hashlib
import importlib.util
import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR_PATH = REPO_ROOT / "tools" / "generate_gothic_arch.py"
RESOURCES = REPO_ROOT / "src" / "main" / "resources"
ERYDON_ASSETS = RESOURCES / "assets" / "erydon"
AUTHORING = ERYDON_ASSETS / "authoring_models" / "block" / "arch" / "gothic"

GEOMETRY_SIGNATURES = {
    "arch_gothic_corner_large_lower.json": "1b6e35dd246ce7406ef691f7538d3fc66ed6ea439ded4257c0b760fc9616417c",
    "arch_gothic_corner_large_upper.json": "b139a4cd0568bfa01dd7189cc8f96fe1845a3143cfefac924f8123a7ce9f91a1",
    "arch_gothic_corner_medium.json": "3583015e07e2621558164b7a7c39a5929f5d1f82609cf4284cecaf37c52e6c13",
    "arch_gothic_corner_small.json": "05cf4d3e9a92831c8c5fb8b9aa8da8a25d24bc01950b53257f03b0d4426e8902",
    "arch_gothic_icon.json": "264f264859fd5a0715778904e69ea89afba42fc843fbab5a87371ce990d17685",
    "arch_gothic_side_large.json": "9f61896a7ed3fbb524b6073a18d71cea42c88f17b8f470f0c457563ce43da918",
    "arch_gothic_side_medium.json": "9dac45222c567ea9c543e8842923e88598d9ade729fe8f709a9f96b63d6b79e2",
    "arch_gothic_side_small.json": "8a43eaa6beab56d10bd74e55d4b872a7d1c17ae5702522ace702b2752a39324b",
    "arch_gothic_top_large.json": "2bf85cf1fd65c3f696014892ae149d467535e0f06e159315aa30e06870059c8c",
}

SPEC = importlib.util.spec_from_file_location("generate_gothic_arch", GENERATOR_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {GENERATOR_PATH}")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


def load_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def geometry_signature(model: dict) -> str:
    model.pop("textures", None)
    for element in model.get("elements", []):
        for face in element.get("faces", {}).values():
            face.pop("uv", None)
            face.pop("texture", None)
            face.pop(GENERATOR.raw_uv.OFFSET_KEY, None)
    payload = json.dumps(
        model, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class GothicArchSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.modern_ids = GENERATOR._registered_modern_ids(RESOURCES)
        cls.gothic_ids = [GENERATOR._gothic_id(value) for value in cls.modern_ids]

    def test_generator_is_current(self) -> None:
        self.assertEqual([], GENERATOR.generate(REPO_ROOT, check=True))

    def test_medium_filler_is_inset_and_meets_cap_without_coplanar_overlap(self) -> None:
        model = load_json(AUTHORING / "arch_gothic_corner_medium.json")
        cap, filler = model["elements"][9], model["elements"][11]
        self.assertGreater(filler["from"][2], cap["from"][2])
        self.assertLess(filler["to"][2], cap["to"][2])
        self.assertEqual(cap["from"][1], filler["to"][1])
        self.assertEqual(cap["to"][0], filler["to"][0])
        self.assertGreaterEqual(filler["from"][0], cap["from"][0])
        self.assertEqual(12, len(model["elements"]))
        self.assertEqual(38, sum(len(element.get("faces", {})) for element in model["elements"]))

    def test_only_live_authoring_components_are_checked_in(self) -> None:
        self.assertEqual(
            {f"arch_gothic_{suffix}.json" for suffix in GENERATOR.MODEL_SUFFIXES},
            {path.name for path in AUTHORING.glob("*.json")},
        )
        self.assertFalse((AUTHORING / "arch_gothic_side_medium_upper.json").exists())
        self.assertFalse((AUTHORING / "arch_gothic_side_large_upper.json").exists())
        self.assertFalse((AUTHORING / "arch_gothic_large_assembly_preview.json").exists())

    def test_large_gap_filler_belongs_to_upper_row_and_keeps_arch_closed(self) -> None:
        lower = load_json(AUTHORING / "arch_gothic_corner_large_lower.json")
        upper = load_json(AUTHORING / "arch_gothic_corner_large_upper.json")
        filler = next(e for e in upper["elements"]
                      if e.get("name") == "gothic_large_upper_gap_filler")
        self.assertEqual([12.52145, 0, 0.001], filler["from"])
        self.assertEqual([13.63776, 12.94126, 15.999], filler["to"])
        # The formerly exposed upper strip retains its exact world bounds,
        # now rendered and lit by the upper block rather than its lower neighbour.
        self.assertAlmostEqual(28.94126, filler["to"][1] + 16)
        self.assertEqual(2, len(lower["elements"]))
        self.assertEqual(6, len(upper["elements"]))
        self.assertEqual(28, sum(len(e["faces"]) for model in (lower, upper)
                                 for e in model["elements"]))
        for model in (lower, upper):
            self.assertEqual(list(range(len(model["elements"]))),
                             model["groups"][0]["children"])

        # The only removed area (below the row boundary) is fully covered by
        # an existing curved panel. Both are convex, so checking the rectangle's
        # corners proves the entire removed strip is covered on front and back.
        panel = lower["elements"][1]
        rotation = GENERATOR.raw_uv.RawRotation.parse(panel["rotation"], (8, 8, 8), "panel")
        for face in ("east", "west"):
            polygon = [rotation.transform(v)[:2] for v in GENERATOR.raw_uv._face_vertices(
                panel["from"], panel["to"], face)]
            for x in (12.52145, 13.63776):
                for y in (15.02024, 16):
                    sides = [(b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0])
                             for a, b in zip(polygon, polygon[1:] + polygon[:1])]
                    self.assertTrue(all(s >= -1e-6 for s in sides) or all(s <= 1e-6 for s in sides),
                                    (face, x, y))

    def test_authoring_geometry_texture_and_uv_contract(self) -> None:
        explicit_counts = {}
        offset_counts = {}
        for filename, expected_signature in GEOMETRY_SIGNATURES.items():
            model = load_json(AUTHORING / filename)
            self.assertIn(model.get("format_version"), {"1.21.11", "1.9.0"}, filename)
            self.assertEqual(
                model.get("textures"),
                {"particle": GENERATOR.STONE_TEXTURE, "stone": GENERATOR.STONE_TEXTURE},
                filename,
            )
            explicit_counts[filename] = 0
            offset_counts[filename] = 0
            for element in model["elements"]:
                for face in element.get("faces", {}).values():
                    self.assertEqual("#stone", face.get("texture"), filename)
                    explicit_counts[filename] += "uv" in face
                    offset_counts[filename] += GENERATOR.raw_uv.OFFSET_KEY in face
            _, counts = GENERATOR.raw_uv._audit_document(
                model,
                f"arch/gothic/{filename}",
                hashlib.sha256((AUTHORING / filename).read_bytes()).hexdigest(),
            )
            self.assertEqual(0, counts.get("rotatedOutOfRangeFaces", 0), filename)
            self.assertEqual(expected_signature, geometry_signature(model), filename)

        self.assertEqual(16, explicit_counts["arch_gothic_corner_small.json"])
        self.assertEqual(84, explicit_counts["arch_gothic_icon.json"])
        self.assertTrue(
            all(
                count == 0
                for filename, count in explicit_counts.items()
                if filename not in {
                    "arch_gothic_corner_small.json",
                    "arch_gothic_icon.json",
                }
            )
        )
        self.assertEqual(66, sum(offset_counts.values()))

    def test_world_renderer_runs_after_continuity_and_selects_repeat_tiles(self) -> None:
        model_root = (
            REPO_ROOT
            / "src/main/java/com/oliver/erydon/client/model"
        )
        renderer = (model_root / "ArchRepeatCtmRenderer.java").read_text(encoding="utf-8")
        family = (model_root / "ArchRomanesqueBakedModel.java").read_text(encoding="utf-8")
        plugin = (model_root / "GothicArchCtmModelLoadingPlugin.java").read_text(encoding="utf-8")
        service = (model_root / "ErydonCtmService.java").read_text(encoding="utf-8")

        self.assertNotIn("GothicArchCtmRenderer", family)
        self.assertIn("ModelModifier.WRAP_LAST_PHASE, REPEAT_CTM_PHASE", plugin)
        self.assertIn("ArchRepeatCtmRenderer.Family.GOTHIC", plugin)
        self.assertIn("wrapped.getQuads(state, sourceCullFace", renderer)
        self.assertIn("SpiralStairCtmGeometry.split(lightFace, vertices)", renderer)
        self.assertIn("ErydonCtmService.repeatTileIndex(", renderer)
        self.assertIn("MutableQuadView.BAKE_NORMALIZED", renderer)
        self.assertIn("gothicArchCtmSetName", plugin)
        self.assertIn("ArchRepeatCtmRenderer.clearGeometryCache();", service)

    def test_all_registered_variants_have_complete_assets(self) -> None:
        self.assertEqual(162, len(self.gothic_ids))
        component_root = ERYDON_ASSETS / "models" / "block" / "arch" / "gothic"
        for block_id in self.gothic_ids:
            for path in (
                ERYDON_ASSETS / "blockstates" / f"{block_id}.json",
                ERYDON_ASSETS / "models" / "block" / "internal" / "wrapped" / f"{block_id}.json",
                ERYDON_ASSETS / "models" / "item" / f"{block_id}.json",
            ):
                self.assertTrue(path.is_file(), str(path))
            for suffix in GENERATOR.MODEL_SUFFIXES:
                self.assertTrue(
                    (component_root / GENERATOR._component_filename(block_id, suffix)).is_file(),
                    f"{block_id}:{suffix}",
                )

        expected_boxes = sum(
            len(load_json(AUTHORING / f"arch_gothic_{suffix}.json")["elements"])
            for suffix in GENERATOR.RENDER_SUFFIXES
        )
        java = (
            REPO_ROOT
            / "src/main/java/com/oliver/erydon/block/ArchGothicBlock.java"
        ).read_text(encoding="utf-8")
        self.assertEqual(
            expected_boxes,
            java.count("shape = VoxelShapes.union(shape, VoxelShapes.cuboid("),
        )

    def test_languages_tags_and_ctm_are_complete(self) -> None:
        expected = {f"erydon:{value}" for value in self.gothic_ids}
        tag_root = RESOURCES / "data" / "erydon" / "tags" / "blocks"
        self.assertEqual(expected, set(load_json(tag_root / "arch_gothic.json")["values"]))
        self.assertTrue(expected.issubset(set(load_json(tag_root / "arch.json")["values"])))
        self.assertTrue(expected.issubset(set(load_json(tag_root / "gothic.json")["values"])))
        self.assertFalse(
            any("_arch_gothic" in value for value in load_json(tag_root / "modern.json")["values"])
        )

        for filename in GENERATOR.LANGUAGE_PROFILE_NAMES:
            language = load_json(ERYDON_ASSETS / "lang" / filename)
            self.assertTrue(
                all(f"block.erydon.{block_id}" in language for block_id in self.gothic_ids)
            )
            for index in range(1, 4):
                self.assertIn(f"tooltip.erydon.family.arch_gothic.{index}", language)

        ctm_roots = (
            RESOURCES / "assets" / "minecraft" / "optifine" / "ctm",
            REPO_ROOT / "run-dev/resourcepacks/erydon-rp-16x-lite/assets/minecraft/optifine/ctm",
            REPO_ROOT / "run-dev/resourcepacks/erydon-rp-64x-pbr/assets/minecraft/optifine/ctm",
        )
        for root in ctm_roots:
            if not root.exists():
                continue
            text = "\n".join(
                path.read_text(encoding="utf-8") for path in root.rglob("*.properties")
            )
            self.assertTrue(all(value in text for value in expected), str(root))


if __name__ == "__main__":
    unittest.main()
