package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Renders the production adapter's GLSL against independent color-energy expectations. */
@Execution(ExecutionMode.SAME_THREAD)
class GlazingReflectionBlendTest {
    // Keep CU's material, tint and final blend anchors intact: the adapter, rather
    // than this fixture, supplies every glazing-specific calculation and guard.
    private static final String WATER = """
            #version 330 compatibility
            uniform int mat;
            uniform vec4 testSpecular;
            uniform float testAlpha, testFresnel, testSkyFade, testFogAlpha;
            uniform int testOutput;
            float pow2(float x) { return x * x; }
            float pow3(float x) { return x * x * x; }
            float min1(float x) { return min(x, 1.0); }
            float GetCustomEmission(vec4 specularMap, vec2 uv) { return 0.0; }
            void GetCustomMaterials(inout vec4 color, out float smoothnessD, inout float materialMask) {
                vec4 specularMap = testSpecular;
                smoothnessD = pow2(specularMap.r);
                float emission;
                vec2 texCoordM = vec2(0.0);
                emission = GetCustomEmission(specularMap, texCoordM);
            }
            void main() {
                vec4 color = vec4(1.0, 0.0, 0.0, testAlpha);
                float fresnel = testFresnel;
                float fresnelM = pow3(fresnel);
                float smoothnessD, materialMaskPh = 0.0;
                float reflectMult = 0.0;
                GetCustomMaterials(color, smoothnessD, materialMaskPh);
                reflectMult = smoothnessD;
                float lViewPos = 0.0, far = 256.0;
                vec4 translucentMult = vec4(mix(vec3(0.666), color.rgb * (1.0 - pow2(pow2(color.a))), color.a), 1.0);
                translucentMult.rgb = mix(translucentMult.rgb, vec3(1.0), min1(pow2(pow2(lViewPos / far))));
                fresnelM = (fresnelM * 0.85 + 0.15) * reflectMult;
                vec4 reflection = vec4(0.0, 1.0, 0.0, 1.0);
                color.rgb = mix(color.rgb, reflection.rgb, fresnelM);
                float prevAlpha = color.a;
                color.a = 1.0;
                float fogAlpha = testFogAlpha;
                color.a = prevAlpha * (1.0 - testSkyFade);
                if (testOutput == 1) {
                    gl_FragData[0] = vec4(fresnelM, color.a, fresnelM * color.a * fogAlpha, translucentMult.r);
                } else if (testOutput == 2) {
                    gl_FragData[0] = vec4(vec3(0.0, 0.0, 1.0), sqrt(fresnelM * color.a * fogAlpha));
                } else {
                    gl_FragData[0] = color;
                }
            }
            """;

    static boolean driverConfigured() { return InstalledShaderTestSupport.driverValidationEnabled(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }
    @AfterEach void resetFinishState() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void productionFixtureParsesAndOnlyTheAdapterAddsTheGlazingResponse() throws Exception {
        assertFalse(WATER.contains("erydonGlazingReflection"));
        String fragment = adapted();
        assertTrue(fragment.contains("materialMask = -1.0"));
        assertTrue(fragment.contains("(0.5 + (1.0 - 0.5) * pow(fresnel, 5.0))"));
        assertFalse(fragment.contains("texture2D("), "The new glass response needs no extra texture read");
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s*#\\s*(?:if|ifdef|ifndef|else|elif|endif|define|undef|include)\\b")
                .matcher(fragment).find(), "Iris receives adapted programs after preprocessing");
        HighPolishShaderPackTest.parse(fragment);
    }

    @Test @EnabledIf("driverConfigured")
    void actualAdaptedGlazingPreservesTintReflectionAndTransmissionEnergy() {
        InstalledShaderTestSupport.ensureContext();
        int cases = 0;
        try (var gpu = new PixelGpu()) {
            int program = gpu.program(adapted());
            for (float alpha : new float[]{20.0f / 255, 150.0f / 255}) {
                for (double angle : new double[]{0, Math.PI / 3, Math.PI / 2}) {
                    float grazing = (float) (1 - Math.cos(angle));
                    String label = "Fixed glazing/alpha=" + alpha + "/angle=" + angle;
                    gpu.inputs(program, HighPolishShaderAdapter.GLAZING_ID, alpha, grazing, 1, 0);
                    double reflection = Math.min(.99, .5 + .5 * Math.pow(grazing, 5));
                    float[] color = gpu.draw(program, 0, true);
                    assertEnergy(color, alpha * (1 - reflection), reflection,
                            (1 - alpha) * (1 - reflection), label);
                    float[] diagnostic = gpu.draw(program, 1, false);
                    assertEquals(alpha * (1 - reflection) + reflection, diagnostic[1], 0.000003, label);
                    assertEquals(reflection, diagnostic[2], 0.000003, label + ": WSR energy transport");
                    assertEquals(originalTintProxy(alpha), diagnostic[3], 0.000003,
                            label + ": volumetric tint must remain unchanged");
                    assertTrue(diagnostic[0] >= 0 && diagnostic[0] <= 1, label + ": bounded surface blend");
                    cases++;
                }
            }
        }
        assertEquals(6, cases);
        System.out.println("Glazing blend readback: " + cases + " crystal/colored angle combinations at fixed 50% reflection");
    }

    @Test @EnabledIf("driverConfigured")
    void holesOpaqueFramesRoughPixelsAndOtherMaterialsKeepTheirNativeBlend() {
        InstalledShaderTestSupport.ensureContext();
        try (var gpu = new PixelGpu()) {
            int program = gpu.program(adapted());
            for (var sample : List.of(
                    new NativeSample("transparent edge", HighPolishShaderAdapter.GLAZING_ID, 0, 1),
                    new NativeSample("opaque smooth frame", HighPolishShaderAdapter.MIRROR_NORMAL_FRAME_ID, 1, 1),
                    new NativeSample("opaque lead", HighPolishShaderAdapter.GLAZING_ID, 1, 0),
                    new NativeSample("rough translucent frame", HighPolishShaderAdapter.MIRROR_NORMAL_FRAME_ID, .5f, .5f),
                    new NativeSample("unmapped glass", 32008, 20.0f / 255, 1),
                    new NativeSample("water", 32000, .4f, 1))) {
                gpu.inputs(program, sample.material(), sample.alpha(), 0, sample.smoothnessRed(), 0);
                double nativeWeight = .15 * sample.smoothnessRed() * sample.smoothnessRed();
                assertEnergy(gpu.draw(program, 0, true), sample.alpha() * (1 - nativeWeight),
                        sample.alpha() * nativeWeight, 1 - sample.alpha(), sample.name());
                float[] diagnostic = gpu.draw(program, 1, false);
                assertEquals(sample.alpha(), diagnostic[1], 0.000003, sample.name() + ": original alpha");
                assertEquals(originalTintProxy(sample.alpha()), diagnostic[3], 0.000003, sample.name());
            }
            // The metallic outward coating remains its separate pre-existing 90% response.
            gpu.inputs(program, HighPolishShaderAdapter.MIRROR_POLISHED_FRAME_ID, .5f, 0, 1, 230.0f / 255);
            assertEnergy(gpu.draw(program, 0, true), .05, .45, .5, "two-way metallic coating");
            float[] coating = gpu.draw(program, 1, false);
            assertEquals(.9, coating[0], .000003);
            assertEquals(.5, coating[1], .000003, "Coating alpha must not receive the dielectric adjustment");
            gpu.inputs(program, HighPolishShaderAdapter.MIRROR_NORMAL_FRAME_ID, 20.0f / 255, 0, 1, 0);
            assertEnergy(gpu.draw(program, 0, true), 10.0 / 255, .5, 235.0 / 510,
                    "inner dielectric window glass uses the glazing control");
        }
    }

    @Test @EnabledIf("driverConfigured")
    void actualSnormStorageRetainsGrazingReflectionAndNativeFogAttenuation() {
        InstalledShaderTestSupport.ensureContext();
        try (var gpu = new PixelGpu()) {
            int program = gpu.program(adapted());
            for (float alpha : new float[]{20.0f / 255, 150.0f / 255}) {
                gpu.inputs(program, HighPolishShaderAdapter.GLAZING_ID, alpha, 1, 1, 0);
                float stored = gpu.storedReflection(program);
                assertTrue(stored < 1 && stored * stored > .98,
                        "Grazing weight must survive RGBA8_SNORM without CU's error sentinel");
                gpu.fog(program, .2f, .7f);
                float[] diagnostic = gpu.draw(program, 1, false);
                assertEquals(.99 * .8 * .7, diagnostic[2], .000003,
                        "CU's sky fade and fog multiply reflection once each");
                float fogStored = gpu.storedReflection(program);
                assertEquals(diagnostic[2], fogStored * fogStored, .012,
                        "Real SNORM transport preserves the attenuated reflection within quantization");
            }
        }
    }

    private record NativeSample(String name, int material, float alpha, float smoothnessRed) { }

    private static String adapted() {
        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.COMPLEMENTARY,
                true, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER);
        assertTrue(result.changed(), result.status());
        return result.text();
    }

    private static double originalTintProxy(double alpha) {
        return .666 * (1 - alpha) + (1 - Math.pow(alpha, 4)) * alpha;
    }

    private static void assertEnergy(float[] color, double tint, double reflection, double transmission, String label) {
        assertEquals(tint, color[0], .000004, label + ": pane tint contribution");
        assertEquals(reflection, color[1], .000004, label + ": reflected environment contribution");
        assertEquals(transmission, color[2], .000004, label + ": transmitted background contribution");
        assertEquals(1, color[0] + color[1] + color[2], .000006, label + ": conserved color energy");
    }

    /** Single-pixel floating target plus CU's actual signed-normalized reflection format. */
    private static final class PixelGpu implements AutoCloseable {
        private final List<Integer> programs = new ArrayList<>(), textures = new ArrayList<>();
        private final int framebuffer = GL30.glGenFramebuffers(), vao = GL30.glGenVertexArrays();
        private final int floating = texture(GL30.GL_RGBA32F), snorm = texture(GL31.GL_RGBA8_SNORM);
        private int readback;

        PixelGpu() {
            GL30.glBindVertexArray(vao);
            GL11.glViewport(0, 0, 1, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        }

        int program(String fragment) {
            String vertex = "#version 330 compatibility\nvoid main() { vec2 p[3] = vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3)); gl_Position=vec4(p[gl_VertexID],0,1); }";
            int v = shader(GL20.GL_VERTEX_SHADER, vertex), f = shader(GL20.GL_FRAGMENT_SHADER, fragment);
            int program = GL20.glCreateProgram();
            programs.add(program);
            GL20.glAttachShader(program, v);
            GL20.glAttachShader(program, f);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(v);
            GL20.glDeleteShader(f);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(program));
            return program;
        }

        void inputs(int program, int material, float alpha, float grazing, float red, float green) {
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "mat"), material);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "testSpecular"), red, green, 0, 1);
            scalar(program, "testAlpha", alpha);
            scalar(program, "testFresnel", grazing);
            fog(program, 0, 1);
        }

        void fog(int program, float skyFade, float fogAlpha) {
            GL20.glUseProgram(program);
            scalar(program, "testSkyFade", skyFade);
            scalar(program, "testFogAlpha", fogAlpha);
        }

        float[] draw(int program, int output, boolean blend) {
            target(floating);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "testOutput"), output);
            GL11.glClearColor(0, 0, 1, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            if (blend) {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else GL11.glDisable(GL11.GL_BLEND);
            return drawPixel();
        }

        float storedReflection(int program) {
            target(snorm);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "testOutput"), 2);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            if (readback == 0) readback = program("""
                    #version 330 compatibility
                    uniform sampler2D stored;
                    void main() { gl_FragData[0] = texelFetch(stored, ivec2(0), 0); }
                    """);
            // Sampling, rather than legacy glReadPixels conversion, observes the
            // signed-normalized value used by CU's composite pass.
            target(floating);
            GL20.glUseProgram(readback);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snorm);
            GL20.glUniform1i(GL20.glGetUniformLocation(readback, "stored"), 0);
            return drawPixel()[3];
        }

        private int texture(int format) {
            int texture = GL11.glGenTextures();
            textures.add(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            return texture;
        }

        private void target(int texture) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        }

        private float[] drawPixel() {
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            var pixel = BufferUtils.createFloatBuffer(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            float[] result = new float[4];
            pixel.get(result);
            for (float value : result) assertTrue(Float.isFinite(value), "Finite GPU result");
            return result;
        }

        private static void scalar(int program, String name, float value) {
            GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
        }

        private static int shader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
                String error = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                fail(error + "\n" + source);
            }
            return shader;
        }

        @Override public void close() {
            GL20.glUseProgram(0);
            GL11.glDisable(GL11.GL_BLEND);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glBindVertexArray(0);
            programs.forEach(GL20::glDeleteProgram);
            textures.forEach(GL11::glDeleteTextures);
            GL30.glDeleteFramebuffers(framebuffer);
            GL30.glDeleteVertexArrays(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }
}
