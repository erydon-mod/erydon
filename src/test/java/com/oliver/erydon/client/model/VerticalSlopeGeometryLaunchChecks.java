package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.block.GlazingShallowSlopeBlock;
import com.oliver.erydon.block.GlazingSlopeBlock;
import com.oliver.erydon.block.GlazingVerticalSlopeBlock;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sun.misc.Unsafe;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Actual vertical renderer and authored glass meshes, without a MinecraftClient or a real world. */
public final class VerticalSlopeGeometryLaunchChecks {
    private static final double EPS = .00001;
    private static final Map<String, JsonObject> RESOURCES = new HashMap<>();
    private static final String[] FINISHES = {"tinted", "silver", "crystal", "bronze"};

    public static void run() throws Exception {
        int verticalStates = 0, glassStates = 0, mirrorRays = 0;
        List<Block> walls = List.of(new SlopeVerticalBlock(AbstractBlock.Settings.copy(Blocks.STONE)),
                new SlopeVerticalShallowBroadBlock(AbstractBlock.Settings.copy(Blocks.STONE)),
                new SlopeVerticalShallowNarrowBlock(AbstractBlock.Settings.copy(Blocks.STONE)));
        try (SpriteContents contents = new SpriteContents(new Identifier("erydon", "vertical_shape_probe"),
                new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new TestSprite(contents);
            BakedModel wrapped = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(), new Class<?>[]{BakedModel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getParticleSprite" -> sprite;
                        case "getTransformation" -> ModelTransformation.NONE;
                        case "getOverrides" -> ModelOverrideList.EMPTY;
                        case "getQuads" -> List.of();
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            for (Block wall : walls) {
                SlopeVerticalBakedModel model = new SlopeVerticalBakedModel(wrapped);
                for (BlockState state : wall.getStateManager().getStates()) {
                    VoxelShape shape = sameInteractionShapes(wall, state);
                    List<Triangle> mesh = baked(model.getQuads(state, null, Random.create(0)));
                    double tolerance = wall instanceof SlopeVerticalBlock ? 1.0 / 16 : 1.0 / 32;
                    int rayAxis = state.get(Properties.HORIZONTAL_FACING).getAxis() == Direction.Axis.X ? 2 : 0;
                    // Horizontal rays hit the actual full-height wedge boundary, including near every slice edge.
                    for (int iz = 0; iz < 256; iz++) {
                        double z = (iz + .03125) / 256;
                        double first = rayAxis == 0 ? .5 : z, second = rayAxis == 0 ? z : .5;
                        double[] rendered = bounds(mesh, rayAxis, first, second);
                        double[] solid = bounds(shape.getBoundingBoxes(), rayAxis, first, second);
                        if (!Double.isFinite(rendered[0])) continue;
                        require(solid[0] <= rendered[0] + EPS && solid[1] >= rendered[1] - EPS,
                                "Vertical outline misses visible face/tip: " + state + " z=" + z);
                        require(rendered[0] - solid[0] <= tolerance + EPS && solid[1] - rendered[1] <= tolerance + EPS,
                                "Vertical conservative envelope grew too far: " + state);
                    }
                    // Mirrors and rotations must transform the whole cached solid, not merely its facing label.
                    for (BlockMirror mirror : BlockMirror.values()) {
                        BlockState reflected = wall.mirror(state, mirror);
                        VoxelShape expected = transform(shape, mirror, BlockRotation.NONE);
                        require(!VoxelShapes.matchesAnywhere(expected, sameInteractionShapes(wall, reflected), BooleanBiFunction.NOT_SAME),
                                "Vertical mirror changed the physical wedge: " + state + " " + mirror);
                    }
                    for (BlockRotation rotation : BlockRotation.values()) {
                        require(!VoxelShapes.matchesAnywhere(transform(shape, BlockMirror.NONE, rotation),
                                        sameInteractionShapes(wall, wall.rotate(state, rotation)), BooleanBiFunction.NOT_SAME),
                                "Vertical rotation changed the physical wedge: " + state + " " + rotation);
                    }
                    require(shape.getMin(Direction.Axis.Y) == 0 && shape.getMax(Direction.Axis.Y) == 1,
                            "Wall wedge stopped being a full-height prism");
                    verticalStates++;
                }
            }
        }
        int placements = placement(walls);
        for (String finish : FINISHES) {
            for (Block glass : List.of(new GlazingSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS)),
                    new GlazingVerticalSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS)))) {
                String id = "glazing_framed_" + finish + (glass instanceof GlazingSlopeBlock ? "_slope" : "_slope_vertical");
                for (BlockState state : glass.getStateManager().getStates()) {
                    List<Triangle> mesh = authored(render(id, state));
                    VoxelShape shape = sameInteractionShapes(glass, state);
                    if (glass instanceof GlazingSlopeBlock) {
                        // Both top and bottom authored variants use their full X/Y blockstate transform.
                        for (int ix = 0; ix < 16; ix++) for (int iz = 0; iz < 32; iz++) {
                            double x = (ix + .5) / 16, z = (iz + .5) / 32;
                            compare(bounds(mesh, 1, x, z), bounds(shape.getBoundingBoxes(), 1, x, z), .065,
                                    "Glass slope render/outline mismatch: " + id + " " + state);
                        }
                    } else {
                        for (double y : new double[]{.01, .5, .99}) for (int iz = 0; iz < 32; iz++) {
                            double z = (iz + .5) / 32;
                            // The existing 2/16-wide vertical frame staircase overreaches the thin
                            // authored pane by up to .098327; this review preserves that baseline.
                            compare(bounds(mesh, 0, y, z), bounds(shape.getBoundingBoxes(), 0, y, z), .1,
                                    "Vertical glass render/outline mismatch: " + id + " " + state);
                        }
                    }
                    glassStates++;
                }
            }
            for (var variant : GlazingShallowSlopeBlock.Variant.values()) {
                GlazingShallowSlopeBlock glass = new GlazingShallowSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS), variant);
                String id = "glazing_framed_" + finish + "_shallow_slope_" + variant.name().toLowerCase(java.util.Locale.ROOT);
                for (BlockState state : glass.getStateManager().getStates()) {
                    if (state.get(Properties.WATERLOGGED)) continue;
                    sameInteractionShapes(glass, state);
                    List<Triangle> original = authored(render(id, state));
                    for (BlockMirror mirror : BlockMirror.values()) {
                        BlockState reflected = glass.mirror(state, mirror);
                        if (mirror == BlockMirror.NONE) require(reflected == state, "NONE changed saved glass state");
                        require(reflected.get(Properties.BLOCK_HALF) == state.get(Properties.BLOCK_HALF), "Mirror changed saved section");
                        List<Triangle> result = authored(render(id, reflected));
                        for (double x : new double[]{.15, .25, .4, .6, .75, .85}) {
                            for (double z : new double[]{.15, .25, .4, .6, .75, .85}) {
                                double originalX = mirror == BlockMirror.FRONT_BACK ? 1 - x : x;
                                double originalZ = mirror == BlockMirror.LEFT_RIGHT ? 1 - z : z;
                                // The authored inner/outer glass arms differ by up to .0029 after reflection.
                                compare(bounds(original, 1, originalX, originalZ), bounds(result, 1, x, z), .0031,
                                        "Authored shallow glass mirror points to the wrong arm: " + state + " " + mirror);
                                mirrorRays++;
                            }
                        }
                    }
                }
            }
        }
        System.out.println("ERYDON_VERTICAL_SLOPE_GEOMETRY_OK: verticalStates=" + verticalStates
                + " placementCases=" + placements + " glazingStates=" + glassStates + " authoredMirrorRays=" + mirrorRays);
    }

    private static VoxelShape sameInteractionShapes(Block block, BlockState state) {
        var outline = block.getOutlineShape(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
        var collision = block.getCollisionShape(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
        require(!VoxelShapes.matchesAnywhere(outline, collision, BooleanBiFunction.NOT_SAME), "Collision differs from outline: " + state);
        return outline;
    }

    private static int placement(List<Block> walls) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        TestWorld world = (TestWorld) ((Unsafe) field.get(null)).allocateInstance(TestWorld.class);
        int count = 0;
        for (Block block : walls) for (Direction facing : Direction.Type.HORIZONTAL) for (Direction side : Direction.values()) {
            for (double x : new double[]{.25, .75}) for (double z : new double[]{.25, .75}) for (boolean water : new boolean[]{false, true}) {
                world.water = water;
                var context = new AutomaticItemPlacementContext(world, BlockPos.ORIGIN, facing, ItemStack.EMPTY, side) {
                    @Override public Vec3d getHitPos() { return new Vec3d(x, .5, z); }
                };
                BlockState placed = block.getPlacementState(context);
                require(placed != null && placed.get(Properties.WATERLOGGED) == water, "Wall placement lost waterlogging");
                if (side.getAxis().isHorizontal()) {
                    require(placed.get(Properties.HORIZONTAL_FACING) == facing.getOpposite(), "Side placement changed wall facing");
                } else {
                    Direction quadrant = x < .5 ? (z < .5 ? Direction.NORTH : Direction.WEST)
                            : (z < .5 ? Direction.EAST : Direction.SOUTH);
                    boolean left = switch (facing) {
                        case NORTH -> x < .5; case SOUTH -> x >= .5; case EAST -> z < .5; case WEST -> z >= .5;
                        default -> throw new AssertionError(facing);
                    };
                    Direction expected = block instanceof SlopeVerticalBlock || !left ? quadrant : quadrant.rotateYClockwise();
                    require(placed.get(Properties.HORIZONTAL_FACING) == expected, "Floor/ceiling placement changed wall quadrant");
                    if (block instanceof SlopeVerticalShallowBroadBlock) require((placed.get(SlopeVerticalShallowBroadBlock.HAND)
                            == SlopeVerticalShallowBroadBlock.Handedness.LEFT) == left, "Broad wall placement changed hand");
                    if (block instanceof SlopeVerticalShallowNarrowBlock) require((placed.get(SlopeVerticalShallowNarrowBlock.HAND)
                            == SlopeVerticalShallowNarrowBlock.Handedness.LEFT) == left, "Narrow wall placement changed hand");
                }
                for (Direction neighbour : Direction.values()) {
                    require(block.getStateForNeighborUpdate(placed, neighbour, block.getDefaultState(), world,
                                    BlockPos.ORIGIN, BlockPos.ORIGIN.offset(neighbour)) == placed,
                            "Fixed wall wedge unexpectedly auto-formed a corner");
                }
                count++;
            }
        }
        return count;
    }

    private static VoxelShape transform(VoxelShape shape, BlockMirror mirror, BlockRotation rotation) {
        VoxelShape[] result = {VoxelShapes.empty()};
        int turns = switch (rotation) { case NONE -> 0; case CLOCKWISE_90 -> 1; case CLOCKWISE_180 -> 2; case COUNTERCLOCKWISE_90 -> 3; };
        for (Box box : shape.getBoundingBoxes()) {
            double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? box.minX : box.maxX, y = (i & 2) == 0 ? box.minY : box.maxY;
                double z = (i & 4) == 0 ? box.minZ : box.maxZ;
                if (mirror == BlockMirror.FRONT_BACK) x = 1 - x;
                if (mirror == BlockMirror.LEFT_RIGHT) z = 1 - z;
                for (int j = 0; j < turns; j++) { double old = x; x = 1 - z; z = old; }
                double[] p = {x, y, z};
                for (int axis = 0; axis < 3; axis++) { low[axis] = Math.min(low[axis], p[axis]); high[axis] = Math.max(high[axis], p[axis]); }
            }
            result[0] = VoxelShapes.union(result[0], VoxelShapes.cuboid(low[0], low[1], low[2], high[0], high[1], high[2]));
        }
        return result[0];
    }

    private static JsonObject render(String id, BlockState state) {
        for (var entry : resource("blockstates/" + id + ".json").getAsJsonObject("variants").entrySet()) {
            boolean matches = true;
            for (String term : entry.getKey().split(",")) {
                String[] pair = term.split("=");
                var property = state.getBlock().getStateManager().getProperty(pair[0]);
                if (property == null || !state.get(property).equals(property.parse(pair[1]).orElse(null))) { matches = false; break; }
            }
            if (matches) return entry.getValue().getAsJsonObject();
        }
        throw new AssertionError("Missing authored state: " + id + " " + state);
    }

    private static List<Triangle> authored(JsonObject render) {
        JsonObject model = resource("models/" + render.get("model").getAsString().substring("erydon:".length()) + ".json");
        while (!model.has("elements")) model = resource("models/" + model.get("parent").getAsString().substring("erydon:".length()) + ".json");
        int xRotation = render.has("x") ? render.get("x").getAsInt() : 0;
        int yRotation = render.has("y") ? render.get("y").getAsInt() : 0;
        var matrix = ModelRotation.get(xRotation, yRotation).getRotation().getMatrix();
        List<Triangle> result = new ArrayList<>();
        for (var value : model.getAsJsonArray("elements")) {
            JsonObject element = value.getAsJsonObject(); Vector3f[] vertices = new Vector3f[8];
            for (int i = 0; i < 8; i++) {
                Vector3f p = new Vector3f(element.getAsJsonArray((i & 1) == 0 ? "from" : "to").get(0).getAsFloat() / 16,
                        element.getAsJsonArray((i & 2) == 0 ? "from" : "to").get(1).getAsFloat() / 16,
                        element.getAsJsonArray((i & 4) == 0 ? "from" : "to").get(2).getAsFloat() / 16);
                if (element.has("rotation")) {
                    JsonObject r = element.getAsJsonObject("rotation"); var origin = r.getAsJsonArray("origin");
                    Vector3f centre = new Vector3f(origin.get(0).getAsFloat() / 16, origin.get(1).getAsFloat() / 16, origin.get(2).getAsFloat() / 16);
                    Vector3f axis = switch (r.get("axis").getAsString()) { case "x" -> new Vector3f(1, 0, 0); case "y" -> new Vector3f(0, 1, 0); case "z" -> new Vector3f(0, 0, 1); default -> throw new AssertionError(r); };
                    p.sub(centre).rotate(new Quaternionf().rotationAxis((float) Math.toRadians(r.get("angle").getAsDouble()), axis)).add(centre);
                    require(!r.has("rescale") || !r.get("rescale").getAsBoolean(), "Unexpected glazing element rescale");
                }
                matrix.transformPosition(p.sub(.5f, .5f, .5f)).add(.5f, .5f, .5f); vertices[i] = p;
            }
            for (String face : element.getAsJsonObject("faces").keySet()) {
                int[] index = switch (face) {
                    case "down" -> new int[]{0, 1, 5, 4}; case "up" -> new int[]{2, 6, 7, 3};
                    case "north" -> new int[]{0, 2, 3, 1}; case "south" -> new int[]{4, 5, 7, 6};
                    case "west" -> new int[]{0, 4, 6, 2}; case "east" -> new int[]{1, 3, 7, 5}; default -> throw new AssertionError(face);
                };
                result.add(new Triangle(vertices[index[0]], vertices[index[1]], vertices[index[2]]));
                result.add(new Triangle(vertices[index[0]], vertices[index[2]], vertices[index[3]]));
            }
        }
        return result;
    }

    private static List<Triangle> baked(List<BakedQuad> quads) {
        List<Triangle> result = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Vector3f[] p = new Vector3f[4]; int[] data = quad.getVertexData();
            for (int i = 0; i < 4; i++) p[i] = new Vector3f(Float.intBitsToFloat(data[i * 8]), Float.intBitsToFloat(data[i * 8 + 1]), Float.intBitsToFloat(data[i * 8 + 2]));
            result.add(new Triangle(p[0], p[1], p[2])); result.add(new Triangle(p[0], p[2], p[3]));
        }
        return result;
    }

    private static double[] bounds(List<Triangle> mesh, int axis, double first, double second) {
        int a = axis == 0 ? 1 : 0, b = axis == 2 ? 1 : 2;
        double[] result = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Triangle t : mesh) {
            double denominator = (t.b.get(b) - t.c.get(b)) * (t.a.get(a) - t.c.get(a)) + (t.c.get(a) - t.b.get(a)) * (t.a.get(b) - t.c.get(b));
            if (Math.abs(denominator) < 1e-10) continue;
            double u = ((t.b.get(b) - t.c.get(b)) * (first - t.c.get(a)) + (t.c.get(a) - t.b.get(a)) * (second - t.c.get(b))) / denominator;
            double v = ((t.c.get(b) - t.a.get(b)) * (first - t.c.get(a)) + (t.a.get(a) - t.c.get(a)) * (second - t.c.get(b))) / denominator;
            if (u < -1e-8 || v < -1e-8 || u + v > 1 + 1e-8) continue;
            double value = u * t.a.get(axis) + v * t.b.get(axis) + (1 - u - v) * t.c.get(axis);
            result[0] = Math.min(result[0], value); result[1] = Math.max(result[1], value);
        }
        return result;
    }

    private static double[] bounds(Iterable<Box> boxes, int axis, double first, double second) {
        double[] result = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Box box : boxes) {
            boolean hit = axis == 0 ? first >= box.minY && first <= box.maxY && second >= box.minZ && second <= box.maxZ
                    : axis == 1 ? first >= box.minX && first <= box.maxX && second >= box.minZ && second <= box.maxZ
                    : first >= box.minX && first <= box.maxX && second >= box.minY && second <= box.maxY;
            if (!hit) continue;
            result[0] = Math.min(result[0], axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ);
            result[1] = Math.max(result[1], axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ);
        }
        return result;
    }

    private static void compare(double[] expected, double[] actual, double tolerance, String label) {
        require(Double.isFinite(expected[0]) == Double.isFinite(actual[0]), label + " coverage differs");
        if (!Double.isFinite(expected[0])) return;
        require(Math.abs(expected[0] - actual[0]) <= tolerance && Math.abs(expected[1] - actual[1]) <= tolerance,
                label + " expected=" + expected[0] + ".." + expected[1] + " actual=" + actual[0] + ".." + actual[1]);
    }

    private static JsonObject resource(String path) {
        return RESOURCES.computeIfAbsent(path, key -> {
            var stream = VerticalSlopeGeometryLaunchChecks.class.getResourceAsStream("/assets/erydon/" + key);
            require(stream != null, "Missing authored resource: " + key);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        });
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Triangle(Vector3f a, Vector3f b, Vector3f c) {}
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "probe_atlas"), contents, 32, 32, 0, 0); }
    }
    /** Only overridden in-memory methods are called; no world is created or saved. */
    private static final class TestWorld extends ServerWorld {
        boolean water;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.getDefaultState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return water ? Fluids.WATER.getStill(false) : Fluids.EMPTY.getDefaultState(); }
        @Override public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay) {}
    }
}
