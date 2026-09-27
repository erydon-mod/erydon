package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleColumnSliceTest {
    @Test
    void fullTopFaceIsClippedIntoFourLocalCtmCells() {
        BakedQuad original = quad(Direction.UP,
                new float[]{0, 0, 0}, new float[]{1, 0, 0},
                new float[]{1, 0, 1}, new float[]{0, 0, 1});
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            List<BakedQuad> pieces = DoubleColumnSlice.bake(original, 0, x, z, true);
            assertEquals(1, pieces.size());
            assertLocal(pieces);
        }
    }

    @Test
    void crossingShaftAndTallBaseFacesStayInsideTheirOwnCells() {
        BakedQuad diagonal = quad(Direction.UP,
                new float[]{0.1F, 0, 0.4F}, new float[]{0.6F, 0, 0.1F},
                new float[]{0.9F, 0, 0.6F}, new float[]{0.4F, 0, 0.9F});
        int count = 0;
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            List<BakedQuad> pieces = DoubleColumnSlice.bake(diagonal, 0, x, z, true);
            assertLocal(pieces);
            count += pieces.size();
        }
        assertTrue(count >= 4);

        BakedQuad tall = quad(Direction.NORTH,
                new float[]{0, 0, 0}, new float[]{1, 0, 0},
                new float[]{1, 1, 0}, new float[]{0, 1, 0});
        for (int layer = 0; layer < 2; layer++) for (int x = 0; x < 2; x++) {
            List<BakedQuad> pieces = DoubleColumnSlice.bake(tall, layer, x, 0, false);
            assertFalse(pieces.isEmpty());
            assertLocal(pieces);
        }
    }

    private static void assertLocal(List<BakedQuad> pieces) {
        for (BakedQuad piece : pieces) {
            int[] data = piece.getVertexData();
            for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) {
                float coordinate = Float.intBitsToFloat(data[i * 8 + axis]);
                assertTrue(coordinate >= -0.0001F && coordinate <= 1.0001F,
                        "A CTM fragment left its block cell: " + coordinate);
            }
        }
    }

    private static BakedQuad quad(Direction face, float[]... vertices) {
        int[] data = new int[32];
        for (int i = 0; i < 4; i++) {
            data[i * 8] = Float.floatToRawIntBits(vertices[i][0]);
            data[i * 8 + 1] = Float.floatToRawIntBits(vertices[i][1]);
            data[i * 8 + 2] = Float.floatToRawIntBits(vertices[i][2]);
            data[i * 8 + 3] = -1;
        }
        return new BakedQuad(data, -1, face, null, true);
    }
}
