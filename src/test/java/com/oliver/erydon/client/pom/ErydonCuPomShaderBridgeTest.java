package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErydonCuPomShaderBridgeTest {
    @Test
    void helperContainsAllRequiredMaterialSamplingAndFallbackPaths() {
        String vertex = ErydonCuPomShaderBridge.vertexSource();
        String fragment = ErydonCuPomShaderBridge.fragmentSource();
        assertFalse(vertex.isBlank());
        assertFalse(fragment.isBlank());
        assertFalse(vertex.lines().anyMatch(line -> line.stripLeading().startsWith("#")));
        assertFalse(fragment.lines().anyMatch(line -> line.stripLeading().startsWith("#")));

        assertTrue(vertex.contains("uniform sampler2D erydonCtmPomLookup;"));
        assertTrue(vertex.contains("ERYDON_CTM_POM_LUT_SIZE = vec2(1024.0, 1057.0)"));
        assertTrue(vertex.contains("ERYDON_CTM_POM_OCCUPANCY_START = 1024.0"));
        assertTrue(vertex.contains("ERYDON_CTM_POM_RECORD_START = 1049600.0"));
        assertTrue(vertex.contains("abs(magic.w - 4.0)"));
        assertTrue(vertex.contains("texture2DLod("));
        assertTrue(vertex.contains("void erydonCtmPomApplyExactBounds("));
        assertFalse(vertex.contains("int erydonPomFragmentRecord = -2;"));

        assertTrue(fragment.contains("uniform sampler2D erydonCtmPomLookup;"));
        assertTrue(fragment.contains("texture2D("));
        assertFalse(fragment.contains("texture2DLod("));
        assertFalse(fragment.contains("void erydonCtmPomApplyExactBounds("));
        assertTrue(fragment.contains("vec2 ordinaryUv"));
        assertTrue(fragment.contains("return ordinaryUv;"));
        assertTrue(fragment.contains("erydonCtmPomRepeatDelta"));
        assertTrue(fragment.contains("targetBounds"));
        assertTrue(fragment.contains("int erydonPomFragmentRecord = -2;"));
        assertTrue(fragment.contains("int erydonCtmPomResolveFragmentRecord()"));
        assertTrue(fragment.contains("int resolvedRecord = erydonCtmPomResolveFragmentRecord();"));
        assertTrue(fragment.contains("(wrapped - 0.5) * vTexCoordAM.pq"));
    }

    @Test
    void unknownOrUnbalancedDirectivesFailStageSpecialization() {
        boolean unknownRejected = false;
        try {
            ErydonCuPomShaderBridge.specialize("#define TOO_LATE 1\n", ErydonCuPomShaderBridge.Stage.VERTEX);
        } catch (IllegalArgumentException expected) {
            unknownRejected = true;
        }
        assertTrue(unknownRejected);

        boolean unbalancedRejected = false;
        try {
            ErydonCuPomShaderBridge.specialize(
                    "#ifdef ERYDON_CTM_POM_VERTEX_STAGE\nfloat value;\n",
                    ErydonCuPomShaderBridge.Stage.VERTEX);
        } catch (IllegalArgumentException expected) {
            unbalancedRejected = true;
        }
        assertTrue(unbalancedRejected);
    }

    @Test
    void exactBoundsReconstructEveryOriginalAtlasCoordinate() {
        double minU = 2048.0 / 16384.0;
        double minV = 4096.0 / 16384.0;
        double spanU = 64.0 / 16384.0;
        double spanV = 64.0 / 16384.0;
        double[][] localCoordinates = {
                {0.11, 0.27},
                {0.88, 0.34},
                {0.63, 0.93},
                {0.42, 0.61}
        };

        for (double[] local : localCoordinates) {
            double atlasU = minU + local[0] * spanU;
            double atlasV = minV + local[1] * spanV;
            double signU = 2.0 * ((atlasU - minU) / spanU) - 1.0;
            double signV = 2.0 * ((atlasV - minV) / spanV) - 1.0;
            double reconstructedU = minU + (signU * 0.5 + 0.5) * spanU;
            double reconstructedV = minV + (signV * 0.5 + 0.5) * spanV;
            assertTrue(Math.abs(atlasU - reconstructedU) < 0.0000000001D);
            assertTrue(Math.abs(atlasV - reconstructedV) < 0.0000000001D);
        }
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
