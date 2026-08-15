package com.oliver.erydon.block;

import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.LinkedHashSet;
import java.util.Set;

public class ChimneyBlock extends Block implements ClusterRebuildableBlock {
    public static final EnumProperty<ChimneyPart> PART = EnumProperty.of("part", ChimneyPart.class);

    private static final VoxelShape SMALL_SHAPE = makeSmallShape();
    private static final VoxelShape LARGE_LOWER_SHAPE = makeLargeLowerShape();
    private static final VoxelShape LARGE_UPPER_SHAPE = makeLargeUpperShape();

    public ChimneyBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(PART, ChimneyPart.SMALL));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(PART, getPartFor(ctx.getWorld(), ctx.getBlockPos()));
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        BlockState updated = recompute(state, world, pos);
        if (updated != state) {
            world.setBlockState(pos, updated, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
        }
        updateNeighbor(world, pos.up());
        updateNeighbor(world, pos.down());
    }

    @Override
    public BlockState getStateForNeighborUpdate(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            WorldAccess world,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        if (direction.getAxis() != Direction.Axis.Y) {
            return state;
        }
        return recompute(state, world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            updateNeighbor(world, pos.up());
            updateNeighbor(world, pos.down());
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.fullCube();
    }

    private void updateNeighbor(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isOf(this)) {
            return;
        }

        BlockState updated = recompute(state, world, pos);
        if (updated != state) {
            world.setBlockState(pos, updated, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
        }
    }

    private BlockState recompute(BlockState state, WorldAccess world, BlockPos pos) {
        ChimneyPart part = getPartFor(world, pos);
        return state.get(PART) == part ? state : state.with(PART, part);
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> component = collectVerticalComponent(world, seed);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        for (BlockPos pos : component) {
            updateNeighbor(world, pos);
        }
        return new ClusterRecalcResult(component, true);
    }

    private ChimneyPart getPartFor(WorldAccess world, BlockPos pos) {
        boolean sameBelow = world.getBlockState(pos.down()).isOf(this);
        boolean sameAbove = world.getBlockState(pos.up()).isOf(this);

        if (sameBelow) {
            return ChimneyPart.LARGE_UPPER;
        }
        if (sameAbove) {
            return ChimneyPart.LARGE_LOWER;
        }
        return ChimneyPart.SMALL;
    }

    private Set<BlockPos> collectVerticalComponent(WorldAccess world, BlockPos seed) {
        Set<BlockPos> component = new LinkedHashSet<>();
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return component;
        }

        BlockPos bottom = seed;
        while (ClusterRecalcSafety.getBlockState(world, bottom.down()).isOf(this)) {
            bottom = bottom.down();
        }

        for (BlockPos current = bottom;
             ClusterRecalcSafety.getBlockState(world, current).isOf(this);
             current = current.up()) {
            if (!ClusterRecalcSafety.claim(current)) {
                break;
            }
            component.add(current);
        }

        return component;
    }

    private VoxelShape shapeFor(BlockState state) {
        return switch (state.get(PART)) {
            case SMALL -> SMALL_SHAPE;
            case LARGE_LOWER -> LARGE_LOWER_SHAPE;
            case LARGE_UPPER -> LARGE_UPPER_SHAPE;
        };
    }

    public enum ChimneyPart implements StringIdentifiable {
        SMALL("small"),
        LARGE_LOWER("large_lower"),
        LARGE_UPPER("large_upper");

        private final String name;

        ChimneyPart(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private static VoxelShape makeSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.125, 0, -0.125, 1, 0.125, 0.015625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.984375, 1.125, 0.125, 1.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.984375, 0, -0.125, 1.125, 0.125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.125, 0, 0, 0.015625, 0.125, 1.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.984375, 0, 0, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.015625, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.984375, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.015625), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeLargeLowerShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.125, 0, -0.125, 1, 0.125, 0.015625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.984375, 1.125, 0.125, 1.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.984375, 0, -0.125, 1.125, 0.125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.125, 0, 0, 0.015625, 0.125, 1.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.984375, 0, 0, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.015625, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.984375, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.015625), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeLargeUpperShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.984375, 0, 0, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.015625, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.984375, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.015625), BooleanBiFunction.OR);
        return shape;
    }
}
