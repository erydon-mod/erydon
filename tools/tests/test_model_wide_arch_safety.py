from __future__ import annotations

import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "src/main/resources"


class WideArchResourcesTest(unittest.TestCase):
    def test_every_registered_arch_keeps_native_repeat_coverage(self) -> None:
        with (RESOURCES / "data/erydon/id_migration.tsv").open(encoding="utf-8", newline="") as handle:
            aliases = {row["old_path"]: row["canonical_path"] for row in csv.DictReader(handle, delimiter="\t")}
        covered = set()
        for path in (RESOURCES / "assets/minecraft/optifine/ctm").rglob("*.properties"):
            content = path.read_bytes()
            text = content.decode("utf-8-sig").replace("\\\n", " ").replace("\\\r\n", " ")
            properties = dict(re.findall(r"^([^#\s=]+)\s*=\s*(.*)$", text, re.MULTILINE))
            matches = properties.get("matchBlocks", "").split()
            arches = [match.removeprefix("erydon:") for match in matches if "_arch_" in match]
            if not arches or properties.get("method", "").strip() != "repeat":
                continue
            self.assertFalse(content.startswith(b"\xef\xbb\xbf"), str(path))
            covered.update(aliases.get(block, block) for block in arches)
        for style in ("romanesque", "modern", "gothic"):
            blockstates = sorted((RESOURCES / "assets/erydon/blockstates").glob(f"*_arch_{style}.json"))
            self.assertEqual(162, len(blockstates), style)
            for path in blockstates:
                self.assertIn(path.stem, covered, path.stem)
                document = json.loads(path.read_text(encoding="utf-8-sig"))
                self.assertEqual(1, len(document["multipart"]), path.stem)
                self.assertNotIn("when", document["multipart"][0], path.stem)
                model = document["multipart"][0]["apply"]["model"]
                self.assertTrue(model.startswith("erydon:block/internal/wrapped/"), path.stem)
                # Published aged IDs keep their legacy resource paths through the migration map.
                self.assertTrue((RESOURCES / "assets/erydon/models" / (model.removeprefix("erydon:") + ".json")).is_file())

    def test_all_languages_explain_the_default_and_debug_widths(self) -> None:
        for language in ("en_us", "de_de", "es_es"):
            document = json.loads((RESOURCES / f"assets/erydon/lang/{language}.json").read_text(encoding="utf-8-sig"))
            for style in ("romanesque", "modern", "gothic"):
                tooltip = document[f"tooltip.erydon.family.arch_{style}.1"]
                self.assertIn("3", tooltip)
                self.assertIn("6", tooltip)


if __name__ == "__main__":
    unittest.main()
