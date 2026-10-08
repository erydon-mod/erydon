package com.oliver.erydon.client.model;

import com.oliver.erydon.block.GlazingShallowSlopeBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import sun.misc.Unsafe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Placement and edited-neighbour matrices use actual registered blocks, independent of mesh generation. */
final class GlazingSlopePlacementLaunchChecks {
    private static final String[] FINISHES = {"tinted", "silver", "crystal", "bronze"};
    static final String[] FORMS = {"slope", "shallow_slope_lower", "shallow_slope_upper"};

    static void run() throws Exception {
        TestWorld world = world();
        int clicks = 0, joins = 0, transforms = 0;
        for (String form : FORMS) for (String finish : FINISHES) {
            Block block = block(finish, form);
            for (Direction facing : Direction.Type.HORIZONTAL) for (Direction side : Direction.values()) {
                for (double hitY : new double[]{.1, .5, .9}) for (boolean water : new boolean[]{false, true}) {
                    world.states.clear(); world.water = water;
                    BlockState placed = block.getPlacementState(context(world, facing, side, hitY));
                    BlockHalf expectedHalf = form.equals("slope")
                            ? side == Direction.DOWN || (side.getAxis().isHorizontal() && hitY > .5) ? BlockHalf.TOP : BlockHalf.BOTTOM
                            : form.endsWith("upper") ? BlockHalf.TOP : BlockHalf.BOTTOM;
                    require(placed != null && placed.get(Properties.HORIZONTAL_FACING) == facing
                                    && placed.get(Properties.BLOCK_HALF) == expectedHalf
                                    && placed.get(Properties.WATERLOGGED) == water && isShape(placed, "straight"),
                            "Held glazing section/half/facing changed during placement: " + form + " " + side + " " + hitY);
                    // These fixtures register after vanilla bootstrap, before the normal state-cache pass.
                    require((block.getFluidState(placed).getFluid() == Fluids.WATER) == water, "Waterlogged fluid differs");
                    clicks++;
                }
            }
            for (String otherFinish : FINISHES) for (Direction facing : Direction.Type.HORIZONTAL) {
                for (BlockHalf half : BlockHalf.values()) for (boolean front : new boolean[]{false, true}) {
                    for (boolean left : new boolean[]{false, true}) for (boolean neighbourFirst : new boolean[]{false, true}) {
                        for (boolean water : new boolean[]{false, true}) {
                            BlockState self = block.getDefaultState().with(Properties.HORIZONTAL_FACING, facing)
                                    .with(Properties.BLOCK_HALF, half).with(Properties.WATERLOGGED, water);
                            Direction turn = left ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
                            Direction side = front ? facing : facing.getOpposite();
                            BlockPos neighbourPos = BlockPos.ORIGIN.offset(side);
                            BlockState neighbour = block(otherFinish, form).getDefaultState().with(Properties.HORIZONTAL_FACING, turn)
                                    .with(Properties.BLOCK_HALF, half);
                            String expected = (front ? "inner_" : "outer_") + (left ? "left" : "right");
                            world.states.clear(); world.water = water;
                            if (neighbourFirst) world.states.put(neighbourPos, neighbour);
                            // A saved shallow HALF is deliberately inert, unlike ordinary45° HALF.
                            BlockState placed = block.getPlacementState(context(world, facing,
                                    half == BlockHalf.TOP ? Direction.DOWN : Direction.UP, .5));
                            if (block instanceof GlazingShallowSlopeBlock) placed = placed.with(Properties.BLOCK_HALF, half);
                            if (!neighbourFirst) {
                                require(isShape(placed, "straight"), "A lone pane formed a corner");
                                world.states.put(neighbourPos, neighbour);
                                placed = update(placed, side, world);
                            }
                            require(isShape(placed, expected), "Placement order lost glazing corner: " + form + " " + self + " " + expected);
                            require(placed.get(Properties.WATERLOGGED) == water, "Corner update lost waterlogging");
                            joins++;

                            // A matching aligned neighbour in the flank suppresses the corner.
                            Direction flank = front ? turn.getOpposite() : turn;
                            world.states.put(BlockPos.ORIGIN.offset(flank), self);
                            require(isShape(update(self, side, world), "straight"), "Aligned flank failed to suppress a corner");
                            world.states.remove(BlockPos.ORIGIN.offset(flank));

                            //45° joins require matching HALF; shallow blocks preserve their registered section.
                            world.states.put(neighbourPos, neighbour.with(Properties.BLOCK_HALF,
                                    half == BlockHalf.TOP ? BlockHalf.BOTTOM : BlockHalf.TOP));
                            require(isShape(update(self, side, world), form.equals("slope") ? "straight" : expected),
                                    "Glazing half compatibility changed: " + form);
                            world.states.put(neighbourPos, neighbour);
                            for (String incompatible : FORMS) if (!incompatible.equals(form)) {
                                world.states.put(neighbourPos, block(otherFinish, incompatible).getDefaultState()
                                        .with(Properties.HORIZONTAL_FACING, turn).with(Properties.BLOCK_HALF, half));
                                require(isShape(update(self, side, world), "straight"), "Different glazing gradients formed a corner");
                            }
                            world.states.put(neighbourPos, neighbour);

                            // Rotate/reflect the actual neighbourhood and resolve again, including cross-finish neighbours.
                            for (BlockRotation rotation : BlockRotation.values()) {
                                BlockState moved = self.getBlock().rotate(self, rotation);
                                BlockState movedNeighbour = neighbour.getBlock().rotate(neighbour, rotation);
                                Direction movedSide = rotation.rotate(side);
                                world.states.clear(); world.states.put(BlockPos.ORIGIN.offset(movedSide), movedNeighbour);
                                BlockState result = update(moved, movedSide, world);
                                require(result == placed.getBlock().rotate(placed, rotation), "Rotated glazing neighbourhood changed corner");
                                transforms++;
                            }
                            for (BlockMirror mirror : BlockMirror.values()) {
                                BlockState moved = self.getBlock().mirror(self, mirror);
                                BlockState movedNeighbour = neighbour.getBlock().mirror(neighbour, mirror);
                                Direction movedSide = mirror.getRotation(side).rotate(side);
                                world.states.clear(); world.states.put(BlockPos.ORIGIN.offset(movedSide), movedNeighbour);
                                require(update(moved, movedSide, world) == placed.getBlock().mirror(placed, mirror),
                                        "Mirrored glazing neighbourhood changed corner: " + mirror);
                                transforms++;
                            }
                            world.states.clear();
                            require(isShape(update(placed, side, world), "straight"), "Removing a neighbour failed to restore straight glazing");
                            world.states.put(BlockPos.ORIGIN.up(), neighbour);
                            require(isShape(update(self, side, world), "straight"), "Vertical stack formed a horizontal glass corner");
                        }
                    }
                }
            }
        }
        System.out.println("ERYDON_GLAZING_PLACEMENT_OK: clickPlacements=" + clicks + " joins=" + joins
                + " transformedNeighbourhoods=" + transforms + " profiles=3 finishes=4");
    }

    static Block block(String finish, String form) {
        Identifier id = new Identifier("erydon", "glazing_framed_" + finish + "_" + form);
        require(Registries.BLOCK.containsId(id), "Missing canonical glazing fixture: " + id);
        return Registries.BLOCK.get(id);
    }
    static <T extends Comparable<T>> BlockState shape(BlockState state, Property<T> property, String name) {
        return state.with(property, property.parse(name).orElseThrow());
    }
    static BlockState shape(BlockState state, String name) {
        return shape(state, state.getBlock().getStateManager().getProperty("shape"), name);
    }
    static boolean isShape(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateManager().getProperty("shape");
        return property != null && state.get(property).equals(property.parse(name).orElseThrow());
    }
    private static BlockState update(BlockState state, Direction direction, TestWorld world) {
        BlockPos neighbour = BlockPos.ORIGIN.offset(direction);
        return state.getBlock().getStateForNeighborUpdate(state, direction, world.getBlockState(neighbour), world,
                BlockPos.ORIGIN, neighbour);
    }
    private static AutomaticItemPlacementContext context(TestWorld world, Direction facing, Direction side, double hitY) {
        return new AutomaticItemPlacementContext(world, BlockPos.ORIGIN, facing, ItemStack.EMPTY, side) {
            @Override public Vec3d getHitPos() { return new Vec3d(.5, hitY, .5); }
        };
    }
    private static TestWorld world() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        TestWorld world = (TestWorld) ((Unsafe) field.get(null)).allocateInstance(TestWorld.class);
        world.states = new HashMap<>();
        return world;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        boolean water;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return water ? Fluids.WATER.getStill(false) : Fluids.EMPTY.getDefaultState(); }
        @Override public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay) {}
    }
}
