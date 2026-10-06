package com.oliver.erydon.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.oliver.erydon.block.GlazingShallowSlopeBlock.*;

/** Production placement and targeting against the authored glass meshes, under Fabric bootstrap. */
final class GlazingShallowSlopeLaunchChecks {
    static void run() throws Exception {
        TestWorld world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.states = new HashMap<>();
        int placements = 0, states = 0, samples = 0;
        for (String finish : List.of("tinted", "silver", "crystal", "bronze")) {
            for (Variant variant : Variant.values()) {
                GlazingShallowSlopeBlock block = new GlazingShallowSlopeBlock(
                        net.minecraft.block.AbstractBlock.Settings.copy(Blocks.GLASS), variant);
                BlockHalf fixedHalf = variant == Variant.UPPER ? BlockHalf.TOP : BlockHalf.BOTTOM;
                require(block.getDefaultState().get(HALF) == fixedHalf, "Incorrect default section");
                String id = "glazing_framed_" + finish + "_shallow_slope_" + variant.name().toLowerCase(java.util.Locale.ROOT);
                JsonObject variants = resource("blockstates/" + id + ".json").getAsJsonObject("variants");
                // Ceiling, floor and side clicks at either height must all retain the held section.
                for (Direction facing : Direction.Type.HORIZONTAL) for (Direction side : Direction.values()) {
                    for (double hitY : new double[]{.1, .5, .9}) for (boolean water : new boolean[]{false, true}) {
                        world.water = water;
                        world.states.clear();
                        world.states.put(BlockPos.ORIGIN.down(), block.getDefaultState());
                        var context = new AutomaticItemPlacementContext(world, BlockPos.ORIGIN, facing, ItemStack.EMPTY, side) {
                            @Override public Vec3d getHitPos() { return new Vec3d(.5, hitY, .5); }
                        };
                        BlockState placed = block.getPlacementState(context);
                        require(placed.get(HALF) == fixedHalf, "Click changed held section: " + id + " " + side + " " + hitY);
                        require(placed.get(FACING) == facing, "Placement does not follow shallow-stair facing");
                        require(placed.get(WATERLOGGED) == water, "Placement lost waterlogging");
                        require(placed.get(SHAPE) == SlopeShape.STRAIGHT, "Vertical stack created a corner");
                        placements++;
                    }
                }
                world.water = false;
                world.states.clear();
                for (Direction facing : Direction.Type.HORIZONTAL) for (SlopeShape shape : SlopeShape.values()) {
                    VoxelShape fixed = block.getOutlineShape(block.getDefaultState().with(FACING, facing).with(SHAPE, shape),
                            world, BlockPos.ORIGIN, null);
                    for (BlockHalf half : BlockHalf.values()) for (boolean water : new boolean[]{false, true}) {
                        BlockState state = block.getDefaultState().with(FACING, facing).with(SHAPE, shape)
                                .with(HALF, half).with(WATERLOGGED, water);
                        require(block.getOutlineShape(state, world, BlockPos.ORIGIN, null) == fixed,
                                "Saved half changed targeting: " + id + " " + state);
                        require(block.getCollisionShape(state, world, BlockPos.ORIGIN, null) == fixed,
                                "Collision and targeting diverged");
                        require((block.getFluidState(state).getFluid() == Fluids.WATER) == water, "Saved waterlogging changed");
                        String key = "half=" + half.asString() + ",shape=" + shape.asString() + ",facing=" + facing.asString()
                                + ",waterlogged=" + water;
                        JsonObject render = variants.getAsJsonObject(key);
                        require(render != null && !render.has("x"), "Render still inverts the section: " + id + " " + key);
                        samples += checkMesh(fixed, render, id + " " + key);
                        for (BlockRotation rotation : BlockRotation.values()) {
                            require(block.rotate(state, rotation).get(FACING) == rotation.rotate(facing), "Rotation changed facing");
                        }
                        states++;
                    }
                }
                corners(block, world);
            }
        }
        System.out.println("ERYDON_SHALLOW_GLAZING_OK: placementCases=" + placements + " savedStates=" + states
                + " meshSamples=" + samples + " finishes=4");
    }

    private static void corners(GlazingShallowSlopeBlock block, TestWorld world) {
        GlazingShallowSlopeBlock opposite = new GlazingShallowSlopeBlock(
                net.minecraft.block.AbstractBlock.Settings.copy(Blocks.GLASS),
                block.getDefaultState().get(HALF) == BlockHalf.TOP ? Variant.LOWER : Variant.UPPER);
        for (Direction facing : Direction.Type.HORIZONTAL) for (BlockHalf half : BlockHalf.values()) {
            for (BlockHalf neighborHalf : BlockHalf.values()) for (boolean left : new boolean[]{false, true}) {
                Direction turn = left ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
                BlockState self = block.getDefaultState().with(FACING, facing).with(HALF, half);
                BlockState other = block.getDefaultState().with(FACING, turn).with(HALF, neighborHalf);
                for (boolean front : new boolean[]{false, true}) {
                    Direction side = front ? facing : facing.getOpposite();
                    BlockPos pos = BlockPos.ORIGIN.offset(side);
                    world.states.clear();
                    world.states.put(pos, other);
                    BlockState updated = block.getStateForNeighborUpdate(self, side, other, world, BlockPos.ORIGIN, pos);
                    SlopeShape expected = front ? (left ? SlopeShape.INNER_LEFT : SlopeShape.INNER_RIGHT)
                            : (left ? SlopeShape.OUTER_LEFT : SlopeShape.OUTER_RIGHT);
                    require(updated.get(SHAPE) == expected, "Saved half prevents automatic corner");
                    world.states.put(pos, opposite.getDefaultState().with(FACING, turn));
                    updated = block.getStateForNeighborUpdate(self, side, world.getBlockState(pos), world, BlockPos.ORIGIN, pos);
                    require(updated.get(SHAPE) == SlopeShape.STRAIGHT, "Lower and upper formed an incompatible corner");
                    world.states.clear();
                    updated = block.getStateForNeighborUpdate(updated.with(SHAPE, expected), side,
                            Blocks.AIR.getDefaultState(), world, BlockPos.ORIGIN, pos);
                    require(updated.get(SHAPE) == SlopeShape.STRAIGHT, "Removing neighbor did not restore straight glass");
                }
            }
        }
    }

    private static int checkMesh(VoxelShape shape, JsonObject render, String label) {
        String path = render.get("model").getAsString().substring("erydon:".length());
        JsonObject model = resource("models/" + path + ".json");
        while (!model.has("elements")) {
            model = resource("models/" + model.get("parent").getAsString().substring("erydon:".length()) + ".json");
        }
        List<double[][]> triangles = new ArrayList<>();
        double yaw = Math.toRadians(render.get("y").getAsDouble());
        for (var value : model.getAsJsonArray("elements")) {
            JsonObject element = value.getAsJsonObject();
            double[][] vertices = new double[8][3];
            for (int index = 0; index < 8; index++) {
                double[] p = vertices[index];
                for (int axis = 0; axis < 3; axis++) {
                    p[axis] = element.getAsJsonArray((index & (1 << axis)) == 0 ? "from" : "to").get(axis).getAsDouble() / 16;
                }
                if (element.has("rotation")) {
                    JsonObject rotation = element.getAsJsonObject("rotation");
                    JsonArray origin = rotation.getAsJsonArray("origin");
                    int axis = "xyz".indexOf(rotation.get("axis").getAsString()), b = (axis + 1) % 3, c = (axis + 2) % 3;
                    double angle = Math.toRadians(rotation.get("angle").getAsDouble());
                    double u = p[b] - origin.get(b).getAsDouble() / 16, v = p[c] - origin.get(c).getAsDouble() / 16;
                    p[b] = origin.get(b).getAsDouble() / 16 + u * Math.cos(angle) - v * Math.sin(angle);
                    p[c] = origin.get(c).getAsDouble() / 16 + u * Math.sin(angle) + v * Math.cos(angle);
                }
                double x = p[0] - .5, z = p[2] - .5;
                p[0] = .5 + x * Math.cos(yaw) - z * Math.sin(yaw);
                p[2] = .5 + x * Math.sin(yaw) + z * Math.cos(yaw);
            }
            for (String face : element.getAsJsonObject("faces").keySet()) {
                int[] indices = switch (face) {
                    case "up" -> new int[]{2, 3, 7, 6}; case "down" -> new int[]{0, 4, 5, 1};
                    case "north" -> new int[]{0, 1, 3, 2}; case "south" -> new int[]{4, 6, 7, 5};
                    case "west" -> new int[]{0, 2, 6, 4}; case "east" -> new int[]{1, 5, 7, 3};
                    default -> throw new AssertionError(face);
                };
                triangles.add(new double[][]{vertices[indices[0]], vertices[indices[1]], vertices[indices[2]]});
                triangles.add(new double[][]{vertices[indices[0]], vertices[indices[2]], vertices[indices[3]]});
            }
        }
        int samples = 0;
        for (int ix = 0; ix < 32; ix++) for (int iz = 0; iz < 32; iz++) {
            double x = (ix + .5) / 32, z = (iz + .5) / 32;
            double bottom = Double.POSITIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
            for (double[][] triangle : triangles) {
                double[] a = triangle[0], b = triangle[1], c = triangle[2];
                double denominator = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
                if (Math.abs(denominator) < 1e-10) continue;
                double u = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / denominator;
                double v = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / denominator;
                if (u < -1e-8 || v < -1e-8 || u + v > 1 + 1e-8) continue;
                double y = u * a[1] + v * b[1] + (1 - u - v) * c[1];
                bottom = Math.min(bottom, y); top = Math.max(top, y);
            }
            if (!Double.isFinite(bottom)) continue;
            var hit = shape.raycast(new Vec3d(x, 2, z), new Vec3d(x, -1, z), BlockPos.ORIGIN);
            var underside = shape.raycast(new Vec3d(x, -1, z), new Vec3d(x, 2, z), BlockPos.ORIGIN);
            // Existing authored voxel stair-steps approximate smooth glass/frame edges to about one model pixel.
            require(hit != null && underside != null && Math.abs(hit.getPos().y - top) < .065
                    && Math.abs(underside.getPos().y - bottom) < .065,
                    "Selection misses visible glass: " + label + " at " + x + "," + z);
            samples++;
        }
        return samples;
    }

    private static JsonObject resource(String path) {
        var stream = GlazingShallowSlopeLaunchChecks.class.getResourceAsStream("/assets/erydon/" + path);
        require(stream != null, "Missing model resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Constructor bypass stays in the test; only these in-memory world operations are called. */
    static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        boolean water;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return water ? Fluids.WATER.getStill(false) : Fluids.EMPTY.getDefaultState(); }
        @Override public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay) {}
    }
}
