from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
LOADER_PATH = (
    REPO_ROOT
    / "src"
    / "main"
    / "java"
    / "com"
    / "oliver"
    / "erydon"
    / "client"
    / "model"
    / "ErydonRawModelLoadingPlugin.java"
)

EXPECTED_AUTHORING_MODELS = {
    "column_gothic/plinth": "authoring_models/block/column/gothic/column_gothic_plinth.json",
    "column_gothic/base": "authoring_models/block/column/gothic/column_gothic_base.json",
    "column_gothic/pillar": "authoring_models/block/column/gothic/column_gothic_pillar.json",
    "column_gothic/capital": "authoring_models/block/column/gothic/column_gothic_capital.json",
    "alcove_georgian/back": "authoring_models/block/alcove/alcove_georgian_single_back.json",
    "alcove_georgian/sides": "authoring_models/block/alcove/alcove_georgian_single_sides.json",
    "alcove_georgian/base": "authoring_models/block/alcove/alcove_georgian_single_base.json",
    "alcove_georgian/top": "authoring_models/block/alcove/alcove_georgian_single_top.json",
    "alcove_georgian/icon": "authoring_models/block/alcove/alcove_georgian_icon.json",
    "alcove_georgian/double_side_left": "authoring_models/block/alcove/alcove_georgian_double_side_left.json",
    "alcove_georgian/double_side_right": "authoring_models/block/alcove/alcove_georgian_double_side_right.json",
    "alcove_georgian/double_top_left": "authoring_models/block/alcove/alcove_georgian_double_top_left.json",
    "alcove_georgian/double_top_right": "authoring_models/block/alcove/alcove_georgian_double_top_right.json",
    "alcove_georgian/triple_side_left": "authoring_models/block/alcove/alcove_georgian_triple_side_left.json",
    "alcove_georgian/triple_side_center": "authoring_models/block/alcove/alcove_georgian_triple_side_center.json",
    "alcove_georgian/triple_side_right": "authoring_models/block/alcove/alcove_georgian_triple_side_right.json",
    "alcove_georgian/triple_top_left": "authoring_models/block/alcove/alcove_georgian_triple_top_left.json",
    "alcove_georgian/triple_top_center": "authoring_models/block/alcove/alcove_georgian_triple_top_center.json",
    "alcove_georgian/triple_top_right": "authoring_models/block/alcove/alcove_georgian_triple_top_right.json",
    "alcove_gothic/back": "authoring_models/block/alcove/alcove_gothic_single_back.json",
    "alcove_gothic/sides": "authoring_models/block/alcove/alcove_gothic_single_sides.json",
    "alcove_gothic/base": "authoring_models/block/alcove/alcove_gothic_single_base.json",
    "alcove_gothic/top": "authoring_models/block/alcove/alcove_gothic_single_top.json",
    "alcove_gothic/icon": "authoring_models/block/alcove/alcove_gothic_icon.json",
    "alcove_gothic/double_side_left": "authoring_models/block/alcove/alcove_gothic_double_side_left.json",
    "alcove_gothic/double_side_right": "authoring_models/block/alcove/alcove_gothic_double_side_right.json",
    "alcove_gothic/double_top_left": "authoring_models/block/alcove/alcove_gothic_double_top_left.json",
    "alcove_gothic/double_top_right": "authoring_models/block/alcove/alcove_gothic_double_top_right.json",
    "alcove_gothic/triple_side_left": "authoring_models/block/alcove/alcove_gothic_triple_side_left.json",
    "alcove_gothic/triple_side_center": "authoring_models/block/alcove/alcove_gothic_triple_side_center.json",
    "alcove_gothic/triple_side_right": "authoring_models/block/alcove/alcove_gothic_triple_side_right.json",
    "alcove_gothic/triple_top_left": "authoring_models/block/alcove/alcove_gothic_triple_top_left.json",
    "alcove_gothic/triple_top_center": "authoring_models/block/alcove/alcove_gothic_triple_top_center.json",
    "alcove_gothic/triple_top_right": "authoring_models/block/alcove/alcove_gothic_triple_top_right.json",
    "arch_gothic/corner_small": "authoring_models/block/arch/gothic/arch_gothic_corner_small.json",
    "arch_gothic/corner_medium": "authoring_models/block/arch/gothic/arch_gothic_corner_medium.json",
    "arch_gothic/corner_large_upper": "authoring_models/block/arch/gothic/arch_gothic_corner_large_upper.json",
    "arch_gothic/corner_large_lower": "authoring_models/block/arch/gothic/arch_gothic_corner_large_lower.json",
    "arch_gothic/side_small": "authoring_models/block/arch/gothic/arch_gothic_side_small.json",
    "arch_gothic/side_medium": "authoring_models/block/arch/gothic/arch_gothic_side_medium.json",
    "arch_gothic/side_large": "authoring_models/block/arch/gothic/arch_gothic_side_large.json",
    "arch_gothic/top_large": "authoring_models/block/arch/gothic/arch_gothic_top_large.json",
    "arch_gothic/icon": "authoring_models/block/arch/gothic/arch_gothic_icon.json",
}

# This is the authoring-loader geometry contract mirrored by the model audit tools.
EXPECTED_TRANSFORM_ORDER = (
    "element rotation",
    "group rotations from innermost to outermost",
    "X then Y then Z within each rotation",
)


def _balanced_region(source: str, open_index: int, opening: str, closing: str) -> str:
    """Return a balanced Java region while ignoring delimiters in strings/comments."""
    if source[open_index] != opening:
        raise AssertionError(f"Expected {opening!r} at source offset {open_index}")

    depth = 0
    index = open_index
    state = "code"
    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""

        if state == "line_comment":
            if char == "\n":
                state = "code"
        elif state == "block_comment":
            if char == "*" and following == "/":
                state = "code"
                index += 1
        elif state in {"string", "character"}:
            if char == "\\":
                index += 1
            elif (state == "string" and char == '"') or (
                state == "character" and char == "'"
            ):
                state = "code"
        elif char == "/" and following == "/":
            state = "line_comment"
            index += 1
        elif char == "/" and following == "*":
            state = "block_comment"
            index += 1
        elif char == '"':
            state = "string"
        elif char == "'":
            state = "character"
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return source[open_index : index + 1]

        index += 1

    raise AssertionError(f"Unbalanced Java source after offset {open_index}")


def _body_after(source: str, signature_pattern: str) -> str:
    match = re.search(signature_pattern, source, flags=re.DOTALL)
    if match is None:
        raise AssertionError(f"Java declaration not found: {signature_pattern}")
    open_index = source.find("{", match.end())
    if open_index < 0:
        raise AssertionError(f"Java body not found after: {signature_pattern}")
    return _balanced_region(source, open_index, "{", "}")[1:-1]


def _compact(source: str) -> str:
    return re.sub(r"\s+", " ", source).strip()


def _assert_in_order(test: unittest.TestCase, source: str, snippets: tuple[str, ...]) -> None:
    cursor = -1
    for snippet in snippets:
        position = source.find(snippet, cursor + 1)
        test.assertGreater(
            position,
            cursor,
            f"Expected loader contract step after previous step: {snippet}",
        )
        cursor = position


class RawLoaderSourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = LOADER_PATH.read_text(encoding="utf-8")

    def test_authoring_model_map_contains_exactly_the_43_registered_paths(self) -> None:
        declaration = re.search(
            r"\bAUTHORING_MODELS\s*=\s*Map\.ofEntries\s*\(", self.source
        )
        self.assertIsNotNone(declaration, "AUTHORING_MODELS must remain a Map.ofEntries declaration")
        open_index = self.source.find("(", declaration.start())
        initializer = _balanced_region(self.source, open_index, "(", ")")
        entries = re.findall(
            r'Map\.entry\s*\(\s*"([^"]+)"\s*,\s*new\s+Identifier\s*'
            r'\(\s*Erydon\.MOD_ID\s*,\s*"([^"]+)"\s*\)\s*\)',
            initializer,
            flags=re.DOTALL,
        )

        self.assertEqual(43, initializer.count("Map.entry("))
        self.assertEqual(43, len(entries), "Every authoring entry must match the locked path form")
        self.assertEqual(EXPECTED_AUTHORING_MODELS, dict(entries))

    def test_uv_offset_is_strictly_two_finite_numbers_and_is_retained(self) -> None:
        raw_face = _body_after(self.source, r"\bclass\s+RawFace\b")
        parse = _compact(
            _body_after(
                raw_face,
                r"\bRawFace\s+parse\s*\(\s*JsonObject\s+object\s*\)",
            )
        )

        self.assertRegex(parse, r"float\[\]\s+uvOffset\s*=\s*new float\[\]\s*\{\s*0\.0F\s*,\s*0\.0F\s*\}")
        self.assertRegex(
            parse,
            r'if\s*\(\s*object\.has\s*\(\s*"erydon_uv_offset"\s*\)\s*\)',
        )
        self.assertRegex(
            parse,
            r"if\s*\(\s*!offsetElement\.isJsonArray\(\)\s*\|\|\s*"
            r"offsetElement\.getAsJsonArray\(\)\.size\(\)\s*!=\s*2\s*\)\s*\{\s*throw\s+new\s+IllegalArgumentException",
        )
        self.assertRegex(parse, r"for\s*\(\s*int\s+index\s*=\s*0\s*;\s*index\s*<\s*2\s*;\s*index\+\+\s*\)")
        self.assertRegex(
            parse,
            r"if\s*\(\s*!value\.isJsonPrimitive\(\)\s*\|\|\s*"
            r"!value\.getAsJsonPrimitive\(\)\.isNumber\(\)\s*\)\s*\{\s*throw\s+new\s+IllegalArgumentException",
        )
        _assert_in_order(
            self,
            parse,
            (
                "float parsed = value.getAsFloat();",
                "if (!Float.isFinite(parsed))",
                "throw new IllegalArgumentException",
                "uvOffset[index] = parsed;",
                "return new RawFace(cullFace, textureKey, uv, uvOffset, cullBoundaryOverride);",
            ),
        )

        normalized_face = _compact(raw_face)
        self.assertIn("private final float[] uvOffset;", normalized_face)
        self.assertIn("this.uvOffset = uvOffset.clone();", normalized_face)
    def test_uv_offset_is_applied_after_uv_construction_to_all_four_pairs(self) -> None:
        baked_model = _body_after(self.source, r"\bclass\s+RawBakedModel\b")
        raw_surfaces = _compact(
            _body_after(
                self.source,
                r"\bList<RawSurface>\s+rawSurfaces\s*\(\s*RawModelData\s+data\s*\)",
            )
        )
        _assert_in_order(
            self,
            raw_surfaces,
            (
                "Vector3f[] vertices = element.transformedVertices(entry.getKey());",
                "Direction nominalFace = RawBakedModel.closestDirection(vertices);",
                "float[] uv = face.uv == null ? RawElement.defaultUv(vertices, nominalFace) : RawBakedModel.rectUv(face.uv);",
                "RawBakedModel.applyUvOffset(uv, face.uvOffset);",
                "surfaces.add(new RawSurface(vertices, uv, nominalFace, cullFace, face));",
            ),
        )

        bake_quad = _compact(
            _body_after(
                baked_model,
                r"\bBakedQuad\s+bakeQuad\s*\(\s*RawSurface\s+surface\s*,\s*"
                r"Sprite\s+sprite\s*\)",
            )
        )
        _assert_in_order(
            self,
            bake_quad,
            (
                "for (int vertex = 0; vertex < 4; vertex++)",
                "surface.uv[vertex * 2]",
                "surface.uv[vertex * 2 + 1]",
            ),
        )

        apply_offset = _compact(
            _body_after(
                baked_model,
                r"\bvoid\s+applyUvOffset\s*\(\s*float\[\]\s+uv\s*,\s*float\[\]\s+offset\s*\)",
            )
        )
        self.assertRegex(
            apply_offset,
            r"^for\s*\(\s*int\s+vertex\s*=\s*0\s*;\s*vertex\s*<\s*4\s*;\s*vertex\+\+\s*\)\s*\{\s*"
            r"uv\s*\[\s*vertex\s*\*\s*2\s*\]\s*\+=\s*offset\s*\[\s*0\s*\]\s*;\s*"
            r"uv\s*\[\s*vertex\s*\*\s*2\s*\+\s*1\s*\]\s*\+=\s*offset\s*\[\s*1\s*\]\s*;\s*\}\s*$",
        )

    def test_cull_boundary_override_is_boolean_and_only_used_with_cullface(self) -> None:
        raw_face = _body_after(self.source, r"\bclass\s+RawFace\b")
        parse = _compact(
            _body_after(
                raw_face,
                r"\bRawFace\s+parse\s*\(\s*JsonObject\s+object\s*\)",
            )
        )
        self.assertIn(
            'if (object.has("erydon_cull_boundary_override"))',
            parse,
        )
        self.assertIn(
            "!overrideElement.getAsJsonPrimitive().isBoolean()",
            parse,
        )
        self.assertIn(
            "cullBoundaryOverride = overrideElement.getAsBoolean();",
            parse,
        )

        raw_surfaces = _compact(
            _body_after(
                self.source,
                r"\bList<RawSurface>\s+rawSurfaces\s*\(\s*RawModelData\s+data\s*\)",
            )
        )
        self.assertRegex(
            raw_surfaces,
            r"Direction\s+cullFace\s*=\s*face\.cullFace\s*!=\s*null\s*&&\s*\(\s*"
            r"face\.cullBoundaryOverride\s*\|\|\s*"
            r"RawBakedModel\.isOnCullBoundary\(face\.cullFace,\s*vertices\)\s*\)\s*"
            r"\?\s*face\.cullFace\s*:\s*null\s*;",
        )

    def test_transform_order_matches_the_raw_authoring_contract(self) -> None:
        """Lock element -> inner groups -> outer groups, with X -> Y -> Z rotations."""
        self.assertEqual(
            (
                "element rotation",
                "group rotations from innermost to outermost",
                "X then Y then Z within each rotation",
            ),
            EXPECTED_TRANSFORM_ORDER,
        )

        raw_element = _body_after(self.source, r"\bclass\s+RawElement\b")
        element_parse = _compact(
            _body_after(
                raw_element,
                r"\bRawElement\s+parse\s*\(\s*JsonObject\s+object\s*,\s*"
                r"List<RawRotation>\s+groupRotations\s*\)",
            )
        )
        _assert_in_order(
            self,
            element_parse,
            ("rotations.add(elementRotation);", "rotations.addAll(groupRotations);"),
        )

        raw_model_data = _body_after(self.source, r"\bclass\s+RawModelData\b")
        group_collection = _compact(
            _body_after(
                raw_model_data,
                r"\bvoid\s+collectGroupRotations\s*\(\s*JsonObject\s+group\s*,\s*"
                r"List<RawRotation>\s+inherited\s*,\s*"
                r"Map<Integer,\s*List<RawRotation>>\s+rotationsByElement\s*\)",
            )
        )
        _assert_in_order(
            self,
            group_collection,
            (
                "rotations = new ArrayList<>(inherited);",
                "rotations.add(groupRotation);",
                "List<RawRotation> elementRotations = new ArrayList<>(rotations);",
                "Collections.reverse(elementRotations);",
                "addAll(elementRotations);",
            ),
        )

        transformed_vertices = _compact(
            _body_after(raw_element, r"\bVector3f\[\]\s+transformedVertices\s*\(\s*Direction\s+face\s*\)")
        )
        self.assertRegex(
            transformed_vertices,
            r"for\s*\(\s*RawRotation\s+rotation\s*:\s*rotations\s*\)\s*\{\s*"
            r"vertex\s*=\s*rotation\.transform\(vertex\)\s*;\s*\}",
        )

        raw_rotation = _body_after(self.source, r"\bclass\s+RawRotation\b")
        transform = _compact(
            _body_after(raw_rotation, r"\bVector3f\s+transform\s*\(\s*Vector3f\s+vertex\s*\)")
        )
        _assert_in_order(
            self,
            transform,
            (
                "vertex.x - origin[0]",
                "rotateX(transformed);",
                "rotateY(transformed);",
                "rotateZ(transformed);",
                "return transformed.add(origin[0], origin[1], origin[2]);",
            ),
        )


if __name__ == "__main__":
    unittest.main()
