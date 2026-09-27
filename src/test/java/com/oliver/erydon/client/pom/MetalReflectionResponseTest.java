package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MetalReflectionResponseTest {
    private static final String FETCH = "vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;";
    private static final String DEFERRED = """
            void main() {
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                fresnelM = fresnelM * sqrt1(smoothnessD) - dither * 0.01;
                if (materialMaskInt == 242) {
                    fresnelM = (pow3(fresnel) * 0.5 + 0.5) * smoothnessD;
                }
            }
            """;
    private static final String COMPOSITE = """
            void main() {
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                reflection.rgb *= reflectColor;
            }
            """;
    private static final String BLEND = """
            vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0) {
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                float smoothnessD = texture6.r;
                vec4 texture1Sample = texture2D(colortex1, sampleCoord);
                return sum / weightSum;
            }
            void main() {
                compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0);
                const float texturePreservation = 0.7;
                compositeReflection.rgb = mix(compositeReflection.rgb, max(color, compositeReflection.rgb), texturePreservation);
                color = mix(color, compositeReflection.rgb, fresnelM);
            }
            """;
    private static final String WATER = """
            if (materialMaskPh > 0.5) fresnelM = max(fresnelM, (0.9 - 0.15) / 0.85);
            fresnelM = (fresnelM * 0.85 + 0.15) * reflectMult;
            vec4 reflection = GetReflection();
            color.rgb = mix(color.rgb, reflection.rgb, fresnelM);
            """;

    @Test void deferredDecodeKeepsPureStoneAndItsMirrorRuleOutsideTheMetalBranch() {
        String result = MetalReflectionResponse.adapt("deferred1", DEFERRED).text();
        assertTrue(result.contains("vec4 texture6 = texelFetch(colortex6, texelCoord, 0);"));
        assertTrue(result.contains("if (materialMaskInt == 243 || materialMaskInt == 244)"));
        assertTrue(result.contains("floor(erydonMaterialByte / 4.0) / 63.0"));
        assertTrue(result.contains("mod(erydonMaterialByte, 4.0)"));
        assertTrue(result.contains("(175.0 / 255.0) * (175.0 / 255.0)"));
        assertTrue(result.contains("if (materialMaskInt == 242) {\n        fresnelM = (pow3(fresnel) * 0.5 + 0.5) * smoothnessD;"));
        assertFalse(result.contains("0.85"), "The former fixed 85% metal floor is absent");
        assertEquals(1, occurrences(result, "texelFetch("));
    }

    @Test void voxysHelperFetchIsNotPromotedOrChanged() {
        String helper = "void GetLODShadows() { " + FETCH + " }\n";
        String result = MetalReflectionResponse.adapt("deferred1", helper + DEFERRED).text();
        assertTrue(result.startsWith(helper));
        assertEquals(1, occurrences(result, "vec4 texture6"));
        assertEquals(2, occurrences(result, "texelFetch("));
    }

    @Test void compositeTintUsesWeightedReflectionEnergyAndBecomesWhiteAtGrazing() {
        String result = MetalReflectionResponse.adapt("composite", COMPOSITE).text();
        assertTrue(result.contains("vec3(0.72, 0.42, 0.16) : vec3(0.95, 0.93, 0.88)"));
        assertTrue(result.contains("erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth"));
        assertTrue(result.contains("max(fresnelM * fresnelM, 0.000001)"));
        assertTrue(result.contains("erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare"));
        assertEquals(1, occurrences(result, "reflection.rgb *= reflectColor;"));
        assertEquals(1, occurrences(result, "texelFetch("));
    }

    @Test void filterBlendReusesItsExistingFetchAndLetsHistoryGuardRunAfterward() {
        var result = MetalReflectionResponse.adapt("composite1", BLEND);
        assertTrue(result.changed());
        assertEquals(1, occurrences(result.text(), "texelFetch("));
        assertTrue(result.text().contains("erydonMetalTexturePreservation = 0.7;"));
        assertTrue(result.text().contains("erydonMetalTexturePreservation = mix(0.7, erydonMetalPreservation, erydonCoverage);"));
        assertTrue(result.text().contains("0.35 + 0.4 * (1.0 - sqrt(clamp(texture6.r, 0.0, 1.0)))"));
        assertTrue(result.text().contains("float texturePreservation = erydonMetalTexturePreservation;"));
        assertTrue(result.text().contains("color = mix(color, compositeReflection.rgb, fresnelM);"));
        assertTrue(MetalReflectionFilter.adapt("composite1", result.text()).changed());
    }

    @Test void lowSamplerProfileRetainsNativePreservationAndSamplerCount() {
        String input = BLEND.replace("    " + FETCH + "\n", "").replace("    float smoothnessD = texture6.r;\n", "");
        var result = MetalReflectionResponse.adapt("composite1", input);
        assertEquals("TRANSFORMED_LOW_SAMPLER", result.status());
        assertFalse(result.text().contains("colortex6"));
        assertFalse(result.text().contains("texelFetch"));
        assertFalse(result.text().contains("0.35"));
        assertTrue(result.text().contains("erydonMetalTexturePreservation = 0.7;"));
    }

    @Test void waterChangesOnlyMetalCoverageAndLeavesMirrorCoatingIntact() {
        String result = MetalReflectionResponse.adapt("gbuffers_water", WATER).text();
        assertTrue(result.startsWith(WATER.substring(0, WATER.indexOf("vec4 reflection"))));
        assertTrue(result.contains("if (erydonMetalCoverage > 0.0)"));
        assertTrue(result.contains("clamp(1.0 + dot(normalM, nViewPos), 0.0, 1.0)"));
        assertTrue(result.contains("mix(clamp(fresnelM, 0.0, 1.0), erydonMetalStrength, erydonCoverage)"));
        assertTrue(result.indexOf("reflection.rgb *= erydonWaterMetalTint;") < result.indexOf("color.rgb = mix"));
        assertEquals(1, occurrences(result, "GetReflection("));
        assertFalse(result.contains("texture"));
    }

    @Test void missingAndDuplicateAnchorsNeverPartiallyModifySource() {
        for (var input : List.of(new String[]{"deferred1", DEFERRED}, new String[]{"composite", COMPOSITE},
                new String[]{"composite1", BLEND}, new String[]{"gbuffers_water", WATER})) {
            for (String unsupported : List.of("unknown shader", input[1] + input[1])) {
                var result = MetalReflectionResponse.adapt(input[0], unsupported);
                assertSame(unsupported, result.text());
                assertEquals("UNSUPPORTED_SOURCE", result.status());
            }
            String once = MetalReflectionResponse.adapt(input[0], input[1]).text();
            assertSame(once, MetalReflectionResponse.adapt(input[0], once).text());
            assertFalse(once.contains("#"));
        }
        String missing = BLEND.replace("const float texturePreservation = 0.7;", "");
        assertSame(missing, MetalReflectionResponse.adapt("composite1", missing).text());
        assertSame(DEFERRED, MetalReflectionResponse.adapt("gbuffers_entities", DEFERRED).text());
        assertNull(MetalReflectionResponse.adapt("composite", null).text());
    }

    static boolean configured() { return InstalledShaderTestSupport.configured(); }

    @Test @EnabledIf("configured")
    void installedReflectionPassesParseWithResponseThenHistoryFiltering() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (String program : List.of("deferred1", "composite", "composite1", "gbuffers_water")) {
                        String source = InstalledShaderTestSupport.source(zip, dimension, program + ".fsh",
                                InstalledShaderTestSupport.Options.DEFAULT, List.of());
                        if (program.equals("gbuffers_water")) {
                            source = source.replaceFirst("void main", "float erydonMetalCoverage = 0.0;\n"
                                    + "vec3 erydonMetalF0 = vec3(0.0);\nvoid main");
                        }
                        var response = MetalReflectionResponse.adapt(program, source);
                        assertTrue(response.changed(), path.getFileName() + "/" + dimension + "/" + program + ": " + response.status());
                        var history = MetalReflectionFilter.adapt(program, response.text());
                        if (program.equals("composite") || program.equals("composite1")) assertTrue(history.changed(), history.status());
                        HighPolishShaderPackTest.parse(history.text());
                    }
                }
            }
        }
    }

    private static int occurrences(String source, String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
