package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.oliver.erydon.HighPolishSettings.Level;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishShaderAdapterTest {
    private static final String TERRAIN = """
            normalMap = ReadNormal(vTexCoord.st);
            normalM = normalMap.rgb;
            vec4 specularMap = texture2D(specular, texCoordM);
            float smoothnessM = pow2(specularMap.r);
            if (specularMap.g < OSIEBCA * 229.1) {
                materialMask = specularMap.g * OSIEBCA * 214.0;
            } else {
                materialMask = specularMap.g - OSIEBCA * 15.0;
            }
            """;
    private static final String DEFERRED = "fresnelM = fresnelM * sqrt1(smoothnessD) - dither * 0.01;";
    private static final String WATER = "emission = GetCustomEmission(specularMap, texCoordM);\nreflectMult = smoothnessD;";
    private static final String COMPOSITE = "vec3 reflectColor = vec3(1.0);\nreflection.rgb *= reflectColor;";
    private static final String BLEND = """
            vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0) {
                vec3 texture6 = texelFetch(colortex6, texelCoord, 0).rgb;
                float smoothnessD = texture6.r;
                return sum / weightSum;
            }
            compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0);
            const float texturePreservation = 0.7;
            compositeReflection.rgb = mix(compositeReflection.rgb, max(color, compositeReflection.rgb), texturePreservation);
            color = mix(color, compositeReflection.rgb, fresnelM);
            """;

    @AfterEach void reset() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void circularColumnApproximationChangesOnlyReflectionEligibility() {
        String source = "void UpdateSceneVoxelMap(int mat, vec3 normal, vec3 position) {\n"
                + "bool doSolidBlockCheck = true;\nif (doSolidBlockCheck) { if (mat % 2 == 1) return; }\n"
                + "imageStore(wsr_img, ivec3(voxelPos), uvec4(matM, 0u, 0u, 0u));\n}";
        var result = HighPolishShaderAdapter.adaptColumnReflections(source, true);
        assertTrue(result.changed());
        assertTrue(result.text().contains("doSolidBlockCheck = false;"));
        assertEquals(1, result.text().split("imageStore", -1).length - 1);
        assertFalse(result.text().contains("texture"));
        for (Level level : Level.values()) {
            int id = HighPolishShaderAdapter.columnMaterialId(level);
            assertEquals(1, id % 2, "Columns must retain partial-block voxel lighting");
            assertTrue(HighPolishShaderAdapter.isReservedMaterial(id));
            assertTrue(result.text().contains("mat == " + id));
            assertTrue(HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text().contains("mat == " + id));
        }
        assertFalse(HighPolishShaderAdapter.adaptColumnReflections(result.text(), true).changed());
        assertSame(source, HighPolishShaderAdapter.adaptColumnReflections(source, false).text());
        assertFalse(HighPolishShaderAdapter.adaptColumnReflections(source + source, true).changed());
        assertFalse(HighPolishShaderAdapter.adaptColumnReflections("different shader", true).changed());
    }

    @Test void onlyOpaqueProgramsChangeAndHeightSamplingIsPreserved() {
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true);
        assertTrue(result.changed());
        assertTrue(result.text().startsWith(TERRAIN.substring(0, TERRAIN.indexOf("float smoothnessM"))));
        assertTrue(result.text().contains("materialMask = specularMap.g - OSIEBCA * 15.0;"));
        assertEquals(1, result.text().split("texture2D", -1).length - 1);
        assertTrue(result.text().contains("specularMap.r >= 0.68"), "Grout must retain its authored roughness");
        assertTrue(result.text().contains("specularMap.g < 229.1 / 255.0"), "Stone finish must not replace metal channels");
        assertTrue(result.text().indexOf("175.0 / 255.0 : 1.0") < result.text().indexOf("float smoothnessM"),
                "All source textures must select smoothness before CU calculates highlights and reflections");
        for (String program : new String[]{"gbuffers_water", "shadow", "gbuffers_entities", "composite2"}) {
            assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment(program, TERRAIN, true).text());
        }
    }

    @Test void markerDoesNotUseWatersMaterialMask() {
        String terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text();
        String deferred = HighPolishShaderAdapter.adaptFragment("deferred1", DEFERRED, true).text();
        assertTrue(terrain.contains("OSIEBCA * 242.0"));
        assertTrue(deferred.contains("materialMaskInt == 242"));
        assertFalse(terrain.contains("241"));
        assertTrue(deferred.contains("(pow3(fresnel) * 0.5 + 0.5) * smoothnessD"));
        assertFalse(deferred.contains("materialMaskInt == 243"), "Metal response belongs to the separate material adapter");
        assertFalse(terrain.contains("OSIEBCA * 243.0"));
        assertFalse(deferred.contains("reflectColor ="), "deferred1 cannot carry the tint into composite");
        String mirrorMask = terrain.substring(terrain.indexOf("if (", terrain.indexOf("materialMask =")), terrain.indexOf("} else"));
        assertTrue(mirrorMask.contains("mat == 12024"));
        assertFalse(mirrorMask.contains("mat == 12032"), "Honed must not receive the Mirror floor");
        assertFalse(mirrorMask.contains("mat == 12040"), "Polished has no added reflection boost");
    }

    @Test void disabledMissingAndAmbiguousSourcesAreByteExact() {
        assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, false).text());
        for (String source : new String[]{"unrecognised", TERRAIN + TERRAIN}) {
            var result = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", source, true);
            assertEquals("UNSUPPORTED_SOURCE", result.status());
            assertSame(source, result.text());
        }
        assertNull(HighPolishShaderAdapter.adaptFragment("deferred1", null, true).text());
    }

    @Test void stoneAndGlassStagesAreRequiredWithoutCouplingMetalReflectionStages() {
        HighPolishShaderAdapter.beginShaderLoad(true, true);
        assertFalse(HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN).changed());
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN);
        assertFalse(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.adaptFragment("deferred1", DEFERRED);
        assertFalse(HighPolishShaderAdapter.ready(), "The mirror program must also be recognised");
        HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER);
        assertTrue(HighPolishShaderAdapter.ready());
        assertEquals("OTHER_PROGRAM", HighPolishShaderAdapter.adaptFragment("composite", COMPOSITE).status());
        assertEquals("OTHER_PROGRAM", HighPolishShaderAdapter.adaptFragment("composite1", BLEND).status());
        assertTrue(HighPolishShaderAdapter.ready());
        // Absent optional programs must not cancel an already loaded base program.
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", null);
        assertTrue(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", "changed source");
        assertFalse(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.beginShaderLoad(false, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN).text());
        assertFalse(HighPolishShaderAdapter.ready());
    }

    @Test void reflectionTintAndBlendingRemainByteExactForTheSeparateMetalAdapter() {
        for (String program : new String[]{"composite", "composite1"}) {
            String source = program.equals("composite") ? COMPOSITE : BLEND;
            for (String input : new String[]{source, source + source, "unknown shader source"}) {
                var result = HighPolishShaderAdapter.adaptFragment(program, input, true);
                assertEquals("OTHER_PROGRAM", result.status());
                assertFalse(result.changed());
                assertSame(input, result.text());
            }
            assertSame(source, HighPolishShaderAdapter.adaptFragment(program, source, false).text());
        }
    }

    @Test void collisionRejectsBeforeAnyShaderIsPatched() {
        for (int id = 12000; id < 12060; id++) {
            assertEquals(HighPolishShaderAdapter.RESERVED_IDS.contains(id), HighPolishShaderAdapter.isReservedMaterial(id));
        }
        for (int used : HighPolishShaderAdapter.RESERVED_IDS) {
            HighPolishShaderAdapter.beginShaderLoad(true, true);
            HighPolishShaderAdapter.acceptMaterialIds(id -> id == used);
            assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN).text());
            assertFalse(HighPolishShaderAdapter.ready());
        }
    }

    @Test void repeatedTransformsAndSpiralPredicateAreIdempotent() {
        String once = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text();
        assertSame(once, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", once, true).text());
        HighPolishShaderAdapter.beginShaderLoad(true, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        String spiral = "if (mat == 32120) {}; bool skipPom = mat == 32120 && record < 0;";
        String adapted = HighPolishShaderAdapter.adaptSpiralPredicate(spiral);
        assertEquals(2, adapted.split("mat == 12027", -1).length - 1);
        assertEquals(2, adapted.split("mat == 12035", -1).length - 1);
        assertEquals(2, adapted.split("mat == 12043", -1).length - 1);
        assertSame(adapted, HighPolishShaderAdapter.adaptSpiralPredicate(adapted));
    }

    @Test void solidAndPartialShapesKeepComplementarysLightingConvention() {
        for (Level level : Level.values()) {
            assertEquals(0, HighPolishShaderAdapter.materialId(level, true, false) % 2);
            assertEquals(1, HighPolishShaderAdapter.materialId(level, false, false) % 2);
            assertEquals(1, HighPolishShaderAdapter.materialId(level, false, true) % 2);
            assertEquals(1, HighPolishShaderAdapter.mirrorFrameId(level) % 2);
        }
        assertEquals(12027, HighPolishShaderAdapter.materialId(Level.MIRROR, false, true));
        assertEquals(HighPolishShaderAdapter.RESERVED_IDS.size(), HighPolishShaderAdapter.RESERVED_IDS.stream().distinct().count());
        for (int id : HighPolishShaderAdapter.RESERVED_IDS) assertTrue(id >= 11024 && id < 30000);
    }

    @Test void otherShaderPacksRemainByteExactDuringTheComplementaryTrial() {
        String source = "vec4 SpecularTex = texture2D_POMSwitch(specular, uv, gradient, ifPOM, lod);\n"
                + "SpecularTex.g = max(SpecularTex.g, max(Puddle_shape*0.02,0.02));\n"
                + "gl_FragData[1].rg = SpecularTex.rg;";
        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.BLISS, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", source);
        assertFalse(result.changed());
        assertFalse(HighPolishShaderAdapter.ready());
        assertFalse(HighPolishShaderAdapter.requested());
        assertSame(source, result.text());
        assertSame(source, HighPolishShaderAdapter.adaptFragment("gbuffers_water", source).text());
        assertSame(source, HighPolishShaderAdapter.adaptFragment("deferred1", source).text());
        assertSame(source, HighPolishShaderAdapter.adaptSpiralPredicate(source));
        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.BLISS, false);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        assertSame(source, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", source).text());
        assertEquals(HighPolishShaderAdapter.Profile.UNSUPPORTED, HighPolishShaderAdapter.profileForProperties("other pack"));
    }

    @Test void mirrorCoatingUsesExistingSampleAndDoesNotPolishTheUnselectedFrame() {
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER, true);
        assertTrue(result.changed());
        for (int id : new int[]{12029, 12031, 12037, 12045}) assertTrue(result.text().contains("mat == " + id));
        assertTrue(result.text().contains("specularMap.g >= 229.5 / 255.0"), "Clear inner glass is non-metallic");
        assertFalse(result.text().contains("texture2D"), "No extra specular texture read");
        assertTrue(result.text().contains("(0.9 - 0.15) / 0.85"));
        String terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text();
        assertTrue(terrain.contains("mat == 12029"));
        assertFalse(terrain.contains("mat == 12031"), "Normal and aged window frames must remain unchanged");
        String metalBranch = terrain.substring(terrain.indexOf("materialMask = specularMap.g -"));
        for (Level level : Level.values()) {
            assertFalse(metalBranch.contains("mat == " + HighPolishShaderAdapter.mirrorFrameId(level)),
                    "Window coating must not inherit the inlay boost when glass polish is off");
        }
        assertSame(result.text(), HighPolishShaderAdapter.adaptFragment("gbuffers_water", result.text(), true).text());
        for (String unsupported : new String[]{WATER + WATER, "reflectMult = smoothnessD;"}) {
            assertSame(unsupported, HighPolishShaderAdapter.adaptFragment("gbuffers_water", unsupported, true).text());
        }
        assertSame(WATER, HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER, false).text());
    }
}
