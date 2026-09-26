package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchHorizontalReflectionTest {
    @Test
    void reflectedSideReversesDepthButCentredKeystoneReversesWidth() {
        var northSide = ArchHorizontalReflection.forState(Direction.NORTH, false);
        var northCentre = ArchHorizontalReflection.forState(Direction.NORTH, true);
        assertEquals(Direction.NORTH, northSide.face(Direction.SOUTH));
        assertEquals(Direction.EAST, northSide.face(Direction.EAST));
        assertEquals(Direction.WEST, northCentre.face(Direction.EAST));
        assertEquals(Direction.NORTH, northCentre.face(Direction.NORTH));

        var eastSide = ArchHorizontalReflection.forState(Direction.EAST, false);
        var eastCentre = ArchHorizontalReflection.forState(Direction.EAST, true);
        assertEquals(Direction.WEST, eastSide.face(Direction.EAST));
        assertEquals(Direction.SOUTH, eastCentre.face(Direction.NORTH));
    }

    @Test
    void twoMirrorsRestorePositionUvWindingAndNormals() {
        int[] data = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            data[offset] = Float.floatToRawIntBits(0.125F + vertex * 0.125F);
            data[offset + 1] = Float.floatToRawIntBits(0.25F + vertex * 0.125F);
            data[offset + 2] = Float.floatToRawIntBits(0.25F + vertex * 0.125F);
            data[offset + 4] = Float.floatToRawIntBits(vertex * 0.2F);
            data[offset + 5] = Float.floatToRawIntBits(vertex * 0.3F);
            data[offset + 7] = 0x007F0000; // south-facing unit normal
        }
        BakedQuad original = new BakedQuad(data, -1, Direction.SOUTH, null, true);
        var reflection = ArchHorizontalReflection.forState(Direction.NORTH, false);
        BakedQuad once = reflection.reflect(original);
        BakedQuad twice = reflection.reflect(once);

        assertEquals(Direction.NORTH, once.getFace());
        assertEquals(Direction.SOUTH, twice.getFace());
        assertEquals(Float.floatToRawIntBits(0.75F), once.getVertexData()[2]);
        assertEquals(data[3 * 8 + 4], once.getVertexData()[8 + 4]);
        assertArrayEquals(data, twice.getVertexData());
    }
}
