package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class InlayInitialAlbedoFilterTest {
    static boolean installedShadersConfigured() { return InstalledShaderTestSupport.configured(); }
    @AfterAll static void closeCompiler() {
        InstalledShaderTestSupport.close();
        HighPolishShaderAdapter.beginShaderLoad(false, false);
    }
    private static final String SOURCE = """
            vec2 dcdx = dFdx(texCoord.xy);
            vec2 dcdy = dFdy(texCoord.xy);
            vec4 textureAF(sampler2D texSampler, vec2 uv) {
                mat2 J = inverseM(mat2(dFdx(uv), dFdy(uv)));
                float samplesDiv2 = 8 / 2.0;
                vec2 ADivSamples = A / 8;
                filteredColor.a /= 8;
                return filteredColor;
            }
            void erydonInlayTrace(inout vec2 sampledUv, inout vec4 color, float distanceToSurface) {
                color = vec4(baseColor.rgb * glColor.rgb, 1.0);
            }
            void GetCustomMaterials(inout vec4 color) {
                vec2 texCoordM = texCoord;
                if (erydonInlayActive) {
                    erydonInlayTrace(texCoordM, color, lViewPos);
                }
            }
            void main() {
                vec4 color = textureAF(tex, texCoord);
                float smoothnessD = 0.0, materialMask = 0.0;
            %s
                float erydonOverlayAlpha = erydonInlayActive ? 1.0 : erydonMetalCutout(texCoord, 0.5);
                if (erydonOverlayAlpha >= 0.0) { if (erydonOverlayAlpha < 0.5) discard; color.a = 1.0; }
                if (color.a <= 0.00001) discard;
                vec3 colorP = color.rgb;
                color.rgb *= glColor.rgb;
                GetCustomMaterials(color, normalM);
            }
            """.formatted(InlayInitialAlbedoFilter.ACTIVE);

    @Test void activeGatePrecedesInitialColorAndNativeFallbackUsesUnconditionalGradients() {
        String result = InlayInitialAlbedoFilter.adapt(SOURCE);
        assertNotEquals(SOURCE, result);
        assertTrue(result.contains(InlayInitialAlbedoFilter.MARKER));
        int gate = result.indexOf(InlayInitialAlbedoFilter.ACTIVE);
        int color = result.indexOf("vec4 color = vec4(0.0, 0.0, 0.0, 1.0);");
        assertTrue(gate > result.indexOf("void main()") && gate < color);
        assertTrue(result.contains("if (!erydonInlayActive) color = textureAF(tex, texCoord, dcdx, dcdy);"));
        assertTrue(result.contains("mat2 J = inverseM(mat2(uvDx, uvDy));"));
        assertTrue(result.contains("vec4 textureAF(sampler2D texSampler, vec2 uv) { return textureAF(texSampler, uv, dFdx(uv), dFdy(uv)); }"));
        assertEquals(1, result.split("erydonInlayActive = erydonInlaySubstrateRecord", -1).length - 1);
        assertSame(result, InlayInitialAlbedoFilter.adapt(result));
    }

    @Test void missingDuplicatedOrChangedContractsRetainOriginalSource() {
        for (String token : new String[]{InlayInitialAlbedoFilter.ACTIVE, "vec4 color = textureAF(tex, texCoord);",
                "vec2 dcdx = dFdx(texCoord.xy);", "vec2 dcdy = dFdy(texCoord.xy);",
                "mat2 J = inverseM(mat2(dFdx(uv), dFdy(uv)));", "float samplesDiv2 = 8 / 2.0;",
                "color = vec4(baseColor.rgb * glColor.rgb, 1.0);"}) {
            String missing = SOURCE.replace(token, "");
            assertSame(missing, InlayInitialAlbedoFilter.adapt(missing), token);
            String duplicated = SOURCE + "\n" + token;
            assertSame(duplicated, InlayInitialAlbedoFilter.adapt(duplicated), token);
        }
        for (int filter : new int[]{0, 4, 16}) {
            String other = SOURCE.replace("8 / 2.0", filter + " / 2.0");
            assertSame(other, InlayInitialAlbedoFilter.adapt(other));
        }
        for (String discard : List.of("", "if (color.a <= 0.5) discard;")) {
            String unreviewedDiscard = SOURCE.replace("if (color.a <= 0.00001) discard;", discard);
            assertSame(unreviewedDiscard, InlayInitialAlbedoFilter.adapt(unreviewedDiscard));
        }
        assertNull(InlayInitialAlbedoFilter.adapt(null));
        assertSame("unknown shader", InlayInitialAlbedoFilter.adapt("unknown shader"));
    }

    @Test void survivingInitialColorUsesOrAnEarlyTraceReturnDisableOptimization() {
        for (String mutation : new String[]{
                SOURCE.replace("GetCustomMaterials(color, normalM);", "emission = color.r; GetCustomMaterials(color, normalM);"),
                SOURCE + "\nvec3 keepInitial = colorP;",
                SOURCE.replace("color = vec4(baseColor.rgb * glColor.rgb, 1.0);", "if (unknown) return; color = vec4(baseColor.rgb * glColor.rgb, 1.0);"),
                SOURCE.replace("color = vec4(baseColor.rgb * glColor.rgb, 1.0);", "if (unknown) { color = vec4(baseColor.rgb * glColor.rgb, 1.0); }"),
                SOURCE.replace("color = vec4(baseColor.rgb * glColor.rgb, 1.0);", "if (unknown)\ncolor = vec4(baseColor.rgb * glColor.rgb, 1.0);"),
                SOURCE.replace("color = vec4(baseColor.rgb * glColor.rgb, 1.0);", "color = vec4(baseColor.rgb * glColor.rgb, 1.0); color.r *= 0.5;"),
                SOURCE.replace("if (erydonInlayActive) {", "if (erydonInlayActive && unknown) {"),
                SOURCE.replace("vec2 dcdx = dFdx(texCoord.xy);", "void unsafe() { vec2 dcdx = dFdx(texCoord.xy); }")}) {
            assertSame(mutation, InlayInitialAlbedoFilter.adapt(mutation));
        }
    }

    @Test @EnabledIf("installedShadersConfigured")
    void installedTerrainOnlySkipsRecognizedAf8InlaySources() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (var options : List.of(new InstalledShaderTestSupport.Options(true, 8, 120),
                        new InstalledShaderTestSupport.Options(true, 4, 120),
                        new InstalledShaderTestSupport.Options(true, 0, 120),
                        new InstalledShaderTestSupport.Options(false, 8, 120),
                        new InstalledShaderTestSupport.Options(true, 8, 0))) {
                    HighPolishShaderAdapter.beginShaderLoad(true, true);
                    HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                    String vertex = InstalledShaderTestSupport.source(zip, "world0", "gbuffers_terrain.vsh", options, List.of());
                    String fragment = InstalledShaderTestSupport.source(zip, "world0", "gbuffers_terrain.fsh", options, List.of());
                    var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram("gbuffers_terrain", vertex, fragment,
                            ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), true);
                    var finish = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", pom.fragmentText(), true);
                    var metal = MetallicShaderAdapter.adapt("gbuffers_terrain", pom.vertexText(), finish.text(), true);
                    assertTrue(metal.changed(), path.getFileName() + ": " + metal.status());
                    String reviewedDiscard = "if (color.a <= 0.00001) discard;";
                    boolean af8Candidate = options.pom() && options.filtering() == 8 && options.normalStrength() != 0;
                    boolean hasReviewedDiscard = fragment.contains(reviewedDiscard);
                    boolean optimized = af8Candidate && hasReviewedDiscard;
                    assertEquals(optimized, metal.fragment().contains(InlayInitialAlbedoFilter.MARKER),
                            path.getFileName() + ": " + options);
                    if (af8Candidate && !hasReviewedDiscard) {
                        // Newer CU sources use a different native discard. Retain AF8 until that
                        // exact source contract is reviewed; the element-bounds fix does not alter it.
                        String unreviewedDiscard = "if (color.a <= 0.5) discard;";
                        assertTrue(fragment.contains(unreviewedDiscard), "Unexpected native discard contract: " + path);
                        assertTrue(metal.fragment().contains("vec4 color = textureAF(tex, texCoord);"),
                                "Unreviewed native discard must retain the initial AF8 sample");
                        String restoredContract = metal.fragment().replace(unreviewedDiscard, reviewedDiscard);
                        assertTrue(InlayInitialAlbedoFilter.adapt(restoredContract).contains(InlayInitialAlbedoFilter.MARKER),
                                "Native discard must be the only unsupported AF8 precondition");
                    }
                    HighPolishShaderPackTest.parse(metal.fragment());
                    InstalledShaderTestSupport.validateProgram(path.getFileName() + "/" + options, metal.vertex(), metal.fragment());
                }
            }
        }
    }
}
