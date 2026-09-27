"""Check every double circular column has the resources needed in game."""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "src/main/resources"
ASSETS = ROOT / "assets/erydon"


class DoubleCircularColumnAssetsTest(unittest.TestCase):
    def test_every_material_has_model_drop_language_and_ctm(self):
        sources = sorted(path.stem for path in (ASSETS / "blockstates").glob("*_column_circular.json"))
        self.assertEqual(54, len(sources))
        languages = {
            language: json.loads((ASSETS / f"lang/{language}.json").read_text(encoding="utf-8"))
            for language in ("en_us", "de_de", "es_es")
        }
        ctm_text = "\n".join(path.read_text(encoding="utf-8-sig") for path in
                             (ROOT / "assets/minecraft/optifine/ctm").rglob("*.properties"))
        for source in sources:
            target = source + "_double"
            with self.subTest(block=target):
                blockstate = json.loads((ASSETS / f"blockstates/{target}.json").read_text(encoding="utf-8"))
                self.assertEqual(f"erydon:block/internal/wrapped/{target}",
                                 blockstate["multipart"][0]["apply"]["model"])
                self.assertTrue((ASSETS / f"models/block/internal/wrapped/{target}.json").is_file())
                standard_item = json.loads((ASSETS / f"models/item/{source}.json").read_text(encoding="utf-8"))
                double_item = json.loads((ASSETS / f"models/item/{target}.json").read_text(encoding="utf-8"))
                self.assertEqual([0.55] * 3, standard_item["display"]["gui"]["scale"])
                self.assertEqual([0.65] * 3, double_item["display"]["gui"]["scale"])
                loot = json.loads((ROOT / f"data/erydon/loot_tables/blocks/{target}.json").read_text(encoding="utf-8"))
                condition = loot["pools"][0]["entries"][0]["conditions"][0]
                self.assertEqual({"part_x": "0", "part_z": "0"}, condition["properties"])
                self.assertTrue(all(f"block.erydon.{target}" in lang for lang in languages.values()))
                self.assertIn(f"erydon:{target}", ctm_text)


if __name__ == "__main__":
    unittest.main()
