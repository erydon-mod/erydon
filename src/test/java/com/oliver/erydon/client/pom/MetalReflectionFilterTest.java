package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MetalReflectionFilterTest {
    private static final String FORMATS = """
            /*
            const int colortex1Format = RGB8_SNORM;
            const int colortex4Format = RGBA8_SNORM;
            const int colortex6Format = RGB8;
            const int colortex7Format = RGBA16F;
            */
            const bool colortex1Clear = false;
            """;
    private static final String HISTORY = """
            void main() {
                vec4 reflectOutput = vec4(0.0);
                if (z0 < 1.0) {
                    vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                    int materialMaskInt = int(texture6.g * 255.1);
                    vec3 prevNormalM = mat3(gbufferModelView) * texture2D(colortex1, virtualPrevRefPos.xy).rgb;
                    float prevValid = exp(-12.0 * length(normalM - prevNormalM));
                    reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / prevValid));
                }
                gl_FragData[1] = vec4(texture4.rgb, 1.0);
            }
            """;
    private static final String FILTER = """
            vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0) {
                vec4 sum = vec4(0.0);
                float weightSum = 0.0;
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                float smoothnessD = texture6.r;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        vec4 texture1Sample = texture2D(colortex1, sampleCoord);
                        if (length(texture4.rgb - texture1Sample.rgb) > 0.1 + 0.075 * dither) continue;
                        sum += sampleCol * weight;
                        weightSum += weight;
                    }
                }
                return sum / weightSum;
            }
            """;

    @Test void formatsAddOnlyExistingBufferAlphaChannelsAndKeepOtherDirectives() {
        for (String program : new String[]{"shadow", "composite6", "final"}) {
            var result = MetalReflectionFilter.adapt(program, FORMATS);
            assertTrue(result.changed());
            assertTrue(result.text().contains("colortex1Format = RGBA8_SNORM;"));
            assertTrue(result.text().contains("colortex6Format = RGBA8;"));
            assertTrue(result.text().contains("colortex4Format = RGBA8_SNORM;"));
            assertTrue(result.text().contains("colortex7Format = RGBA16F;"));
            assertTrue(result.text().contains("colortex1Clear = false;"));
            assertFalse(result.text().contains("sampler"));
        }
    }

    @Test void historyUsesDistinctMetalTagsAndCannotConfuseInterpolatedSilverWithBronze() {
        String result = MetalReflectionFilter.adapt("composite", HISTORY).text();
        assertTrue(result.contains("materialMaskInt == 243 ? 0.5 : (materialMaskInt == 244 ? 1.0 : 0.0)"));
        assertTrue(result.indexOf("float erydonReflectionMaterial") < result.indexOf("if (z0"));
        assertTrue(result.contains("gl_FragData[1] = vec4(texture4.rgb, erydonReflectionMaterial)"));
        assertTrue(result.contains("textureSize(colortex1, 0)"), "Half-resolution buffers need their actual dimensions");
        assertTrue(result.contains("texelFetch(colortex1, erydonHistoryPixel, 0).a"));
        assertTrue(result.contains("abs(erydonPreviousNormal.a - erydonPreviousMaterial) < 0.02"));
        assertTrue(result.contains("if (erydonPreviousMaterialMatches)"));
        assertTrue(result.contains("max(prevValid, 0.000001)"));
        assertFalse(result.contains("GetReflection("));
        assertFalse(result.contains("#"));
    }

    @Test void spatialFilterRejectsBothDirectionsAndFallsBackWhenEverySampleFails() {
        String result = MetalReflectionFilter.adapt("composite1", FILTER).text();
        assertTrue(result.contains("erydonFilterMaterial > 0.01 || texture1Sample.a > 0.01"));
        assertTrue(result.contains("abs(erydonSampleMaterial - erydonFilterMaterial) >= 0.02"));
        assertTrue(result.contains("abs(texture1Sample.a - erydonSampleMaterial) >= 0.02"));
        assertTrue(result.contains("length(texture4.rgb - texture1Sample.rgb) > 0.1 + 0.075 * dither"));
        assertTrue(result.contains("return weightSum > 0.000001 ? sum / weightSum : centerCol;"));
        assertTrue(result.contains("sum += sampleCol * weight;"));
        assertFalse(result.contains("#"));
    }

    @Test void rgbaMaterialReadAndExtendedFilterSignatureRemainCompatible() {
        String input = FILTER.replace("vec3 texture6", "vec4 texture6").replace("texelCoord, 0).rgb", "texelCoord, 0)")
                .replace("float z0)", "float z0, out float preservation)");
        assertTrue(MetalReflectionFilter.adapt("composite1", input).changed());
    }

    @Test void lowSamplerProfileDoesNotIntroduceAReadOrSampler() {
        String input = FILTER.replace("    vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;\n", "")
                .replace("    float smoothnessD = texture6.r;\n", "");
        var result = MetalReflectionFilter.adapt("composite1", input);
        assertEquals("TRANSFORMED_LOW_SAMPLER", result.status());
        assertFalse(result.text().contains("texelFetch"));
        assertFalse(result.text().contains("colortex6"));
        assertTrue(result.text().contains(": centerCol;"));
    }

    @Test void unknownMissingAndDuplicateAnchorsFailWithoutChangingSource() {
        for (String program : new String[]{"shadow", "composite", "composite1"}) {
            String input = program.equals("shadow") ? FORMATS : program.equals("composite") ? HISTORY : FILTER;
            for (String invalid : new String[]{"unknown shader", input + input}) {
                var result = MetalReflectionFilter.adapt(program, invalid);
                assertEquals("UNSUPPORTED_SOURCE", result.status());
                assertSame(invalid, result.text());
            }
            String once = MetalReflectionFilter.adapt(program, input).text();
            assertEquals("ALREADY_TRANSFORMED", MetalReflectionFilter.adapt(program, once).status());
            assertSame(once, MetalReflectionFilter.adapt(program, once).text());
        }
        String missing = HISTORY.replace("gl_FragData[1] = vec4(texture4.rgb, 1.0);", "");
        assertSame(missing, MetalReflectionFilter.adapt("composite", missing).text());
        assertSame(FORMATS, MetalReflectionFilter.adapt("gbuffers_terrain", FORMATS).text());
        assertNull(MetalReflectionFilter.adapt("composite", null).text());
    }

    static boolean configured() { return InstalledShaderTestSupport.configured(); }

    @Test @EnabledIf("configured")
    void installedSourcesKeepValidSyntaxAfterMaterialAndHistoryChanges() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (String program : List.of("shadow", "composite6", "final", "composite", "composite1")) {
                        String source = InstalledShaderTestSupport.source(zip, dimension, program + ".fsh",
                                InstalledShaderTestSupport.Options.DEFAULT, List.of());
                        var result = MetalReflectionFilter.adapt(program, source);
                        assertTrue(result.changed(), path.getFileName() + "/" + dimension + "/" + program + ": " + result.status());
                        HighPolishShaderPackTest.parse(result.text());
                    }
                }
            }
        }
    }
}
