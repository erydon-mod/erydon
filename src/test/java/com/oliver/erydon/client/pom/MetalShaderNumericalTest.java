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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Numerical offscreen proof of the actual shader helpers, not a visual acceptance test. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class MetalShaderNumericalTest {
    static boolean configured() { return InstalledShaderTestSupport.driverValidationEnabled(); }

    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }

    @Test void realGpuReadsCpuCoveragePyramidAndReconstructsOpposedSubpixelBevels() throws Exception {
        InstalledShaderTestSupport.ensureContext();
        byte[] line = new byte[64 * 64];
        for (int y = 0; y < 64; y++) line[y * 64 + 31] = (byte) 255;
        byte[] solid = new byte[16 * 16];
        Arrays.fill(solid, (byte) 255);
        var lookup = MetallicLookupLayout.encode(128, 64, List.of(
                new MetallicLookupLayout.SpriteData(0, 0, 64, 64, 2, 1, 0, false, line, 0xff2f89dd),
                new MetallicLookupLayout.SpriteData(64, 0, 16, 16, 3, 2, 0, false, solid),
                new MetallicLookupLayout.SpriteData(80, 0, 16, 16, 3, 0, 0, false, solid)));

        int program = 0, vertex = 0, fragment = 0, vao = 0, framebuffer = 0, target = 0, texture = 0;
        try {
            String helper = resource("erydon_metal_lookup.glsl");
            String vertexSource = "#version 330 core\n" + helper + """
                    uniform vec2 testUv;
                    flat out ivec4 erydonMetalBounds;
                    flat out ivec4 erydonMetalInfo;
                    flat out ivec2 erydonMetalAtlas;
                    flat out vec3 erydonMetalAlbedoMean;
                    void main() {
                        ivec4 header = erydonMetalBytes(0);
                        erydonMetalAtlas = ivec2(erydonMetalU16(header.xy), erydonMetalU16(header.zw));
                        erydonReadMetal(testUv, erydonMetalAtlas, erydonMetalBounds, erydonMetalInfo, erydonMetalAlbedoMean);
                        vec2 positions[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
                        gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
                    }
                    """;
            String fragmentSource = "#version 330 core\n" + helper + resource("erydon_metal_fragment.glsl") + """
                    uniform vec2 testUv;
                    uniform vec2 testDx;
                    uniform vec2 testDy;
                    uniform int testMode;
                    uniform vec3 testSampledAlbedo;
                    uniform vec3 testTint;
                    uniform float testCoverage;
                    layout(location = 0) out vec4 result;
                    void main() {
                        result = vec4(0.0);
                        if (erydonMetalInfo.x == 0) return;
                        float coverage = erydonMetalMask(testUv, erydonMetalBounds, erydonMetalInfo, testDx, testDy);
                        if (testMode == 1) {
                            vec2 bevel = erydonMetalBevel(testUv, erydonMetalBounds, erydonMetalInfo, 0.0);
                            result = vec4(bevel, coverage, 1.0);
                        } else if (testMode == 2) {
                            result = vec4(erydonMetalAlbedoMean, 1.0);
                        } else if (testMode == 3) {
                            result = vec4(erydonMetalAlbedo(testSampledAlbedo, erydonMetalAlbedoMean,
                                    vec3(0.72, 0.42, 0.16), testCoverage, testTint), 1.0);
                        } else {
                            result = vec4(coverage, float(erydonMetalInfo.x), float(erydonMetalInfo.y), 1.0);
                        }
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
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer bytes = BufferUtils.createByteBuffer(lookup.rgba().length);
            bytes.put(lookup.rgba()).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, lookup.width(), lookup.height(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);

            target = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
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
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "erydonMetalLookup"), 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

            float[] close = sample(program, 31.5f, 32.5f, 0.1f, 0, 0, 0.1f, 0);
            assertArrayEquals(new float[]{1, 2, 1, 1}, close, 0.00001f);

            // Mip 2 has exactly a quarter-metal footprint, unlike categorical PBR mips.
            float[] minified = sample(program, 30, 30, 4, 0, 0, 4, 0);
            assertEquals(64.0f / 255.0f, minified[0], 0.00001f);
            float diagonal = (float) Math.sqrt(8.0);
            float[] rotated = sample(program, 30, 30, diagonal, diagonal, -diagonal, diagonal, 0);
            assertEquals(minified[0], rotated[0], 0.00001f, "Rotating an isotropic footprint must not change its mip");

            float[] left = sample(program, 31.05f, 32.5f, 0, 0, 0, 0, 1);
            float[] right = sample(program, 31.95f, 32.5f, 0, 0, 0, 0, 1);
            assertTrue(left[0] < -0.1f && right[0] > 0.1f, "One-texel strips need distinct left/right slopes");
            assertEquals(-left[0], right[0], 0.00002f);
            assertEquals(0, left[1], 0.00001f);
            assertEquals(1, left[2], 0.00001f, "Bevel shaping must not reduce the source silhouette");

            assertArrayEquals(new float[]{1, 3, 2, 1}, sample(program, 72, 8, 16, 0, 0, 16, 0), 0.00001f);
            assertArrayEquals(new float[4], sample(program, 104, 8, 0, 0, 0, 0, 0), 0.00001f,
                    "Unknown sprites stay outside the metal path");
            assertArrayEquals(new float[4], sample(program, 88, 8, 0, 0, 0, 0, 0), 0.00001f,
                    "Unknown alloys must not be shaded as silver in later passes");

            float[] mean = {221.0f / 255.0f, 137.0f / 255.0f, 47.0f / 255.0f};
            assertArrayEquals(new float[]{mean[0], mean[1], mean[2], 1},
                    sample(program, 31.5f, 32.5f, 0, 0, 0, 0, 2), 0.00001f,
                    "The fifth record texel must preserve authored metal mean RGB in channel order");
            assertArrayEquals(new float[]{230.0f / 255.0f, 230.0f / 255.0f, 230.0f / 255.0f, 1},
                    sample(program, 72, 8, 0, 0, 0, 0, 2), 0.00001f,
                    "The compatibility constructor must also write its default mean colour");

            float[] stone = {0.35f, 0.4f, 0.45f};
            float[] f0 = {0.72f, 0.42f, 0.16f};
            for (float[] tint : List.of(new float[]{1, 1, 1}, new float[]{0.6f, 0.8f, 0.9f})) {
                for (float coverage : new float[]{0, 0.25f, 1}) {
                    float[] sampled = new float[3];
                    float[] expected = new float[]{0, 0, 0, 1};
                    for (int channel = 0; channel < 3; channel++) {
                        sampled[channel] = ((1 - coverage) * stone[channel] + coverage * mean[channel]) * tint[channel];
                        expected[channel] = ((1 - coverage) * stone[channel]
                                + coverage * (float) Math.pow(f0[channel], 1.0 / 2.2)) * tint[channel];
                    }
                    GL20.glUniform3f(GL20.glGetUniformLocation(program, "testSampledAlbedo"), sampled[0], sampled[1], sampled[2]);
                    GL20.glUniform3f(GL20.glGetUniformLocation(program, "testTint"), tint[0], tint[1], tint[2]);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program, "testCoverage"), coverage);
                    float[] corrected = sample(program, 31.5f, 32.5f, 0, 0, 0, 0, 3);
                    assertArrayEquals(expected, corrected, 0.00001f,
                            "Replace only the metal contribution; retain stone and apply tint once at coverage " + coverage);
                    if (coverage == 0) {
                        for (int channel = 0; channel < 3; channel++) {
                            assertEquals(Float.floatToIntBits(sampled[channel]), Float.floatToIntBits(corrected[channel]),
                                    "Zero coverage must preserve the original sampled colour exactly");
                        }
                    }
                }
            }
        } finally {
            GL20.glUseProgram(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glBindVertexArray(0);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (target != 0) GL11.glDeleteTextures(target);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (program != 0) GL20.glDeleteProgram(program);
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static float[] sample(int program, float x, float y, float dxX, float dxY, float dyX, float dyY, int mode) {
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "testUv"), x / 128.0f, y / 64.0f);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "testDx"), dxX, dxY);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "testDy"), dyX, dyY);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "testMode"), mode);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        float[] result = new float[4];
        pixel.get(result);
        for (float value : result) assertTrue(Float.isFinite(value), "Shader result must remain finite");
        return result;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            fail(log);
        }
        return shader;
    }

    private static String resource(String file) throws Exception {
        try (var input = MetalShaderNumericalTest.class.getResourceAsStream("/assets/erydon/shaders/include/" + file)) {
            assertNotNull(input, file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
