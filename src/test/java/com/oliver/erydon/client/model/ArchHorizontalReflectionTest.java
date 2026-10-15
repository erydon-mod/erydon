package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchHorizontalReflectionTest {
    @Test
    void authoredDepthMirrorKeepsWorldUvPhaseInsideTheOriginalSprite() {
        try (var contents = new SpriteContents(new Identifier("erydon", "arch_mirror_fixture"),
                new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new Sprite(new Identifier("erydon", "fixture_atlas"), contents, 64, 64, 16, 32) { };
            for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                var mirror = ArchHorizontalReflection.forDepthMirror(facing);
                for (Direction face : Direction.values()) {
                    int[] data = new int[32];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int offset = vertex * 8;
                        data[offset] = Float.floatToRawIntBits(.125F + vertex * .125F);
                        data[offset + 1] = Float.floatToRawIntBits(.25F);
                        data[offset + 2] = Float.floatToRawIntBits(.25F + vertex * .125F);
                        data[offset + 4] = Float.floatToRawIntBits(sprite.getFrameU(2 + vertex * 2));
                        data[offset + 5] = Float.floatToRawIntBits(sprite.getFrameV(4 + vertex * 2));
                        data[offset + 7] = (face.getOffsetX() * 127 & 255)
                                | ((face.getOffsetY() * 127 & 255) << 8) | ((face.getOffsetZ() * 127 & 255) << 16);
                    }
                    BakedQuad original = new BakedQuad(data, -1, face, sprite, true);
                    BakedQuad reflected = mirror.reflect(original);
                    boolean flipU = face.getAxis() != Direction.Axis.Y || facing.getAxis() == Direction.Axis.X;
                    assertEquals(sprite.getFrameU(flipU ? 14 : 2),
                            Float.intBitsToFloat(reflected.getVertexData()[4]), .000001);
                    assertEquals(sprite.getFrameV(flipU ? 4 : 12),
                            Float.intBitsToFloat(reflected.getVertexData()[5]), .000001);
                    assertEquals(mirror.face(face), reflected.getFace());
                    assertArrayEquals(data, mirror.reflect(reflected).getVertexData());
                }
            }
        }
    }

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
