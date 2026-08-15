from __future__ import annotations

import sys
import unittest
from pathlib import Path

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools"))

import generate_rock_family  # noqa: E402


class RockFamilyAssetTests(unittest.TestCase):
    def test_complete_hewn_equivalent_family(self) -> None:
        generate_rock_family.validate_outputs()

    def test_native_height_filter_preserves_rgb_and_reduces_fine_relief(self) -> None:
        source = Image.new("RGBA", (64, 64))
        pixels = source.load()
        for y in range(64):
            for x in range(64):
                broad_height = 200 if x < 32 else 245
                fine_variation = -4 if (x + y) % 2 == 0 else 4
                pixels[x, y] = (
                    64 + x,
                    96 + y,
                    255 - x,
                    broad_height + fine_variation,
                )

        unchanged = generate_rock_family.prepare_sheet("", source)
        smoothed = generate_rock_family.prepare_sheet("_n", source)
        source_height = source.getchannel("A")
        smoothed_height = smoothed.getchannel("A")

        self.assertEqual(source.tobytes(), unchanged.tobytes())
        self.assertEqual(
            source.convert("RGB").tobytes(), smoothed.convert("RGB").tobytes()
        )
        self.assertNotEqual(source_height.tobytes(), smoothed_height.tobytes())
        self.assertEqual(
            (
                generate_rock_family.ROCK_HEIGHT_TARGET_MINIMUM,
                generate_rock_family.ROCK_HEIGHT_TARGET_MAXIMUM,
            ),
            smoothed_height.getextrema(),
        )

        def fine_relief(channel: Image.Image) -> float:
            total = 0
            samples = 0
            for y in range(8, 56):
                for x in range(8, 24):
                    value = channel.getpixel((x, y))
                    total += abs(value - channel.getpixel((x + 1, y)))
                    total += abs(value - channel.getpixel((x, y + 1)))
                    samples += 2
            return total / samples

        self.assertLess(
            fine_relief(smoothed_height), fine_relief(source_height)
        )

        broad_blur = generate_rock_family.wrap_gaussian_blur(
            source_height, generate_rock_family.ROCK_HEIGHT_BASE_BLUR_RADIUS
        )
        broad_blur = generate_rock_family.scale_height_range(
            source_height, broad_blur
        )

        def step_contrast(channel: Image.Image) -> float:
            return sum(
                abs(channel.getpixel((31, y)) - channel.getpixel((32, y)))
                for y in range(channel.height)
            ) / channel.height

        self.assertGreater(
            step_contrast(smoothed_height), step_contrast(broad_blur)
        )

    def test_shared_ui_and_command_infrastructure_mentions_rock(self) -> None:
        java_root = REPO_ROOT / "src" / "main" / "java" / "com" / "oliver" / "erydon"
        expected_snippets = {
            java_root / "client" / "config" / "ErydonTextureGalleryScreen.java": (
                'option.erydon.gallery.variant.rock"',
                'material + "_rock"',
            ),
            java_root / "command" / "ErydonShowcaseCommand.java": (
                'order.put("rock_block", 1);',
            ),
            java_root / "command" / "ErydonSwapFamilyDatabase.java": (
                '"_rock"',
                '"kelastrion_rock"',
                '"latmion_rock"',
                '"psamatheon_rock"',
            ),
            java_root / "item" / "ErydonItemOrdering.java": (
                'base.equals("rock")',
                'new TextureShape(5, "rock"',
            ),
        }
        for path, snippets in expected_snippets.items():
            source = path.read_text(encoding="utf-8")
            for snippet in snippets:
                with self.subTest(path=path.name, snippet=snippet):
                    self.assertIn(snippet, source)

    def test_shader_block_list_contains_rock_slopes(self) -> None:
        properties = (
            REPO_ROOT / "src" / "main" / "resources" / "assets" / "erydon" / "shaders" / "block.properties"
        ).read_text(encoding="utf-8-sig")
        self.assertIn("erydon:aganite_rock_slope", properties)
        self.assertIn("erydon:sanguenite_rock_slope_steep_upper", properties)


if __name__ == "__main__":
    unittest.main()
