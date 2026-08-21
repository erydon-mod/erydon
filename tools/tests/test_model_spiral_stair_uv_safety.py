from __future__ import annotations

import csv
import json
import re
import unittest
from collections import Counter
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ERYDON_ASSETS = REPO_ROOT / "src" / "main" / "resources" / "assets" / "erydon"
BLOCKSTATES = ERYDON_ASSETS / "blockstates"
MODELS = ERYDON_ASSETS / "models" / "block" / "stairs" / "spiral"
BLOCK_SOURCE = (
    REPO_ROOT
    / "src"
    / "main"
    / "java"
    / "com"
    / "oliver"
    / "erydon"
    / "block"
    / "StairsSpiralLargeBlock.java"
)
ID_MIGRATION_MANIFEST = (
    REPO_ROOT / "src" / "main" / "resources" / "data" / "erydon" / "id_migration.tsv"
)
CORE_CTM_ROOT = (
    REPO_ROOT / "src" / "main" / "resources" / "assets" / "minecraft" / "optifine" / "ctm"
)
OPTIONAL_COLLECTION_CTM_ROOTS = (
    REPO_ROOT
    / "run-dev"
    / "resourcepacks"
    / "erydon-rp-16x-lite"
    / "assets"
    / "minecraft"
    / "optifine"
    / "ctm",
    REPO_ROOT
    / "run-dev"
    / "resourcepacks"
    / "erydon-rp-64x-pbr"
    / "assets"
    / "minecraft"
    / "optifine"
    / "ctm",
)

def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def load_legacy_resource_ids() -> dict[str, str]:
    with ID_MIGRATION_MANIFEST.open("r", encoding="utf-8", newline="") as stream:
        return {
            row["canonical_path"]: row["old_path"]
            for row in csv.DictReader(stream, delimiter="\t")
            if row["mode"] == "PERMANENT_ALIAS"
        }


LEGACY_RESOURCE_IDS = load_legacy_resource_ids()


def legacy_resource_id(block_id: str) -> str:
    return LEGACY_RESOURCE_IDS.get(block_id, block_id)


class SpiralStairUvSafetyTests(unittest.TestCase):
    def test_placement_uses_one_pivot_block_and_keeps_legacy_state_schema(self) -> None:
        source = BLOCK_SOURCE.read_text(encoding="utf-8")
        self.assertIn('EnumProperty.of("part", Part.class)', source)
        self.assertIn('BooleanProperty.of("cap")', source)
        self.assertIn('.with(PART, Part.B)', source)
        self.assertIn('adjacentFacing.rotateYClockwise()', source)
        self.assertIn('adjacentFacing.rotateYCounterclockwise()', source)
        self.assertNotIn('void onPlaced(', source)
        self.assertNotIn('syncCapForLayer', source)
        self.assertNotIn('getAllPartPositions', source)

    def test_blockstates_render_only_the_single_pivot_model(self) -> None:
        paths = sorted(BLOCKSTATES.glob("*stairs_spiral_large*.json"))
        self.assertEqual(81, len(paths))
        rotations: Counter[int] = Counter()
        total_applications = 0
        expected_y = {"north": 90, "east": 180, "south": 270, "west": 0}

        for path in paths:
            resource_stem = legacy_resource_id(path.stem)
            multipart = load_json(path).get("multipart", [])
            self.assertEqual(4, len(multipart), path.name)
            clauses = Counter()
            for entry in multipart:
                when = entry["when"]
                apply = entry["apply"]
                facing = when["facing"]
                self.assertEqual(expected_y[facing], apply["y"], path.name)
                self.assertNotIn("uvlock", apply, path.name)
                self.assertEqual({"facing", "part"}, set(when), path.name)
                self.assertEqual("b", when["part"], path.name)
                self.assertEqual(
                    f"erydon:block/stairs/spiral/{resource_stem}",
                    apply["model"],
                    path.name,
                )
                clauses[facing] += 1
                rotations[apply["y"]] += 1
                total_applications += 1

            for facing in expected_y:
                self.assertEqual(1, clauses[facing], path.name)

        self.assertEqual(324, total_applications)
        self.assertEqual(Counter({0: 81, 90: 81, 180: 81, 270: 81}), rotations)

    def test_single_master_preserves_supplied_out_of_bounds_geometry_and_uvs(self) -> None:
        path = MODELS / "stairs_spiral_large.json"
        document = load_json(path)
        self.assertEqual(16, len(document.get("elements", [])))
        self.assertEqual(["A", "B", "C"], [group["name"] for group in document["groups"]])

        total_faces = 0
        out_of_bounds_uv_faces = 0
        coordinates = []
        for element_index, element in enumerate(document["elements"]):
            coordinates.extend(element["from"])
            coordinates.extend(element["to"])
            self.assertEqual("y", element["rotation"]["axis"], element_index)
            for face_name, face in element["faces"].items():
                total_faces += 1
                self.assertEqual("#stone", face.get("texture"), path.name)
                uv = face.get("uv")
                self.assertEqual(4, len(uv), f"element {element_index} {face_name}")
                if min(uv) < 0 or max(uv) > 16:
                    out_of_bounds_uv_faces += 1

        self.assertEqual(94, total_faces)
        self.assertEqual(88, out_of_bounds_uv_faces)
        self.assertEqual(-16, min(coordinates))
        self.assertEqual(32, max(coordinates))

    def test_all_material_children_share_the_single_world_geometry_master(self) -> None:
        child_models = set()
        for blockstate_path in BLOCKSTATES.glob("*stairs_spiral_large*.json"):
            stem = legacy_resource_id(blockstate_path.stem)
            aged = stem.endswith("_stairs_spiral_large_aged")
            if aged:
                material = stem[: -len("_stairs_spiral_large_aged")]
                expected_texture = f"erydon:block/{material}_block_aged"
            else:
                material = stem[: -len("_stairs_spiral_large")]
                expected_texture = f"erydon:block/{material}_block"

            for entry in load_json(blockstate_path).get("multipart", []):
                model_id = entry["apply"]["model"]
                child_name = model_id.removeprefix("erydon:block/stairs/spiral/")
                child_path = MODELS / f"{child_name}.json"
                self.assertTrue(child_path.is_file(), str(child_path))
                child = load_json(child_path)
                self.assertEqual(
                    "erydon:block/stairs/spiral/stairs_spiral_large",
                    child.get("parent"),
                    child_name,
                )
                self.assertEqual(expected_texture, child.get("textures", {}).get("stone"), child_name)
                self.assertEqual(expected_texture, child.get("textures", {}).get("particle"), child_name)
                child_models.add(child_name)

        self.assertEqual(81, len(child_models))

    def test_every_spiral_block_has_ctm_coverage_in_available_pack_roots(self) -> None:
        block_ids = {
            path.stem for path in BLOCKSTATES.glob("*stairs_spiral_large*.json")
        }
        self.assertEqual(81, len(block_ids))
        self.assertTrue(CORE_CTM_ROOT.is_dir(), str(CORE_CTM_ROOT))
        roots = [CORE_CTM_ROOT]
        roots.extend(root for root in OPTIONAL_COLLECTION_CTM_ROOTS if root.is_dir())
        for root in roots:
            properties = []
            for path in root.rglob("*.properties"):
                source = path.read_bytes()
                self.assertFalse(source.startswith(b"\xef\xbb\xbf"), str(path))
                properties.append((path, source.decode("utf-8")))
            for canonical_block_id in block_ids:
                block_id = legacy_resource_id(canonical_block_id)
                pattern = re.compile(
                    rf"(?<![a-z0-9_])erydon:{re.escape(block_id)}(?![a-z0-9_])"
                )
                matching = [entry for entry in properties if pattern.search(entry[1])]
                self.assertEqual(1, len(matching), f"{root}: {block_id}")
                path, source = matching[0]
                values = {}
                for line in source.splitlines():
                    if "=" in line and not line.lstrip().startswith("#"):
                        key, value = line.split("=", 1)
                        values[key.strip()] = value.strip()

                self.assertEqual("repeat", values.get("method"), str(path))
                self.assertEqual("6", values.get("width"), str(path))
                self.assertEqual("6", values.get("height"), str(path))
                self.assertNotIn("symmetry", values, str(path))
                self.assertNotIn("orient", values, str(path))

                if block_id.endswith("_stairs_spiral_large_aged"):
                    ctm_set = block_id[: -len("_stairs_spiral_large_aged")] + "_aged"
                else:
                    ctm_set = block_id[: -len("_stairs_spiral_large")]
                expected_tiles = [
                    f"optifine/ctm/{ctm_set}/{index}"
                    for index in range(36)
                ]
                actual_tiles = [
                    tile.removeprefix("textures/")
                    for tile in values.get("tiles", "").split()
                ]
                self.assertEqual(expected_tiles, actual_tiles, str(path))

                texture_root = root.parent.parent / "textures" / "optifine" / "ctm"
                ctm_folder = texture_root / ctm_set
                self.assertTrue(ctm_folder.is_dir(), str(ctm_folder))
                self.assertEqual(
                    [f"{index}.png" for index in range(36)],
                    sorted(
                        (
                            path.name
                            for path in ctm_folder.glob("*.png")
                            if path.stem.isdigit()
                        ),
                        key=lambda name: int(name.removesuffix(".png")),
                    ),
                    str(ctm_folder),
                )
                self.assertFalse(
                    list(ctm_folder.glob("*_e.png")),
                    f"OptiFine emissive overlays require a matching custom-render path: {ctm_folder}",
                )


if __name__ == "__main__":
    unittest.main()
