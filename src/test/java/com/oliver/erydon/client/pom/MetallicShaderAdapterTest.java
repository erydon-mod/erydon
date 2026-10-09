package com.oliver.erydon.client.pom;

import com.oliver.erydon.client.MetallicMaterials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetallicShaderAdapterTest {
    private static final String COVER_SURFACE = """
            void GetCustomMaterials(inout vec4 color, inout vec3 normalM, inout float materialMask) {
                vec4 specularMap = texture2D(specular, texCoordM);
                highlightMult *= 0.5 + 0.5 * specularMap.g;
                emission = GetCustomEmission(specularMap, texCoordM);
                float smoothnessM = pow2(specularMap.r);
                materialMask = specularMap.g * OSIEBCA * 214.0;
            }
            void DoLighting(inout vec4 color) {
                vec3 lightHighlight = vec3(0.0);
                color.rgb *= finalDiffuse;
                color.rgb += lightHighlight;
            }
            void main() {
                float smoothnessD = 0.0, materialMask = 0.0;
                GetCustomMaterials(color, normalM, materialMask);
                gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);
            }
            """;

    @Test void stateSelectedGlossCoversBypassMetalBeforeSpecularMutationAndKeepTheirFinishTags() throws Exception {
        var finish = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", COVER_SURFACE, true);
        assertTrue(finish.changed(), finish.status());
        var result = MetallicShaderAdapter.adapt("gbuffers_terrain",
                "attribute vec4 mc_midTexCoord;\nvoid main() { gl_Position = gl_Vertex; }", finish.text(), true);
        assertTrue(result.changed(), result.status());
        String shader = result.fragment();
        assertTrue(shader.contains("bool erydonGlossCover = mat == 12059 || mat == 12061;"));
        int guard = shader.indexOf("if (!erydonGlossCover && erydonInfo.x > 0 && erydonInfo.x < 4)");
        int open = shader.indexOf('{', guard);
        int close = matchingBrace(shader, open);
        int specularMutation = shader.indexOf("specularMap.g = erydonMetalCoverage");
        assertTrue(guard >= 0 && specularMutation > open && specularMutation < close,
                "Shared metal masks must not mutate a Gloss cover's authored specular sample");
        assertTrue(close < shader.indexOf("emission = GetCustomEmission"),
                "The state guard must run before the finish adapter's emission/early-return boundary");
        assertTrue(shader.contains("if (!erydonGlossCover && erydonMetalCoverage >= 1.0 / 126.0 && erydonInfo.x > 0 && erydonInfo.x < 4)"),
                "The later conductor assignment must preserve Gloss cover masks242/245 too");
        assertTrue(shader.contains("materialMask = OSIEBCA * (mat == 12061 ? 245.0 : 242.0);"),
                "The complete adapter composition must retain the state-selected finish tag");
        HighPolishShaderPackTest.parse(shader);
    }

    @Test void glossInsetMarkersRequireThePreparedOpaquePipelineAndPreserveAuthoredEmission() throws Exception {
        String vertex = "attribute vec4 mc_midTexCoord;\nvoid main() { gl_Position = gl_Vertex; }";
        var nativeOnly = MetallicShaderAdapter.adapt("gbuffers_terrain", vertex, COVER_SURFACE, true);
        assertTrue(nativeOnly.changed(), nativeOnly.status());
        assertFalse(nativeOnly.fragment().contains("if (erydonInfo.x == 4)"),
                "Metal-only/colliding-finish fallback must never emit an unsupported Mirror tag");
        assertTrue(nativeOnly.fragment().contains("erydonInfo.x > 0 && erydonInfo.x < 4"),
                "Kind4 has zero conductor coverage even when its mirror override is suppressed");

        String prepared = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", COVER_SURFACE, true).text();
        var result = MetallicShaderAdapter.adapt("gbuffers_terrain", vertex, prepared, true);
        assertTrue(result.changed(), result.status());
        int marker = result.fragment().indexOf("if (erydonInfo.x == 4)");
        assertTrue(marker > result.fragment().indexOf("emission = GetCustomEmission(specularMap, texCoordM);"));
        String override = result.fragment().substring(marker,
                matchingBrace(result.fragment(), result.fragment().indexOf('{', marker)) + 1);
        assertTrue(override.contains("smoothnessG = 1.0;"));
        assertTrue(override.contains("smoothnessD = 1.0;"));
        assertTrue(override.contains("materialMask = 242.0 / 255.0;"));
        assertFalse(override.contains("emission ="));
        assertFalse(override.contains("erydonMetalCoverage"));
        assertFalse(override.contains("erydonMetalF0"));
        HighPolishShaderPackTest.parse(result.fragment());

        for (String invalid : new String[]{prepared.replace("emission = GetCustomEmission(specularMap, texCoordM);", "emission = 0.0;"),
                prepared + "\nemission = GetCustomEmission(specularMap, texCoordM);"}) {
            var rejected = MetallicShaderAdapter.adapt("gbuffers_terrain", vertex, invalid, true);
            assertEquals("UNSUPPORTED_SOURCE", rejected.status());
            assertSame(vertex, rejected.vertex());
            assertSame(invalid, rejected.fragment());
        }
    }

    @Test void glossyCoverStateExemptionDoesNotExcludeTheSharedMatteOrCeilingMetalSprites() {
        for (String alloy : new String[]{"bronze", "silver"}) {
            var gloss = MetallicMaterials.classify("erydon", "block/cover_" + alloy + "_gloss");
            var matte = MetallicMaterials.classify("erydon", "block/cover_" + alloy + "_matte");
            assertNotNull(gloss);
            assertNotNull(matte);
            assertEquals(MetallicMaterials.STANDALONE, gloss.kind());
            assertEquals(MetallicMaterials.STANDALONE, matte.kind());
            assertFalse(gloss.pureMetalFallback());
            assertTrue(matte.pureMetalFallback());
            assertArrayEquals(new byte[]{(byte) 255},
                    MetallicMaterials.coverage(new int[]{-1}, new int[]{0xff00ffff}, gloss.pureMetalFallback()));
            assertEquals(166, MetallicMaterials.roughness(new byte[]{(byte) 255}, null, matte.pureMetalFallback()));
        }
    }

    @Test void disabledUnknownAndMissingSourcesCannotPartiallyEnableMetalRendering() {
        String vertex = "unchanged vertex";
        String fragment = "unchanged fragment";
        for (String program : MetallicShaderAdapter.PROGRAMS) {
            var disabled = MetallicShaderAdapter.adapt(program, vertex, fragment, false);
            assertFalse(disabled.changed());
            assertSame(vertex, disabled.vertex());
            assertSame(fragment, disabled.fragment());
            var unsupported = MetallicShaderAdapter.adapt(program, vertex, fragment, true);
            assertFalse(unsupported.changed(), program);
            assertEquals("UNSUPPORTED_SOURCE", unsupported.status(), program);
            assertSame(vertex, unsupported.vertex());
            assertSame(fragment, unsupported.fragment());
            var missing = MetallicShaderAdapter.adapt(program, vertex, null, true);
            assertFalse(missing.changed());
            assertSame(vertex, missing.vertex());
            assertNull(missing.fragment());
        }
        var other = MetallicShaderAdapter.adapt("gbuffers_entities", vertex, fragment, true);
        assertFalse(other.changed());
        assertEquals("OTHER_PROGRAM", other.status());
        assertSame(vertex, other.vertex());
        assertSame(fragment, other.fragment());
    }

    private static int matchingBrace(String source, int open) {
        assertTrue(open >= 0, "Missing guarded block");
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return i;
        }
        return fail("Unclosed guarded block");
    }
}
