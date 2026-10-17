from __future__ import annotations

import hashlib
import itertools
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from model_uv_safety import audit_models  # noqa: E402


ASSETS = ROOT / "src/main/resources/assets/erydon"
MODELS = ASSETS / "models"
PENDANTS = MODELS / "block/light/pendant"
MATERIALS = ("borealis", "gelastrum", "mielonyx", "selenephos")
FRAMES = ("bronze", "silver")
BODIES = ("small", "large")

# Recorded before the repair: each entry is one complete authored UV island.
# These references protect geometry and texture scale independently of the UV audit.
REPAIRED_ISLANDS = (
    51, 64, 156, 167, 248, 249, 258, 273, 317, 318, 363, 376,
    416, 427, 456, 457, 466, 481, 525, 526, 571, 584, 624, 635,
    664, 665, 674, 689, 716, 717, 785, 786,
)
ORIGINAL_NON_UV_SHA256 = "fa84b68536211043e594446f3cc090d2d3f833fe5df3800feac2a94ab08c92f5"
UNTOUCHED_UVS_SHA256 = "4f56e31c7de1bfaf189732d5118af164f0bf71423f142303b4a4b22ed13c353e"
ISLAND_LAYOUT_SHA256 = "84e5b9c6a0145dca497d16f954cadd20e56234c2a54739c528b78a209e6dfa6c"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def digest(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False).encode("utf-8")).hexdigest()


def without_uv(value: object) -> object:
    if isinstance(value, dict):
        return {key: without_uv(child) for key, child in value.items() if key != "uv"}
    if isinstance(value, list):
        return [without_uv(child) for child in value]
    return value


class PendantUvSafetyTests(unittest.TestCase):
    def test_both_real_body_masters_have_atlas_safe_uvs(self) -> None:
        for body in BODIES:
            with self.subTest(body=body):
                report = audit_models([PENDANTS / f"light_pendant_{body}.json"], canonical_root=MODELS)
                self.assertEqual(0, report["summary"]["parseFailures"])
                self.assertEqual(0, report["summary"]["validationFailures"])
                self.assertEqual(0, len(report["findings"]), "A pendant face can sample a neighbouring atlas sprite")

    def test_large_master_retains_its_geometry_materials_and_render_cost(self) -> None:
        model = load(PENDANTS / "light_pendant_large.json")
        self.assertEqual(848, len(model["elements"]))
        self.assertEqual(4529, sum(len(element["faces"]) for element in model["elements"]))
        self.assertEqual(ORIGINAL_NON_UV_SHA256, digest(without_uv(model)))

    def test_repaired_islands_keep_uv_scale_direction_and_internal_offsets(self) -> None:
        model = load(PENDANTS / "light_pendant_large.json")
        uvs = [{name: face["uv"] for name, face in element["faces"].items()}
               for element in model["elements"]]
        untouched = {index: chart for index, chart in enumerate(uvs) if index not in REPAIRED_ISLANDS}
        self.assertEqual(UNTOUCHED_UVS_SHA256, digest(untouched))
        layout = {}
        for index in REPAIRED_ISLANDS:
            chart = uvs[index]
            minimum_v = min(value for uv in chart.values() for value in uv[1::2])
            layout[index] = {
                name: [value - minimum_v if axis % 2 else value for axis, value in enumerate(uv)]
                for name, uv in chart.items()
            }
        # Compare translation-independent chart shapes with the pre-repair asset,
        # rather than prescribing the repair's arithmetic. Clamps, stretch, flips
        # and independent face shifts all change this layout fingerprint.
        self.assertEqual(ISLAND_LAYOUT_SHA256, digest(layout))

    def test_every_placed_material_frame_and_body_inherits_the_safe_master(self) -> None:
        expected_blocks = {f"{material}_diaphanes_light_pendant" for material in MATERIALS}
        actual_blocks = {path.stem for path in (ASSETS / "blockstates").glob("*_diaphanes_light_pendant.json")}
        self.assertEqual(expected_blocks, actual_blocks)
        for material in MATERIALS:
            block = f"{material}_diaphanes_light_pendant"
            entries = load(ASSETS / "blockstates" / f"{block}.json")["multipart"]
            for body, frame, part in itertools.product(BODIES, FRAMES, ("single", "bottom")):
                with self.subTest(material=material, body=body, frame=frame, part=part):
                    selected = [entry["apply"]["model"] for entry in entries
                                if entry["when"].get("body") == body
                                and entry["when"].get("frame") == frame
                                and entry["when"].get("part") == part]
                    child_id = f"block/light/pendant/{block}_{body}_{frame}"
                    self.assertEqual([f"erydon:{child_id}"], selected)
                    child = load(MODELS / f"{child_id}.json")
                    self.assertEqual(f"erydon:block/light/pendant/light_pendant_{body}", child["parent"])
                    self.assertNotIn("elements", child, "A child must not bypass the corrected master")
                    self.assertEqual({"stone": f"erydon:block/{material}_diaphanes_block",
                                      "metal": f"erydon:block/cover_{frame}_gloss"}, child["textures"])
            item = load(MODELS / "item" / f"{block}.json")
            self.assertEqual(f"erydon:block/light/pendant/{block}_small_bronze", item["parent"])


if __name__ == "__main__":
    unittest.main()
