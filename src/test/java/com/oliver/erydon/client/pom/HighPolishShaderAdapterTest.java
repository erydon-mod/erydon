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
        assertTrue(deferred.contains("(pow3(fresnel) * 0.85 + 0.15) * smoothnessD"));
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
        assertTrue(HighPolishShaderAdapter.ready());
        // CU's empty root programs must not cancel the dimension programs.
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
        for (int used : new int[]{12024, 12025, 12027}) {
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
}
