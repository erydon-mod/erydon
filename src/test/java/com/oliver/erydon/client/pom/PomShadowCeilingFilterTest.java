package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PomShadowCeilingFilterTest {
    private static final String NATIVE = """
            float GetParallaxShadow(float parallaxFade, float dither, float height, vec2 coord, vec3 lightVec, mat3 tbn) {
                float parallaxshadow = 1.0;
                vec3 parallaxdir = tbn * lightVec;
                parallaxdir.xy *= 1.0 * 1.95; // Angle
                for (int i = 0; i < 4 && parallaxshadow >= 0.01; i++) {
                    float stepLC = 0.025 * (i + dither);
                    float currentHeight = height + parallaxdir.z * stepLC;
                    vec2 parallaxCoord = erydonCtmPomAtlasUv(coord + parallaxdir.xy * stepLC);
                    float offsetHeight = textureGrad(normals, parallaxCoord, dcdx, dcdy).a;
                    parallaxshadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
                }
                return mix(parallaxshadow, 1.0, parallaxFade);
            }
            """;
    private static final String NATIVE_TAP = """
                    vec2 parallaxCoord = erydonCtmPomAtlasUv(coord + parallaxdir.xy * stepLC);
                    float offsetHeight = textureGrad(normals, parallaxCoord, dcdx, dcdy).a;
                    parallaxshadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
            """;
    private static final String INLAY_TAP = """
                    float offsetHeight = erydonInlaySubstrateHeight(erydonInlayShadowLocal + direction.xy / erydonInlayRibbonScale * stepLC);
                    shadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
            """;
    private static final String SOURCE = NATIVE + recessSource();

    @Test void wrapsBothProvedTapsAndKeepsEveryOtherOperation() {
        for (String depth : List.of("0.00", "1.00", "1.95", "2.00")) {
            String source = SOURCE.replace("1.0 * 1.95;", "1.0 * " + depth + ";");
            String adapted = PomShadowCeilingFilter.adapt(source);
            assertNotEquals(source, adapted);
            assertEquals(2, count(adapted, PomShadowCeilingFilter.MARKER));
            assertEquals(2, count(adapted, "if (!(currentHeight >= 1.0))"));
            assertEquals(2, count(adapted, "float offsetHeight = 1.0;"));
            assertTrue(adapted.contains("return mix(parallaxshadow, 1.0, parallaxFade);"));
            assertTrue(adapted.contains("shadow = mix(1.0, shadow, erydonInlayShadowBaseFade);"));
            assertTrue(adapted.contains("shadow *= erydonInlayTraceGroove(erydonInlayShadowLocal, ray, opening, wall);"));
            assertSame(adapted, PomShadowCeilingFilter.adapt(adapted));
        }
        assertEquals(2, count(PomShadowCeilingFilter.adapt(SOURCE.replace("\n", "\r\n")), PomShadowCeilingFilter.MARKER));
    }

    @Test void changedOrDuplicatedContractsAtomicallyRetainOriginalSource() {
        for (String source : List.of(
                SOURCE.replace("float currentHeight = height +", "float currentHeight = 0.1 + height +"),
                SOURCE.replace("float currentHeight = erydonInlayShadowBaseHeight +", "float currentHeight = 0.1 + erydonInlayShadowBaseHeight +"),
                SOURCE.replace("parallaxshadow *= clamp", "parallaxshadow += clamp"),
                SOURCE.replace("shadow *= clamp", "shadow += clamp"),
                SOURCE.replace("textureGrad(normals, parallaxCoord, dcdx, dcdy).a", "textureGrad(normals, parallaxCoord, dcdx, dcdy).a * 2.0"),
                SOURCE.replace("erydonInlayGradient(dcdy, scale)).a;", "erydonInlayGradient(dcdy, scale)).a * 2.0;"),
                SOURCE.replace("float stepLC = 0.025 * (i + dither);", "float stepLC = 0.05 * (i + dither);"),
                SOURCE.replace("parallaxdir.xy *= 1.0 * 1.95;", "parallaxdir.xy *= unknownDepth;"),
                SOURCE.replace("float parallaxshadow = 1.0;", "float parallaxshadow = 1.0; anotherState++;"),
                SOURCE.replace("float shadow = 1.0;", "float shadow = 1.0; anotherState++;"),
                SOURCE.replace("return shadow;", "return shadow * extraScale;"),
                SOURCE.replace("float erydonInlaySubstrateHeight", "float unknownHeight"),
                SOURCE.replace("float erydonInlayShadow", "float unknownShadow"),
                SOURCE + NATIVE,
                SOURCE + NATIVE_TAP,
                SOURCE + INLAY_TAP,
                SOURCE + recessSource())) {
            assertSame(source, PomShadowCeilingFilter.adapt(source));
        }
        assertNull(PomShadowCeilingFilter.adapt(null));
        assertSame("unknown shader", PomShadowCeilingFilter.adapt("unknown shader"));
    }

    @Test void lookalikeTapOutsideProvedFunctionCannotSubstituteForItsTap() {
        String nativeLookalike = SOURCE.replace("vec2 parallaxCoord =", "vec2  parallaxCoord =")
                + "\nvoid unrelated() {\n" + NATIVE_TAP + "}\n";
        String inlayLookalike = SOURCE.replace("float offsetHeight = erydonInlaySubstrateHeight", "float  offsetHeight = erydonInlaySubstrateHeight")
                + "\nvoid unrelated() {\n" + INLAY_TAP + "}\n";
        assertSame(nativeLookalike, PomShadowCeilingFilter.adapt(nativeLookalike));
        assertSame(inlayLookalike, PomShadowCeilingFilter.adapt(inlayLookalike));
    }

    @Test void normalizedHeightCeilingMakesTheOriginalMultiplierExactlyOne() {
        for (int alpha = 0; alpha <= 255; alpha++) {
            float sampledHeight = alpha / 255.0f;
            for (float currentHeight : new float[]{1, Math.nextUp(1f), 1.025f, 2, 1000, Float.MAX_VALUE}) {
                float multiplier = Math.max(0, Math.min(1, 1 - (sampledHeight - currentHeight) * 4));
                assertEquals(Float.floatToRawIntBits(1), Float.floatToRawIntBits(multiplier));
            }
        }
        assertTrue(!(Float.NaN >= 1.0f), "NaN follows the original sampled fallback");
    }

    static boolean installedShadersConfigured() { return InstalledShaderTestSupport.configured(); }

    @Test @EnabledIf("installedShadersConfigured")
    void installedTerrainUsesCeilingGuardOnlyWithRecognizedRecessedPom() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (var options : List.of(new InstalledShaderTestSupport.Options(true, 8, 120),
                        new InstalledShaderTestSupport.Options(true, 0, 120),
                        new InstalledShaderTestSupport.Options(false, 8, 120),
                        new InstalledShaderTestSupport.Options(true, 8, 0))) {
                    HighPolishShaderAdapter.beginShaderLoad(true, true);
                    HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                    String vertex = InstalledShaderTestSupport.source(zip, "world0", "gbuffers_terrain.vsh", options, List.of());
                    String fragment = InstalledShaderTestSupport.source(zip, "world0", "gbuffers_terrain.fsh", options, List.of());
                    var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram("gbuffers_terrain", vertex, fragment,
                            ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), true);
                    var polish = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", pom.fragmentText(), true);
                    var metal = MetallicShaderAdapter.adapt("gbuffers_terrain", pom.vertexText(), polish.text(), true);
                    assertTrue(metal.changed(), path.getFileName() + ": " + metal.status());
                    boolean expected = options.pom() && options.normalStrength() != 0;
                    assertEquals(expected ? 2 : 0, count(metal.fragment(), PomShadowCeilingFilter.MARKER),
                            path.getFileName() + ": " + options);
                    HighPolishShaderPackTest.parse(metal.fragment());
                    InstalledShaderTestSupport.validateProgram(path.getFileName() + "/" + options, metal.vertex(), metal.fragment());
                }
            }
        }
    }

    @AfterAll static void closeCompiler() {
        InstalledShaderTestSupport.close();
        HighPolishShaderAdapter.beginShaderLoad(false, false);
    }

    private static String recessSource() {
        try (var input = PomShadowCeilingFilterTest.class.getResourceAsStream("/assets/erydon/shaders/include/erydon_inlay_recess.glsl")) {
            if (input == null) throw new IllegalStateException("Missing recessed inlay helper");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static int count(String text, String token) { return text.split(java.util.regex.Pattern.quote(token), -1).length - 1; }
}
