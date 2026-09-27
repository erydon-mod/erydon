package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MetalLightingTransformTest {
    private static final String GLOBALS = """
            float erydonMetalCoverage = 0.0;
            vec3 erydonMetalF0 = vec3(0.0);
            vec3 erydonMetalDiffuseComponent = vec3(0.0);
            float erydonMetalRoughness = 0.22;
            """;
    private static final String LIGHTING = """
            float GetNoHSquared(float radiusTan, float NoL, float NoV, float VoL) { return 1.0; }
            void DoLighting(inout vec4 color) {
                vec3 lightHighlight = vec3(0.0);
                float specularHighlight = GGX(normalM, nViewPos, lightVec, NdotLmax0, smoothnessG);
                specularHighlight *= highlightMult;
                lightHighlight = isEyeInWater != 1 ? shadowMult : pow(shadowMult, vec3(0.25)) * 0.35;
                lightHighlight *= (subsurfaceHighlight + specularHighlight) * highlightColor;
                lightHighlight *= lightColorMult;
                color.rgb *= finalDiffuse;
                color.rgb += lightHighlight;
                color.rgb *= pow2(1.0 - darknessLightFactor);
            }
            """;

    @Test void dielectricHighlightAndAllExistingIlluminationGatesRemainUnchanged() {
        for (String program : List.of("gbuffers_terrain", "gbuffers_water")) {
            var result = MetalLightingTransform.adapt(program, GLOBALS + LIGHTING);
            assertTrue(result.changed());
            assertTrue(result.text().contains("float specularHighlight = GGX(normalM, nViewPos, lightVec, NdotLmax0, smoothnessG);"));
            assertTrue(result.text().contains("specularHighlight *= highlightMult;"));
            assertTrue(result.text().contains("else {\n    lightHighlight *= (subsurfaceHighlight + specularHighlight) * highlightColor;"));
            assertTrue(result.text().contains("lightHighlight = isEyeInWater != 1 ? shadowMult : pow(shadowMult, vec3(0.25)) * 0.35;"));
            assertTrue(result.text().contains("lightHighlight *= lightColorMult;"));
            assertTrue(result.text().contains("color.rgb *= finalDiffuse;"));
            assertTrue(result.text().contains("color.rgb *= pow2(1.0 - darknessLightFactor);"));
            assertEquals(1, occurrences(result.text(), "color.rgb += lightHighlight;"));
            assertFalse(result.text().contains("texture"));
            assertFalse(result.text().contains("sampler"));
            assertFalse(result.text().contains("#"));
        }
    }

    @Test void conductorUsesCoverageAndColoredFresnelWithoutDielectricBoostOrExtraCosine() {
        String result = MetalLightingTransform.adapt("gbuffers_terrain", GLOBALS + LIGHTING).text();
        String helper = result.substring(result.indexOf("vec3 ErydonConductorHighlight"), result.indexOf("void DoLighting"));
        assertTrue(helper.contains("clamp(erydonMetalF0"));
        assertTrue(helper.contains("vec3 fresnel = f0 + (vec3(1.0) - f0) * fresnelWeight;"));
        assertTrue(helper.contains("GetNoHSquared(0.01, noL, noV,"));
        assertTrue(helper.contains("float areaAlpha = alpha + 0.005;"));
        assertTrue(helper.contains("clamp(erydonMetalRoughness, 2.0 / 255.0, 1.0)"));
        assertTrue(helper.contains("nativeLightGate <= 0.0 || noV <= 0.0 || noL <= 0.0"));
        assertTrue(helper.contains("return pow(max(fresnel * distribution * visibility, vec3(0.0)), vec3(1.0 / 2.2));"));
        assertFalse(helper.contains("highlightMult"));
        assertTrue(result.contains("clamp(erydonMetalCoverage, 0.0, 1.0)"));
        assertTrue(result.indexOf(GLOBALS.strip()) < result.indexOf("vec3 ErydonConductorHighlight"));
    }

    @Test void compiledOutHighlightsStillRemoveOnlyTheMetalDiffuseContribution() {
        String input = """
                void DoLighting(inout vec4 color) {
                    vec3 lightHighlight = vec3(0.0);
                    color.rgb *= finalDiffuse;
                    color.rgb += lightHighlight;
                }
                """;
        var result = MetalLightingTransform.adapt("gbuffers_terrain", input);
        assertEquals("TRANSFORMED", result.status());
        assertTrue(result.changed());
        assertTrue(result.text().contains("if (erydonMetalCoverage > 0.0 && emission <= 0.0)"));
        assertTrue(result.text().contains("erydonMetalBroadLighting(color.rgb, erydonMetalDiffuseComponent,"));
        assertFalse(result.text().contains("ErydonConductorHighlight"));
        assertTrue(result.text().indexOf("erydonMetalBroadLighting") < result.text().indexOf("color.rgb += lightHighlight;"));
    }

    @Test void mismatchedOrDuplicateSourcesFailBeforeAnyModification() {
        for (String source : List.of("unknown shader", LIGHTING + LIGHTING,
                LIGHTING.replace("GetNoHSquared", "MissingSunDisc"),
                LIGHTING.replace("lightHighlight *= (subsurfaceHighlight + specularHighlight) * highlightColor;", ""),
                LIGHTING.replace("float specularHighlight = GGX(normalM, nViewPos, lightVec, NdotLmax0, smoothnessG);", ""))) {
            var result = MetalLightingTransform.adapt("gbuffers_terrain", source);
            assertEquals("UNSUPPORTED_SOURCE", result.status());
            assertSame(source, result.text());
        }
        String once = MetalLightingTransform.adapt("gbuffers_terrain", GLOBALS + LIGHTING).text();
        assertEquals("ALREADY_TRANSFORMED", MetalLightingTransform.adapt("gbuffers_terrain", once).status());
        assertSame(once, MetalLightingTransform.adapt("gbuffers_terrain", once).text());
        assertSame(LIGHTING, MetalLightingTransform.adapt("gbuffers_entities", LIGHTING).text());
        assertNull(MetalLightingTransform.adapt("gbuffers_terrain", null).text());
    }

    static boolean configured() { return InstalledShaderTestSupport.configured(); }

    @Test @EnabledIf("configured")
    void installedTerrainAndWaterPreprocessAndParseWithConductorLighting() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (String program : List.of("gbuffers_terrain", "gbuffers_water")) {
                        String source = InstalledShaderTestSupport.source(zip, dimension, program + ".fsh",
                                InstalledShaderTestSupport.Options.DEFAULT, List.of());
                        source = source.replaceFirst("void DoLighting", GLOBALS + "\nvoid DoLighting");
                        var result = MetalLightingTransform.adapt(program, source);
                        assertTrue(result.changed() || result.status().equals("HIGHLIGHT_NOT_COMPILED"),
                                path.getFileName() + "/" + dimension + "/" + program + ": " + result.status());
                        HighPolishShaderPackTest.parse(result.text());
                    }
                }
            }
        }
    }

    private static int occurrences(String source, String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
