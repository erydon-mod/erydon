import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/erydon"


class GothicColumnControlsSafetyTests(unittest.TestCase):
    def test_every_material_and_aged_variant_reuses_the_wrapped_raw_shaft(self):
        states = sorted((ASSETS / "blockstates").glob("*column_gothic.json"))
        self.assertEqual(54, len(states))
        for state in states:
            block_id = state.stem
            wrapper = json.loads(state.read_text(encoding="utf-8-sig"))
            self.assertEqual({"multipart": [{"apply": {"model": f"erydon:block/internal/wrapped/{block_id}"}}]}, wrapper)
            aged = "_aged_" in block_id
            base_id = block_id.replace("_aged_", "_")
            child = ASSETS / f"models/block/column/gothic/{base_id}_pillar{'_aged' if aged else ''}.json"
            model = json.loads(child.read_text(encoding="utf-8-sig"))
            self.assertEqual("erydon:block/column/gothic/column_gothic_pillar", model["parent"])
            material = base_id.removesuffix("_column_gothic")
            self.assertEqual(f"erydon:block/{material}_block{'_aged' if aged else ''}", model["textures"]["stone"])
        loader = (ROOT / "src/main/java/com/oliver/erydon/client/model/ErydonRawModelLoadingPlugin.java").read_text()
        self.assertIn('Map.entry("column_gothic/pillar", new Identifier(Erydon.MOD_ID, "authoring_models/block/column/gothic/column_gothic_pillar.json"))', loader)

    def test_native_repeat_rules_cover_all_existing_gothic_ids_without_bom(self):
        expected = {f"erydon:{path.stem}" for path in (ASSETS / "blockstates").glob("*column_gothic.json")}
        covered = set()
        rules = ROOT / "src/main/resources/assets/minecraft/optifine/ctm"
        for path in rules.rglob("*.properties"):
            data = path.read_bytes()
            text = data.decode("utf-8-sig").replace("\\\n", " ").replace("\\\r\n", " ")
            values = re.findall(r"^matchBlocks\s*=\s*(.*)$", text, re.MULTILINE)
            matching = expected.intersection(re.findall(r"erydon:[a-z0-9_/]+", " ".join(values)))
            if matching:
                self.assertFalse(data.startswith(b"\xef\xbb\xbf"), path)
                self.assertIn("method=repeat", text.replace(" ", ""), path)
                covered.update(matching)
        self.assertEqual(expected, covered)


if __name__ == "__main__":
    unittest.main()
