package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErydonCuPomShaderBridgeTest {
    @Test
    void helperContainsAllRequiredMaterialSamplingAndFallbackPaths() {
        String source = ErydonCuPomShaderBridge.source();
        assertTrue(source.contains("uniform sampler2D erydonCtmPomLookup;"));
        assertTrue(source.contains("vec2 ordinaryUv"));
        assertTrue(source.contains("return ordinaryUv;"));
        assertTrue(source.contains("erydonCtmPomRepeatDelta"));
        assertTrue(source.contains("targetMidUv"));
        assertTrue(source.contains("ERYDON_CTM_POM_LUT_DIM = 256.0"));
        assertTrue(source.contains("ERYDON_CTM_POM_HASH_SLOTS = 24571.0"));
        assertTrue(source.contains("ERYDON_CTM_POM_CENTRE_START = 49158.0"));
        assertTrue(source.contains("abs(magic.w - 3.0)"));
    }

    @Test
    void canonicalBasesResolveAllSixFacesAndRotatedOrMirroredUvs() {
        assertBasis(new int[]{0, 1, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1});
        assertBasis(new int[]{0, -1, 0}, new int[]{1, 0, 0}, new int[]{0, 0, -1});
        assertBasis(new int[]{0, 0, -1}, new int[]{-1, 0, 0}, new int[]{0, -1, 0});
        assertBasis(new int[]{0, 0, 1}, new int[]{1, 0, 0}, new int[]{0, -1, 0});
        assertBasis(new int[]{-1, 0, 0}, new int[]{0, 0, 1}, new int[]{0, -1, 0});
        assertBasis(new int[]{1, 0, 0}, new int[]{0, 0, -1}, new int[]{0, -1, 0});

        int[] normal = {0, 1, 0};
        assertArrayEquals(new int[]{0, 1}, delta(normal, new int[]{0, 0, 1}, new int[]{1, 0, 0}, 1, 0));
        assertArrayEquals(new int[]{-1, 0}, delta(normal, new int[]{-1, 0, 0}, new int[]{0, 0, 1}, 1, 0));
    }

    private static void assertBasis(int[] normal, int[] tangent, int[] binormal) {
        assertArrayEquals(new int[]{1, 0}, delta(normal, tangent, binormal, 1, 0));
        assertArrayEquals(new int[]{0, 1}, delta(normal, tangent, binormal, 0, 1));
    }

    private static int[] delta(int[] normal, int[] tangent, int[] binormal, int u, int v) {
        int x = tangent[0] * u + binormal[0] * v;
        int y = tangent[1] * u + binormal[1] * v;
        int z = tangent[2] * u + binormal[2] * v;
        if (normal[1] < 0) return new int[]{x, -z};
        if (normal[1] > 0) return new int[]{x, z};
        if (normal[2] < 0) return new int[]{-x, -y};
        if (normal[2] > 0) return new int[]{x, -y};
        if (normal[0] < 0) return new int[]{z, -y};
        return new int[]{-z, -y};
    }
}
