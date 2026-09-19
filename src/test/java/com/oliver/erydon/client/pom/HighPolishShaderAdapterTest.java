package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishShaderAdapterTest {
    private static final String TERRAIN = """
            normalMap = ReadNormal(vTexCoord.st);
            normalM = normalMap.rgb;
            vec4 specularMap = texture2D(specular, texCoordM);
            if (specularMap.g < OSIEBCA * 229.1) {
                materialMask = specularMap.g * OSIEBCA * 214.0;
            } else {
                materialMask = specularMap.g - OSIEBCA * 15.0;
            }
            """;
    private static final String DEFERRED = "fresnelM = fresnelM * sqrt1(smoothnessD) - dither * 0.01;";
    private static final String WATER = "emission = GetCustomEmission(specularMap, texCoordM);\nreflectMult = smoothnessD;";

    @AfterEach void reset() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void onlyOpaqueProgramsChangeAndHeightSamplingIsPreserved() {
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true);
        assertTrue(result.changed());
        assertTrue(result.text().startsWith(TERRAIN.substring(0, TERRAIN.indexOf("materialMask ="))));
        assertTrue(result.text().endsWith("} else {\n    materialMask = specularMap.g - OSIEBCA * 15.0;\n}\n"));
        assertEquals(1, result.text().split("texture2D", -1).length - 1);
        assertTrue(result.text().contains("specularMap.r >= 0.68"), "Grout must retain its authored roughness");
        for (String program : new String[]{"gbuffers_water", "shadow", "gbuffers_entities", "composite"}) {
            assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment(program, TERRAIN, true).text());
        }
    }

    @Test void markerDoesNotUseWatersMaterialMask() {
        String terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text();
        String deferred = HighPolishShaderAdapter.adaptFragment("deferred1", DEFERRED, true).text();
        assertTrue(terrain.contains("OSIEBCA * 242.0"));
        assertTrue(deferred.contains("materialMaskInt == 242"));
        assertFalse(terrain.contains("241"));
        assertTrue(deferred.contains("(pow3(fresnel) * 0.75 + 0.25) * smoothnessD"));
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

    @Test void bothProgramsAndUnusedIdsAreRequiredBeforeClassifyingBlocks() {
        HighPolishShaderAdapter.beginShaderLoad(true, true);
        assertFalse(HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN).changed());
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN);
        assertFalse(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.adaptFragment("deferred1", DEFERRED);
        assertFalse(HighPolishShaderAdapter.ready(), "The mirror program must also be recognised");
        HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER);
        assertTrue(HighPolishShaderAdapter.ready());
        // Absent optional programs must not cancel an already loaded base program.
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", null);
        assertTrue(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.adaptFragment("deferred1", "changed source");
        assertFalse(HighPolishShaderAdapter.ready());
        HighPolishShaderAdapter.beginShaderLoad(false, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        assertSame(TERRAIN, HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN).text());
        assertFalse(HighPolishShaderAdapter.ready());
    }

    @Test void collisionRejectsBeforeAnyShaderIsPatched() {
        for (int used : new int[]{12024, 12025, 12027, 12029, 12031}) {
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
        assertSame(adapted, HighPolishShaderAdapter.adaptSpiralPredicate(adapted));
    }

    @Test void solidAndPartialShapesKeepComplementarysLightingConvention() {
        assertEquals(0, HighPolishShaderAdapter.materialId(true, false) % 2);
        assertEquals(1, HighPolishShaderAdapter.materialId(false, false) % 2);
        assertEquals(12027, HighPolishShaderAdapter.materialId(false, true));
        for (int id : new int[]{12024, 12025, 12027}) assertTrue(id >= 11024 && id < 30000);
    }

    @Test void blissPolishesOnlySelectedStoneWithoutAnotherSampleOrChangingMetals() {
        String source = "vec4 SpecularTex = texture2D_POMSwitch(specular, uv, gradient, ifPOM, lod);\n"
                + "SpecularTex.g = max(SpecularTex.g, max(Puddle_shape*0.02,0.02));\n"
                + "gl_FragData[1].rg = SpecularTex.rg;";
        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.BLISS, true);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        var result = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", source);
        assertTrue(result.changed());
        assertTrue(HighPolishShaderAdapter.ready(), "Bliss needs no deferred-stage patch");
        assertEquals(1, result.text().split("texture2D_POMSwitch", -1).length - 1);
        assertTrue(result.text().contains("SpecularTex.r >= 0.68 && SpecularTex.g < 229.5 / 255.0"));
        assertTrue(result.text().contains("SpecularTex.g = max(SpecularTex.g, 0.25)"));
        assertTrue(result.text().endsWith("gl_FragData[1].rg = SpecularTex.rg;"));
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
        assertTrue(result.text().contains("(mat == 12029 || mat == 12031)"));
        assertTrue(result.text().contains("specularMap.g >= 229.5 / 255.0"), "Clear inner glass is non-metallic");
        assertFalse(result.text().contains("texture2D"), "No extra specular texture read");
        assertTrue(result.text().contains("(0.9 - 0.15) / 0.85"));
        String terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", TERRAIN, true).text();
        assertTrue(terrain.contains("mat == 12029"));
        assertFalse(terrain.contains("mat == 12031"), "Normal and aged window frames must remain unchanged");
        assertSame(result.text(), HighPolishShaderAdapter.adaptFragment("gbuffers_water", result.text(), true).text());
        for (String unsupported : new String[]{WATER + WATER, "reflectMult = smoothnessD;"}) {
            assertSame(unsupported, HighPolishShaderAdapter.adaptFragment("gbuffers_water", unsupported, true).text());
        }
        assertSame(WATER, HighPolishShaderAdapter.adaptFragment("gbuffers_water", WATER, false).text());
    }
}
