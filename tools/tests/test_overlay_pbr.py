from __future__ import annotations

import unittest

from PIL import Image

from tools import generate_overlay_pbr as overlay


class OverlayPbrTests(unittest.TestCase):
    def test_cutout_bevel_keeps_its_normals_without_recessing_the_surface(self) -> None:
        albedo = Image.new("RGBA", (5, 3), (120, 80, 30, 0))
        for y in range(3):
            for x, alpha in enumerate((0, 128, 255, 128, 0)):
                albedo.putpixel((x, y), (120, 80, 30, alpha))

        normal = overlay.make_normal(albedo)
        self.assertEqual(normal.getchannel("A").getextrema(), (255, 255))
        # Retain the inward-facing bevel lighting, including partially covered
        # edge texels. Flattening the RGB normal would lose the approved detail.
        self.assertEqual(normal.getpixel((1, 1)), (185, 128, 255, 255))
        self.assertEqual(normal.getpixel((2, 1)), (128, 128, 255, 255))
        self.assertEqual(normal.getpixel((3, 1)), (70, 128, 255, 255))
        for metal in overlay.METALS:
            self.assertEqual(overlay.make_specular(albedo, metal).getpixel((2, 1)),
                             (255, 255, 0, 255))

    def test_every_shared_tile_is_surface_height_and_matches_the_generator(self) -> None:
        tiles = overlay.albedo_files(overlay.DEFAULT_ROOT)
        self.assertEqual(len(tiles), 8 * 47)
        families = {path.parent for path in tiles}
        self.assertEqual(len(families), 8)
        for family in families:
            self.assertEqual({p.stem for p in tiles if p.parent == family},
                             {str(i) for i in range(47)})
        for path in tiles:
            with self.subTest(tile=path.relative_to(overlay.DEFAULT_ROOT)):
                with Image.open(path) as image:
                    albedo = image.convert("RGBA")
                with Image.open(path.with_name(path.stem + "_n.png")) as image:
                    normal = image.convert("RGBA")
                with Image.open(path.with_name(path.stem + "_s.png")) as image:
                    specular = image.convert("RGBA")
                self.assertEqual(normal.size, albedo.size)
                self.assertEqual(normal.getchannel("A").getextrema(), (255, 255))
                self.assertEqual(normal.tobytes(), overlay.make_normal(albedo).tobytes())
                self.assertEqual(specular.tobytes(),
                                 overlay.make_specular(albedo, path.parent.name).tobytes())


if __name__ == "__main__":
    unittest.main()
