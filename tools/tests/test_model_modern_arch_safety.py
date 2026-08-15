from __future__ import annotations

import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
MODEL_ROOT = REPO_ROOT / "src/main/resources/assets/erydon/models/block/arch/modern"
JAVA_ROOT = REPO_ROOT / "src/main/java/com/oliver/erydon/client/model"


class ModernArchSafetyTests(unittest.TestCase):
    def test_world_geometry_uses_implicit_uvs_with_rotated_arch_segments(self) -> None:
        masters = sorted(MODEL_ROOT.glob("arch_modern_*.json"))
        self.assertEqual(11, len(masters))
        rotated_angles = set()

        for path in masters:
            model = json.loads(path.read_text(encoding="utf-8-sig"))
            if path.name == "arch_modern_icon.json":
                continue
            for element in model.get("elements", []):
                rotation = element.get("rotation", {})
                if rotation.get("axis") == "z" and rotation.get("angle"):
                    rotated_angles.add(float(rotation["angle"]))
                for face in element.get("faces", {}).values():
                    self.assertNotIn("uv", face, f"{path.name} must remain world-projected")

        self.assertTrue({-45.0, -22.5, 22.5, 45.0}.issubset(rotated_angles))

    def test_post_continuity_renderer_owns_modern_world_projection(self) -> None:
        renderer = (JAVA_ROOT / "ArchRepeatCtmRenderer.java").read_text(encoding="utf-8")
        plugin = (JAVA_ROOT / "ModernArchCtmModelLoadingPlugin.java").read_text(encoding="utf-8")
        service = (JAVA_ROOT / "ErydonCtmService.java").read_text(encoding="utf-8")

        self.assertIn("ModelModifier.WRAP_LAST_PHASE, REPEAT_CTM_PHASE", plugin)
        self.assertIn("ArchRepeatCtmRenderer.Family.MODERN", plugin)
        self.assertIn("wrapped.getQuads(state, sourceCullFace", renderer)
        self.assertIn("SpiralStairCtmGeometry.split(lightFace, vertices)", renderer)
        self.assertIn("ErydonCtmService.repeatTileIndex(", renderer)
        self.assertIn("MutableQuadView.BAKE_NORMALIZED", renderer)
        self.assertIn("modernArchCtmSetName", plugin)
        self.assertIn("ArchRepeatCtmRenderer.clearGeometryCache();", service)


if __name__ == "__main__":
    unittest.main()
