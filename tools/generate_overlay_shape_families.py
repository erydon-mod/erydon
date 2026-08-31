#!/usr/bin/env python3
"""Generate the approved basic shape set for every ERYDON metal-inlay overlay."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src" / "main" / "resources"

MATERIALS = (
    "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
    "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
    "kylorion", "laurentium", "mielonyx", "nerium", "noxoplis", "porphyros",
    "portorium", "rosinium", "sanguenite", "selenephos", "solistra", "striatus",
)

ALL_FORMS = (
    "slab",
    "slice_horizontal",
    "slice_vertical",
    "post",
    "stairs",
    "stairs_shallow_bottom",
    "stairs_shallow_top",
    "wall",
    "layer",
    "layer_multiface",
    "layer_vertical",
    "slope",
    "slope_shallow_lower",
    "slope_shallow_upper",
    "slope_steep_lower",
    "slope_steep_upper",
    "slope_vertical",
    "slope_vertical_shallow_broad",
    "slope_vertical_shallow_narrow",
)

FORMS = (
    "stairs",
    "stairs_shallow_bottom",
    "stairs_shallow_top",
    "layer_multiface",
    "slope",
    "slope_shallow_lower",
    "slope_shallow_upper",
    "slope_steep_lower",
    "slope_steep_upper",
    "slope_vertical",
    "slope_vertical_shallow_broad",
    "slope_vertical_shallow_narrow",
)

REMOVED_FORMS = tuple(form for form in ALL_FORMS if form not in FORMS)

OVERLAYS = (
    ("trim", "bronze", "trim"),
    ("trim", "silver", "trim"),
    ("guilloche", "bronze", "guilloche"),
    ("guilloche", "silver", "guilloche"),
    ("quatrefoil", "bronze", "quatrefoil"),
    ("quatrefoil", "silver", "quatrefoil"),
    ("rosette", "bronze", "rose"),
    ("rosette", "silver", "rose"),
)

LEGACY_BASE_RE = re.compile(
    r"^erydon:(?P<material>[a-z0-9_]+)_block_(?P<metal>bronze|silver)"
    r"(?P<motif>trim|guilloche|quatrefoil|rose)$"
)


@dataclass(frozen=True)
class Family:
    material: str
    motif: str
    metal: str
    legacy_motif: str

    @property
    def prefix(self) -> str:
        return f"{self.material}_{self.motif}_{self.metal}"

    @property
    def legacy_texture(self) -> str:
        return f"{self.material}_block_{self.metal}{self.legacy_motif}"

    @property
    def shape_ids(self) -> tuple[str, ...]:
        return self.shape_ids_for(FORMS)

    @property
    def all_shape_ids(self) -> tuple[str, ...]:
        return self.shape_ids_for(ALL_FORMS)

    def shape_ids_for(self, forms: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(f"erydon:{self.prefix}_{form}" for form in forms)


FAMILIES = tuple(
    Family(material, motif, metal, legacy_motif)
    for material in MATERIALS
    for motif, metal, legacy_motif in OVERLAYS
)
FAMILY_BY_LEGACY = {
    f"erydon:{family.legacy_texture}": family for family in FAMILIES
}


class Writer:
    def __init__(self, check: bool) -> None:
        self.check = check
        self.changed = 0
        self.created = 0
        self.deleted = 0

    def bytes(self, path: Path, content: bytes) -> None:
        current = path.read_bytes() if path.exists() else None
        if current == content:
            return
        self.changed += 1
        if current is None:
            self.created += 1
        if not self.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

    def text(self, path: Path, content: str) -> None:
        self.bytes(path, content.encode("utf-8"))

    def delete(self, path: Path) -> None:
        if not path.exists():
            return
        self.changed += 1
        self.deleted += 1
        if not self.check:
            path.unlink()


def template_family_for(family: Family) -> tuple[str, str]:
    if family.motif == "trim":
        return "nerium_trim_bronze", "nerium_block_bronzetrim"
    return "nerium_guilloche_bronze", "nerium_block_bronzeguilloche"


def model_form(path: Path, family_prefix: str) -> str | None:
    block_models = RESOURCES / "assets" / "erydon" / "models" / "block"
    relative = path.relative_to(block_models)
    parts = relative.parts
    if parts[0] == "slab":
        return "slab"
    if parts[0] == "wall":
        return "wall"
    if parts[:2] == ("layer", "slice_horizontal"):
        return "slice_horizontal"
    if parts[:2] == ("layer", "slice_vertical"):
        return "slice_vertical"
    if parts[:2] == ("layer", "slice_post"):
        return "post"
    if parts[:2] == ("layer", "layer_multiface"):
        return "layer_multiface"
    if parts[:2] == ("layer", "layer"):
        suffix = path.stem[len(family_prefix) + 1:]
        return "layer_vertical" if suffix.startswith("layer_vertical_") else "layer"
    if parts[0] == "stairs":
        return "stairs"
    if parts[0] == "slope":
        return "slope"
    if parts[:2] == ("internal", "wrapped"):
        suffix = path.stem[len(family_prefix) + 1:]
        for form in sorted(ALL_FORMS, key=len, reverse=True):
            if suffix == form or suffix.startswith(form + "_"):
                return form
    return None


def template_files(template_family: str) -> tuple[Path, ...]:
    assets = RESOURCES / "assets" / "erydon"
    paths: list[Path] = []
    for folder in (assets / "blockstates", assets / "models" / "item"):
        for path in folder.glob(f"{template_family}_*.json"):
            suffix = path.stem[len(template_family) + 1:]
            if suffix in FORMS:
                paths.append(path)
    for path in (assets / "models" / "block").rglob(f"{template_family}_*.json"):
        form = model_form(path, template_family)
        if form is None or form in FORMS:
            paths.append(path)
    paths = sorted(set(paths))
    if not paths:
        raise RuntimeError(f"No files found for template {template_family}")
    return tuple(paths)


def generate_assets(writer: Writer) -> None:
    templates = {
        template: template_files(template)
        for template in ("nerium_trim_bronze", "nerium_guilloche_bronze")
    }
    template_sizes = {template: len(paths) for template, paths in templates.items()}
    if len(set(template_sizes.values())) != 1:
        raise RuntimeError(f"Overlay templates differ in size: {template_sizes}")
    for family in FAMILIES:
        template_family, template_texture = template_family_for(family)
        if family.prefix == template_family:
            continue
        for source in templates[template_family]:
            relative = source.relative_to(RESOURCES)
            target_relative = Path(str(relative).replace(template_family, family.prefix))
            content = source.read_text(encoding="utf-8")
            content = content.replace(
                "nerium_herringbone_bronze",
                f"{family.material}_herringbone_bronze",
            )
            content = content.replace(template_texture, family.legacy_texture)
            content = content.replace(template_family, family.prefix)
            writer.text(RESOURCES / target_relative, content)


def prune_removed_assets(writer: Writer) -> None:
    assets = RESOURCES / "assets" / "erydon"
    for family in FAMILIES:
        for folder in (assets / "blockstates", assets / "models" / "item"):
            for form in REMOVED_FORMS:
                writer.delete(folder / f"{family.prefix}_{form}.json")
        for path in (assets / "models" / "block").rglob(f"{family.prefix}_*.json"):
            if model_form(path, family.prefix) in REMOVED_FORMS:
                writer.delete(path)


def newline_for(text: str) -> str:
    return "\r\n" if "\r\n" in text else "\n"


def property_values(text: str, key: str) -> list[str]:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith(key + "="):
            continue
        values: list[str] = []
        current = index
        while current < len(lines):
            raw = lines[current]
            if current == index:
                raw = raw[len(key) + 1:]
            continued = raw.rstrip().endswith("\\")
            raw = raw.rstrip()
            if continued:
                raw = raw[:-1]
            values.extend(raw.split())
            current += 1
            if not continued:
                return values
    raise RuntimeError(f"Missing property {key}")


def replace_property_values(text: str, key: str, values: list[str]) -> str:
    newline = newline_for(text)
    lines = text.splitlines(keepends=True)
    start = None
    end = None
    for index, line in enumerate(lines):
        bare = line.rstrip("\r\n")
        if bare.startswith(key + "="):
            start = index
            end = index + 1
            while bare.rstrip().endswith("\\") and end < len(lines):
                bare = lines[end].rstrip("\r\n")
                end += 1
            break
    if start is None or end is None:
        raise RuntimeError(f"Missing property {key}")
    replacement = []
    for index, value in enumerate(values):
        prefix = f"{key}=" if index == 0 else "  "
        continuation = " \\" if index + 1 < len(values) else ""
        replacement.append(prefix + value + continuation + newline)
    lines[start:end] = replacement
    return "".join(lines)


def set_property(text: str, key: str, value: str) -> str:
    newline = newline_for(text)
    lines = text.splitlines(keepends=True)
    replacement = f"{key}={value}{newline}"
    for index, line in enumerate(lines):
        if line.rstrip("\r\n").startswith(key + "="):
            lines[index] = replacement
            return "".join(lines)
    if text and not text.endswith(("\n", "\r")):
        text += newline
    return text + replacement


def update_overlay_property_tree(writer: Writer, assets_root: Path) -> None:
    ctm_root = assets_root / "minecraft" / "optifine" / "ctm"
    overlay_paths = sorted(ctm_root.rglob("*.properties"))
    overlay_paths = [
        path for path in overlay_paths
        if path.read_bytes().decode("latin-1").startswith("method=overlay_ctm")
    ]
    if len(overlay_paths) != 192:
        raise RuntimeError(f"Expected 192 overlay rules in {ctm_root}, found {len(overlay_paths)}")

    seen: set[str] = set()
    for path in overlay_paths:
        text = path.read_bytes().decode("latin-1")
        base_values = property_values(text, "matchBlocks")
        if not base_values:
            raise RuntimeError(f"No matchBlocks values in {path}")
        base = base_values[0]
        family = FAMILY_BY_LEGACY.get(base)
        if family is None:
            raise RuntimeError(f"Unexpected overlay base {base} in {path}")
        if base in seen:
            raise RuntimeError(f"Duplicate overlay base {base}")
        seen.add(base)
        text = replace_property_values(text, "matchBlocks", [base, *family.shape_ids])
        text = set_property(text, "SynapheiaOverlayShape", "source")
        text = set_property(text, "SynapheiaOverlayConnect", "rule")
        desired_layer = (
            "cutout" if family.motif in {"quatrefoil", "rosette"} else "cutout_mipped"
        )
        if property_values(text, "layer") != [desired_layer]:
            text = set_property(text, "layer", desired_layer)
        writer.bytes(path, text.encode("latin-1"))


def update_repeat_property_tree(writer: Writer,
                                assets_root: Path,
                                require_all: bool = True) -> None:
    ctm_root = assets_root / "minecraft" / "optifine" / "ctm"
    for material in MATERIALS:
        path = ctm_root / material / f"e_{material}_trim_base.properties"
        if not path.is_file():
            if require_all:
                raise RuntimeError(f"Missing base repeat rule {path}")
            continue
        text = path.read_bytes().decode("latin-1")
        bases = [
            value for value in property_values(text, "matchBlocks")
            if value in FAMILY_BY_LEGACY
        ]
        bases = list(dict.fromkeys(bases))
        if len(bases) != 8:
            raise RuntimeError(f"Expected 8 overlay bases in {path}, found {len(bases)}")
        expanded: list[str] = []
        for base in bases:
            family = FAMILY_BY_LEGACY[base]
            if family.material != material:
                raise RuntimeError(f"Wrong material {base} in {path}")
            expanded.extend((base, *family.shape_ids))
        text = replace_property_values(text, "matchBlocks", expanded)
        writer.bytes(path, text.encode("latin-1"))


def update_property_tree(writer: Writer, assets_root: Path) -> None:
    update_overlay_property_tree(writer, assets_root)
    update_repeat_property_tree(writer, assets_root)


def sync_tag_values(writer: Writer,
                    path: Path,
                    owned_values: list[str],
                    desired_values: list[str]) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    existing = data.setdefault("values", [])
    owned = set(owned_values)
    desired = set(desired_values)
    existing[:] = [
        value for value in existing
        if not (isinstance(value, str) and value in owned and value not in desired)
    ]
    present = {value for value in existing if isinstance(value, str)}
    for value in desired_values:
        if value not in present:
            existing.append(value)
            present.add(value)
    writer.text(path, json.dumps(data, ensure_ascii=False, indent=2) + "\n")


FORM_TAGS = {
    "slab": ("slab",),
    "slice_horizontal": ("horizontal_slice", "horizontal"),
    "slice_vertical": ("vertical_slice", "vertical"),
    "post": ("post",),
    "stairs": ("stairs",),
    "stairs_shallow_bottom": ("stairs_shallow", "stairs_shallow_bottom", "shallow"),
    "stairs_shallow_top": ("stairs_shallow", "stairs_shallow_top", "shallow"),
    "wall": ("wall", "walls"),
    "layer": ("layer",),
    "layer_multiface": ("layer", "layer_multiface"),
    "layer_vertical": ("layer", "layer_vertical", "vertical"),
    "slope": ("slope",),
    "slope_shallow_lower": ("slope_shallow", "slope_shallow_lower", "shallow"),
    "slope_shallow_upper": ("slope_shallow", "slope_shallow_upper", "shallow"),
    "slope_steep_lower": ("slope_steep", "slope_steep_lower", "steep"),
    "slope_steep_upper": ("slope_steep", "slope_steep_upper", "steep"),
    "slope_vertical": ("slope_vertical", "vertical"),
    "slope_vertical_shallow_broad": (
        "slope_vertical_shallow", "slope_vertical_shallow_broad", "vertical", "shallow", "broad",
    ),
    "slope_vertical_shallow_narrow": (
        "slope_vertical_shallow", "slope_vertical_shallow_narrow", "vertical", "shallow", "narrow",
    ),
}


def update_tags(writer: Writer) -> None:
    tag_root = RESOURCES / "data" / "erydon" / "tags" / "blocks"
    all_shapes = [shape for family in FAMILIES for shape in family.shape_ids]
    all_owned_shapes = [shape for family in FAMILIES for shape in family.all_shape_ids]
    sync_tag_values(writer, tag_root / "erydon_blocks.json", all_owned_shapes, all_shapes)
    sync_tag_values(writer, tag_root / "marble.json", all_owned_shapes, all_shapes)
    sync_tag_values(
        writer,
        RESOURCES / "data" / "material" / "tags" / "blocks" / "stone.json",
        all_owned_shapes,
        all_shapes,
    )

    for material in MATERIALS:
        values = [shape for family in FAMILIES if family.material == material for shape in family.shape_ids]
        owned = [shape for family in FAMILIES if family.material == material for shape in family.all_shape_ids]
        sync_tag_values(writer, tag_root / f"{material}.json", owned, values)
    for metal in ("bronze", "silver"):
        values = [shape for family in FAMILIES if family.metal == metal for shape in family.shape_ids]
        owned = [shape for family in FAMILIES if family.metal == metal for shape in family.all_shape_ids]
        sync_tag_values(writer, tag_root / f"{metal}.json", owned, values)
    for motif, tag in (
        ("trim", "trim"),
        ("guilloche", "guilloche"),
        ("quatrefoil", "quatrefoil"),
        ("rosette", "rose"),
    ):
        values = [shape for family in FAMILIES if family.motif == motif for shape in family.shape_ids]
        owned = [shape for family in FAMILIES if family.motif == motif for shape in family.all_shape_ids]
        sync_tag_values(writer, tag_root / f"{tag}.json", owned, values)

    by_tag: dict[str, list[str]] = {}
    owned_by_tag: dict[str, list[str]] = {}
    for family in FAMILIES:
        for form, shape in zip(FORMS, family.shape_ids):
            for tag in FORM_TAGS[form]:
                by_tag.setdefault(tag, []).append(shape)
        for form, shape in zip(ALL_FORMS, family.all_shape_ids):
            for tag in FORM_TAGS[form]:
                owned_by_tag.setdefault(tag, []).append(shape)
    for tag in sorted(owned_by_tag):
        sync_tag_values(
            writer,
            tag_root / f"{tag}.json",
            owned_by_tag[tag],
            by_tag.get(tag, []),
        )
    sync_tag_values(
        writer,
        RESOURCES / "data" / "minecraft" / "tags" / "blocks" / "walls.json",
        owned_by_tag["wall"],
        by_tag.get("wall", []),
    )


def append_language_entries(writer: Writer, path: Path) -> None:
    raw = path.read_bytes().decode("utf-8")
    language = json.loads(raw)
    reference_base = language["block.erydon.nerium_trim_bronze_block"].rsplit(" ", 1)[0]
    labels: dict[str, str] = {}
    for form in FORMS:
        value = language[f"block.erydon.nerium_trim_bronze_{form}"]
        if not value.startswith(reference_base + " "):
            raise RuntimeError(f"Unexpected reference translation for {form} in {path}")
        labels[form] = value[len(reference_base) + 1:]

    expected: dict[str, str] = {}
    removed_keys = {
        f"block.erydon.{family.prefix}_{form}"
        for family in FAMILIES
        for form in REMOVED_FORMS
    }
    for family in FAMILIES:
        base_key = f"block.erydon.{family.prefix}_block"
        base = language[base_key].rsplit(" ", 1)[0]
        for form in FORMS:
            key = f"block.erydon.{family.prefix}_{form}"
            value = f"{base} {labels[form]}"
            expected[key] = value
            if key in language:
                if language[key] != value:
                    raise RuntimeError(f"Unexpected existing translation {key} in {path}")
    additions = [(key, value) for key, value in expected.items() if key not in language]

    # Git treats CR characters on newly generated JSON lines as trailing whitespace.
    # Normalize only this generator's entries, preserving every unrelated line byte-for-byte.
    normalized_lines: list[str] = []
    for line in raw.splitlines(keepends=True):
        stripped = line.strip()
        comma = stripped.endswith(",")
        candidate = stripped[:-1] if comma else stripped
        key = None
        if candidate.startswith('"') and ":" in candidate:
            try:
                parsed = json.loads("{" + candidate + "}")
                if len(parsed) == 1:
                    key = next(iter(parsed))
            except json.JSONDecodeError:
                pass
        if key in removed_keys:
            continue
        if key in expected:
            normalized_lines.append(
                "    " + json.dumps(key, ensure_ascii=False) + ": "
                + json.dumps(expected[key], ensure_ascii=False)
                + ("," if comma else "") + "\n"
            )
        else:
            normalized_lines.append(line)
    raw = "".join(normalized_lines)

    if not additions:
        json.loads(raw)
        writer.bytes(path, raw.encode("utf-8"))
        return
    newline = "\n"
    close = raw.rfind("}")
    if close < 0:
        raise RuntimeError(f"Invalid language JSON {path}")
    before = raw[:close].rstrip()
    encoded = []
    for key, value in additions:
        encoded.append(
            "    " + json.dumps(key, ensure_ascii=False) + ": "
            + json.dumps(value, ensure_ascii=False)
        )
    updated = before + "," + newline + ("," + newline).join(encoded) + newline + raw[close:]
    json.loads(updated)
    writer.bytes(path, updated.encode("utf-8"))


def update_languages(writer: Writer) -> None:
    root = RESOURCES / "assets" / "erydon" / "lang"
    for name in ("en_us.json", "de_de.json", "es_es.json"):
        append_language_entries(writer, root / name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--collection-root",
        type=Path,
        help="Optional erydon-collection-resource-packs checkout to update/check.",
    )
    parser.add_argument(
        "--overlay-only-assets-root",
        action="append",
        default=[],
        type=Path,
        help="Optional assets root containing the 192 overlay rules but no repeat rules.",
    )
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    writer = Writer(args.check)
    generate_assets(writer)
    prune_removed_assets(writer)
    update_property_tree(writer, RESOURCES / "assets")
    update_tags(writer)
    update_languages(writer)

    if args.collection_root is not None:
        collection = args.collection_root.resolve()
        for tier in ("32x", "64x"):
            update_property_tree(writer, collection / "packs" / tier / "assets")
    for assets_root in args.overlay_only_assets_root:
        resolved = assets_root.resolve()
        update_overlay_property_tree(writer, resolved)
        update_repeat_property_tree(writer, resolved, require_all=False)

    summary = {
        "families": len(FAMILIES),
        "forms_per_family": len(FORMS),
        "shape_blocks": len(FAMILIES) * len(FORMS),
        "files_created": writer.created,
        "files_deleted": writer.deleted,
        "files_changed": writer.changed,
        "mode": "check" if args.check else "write",
    }
    print(json.dumps(summary, indent=2))
    if args.check and writer.changed:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
