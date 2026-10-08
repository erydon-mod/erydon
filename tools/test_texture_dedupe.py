from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from PIL import Image


TOOL_PATH = Path(__file__).with_name("texture_dedupe.py")
SPEC = importlib.util.spec_from_file_location("texture_dedupe", TOOL_PATH)
assert SPEC is not None and SPEC.loader is not None
texture_dedupe = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = texture_dedupe
SPEC.loader.exec_module(texture_dedupe)


def run_tool(*arguments: object) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TOOL_PATH), *(str(item) for item in arguments)],
        check=True,
        capture_output=True,
        text=True,
    )


def record(
    *,
    source: str = "source",
    tier: str = "native",
    namespace: str = "erydon",
    path: str = "textures/block/a.png",
    sha256: str = "a" * 64,
    rgba_sha256: str = "b" * 64,
    metadata_sha256: str = "c" * 64,
    role: str = "albedo",
    mcmeta: bool = False,
    animated: bool = False,
    high_res: bool = False,
    alias_supported: bool = True,
) -> dict:
    return {
        "source": source,
        "tier": tier,
        "namespace": namespace,
        "path": path,
        "resource_id": f"{namespace}:{path}",
        "logical_id": texture_dedupe.logical_id(namespace, path),
        "asset_location": "namespace",
        "dimensions": [16, 16],
        "width": 16,
        "height": 16,
        "mode": "RGBA",
        "bit_depth": 8,
        "file_size": 100,
        "zip_deflate_size_estimate": 90,
        "sha256": sha256,
        "rgba_sha256": rgba_sha256,
        "alpha_min": 255,
        "alpha_max": 255,
        "alpha_varies": False,
        "png_metadata_sha256": metadata_sha256,
        "mcmeta_present": mcmeta,
        "mcmeta_sha256": "d" * 64 if mcmeta else None,
        "role": role,
        "is_ctm": "/optifine/ctm/" in f"/{path}",
        "animated": animated,
        "intentionally_native_high_res": high_res,
        "alias_supported": alias_supported,
        "known_references": [],
    }


class TextureDedupeAnalysisTest(unittest.TestCase):
    def test_recursive_glob_also_matches_direct_children(self) -> None:
        self.assertTrue(
            texture_dedupe.glob_matches(
                "textures/item/emblem.png", "textures/item/**/*.png"
            )
        )
        self.assertTrue(
            texture_dedupe.glob_matches("icon.png", "**/*.png")
        )
        self.assertFalse(
            texture_dedupe.glob_matches(
                "textures/block/quatrefoil.png", "textures/**/oil.png"
            )
        )

    def test_resource_id_keeps_ctm_locations_distinct(self) -> None:
        legacy = "optifine/ctm/example/5.png"
        atlas = "textures/optifine/ctm/example/5.png"
        self.assertEqual(
            texture_dedupe.logical_id("minecraft", legacy),
            texture_dedupe.logical_id("minecraft", atlas),
        )
        self.assertNotEqual(
            texture_dedupe.resource_id("minecraft", legacy),
            texture_dedupe.resource_id("minecraft", atlas),
        )

    def test_exact_groups_stay_inside_source_tier_and_namespace(self) -> None:
        records = [
            record(path="textures/block/a.png"),
            record(path="textures/block/b.png"),
            record(namespace="minecraft", path="textures/block/a.png"),
            record(namespace="minecraft", path="textures/block/b.png"),
        ]
        report = texture_dedupe.duplicate_analysis_document(
            {"config_sha256": "config"}, records
        )
        self.assertEqual(2, report["summary"]["exact_scope_duplicate_group_count"])
        self.assertEqual(2, report["summary"]["eligible_redundant_file_count"])
        self.assertEqual(1, report["summary"]["cross_scope_exact_group_count"])
        self.assertTrue(
            all(
                group["recommended_method"] == "virtual-alias"
                for group in report["exact_scope_groups"]
            )
        )

    def test_metadata_and_high_resolution_groups_are_excluded(self) -> None:
        records = [
            record(path="textures/block/a.png", mcmeta=True, animated=True),
            record(path="textures/block/b.png", mcmeta=True, animated=True),
            record(
                path="textures/block/c.png",
                sha256="e" * 64,
                high_res=True,
            ),
            record(
                path="textures/block/d.png",
                sha256="e" * 64,
                high_res=True,
            ),
        ]
        report = texture_dedupe.duplicate_analysis_document(
            {"config_sha256": "config"}, records
        )
        reasons = report["excluded_exact_totals"]["by_reason"]
        self.assertIn("animated", reasons)
        self.assertIn("mcmeta-backed", reasons)
        self.assertIn("intentional-native-high-resolution", reasons)
        self.assertEqual(0, report["summary"]["eligible_exact_group_count"])

    def test_decoded_only_requires_matching_semantic_metadata(self) -> None:
        records = [
            record(path="textures/block/a.png"),
            record(path="textures/block/b.png", sha256="e" * 64),
            record(
                path="textures/block/c.png",
                sha256="f" * 64,
                metadata_sha256="0" * 64,
            ),
        ]
        report = texture_dedupe.duplicate_analysis_document(
            {"config_sha256": "config"}, records
        )
        self.assertEqual(
            1,
            report["summary"][
                "decoded_equivalent_byte_different_group_count"
            ],
        )

    def test_generated_plan_is_source_scoped_and_exact_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory_path = root / "inventory.json"
            inventory_path.write_text("{}", encoding="utf-8")
            source = texture_dedupe.SourceConfig(
                source_id="source",
                tier="native",
                root=root / "resources",
                root_label="resources",
                namespaces=("erydon",),
                include=("**/*.png",),
                exclude=(),
                role_rules=(),
                metadata_overrides={},
                intentionally_native_high_res=(),
            )
            config = texture_dedupe.ToolConfig(
                path=root / "config.json",
                sha256="config",
                sources=(source,),
                pilot_groups=(),
            )
            records = [
                record(path="textures/block/a.png"),
                record(path="textures/block/b.png"),
                record(
                    path="textures/block/c.png",
                    sha256="e" * 64,
                    role="overlay",
                ),
                record(
                    path="textures/block/d.png",
                    sha256="e" * 64,
                    role="overlay",
                ),
            ]
            plan = texture_dedupe.generated_plan_config(
                config,
                inventory_path,
                {"config_sha256": "config"},
                records,
                "source",
                root / "plans" / "source.json",
                {"albedo"},
            )
            self.assertEqual(1, plan["plan_metadata"]["group_count"])
            self.assertEqual(2, plan["plan_metadata"]["alias_count"])
            self.assertEqual(
                "virtual-alias", plan["plan_metadata"]["method"]
            )

    def test_generated_plan_conservatively_excludes_mixed_roles(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory_path = root / "inventory.json"
            inventory_path.write_text("{}", encoding="utf-8")
            source = texture_dedupe.SourceConfig(
                source_id="source",
                tier="native",
                root=root / "resources",
                root_label="resources",
                namespaces=("erydon",),
                include=("**/*.png",),
                exclude=(),
                role_rules=(),
                metadata_overrides={},
                intentionally_native_high_res=(),
            )
            config = texture_dedupe.ToolConfig(
                path=root / "config.json",
                sha256="config",
                sources=(source,),
                pilot_groups=(),
            )
            plan = texture_dedupe.generated_plan_config(
                config,
                inventory_path,
                {"config_sha256": "config"},
                [
                    record(path="textures/block/a.png", role="albedo"),
                    record(path="textures/block/a_n.png", role="normal"),
                ],
                "source",
                root / "plan.json",
                {"albedo", "normal"},
            )
            self.assertEqual(0, plan["plan_metadata"]["group_count"])
            self.assertEqual(
                1,
                plan["plan_metadata"]["excluded_group_counts_by_reason"][
                    "mixed-role"
                ],
            )

    def test_plan_apply_validate_compare_end_to_end(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_root = root / "source"
            texture_root = source_root / "assets" / "erydon" / "textures" / "block"
            texture_root.mkdir(parents=True)
            image = Image.new("RGBA", (4, 4), (10, 20, 30, 40))
            image.save(texture_root / "a.png")
            image.save(texture_root / "b.png")

            config_path = root / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "sources": [
                            {
                                "id": "fixture",
                                "tier": "native",
                                "root": "source",
                                "namespaces": ["erydon"],
                                "include": ["**/*.png"],
                                "exclude": [],
                                "role_rules": [],
                                "metadata_overrides": {},
                                "intentionally_native_high_res": [],
                            }
                        ],
                        "pilot_groups": [],
                    }
                ),
                encoding="utf-8",
            )
            inventory_path = root / "inventory.json"
            inventory_csv = root / "inventory.csv"
            plan_path = root / "plan.json"
            stage_root = root / "stage"
            validation_path = root / "validation.json"
            comparison_path = root / "comparison.json"

            run_tool(
                "inventory",
                "--config",
                config_path,
                "--json-out",
                inventory_path,
                "--csv-out",
                inventory_csv,
            )
            run_tool(
                "plan",
                "--config",
                config_path,
                "--inventory",
                inventory_path,
                "--source",
                "fixture",
                "--output",
                plan_path,
            )
            shutil.copytree(source_root, stage_root)
            run_tool(
                "apply",
                "--config",
                plan_path,
                "--stage-root",
                stage_root,
            )
            run_tool(
                "validate",
                "--config",
                plan_path,
                "--stage-root",
                stage_root,
                "--report-out",
                validation_path,
            )
            run_tool(
                "compare",
                source_root,
                stage_root,
                "--output",
                comparison_path,
            )

            validation = json.loads(validation_path.read_text(encoding="utf-8"))
            comparison = json.loads(comparison_path.read_text(encoding="utf-8"))
            self.assertEqual(2, validation["aliases_validated"])
            self.assertEqual(1, validation["blobs_validated"])
            self.assertTrue(comparison["equal"])
            self.assertFalse(
                (stage_root / "assets/erydon/textures/block/a.png").exists()
            )
            self.assertFalse(
                (stage_root / "assets/erydon/textures/block/b.png").exists()
            )
            self.assertTrue(
                (stage_root / "assets/erydon/texture_aliases/v1.json").is_file()
            )


class TextureDedupeValidationCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.source_root = root / "source"
        self.texture_root = self.source_root / "assets/erydon/textures/block"
        self.texture_root.mkdir(parents=True)
        for names, color in (("ab", (10, 20, 30, 40)), ("cd", (50, 60, 70, 80))):
            for name in names:
                Image.new("RGBA", (4, 4), color).save(self.texture_root / f"{name}.png")
        self.config = root / "config.json"
        self.config.write_text(json.dumps({
            "schema_version": 1,
            "sources": [{
                "id": "fixture", "tier": "native", "root": "source",
                "namespaces": ["erydon"], "include": ["**/*.png"], "exclude": [],
                "role_rules": [], "metadata_overrides": {}, "intentionally_native_high_res": [],
            }],
            "pilot_groups": [{
                "id": f"fixture-{names}", "selected_by_default": True,
                "source": "fixture", "tier": "native", "namespace": "erydon", "role": "albedo",
                "paths": [f"textures/block/{name}.png" for name in names],
            } for names in ("ab", "cd")],
        }), encoding="utf-8")
        self.stage_root = root / "stage"
        shutil.copytree(self.source_root, self.stage_root)
        self.args = argparse.Namespace(config=self.config, group=[], stage_root=self.stage_root, report_out=None)
        with contextlib.redirect_stdout(io.StringIO()):
            texture_dedupe.command_apply(self.args)

    def validate(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, texture_dedupe.command_validate(self.args))

    def test_shared_targets_checked_once_but_all_baselines_checked(self) -> None:
        with mock.patch.object(texture_dedupe, "inspect_png", wraps=texture_dedupe.inspect_png) as inspect:
            with mock.patch.object(texture_dedupe, "sha256_file", wraps=texture_dedupe.sha256_file) as sha:
                self.validate()
        decoded_paths = [call.args[3] for call in inspect.call_args_list]
        hashed_paths = [call.args[0] for call in sha.call_args_list]
        self.assertEqual(2, sum(path.parent.name == "texture_blobs" for path in decoded_paths))
        self.assertEqual(2, sum(path.parent.name == "texture_blobs" for path in hashed_paths))
        for name in "abcd":
            baseline = self.texture_root / f"{name}.png"
            self.assertEqual(1, decoded_paths.count(baseline))
            self.assertEqual(1, hashed_paths.count(baseline))

    def test_later_alias_metadata_still_checked_for_shared_target(self) -> None:
        path = texture_dedupe.manifest_path(self.stage_root, "erydon")
        manifest = texture_dedupe.read_manifest(path)
        manifest["aliases"][1]["role"] = "specular"
        texture_dedupe.write_json(path, manifest)
        with self.assertRaisesRegex(texture_dedupe.TextureToolError, "Alias differs from baseline"):
            self.validate()

    def test_cached_decode_is_compared_with_each_alias_baseline(self) -> None:
        select = texture_dedupe.selected_records

        def alter_later_decoded_field(*args: object) -> dict:
            records = select(*args)
            for record in records.values():
                if record["path"] == "textures/block/b.png":
                    record["alpha_max"] += 1
            return records

        with mock.patch.object(texture_dedupe, "selected_records", side_effect=alter_later_decoded_field):
            with self.assertRaisesRegex(texture_dedupe.TextureToolError, "Decoded alpha_max differs from baseline"):
                self.validate()

    def test_later_baseline_changed_during_validation_is_rejected(self) -> None:
        inspect = texture_dedupe.inspect_png
        changed = False

        def change_later_baseline(*args: object) -> dict:
            nonlocal changed
            result = inspect(*args)
            if args[3].parent.name == "texture_blobs" and not changed:
                Image.new("RGBA", (4, 4), (90, 100, 110, 120)).save(self.texture_root / "b.png")
                changed = True
            return result

        with mock.patch.object(texture_dedupe, "inspect_png", side_effect=change_later_baseline):
            with self.assertRaisesRegex(texture_dedupe.TextureToolError, "Baseline changed during validation"):
                self.validate()
        self.assertTrue(changed)

    def test_corrupt_blob_is_rechecked_on_the_next_invocation(self) -> None:
        self.validate()
        path = next((self.stage_root / "assets/erydon/texture_blobs").glob("*.png"))
        path.write_bytes(b"corrupt")
        with self.assertRaisesRegex(texture_dedupe.TextureToolError, "Blob SHA mismatch"):
            self.validate()

    def test_missing_other_blob_is_not_hidden_by_a_cached_target(self) -> None:
        manifest = texture_dedupe.read_manifest(texture_dedupe.manifest_path(self.stage_root, "erydon"))
        later_target = self.stage_root / manifest["aliases"][-1]["target"]
        later_target.unlink()
        with self.assertRaisesRegex(texture_dedupe.TextureToolError, "Dangling blob target"):
            self.validate()


class TextureDedupeReferenceTest(unittest.TestCase):
    def test_inventory_attaches_direct_ctm_and_labpbr_references(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            namespace_root = root / "assets" / "minecraft"
            texture = (
                namespace_root
                / "textures"
                / "optifine"
                / "ctm"
                / "example"
                / "0_n.png"
            )
            texture.parent.mkdir(parents=True)
            Image.new("RGBA", (2, 2), (128, 128, 255, 64)).save(texture)
            properties = (
                namespace_root
                / "optifine"
                / "ctm"
                / "example"
                / "example.properties"
            )
            properties.parent.mkdir(parents=True)
            properties.write_text(
                "matchTiles=minecraft:block/example\ntiles=0\n",
                encoding="ascii",
            )
            source = texture_dedupe.SourceConfig(
                source_id="source",
                tier="native",
                root=root,
                root_label=".",
                namespaces=("minecraft",),
                include=("**/*.png",),
                exclude=(),
                role_rules=(),
                metadata_overrides={},
                intentionally_native_high_res=(),
            )
            config = texture_dedupe.ToolConfig(
                path=root / "config.json",
                sha256="config",
                sources=(source,),
                pilot_groups=(),
            )
            records = texture_dedupe.inventory_records(config)
            self.assertEqual(1, len(records))
            item = records[0]
            self.assertEqual("normal", item["role"])
            self.assertTrue(item["is_ctm"])
            self.assertIn(
                "assets/minecraft/optifine/ctm/example/example.properties",
                item["known_references"],
            )
            self.assertIn(
                "implicit:labpbr-sidecar-of:minecraft:optifine/ctm/example/0",
                item["known_references"],
            )

    def test_archive_metrics_count_aliases_blobs_and_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "fixture.zip"
            manifest = {
                "aliases": [{"path": "textures/block/a.png"}],
                "blobs": [{"target": "assets/erydon/texture_blobs/a.png"}],
            }
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "assets/erydon/texture_aliases/v1.json",
                    json.dumps(manifest),
                )
                archive.writestr(
                    "assets/erydon/texture_blobs/a.png", b"png"
                )
                archive.writestr(
                    "assets/minecraft/optifine/ctm/a.properties", b"tiles=0"
                )
            metrics = texture_dedupe.container_metrics(archive_path)
            self.assertEqual(3, metrics["entry_count"])
            self.assertEqual(1, metrics["physical_png_count"])
            self.assertEqual(1, metrics["alias_count"])
            self.assertEqual(1, metrics["canonical_blob_count"])
            self.assertEqual(1, metrics["properties_count"])


if __name__ == "__main__":
    unittest.main()
