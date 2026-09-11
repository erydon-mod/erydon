import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_rusticated_multiface_layers as generator


class RusticatedMultifaceTests(unittest.TestCase):
    def test_all_assets_registrations_tags_languages_and_ctm(self):
        generator.validate_outputs()

    def test_twenty_seven_complete_geometry_sets(self):
        self.assertEqual(27 * 27, len(list(generator.outputs())))


if __name__ == "__main__":
    unittest.main()
