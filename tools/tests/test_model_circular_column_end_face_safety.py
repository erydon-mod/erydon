from __future__ import annotations

import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
MODEL_ROOT = (
    REPO_ROOT
    / "src/main/resources/assets/erydon/models/block/column/circular"
)
BASE_MODEL = MODEL_ROOT / "column_circular_base_narrow.json"
CAPITAL_MODEL = MODEL_ROOT / "column_circular_capital_narrow.json"


def load_elements(path: Path) -> list[dict[str, object]]:
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    return document["elements"]


def bottom_cap_strips(elements: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        element
        for element in elements
        if float(element["from"][1]) <= 0.01
        and float(element["to"][1]) <= 0.51
    ]


def top_cap_strips(elements: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        element
        for element in elements
        if float(element["from"][1]) >= 15.49
        and float(element["to"][1]) >= 15.99
    ]


class CircularColumnEndFaceSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base_strips = bottom_cap_strips(load_elements(BASE_MODEL))
        cls.capital_strips = top_cap_strips(load_elements(CAPITAL_MODEL))

    def test_narrow_base_has_a_complete_staggered_bottom_cap(self) -> None:
        self.assertEqual(8, len(self.base_strips))
        self.assertEqual(
            [index / 1000 for index in range(8)],
            sorted(float(element["from"][1]) for element in self.base_strips),
        )
        for element in self.base_strips:
            face = element["faces"]["down"]
            self.assertEqual("#stone", face["texture"])
            self.assertNotIn("uv", face)
            expected_cullface = (
                "down" if float(element["from"][1]) == 0.0 else None
            )
            self.assertEqual(expected_cullface, face.get("cullface"))

    def test_narrow_capital_has_a_complete_staggered_top_cap(self) -> None:
        self.assertEqual(8, len(self.capital_strips))
        self.assertEqual(
            [round(15.993 + index / 1000, 3) for index in range(8)],
            sorted(float(element["to"][1]) for element in self.capital_strips),
        )
        for element in self.capital_strips:
            face = element["faces"]["up"]
            self.assertEqual("#stone", face["texture"])
            self.assertNotIn("uv", face)
            expected_cullface = (
                "up" if float(element["to"][1]) == 16.0 else None
            )
            self.assertEqual(expected_cullface, face.get("cullface"))

    def test_narrow_end_caps_remain_mirrored(self) -> None:
        self.assertEqual(len(self.base_strips), len(self.capital_strips))
        for base, capital in zip(self.base_strips, self.capital_strips):
            self.assertEqual(
                [base["from"][0], base["from"][2], base["to"][0], base["to"][2]],
                [
                    capital["from"][0],
                    capital["from"][2],
                    capital["to"][0],
                    capital["to"][2],
                ],
            )
            self.assertAlmostEqual(
                16.0 - float(base["from"][1]),
                float(capital["to"][1]),
            )
            self.assertAlmostEqual(
                -float(base["rotation"]["angle"]),
                float(capital["rotation"]["angle"]),
            )


if __name__ == "__main__":
    unittest.main()
