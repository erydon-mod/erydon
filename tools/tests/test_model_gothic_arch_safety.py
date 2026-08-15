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
    "arch_gothic_corner_large_lower.json": "43efcace93bc40135a3f1640dd728534a0f2fec355108123ef88bac019765c47",
    "arch_gothic_corner_large_upper.json": "3e0e679d157bc87ae0071e30863dc9008a29ec23810aa2f2a1c8167673cf2865",
    "arch_gothic_corner_medium.json": "9d1f54a4a8f84560ff672292c5bf9faa2a1aa5372c64c68ef504a31bd37ca179",
    "arch_gothic_corner_small.json": "57c34215b0d2b5a5a57db93dd69736b76e9a85f81d81322c9aed5682f8d42f17",
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

    def test_only_live_authoring_components_are_checked_in(self) -> None:
        self.assertEqual(
            {f"arch_gothic_{suffix}.json" for suffix in GENERATOR.MODEL_SUFFIXES},
            {path.name for path in AUTHORING.glob("*.json")},
        )
        self.assertFalse((AUTHORING / "arch_gothic_side_medium_upper.json").exists())
        self.assertFalse((AUTHORING / "arch_gothic_side_large_upper.json").exists())
        self.assertFalse((AUTHORING / "arch_gothic_large_assembly_preview.json").exists())

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
