package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executes actual GLSL against an independent uncached addressing reference. */
@EnabledIf("configured")
class InlaySubstrateBoundsCacheTest {
    private static final int ATLAS = 512;
    private static final float[] A = {1.25f, .4f};
    private static final String REFERENCE = """
            vec4 erydonInlaySubstrateBounds(vec2 local) {
                vec4 bounds = erydonInlayBaseBounds;
                vec2 tiles = floor(local);
                if (all(equal(tiles, vec2(0.0)))) return bounds;
                vec2 repeatDelta;
                if (erydonInlayRibbon == 1) repeatDelta = tiles;
                else if (!erydonCtmPomRepeatDelta(tiles, repeatDelta)) return bounds;
                float record = float(erydonInlaySubstrateRecord);
                float family = floor(record / 36.0) * 36.0;
                float phase = record - family;
                vec2 target = mod(vec2(mod(phase, 6.0), floor(phase / 6.0)) + repeatDelta, 6.0);
                float targetRecord = family + target.y * 6.0 + target.x;
                if (targetRecord < 0.0 || targetRecord >= erydonCtmPomRecordCount()) return bounds;
                return erydonCtmPomReadBoundsPx(targetRecord);
            }
            """;
    private static final float[][][] FRAMES = {
            {{1,0,0},{0,1,0},{0,0,1}}, {{-1,0,0},{0,1,0},{0,0,-1}},
            {{0,0,-1},{0,1,0},{1,0,0}}, {{0,0,1},{0,1,0},{-1,0,0}},
            {{1,0,0},{0,0,-1},{0,1,0}}, {{1,0,0},{0,0,1},{0,-1,0}},
            {{1,0,0},{1,0,0},{0,0,1}}
    };

    static boolean configured() { return InstalledShaderTestSupport.driverValidationEnabled(); }
    @AfterAll static void closeContext() { InstalledShaderTestSupport.close(); }

    @Test void matchesUncachedBoundsAcrossPhasesRibbonsAndFiniteRecovery() throws Exception {
        InstalledShaderTestSupport.ensureContext();
        float[][][] sequences = {
                {A,A,A}, {A,{2.25f,.4f},A}, {A,{.25f,.4f},A},
                {A,{16777216f,.25f},A}, {A,{-33554432f,7.25f},A},
                {A,{Float.NaN,1.25f},A}, {A,{Float.POSITIVE_INFINITY,-1.25f},A}
        };
        try (Gpu gpu = new Gpu()) {
            for (int frame = 0; frame < FRAMES.length; frame++) for (int phase = 0; phase < 36; phase++)
                for (int ribbon = 0; ribbon < 2; ribbon++) for (float[][] sequence : sequences) {
                    float[] before = gpu.sample(false, frame, phase, ribbon, sequence);
                    float[] after = gpu.sample(true, frame, phase, ribbon, sequence);
                    // Non-finite intermediate texture coordinates are undefined. The final
                    // finite call must recover exactly, including after an uncacheable miss.
                    for (int channel = 0; channel < 2; channel++) {
                        assertTrue(Float.isFinite(after[channel]));
                        assertEquals(Float.floatToRawIntBits(before[channel]), Float.floatToRawIntBits(after[channel]),
                                "UV frame=" + frame + " phase=" + phase + " ribbon=" + ribbon);
                    }
                }
            float[][] repeated = {A,A,A,A,A,A,A,A};
            assertEquals(17f, gpu.sample(false, 0, 19, 0, repeated)[2]);
            assertEquals(3f, gpu.sample(true, 0, 19, 0, repeated)[2]);
            // Counts are source helper calls, not hardware memory transactions.
            byte[] truncated = gpu.lookupBytes.clone();
            truncated[8] = 15; truncated[9] = 0;
            gpu.upload(truncated);
            for (float[][] sequence : sequences) {
                float[] before = gpu.sample(false, 0, 14, 0, sequence);
                float[] after = gpu.sample(true, 0, 14, 0, sequence);
                assertEquals(Float.floatToRawIntBits(before[0]), Float.floatToRawIntBits(after[0]));
                assertEquals(Float.floatToRawIntBits(before[1]), Float.floatToRawIntBits(after[1]));
            }
        }
    }

    private static final class Gpu implements AutoCloseable {
        private final List<ErydonCuPomLookupLayout.SpriteBounds> bounds = new ArrayList<>();
        private final byte[] lookupBytes;
        private final int[] programs = new int[2];
        private final int vao, framebuffer, target, lookup;

        Gpu() throws Exception {
            for (int phase = 0; phase < 36; phase++) {
                int shuffled = phase * 7 % 36;
                bounds.add(new ErydonCuPomLookupLayout.SpriteBounds(shuffled % 6 * 64, shuffled / 6 * 64, 64, 64));
            }
            lookupBytes = ErydonCuPomLookupLayout.encode(ATLAS, ATLAS, List.of(bounds)).rgba();
            String source;
            try (var stream = InlaySubstrateBoundsCacheTest.class.getResourceAsStream(
                    "/assets/erydon/shaders/include/erydon_inlay_recess.glsl")) {
                assertNotNull(stream);
                source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int start = source.indexOf("vec2 erydonInlayLastSubstrateTile =");
            int uv = source.indexOf("vec2 erydonInlaySubstrateUv(");
            int end = source.indexOf("float erydonInlaySubstrateHeight(");
            assertTrue(start >= 0 && uv > start && end > uv);
            String bridge = ErydonCuPomShaderBridge.fragmentSource().replace("texture2D(", "texture(")
                    .replace("vec4 erydonCtmPomReadBytes(float linearIndex) {",
                            "vec4 erydonCtmPomReadBytes(float linearIndex) { testReads += 1.0;");
            String declarations = """
                    #version 330 core
                    uniform ivec2 atlasSize;
                    uniform vec4 vTexCoordAM, erydonInlayBaseBounds;
                    uniform int erydonPomRecord, erydonInlaySubstrateRecord, erydonInlayRibbon;
                    uniform mat4 gbufferModelViewInverse;
                    uniform vec3 tangent, binormal, normal;
                    uniform mat2 erydonInlayRibbonToBase;
                    uniform vec2 erydonInlayRibbonOffset;
                    uniform ivec4 erydonMetalBounds;
                    uniform ivec2 erydonMetalAtlas;
                    uniform vec2 testUv[8];
                    uniform int testCount;
                    float testReads = 0.0;
                    out vec4 result;
                    """;
            String main = """
                    void main() {
                        vec2 last = vec2(0), scale;
                        for (int i = 0; i < testCount; i++) last = erydonInlaySubstrateUv(testUv[i], scale);
                        result = vec4(last, testReads, 1);
                    }
                    """;
            String vertex = "#version 330 core\nvoid main(){vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));gl_Position=vec4(p[gl_VertexID],0,1);}";
            programs[0] = program(vertex, declarations + bridge + REFERENCE + source.substring(uv, end) + main);
            programs[1] = program(vertex, declarations + bridge + source.substring(start, end) + main);
            vao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(vao);
            target = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
            framebuffer = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            lookup = GL11.glGenTextures(); upload(lookupBytes);
            GL11.glViewport(0, 0, 1, 1); GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST);
            for (int p : programs) {
                GL20.glUseProgram(p);
                GL20.glUniform1i(uniform(p,"erydonCtmPomLookup"),0);
                GL20.glUniform2i(uniform(p,"atlasSize"),ATLAS,ATLAS);
                GL20.glUniform2i(uniform(p,"erydonMetalAtlas"),ATLAS,ATLAS);
                GL20.glUniform4i(uniform(p,"erydonMetalBounds"),384,0,64,64);
                GL20.glUniformMatrix4fv(uniform(p,"gbufferModelViewInverse"),false,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
                GL20.glUniformMatrix2fv(uniform(p,"erydonInlayRibbonToBase"),false,new float[]{0,1,-1,0});
            }
        }

        void upload(byte[] bytes) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, lookup);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            ByteBuffer data = BufferUtils.createByteBuffer(bytes.length); data.put(bytes).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1024,1057,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
        }

        float[] sample(boolean cached,int frame,int phase,int ribbon,float[][] coordinates) {
            int p = programs[cached ? 1 : 0]; GL20.glUseProgram(p);
            GL20.glUniform3fv(uniform(p,"tangent"),FRAMES[frame][0]);
            GL20.glUniform3fv(uniform(p,"binormal"),FRAMES[frame][1]);
            GL20.glUniform3fv(uniform(p,"normal"),FRAMES[frame][2]);
            GL20.glUniform1i(uniform(p,"erydonInlaySubstrateRecord"),phase);
            GL20.glUniform1i(uniform(p,"erydonInlayRibbon"),ribbon);
            var b = bounds.get(phase);
            GL20.glUniform4f(uniform(p,"erydonInlayBaseBounds"),b.x(),b.y(),b.width(),b.height());
            GL20.glUniform1i(uniform(p,"testCount"),coordinates.length);
            var input = BufferUtils.createFloatBuffer(coordinates.length * 2);
            for (float[] xy : coordinates) input.put(xy);
            input.flip(); GL20.glUniform2fv(uniform(p,"testUv[0]"),input);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            float[] result = new float[4]; GL11.glReadPixels(0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,result);
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError()); return result;
        }

        private static int uniform(int p,String name) { return GL20.glGetUniformLocation(p,name); }
        private static int program(String vertex,String fragment) {
            int v = compile(GL20.GL_VERTEX_SHADER,vertex), f = compile(GL20.GL_FRAGMENT_SHADER,fragment), p = GL20.glCreateProgram();
            GL20.glAttachShader(p,v); GL20.glAttachShader(p,f); GL20.glLinkProgram(p);
            assertEquals(GL11.GL_TRUE,GL20.glGetProgrami(p,GL20.GL_LINK_STATUS),GL20.glGetProgramInfoLog(p));
            GL20.glDeleteShader(v); GL20.glDeleteShader(f); return p;
        }
        private static int compile(int type,String source) {
            int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader,source); GL20.glCompileShader(shader);
            assertEquals(GL11.GL_TRUE,GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS),GL20.glGetShaderInfoLog(shader)); return shader;
        }
        @Override public void close() {
            GL20.glUseProgram(0); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0); GL30.glBindVertexArray(0);
            for (int p : programs) GL20.glDeleteProgram(p);
            GL11.glDeleteTextures(lookup); GL11.glDeleteTextures(target);
            GL30.glDeleteFramebuffers(framebuffer); GL30.glDeleteVertexArrays(vao);
        }
    }
}
