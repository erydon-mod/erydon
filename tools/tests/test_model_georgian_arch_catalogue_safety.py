"""Cross-check Georgian arch catalogue IDs, model links and CTM resources."""

from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/erydon"
sys.path.insert(0, str(ROOT / "tools"))

import generate_georgian_arch_catalogue as catalogue


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


class GeorgianArchCatalogueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ids = {path.stem for path in (ASSETS / "blockstates").glob("*_arch_georgian.json")}

    def test_all_material_finish_ids_are_canonical(self) -> None:
        source = (ROOT / "src/main/java/com/oliver/erydon/ModBlocks.java").read_text(encoding="utf-8")
        materials_body = re.search(r"ARCH_MODERN_MATERIALS\s*=\s*\{(.*?)\};", source, re.DOTALL).group(1)
        materials = re.findall(r'"([a-z]+)"', materials_body)
        expected = {material + finish + "_arch_georgian" for material in materials
                    for finish in ("", "_aged", "_ashlar", "_rusticated", "_hewn", "_rock")}
        self.assertEqual(len(expected), 162)
        self.assertEqual(self.ids, expected)
        self.assertFalse(list((ASSETS / "blockstates").glob("*_arch_georgian_aged.json")))

    def test_placed_and_item_references_resolve(self) -> None:
        missing_icons = [block_id for block_id in sorted(self.ids)
                         if not (ASSETS / "models/block/arch/georgian" / f"{block_id}_icon.json").is_file()]
        self.assertFalse(missing_icons, f"Missing Georgian icons: {len(missing_icons)}; first {missing_icons[:3]}")
        for block_id in sorted(self.ids):
            with self.subTest(block=block_id):
                state = load(ASSETS / "blockstates" / f"{block_id}.json")
                self.assertEqual(state, {"multipart": [{"apply": {"model": f"erydon:block/internal/wrapped/{block_id}"}}]})
                wrapped = load(ASSETS / "models/block/internal/wrapped" / f"{block_id}.json")
                self.assertEqual(wrapped["elements"], [])
                self.assertTrue(wrapped["textures"]["particle"].startswith("erydon:block/"))
                item = load(ASSETS / "models/item" / f"{block_id}.json")
                self.assertEqual(item["parent"], "erydon:block/arch/georgian/arch_georgian_icon")
                self.assertEqual(item["textures"]["stone"], wrapped["textures"]["particle"])
                icon = ASSETS / "models/block/arch/georgian" / f"{block_id}_icon.json"
                self.assertTrue(icon.is_file(), icon.relative_to(ROOT))
                self.assertEqual(load(icon)["parent"], "erydon:block/arch/georgian/arch_georgian_icon")

    def test_dedicated_native_ctm_covers_every_finish(self) -> None:
        rules = RES / "assets/minecraft/optifine/ctm/arch_georgian"
        self.assertEqual({path.name for path in rules.glob("*.properties")},
                         {f"a_{block_id}.properties" for block_id in self.ids})
        for block_id in sorted(self.ids):
            with self.subTest(block=block_id):
                raw = (rules / f"a_{block_id}.properties").read_bytes()
                self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
                fields = dict(line.split("=", 1) for line in raw.decode("utf-8").splitlines() if "=" in line)
                self.assertEqual(fields["method"], "repeat")
                self.assertEqual((fields["width"], fields["height"]), ("6", "6"))
                self.assertEqual(fields["matchBlocks"], "erydon:" + block_id)
                self.assertEqual(fields["faces"], "all")
                self.assertEqual(fields["connect"], "block")
                self.assertEqual(len(fields["tiles"].split()), 36)
                for tile in fields["tiles"].split():
                    self.assertTrue((RES / "assets/minecraft" / (tile + ".png")).is_file(), tile)

    def test_catalogue_tags_and_translations_are_complete(self) -> None:
        expected = {"erydon:" + block_id for block_id in self.ids}
        for kind in ("blocks", "items"):
            root = RES / "data/erydon/tags" / kind
            for category in ("arch_georgian", "arch", "georgian", "erydon_blocks"):
                members = set(load(root / f"{category}.json")["values"])
                self.assertTrue(expected <= members, f"{kind}/{category}")
            for finish in ("aged", "ashlar", "rusticated", "hewn", "rock"):
                members = set(load(root / f"{finish}.json")["values"])
                self.assertTrue({value for value in expected if "_" + finish + "_" in value} <= members)
        for filename in ("en_us.json", "de_de.json", "es_es.json"):
            language = load(ASSETS / "lang" / filename)
            for block_id in self.ids:
                self.assertIn("block.erydon." + block_id, language)
            for style in ("romanesque", "modern", "gothic", "georgian"):
                self.assertIn("6", language[f"tooltip.erydon.family.arch_{style}.1"])
            self.assertIn("1–5", language["tooltip.erydon.family.arch_georgian.3"])

    def test_catalogue_outputs_are_repeatable(self) -> None:
        for relative, expected in catalogue.expected_outputs(ROOT).items():
            with self.subTest(path=relative.as_posix()):
                self.assertEqual((ROOT / relative).read_bytes(), expected)


if __name__ == "__main__":
    unittest.main()
