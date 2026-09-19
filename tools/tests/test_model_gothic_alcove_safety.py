from __future__ import annotations

import importlib.util
import hashlib
import json
import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR_PATH = REPO_ROOT / "tools" / "generate_gothic_alcove.py"
RESOURCES = REPO_ROOT / "src" / "main" / "resources"
ERYDON_ASSETS = RESOURCES / "assets" / "erydon"
AUTHORING = ERYDON_ASSETS / "authoring_models" / "block" / "alcove"

AUTHORITATIVE_GEOMETRY_SIGNATURES = {
    "alcove_gothic_double_side_right.json": "7a68792864365f0dd4043a8d1c943fdda0aa5822adf37c63add8409895addde4",
    "alcove_gothic_double_side_left.json": "cf3726bbabcc511578e6df0a574024c76e8c3423b425047c109dcaf92865c1ee",
    "alcove_georgian_triple_side_left.json": "61c14ada2f78425dc95e38a2aeae9930b9c46e76843c011fe9f99ffc91f5727f",
    "alcove_georgian_triple_side_center.json": "14c92e48b9fc6a65526d806f2919c9089293841218a98c8fe31f7a5c4e386d9c",
    "alcove_georgian_triple_side_right.json": "bcada60bd27fbe5e09dd09f5a716293195b130c1ef3c9d8b2a73138b4a045037",
    "alcove_georgian_triple_top_left.json": "94f640b09e1407e02c005475adcc15a561d202f697c7b63e14737c583b7bb8fd",
    "alcove_georgian_triple_top_center.json": "c1a3137b6fdf6c0274fda2d4cc8b4fdfad2fbbe4b004f45b000f1770b1007a09",
    "alcove_georgian_triple_top_right.json": "5d677ef709930d0370527fe9e4bdef11ddde8355ed0412692285427c0e663fe8",
    "alcove_gothic_single_top.json": "fbd06def03a9aecd4f6bebc65f57dd032b592c97ad814567822febd06bf538f6",
    "alcove_gothic_double_top_left.json": "21553a4f811f1c059ad6e7ce2e164e0cf6e3c57a4bcf041f4ca5555e66663c89",
    "alcove_gothic_double_top_right.json": "53a74dcf7727789c88751fdcaa986fbc168855a7139e072ac8a9b06e4d5d2f26",
    "alcove_gothic_triple_side_left.json": "1c49b890c9188a1076b51ffef290769732691d18d1e9bd6d81eb13c5f6f71d1c",
    "alcove_gothic_triple_side_center.json": "06faa76bcaedb422079eeeab7e433a7ddb27126160efeb5d893df173fd5df296",
    "alcove_gothic_triple_side_right.json": "dcb545360c4c6e16bbb5a63573eab6db5bbaffe83f88d654669b4e2f2c32230e",
    "alcove_gothic_triple_top_left.json": "e8dd77f772d691548951e449c30d9d7a7bfc7110d32ce62e85af224a434366fe",
    "alcove_gothic_triple_top_center.json": "88494afad272cc17a4e12567ddca0c6b58f6a91bcc6b905267262b4b1a9f432c",
    "alcove_gothic_triple_top_right.json": "df04db9c55f631b79d4af72ddcf51d3fcb97ed2dbd76a7b9f82a624ba3934beb",
    "alcove_gothic_icon.json": "852f0ffc283380bca0821df4630ce39d73316089d5e3efbac8a15b4e81a9a918",
}

SPEC = importlib.util.spec_from_file_location("generate_gothic_alcove", GENERATOR_PATH)
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
    payload = json.dumps(
        model,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class AlcoveSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.georgian_ids = GENERATOR._registered_georgian_ids(RESOURCES)
        cls.gothic_ids = [GENERATOR._gothic_id(value) for value in cls.georgian_ids]

    def test_copied_authoring_models_preserve_the_georgian_topology(self) -> None:
        for gothic_name, georgian_name in GENERATOR.COPIED_AUTHORING_MODELS.items():
            self.assertEqual(
                (AUTHORING / gothic_name).read_bytes(),
                (AUTHORING / georgian_name).read_bytes(),
            )

    def test_manual_authoring_models_preserve_restored_geometry_and_contract(self) -> None:
        self.assertEqual(
            set(GENERATOR.MANUAL_AUTHORING_MODELS),
            set(AUTHORITATIVE_GEOMETRY_SIGNATURES),
        )
        total_offsets = 0
        for filename, expected_signature in AUTHORITATIVE_GEOMETRY_SIGNATURES.items():
            model = load_json(AUTHORING / filename)
            self.assertEqual(model.get("format_version"), "1.21.11", filename)
            self.assertEqual(
                model.get("textures"),
                {
                    "particle": GENERATOR.STONE_TEXTURE,
                    "stone": GENERATOR.STONE_TEXTURE,
                },
                filename,
            )
            self.assertTrue(model.get("elements"), filename)
            for element in model["elements"]:
                for face in element.get("faces", {}).values():
                    self.assertNotIn("uv", face, filename)
                    self.assertEqual(face.get("texture"), "#stone", filename)
                    total_offsets += GENERATOR.raw_uv.OFFSET_KEY in face
            _, counts = GENERATOR.raw_uv._audit_document(
                model,
                f"alcove/{filename}",
                hashlib.sha256((AUTHORING / filename).read_bytes()).hexdigest(),
            )
            self.assertEqual(0, counts.get("rotatedOutOfRangeFaces", 0), filename)
            self.assertEqual(geometry_signature(model), expected_signature, filename)
        self.assertEqual(339, total_offsets)

    def test_all_registered_variants_have_complete_assets(self) -> None:
        self.assertEqual(len(self.georgian_ids), 162)
        self.assertEqual(len(set(self.gothic_ids)), 162)
        for style, block_ids in (
            ("georgian", self.georgian_ids),
            ("gothic", self.gothic_ids),
        ):
            component_root = ERYDON_ASSETS / "models" / "block" / "alcove" / style
            for block_id in block_ids:
                for path in (
                    ERYDON_ASSETS / "blockstates" / f"{block_id}.json",
                    ERYDON_ASSETS / "models" / "block" / "internal" / "wrapped" / f"{block_id}.json",
                    ERYDON_ASSETS / "models" / "item" / f"{block_id}.json",
                ):
                    self.assertTrue(path.is_file(), str(path))
                for suffix in GENERATOR.MODEL_SUFFIXES:
                    filename = GENERATOR._component_filename(block_id, suffix)
                    self.assertTrue((component_root / filename).is_file(), filename)

    def test_triple_components_use_the_existing_back_base_and_texture_pipeline(self) -> None:
        triple_suffixes = set(GENERATOR.TRIPLE_MODEL_SUFFIXES)
        self.assertEqual(
            triple_suffixes,
            {
                "triple_side_left",
                "triple_side_center",
                "triple_side_right",
                "triple_top_left",
                "triple_top_center",
                "triple_top_right",
            },
        )
        for style in ("georgian", "gothic"):
            component_root = ERYDON_ASSETS / "models" / "block" / "alcove" / style
            sample_id = f"aganite_alcove_{style}"
            for suffix in triple_suffixes:
                filename = GENERATOR._component_filename(sample_id, suffix)
                document = load_json(component_root / filename)
                self.assertEqual(
                    document["parent"],
                    f"erydon:block/alcove/{style}/alcove_{style}_{suffix}",
                )
                self.assertEqual(document["textures"]["stone"], "erydon:block/aganite_block")

        baked_source = (
            REPO_ROOT
            / "src"
            / "main"
            / "java"
            / "com"
            / "oliver"
            / "erydon"
            / "client"
            / "model"
            / "AlcoveBakedModel.java"
        ).read_text(encoding="utf-8")
        self.assertIn('suffixes.add("back")', baked_source)
        self.assertIn('suffixes.add("base")', baked_source)
        self.assertIn('case TRIPLE_CENTER -> "triple_side_center"', baked_source)
        self.assertIn('case TRIPLE_CENTER -> "triple_top_center"', baked_source)
        self.assertIn("suffixes.addAll(TRIPLE_MODEL_SUFFIXES);", baked_source)
        self.assertNotIn("effectiveSpan", baked_source)

        for style in ("georgian", "gothic"):
            preview_name = f"_preview_only_alcove_{style}_triple_combined.json"
            component_root = ERYDON_ASSETS / "models" / "block" / "alcove" / style
            self.assertFalse((AUTHORING / preview_name).exists())
            self.assertFalse(any(path.name == preview_name for path in component_root.rglob("*.json")))

        registration_source = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "oliver" / "erydon" / "ModBlocks.java"
        ).read_text(encoding="utf-8")
        self.assertIn("int maxClusterWidth = 3;", registration_source)
        self.assertNotIn('"gothic".equals(style) ? 3 : 2', registration_source)

    def test_languages_tags_ctm_and_cluster_identity_are_complete(self) -> None:
        expected = {f"erydon:{value}" for value in self.gothic_ids}
        tag_root = RESOURCES / "data" / "erydon" / "tags" / "blocks"
        self.assertEqual(set(load_json(tag_root / "alcove_gothic.json")["values"]), expected)
        self.assertTrue(expected.issubset(set(load_json(tag_root / "alcove.json")["values"])))
        self.assertTrue(expected.issubset(set(load_json(tag_root / "gothic.json")["values"])))
        self.assertFalse(
            any("_alcove_gothic" in value for value in load_json(tag_root / "georgian.json")["values"])
        )

        for filename in GENERATOR.LANGUAGE_PROFILE_NAMES:
            document = load_json(ERYDON_ASSETS / "lang" / filename)
            self.assertTrue(
                all(f"block.erydon.{block_id}" in document for block_id in self.gothic_ids)
            )
            self.assertIn("tooltip.erydon.family.alcove_gothic.1", document)
            self.assertIn("tooltip.erydon.family.alcove_gothic.2", document)

        ctm_roots = (
            RESOURCES / "assets" / "minecraft" / "optifine" / "ctm",
            REPO_ROOT / "run-dev" / "resourcepacks" / "erydon-rp-16x-lite" / "assets" / "minecraft" / "optifine" / "ctm",
            REPO_ROOT / "run-dev" / "resourcepacks" / "erydon-rp-64x-pbr" / "assets" / "minecraft" / "optifine" / "ctm",
        )
        mapping = {
            f"erydon:{source}": f"erydon:{target}"
            for source, target in zip(self.georgian_ids, self.gothic_ids)
        }
        for index, root in enumerate(ctm_roots):
            if not root.is_dir():
                continue
            contents = []
            for path in root.rglob("*.properties"):
                raw = path.read_bytes()
                self.assertFalse(raw.startswith(b"\xef\xbb\xbf"), str(path))
                contents.append(raw.decode("utf-8"))
            joined = "\n".join(contents)
            georgian_present = {
                source
                for source in mapping
                if GENERATOR._contains_identifier(joined, source)
            }
            gothic_present = {
                target
                for target in mapping.values()
                if GENERATOR._contains_identifier(joined, target)
            }
            self.assertEqual(
                {mapping[source] for source in georgian_present},
                gothic_present,
                str(root),
            )
            if index == 0:
                self.assertEqual(set(mapping), georgian_present, str(root))

        block_source = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "oliver" / "erydon" / "block" / "AlcoveBlock.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public enum AlcoveClusterWidth", block_source)
        self.assertIn("WIDTH_OVERRIDE_SCOPE", block_source)
        self.assertNotIn("EnumProperty<AlcoveClusterWidth>", block_source)
        self.assertRegex(block_source, r"sameClusterBlock[\s\S]*state\.isOf\(this\)")
        self.assertRegex(
            block_source,
            r"frontLeftDirection\(Direction facing\)[\s\S]*?return facing\.rotateYClockwise\(\)",
        )
        self.assertRegex(
            block_source,
            r"frontRightDirection\(Direction facing\)[\s\S]*?return facing\.rotateYCounterclockwise\(\)",
        )
        self.assertRegex(
            block_source,
            r'SINGLE\("single"\),\s*LEFT\("left"\),\s*RIGHT\("right"\),\s*TRIPLE_LEFT',
        )

    def test_generator_is_idempotent(self) -> None:
        self.assertEqual(GENERATOR.generate(REPO_ROOT, check=True), [])


if __name__ == "__main__":
    unittest.main()
