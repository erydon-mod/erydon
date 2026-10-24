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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises production vertex bounds and fragment POM transport across cropped model elements. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class ErydonCuPomElementBoundsTest {
    private static final int ATLAS = 1024;
    private static final List<ErydonCuPomLookupLayout.SpriteBounds> BOUNDS = bounds();
    private static final float[] FULL = {0, 0, 1, 1};
    private static final float[][][] BASES = {
            {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{1, 0, 0}, {0, 0, -1}, {0, -1, 0}},
            {{-1, 0, 0}, {0, -1, 0}, {0, 0, -1}},
            {{1, 0, 0}, {0, -1, 0}, {0, 0, 1}},
            {{0, 0, 1}, {0, -1, 0}, {-1, 0, 0}},
            {{0, 0, -1}, {0, -1, 0}, {1, 0, 0}}
    };

    static boolean configured() { return InstalledShaderTestSupport.driverValidationEnabled(); }

    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }

    @Test void adjacentCroppedElementsShareOneHeightDomainAndDepthScale() {
        InstalledShaderTestSupport.ensureContext();
        // Authored Romanesque side elements include this narrow boundary inside a block face.
        float seam = 12.17776f / 16;
        float[] left = {.4f, .15f, seam - .4f, .7f};
        float[] right = {seam, .15f, 1 - seam, .7f};
        try (Gpu gpu = new Gpu()) {
            gpu.basis(BASES[0]);
            for (int phase : new int[]{0, 14, 35}) {
                assertCoplanarSeam(gpu, phase, seam, .4f, left, right);
                // arch_romanesque_top_large.json: south fronts at z=16, x=0..8 and 8..16,
                // y=10.51216..16. World projection gives two crops meeting at U=.5.
                float upperHeight = (16 - 10.51216f) / 16;
                assertCoplanarSeam(gpu, phase, .5f, .15f,
                        new float[]{0, 0, .5f, upperHeight}, new float[]{.5f, 0, .5f, upperHeight});
            }
        }
    }

    private static void assertCoplanarSeam(Gpu gpu, int phase, float seam, float v, float[] left, float[] right) {
        float[] a = gpu.sample(phase, left, seam - .01f, v, .04f, .03f);
        float[] b = gpu.sample(phase, right, seam + .01f, v, .02f, .03f);
        float[] full = gpu.sample(phase, FULL, seam - .01f, v, .04f, .03f);
        assertAtlas(phase, seam + .03f, v + .03f, a);
        assertAtlas(phase, seam + .03f, v + .03f, b);
        assertEquals(a[0], b[0], .000001, "No POM U jump at the internal element seam");
        assertEquals(a[1], b[1], .000001, "No POM V jump at the internal element seam");
        assertEquals(full[0], a[0], .000001, "Cropped and full faces use the same POM depth scale");
        assertEquals(full[1], a[1], .000001, "Cropped and full faces use the same POM depth scale");
        assertEquals(seam - .01f, a[2], .000005, "Left element retains its fullsprite U phase");
        assertEquals(seam + .01f, b[2], .000005, "Right element retains its fullsprite U phase");
        assertEquals(v, a[3], .000005, "Cropped element retains its fullsprite V phase");
    }

    @Test void trueTileEdgesKeepTheirPhaseAcrossSixFacesAndRotatedOrMirroredUvs() {
        InstalledShaderTestSupport.ensureContext();
        try (Gpu gpu = new Gpu()) {
            for (float[][] basis : BASES) {
                for (int phase : new int[]{0, 14, 35}) {
                    gpu.basis(basis);
                    assertAtlas(target(phase, 1, 0), .05f, .4f,
                            gpu.sample(phase, new float[]{.7f, .1f, .3f, .8f}, .98f, .4f, .07f, 0));
                    assertAtlas(target(phase, 0, 1), .4f, .05f,
                            gpu.sample(phase, new float[]{.1f, .7f, .8f, .3f}, .4f, .98f, 0, .07f));
                    gpu.basis(new float[][]{negative(basis[0]), basis[1], basis[2]});
                    assertAtlas(target(phase, -1, 0), .05f, .4f,
                            gpu.sample(phase, FULL, .98f, .4f, .07f, 0));
                    gpu.basis(new float[][]{basis[0], negative(basis[1]), basis[2]});
                    assertAtlas(target(phase, 0, -1), .4f, .05f,
                            gpu.sample(phase, FULL, .4f, .98f, 0, .07f));
                    gpu.basis(new float[][]{basis[1], basis[0], basis[2]});
                    assertAtlas(target(phase, 0, 1), .05f, .4f,
                            gpu.sample(phase, FULL, .98f, .4f, .07f, 0));
                }
            }
        }
    }

    @Test void unknownSpriteRetainsItsOriginalCroppedDomainAndNativeWrap() {
        InstalledShaderTestSupport.ensureContext();
        try (Gpu gpu = new Gpu()) {
            gpu.basis(BASES[0]);
            assertNativeWrap(gpu, -1);
        }
    }

    @Test void invalidLookupRetainsTheOriginalCroppedDomainForKnownSprites() {
        InstalledShaderTestSupport.ensureContext();
        try (Gpu gpu = new Gpu()) {
            gpu.basis(BASES[0]);
            byte[] malformed = ErydonCuPomLookupLayout.encode(ATLAS, ATLAS, List.of(BOUNDS)).rgba();
            malformed[0] = 0;
            gpu.upload(malformed);
            for (int phase : new int[]{0, 14, 35}) assertNativeWrap(gpu, phase);
        }
    }

    private static void assertNativeWrap(Gpu gpu, int phase) {
        var bounds = phase < 0 ? new ErydonCuPomLookupLayout.SpriteBounds(512, 512, 16, 16) : BOUNDS.get(phase);
        float[] actual = gpu.sample(phase, new float[]{.2f, .1f, .4f, .6f}, .3f, .4f, .8f, -.75f);
        // Native cropped coordinate (.25,.5) plus ray (.8,-.75) wraps to (.05,.75).
        assertEquals((bounds.x() + (.2 + .05 * .4) * bounds.width()) / ATLAS, actual[0], .000001);
        assertEquals((bounds.y() + (.1 + .75 * .6) * bounds.height()) / ATLAS, actual[1], .000001);
        assertEquals(.25f, actual[2], .00002, "Fallback U remains in the original element domain");
        assertEquals(.5f, actual[3], .00002, "Fallback V remains in the original element domain");
    }

    private static void assertAtlas(int phase, float u, float v, float[] actual) {
        var bounds = BOUNDS.get(phase);
        assertEquals((bounds.x() + u * bounds.width()) / ATLAS, actual[0], .000001, "Transported atlas U");
        assertEquals((bounds.y() + v * bounds.height()) / ATLAS, actual[1], .000001, "Transported atlas V");
    }

    private static int target(int phase, int u, int v) {
        return Math.floorMod(phase / 6 + v, 6) * 6 + Math.floorMod(phase % 6 + u, 6);
    }

    private static float[] negative(float[] value) { return new float[]{-value[0], -value[1], -value[2]}; }

    private static List<ErydonCuPomLookupLayout.SpriteBounds> bounds() {
        List<ErydonCuPomLookupLayout.SpriteBounds> result = new ArrayList<>();
        for (int phase = 0; phase < 36; phase++) {
            int shuffled = phase * 7 % 36;
            result.add(new ErydonCuPomLookupLayout.SpriteBounds(shuffled % 6 * 32, shuffled / 6 * 32, 16, 16));
        }
        return List.copyOf(result);
    }

    private static final class Gpu implements AutoCloseable {
        private final int program, vertex, fragment, vao, framebuffer, target, lookup;

        Gpu() {
            String vertexHelper = ErydonCuPomShaderBridge.vertexSource().replace("texture2DLod(", "textureLod(");
            String vertexSource = """
                    #version 330 core
                    uniform ivec2 atlasSize;
                    uniform vec4 testCropBounds;
                    uniform vec2 testAtlasUv;
                    out vec4 vTexCoordAM;
                    out vec2 testLocalUv;
                    flat out int erydonPomRecord;
                    """ + vertexHelper + """
                    void main() {
                        vec2 positions[3] = vec2[3](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
                        gl_Position = vec4(positions[gl_VertexID], 0, 1);
                        vTexCoordAM = testCropBounds;
                        vec2 signValue = (testAtlasUv - vTexCoordAM.xy) / vTexCoordAM.zw * 2.0 - 1.0;
                        vec2 radius = vTexCoordAM.zw * 0.5;
                        vec2 midpoint = vTexCoordAM.xy + radius;
                        erydonPomRecord = erydonCtmPomFindRecord(midpoint, vec2(atlasSize));
                        if (erydonPomRecord >= 0) {
                            erydonCtmPomApplyExactBounds(testAtlasUv, vec2(atlasSize), erydonPomRecord,
                                    vTexCoordAM, signValue, radius, midpoint);
                        }
                        testLocalUv = signValue * 0.5 + 0.5;
                    }
                    """;
            String fragmentHelper = ErydonCuPomShaderBridge.fragmentSource().replace("texture2D(", "texture(");
            String fragmentSource = """
                    #version 330 core
                    uniform ivec2 atlasSize;
                    uniform mat4 gbufferModelViewInverse;
                    uniform vec3 tangent, binormal, normal;
                    uniform vec2 testRayOffset;
                    in vec4 vTexCoordAM;
                    in vec2 testLocalUv;
                    flat in int erydonPomRecord;
                    out vec4 result;
                    """ + fragmentHelper + """
                    void main() {
                        result = vec4(erydonCtmPomAtlasUv(testLocalUv + testRayOffset), testLocalUv);
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
            lookup = GL11.glGenTextures();
            upload(ErydonCuPomLookupLayout.encode(ATLAS, ATLAS, List.of(BOUNDS)).rgba());
            GL20.glUniform1i(uniform("erydonCtmPomLookup"), 0);
            GL20.glUniform2i(uniform("atlasSize"), ATLAS, ATLAS);
            GL20.glUniformMatrix4fv(uniform("gbufferModelViewInverse"), false,
                    new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        }

        void upload(byte[] rgba) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lookup);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer bytes = BufferUtils.createByteBuffer(rgba.length);
            bytes.put(rgba).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1024, 1057, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        }

        void basis(float[][] value) {
            GL20.glUniform3fv(uniform("tangent"), value[0]);
            GL20.glUniform3fv(uniform("binormal"), value[1]);
            GL20.glUniform3fv(uniform("normal"), value[2]);
        }

        float[] sample(int phase, float[] crop, float u, float v, float rayU, float rayV) {
            var bounds = phase < 0 ? new ErydonCuPomLookupLayout.SpriteBounds(512, 512, 16, 16) : BOUNDS.get(phase);
            GL20.glUniform4f(uniform("testCropBounds"), (bounds.x() + crop[0] * bounds.width()) / ATLAS,
                    (bounds.y() + crop[1] * bounds.height()) / ATLAS,
                    crop[2] * bounds.width() / ATLAS, crop[3] * bounds.height() / ATLAS);
            GL20.glUniform2f(uniform("testAtlasUv"), (bounds.x() + u * bounds.width()) / ATLAS,
                    (bounds.y() + v * bounds.height()) / ATLAS);
            GL20.glUniform2f(uniform("testRayOffset"), rayU, rayV);
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
            GL11.glDeleteTextures(lookup);
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
