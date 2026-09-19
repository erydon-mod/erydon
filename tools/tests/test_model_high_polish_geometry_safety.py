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
                self.assertNotIn("up", top[7]["faces"], "Remove the buried cap, not the visible front")

    def test_wide_alcoves_have_continuous_fronts_above_the_opening(self):
        for style in ("georgian", "gothic"):
            for size, sides in (("double", ("left", "right")),
                                ("triple", ("left", "center", "right"))):
                polygons = []
                for column, side in enumerate(sides):
                    elements = json.loads((MODELS / f"alcove/alcove_{style}_{size}_top_{side}.json").read_text())["elements"]
                    lining = [e for e in elements if e.get("name") == "roof_inner_lining"]
                    self.assertEqual(1, len(lining))
                    self.assertEqual({"down"}, set(lining[0]["faces"]))
                    self.assertLess(lining[0]["to"][1], 16, "The ceiling must remain inside the roof")
                    for element in elements:
                        for face in element["faces"]:
                            points = vertices(element, face)
                            depths = [p[2] for p in points]
                            if min(depths) > 15.85 and max(depths) - min(depths) < .0001:
                                polygons.append([(x + column * 16, y) for x, y, _ in points])
                # Avoid vertices and the intentionally stepped outer jamb. A
                # vertical ray through the facade may enter it once only; the
                # shortened crown pieces previously left gaps > 1 model unit.
                for sample in range(128, len(sides) * 16 * 64 - 128):
                    x = (sample + .3) / 64
                    intervals = []
                    for polygon in polygons:
                        ys = []
                        for (x1, y1), (x2, y2) in zip(polygon, polygon[1:] + polygon[:1]):
                            if min(x1, x2) <= x <= max(x1, x2) and abs(x2 - x1) > .000001:
                                ys.append(y1 + (y2 - y1) * (x - x1) / (x2 - x1))
                        if ys:
                            intervals.append((min(ys), max(ys)))
                    intervals.sort()
                    self.assertTrue(intervals, (style, size, x))
                    end = intervals[0][1]
                    for lower, upper in intervals[1:]:
                        # Allow authored subpixel joins (under 1/256 block).
                        self.assertLessEqual(lower - end, .055, (style, size, x, end, lower))
                        end = max(end, upper)

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
