package com.oliver.erydon.block;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Actual pitched placement/update callbacks, isolated from game windows and saved worlds. */
public final class PitchedSlopeCornerLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            run();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static void run() throws Exception {
        List<Profile> profiles = List.of(
                new Profile("standard", new SlopeBlock(settings()), new SlopeBlock(glass())),
                new Profile("shallow_lower", new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.LOWER),
                        new ShallowSlopeBlock(glass(), ShallowSlopeBlock.Variant.LOWER)),
                new Profile("shallow_upper", new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.UPPER),
                        new ShallowSlopeBlock(glass(), ShallowSlopeBlock.Variant.UPPER)),
                new Profile("steep_lower", new SlopeSteepBlock(settings(), SlopeSteepBlock.Variant.LOWER),
                        new SlopeSteepBlock(glass(), SlopeSteepBlock.Variant.LOWER)),
                new Profile("steep_upper", new SlopeSteepBlock(settings(), SlopeSteepBlock.Variant.UPPER),
                        new SlopeSteepBlock(glass(), SlopeSteepBlock.Variant.UPPER)));
        TestWorld world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.states = new HashMap<>();
        int joins = 0, edits = 0, transforms = 0, placements = 0;
        for (Profile profile : profiles) for (BlockHalf half : BlockHalf.values()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                BlockState self = state(profile.block, facing, half, true);
                // A side click must choose the same half as ceiling/floor placement.
                for (Direction side : Direction.values()) for (double hit : new double[]{.25, .75}) {
                    world.states.clear();
                    world.water = true;
                    BlockState placed = place(profile.block, world, BlockPos.ORIGIN, facing, side, hit);
                    BlockHalf expected = side == Direction.DOWN || side != Direction.UP && hit > .5
                            ? BlockHalf.TOP : BlockHalf.BOTTOM;
                    require(placed.get(Properties.BLOCK_HALF) == expected, "Wrong clicked half");
                    require(placed.get(Properties.HORIZONTAL_FACING) == facing, "Wrong placement facing");
                    require(placed.get(Properties.WATERLOGGED), "Placement lost waterlogging");
                    placements++;
                }
                for (boolean left : new boolean[]{false, true}) for (boolean front : new boolean[]{false, true}) {
                    Direction turn = left ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
                    Direction side = front ? facing : facing.getOpposite();
                    BlockPos neighbour = BlockPos.ORIGIN.offset(side);
                    Direction blockerSide = front ? turn.getOpposite() : turn;
                    BlockPos blocker = BlockPos.ORIGIN.offset(blockerSide);
                    String expected = (front ? "inner_" : "outer_") + (left ? "left" : "right");
                    String label = profile.name + " " + half + " " + facing + " " + expected;
                    for (Block material : new Block[]{profile.block, profile.otherMaterial}) {
                        BlockState other = state(material, turn, half, false);
                        // Both edit orders call the production placement and neighbour callbacks.
                        for (boolean neighbourFirst : new boolean[]{false, true}) {
                            world.states.clear();
                            world.water = true;
                            if (neighbourFirst) world.change(neighbour, other);
                            BlockState placed = place(profile.block, world, BlockPos.ORIGIN, facing,
                                    half == BlockHalf.TOP ? Direction.DOWN : Direction.UP, .5);
                            world.change(BlockPos.ORIGIN, placed);
                            if (!neighbourFirst) world.change(neighbour, other);
                            require(shape(world.getBlockState(BlockPos.ORIGIN)).equals(expected),
                                    "Corner does not match physical turn: " + label + " first=" + neighbourFirst);
                            require(world.getBlockState(BlockPos.ORIGIN).get(Properties.WATERLOGGED),
                                    "Corner update lost waterlogging");
                            joins++;
                            world.change(neighbour, Blocks.AIR.getDefaultState());
                            require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("straight"),
                                    "Removal retained a stale corner: " + label);
                            edits++;
                        }
                        world.states.clear();
                        world.change(neighbour, other);
                        world.change(BlockPos.ORIGIN, self);
                        world.change(blocker, state(material, facing, half, false));
                        require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("straight"),
                                "Straight-through row was cut into a corner: " + label);
                        world.change(blocker, Blocks.AIR.getDefaultState());
                        require(shape(world.getBlockState(BlockPos.ORIGIN)).equals(expected),
                                "Removing blocker did not restore the corner: " + label);
                        edits += 2;
                        world.change(neighbour, state(material, turn, opposite(half), false));
                        require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("straight"),
                                "Opposite halves joined: " + label);
                        edits++;
                    }
                    for (Profile incompatible : profiles) {
                        if (incompatible == profile) continue;
                        world.states.clear();
                        world.change(neighbour, state(incompatible.block, turn, half, false));
                        world.change(BlockPos.ORIGIN, self);
                        require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("straight"),
                                "Different gradients/profiles joined: " + label + " / " + incompatible.name);
                        edits++;
                    }
                    // Resolve a physically transformed neighbourhood, not just a round-trip enum.
                    for (BlockRotation rotation : BlockRotation.values()) for (BlockMirror mirror : BlockMirror.values()) {
                        BlockState resolved = withShape(self, expected);
                        world.states.clear();
                        BlockState transformedOther = state(profile.block, turn, half, false).rotate(rotation).mirror(mirror);
                        BlockPos transformedNeighbour = transform(neighbour, rotation, mirror);
                        world.change(transformedNeighbour, transformedOther);
                        Direction transformedFacing = self.rotate(rotation).mirror(mirror).get(Properties.HORIZONTAL_FACING);
                        world.change(BlockPos.ORIGIN, place(profile.block, world, BlockPos.ORIGIN, transformedFacing,
                                half == BlockHalf.TOP ? Direction.DOWN : Direction.UP, .5));
                        require(world.getBlockState(BlockPos.ORIGIN) == resolved.rotate(rotation).mirror(mirror),
                                "Transformed corner disagrees with transformed neighbours: " + label + " " + rotation + " " + mirror);
                        transforms++;
                    }
                }
                // Aligned and reverse-aligned rows never turn; vertical changes never invent a corner.
                for (Direction row : new Direction[]{facing, facing.getOpposite()}) {
                    world.states.clear();
                    world.change(BlockPos.ORIGIN.offset(facing), state(profile.block, row, half, false));
                    world.change(BlockPos.ORIGIN, self);
                    world.change(BlockPos.ORIGIN.up(), state(profile.block, facing.rotateYClockwise(), half, false));
                    require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("straight"), "Aligned/vertical row formed a corner");
                    edits++;
                }
                // An allowed front join takes precedence; its row blocker permits a back join instead.
                world.states.clear();
                Direction turn = facing.rotateYCounterclockwise();
                world.change(BlockPos.ORIGIN.offset(facing), state(profile.block, turn, half, false));
                world.change(BlockPos.ORIGIN.offset(facing.getOpposite()), state(profile.block, turn, half, false));
                world.change(BlockPos.ORIGIN, place(profile.block, world, BlockPos.ORIGIN, facing,
                        half == BlockHalf.TOP ? Direction.DOWN : Direction.UP, .5));
                require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("inner_left"), "Front/back priority changed");
                world.change(BlockPos.ORIGIN.offset(turn.getOpposite()), state(profile.otherMaterial, facing, half, false));
                require(shape(world.getBlockState(BlockPos.ORIGIN)).equals("outer_left"), "Blocked inner did not consider the outer join");
                edits += 2;
            }
        }
        System.out.println("ERYDON_PITCHED_CORNERS_OK: profiles=5 joins=" + joins + " edits=" + edits
                + " transformedNeighbourhoods=" + transforms + " clickPlacements=" + placements);
    }

    private static AbstractBlock.Settings settings() { return AbstractBlock.Settings.copy(Blocks.STONE); }
    private static AbstractBlock.Settings glass() { return AbstractBlock.Settings.copy(Blocks.GLASS); }
    private static BlockHalf opposite(BlockHalf half) { return half == BlockHalf.TOP ? BlockHalf.BOTTOM : BlockHalf.TOP; }
    private static BlockState state(Block block, Direction facing, BlockHalf half, boolean water) {
        return block.getDefaultState().with(Properties.HORIZONTAL_FACING, facing)
                .with(Properties.BLOCK_HALF, half).with(Properties.WATERLOGGED, water);
    }
    private static BlockState withShape(BlockState state, String name) {
        String key = name.toUpperCase(java.util.Locale.ROOT);
        if (state.getBlock() instanceof SlopeBlock) return state.with(SlopeBlock.SHAPE, SlopeBlock.SlopeShape.valueOf(key));
        if (state.getBlock() instanceof ShallowSlopeBlock) return state.with(ShallowSlopeBlock.SHAPE, ShallowSlopeBlock.SlopeShape.valueOf(key));
        return state.with(SlopeSteepBlock.SHAPE, SlopeSteepBlock.SlopeShape.valueOf(key));
    }
    private static String shape(BlockState state) {
        if (state.getBlock() instanceof SlopeBlock) return state.get(SlopeBlock.SHAPE).asString();
        if (state.getBlock() instanceof ShallowSlopeBlock) return state.get(ShallowSlopeBlock.SHAPE).asString();
        return state.get(SlopeSteepBlock.SHAPE).asString();
    }
    private static BlockState place(Block block, TestWorld world, BlockPos pos, Direction facing, Direction side, double hitY) {
        return block.getPlacementState(new AutomaticItemPlacementContext(world, pos, facing, ItemStack.EMPTY, side) {
            @Override public Direction getHorizontalPlayerFacing() { return facing.getOpposite(); }
            @Override public Vec3d getHitPos() { return new Vec3d(pos.getX() + .5, pos.getY() + hitY, pos.getZ() + .5); }
        });
    }
    private static BlockPos transform(BlockPos pos, BlockRotation rotation, BlockMirror mirror) {
        BlockPos turned = pos.rotate(rotation);
        return switch (mirror) {
            case NONE -> turned;
            case LEFT_RIGHT -> new BlockPos(turned.getX(), turned.getY(), -turned.getZ());
            case FRONT_BACK -> new BlockPos(-turned.getX(), turned.getY(), turned.getZ());
        };
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Profile(String name, Block block, Block otherMaterial) {}

    /** In-memory notifications still invoke each real block's neighbour-update callback. */
    static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        boolean water;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return water ? Fluids.WATER.getStill(false) : Fluids.EMPTY.getDefaultState(); }
        @Override public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay) {}
        void change(BlockPos pos, BlockState state) {
            if (state.isAir()) states.remove(pos); else states.put(pos.toImmutable(), state);
            ArrayDeque<BlockPos> changed = new ArrayDeque<>();
            changed.add(pos.toImmutable());
            int updates = 0;
            while (!changed.isEmpty()) {
                BlockPos source = changed.remove();
                for (Direction direction : Direction.values()) {
                    BlockPos target = source.offset(direction);
                    BlockState before = getBlockState(target);
                    if (!(before.getBlock() instanceof SlopeBlock || before.getBlock() instanceof ShallowSlopeBlock
                            || before.getBlock() instanceof SlopeSteepBlock)) continue;
                    BlockState after = before.getBlock().getStateForNeighborUpdate(before, direction.getOpposite(),
                            getBlockState(source), this, target, source);
                    if (before != after) { states.put(target.toImmutable(), after); changed.add(target); }
                    require(++updates < 256, "Neighbour callbacks did not settle");
                }
            }
        }
    }
}
