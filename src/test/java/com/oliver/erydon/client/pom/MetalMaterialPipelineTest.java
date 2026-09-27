package com.oliver.erydon.client.pom;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Executes buffer transport and production reflection slices; it does not claim visual scene validation. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class MetalMaterialPipelineTest {
    @TempDir Path emptyPack;

    static boolean configured() { return InstalledShaderTestSupport.configured(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }
    @AfterEach void resetFinishState() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void actualIrisProgramSetRetainsAlphaFormatsAndRoutesMaterialWrites() throws Exception {
        withConfig(() -> {
            for (Path path : InstalledShaderTestSupport.shaderPaths()) {
                try (var zip = new ZipFile(path.toFile())) {
                    for (String dimension : List.of("world0", "world-1", "world1")) {
                        Fixture fixture = fixture(zip, dimension);
                        var settings = fixture.programs().getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
                        assertEquals(InternalTextureFormat.RGBA16, settings.get(6).getInternalFormat(), path + "/" + dimension);
                        assertEquals(InternalTextureFormat.RGBA8_SNORM, settings.get(1).getInternalFormat());
                        assertEquals(InternalTextureFormat.RGBA8_SNORM, settings.get(4).getInternalFormat());
                        assertEquals(6, fixture.programs().get(ProgramId.Terrain).orElseThrow().getDirectives().getDrawBuffers()[1]);
                        assertEquals(4, fixture.programs().getComposite(ProgramArrayId.Deferred)[1].getDirectives().getDrawBuffers()[2]);
                        assertEquals(1, fixture.programs().getComposite(ProgramArrayId.Composite)[0].getDirectives().getDrawBuffers()[1]);
                    }
                }
            }
        });
    }

    @Test @EnabledIf("driverConfigured")
    void actualPackedMaterialSurvivesGbufferAndDeferredTransportBeforeConductorTint() throws Exception {
        InstalledShaderTestSupport.ensureContext();
        // This is a transport test, so one configured source is sufficient; other versions are covered above.
        try (var zip = new ZipFile(InstalledShaderTestSupport.shaderPaths().get(0).toFile())) {
            for (boolean worldSpace : new boolean[]{false, true}) verifyMaterialTransport(zip, worldSpace);
        }
    }

    private void verifyMaterialTransport(ZipFile zip, boolean worldSpace) throws Exception {
            Fixture fixture = fixture(zip, "world0", Map.of("WORLD_SPACE_REFLECTIONS", worldSpace ? "1" : "-1", "COLORED_LIGHTING", "1"));
            var formats = fixture.programs().getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
            String terrain = fixture.fragments().get("gbuffers_terrain");
            String deferred = fixture.fragments().get("deferred1");
            String composite = fixture.fragments().get("composite");
            String packing = statement(terrain, "int erydonFinish =") + "\n"
                    + statement(terrain, "erydonMetalPacked = (") + "\n"
                    + statement(terrain, "erydonMetalTag = (erydonInfo.y");
            String quantize = statement(terrain, "erydonMetalCoverage = floor(") + "\n"
                    + statement(terrain, "erydonMetalRoughness = clamp(floor(");
            String mixSmoothness = statement(terrain, "float erydonMetalSmoothness =") + "\n"
                    + statement(terrain, "smoothnessD = mix(smoothnessD, erydonMetalSmoothness");
            String finishSelection = blockAfter(terrain, "// ERYDON opaque high polish");
            int mirrorAssignment = terrain.indexOf("materialMask = OSIEBCA * 242.0;");
            String mirrorSelection = statement(terrain, terrain.substring(terrain.lastIndexOf("if (", mirrorAssignment), mirrorAssignment));
            String materialWrite = statement(terrain, "gl_FragData[1] =");
            String deferredResponse = blockAfter(deferred, "// ERYDON conductor reflection response");
            String nativeCurve = nativeFresnelCurve(deferred, worldSpace);
            String mirrorCurve = blockAfter(deferred, "// ERYDON opaque high polish");
            String deferredWrite = statement(deferred, "gl_FragData[2] =");
            String compositeTint = blockAfter(composite, "// ERYDON conductor reflection response");
            String tintWrite = statement(composite, "reflection.rgb *= reflectColor");
            String blend = fixture.fragments().get("composite1");
            String finalBlend = blockAt(blend, blend.indexOf("if (erydonMetalBlend.x > 0.0)"), true);
            String blendMetadata = statement(blend, "int erydonBlendMask =") + "\n"
                    + blockAt(blend, blend.indexOf("if (erydonBlendMask == 243"), false);
            String broadHelper;
            try (var input = getClass().getResourceAsStream("/assets/erydon/shaders/include/erydon_metal_fragment.glsl")) {
                assertNotNull(input);
                String helper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                broadHelper = blockAt(helper, helper.indexOf("vec3 erydonMetalBroadLighting("), false);
            }
            String diffuse = statement(terrain, "color.rgb *= finalDiffuse;") + "\n"
                    + blockAt(terrain, terrain.indexOf("if (erydonMetalCoverage > 0.0 && emission <= 0.0)"), false);
            String direct = statement(terrain, "color.rgb += lightHighlight;");

            String producer = """
                    uniform float coverage;
                    uniform int alloy;
                    uniform int finish;
                    uniform float roughness;
                    uniform float stoneSmoothness;
                    float pow2(float x) { return x * x; }
                    void main() {
                        float erydonMetalCoverage = coverage;
                        int mat = finish == 1 ? 12032 : finish == 2 ? 12040 : finish == 3 ? 12024 : 0;
                        const float OSIEBCA = 1.0 / 255.0;
                        vec4 specularMap = vec4(stoneSmoothness, 10.0 / 255.0, 0.0, 0.0);
                    """ + finishSelection + """
                        float materialMask = specularMap.g * OSIEBCA * 214.0;
                    """ + mirrorSelection + """
                        ivec4 erydonInfo = ivec4(2, alloy, 0, 0);
                        float erydonMetalPacked = 0.0, erydonMetalTag = 0.0;
                        float smoothnessD = pow2(specularMap.r), skyLightFactor = 1.0;
                        float erydonRoughness = roughness, erydonMetalRoughness = 0.0;
                        if (coverage >= 1.0 / 126.0) {
                    """ + quantize + mixSmoothness + packing + """
                            materialMask = erydonMetalTag;
                        }
                    """ + materialWrite + "\n}\n";
            String strength = """
                    uniform sampler2D colortex6;
                    uniform float grazing;
                    float pow2(float x) { return x * x; }
                    float pow3(float x) { return x * x * x; }
                    float sqrt1(float x) { return x * (2.0 - x); }
                    void main() {
                        ivec2 texelCoord = ivec2(0);
                        vec4 texture6 = texelFetch(colortex6, texelCoord, 0);
                        int materialMaskInt = int(texture6.g * 255.1);
                        float smoothnessD = texture6.r;
                        float fresnel = grazing, dither = 0.0;
                        float intenseFresnel = materialMaskInt <= 240 ? float(materialMaskInt) / 240.0 : 0.0;
                        float fresnelM = 0.0;
                    """ + nativeCurve + deferredResponse + mirrorCurve + """
                        mat4 gbufferModelViewInverse = mat4(1.0);
                        vec3 normalM = vec3(0.0, 0.0, 1.0);
                        vec4 color = vec4(1.0);
                    """ + deferredWrite + "\n}\n";
            String tint = """
                    uniform sampler2D colortex6;
                    uniform sampler2D colortex4;
                    uniform vec3 environment;
                    uniform float grazing;
                    void main() {
                        ivec2 texelCoord = ivec2(0);
                        vec4 texture6 = texelFetch(colortex6, texelCoord, 0);
                        vec4 texture4 = texelFetch(colortex4, texelCoord, 0);
                        int materialMaskInt = int(texture6.g * 255.1);
                        float smoothnessD = texture6.r, fresnelM = texture4.a, fresnel = grazing;
                        vec3 reflectColor = vec3(1.0);
                        vec4 reflection = vec4(environment, 1.0);
                    """ + compositeTint + tintWrite + """
                        gl_FragData[0] = reflection;
                    }
                    """;
            String composition = broadHelper + """
                    uniform sampler2D colortex6;
                    uniform sampler2D colortex4;
                    uniform sampler2D colortex7;
                    uniform vec3 surfaceEncoded;
                    uniform vec3 metalComponent;
                    uniform vec3 directEncoded;
                    uniform float coverage;
                    uniform float roughness;
                    vec3 surfaceLighting() {
                        vec4 color = vec4(surfaceEncoded, 1.0);
                        vec3 finalDiffuse = vec3(1.0), lightHighlight = directEncoded;
                        float emission = 0.0, erydonMetalCoverage = coverage, erydonMetalRoughness = roughness;
                        vec3 erydonMetalDiffuseComponent = metalComponent;
                    """ + diffuse + direct + """
                        return color.rgb;
                    }
                    void main() {
                        vec4 texture6 = texelFetch(colortex6, ivec2(0), 0);
                        vec4 texture4 = texelFetch(colortex4, ivec2(0), 0);
                        float fresnelM = pow(texture4.a, 2.0);
                        mat4 gbufferModelView = mat4(1.0);
                        vec3 nViewPos = vec3(0.0, 0.0, -1.0);
                        vec2 erydonMetalBlend = vec2(0.0);
                    """ + blendMetadata + """
                        vec3 color = surfaceLighting();
                        vec4 compositeReflection = texelFetch(colortex7, ivec2(0), 0);
                        const float texturePreservation = 0.7;
                    """ + finalBlend + """
                        gl_FragData[0] = vec4(pow(color, vec3(2.2)), fresnelM);
                    }
                    """;

            try (var gpu = new TransportGpu()) {
                int g6 = gpu.texture(formats.get(6).getInternalFormat());
                int g4 = gpu.texture(formats.get(4).getInternalFormat());
                int g7 = gpu.texture(formats.get(7).getInternalFormat());
                int output = gpu.texture(InternalTextureFormat.RGBA32F);
                int produce = gpu.program(producer), transport = gpu.program(strength);
                int reflect = gpu.program(tint), compose = gpu.program(composition);
                for (int alloy : new int[]{1, 2}) {
                    float[] f0 = alloy == 1 ? new float[]{0.92f, 0.41262f, 0.0f} : new float[]{0.95f, 0.93f, 0.88f};
                    for (float roughness : new float[]{0, 3.0f / 255, 0.22f, 0.65f}) {
                      for (int finishCase : new int[]{0, 1, 2, 3, 4}) {
                        int finish = finishCase == 4 ? 3 : finishCase;
                        float sourceSmoothness = finishCase == 4 ? 0.5f : 0.8f;
                        double baseSmoothness = finishCase == 1 ? square(175.0 / 255.0)
                                : finishCase == 2 || finishCase == 3 ? 1.0 : square(sourceSmoothness);
                        int expectedFinish = finishCase == 4 ? 0 : finish;
                        float r = Math.max(2, Math.min(229, Math.round(roughness * 255))) / 255.0f;
                        double broadShare = Math.max(r * r, 0.06);
                        for (float coverage : new float[]{0, 0.25f, 1}) {
                            float c = Math.round(coverage * 63) / 63.0f;
                            String label = (worldSpace ? "WSR" : "SSR") + "/alloy=" + alloy + "/r=" + roughness
                                    + "/finish=" + finishCase + "/coverage=" + coverage;
                            gpu.target(g6, 1, 6);
                            GL20.glUseProgram(produce);
                            uniform(produce, "coverage", coverage); uniform(produce, "roughness", roughness);
                            GL20.glUniform1i(GL20.glGetUniformLocation(produce, "alloy"), alloy);
                            uniform(produce, "stoneSmoothness", sourceSmoothness);
                            GL20.glUniform1i(GL20.glGetUniformLocation(produce, "finish"), finish);
                            float[] packed = gpu.draw();
                            int word = Math.round(packed[3] * 65535);
                            assertEquals(coverage == 0 ? 0 : ((Math.round(coverage * 63) * 4 + expectedFinish) * 256 + Math.round(r * 255)), word,
                                    label + ": G6 alpha must preserve coverage, finish and independent roughness");
                            assertEquals(coverage == 0 ? expectedFinish == 3 ? 242 : 8 : alloy == 1 ? 243 : 244,
                                    (int) (packed[1] * 255.1f), label + ": material identity");
                            assertEquals(baseSmoothness * (1 - c) + square(1 - r) * c, packed[0], 0.00002,
                                    label + ": production mixes smoothness before writing G6");
                            if (coverage == 1 && roughness < 0.02f) {
                                assertTrue(packed[0] > 0.97,
                                        "Authored polished metal must reach CU's sharp-reflection range, independently of stone finish");
                            }

                            gpu.target(g4, 2, 4);
                            GL20.glUseProgram(transport);
                            gpu.sampler(transport, "colortex6", 0, g6);
                            uniform(transport, "grazing", 0);
                            gpu.draw();
                            float[] deferredPixel = gpu.sampleStored(g4, output);
                            assertTrue(deferredPixel[3] > 0 && deferredPixel[3] < 1,
                                    "SNORM strength must not become CU's zero/one error sentinel");
                            float storedStrength = deferredPixel[3] * deferredPixel[3];
                            double baseFresnel = nativeStoneFresnel(worldSpace, expectedFinish == 3, baseSmoothness, 0);
                            double combined = baseFresnel * (1 - c) + f0[0] * c;
                            assertEquals(Math.sqrt(Math.min(combined, 0.98)), deferredPixel[3], 0.5 / 127 + 0.00002,
                                    label + ": independent stone Fresnel and conductor strength survive SNORM conversion");

                            for (float environment : new float[]{0, 0.15f, 0.8f}) {
                                gpu.target(g7, 0, 7);
                                GL20.glUseProgram(reflect);
                                gpu.sampler(reflect, "colortex6", 0, g6);
                                gpu.sampler(reflect, "colortex4", 1, g4);
                                uniform(reflect, "grazing", 0);
                                float envEncoded = (float) Math.pow(environment, 1.0 / 2.2);
                                vector(reflect, "environment", new float[]{envEncoded, envEncoded, envEncoded});
                                gpu.draw();
                                float[] reflected = gpu.sampleStored(g7, output);
                                for (int channel = 0; channel < 3; channel++) {
                                    double metalShare = Math.min(1, c * f0[0] / storedStrength);
                                    double coefficient = 1 - metalShare + metalShare * (1 - broadShare) * f0[channel] / f0[0];
                                    assertEquals(Math.pow(environment * coefficient, 1.0 / 2.2), reflected[channel], 0.009,
                                            label + "/env=" + environment + "/channel=" + channel
                                                    + ": metal reflection roughness must remain independent of stone finish");
                                }
                                for (float directLight : new float[]{0, 0.04f}) {
                                    gpu.target(output, 0, 0);
                                    GL20.glUseProgram(compose);
                                    gpu.sampler(compose, "colortex6", 0, g6);
                                    gpu.sampler(compose, "colortex4", 1, g4);
                                    gpu.sampler(compose, "colortex7", 2, g7);
                                    uniform(compose, "coverage", c); uniform(compose, "roughness", r);
                                    float[] surface = new float[3], metal = new float[3], directValue = new float[3];
                                    double[] surfaceAfterLighting = new double[3];
                                    for (int channel = 0; channel < 3; channel++) {
                                        metal[channel] = c * (float) Math.pow(f0[channel], 1.0 / 2.2);
                                        surface[channel] = (1 - c) * 0.4f + metal[channel];
                                        directValue[channel] = directLight;
                                        surfaceAfterLighting[channel] = (1 - c) * 0.4
                                                + metal[channel] * Math.pow(broadShare, 1.0 / 2.2) + directLight;
                                    }
                                    vector(compose, "surfaceEncoded", surface); vector(compose, "metalComponent", metal);
                                    vector(compose, "directEncoded", directValue);
                                    float[] actual = gpu.draw();
                                    if (alloy == 1 && coverage == 1 && environment == 1 && directLight == 0) {
                                        assertEquals(166.0 / 239.0, Math.pow(actual[1] / actual[0], 1.0 / 2.2), 0.005,
                                                "Deferred reflections must preserve the same authored gold hue as surface lighting");
                                        assertEquals(0, actual[2], 0.0001,
                                                "Neutral reflected light must not introduce blue into authored gold");
                                    }
                                    for (int channel = 0; channel < 3; channel++) {
                                        double expected;
                                        if (coverage == 0) {
                                            double preserved = reflected[channel] * 0.3
                                                    + Math.max(surfaceAfterLighting[channel], reflected[channel]) * 0.7;
                                            expected = Math.pow(surfaceAfterLighting[channel] * (1 - storedStrength)
                                                    + preserved * storedStrength, 2.2);
                                        } else {
                                            double stoneShare = Math.max(0, Math.min(1 - c, storedStrength - c * f0[0]));
                                            expected = Math.pow(surfaceAfterLighting[channel], 2.2) * (1 - stoneShare)
                                                    + Math.pow(reflected[channel], 2.2) * storedStrength;
                                        }
                                        assertEquals(expected, actual[channel], 0.0004,
                                                label + "/env=" + environment + "/direct=" + directLight + "/channel=" + channel
                                                        + ": final composition preserves direct lighting and native zero coverage");
                                        if (coverage == 1 && directLight == 0) {
                                            double expectedEnergy = f0[channel] * broadShare
                                                    + f0[channel] * (1 - broadShare) * environment;
                                            assertEquals(expectedEnergy, actual[channel], 0.017,
                                                    "Metal absorption must remain absorption; gamma conversion must not darken F0 twice");
                                            if (environment == 0 && roughness < 0.02f) {
                                                assertTrue(actual[channel] >= 0.04 * f0[channel]
                                                        && actual[channel] <= 0.08 * f0[channel],
                                                        "Polished metal keeps a small light-dependent fallback without a milky base coat");
                                            }
                                        }
                                        if (coverage == 1 && environment == 0) {
                                            assertEquals(Math.pow(surfaceAfterLighting[channel], 2.2), actual[channel], 0.0004,
                                                    "Black reflected surroundings must not erase the existing direct highlight");
                                        }
                                    }
                                }
                            }
                        }
                      }
                    }
                }
                // Check the actual SNORM8 endpoint that a float-only test cannot reproduce.
                gpu.target(g4, 2, 4); GL20.glUseProgram(transport);
                gpu.sampler(transport, "colortex6", 0, g6); uniform(transport, "grazing", 1);
                gpu.draw();
                float[] grazing = gpu.sampleStored(g4, output);
                assertTrue(grazing[3] < 1 && grazing[3] * grazing[3] > 0.95,
                        "Fully grazing silver must retain reflection rather than round to CU's error sentinel");
            }
    }

    static boolean driverConfigured() { return InstalledShaderTestSupport.driverValidationEnabled(); }

    private Fixture fixture(ZipFile zip, String dimension) throws Exception {
        return fixture(zip, dimension, Map.of());
    }

    private Fixture fixture(ZipFile zip, String dimension, Map<String, String> overrides) throws Exception {
        HighPolishShaderAdapter.beginShaderLoad(true, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        var sources = new HashMap<String, String>();
        var fragments = new HashMap<String, String>();
        for (var entry : java.util.Collections.list(zip.entries())) {
            String prefix = "shaders/" + dimension + "/";
            if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(".fsh")) continue;
            String file = entry.getName().substring(prefix.length());
            String program = file.substring(0, file.length() - 4);
            String vertex = InstalledShaderTestSupport.source(zip, dimension, program + ".vsh", InstalledShaderTestSupport.Options.DEFAULT, List.of(), overrides);
            String fragment = InstalledShaderTestSupport.source(zip, dimension, file, InstalledShaderTestSupport.Options.DEFAULT, List.of(), overrides);
            var ctm = ComplementaryUnboundDev5SourceTransformer.transformProgram(program, vertex, fragment,
                    ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), true);
            var metal = MetallicShaderAdapter.adapt(program, ctm.vertexText(),
                    HighPolishShaderAdapter.adaptFragment(program, ctm.fragmentText(), true).text(), true);
            if (MetallicShaderAdapter.PROGRAMS.contains(program)) assertTrue(metal.changed(), program + ": " + metal.status());
            sources.put("/" + dimension + "/" + program + ".vsh", metal.vertex());
            sources.put("/" + dimension + "/" + file, metal.fragment());
            fragments.put(program, metal.fragment());
        }
        var options = new ShaderPackOptions(new IncludeGraph(emptyPack, ImmutableList.of()), Map.of());
        var properties = new ShaderProperties("", options, List.of());
        // ProgramSet only asks its owner about tessellation. Supply that feature set without invoking
        // ShaderPack's unrelated client/version/graphics bootstrap; ProgramSet itself is fully constructed.
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var owner = (ShaderPack) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(ShaderPack.class);
        var features = ShaderPack.class.getDeclaredField("activeFeatures");
        features.setAccessible(true);
        features.set(owner, Set.of());
        var programs = new ProgramSet(AbsolutePackPath.fromAbsolutePath("/" + dimension),
                path -> sources.get(path.getPathString()), properties, owner);
        return new Fixture(programs, fragments);
    }

    private record Fixture(ProgramSet programs, Map<String, String> fragments) { }
    private interface CheckedAction { void run() throws Exception; }

    private static double square(double x) { return x * x; }

    private static double nativeStoneFresnel(boolean worldSpace, boolean mirror, double smoothness, double grazing) {
        if (mirror) return (grazing * grazing * grazing * 0.5 + 0.5) * smoothness;
        double factor = (1 - smoothness) * 0.7;
        double angle = worldSpace ? grazing * 0.7 + 0.3 : Math.max(grazing - factor, 0) / (1 - factor);
        double intensity = 8.0 / 240.0;
        return (square(angle) * (1 - intensity) + (angle * 0.75 + 0.25) * intensity) * smoothness * (2 - smoothness);
    }

    /** Lift CU's actual selected predecessor, including its smoothness-dependent SSR knee. */
    private static String nativeFresnelCurve(String source, boolean worldSpace) {
        int begin = source.indexOf(worldSpace ? "fresnelM = fresnel * 0.7" : "float fresnelFactor = (1.0 - smoothnessD)");
        assertTrue(begin >= 0, "Requested reflection option must select the expected real CU predecessor");
        int end = source.indexOf("fresnelM = fresnelM * sqrt1(smoothnessD)", begin);
        assertTrue(end > begin);
        return source.substring(begin, source.indexOf(';', end) + 1);
    }

    private static void withConfig(CheckedAction action) throws Exception {
        var field = Iris.class.getDeclaredField("irisConfig");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, new IrisConfig(Path.of("unused-material-contract.properties")));
            action.run();
        } finally {
            field.set(null, previous);
            HighPolishShaderAdapter.beginShaderLoad(false, false);
        }
    }

    private static String statement(String source, String start) {
        int begin = source.indexOf(start);
        assertTrue(begin >= 0, "Missing production statement: " + start);
        return source.substring(begin, source.indexOf(';', begin) + 1);
    }

    private static String blockAfter(String source, String marker) {
        int markerAt = source.indexOf(marker);
        assertTrue(markerAt >= 0, marker);
        return blockAt(source, source.indexOf("if (", markerAt), false);
    }

    private static String blockAt(String source, int begin, boolean includeElse) {
        assertTrue(begin >= 0, "Missing production block");
        int brace = source.indexOf('{', begin), depth = 0;
        for (int i = brace; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) {
                int end = i + 1;
                if (includeElse) {
                    int next = end;
                    while (Character.isWhitespace(source.charAt(next))) next++;
                    assertTrue(source.startsWith("else", next));
                    end = next + blockAt(source, next, false).length();
                }
                return source.substring(begin, end);
            }
        }
        throw new AssertionError("Unclosed production block");
    }

    private static void uniform(int program, String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
    }

    private static void vector(int program, String name, float[] value) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, name), value[0], value[1], value[2]);
    }

    private static final class TransportGpu implements AutoCloseable {
        private final java.util.List<Integer> textures = new java.util.ArrayList<>();
        private final java.util.List<Integer> programs = new java.util.ArrayList<>();
        private final int framebuffer = GL30.glGenFramebuffers(), vao = GL30.glGenVertexArrays();
        private int attached = -1;
        private int readback;

        TransportGpu() {
            GL30.glBindVertexArray(vao);
            GL11.glViewport(0, 0, 1, 1);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_DITHER);
        }

        int texture(InternalTextureFormat format) {
            int texture = GL11.glGenTextures();
            textures.add(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format.getGlFormat(), 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            return texture;
        }

        int program(String fragment) {
            String vertex = "#version 330 compatibility\nvoid main() { vec2 p[3] = vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3)); gl_Position=vec4(p[gl_VertexID],0,1); }";
            int v = shader(GL20.GL_VERTEX_SHADER, vertex), f = shader(GL20.GL_FRAGMENT_SHADER, "#version 330 compatibility\n" + fragment);
            int program = GL20.glCreateProgram();
            programs.add(program);
            GL20.glAttachShader(program, v); GL20.glAttachShader(program, f); GL20.glLinkProgram(program);
            GL20.glDeleteShader(v); GL20.glDeleteShader(f);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(program));
            return program;
        }

        void target(int texture, int slot, int buffer) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            if (attached >= 0) GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + attached, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + buffer, GL11.GL_TEXTURE_2D, texture, 0);
            attached = buffer;
            int[] draw = new int[slot + 1];
            draw[slot] = GL30.GL_COLOR_ATTACHMENT0 + buffer;
            GL20.glDrawBuffers(draw);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + buffer);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        }

        void sampler(int program, String name, int unit, int texture) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit);
        }

        float[] draw() {
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            var pixel = BufferUtils.createFloatBuffer(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            float[] result = new float[4]; pixel.get(result);
            for (float value : result) assertTrue(Float.isFinite(value));
            return result;
        }

        /** Legacy glReadPixels conversion of SNORM attachments differs from shader texture sampling. */
        float[] sampleStored(int texture, int floatingTarget) {
            if (readback == 0) readback = program("uniform sampler2D stored; void main() { gl_FragData[0] = texelFetch(stored, ivec2(0), 0); }");
            target(floatingTarget, 0, 0);
            GL20.glUseProgram(readback);
            sampler(readback, "stored", 3, texture);
            return draw();
        }

        private static int shader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
                String message = GL20.glGetShaderInfoLog(shader); GL20.glDeleteShader(shader); fail(message + "\n" + source);
            }
            return shader;
        }

        @Override public void close() {
            GL20.glUseProgram(0); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0); GL30.glBindVertexArray(0);
            programs.forEach(GL20::glDeleteProgram); textures.forEach(GL11::glDeleteTextures);
            GL30.glDeleteFramebuffers(framebuffer); GL30.glDeleteVertexArrays(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }
}
