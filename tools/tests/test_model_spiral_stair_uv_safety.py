from __future__ import annotations

import csv
import json
import math
import re
import unittest
from collections import Counter
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ERYDON_ASSETS = REPO_ROOT / "src" / "main" / "resources" / "assets" / "erydon"
BLOCKSTATES = ERYDON_ASSETS / "blockstates"
MODELS = ERYDON_ASSETS / "models" / "block" / "stairs" / "spiral"
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

EXPECTED_OUT_OF_CELL_HORIZONTAL_FACES = {
    ("stairs_spiral_large_a.json", 1, "up"),
    ("stairs_spiral_large_a.json", 1, "down"),
    ("stairs_spiral_large_b.json", 1, "up"),
    ("stairs_spiral_large_b.json", 1, "down"),
    ("stairs_spiral_large_b.json", 2, "up"),
    ("stairs_spiral_large_b.json", 2, "down"),
    ("stairs_spiral_large_b.json", 3, "up"),
    ("stairs_spiral_large_b.json", 3, "down"),
    ("stairs_spiral_large_c.json", 0, "up"),
    ("stairs_spiral_large_c.json", 0, "down"),
    ("stairs_spiral_large_c.json", 1, "up"),
    ("stairs_spiral_large_c.json", 1, "down"),
}


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


def rotated_horizontal_corners(element: dict) -> list[tuple[float, float]]:
    rotation = element.get("rotation", {})
    angle = float(rotation.get("angle", 0.0))
    if angle and rotation.get("axis") != "y":
        raise AssertionError(f"Unexpected non-Y rotation: {rotation}")
    origin = rotation.get("origin", [8.0, 8.0, 8.0])
    radians = math.radians(angle)
    cosine = math.cos(radians)
    sine = math.sin(radians)
    corners = []
    for x in (float(element["from"][0]), float(element["to"][0])):
        for z in (float(element["from"][2]), float(element["to"][2])):
            relative_x = x - float(origin[0])
            relative_z = z - float(origin[2])
            corners.append(
                (
                    float(origin[0]) + cosine * relative_x + sine * relative_z,
                    float(origin[2]) - sine * relative_x + cosine * relative_z,
                )
            )
    return corners


class SpiralStairUvSafetyTests(unittest.TestCase):
    def test_blockstate_applications_remain_exact_and_custom_renderer_owned(self) -> None:
        paths = sorted(BLOCKSTATES.glob("*stairs_spiral_large*.json"))
        self.assertEqual(81, len(paths))
        rotations: Counter[int] = Counter()
        total_applications = 0
        expected_y = {"north": 90, "east": 180, "south": 270, "west": 0}
        expected_suffix = {
            ("a", "false"): "a",
            ("b", "false"): "b",
            ("c", "false"): "c",
            ("b", "true"): "e",
            ("d", "true"): "d",
        }

        for path in paths:
            resource_stem = legacy_resource_id(path.stem)
            multipart = load_json(path).get("multipart", [])
            self.assertEqual(20, len(multipart), path.name)
            clauses = Counter()
            for entry in multipart:
                when = entry["when"]
                apply = entry["apply"]
                facing = when["facing"]
                self.assertEqual(expected_y[facing], apply["y"], path.name)
                self.assertNotIn("uvlock", apply, path.name)
                key = (facing, when["part"], when.get("cap", "false"))
                suffix = expected_suffix[key[1:]]
                if resource_stem.endswith("_aged"):
                    expected_model = f"{resource_stem[:-5]}_{suffix}_aged"
                else:
                    expected_model = f"{resource_stem}_{suffix}"
                self.assertEqual(
                    f"erydon:block/stairs/spiral/{expected_model}",
                    apply["model"],
                    path.name,
                )
                clauses[key] += 1
                rotations[apply["y"]] += 1
                total_applications += 1

            for facing in expected_y:
                self.assertEqual(1, clauses[(facing, "a", "false")], path.name)
                self.assertEqual(1, clauses[(facing, "b", "false")], path.name)
                self.assertEqual(1, clauses[(facing, "c", "false")], path.name)
                self.assertEqual(1, clauses[(facing, "b", "true")], path.name)
                self.assertEqual(1, clauses[(facing, "d", "true")], path.name)

        self.assertEqual(1620, total_applications)
        self.assertEqual(Counter({0: 405, 90: 405, 180: 405, 270: 405}), rotations)

    def test_in_world_masters_keep_implicit_uvs_and_known_fit_guard(self) -> None:
        blocked = set()
        authored_face_rotations = set()
        total_faces = 0
        horizontal_faces = 0
        for suffix in "abcde":
            path = MODELS / f"stairs_spiral_large_{suffix}.json"
            document = load_json(path)
            for element_index, element in enumerate(document.get("elements", [])):
                corners = rotated_horizontal_corners(element)
                fits = all(
                    -0.001 <= coordinate <= 16.001
                    for corner in corners
                    for coordinate in corner
                )
                for face_name, face in element.get("faces", {}).items():
                    total_faces += 1
                    self.assertNotIn("uv", face, f"{path.name} element {element_index} {face_name}")
                    self.assertEqual("#stone", face.get("texture"), path.name)
                    if "rotation" in face:
                        authored_face_rotations.add(
                            (path.name, element_index, face_name, face["rotation"])
                        )
                    if face_name in {"up", "down"}:
                        horizontal_faces += 1
                        if not fits:
                            blocked.add((path.name, element_index, face_name))

        self.assertEqual(119, total_faces)
        self.assertEqual(39, horizontal_faces)
        self.assertEqual(EXPECTED_OUT_OF_CELL_HORIZONTAL_FACES, blocked)
        self.assertEqual(27, horizontal_faces - len(blocked))
        self.assertEqual(
            {
                ("stairs_spiral_large_b.json", 3, "up", 90),
                ("stairs_spiral_large_b.json", 3, "down", 270),
            },
            authored_face_rotations,
        )

    def test_all_material_children_share_the_five_world_geometry_masters(self) -> None:
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
                suffix_name = child_name.removesuffix("_aged") if aged else child_name
                suffix = suffix_name.rsplit("_", 1)[1]
                self.assertEqual(
                    f"erydon:block/stairs/spiral/stairs_spiral_large_{suffix}",
                    child.get("parent"),
                    child_name,
                )
                self.assertEqual(expected_texture, child.get("textures", {}).get("stone"), child_name)
                self.assertEqual(expected_texture, child.get("textures", {}).get("particle"), child_name)
                child_models.add(child_name)

        self.assertEqual(405, len(child_models))

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
