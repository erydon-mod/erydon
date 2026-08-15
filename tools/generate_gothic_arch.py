#!/usr/bin/env python3
"""Normalize and integrate ERYDON's raw-authored Gothic arch family.

The checked-in ``authoring_models`` files are the geometry source of truth.
The live family deliberately mirrors Modern Arch behaviour, so the unused
``side_medium_upper`` and ``side_large_upper`` column-transition pieces and
the Blockbench assembly preview are not imported or baked.

Run from any directory:
    python tools/generate_gothic_arch.py
    python tools/generate_gothic_arch.py --check
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path


TOOLS_ROOT = Path(__file__).resolve().parent
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

import model_raw_uv_safety as raw_uv


RENDER_SUFFIXES = (
    "corner_small",
    "corner_medium",
    "corner_large_upper",
    "corner_large_lower",
    "side_small",
    "side_medium",
    "side_large",
    "top_large",
)
MODEL_SUFFIXES = RENDER_SUFFIXES + ("icon",)
STONE_TEXTURE = "erydon:block/aganite_block"

LANGUAGE_PROFILE_NAMES = {
    "de_de.json": ("Modern Bogen", "Gotischer Bogen"),
    "en_us.json": ("Modern Arch", "Gothic Arch"),
    "es_es.json": ("Arco moderno", "Arco gótico"),
}

TOOLTIPS = {
    "de_de.json": (
        "Baut automatisch gotische Bögen mit 1-3 Blöcken Breite in beliebiger Höhe.",
        "Verwendet einen einfachen Rahmen ohne den romanischen Säulenstil.",
        "Nutze /recalc, um Gruppen neu aufzubauen.",
    ),
    "en_us.json": (
        "Auto-assembles Gothic arches 1-3 blocks wide at any height.",
        "Uses a simple frame without the Romanesque column style.",
        "Use /recalc to rebuild clusters.",
    ),
    "es_es.json": (
        "Ensambla automáticamente arcos góticos de 1 a 3 bloques de ancho a cualquier altura.",
        "Utiliza un marco sencillo sin el estilo de columnas románico.",
        "Usa /recalc para reconstruir los grupos.",
    ),
}

# The Blockbench sources contain exact coplanar overlaps. These locked,
# sub-pixel inward adjustments remove them without changing the visible
# silhouette. The icon uses the smaller value to avoid meeting nearby trim.
GEOMETRY_NUDGES = {
    "corner_small": (
        (17, "from", 2, 0.0, 0.001),
        (18, "from", 2, 0.0, 0.001),
    ),
    "top_large": (
        (5, "from", 2, 0.0, 0.001),
        (5, "to", 2, 16.0, 15.999),
        (5, "to", 1, 16.00795, 16.00695),
    ),
    "icon": (
        (9, "from", 2, 7.6059, 7.6061),
        (9, "to", 2, 15.6059, 15.6057),
        (9, "to", 1, 16.00267, 16.00247),
        (19, "to", 2, 15.6059, 15.6056),
    ),
}


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _load_json(path: Path) -> object:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def _normalize_authoring_model(model: dict, *, suffix: str) -> dict:
    """Bind the neutral stone texture while preserving authored geometry."""
    result = copy.deepcopy(model)
    for element_index, member, axis, before, after in GEOMETRY_NUDGES.get(suffix, ()):
        values = result["elements"][element_index][member]
        current = float(values[axis])
        if abs(current - before) <= 1.0e-9:
            values[axis] = after
        elif abs(current - after) > 1.0e-9:
            raise ValueError(
                f"Unexpected Gothic Arch nudge source at {suffix} "
                f"elements[{element_index}].{member}[{axis}]: {current}"
            )
    result["textures"] = {
        "particle": STONE_TEXTURE,
        "stone": STONE_TEXTURE,
    }
    for element in result.get("elements", []):
        for face in element.get("faces", {}).values():
            if suffix != "icon":
                face.pop("uv", None)
            face["texture"] = "#stone"
    if suffix != "icon":
        findings, _ = raw_uv._audit_document(
            result, "generated-gothic-arch.json", "0" * 64
        )
        for finding in findings:
            if not finding["rotated"] or finding["operationClass"] is not None:
                continue
            index = finding["elementIndex"]
            direction = finding["face"]
            authored_face = model["elements"][index]["faces"][direction]
            authored_uv = authored_face.get("uv")
            if authored_uv is None:
                raise ValueError(
                    "Gothic arch has a boundary-crossing implicit face without "
                    f"an authored fallback UV: {finding['facePointer']}"
                )
            result["elements"][index]["faces"][direction]["uv"] = copy.deepcopy(
                authored_uv
            )
    return _repair_rotated_uvs(result)


def _repair_rotated_uvs(model: dict) -> dict:
    """Apply only deterministic, geometry-preserving raw-loader UV offsets."""
    result = copy.deepcopy(model)
    findings, _ = raw_uv._audit_document(result, "generated-gothic-arch.json", "0" * 64)
    for finding in findings:
        if not finding["rotated"]:
            continue
        if (
            finding["operationClass"] != "implicit_uniform_offset"
            or finding["proposedOffset"] is None
        ):
            raise ValueError(
                "Gothic arch has a rotated UV finding that cannot be repaired "
                f"without changing authored mapping: {finding['facePointer']}"
            )
        face = result["elements"][finding["elementIndex"]]["faces"][finding["face"]]
        face[raw_uv.OFFSET_KEY] = finding["proposedOffset"]

    _, post_counts = raw_uv._audit_document(result, "generated-gothic-arch.json", "0" * 64)
    if post_counts.get("rotatedOutOfRangeFaces", 0):
        raise ValueError("Gothic arch UV repair did not converge")
    return result


def _family_text(text: str) -> str:
    return text.replace("/modern/", "/gothic/").replace(
        "arch_modern", "arch_gothic"
    )


def _gothic_id(modern_id: str) -> str:
    return modern_id.replace("_arch_modern", "_arch_gothic")


def _split_aged(block_id: str) -> tuple[str, bool]:
    if block_id.endswith("_aged"):
        return block_id[: -len("_aged")], True
    if "_aged_" in block_id:
        return block_id.replace("_aged_", "_", 1), True
    return block_id, False


def _component_filename(block_id: str, suffix: str) -> str:
    base, aged = _split_aged(block_id)
    return f"{base}_{suffix}{'_aged' if aged else ''}.json"


def _registered_modern_ids(resources: Path) -> list[str]:
    blockstates = resources / "assets" / "erydon" / "blockstates"
    pattern = re.compile(r"^(.+_arch_modern(?:_aged)?)\.json$")
    result = []
    for path in sorted(blockstates.glob("*_arch_modern*.json")):
        match = pattern.fullmatch(path.name)
        if match:
            result.append(match.group(1))
    if len(result) != 162:
        raise ValueError(f"Expected 162 Modern Arch blockstates, found {len(result)}")
    return result


def _empty_component_template() -> dict:
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/stone"},
        "elements": [],
    }


def _icon_template(authoring_icon: dict) -> dict:
    result = {
        "parent": "minecraft:block/block",
        "textures": {
            "stone": "minecraft:block/stone",
            "particle": "#stone",
        },
    }
    if "display" in authoring_icon:
        result["display"] = copy.deepcopy(authoring_icon["display"])
    return result


def _expected_new_files(repo_root: Path, modern_ids: list[str]) -> dict[Path, bytes]:
    resources = repo_root / "src" / "main" / "resources"
    assets = resources / "assets" / "erydon"
    authoring = assets / "authoring_models" / "block" / "arch" / "gothic"
    expected: dict[Path, bytes] = {}
    normalized: dict[str, dict] = {}

    for suffix in MODEL_SUFFIXES:
        target = authoring / f"arch_gothic_{suffix}.json"
        if not target.is_file():
            raise ValueError(f"Missing Gothic arch authoring model: {target}")
        document = _load_json(target)
        if not isinstance(document, dict):
            raise ValueError(f"Gothic arch authoring model is not an object: {target}")
        normalized[suffix] = _normalize_authoring_model(document, suffix=suffix)
        expected[target] = _json_bytes(normalized[suffix])

    component_source = assets / "models" / "block" / "arch" / "modern"
    component_target = assets / "models" / "block" / "arch" / "gothic"
    for suffix in RENDER_SUFFIXES:
        expected[component_target / f"arch_gothic_{suffix}.json"] = _json_bytes(
            _empty_component_template()
        )
    expected[component_target / "arch_gothic_icon.json"] = _json_bytes(
        _icon_template(normalized["icon"])
    )

    blockstates = assets / "blockstates"
    wrapped = assets / "models" / "block" / "internal" / "wrapped"
    item_models = assets / "models" / "item"
    for modern_id in modern_ids:
        gothic_id = _gothic_id(modern_id)
        for source_root, target_root in (
            (blockstates, blockstates),
            (wrapped, wrapped),
            (item_models, item_models),
        ):
            source = source_root / f"{modern_id}.json"
            target = target_root / f"{gothic_id}.json"
            expected[target] = _family_text(source.read_text(encoding="utf-8")).encode("utf-8")

        for suffix in MODEL_SUFFIXES:
            target = component_target / _component_filename(gothic_id, suffix)
            if suffix == "icon":
                item_document = _load_json(item_models / f"{modern_id}.json")
                expected[target] = _json_bytes(
                    {
                        "parent": "erydon:block/arch/gothic/arch_gothic_icon",
                        "textures": item_document.get("textures", {}),
                    }
                )
            else:
                source = component_source / _component_filename(modern_id, suffix)
                expected[target] = _family_text(source.read_text(encoding="utf-8")).encode("utf-8")

    java_target = (
        repo_root
        / "src"
        / "main"
        / "java"
        / "com"
        / "oliver"
        / "erydon"
        / "block"
        / "ArchGothicBlock.java"
    )
    expected[java_target] = _gothic_block_java(normalized).encode("utf-8")
    return expected


def _element_boxes(model: dict) -> list[tuple[float, float, float, float, float, float]]:
    group_rotations = raw_uv._collect_group_rotations(model)
    boxes = []
    for index, element in enumerate(model.get("elements", [])):
        start = tuple(float(value) for value in element["from"][:3])
        end = tuple(float(value) for value in element["to"][:3])
        default_origin = tuple(float(value) for value in element.get("origin", (8.0, 8.0, 8.0))[:3])
        element_rotation = raw_uv.RawRotation.parse(
            element.get("rotation"), default_origin, f"elements[{index}].rotation"
        )
        rotations = ([] if element_rotation.identity else [element_rotation]) + group_rotations.get(index, [])
        vertices = [
            (x, y, z)
            for x in (start[0], end[0])
            for y in (start[1], end[1])
            for z in (start[2], end[2])
        ]
        for rotation in rotations:
            vertices = [rotation.transform(vertex) for vertex in vertices]
        boxes.append(
            (
                min(vertex[0] for vertex in vertices),
                min(vertex[1] for vertex in vertices),
                min(vertex[2] for vertex in vertices),
                max(vertex[0] for vertex in vertices),
                max(vertex[1] for vertex in vertices),
                max(vertex[2] for vertex in vertices),
            )
        )
    return boxes


def _java_number(value: float) -> str:
    result = f"{value / 16.0:.8f}".rstrip("0").rstrip(".")
    return "0.0" if result in {"", "-0"} else result


def _shape_method(name: str, boxes: list[tuple[float, float, float, float, float, float]]) -> str:
    lines = [f"    private static VoxelShape make{name}Shape() {{", "        VoxelShape shape = VoxelShapes.empty();"]
    for box in boxes:
        values = ", ".join(_java_number(value) for value in box)
        lines.append(f"        shape = VoxelShapes.union(shape, VoxelShapes.cuboid({values}));")
    lines.extend(("        return shape.simplify();", "    }"))
    return "\n".join(lines)


def _gothic_block_java(models: dict[str, dict]) -> str:
    methods = []
    for suffix in RENDER_SUFFIXES:
        method_name = "".join(part.capitalize() for part in suffix.split("_"))
        methods.append(_shape_method(method_name, _element_boxes(models[suffix])))
    shape_methods = "\n\n".join(methods)
    return f"""package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public class ArchGothicBlock extends ArchModernBlock {{
    private static final VoxelShape SHAPE_EMPTY = VoxelShapes.empty();
    private static final VoxelShape SHAPE_CORNER_SMALL = makeCornerSmallShape();
    private static final VoxelShape SHAPE_CORNER_MEDIUM = makeCornerMediumShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_UPPER = makeCornerLargeUpperShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_LOWER = makeCornerLargeLowerShape();
    private static final VoxelShape SHAPE_SIDE_SMALL = makeSideSmallShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM = makeSideMediumShape();
    private static final VoxelShape SHAPE_SIDE_LARGE = makeSideLargeShape();
    private static final VoxelShape SHAPE_TOP_LARGE = makeTopLargeShape();
    private static final VoxelShape[] GOTHIC_SHAPE_CACHE = new VoxelShape[Arrangement.values().length * 4];

    public ArchGothicBlock(Settings settings) {{
        super(settings);
    }}

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {{
        if (state.get(ARRANGEMENT).isVoid()) {{
            return VoxelShapes.fullCube();
        }}
        return getGothicWorldSpaceShape(state);
    }}

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {{
        if (state.get(ARRANGEMENT).isVoid()) {{
            return SHAPE_EMPTY;
        }}
        return getGothicWorldSpaceShape(state);
    }}

    private static VoxelShape getGothicWorldSpaceShape(BlockState state) {{
        Direction facing = state.get(FACING);
        Arrangement arrangement = state.get(ARRANGEMENT);
        int index = arrangement.ordinal() * 4 + facingTurns(facing);
        VoxelShape cached = GOTHIC_SHAPE_CACHE[index];
        if (cached != null) {{
            return cached;
        }}

        VoxelShape shape = SHAPE_EMPTY;
        shape = VoxelShapes.union(shape, cornerShape(arrangement.corner(), arrangement.cornerFlip()));
        shape = VoxelShapes.union(shape, sideShape(arrangement.sideL(), false));
        shape = VoxelShapes.union(shape, sideShape(arrangement.sideR(), true));
        if (arrangement.hasTopLarge()) {{
            shape = VoxelShapes.union(shape, SHAPE_TOP_LARGE);
        }}

        VoxelShape rotated = rotateShapeY(shape, facingTurns(facing)).simplify();
        GOTHIC_SHAPE_CACHE[index] = rotated;
        return rotated;
    }}

    private static VoxelShape cornerShape(Corner corner, boolean flip) {{
        VoxelShape shape = switch (corner) {{
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_CORNER_SMALL;
            case MEDIUM -> SHAPE_CORNER_MEDIUM;
            case LARGE_UPPER -> SHAPE_CORNER_LARGE_UPPER;
            case LARGE_LOWER -> SHAPE_CORNER_LARGE_LOWER;
        }};
        return rotateShapeY(shape, flip ? 0 : 2);
    }}

    private static VoxelShape sideShape(Side side, boolean right) {{
        VoxelShape shape = switch (side) {{
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_SIDE_SMALL;
            case MEDIUM -> SHAPE_SIDE_MEDIUM;
            case LARGE -> SHAPE_SIDE_LARGE;
        }};
        return rotateShapeY(shape, right ? 0 : 2);
    }}

    private static int facingTurns(Direction facing) {{
        return switch (facing) {{
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        }};
    }}

    private static VoxelShape rotateShapeY(VoxelShape shape, int turns) {{
        if (shape.isEmpty()) {{
            return shape;
        }}
        VoxelShape rotated = shape;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {{
            rotated = rotateShape90Y(rotated);
        }}
        return rotated;
    }}

    private static VoxelShape rotateShape90Y(VoxelShape shape) {{
        final VoxelShape[] result = {{VoxelShapes.empty()}};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                result[0] = VoxelShapes.union(result[0],
                        VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)));
        return result[0].simplify();
    }}

{shape_methods}
}}
"""


def _tag_updates(repo_root: Path, modern_ids: list[str]) -> dict[Path, bytes]:
    data_root = repo_root / "src" / "main" / "resources" / "data"
    erydon_tags = data_root / "erydon" / "tags" / "blocks"
    mapping = {f"erydon:{source}": f"erydon:{_gothic_id(source)}" for source in modern_ids}
    updates: dict[Path, bytes] = {}

    modern_tag = erydon_tags / "arch_modern.json"
    gothic_tag = erydon_tags / "arch_gothic.json"
    modern_document = _load_json(modern_tag)
    updates[gothic_tag] = _json_bytes(
        {**modern_document, "values": [mapping[value] for value in modern_document["values"]]}
    )

    excluded = {modern_tag.resolve(), (erydon_tags / "modern.json").resolve()}
    for path in sorted(data_root.rglob("*.json")):
        if path.resolve() in excluded:
            continue
        document = _load_json(path)
        if not isinstance(document, dict) or not isinstance(document.get("values"), list):
            continue
        existing = {value for value in document["values"] if isinstance(value, str)}
        expanded = []
        changed = False
        for value in document["values"]:
            expanded.append(value)
            gothic_value = mapping.get(value)
            if gothic_value is not None and gothic_value not in existing:
                expanded.append(gothic_value)
                existing.add(gothic_value)
                changed = True
        if changed:
            document["values"] = expanded
            updates[path] = _json_bytes(document)

    style_path = erydon_tags / "gothic.json"
    style = _load_json(style_path)
    existing = set(style["values"])
    additions = [mapping[f"erydon:{source}"] for source in modern_ids]
    if any(value not in existing for value in additions):
        style["values"] += [value for value in additions if value not in existing]
        updates[style_path] = _json_bytes(style)
    return updates


def _language_updates(repo_root: Path, modern_ids: list[str]) -> dict[Path, bytes]:
    lang_root = repo_root / "src" / "main" / "resources" / "assets" / "erydon" / "lang"
    mapping = {source: _gothic_id(source) for source in modern_ids}
    updates: dict[Path, bytes] = {}
    for filename, (source_name, target_name) in LANGUAGE_PROFILE_NAMES.items():
        path = lang_root / filename
        text = path.read_text(encoding="utf-8")
        existing_keys = set(re.findall(r'^\s*"([^"]+)"\s*:', text, flags=re.MULTILINE))
        expanded = []
        inserted_blocks = 0
        inserted_tooltips = 0
        tooltip_values = TOOLTIPS[filename]
        for line in text.splitlines():
            expanded.append(line)
            block_match = re.match(
                r'^(\s*)"block\.erydon\.([^"]+_arch_modern(?:_aged)?)"\s*:', line
            )
            if block_match:
                source_id = block_match.group(2)
                target_id = mapping.get(source_id)
                target_key = f"block.erydon.{target_id}" if target_id else None
                if target_key and target_key not in existing_keys:
                    duplicated = line.replace(source_id, target_id, 1).replace(source_name, target_name)
                    if duplicated == line:
                        raise ValueError(f"Could not translate Gothic Arch name in {filename}: {line.strip()}")
                    expanded.append(duplicated)
                    existing_keys.add(target_key)
                    inserted_blocks += 1
                continue

            if '"tooltip.erydon.family.arch_romanesque.3"' in line:
                for index, value in enumerate(tooltip_values, start=1):
                    key = f"tooltip.erydon.family.arch_gothic.{index}"
                    if key in existing_keys:
                        continue
                    indentation = re.match(r"^\s*", line).group(0)
                    expanded.append(
                        f'{indentation}"{key}": {json.dumps(value, ensure_ascii=False)},'
                    )
                    existing_keys.add(key)
                    inserted_tooltips += 1

        new_text = "\n".join(expanded) + "\n"
        if new_text != text:
            if inserted_blocks not in {0, len(modern_ids)}:
                raise ValueError(
                    f"Expected {len(modern_ids)} Gothic Arch names in {filename}, inserted {inserted_blocks}"
                )
            if inserted_tooltips not in {0, 3}:
                raise ValueError(f"Expected three Gothic Arch tooltip lines in {filename}")
            updates[path] = new_text.encode("utf-8")
    return updates


def _contains_identifier(text: str, identifier: str) -> bool:
    return re.search(rf"(?<![a-z0-9_]){re.escape(identifier)}(?![a-z0-9_])", text) is not None


def _add_ctm_matches(
    text: str, mapping: dict[str, str], *, newline: str
) -> tuple[str, int]:
    lines = text.splitlines()
    existing = {target for target in mapping.values() if _contains_identifier(text, target)}
    expanded = []
    inserted = 0
    for line in lines:
        expanded.append(line)
        for source, target in mapping.items():
            if target in existing or not _contains_identifier(line, source):
                continue
            trimmed = expanded[-1].rstrip()
            continued = trimmed.endswith("\\")
            if not continued:
                expanded[-1] = trimmed + " \\"
            indentation = re.match(r"^\s*", line).group(0)
            expanded.append(indentation + target + (" \\" if continued else ""))
            existing.add(target)
            inserted += 1
    return newline.join(expanded) + newline, inserted


def _ctm_updates(repo_root: Path, modern_ids: list[str]) -> dict[Path, bytes]:
    mapping = {f"erydon:{source}": f"erydon:{_gothic_id(source)}" for source in modern_ids}
    roots = (
        (repo_root / "src" / "main" / "resources" / "assets" / "minecraft" / "optifine" / "ctm", True),
        (repo_root / "run-dev" / "resourcepacks" / "erydon-rp-16x-lite" / "assets" / "minecraft" / "optifine" / "ctm", False),
        (repo_root / "run-dev" / "resourcepacks" / "erydon-rp-64x-pbr" / "assets" / "minecraft" / "optifine" / "ctm", False),
    )
    updates: dict[Path, bytes] = {}
    for root, require_complete in roots:
        if not root.exists():
            continue
        source_matches = set()
        for path in sorted(root.rglob("*.properties")):
            raw = path.read_bytes()
            if raw.startswith(b"\xef\xbb\xbf"):
                raise ValueError(f"CTM properties file has a UTF-8 BOM: {path}")
            text = raw.decode("utf-8")
            newline = "\r\n" if b"\r\n" in raw else "\n"
            for source in mapping:
                if _contains_identifier(text, source):
                    source_matches.add(source)
            new_text, inserted = _add_ctm_matches(text, mapping, newline=newline)
            if inserted:
                updates[path] = new_text.encode("utf-8")
        if require_complete and len(source_matches) != len(mapping):
            missing = sorted(set(mapping) - source_matches)
            raise ValueError(
                f"CTM root {root} is missing {len(missing)} Modern Arch IDs (first: {missing[:3]})"
            )
    return updates


def _merge_updates(target: dict[Path, bytes], additions: dict[Path, bytes]) -> None:
    for path, content in additions.items():
        previous = target.get(path)
        if previous is not None and previous != content:
            raise ValueError(f"Conflicting generated content for {path}")
        target[path] = content


def generate(repo_root: Path, *, check: bool) -> list[Path]:
    repo_root = repo_root.resolve()
    resources = repo_root / "src" / "main" / "resources"
    if not (repo_root / "build.gradle").is_file() or not resources.is_dir():
        raise ValueError(f"Not an ERYDON repository root: {repo_root}")

    modern_ids = _registered_modern_ids(resources)
    expected = _expected_new_files(repo_root, modern_ids)
    _merge_updates(expected, _tag_updates(repo_root, modern_ids))
    _merge_updates(expected, _language_updates(repo_root, modern_ids))
    _merge_updates(expected, _ctm_updates(repo_root, modern_ids))

    changed = []
    for path, content in sorted(expected.items(), key=lambda item: str(item[0])):
        current = path.read_bytes() if path.exists() else None
        if current == content:
            continue
        changed.append(path)
        if not check:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("wb") as handle:
                handle.write(content)
    return changed


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="ERYDON repository root (defaults to the parent of tools/)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify generated files without changing the worktree",
    )
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    changed = generate(args.repo_root, check=args.check)
    if args.check:
        if changed:
            print(f"Gothic Arch generation is stale: {len(changed)} file(s) differ")
            for path in changed[:20]:
                print(f"  {path}")
            if len(changed) > 20:
                print(f"  ... and {len(changed) - 20} more")
            return 1
        print("Gothic Arch generation is current")
        return 0
    print(f"Generated or updated {len(changed)} Gothic Arch file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
