package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.WideArchLayout;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
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
            for (StyleSet style : family.getClass() == ArchRomanesqueBlock.class
                    ? StyleSet.values() : new StyleSet[]{StyleSet.BASE})
            for (Direction facing : Direction.Type.HORIZONTAL) {
                double area = 0;
                for (int row = 0; row < rows; row++) for (int x = 0; x < width; x++) {
                    Arrangement target = (Arrangement) layout.invoke(family, width, rows, x, row, style);
                    var state = family.getDefaultState().with(WIDTH, width).with(FACING, facing).with(ARRANGEMENT, target);
                    List<BakedQuad> clipped = WideArchQuads.create(state, child ->
                            child.get(ARRANGEMENT).hasTopLarge() ? List.of(front(sprite, 1)) : List.of());
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
                for (BakedQuad quad : WideArchQuads.create(state, child ->
                        child.get(ARRANGEMENT) == Arrangement.TRIPLE_COLUMN_UPPER_L
                                ? List.of(front(sprite, 0.7F)) : List.of())) area += area(quad);
            }
            require(Math.abs(area-0.7*width/3) < 1e-5, "Column transition was cut off at its owning cell");
            cases++;
        }
        cases += completeComponentSources(families, sprite, layout);
        ArchRepeatSourceLaunchChecks.run(families, sprite);
        RawArchModelLoadingLaunchChecks.run(sprite);
        System.out.println("ERYDON_WIDE_ARCH_MESH_OK cases=" + cases);
    }

    private static int completeComponentSources(ArchRomanesqueBlock[] families, Sprite sprite, Method layout) throws Exception {
        BakedQuad overlay = surface(sprite, Direction.SOUTH, 17);
        List<BakedQuad> culled = Arrays.stream(Direction.values()).map(face -> surface(sprite, face, face.ordinal())).toList();
        List<BakedQuad> complete = new ArrayList<>(List.of(overlay, overlay));
        complete.addAll(culled);
        int cases = 0;
        for (var family : families) for (int width = 4; width <= 6; width++) {
            int rows = (2 * width + 2) / 3;
            for (StyleSet style : family.getClass() == ArchRomanesqueBlock.class
                    ? StyleSet.values() : new StyleSet[]{StyleSet.BASE})
            for (Direction facing : Direction.Type.HORIZONTAL) for (boolean reflected : new boolean[]{false, true}) {
                int overlaySurvivors = 0;
                for (int row = 0; row < rows; row++) for (int x = 0; x < width; x++) {
                    Arrangement target = (Arrangement) layout.invoke(family, width, rows, x, row, style);
                    BlockState state = family.getDefaultState().with(WIDTH, width).with(FACING, facing)
                            .with(REFLECTED, reflected).with(ARRANGEMENT, target);
                    List<BlockState> queried = new ArrayList<>();
                    int[] bucketCalls = new int[7];
                    BakedModel mixed = (BakedModel) Proxy.newProxyInstance(WideArchMeshLaunchChecks.class.getClassLoader(),
                            new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                                require(method.getName().equals("getQuads"), "Unexpected fixture model query");
                                Direction face = (Direction) args[1];
                                bucketCalls[face == null ? 0 : face.ordinal() + 1]++;
                                return face == null ? List.of(overlay, overlay) : List.of(culled.get(face.ordinal()));
                            });
                    List<BakedQuad> actual = WideArchQuads.create(state, child -> {
                        require(!queried.contains(child), "Wide arch queried a complete component more than once");
                        queried.add(child);
                        require(child.get(WIDTH) == 3 && child.get(FACING) == Direction.NORTH
                                && child.get(REFLECTED) == reflected, "Wrong source component state");
                        require(child.get(ARRANGEMENT).isColumnStyle() == target.isColumnStyle(),
                                "Projected crown mixed column and plain sources");
                        List<BakedQuad> assembled = new ArrayList<>();
                        // Match the production arch's complete null-face assembly.
                        AxiomFallbackQuads.add(assembled, mixed, 0, null, Random.create(0), true);
                        return reflectSource(child, assembled);
                    });
                    int components = WideArchLayout.components(target).size();
                    require(queried.size() == components, "Wide arch omitted a component source");
                    for (int calls : bucketCalls) require(calls == components, "Source face bucket duplicated or omitted");

                    // Explicitly flatten the fixture once, independently of the face-bucket collector.
                    // Compare every surviving vertex word and quad attribute after the unchanged clipping path.
                    List<BakedQuad> expected = WideArchQuads.create(state, child -> reflectSource(child, complete));
                    require(actual.size() == expected.size(), "Mixed source collection duplicated or lost surfaces");
                    for (int i = 0; i < actual.size(); i++) requireSameQuad(expected.get(i), actual.get(i));
                    long overlays = actual.stream().filter(quad -> quad.getColorIndex() == 17).count();
                    int singleOverlay = WideArchQuads.create(state, child -> reflectSource(child, List.of(overlay))).size();
                    require(overlays == 2L * singleOverlay, "Legitimate coincident overlays were deduplicated");
                    overlaySurvivors += singleOverlay;
                }
                require(overlaySurvivors > 0, "Overlay preservation fixture produced no surviving surfaces");
                cases++;
            }
        }
        return cases;
    }

    private static List<BakedQuad> reflectSource(BlockState child, List<BakedQuad> quads) {
        if (!child.get(REFLECTED)) return quads;
        var reflection = ArchHorizontalReflection.forState(child.get(FACING), child.get(ARRANGEMENT).hasTopLarge());
        return quads.stream().map(reflection::reflect).toList();
    }

    private static void requireSameQuad(BakedQuad expected, BakedQuad actual) {
        require(Arrays.equals(expected.getVertexData(), actual.getVertexData()),
                "Surviving positions, UVs, colors, light or normals changed");
        require(expected.getFace() == actual.getFace() && expected.getSprite() == actual.getSprite()
                && expected.getColorIndex() == actual.getColorIndex() && expected.hasShade() == actual.hasShade(),
                "Surviving face, sprite, tint or shade changed");
    }

    private static BakedQuad surface(Sprite sprite, Direction face, int tint) {
        float[][] positions = switch (face) {
            case DOWN -> new float[][]{{0,0,0},{1,0,0},{1,0,1},{0,0,1}};
            case UP -> new float[][]{{0,1,1},{1,1,1},{1,1,0},{0,1,0}};
            case NORTH -> new float[][]{{1,0,0},{0,0,0},{0,1,0},{1,1,0}};
            case SOUTH -> new float[][]{{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
            case WEST -> new float[][]{{0,0,0},{0,0,1},{0,1,1},{0,1,0}};
            case EAST -> new float[][]{{1,0,1},{1,0,0},{1,1,0},{1,1,1}};
        };
        int[] data = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int start = vertex * 8;
            for (int axis = 0; axis < 3; axis++) data[start + axis] = Float.floatToRawIntBits(positions[vertex][axis]);
            data[start + 3] = 0xFF708090 + tint;
            data[start + 4] = Float.floatToRawIntBits(sprite.getFrameU(vertex % 2 * 16));
            data[start + 5] = Float.floatToRawIntBits(sprite.getFrameV(vertex / 2 * 16));
            data[start + 6] = 0x00D000B0 + tint;
            data[start + 7] = (face.getOffsetX() * 127 & 255) | ((face.getOffsetY() * 127 & 255) << 8)
                    | ((face.getOffsetZ() * 127 & 255) << 16);
        }
        return new BakedQuad(data, tint, face, sprite, tint % 2 == 0);
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
