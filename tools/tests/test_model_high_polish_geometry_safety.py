"""Geometry regressions behind the visible window, alcove and arch seams."""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import model_raw_uv_safety as raw

MODELS = ROOT / "src/main/resources/assets/erydon/authoring_models/block"


def vertices(element, face):
    rotation = raw.RawRotation.parse(element.get("rotation"), [8, 8, 8], "test")
    return [rotation.transform(p) for p in raw._face_vertices(element["from"], element["to"], face)]


class HighPolishGeometryTests(unittest.TestCase):
    def test_double_sides_follow_their_own_dome_rim(self):
        for style in ("georgian", "gothic"):
            for side in ("left", "right"):
                top = json.loads((MODELS / f"alcove/alcove_{style}_double_top_{side}.json").read_text())["elements"]
                walls = json.loads((MODELS / f"alcove/alcove_{style}_double_side_{side}.json").read_text())["elements"]
                for wall, rim in zip(walls[:4], (top[i] for i in (39, 38, 37, 36))):
                    edge = sorted(vertices(rim, "down"), key=lambda p: p[1])[:2]
                    actual = vertices(wall, "south")
                    for x, y, z in edge:
                        self.assertTrue(any(abs(px-x) < .0001 and abs(pz-z) < .0001 for px, _, pz in actual),
                                        (style, side, edge, actual))
                        self.assertLessEqual(wall["from"][1], y)
                        self.assertGreaterEqual(wall["to"][1], y)
                    self.assertAlmostEqual(16, wall["to"][1] - wall["from"][1])
                    self.assertEqual(16, wall["to"][1], "Sides must not protrude through the roof")
                    self.assertNotIn("up", wall["faces"], "Buried joint caps must not overlap")
                    self.assertNotIn("down", wall["faces"])
                    self.assertGreaterEqual(wall["to"][2] - wall["from"][2], .34)
                for closure in top[4:8]:
                    # Keep a substantial gap beneath the roof; tiny separations flicker with POM.
                    self.assertLessEqual(max(v[1] for v in vertices(closure, "up")), 15.1201)

    def test_arch_fillers_share_curve_lighting_depth_without_coplanar_overlap(self):
        for size, count, fillers in (("small", 16, (17, 18)), ("medium", 8, (11,))):
            elements = json.loads((MODELS / f"arch/gothic/arch_gothic_corner_{size}.json").read_text())["elements"]
            near, far = [], []
            for curve in elements[:count]:
                depths = []
                for face in ("east", "west"):
                    depths.append(vertices(curve, face)[0][2])
                near.append(min(depths)); far.append(max(depths))
            for index in fillers:
                filler = elements[index]
                self.assertGreater(filler["from"][2], max(near))
                self.assertLess(filler["to"][2], min(far))
                # Both the curve and filler must be on the same side of the lighting boundary.
                self.assertGreater(filler["from"][2], .008)
                self.assertEqual(filler["to"][2] > 16, min(far) > 16)


if __name__ == "__main__":
    unittest.main()
