package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplementaryUnboundDev5SourceTransformerTest {
    private static final String HELPER =
            ComplementaryUnboundDev5SourceTransformer.HELPER_SENTINEL + "\n"
                    + "vec2 erydonCtmPomAtlasUv(vec2 value) { return value; }";

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
        assertTrue(auto.text().contains(ComplementaryUnboundDev5SourceTransformer.TEXTURE_DIRECTIVE));

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
    void transformsAllFourPomSamplingDomainsAtomically() {
        var result = transform(SOURCE);

        assertEquals("TRANSFORMED", result.status());
        assertTrue(result.changed());
        assertEquals(1, result.counts().get("post_helper"));
        assertTrue(result.text().contains("coord = erydonCtmPomAtlasUv(coord);"));
        assertTrue(result.text().contains("newCoord = erydonCtmPomAtlasUv(localCoord);"));
        assertTrue(result.text().contains("vec2 parallaxCoord = erydonCtmPomAtlasUv("));
        assertTrue(result.text().contains("vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);"));
    }

    @Test
    void everyChangedOrDuplicateAnchorReturnsTheOriginalByteForByte() {
        List<String> mutated = List.of(
                SOURCE.replace("coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;", "coord = fract(coord);"),
                SOURCE.replace("localCoord = fract(vTexCoord.st + pI * interval);", "localCoord = fract(vTexCoord.st);"),
                SOURCE.replace("vec2 parallaxCoord = fract(coord + parallaxdir.xy * stepLC) * vTexCoordAM.pq + vTexCoordAM.st;",
                        "vec2 parallaxCoord = fract(coord);"),
                SOURCE.replace("vec2 atlasCoord = fract(texCoord) * vTexCoordAM.pq + vTexCoordAM.st;",
                        "vec2 atlasCoord = fract(texCoord);"),
                SOURCE.replace("coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;",
                        "coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;\n"
                                + "    coord = fract(coord) * vTexCoordAM.pq + vTexCoordAM.st;")
        );
        for (String source : mutated) {
            var result = transform(source);
            assertEquals("ANCHOR_MISMATCH_NO_CHANGE", result.status());
            assertFalse(result.changed());
            assertSame(source, result.text());
        }
    }

    @Test
    void skipsOtherProgramsPomCompiledOutAndAlreadyTransformedSource() {
        var other = ComplementaryUnboundDev5SourceTransformer.transformFragment(
                "gbuffers_entities", SOURCE, HELPER, true);
        assertEquals("OTHER_PROGRAM", other.status());
        assertSame(SOURCE, other.text());

        String noPom = "void main() {}\n";
        var compiledOut = transform(noPom);
        assertEquals("POM_NOT_COMPILED", compiledOut.status());
        assertSame(noPom, compiledOut.text());

        String already = HELPER + "\n" + SOURCE;
        var transformed = transform(already);
        assertEquals("ALREADY_TRANSFORMED", transformed.status());
        assertSame(already, transformed.text());
    }

    private static ComplementaryUnboundDev5SourceTransformer.Result transform(String source) {
        return ComplementaryUnboundDev5SourceTransformer.transformFragment(
                "gbuffers_terrain", source, HELPER, true);
    }

    private static final String SOURCE = """
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
            """;
}
