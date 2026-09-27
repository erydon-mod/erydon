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
                fresnelM = fresnel * 0.7 + 0.3;
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
        assertTrue(result.contains("* 65535.0 + 0.5"));
        assertTrue(result.contains("mod(erydonMaterialWord, 256.0) / 255.0"));
        assertTrue(result.contains("smoothnessD - erydonCoverage * erydonMetalSmoothness"));
        assertTrue(result.contains("sqrt1(erydonBaseSmoothness)"));
        assertTrue(result.contains("float erydonBaseReflection = fresnel * 0.7 + 0.3;"));
        assertTrue(result.contains("if (materialMaskInt == 242) {\n        fresnelM = (pow3(fresnel) * 0.5 + 0.5) * smoothnessD;"));
        assertFalse(result.contains("0.85"), "The former fixed 85% metal floor is absent");
        assertTrue(result.contains("erydonCoverage), 0.0, 0.98)"), "SNORM transport must never round to CU's error sentinel");
        assertFalse(result.contains("0.75 + 0.25 * smoothnessD"), "F0 complements represent absorption, not an arbitrary geometry dimmer");
        assertEquals(1, occurrences(result, "texelFetch("));
    }

    @Test void reconstructedSubstrateUsesTheCompiledSsrCurveAndRejectsAmbiguousVariants() {
        String ssr = DEFERRED.replace("fresnelM = fresnel * 0.7 + 0.3;",
                "float fresnelFactor = (1.0 - smoothnessD) * 0.7;\n"
                        + "fresnelM = max(fresnel - fresnelFactor, 0.0) / (1.0 - fresnelFactor);");
        var result = MetalReflectionResponse.adapt("deferred1", ssr);
        assertTrue(result.changed());
        assertTrue(result.text().contains("float erydonBaseFactor = (1.0 - erydonBaseSmoothness) * 0.7;"));
        assertTrue(result.text().contains("max(fresnel - erydonBaseFactor, 0.0) / (1.0 - erydonBaseFactor)"));
        assertFalse(result.text().contains("float erydonBaseReflection = fresnel * 0.7 + 0.3;"));
        for (String unsupported : List.of(DEFERRED.replace("fresnelM = fresnel * 0.7 + 0.3;", ""),
                ssr + "\nfresnelM = fresnel * 0.7 + 0.3;")) {
            var rejected = MetalReflectionResponse.adapt("deferred1", unsupported);
            assertEquals("UNSUPPORTED_SOURCE", rejected.status());
            assertSame(unsupported, rejected.text());
        }
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
        assertTrue(result.contains("vec3(0.92, 0.41262, 0.0) : vec3(0.95, 0.93, 0.88)"));
        assertTrue(result.contains("erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth"));
        assertTrue(result.contains("max(fresnelM * fresnelM, 0.000001)"));
        assertTrue(result.contains("erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare"));
        assertTrue(result.contains("reflectColor = pow(max(erydonLinearTint, vec3(0.0)), vec3(1.0 / 2.2))"));
        assertTrue(result.contains("1.0 - erydonBroadShare"));
        assertTrue(result.contains("float erydonRoughness = mod(erydonMaterialWord, 256.0) / 255.0;"));
        assertFalse(result.contains("1.0 - sqrt(clamp(smoothnessD"), "Mixed stone smoothness must not change the metal lobe");
        assertEquals(1, occurrences(result, "reflection.rgb *= reflectColor;"));
        assertEquals(1, occurrences(result, "texelFetch("));
    }

    @Test void filterBlendReusesItsExistingFetchAndLetsHistoryGuardRunAfterward() {
        var result = MetalReflectionResponse.adapt("composite1", BLEND);
        assertTrue(result.changed());
        assertEquals(1, occurrences(result.text(), "texelFetch("));
        assertTrue(result.text().contains("erydonMetalBlend = vec2(0.0);"));
        assertTrue(result.text().contains("if (erydonMetalBlend.x > 0.0)"));
        assertTrue(result.text().contains("erydonBaseLinear * (1.0 - erydonStoneFresnelShare)"));
        assertTrue(result.text().contains("+ erydonReflectionLinear * fresnelM"));
        assertTrue(result.text().contains("const float texturePreservation = 0.7;"));
        assertFalse(result.text().contains("0.35 + 0.4"));
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
        assertTrue(result.text().contains("erydonMetalBlend = vec2(0.0);"));
    }

    @Test void waterChangesOnlyMetalCoverageAndLeavesMirrorCoatingIntact() {
        String result = MetalReflectionResponse.adapt("gbuffers_water", WATER).text();
        assertTrue(result.startsWith(WATER.substring(0, WATER.indexOf("vec4 reflection"))));
        assertTrue(result.contains("if (erydonMetalCoverage > 0.0)"));
        assertTrue(result.contains("clamp(1.0 + dot(normalM, nViewPos), 0.0, 1.0)"));
        assertTrue(result.contains("mix(clamp(fresnelM, 0.0, 1.0), erydonMetalStrength, erydonCoverage)"));
        assertTrue(result.indexOf("reflection.rgb *= erydonWaterMetalTint;") < result.indexOf("color.rgb = mix"));
        assertTrue(result.contains("erydonWaterBaseLinear + erydonWaterReflectionLinear"));
        assertTrue(result.contains("erydonWaterReflectionDelta = max(color.rgb - erydonWaterBaseEncoded"));
        assertEquals(1, occurrences(result, "GetReflection("));
        assertFalse(result.contains("texture"));
    }

    @Test void waterWsrReplacementUsesTheSameEncodedDeltaAsTheActualLinearComposition() {
        String water = "skyReflection += specularHighlight * highlightColor * shadowMult * highlightMult * invRainFactor;\n"
                + WATER + "gl_FragData[4] = vec4(reflection.rgb * fresnelM * color.a * fogAlpha, reflection.a);\n";
        String result = MetalReflectionResponse.adapt("gbuffers_water", water).text();
        assertTrue(result.contains("(erydonMetalCoverage > 0.0 ? erydonWaterReflectionDelta : reflection.rgb * fresnelM) * color.a * fogAlpha"));
        assertTrue(result.contains("skyReflection += (1.0 - clamp(erydonMetalCoverage, 0.0, 1.0))"),
                "The native background sun lobe must not duplicate direct conductor light");
        String blend = BLEND.replace("void main() {", "void main() {\ncompositeReflection.rgb *= fresnelM;");
        String post = MetalReflectionResponse.adapt("composite1", blend).text();
        assertTrue(post.contains("erydonTranslucentBase + erydonTranslucentReflection"));
        assertTrue(post.contains("else compositeReflection.rgb *= fresnelM;"));
        assertEquals(2, occurrences(post, "texelFetch("), "Only the translucent branch needs one additional existing-sampler lookup");
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
                                    + "vec3 erydonMetalF0 = vec3(0.0);\nfloat erydonMetalRoughness = 0.22;\nvoid main");
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
