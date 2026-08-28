package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplementaryUnboundDev5SourceTransformerTest {
    private static final String HELPER = """
            uniform sampler2D erydonCtmPomLookup;
            int erydonCtmPomFindRecord(vec2 uv, vec2 size) { return 0; }
            void erydonCtmPomApplyExactBounds(vec2 uv, vec2 size, int record,
                    inout vec4 bounds, inout vec2 signValue, inout vec2 radius, inout vec2 midpoint) {}
            vec2 erydonCtmPomAtlasUv(vec2 value) { return value; }
            """;

    @Test
    void propertiesModesAreExactAndFailClosed() {
        assertEquals("texture.erydonCtmPomLookup=erydon:ctm_pom_lookup",
                ComplementaryUnboundDev5SourceTransformer.TEXTURE_DIRECTIVE);
        String properties = "screen=TEST\n";
        String exactHash = ComplementaryUnboundDev5SourceTransformer.sha256(properties);

        var auto = ComplementaryUnboundDev5SourceTransformer.adaptProperties(
                properties, ComplementaryUnboundDev5SourceTransformer.Mode.AUTO, exactHash);
        assertTrue(auto.changed());
        assertTrue(auto.eligible());

        var off = ComplementaryUnboundDev5SourceTransformer.adaptProperties(
                properties, ComplementaryUnboundDev5SourceTransformer.Mode.OFF, exactHash);
        assertFalse(off.changed());
        assertFalse(off.eligible());
        assertSame(properties, off.text());

        var unknown = ComplementaryUnboundDev5SourceTransformer.adaptProperties(
                properties, ComplementaryUnboundDev5SourceTransformer.Mode.AUTO, "0".repeat(64));
        assertEquals("UNSUPPORTED_SHADER_PROPERTIES", unknown.status());
        assertSame(properties, unknown.text());

        var force = ComplementaryUnboundDev5SourceTransformer.adaptProperties(
                properties, ComplementaryUnboundDev5SourceTransformer.Mode.FORCE, "0".repeat(64));
        assertTrue(force.changed());
        assertTrue(force.eligible());
    }

    @Test
    void transformsVertexAndFragmentStagesAtomically() {
        var result = transform(VERTEX_SOURCE, FRAGMENT_SOURCE);

        assertEquals("TRANSFORMED", result.status());
        assertTrue(result.changed());
        assertFalse(result.vertexText().contains("#define"));
        assertFalse(result.fragmentText().contains("#define"));
        assertTrue(result.vertexText().contains("flat out int erydonPomRecord;"));
        assertTrue(result.vertexText().contains("erydonPomRecord = -1;"));
        assertTrue(result.vertexText().contains("if (mat == 32120)"));
        assertTrue(result.vertexText().contains("    erydonPomRecord = erydonCtmPomFindRecord("));
        assertTrue(result.vertexText().contains("    if (erydonPomRecord >= 0)"));
        assertTrue(result.vertexText().contains("erydonCtmPomApplyExactBounds("));
        assertTrue(result.fragmentText().contains("flat in int erydonPomRecord;"));
        assertTrue(result.fragmentText().contains("bool skipPom = mat == 32120 && erydonPomRecord < 0;"));
        assertTrue(result.fragmentText().contains("coord = erydonCtmPomAtlasUv(coord);"));
        assertTrue(result.fragmentText().contains("newCoord = erydonCtmPomAtlasUv(localCoord);"));
        assertTrue(result.fragmentText().contains("vec2 parallaxCoord = erydonCtmPomAtlasUv("));
        assertTrue(result.fragmentText().contains("vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);"));
    }

    @Test
    void realStageHelpersReachIrisWithoutLatePreprocessorDirectives() {
        var result = ComplementaryUnboundDev5SourceTransformer.transformProgram(
                "gbuffers_terrain",
                VERTEX_SOURCE,
                FRAGMENT_SOURCE,
                ErydonCuPomShaderBridge.vertexSource(),
                ErydonCuPomShaderBridge.fragmentSource(),
                true);

        assertEquals("TRANSFORMED", result.status());
        assertTrue(result.changed());
        assertFalse(result.vertexText().lines()
                .anyMatch(line -> line.stripLeading().startsWith("#")));
        assertFalse(result.fragmentText().lines()
                .anyMatch(line -> line.stripLeading().startsWith("#")));
        assertTrue(result.vertexText().contains("texture2DLod("));
        assertFalse(result.vertexText().contains("int erydonPomFragmentRecord = -2;"));
        assertTrue(result.fragmentText().contains("int erydonPomFragmentRecord = -2;"));
        assertFalse(result.fragmentText().contains("texture2DLod("));
    }

    @Test
    void everyChangedOrDuplicateAnchorReturnsBothOriginalStagesByteForByte() {
        List<SourcePair> mutated = List.of(
                new SourcePair(VERTEX_SOURCE.replace("out vec4 vTexCoordAM;", "out vec4 changedBounds;"),
                        FRAGMENT_SOURCE),
                new SourcePair(VERTEX_SOURCE.replace("mat = int(mc_Entity.x + 0.5);", "mat = 0;"),
                        FRAGMENT_SOURCE),
                new SourcePair(VERTEX_SOURCE.replace(
                        "vTexCoordAM.zw  = abs(texMinMidCoord) * 2;", "vTexCoordAM.zw = vec2(1.0);"),
                        FRAGMENT_SOURCE),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace("in vec4 vTexCoordAM;", "in vec4 changedBounds;")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace("vec2 vTexCoord = signMidCoordPos * 0.5 + 0.5;",
                                "vec2 vTexCoord = vec2(0.0);")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace("bool skipPom = false;", "bool skipPom = true;")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace(
                                "coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;",
                                "coord = fract(coord);")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace(
                                "localCoord = fract(vTexCoord.st + pI * interval);",
                                "localCoord = fract(vTexCoord.st);")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace(
                                "vec2 parallaxCoord = fract(coord + parallaxdir.xy * stepLC) * vTexCoordAM.pq + vTexCoordAM.st;",
                                "vec2 parallaxCoord = fract(coord);")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace(
                                "vec2 atlasCoord = fract(texCoord) * vTexCoordAM.pq + vTexCoordAM.st;",
                                "vec2 atlasCoord = fract(texCoord);")),
                new SourcePair(VERTEX_SOURCE,
                        FRAGMENT_SOURCE.replace("bool skipPom = false;",
                                "bool skipPom = false;\n    bool skipPom = false;"))
        );

        for (SourcePair source : mutated) {
            var result = transform(source.vertex(), source.fragment());
            assertEquals("ANCHOR_MISMATCH_NO_CHANGE", result.status());
            assertFalse(result.changed());
            assertSame(source.vertex(), result.vertexText());
            assertSame(source.fragment(), result.fragmentText());
        }
    }

    @Test
    void incompletePriorTransformAndPomCompiledOutFailClosed() {
        String vertexOnly = HELPER + "\n" + VERTEX_SOURCE;
        var incomplete = transform(vertexOnly, FRAGMENT_SOURCE);
        assertEquals("INCOMPLETE_TRANSFORM_NO_CHANGE", incomplete.status());
        assertSame(vertexOnly, incomplete.vertexText());
        assertSame(FRAGMENT_SOURCE, incomplete.fragmentText());

        String noPomVertex = "void main() {}\n";
        String noPomFragment = "void main() {}\n";
        var noPom = transform(noPomVertex, noPomFragment);
        assertEquals("POM_NOT_COMPILED", noPom.status());
        assertSame(noPomVertex, noPom.vertexText());
        assertSame(noPomFragment, noPom.fragmentText());
    }

    @Test
    void skipsOtherProgramsAndIneligibleSources() {
        var other = ComplementaryUnboundDev5SourceTransformer.transformProgram(
                "gbuffers_entities", VERTEX_SOURCE, FRAGMENT_SOURCE, HELPER, HELPER, true);
        assertEquals("OTHER_PROGRAM", other.status());
        assertSame(VERTEX_SOURCE, other.vertexText());
        assertSame(FRAGMENT_SOURCE, other.fragmentText());

        var ineligible = ComplementaryUnboundDev5SourceTransformer.transformProgram(
                "gbuffers_terrain", VERTEX_SOURCE, FRAGMENT_SOURCE, HELPER, HELPER, false);
        assertEquals("NOT_ELIGIBLE", ineligible.status());
        assertSame(VERTEX_SOURCE, ineligible.vertexText());
        assertSame(FRAGMENT_SOURCE, ineligible.fragmentText());
    }

    private static ComplementaryUnboundDev5SourceTransformer.ProgramResult transform(
            String vertex,
            String fragment
    ) {
        return ComplementaryUnboundDev5SourceTransformer.transformProgram(
                "gbuffers_terrain", vertex, fragment, HELPER, HELPER, true);
    }

    private static final String VERTEX_SOURCE = """
            flat out int mat;
            out vec2 signMidCoordPos;
            flat out vec2 absMidCoordPos;
            flat out vec2 midCoord;
            out vec4 vTexCoordAM;
            void main() {
                vec2 texMinMidCoord = texCoord - midCoord;
                signMidCoordPos = sign(texMinMidCoord);
                absMidCoordPos = abs(texMinMidCoord);
                mat = int(mc_Entity.x + 0.5);
                vTexCoordAM.zw  = abs(texMinMidCoord) * 2;
                vTexCoordAM.xy  = min(texCoord, midCoord - texMinMidCoord);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            flat in int mat;
            in vec2 texCoord;
            in vec2 signMidCoordPos;
            in vec4 vTexCoordAM;
            vec2 vTexCoord = signMidCoordPos * 0.5 + 0.5;
            vec4 ReadNormal(vec2 coord) {
                coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;
                return texture2D(normals, coord);
            }
            vec2 trace(vec2 pI, vec2 interval, out vec2 newCoord) {
                vec2 localCoord;
                localCoord = fract(vTexCoord.st + pI * interval);
                newCoord = localCoord * vTexCoordAM.pq + vTexCoordAM.st;
                return localCoord;
            }
            void shadow(vec2 coord, vec3 parallaxdir, float stepLC) {
                vec2 parallaxCoord = fract(coord + parallaxdir.xy * stepLC) * vTexCoordAM.pq + vTexCoordAM.st;
            }
            void slope(vec2 texCoord) {
                vec2 atlasCoord = fract(texCoord) * vTexCoordAM.pq + vTexCoordAM.st;
            }
            void material() {
                bool skipPom = false;
            }
            """;

    private record SourcePair(String vertex, String fragment) {
    }
}
