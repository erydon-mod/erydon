from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = REPO_ROOT / "src/main/java/com/oliver/erydon"
OIL_SOURCE = JAVA_ROOT / "block/OilBurnerBlock.java"
TRANSFORM_SOURCE = JAVA_ROOT / "block/DecorShapeTransforms.java"


def calls(source: str, call_name: str, argument_count: int) -> list[tuple[float, ...]]:
    result: list[tuple[float, ...]] = []
    for match in re.finditer(rf"{re.escape(call_name)}\(([^)]+)\)", source):
        arguments = tuple(float(value.strip()) for value in match.group(1).split(","))
        if len(arguments) == argument_count:
            result.append(arguments)
    return result


def transform_bounds(
    bounds: tuple[float, ...],
    scale: float,
    distance: float,
    base_y: float,
    turns: int,
) -> tuple[float, ...]:
    min_x, min_y, min_z, max_x, max_y, max_z = bounds
    result = (
        0.5 + (min_x - 0.5) * scale,
        min_y * scale + base_y,
        0.5 + (min_z - 0.5) * scale - distance,
        0.5 + (max_x - 0.5) * scale,
        max_y * scale + base_y,
        0.5 + (max_z - 0.5) * scale - distance,
    )
    for _ in range(turns):
        min_x, min_y, min_z, max_x, max_y, max_z = result
        result = (1.0 - max_z, min_y, min_x, 1.0 - min_z, max_y, max_x)
    return result


class DecorOffsetShapeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.oil = OIL_SOURCE.read_text(encoding="utf-8")
        cls.transform = TRANSFORM_SOURCE.read_text(encoding="utf-8")

    @staticmethod
    def constant(source: str, name: str) -> float:
        match = re.search(rf"\b{name}\s*=\s*([0-9.]+)F", source)
        if match is None:
            raise AssertionError(f"Missing constant {name}")
        return float(match.group(1))

    def test_oil_burner_shape_transform_contract(self) -> None:
        self.assertIn("0.5 + (minX - 0.5) * scale", self.transform)
        self.assertIn("minY * scale + baseY", self.transform)
        self.assertIn("0.5 + (minZ - 0.5) * scale - northOffset", self.transform)
        self.assertIn("VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)", self.transform)

    def test_oil_burner_profile_and_flame_follow_offset(self) -> None:
        radial_layers = calls(self.oil, "DecorShapeTransforms.radialLayer", 3)
        self.assertEqual(6, len(radial_layers))
        maximum_radius = max(layer[0] for layer in radial_layers)
        body_bounds = (
            (8.0 - maximum_radius) / 16.0,
            min(layer[1] for layer in radial_layers) / 16.0,
            (8.0 - maximum_radius) / 16.0,
            (8.0 + maximum_radius) / 16.0,
            max(layer[2] for layer in radial_layers) / 16.0,
            (8.0 + maximum_radius) / 16.0,
        )
        scale = self.constant(self.transform, "OIL_BURNER_OFFSET_SCALE")
        distance = self.constant(self.transform, "OFFSET_DISTANCE")
        base_y = self.constant(self.transform, "OFFSET_BASE_Y")
        for offset in (False, True):
            for turns in range(4):
                transformed = transform_bounds(
                    body_bounds,
                    scale if offset else 1.0,
                    distance if offset else 0.0,
                    base_y if offset else 0.0,
                    turns,
                )
                self.assertTrue(
                    all(-1.000001 <= value <= 2.000001 for value in transformed),
                    (offset, turns, transformed),
                )
                if offset:
                    self.assertTrue(
                        (
                            transformed[2] < 0.0,
                            transformed[3] > 1.0,
                            transformed[5] > 1.0,
                            transformed[0] < 0.0,
                        )[turns]
                    )

        self.assertIn("Block.createCuboidShape(7.5, 3.5, 0.5, 8.5, 18.5, 15.5)", self.oil)
        self.assertIn("Block.createCuboidShape(0.5, 3.5, 7.5, 15.5, 18.5, 8.5)", self.oil)
        self.assertIn("includeFlameAndTarget && offset", self.oil)

    def test_offset_outline_retains_a_narrow_source_cell_target(self) -> None:
        self.assertIn(
            "TARGET_POST = Block.createCuboidShape(6.0, 0.0, 6.0, 10.0, 16.0, 10.0)",
            self.transform,
        )
        self.assertIn("getRaycastShape(", self.oil)
        self.assertIn("DecorShapeTransforms.TARGET_POST", self.oil)
        self.assertIn("getCullingShape(", self.oil)
        self.assertIn("return VoxelShapes.empty();", self.oil)
        self.assertIn("includeFlameAndTarget && offset", self.oil)


if __name__ == "__main__":
    unittest.main()
