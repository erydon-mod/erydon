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
        self.assertIn('.with(CAP, !isSpiralAnchor(above))', source)
        self.assertIn('adjacentFacing.rotateYClockwise()', source)
        self.assertIn('adjacentFacing.rotateYCounterclockwise()', source)
        self.assertIn('!isSpiralAnchor(ClusterRecalcSafety.getBlockState(world, pos.up()))', source)
        self.assertIn('sourcePos.equals(pos.up())', source)
        self.assertIn('if (!state.get(CAP))', source)
        self.assertIn('legacyHelperPositions(pos, layerFacing)', source)
        self.assertIn('REMOVING_LEGACY_LAYER.set(true)', source)
        self.assertIn('Blocks.AIR.getDefaultState()', source)
        self.assertNotIn('void onPlaced(', source)
        self.assertNotIn('syncCapForLayer', source)
        self.assertNotIn('getAllPartPositions', source)

    def test_blockstates_add_the_offstep_only_to_the_top_single_pivot(self) -> None:
        paths = sorted(BLOCKSTATES.glob("*stairs_spiral_large*.json"))
        self.assertEqual(81, len(paths))
        rotations: Counter[int] = Counter()
        total_applications = 0
        expected_y = {"north": 90, "east": 180, "south": 270, "west": 0}

        for path in paths:
            resource_stem = legacy_resource_id(path.stem)
            multipart = load_json(path).get("multipart", [])
            self.assertEqual(8, len(multipart), path.name)
            clauses = Counter()
            for entry in multipart:
                when = entry["when"]
                apply = entry["apply"]
                facing = when["facing"]
                self.assertEqual(expected_y[facing], apply["y"], path.name)
                self.assertNotIn("uvlock", apply, path.name)
                self.assertEqual("b", when["part"], path.name)
                offstep = when.get("cap") == "true"
                self.assertEqual(
                    {"facing", "part", "cap"} if offstep else {"facing", "part"},
                    set(when),
                    path.name,
                )
                self.assertEqual(
                    f"erydon:block/stairs/spiral/{resource_stem}"
                    + ("_offstep" if offstep else ""),
                    apply["model"],
                    path.name,
                )
                clauses[(facing, offstep)] += 1
                rotations[apply["y"]] += 1
                total_applications += 1

            for facing in expected_y:
                self.assertEqual(1, clauses[(facing, False)], path.name)
                self.assertEqual(1, clauses[(facing, True)], path.name)

        self.assertEqual(648, total_applications)
        self.assertEqual(Counter({0: 162, 90: 162, 180: 162, 270: 162}), rotations)

    def test_separate_masters_preserve_supplied_out_of_bounds_geometry_and_uvs(self) -> None:
        expected = {
            "stairs_spiral_large.json": (12, 72, 72, -16, 32),
            "stairs_spiral_large_offstep.json": (4, 22, 16, -16, 16.008),
        }
        for name, (elements, faces, out_of_bounds, minimum, maximum) in expected.items():
            path = MODELS / name
            document = load_json(path)
            self.assertEqual(elements, len(document.get("elements", [])), name)
            self.assertEqual(
                ["spiral", "offstep"],
                [group["name"] for group in document["groups"]],
                name,
            )

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

            self.assertEqual(faces, total_faces, name)
            self.assertEqual(out_of_bounds, out_of_bounds_uv_faces, name)
            self.assertAlmostEqual(minimum, min(coordinates), places=5, msg=name)
            self.assertAlmostEqual(maximum, max(coordinates), places=5, msg=name)

    def test_offstep_collision_is_a_shallow_top_plate_covering_every_element(self) -> None:
        source = BLOCK_SOURCE.read_text(encoding="utf-8")
        match = re.search(
            r"OFFSTEP_SECTION_NORTH\s*=\s*shape\((.*?)\n\s*\);",
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(match)
        values = [
            float(value)
            for value in re.findall(
                r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?",
                match.group(1),
            )
        ]
        self.assertEqual(24, len(values))
        collision_boxes = [values[index : index + 6] for index in range(0, len(values), 6)]
        for box in collision_boxes:
            self.assertAlmostEqual(15.5 / 16, box[1])
            self.assertAlmostEqual(1.0, box[4])

        document = load_json(MODELS / "stairs_spiral_large_offstep.json")
        for element_index, element in enumerate(document["elements"]):
            rotation = element["rotation"]
            self.assertEqual("y", rotation["axis"], element_index)
            angle = math.radians(rotation["angle"])
            origin_x, _, origin_z = rotation["origin"]
            xs = []
            zs = []
            for x in (element["from"][0], element["to"][0]):
                for z in (element["from"][2], element["to"][2]):
                    delta_x = x - origin_x
                    delta_z = z - origin_z
                    xs.append(
                        origin_x
                        + delta_x * math.cos(angle)
                        + delta_z * math.sin(angle)
                    )
                    zs.append(
                        origin_z
                        - delta_x * math.sin(angle)
                        + delta_z * math.cos(angle)
                    )

            horizontal_bounds = (
                min(xs) / 16,
                min(zs) / 16,
                max(xs) / 16,
                max(zs) / 16,
            )
            covered = any(
                box[0] <= horizontal_bounds[0]
                and box[2] <= horizontal_bounds[1]
                and box[3] >= horizontal_bounds[2]
                and box[5] >= horizontal_bounds[3]
                for box in collision_boxes
            )
            self.assertTrue(
                covered,
                f"offstep element {element_index}: {horizontal_bounds}",
            )

    def test_all_material_children_share_the_two_world_geometry_masters(self) -> None:
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
                offstep = child_name.endswith("_offstep")
                self.assertEqual(
                    "erydon:block/stairs/spiral/stairs_spiral_large"
                    + ("_offstep" if offstep else ""),
                    child.get("parent"),
                    child_name,
                )
                self.assertEqual(expected_texture, child.get("textures", {}).get("stone"), child_name)
                self.assertEqual(expected_texture, child.get("textures", {}).get("particle"), child_name)
                child_models.add(child_name)

        self.assertEqual(162, len(child_models))

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
