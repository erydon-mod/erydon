package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production bridge against shuffled atlas tiles and counts actual lookup reads. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class ErydonCuPomNeighbourCacheTest {
    private static final int ATLAS = 1024;
    private static final float SPAN = 9.6f / ATLAS;
    private static final float[][] REPEATED = {
            {1.05f, .1f}, {1.15f, .2f}, {1.25f, .3f}, {1.35f, .4f},
            {1.45f, .5f}, {1.55f, .6f}, {1.65f, .7f}, {1.75f, .8f}
    };
    private static final List<ErydonCuPomLookupLayout.SpriteBounds> BOUNDS = bounds();

    static boolean configured() { return InstalledShaderTestSupport.driverValidationEnabled(); }

    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }

    @Test void preservesUvsFallbacksAndInvocationLifetimeWhileAvoidingRepeatedTileReads() {
        InstalledShaderTestSupport.ensureContext();
        byte[] lookup = ErydonCuPomLookupLayout.encode(ATLAS, ATLAS, List.of(BOUNDS)).rgba();
        try (Gpu gpu = new Gpu()) {
            gpu.upload(lookup);
            gpu.basis(new float[]{1, 0, 0}, new float[]{0, 0, 1}, new float[]{0, 1, 0});
            assertSample(gpu, 14, -1, REPEATED, true, 10);
            // Returning to a previous tile must refresh the one-entry cache; an interior sample stays native.
            float[][] changing = {{1.25f, .4f}, {2.25f, .4f}, {-.75f, .4f}, {1.75f, .4f}, {.25f, .4f}};
            assertSample(gpu, 14, -1, changing, true, 16);
            // Cache contents must never survive another fragment invocation or a changed source sprite.
            assertSample(gpu, 9, -1, REPEATED, true, 10);
            assertSample(gpu, 14, 14, REPEATED, true, 3);

            float[][][] bases = {
                    {{1, 0, 0}, {0, 0, -1}, {0, -1, 0}},
                    {{-1, 0, 0}, {0, -1, 0}, {0, 0, -1}},
                    {{1, 0, 0}, {0, -1, 0}, {0, 0, 1}},
                    {{0, 0, 1}, {0, -1, 0}, {-1, 0, 0}},
                    {{0, 0, -1}, {0, -1, 0}, {1, 0, 0}}
            };
            for (float[][] basis : bases) {
                gpu.basis(basis[0], basis[1], basis[2]);
                assertSample(gpu, 14, -1, REPEATED, true, 10);
            }
            // Nonzero collinear axes deterministically fail the orthogonal-basis guard.
            gpu.basis(new float[]{1, 0, 0}, new float[]{1, 0, 0}, new float[]{0, 1, 0});
            assertSample(gpu, 14, -1, REPEATED, false, 8);
            gpu.basis(new float[]{1, 0, 0}, new float[]{0, 0, 1}, new float[]{0, 1, 0});

            byte[] malformed = lookup.clone();
            malformed[0] = 0;
            gpu.upload(malformed);
            assertSample(gpu, 14, -1, REPEATED, false, 1);

            // A valid current record with an out-of-range neighbour retains ordinary wrapped UVs.
            byte[] truncated = lookup.clone();
            truncated[8] = 15;
            truncated[9] = 0;
            gpu.upload(truncated);
            assertSample(gpu, 14, -1, REPEATED, false, 8);

            // An unknown sprite and an entirely interior ray never start the neighbour cache.
            gpu.upload(lookup);
            assertSample(gpu, -1, -1, REPEATED, false, 5);
            assertSample(gpu, 14, -1, new float[][]{{.25f, .4f}, {.75f, .6f}}, false, 0);
        }
    }

    @Test void finiteUvRecoversAfterRepeatedKeysAndUncacheableIntermediateTiles() {
        InstalledShaderTestSupport.ensureContext();
        float[] a = {1.25f, .4f};
        float[] b = {2.25f, .4f};
        float[][][] sequences = {
                {a, a, a}, {a, b, a}, {a, {.25f, .4f}, a},
                // These calls can replace the shared midpoint without being cacheable keys.
                {a, {16777216f, .25f}, a}, {a, {-33554432f, 7.25f}, a},
                {a, {Float.NaN, 1.25f}, a}, {a, {Float.POSITIVE_INFINITY, -1.25f}, a}
        };
        try (Gpu gpu = new Gpu()) {
            gpu.upload(ErydonCuPomLookupLayout.encode(ATLAS, ATLAS, List.of(BOUNDS)).rgba());
            gpu.basis(new float[]{1, 0, 0}, new float[]{0, 0, 1}, new float[]{0, 1, 0});
            for (int phase : new int[]{0, 14, 35}) {
                float[] expected = gpu.sample(phase, phase, new float[][]{a}, true);
                for (float[][] sequence : sequences) {
                    // The non-finite intermediate sample is undefined. Only its following
                    // finite result is asserted, against a fresh invocation with the same A.
                    float[] actual = gpu.sample(phase, phase, sequence, true);
                    for (int channel = 0; channel < 2; channel++) {
                        assertTrue(Float.isFinite(actual[channel]), "Finite UV after an intermediate miss");
                        assertEquals(Float.floatToRawIntBits(expected[channel]),
                                Float.floatToRawIntBits(actual[channel]), "Fresh-invocation UV after cache reuse");
                    }
                }
            }
        }
    }

    private static void assertSample(Gpu gpu, int phase, int vertexRecord, float[][] samples,
                                     boolean mapped, int reads) {
        float[] actual = gpu.sample(phase, vertexRecord, samples);
        double expectedU = 0, expectedV = 0;
        var source = phase < 0 ? new ErydonCuPomLookupLayout.SpriteBounds(512, 512, 16, 16) : BOUNDS.get(phase);
        for (float[] uv : samples) {
            boolean crossed = uv[0] < 0 || uv[0] >= 1 || uv[1] < 0 || uv[1] >= 1;
            double u = uv[0] - Math.floor(uv[0]), v = uv[1] - Math.floor(uv[1]);
            if (mapped && crossed) {
                int target = Math.floorMod(phase / 6 + (int) Math.floor(uv[1]), 6) * 6
                        + Math.floorMod(phase % 6 + (int) Math.floor(uv[0]), 6);
                var bounds = BOUNDS.get(target);
                expectedU += (bounds.x() + 8.0) / ATLAS + (u - .5) * SPAN;
                expectedV += (bounds.y() + 8.0) / ATLAS + (v - .5) * SPAN;
            } else {
                expectedU += (source.x() + 3.2) / ATLAS + u * SPAN;
                expectedV += (source.y() + 3.2) / ATLAS + v * SPAN;
            }
        }
        assertEquals(expectedU, actual[0], .000003, "Transported U across every ray sample");
        assertEquals(expectedV, actual[1], .000003, "Transported V across every ray sample");
        assertEquals(reads, actual[2], 0, "Executed lookup reads, including initial validation");
    }

    private static List<ErydonCuPomLookupLayout.SpriteBounds> bounds() {
        List<ErydonCuPomLookupLayout.SpriteBounds> result = new ArrayList<>();
        for (int phase = 0; phase < 36; phase++) {
            int shuffled = phase * 7 % 36;
            result.add(new ErydonCuPomLookupLayout.SpriteBounds(shuffled % 6 * 32, shuffled / 6 * 32, 16, 16));
        }
        return List.copyOf(result);
    }

    private static final class Gpu implements AutoCloseable {
        private final int program, vertex, fragment, vao, framebuffer, target, texture;

        Gpu() {
            String vertexSource = """
                    #version 330 core
                    void main() {
                        vec2 positions[3] = vec2[3](vec2(-1, -1), vec2(3, -1), vec2(-1, 3));
                        gl_Position = vec4(positions[gl_VertexID], 0, 1);
                    }
                    """;
            // Only instrument the byte-reader entry; UV calculations are the unchanged production source.
            String helper = ErydonCuPomShaderBridge.fragmentSource()
                    .replace("texture2D(", "texture(")
                    .replace("vec4 erydonCtmPomReadBytes(float linearIndex) {",
                            "vec4 erydonCtmPomReadBytes(float linearIndex) { erydonTestReads++;");
            String fragmentSource = """
                    #version 330 core
                    uniform vec4 vTexCoordAM;
                    uniform ivec2 atlasSize;
                    uniform int erydonPomRecord;
                    uniform mat4 gbufferModelViewInverse;
                    uniform vec3 tangent, binormal, normal;
                    uniform int testCount;
                    uniform bool testLastOnly;
                    uniform vec2 testUv[16];
                    int erydonTestReads = 0;
                    out vec4 result;
                    """ + helper + """
                    void main() {
                        vec2 total = vec2(0);
                        for (int i = 0; i < testCount; i++) {
                            vec2 current = erydonCtmPomAtlasUv(testUv[i]);
                            if (testLastOnly) total = current;
                            else total += current;
                        }
                        result = vec4(total, float(erydonTestReads), 1);
                    }
                    """;
            vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
            fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(program));
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            texture = GL11.glGenTextures();
            target = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, 1, 1);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL20.glUseProgram(program);
            GL20.glUniform1i(uniform("erydonCtmPomLookup"), 0);
            GL20.glUniform2i(uniform("atlasSize"), ATLAS, ATLAS);
            GL20.glUniformMatrix4fv(uniform("gbufferModelViewInverse"), false,
                    new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        }

        void upload(byte[] rgba) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer bytes = BufferUtils.createByteBuffer(rgba.length);
            bytes.put(rgba).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1024, 1057, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        }

        void basis(float[] tangent, float[] binormal, float[] normal) {
            GL20.glUniform3fv(uniform("tangent"), tangent);
            GL20.glUniform3fv(uniform("binormal"), binormal);
            GL20.glUniform3fv(uniform("normal"), normal);
        }

        float[] sample(int phase, int vertexRecord, float[][] samples) {
            return sample(phase, vertexRecord, samples, false);
        }

        float[] sample(int phase, int vertexRecord, float[][] samples, boolean lastOnly) {
            var source = phase < 0 ? new ErydonCuPomLookupLayout.SpriteBounds(512, 512, 16, 16) : BOUNDS.get(phase);
            GL20.glUniform4f(uniform("vTexCoordAM"), (source.x() + 3.2f) / ATLAS, (source.y() + 3.2f) / ATLAS, SPAN, SPAN);
            GL20.glUniform1i(uniform("erydonPomRecord"), vertexRecord);
            GL20.glUniform1i(uniform("testCount"), samples.length);
            GL20.glUniform1i(uniform("testLastOnly"), lastOnly ? 1 : 0);
            FloatBuffer coordinates = BufferUtils.createFloatBuffer(samples.length * 2);
            for (float[] uv : samples) coordinates.put(uv);
            coordinates.flip();
            GL20.glUniform2fv(uniform("testUv[0]"), coordinates);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            float[] result = new float[4];
            pixel.get(result);
            return result;
        }

        private int uniform(String name) { return GL20.glGetUniformLocation(program, name); }

        @Override public void close() {
            GL20.glUseProgram(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glBindVertexArray(0);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(target);
            GL11.glDeleteTextures(texture);
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);
        }

        private static int compile(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(shader));
            return shader;
        }
    }
}
