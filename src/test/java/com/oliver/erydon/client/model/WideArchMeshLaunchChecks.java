package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Method;
import java.util.List;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;

/** Verifies clipping, area conservation, all facings and geometry-cache width separation. */
public final class WideArchMeshLaunchChecks {
    private WideArchMeshLaunchChecks() { }

    public static void run(ArchRomanesqueBlock[] families) throws Exception {
        try (SpriteContents contents = new SpriteContents(new Identifier("erydon", "wide_arch_probe"),
                new SpriteDimensions(16,16), new NativeImage(16,16,false), AnimationResourceMetadata.EMPTY)) {
            run(families, new TestSprite(contents));
        }
    }

    private static void run(ArchRomanesqueBlock[] families, Sprite sprite) throws Exception {
        Method layout = ArchRomanesqueBlock.class.getDeclaredMethod("computeArrangement",
                int.class, int.class, int.class, int.class, StyleSet.class);
        layout.setAccessible(true);
        int cases = 0;
        for (var family : families) for (int width = 4; width <= 6; width++) {
            int rows = (2 * width + 2) / 3;
            for (Direction facing : Direction.Type.HORIZONTAL) {
                double area = 0;
                for (int row = 0; row < rows; row++) for (int x = 0; x < width; x++) {
                    Arrangement target = (Arrangement) layout.invoke(family, width, rows, x, row, StyleSet.BASE);
                    var state = family.getDefaultState().with(WIDTH, width).with(FACING, facing).with(ARRANGEMENT, target);
                    List<BakedQuad> clipped = WideArchQuads.create(state, (child, face) ->
                            child.get(ARRANGEMENT) == Arrangement.TOP_LARGE && face == null ? List.of(front(sprite, 1)) : List.of());
                    for (BakedQuad quad : clipped) {
                        area += area(quad);
                        for (int vertex = 0; vertex < 4; vertex++) for (int axis = 0; axis < 3; axis++) {
                            float coordinate = Float.intBitsToFloat(quad.getVertexData()[vertex * 8 + axis]);
                            require(coordinate >= -1e-6 && coordinate <= 1+1e-6, "Clipped mesh left its cell");
                        }
                        Direction expected = Direction.SOUTH;
                        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
                        for (int i = 0; i < turns; i++) expected = expected.rotateYClockwise();
                        require(quad.getFace() == expected, "Wrong wide quad face after rotation");
                        int packed = quad.getVertexData()[7];
                        require((byte) packed == expected.getOffsetX()*127
                                && (byte) (packed >> 16) == expected.getOffsetZ()*127, "Wide mesh normal did not rotate");
                        for (int vertex = 0; vertex < 4; vertex++) {
                            int start = vertex * 8;
                            float xPos = Float.intBitsToFloat(quad.getVertexData()[start]);
                            float yPos = Float.intBitsToFloat(quad.getVertexData()[start+1]);
                            float zPos = Float.intBitsToFloat(quad.getVertexData()[start+2]);
                            float u = switch (expected) { case NORTH -> 1-xPos; case EAST -> 1-zPos; case WEST -> zPos; default -> xPos; };
                            require(Math.abs(Float.intBitsToFloat(quad.getVertexData()[start+4]) - sprite.getFrameU(u*16)) < 1e-6,
                                    "Preview UV did not retain its world-cell phase");
                            require(Math.abs(Float.intBitsToFloat(quad.getVertexData()[start+5]) - sprite.getFrameV((1-yPos)*16)) < 1e-6,
                                    "Preview UV stretched vertically");
                        }
                    }
                }
                double expected = width / 3.0 * width / 3.0;
                require(Math.abs(area - expected) < 1e-5, "Clipping duplicated or lost a component surface: " + area + " != " + expected);
                cases++;
            }
        }
        for (int width=4; width<=6; width++) {
            int rows = (2*width+2)/3;
            double area = 0;
            for (int x=0; x<width; x++) {
                Arrangement target = (Arrangement) layout.invoke(families[0], width, rows+2, x, rows, StyleSet.COLUMN);
                if (!target.isWide()) continue;
                var state = families[0].getDefaultState().with(WIDTH,width).with(ARRANGEMENT,target);
                for (BakedQuad quad : WideArchQuads.create(state, (child, face) ->
                        child.get(ARRANGEMENT) == Arrangement.TRIPLE_COLUMN_UPPER_L && face == null
                                ? List.of(front(sprite, 0.7F)) : List.of())) area += area(quad);
            }
            require(Math.abs(area-0.7*width/3) < 1e-5, "Column transition was cut off at its owning cell");
            cases++;
        }
        System.out.println("ERYDON_WIDE_ARCH_MESH_OK cases=" + cases);
    }

    private static BakedQuad front(Sprite sprite, float maxX) {
        int[] data = new int[32];
        float[][] positions = {{0,0,1},{maxX,0,1},{maxX,1,1},{0,1,1}};
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) data[vertex*8+axis] = Float.floatToRawIntBits(positions[vertex][axis]);
            data[vertex*8+3] = -1;
            data[vertex*8+4] = Float.floatToRawIntBits(sprite.getFrameU(positions[vertex][0]*16));
            data[vertex*8+5] = Float.floatToRawIntBits(sprite.getFrameV((1-positions[vertex][1])*16));
            data[vertex*8+7] = 127 << 16;
        }
        return new BakedQuad(data, -1, Direction.SOUTH, sprite, true);
    }

    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "probe_atlas"), contents, 64,64,16,32); }
    }

    private static double area(BakedQuad quad) {
        return triangle(quad.getVertexData(), 0, 1, 2) + triangle(quad.getVertexData(), 0, 2, 3);
    }

    private static double triangle(int[] data, int a, int b, int c) {
        double[] u = new double[3], v = new double[3];
        for (int axis=0; axis<3; axis++) {
            u[axis] = Float.intBitsToFloat(data[b*8+axis])-Float.intBitsToFloat(data[a*8+axis]);
            v[axis] = Float.intBitsToFloat(data[c*8+axis])-Float.intBitsToFloat(data[a*8+axis]);
        }
        double x=u[1]*v[2]-u[2]*v[1], y=u[2]*v[0]-u[0]*v[2], z=u[0]*v[1]-u[1]*v[0];
        return Math.sqrt(x*x+y*y+z*z)/2;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
