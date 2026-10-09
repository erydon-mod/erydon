package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production cover material and reflection code, including emission. */
@Execution(ExecutionMode.SAME_THREAD)
class CoverReflectionBlendTest {
    private static final String MATERIAL = """
            #version 330 compatibility
            uniform int mat;
            uniform vec4 testSpecular;
            uniform float testFresnel;
            uniform int testOutput;
            const float OSIEBCA = 1.0 / 255.0;
            float pow2(float x) { return x * x; }
            float pow3(float x) { return x * x * x; }
            float sqrt1(float x) { return sqrt(x); }
            float GetCustomEmission(vec4 sampleValue, vec2 uv) { return sampleValue.a; }
            void GetCustomMaterials(out float smoothnessG, out float smoothnessD,
                                    out float materialMask, out float emission) {
                vec2 texCoordM = vec2(0.0);
                vec4 specularMap = testSpecular;
                emission = GetCustomEmission(specularMap, texCoordM);
                float smoothnessM = pow2(specularMap.r);
                smoothnessG = smoothnessM;
                smoothnessD = smoothnessM;
                if (specularMap.g < OSIEBCA * 229.1) {
                    materialMask = specularMap.g * OSIEBCA * 214.0;
                } else {
                    materialMask = specularMap.g - OSIEBCA * 15.0;
                }
            }
            void main() {
                float smoothnessG, smoothnessD, materialMask, emission;
                GetCustomMaterials(smoothnessG, smoothnessD, materialMask, emission);
                int materialMaskInt = int(materialMask * 255.1);
                float fresnel = testFresnel;
                float fresnelM = 0.17, dither = 0.0;
                fresnelM = fresnelM * sqrt1(smoothnessD) - dither * 0.01;
                gl_FragData[0] = testOutput == 2 ? vec4(0.0, 0.0, 1.0, sqrt(fresnelM))
                        : vec4(fresnelM, float(materialMaskInt), emission, smoothnessD);
            }
            """;
    private static final String BLEND = """
            #version 330 compatibility
            uniform sampler2D colortex6;
            uniform float testReflection;
            const mat4 gbufferModelView = mat4(1.0);
            const vec4 texture4 = vec4(0.0, 0.0, 1.0, 1.0);
            vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0) {
                ivec2 texelCoord = ivec2(0);
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                float smoothnessD = texture6.r;
                vec4 sum = centerCol;
                float weightSum = 1.0;
                return sum / weightSum;
            }
            void main() {
                vec3 color = vec3(1.0, 0.0, 0.0), nViewPos = vec3(0.0, 0.0, -1.0);
                float dither = 0.0, z0 = 0.5, fresnelM = testReflection;
                vec4 compositeReflection = vec4(0.0, 1.0, 0.0, 1.0);
                compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0);
                const float texturePreservation = 0.7;
                compositeReflection.rgb = mix(compositeReflection.rgb, max(color, compositeReflection.rgb), texturePreservation);
                color = mix(color, compositeReflection.rgb, fresnelM);
                gl_FragData[0] = vec4(color, 1.0);
            }
            """;

    static boolean driverConfigured() { return InstalledShaderTestSupport.driverValidationEnabled(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }

    private static String material() {
        var terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", MATERIAL, true);
        assertTrue(terrain.changed(), terrain.status());
        // Each stage normally has a separate source; remove only the terrain idempotence marker here.
        var deferred = HighPolishShaderAdapter.adaptFragment("deferred1",
                terrain.text().replace("// ERYDON opaque high polish", "// fixture terrain stage"), true);
        assertTrue(deferred.changed(), deferred.status());
        return deferred.text();
    }

    @Test void adaptedFixturesParseWithoutPreprocessorInstructions() throws Exception {
        HighPolishShaderPackTest.parse(material());
        var blend = MetalReflectionResponse.adapt("composite1", BLEND);
        assertTrue(blend.changed(), blend.status());
        HighPolishShaderPackTest.parse(blend.text());
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s*#\\s*(?:if|define|include)\\b")
                .matcher(material()).find());
    }

    @Test @EnabledIf("driverConfigured")
    void glossCoversKeepTheirEmissionAndSelectTheMirrorOrCoatingCurve() {
        InstalledShaderTestSupport.ensureContext();
        try (var gpu = new GlazingReflectionBlendTest.PixelGpu()) {
            int program = gpu.program(material());
            int cases = 0;
            for (int id : new int[]{HighPolishShaderAdapter.COVER_GLOSS_ID, HighPolishShaderAdapter.COVER_SILVER_GLOSS_ID}) {
                for (float green : new float[]{90.0F / 255.0F, 1.0F}) {
                    for (float grazing : new float[]{0.0F, 0.5F, 1.0F}) {
                        GL20.glUseProgram(program);
                        GL20.glUniform1i(GL20.glGetUniformLocation(program, "mat"), id);
                        GL20.glUniform4f(GL20.glGetUniformLocation(program, "testSpecular"), 1, green, 0, .37F);
                        GL20.glUniform1f(GL20.glGetUniformLocation(program, "testFresnel"), grazing);
                        float[] pixel = gpu.draw(program, 0, false);
                        boolean silver = id == HighPolishShaderAdapter.COVER_SILVER_GLOSS_ID;
                        double reflection = silver ? Math.min(.99, Math.max(.9, .15 + .85 * Math.pow(grazing, 3)))
                                : Math.min(.99, .5 + .5 * Math.pow(grazing, 3));
                        assertEquals(reflection, pixel[0], .000004, "Selected finish curve");
                        assertEquals(silver ? 245 : 242, pixel[1], .000004, "Opaque material tag");
                        assertEquals(.37, pixel[2], .000004, "Authored emission survives");
                        assertEquals(1, pixel[3], .000004, "Gloss stays perfectly smooth");
                        if (grazing == 1.0F) {
                            float stored = gpu.storedReflection(program);
                            assertTrue(stored < 1 && stored * stored > .98,
                                    "Opaque grazing reflection survives CU's signed-normalized history");
                        }
                        cases++;
                    }
                }
            }
            assertEquals(12, cases);
        }
    }

    @Test @EnabledIf("driverConfigured")
    void silverCoatingMixesReflectionsWithoutTheOpaqueAlbedoPreservation() {
        InstalledShaderTestSupport.ensureContext();
        try (var gpu = new GlazingReflectionBlendTest.PixelGpu()) {
            var transformed = MetalReflectionResponse.adapt("composite1", BLEND);
            assertTrue(transformed.changed(), transformed.status());
            int program = gpu.program(transformed.text());
            for (int mask : new int[]{245, 242, 10}) {
                for (float reflection : new float[]{.9F, .99F}) {
                    GL20.glUseProgram(program);
                    gpu.sample(1, mask / 255.0F, 0, 0);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program, "testReflection"), reflection);
                    float[] pixel = gpu.draw(program, 0, false);
                    double expectedBase = mask == 245 ? 1 - reflection : 1 - .3 * reflection;
                    assertEquals(expectedBase, pixel[0], .000004, "Silver coating has no opaque albedo preservation");
                    assertEquals(reflection, pixel[1], .000004, "Neutral reflected environment");
                    assertEquals(0, pixel[2], .000004);
                }
            }
        }
    }

    @Test @EnabledIf("voxelDriverConfigured")
    void actualWorldReflectionVoxelizerRejectsThinCoversAndRetainsFullBlocks() throws Exception {
        InstalledShaderTestSupport.ensureContext();
        var stages = net.irisshaders.iris.gl.shader.StandardMacros.getRenderStages();
        var defines = stages.entrySet().stream()
                .map(entry -> new net.irisshaders.iris.helpers.StringPair(entry.getKey(), entry.getValue())).toList();
        String source;
        try (var zip = new ZipFile(InstalledShaderTestSupport.shaderPaths().get(0).toFile())) {
            source = InstalledShaderTestSupport.source(zip, "world0", "shadow.vsh",
                    InstalledShaderTestSupport.Options.DEFAULT, defines,
                    Map.of("COLORED_LIGHTING", "512", "WORLD_SPACE_REFLECTIONS", "1"));
        }
        // Execute the installed pack's complete voxelizer, rather than copying its
        // odd-ID check into a test. Replace only the vertex input unavailable in a
        // fragment readback fixture; the two voxel image writes stay real.
        String voxelizer = function(source, "void UpdateSceneVoxelMap(")
                .replace("gl_Vertex", "fixtureVertex")
                .replace("gl_ModelViewMatrix", "fixtureMatrix");
        String fragment = """
                #version 430 compatibility
                uniform float testEntityMaterial;
                uniform int renderStage;
                uniform vec3 testNormal;
                uniform sampler2D tex;
                layout(r32ui, binding = 0) uniform uimage3D wsr_img;
                layout(r32ui, binding = 1) uniform uimage3D wsr_lod_img;
                const mat4 fixtureMatrix = mat4(1.0);
                const mat4 shadowModelViewInverse = mat4(1.0);
                const vec4 fixtureVertex = vec4(0.5, 0.5, 0.5, 1.0);
                const vec3 at_midBlock = vec3(0.0);
                const vec2 texCoord = vec2(0.25), mc_midTexCoord = vec2(0.5);
                const vec2 atlasSize = vec2(16.0);
                float storedFaces = 0.0;
                vec3 playerToSceneVoxel(vec3 pos) { return pos; }
                bool CheckInsideSceneVoxelVolume(vec3 pos) { return true; }
                void storeFaceData(ivec3 pos, vec3 normal, vec2 origin, float radius,
                                   bool allFaces, bool exceptTop, vec3 scenePos) { storedFaces += 1.0; }
                """ + voxelizer + """
                void main() {
                    imageStore(wsr_img, ivec3(0), uvec4(0));
                    imageStore(wsr_lod_img, ivec3(0), uvec4(0));
                    memoryBarrierImage();
                    // Match shadow.vsh: Iris supplies -1 for unmapped blocks, which
                    // becomes int(-0.5) == 0 before the native voxelizer is called.
                    int mat = int(testEntityMaterial + 0.5);
                    UpdateSceneVoxelMap(mat, testNormal, vec3(0.5));
                    memoryBarrierImage();
                    gl_FragData[0] = vec4(float(imageLoad(wsr_img, ivec3(0)).r),
                            float(imageLoad(wsr_lod_img, ivec3(0)).r), storedFaces, 1.0);
                }
                """;
        int[] images = {voxelImage(0), voxelImage(1)};
        try (var gpu = new GlazingReflectionBlendTest.PixelGpu()) {
            int program = gpu.program(fragment);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "renderStage"),
                    Integer.parseInt(stages.get("MC_RENDER_STAGE_TERRAIN_SOLID")));
            gpu.sample(1, 1, 1, 1);
            for (int material : new int[]{-1, 0, 10080, HighPolishShaderAdapter.COVER_MATTE_ID,
                    HighPolishShaderAdapter.COVER_GLOSS_ID, HighPolishShaderAdapter.COVER_SILVER_GLOSS_ID}) {
                for (float[] normal : List.of(new float[]{1, 0, 0}, new float[]{-1, 0, 0},
                        new float[]{0, 1, 0}, new float[]{0, -1, 0},
                        new float[]{0, 0, 1}, new float[]{0, 0, -1})) {
                    GL20.glUseProgram(program);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program, "testEntityMaterial"), material);
                    GL20.glUniform3f(GL20.glGetUniformLocation(program, "testNormal"), normal[0], normal[1], normal[2]);
                    float[] pixel = gpu.draw(program, 0, false);
                    boolean cover = material >= HighPolishShaderAdapter.COVER_GLOSS_ID;
                    assertEquals(cover ? 0 : material > 10 ? material : 1, pixel[0], .00001,
                            "No phantom cube for material " + material + " and normal " + java.util.Arrays.toString(normal));
                    assertEquals(cover ? 0 : 1, pixel[1], .00001, "Reflection LOD occupancy");
                    assertEquals(cover ? 0 : 1, pixel[2], .00001, "No phantom cover face data");
                }
            }
        } finally {
            for (int unit = 0; unit < images.length; unit++) {
                GL42.glBindImageTexture(unit, 0, 0, true, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
                GL11.glDeleteTextures(images[unit]);
            }
        }
    }

    static boolean voxelDriverConfigured() {
        return driverConfigured() && InstalledShaderTestSupport.configured();
    }

    private static int voxelImage(int binding) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R32UI, 1, 1, 1, 0,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL42.glBindImageTexture(binding, texture, 0, true, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
        return texture;
    }

    private static String function(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Installed shader must contain " + signature);
        assertEquals(start, source.lastIndexOf(signature), "Unique installed voxelizer");
        int body = source.indexOf('{', start), depth = 0;
        for (int cursor = body; cursor < source.length(); cursor++) {
            if (source.charAt(cursor) == '{') depth++;
            if (source.charAt(cursor) == '}' && --depth == 0) return source.substring(start, cursor + 1);
        }
        throw new AssertionError("Unclosed installed voxelizer");
    }
}
