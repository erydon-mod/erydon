package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

public class GlazingSlopeBlock extends HorizontalFacingBlock implements Waterloggable {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<BlockHalf> HALF = Properties.BLOCK_HALF;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final Direction[] HORIZONTALS = new Direction[] {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final VoxelShape BOTTOM_SOUTH_SHAPE = createBottomSouthShape();
    private static final VoxelShape TOP_SOUTH_SHAPE = flipY(BOTTOM_SOUTH_SHAPE);
    private static final VoxelShape[] BOTTOM_CACHE = buildShapeCache(BOTTOM_SOUTH_SHAPE);
    private static final VoxelShape[] TOP_CACHE = buildShapeCache(TOP_SOUTH_SHAPE);

    public GlazingSlopeBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, BlockHalf.BOTTOM)
                .with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, WATERLOGGED);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockHalf half;
        if (ctx.getSide() == Direction.DOWN) {
            half = BlockHalf.TOP;
        } else if (ctx.getSide() == Direction.UP) {
            half = BlockHalf.BOTTOM;
        } else {
            double hitY = ctx.getHitPos().y - ctx.getBlockPos().getY();
            half = hitY > 0.5d ? BlockHalf.TOP : BlockHalf.BOTTOM;
        }

        return this.getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing())
                .with(HALF, half)
                .with(WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getVoxelForState(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getVoxelForState(state);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    private static VoxelShape getVoxelForState(BlockState state) {
        VoxelShape[] cache = state.get(HALF) == BlockHalf.TOP ? TOP_CACHE : BOTTOM_CACHE;
        return cache[horizontalIndex(state.get(FACING))];
    }

    private static VoxelShape[] buildShapeCache(VoxelShape southShape) {
        VoxelShape[] cache = new VoxelShape[4];
        for (Direction facing : HORIZONTALS) {
            cache[horizontalIndex(facing)] = rotateShapeSteps(southShape, stepsForFacing(facing));
        }
        return cache;
    }

    private static VoxelShape createBottomSouthShape() {
        VoxelShape shape = VoxelShapes.empty();
        double thickness = 1.25d / 16.0d;

        for (int z = 0; z < 16; z++) {
            double minZ = z / 16.0d;
            double maxZ = (z + 1) / 16.0d;
            double midZ = (minZ + maxZ) * 0.5d;
            double minY = Math.max(0.0d, midZ - thickness * 0.5d);
            double maxY = Math.min(1.0d, midZ + thickness * 0.5d);

            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0d, minY, minZ, 1.0d, maxY, maxZ));
        }

        return shape.simplify();
    }

    private static VoxelShape flipY(VoxelShape shape) {
        final VoxelShape[] flipped = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> flipped[0] = VoxelShapes.union(
                flipped[0],
                VoxelShapes.cuboid(minX, 1.0d - maxY, minZ, maxX, 1.0d - minY, maxZ)
        ));
        return flipped[0];
    }

    private static int stepsForFacing(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeSteps(VoxelShape shape, int steps) {
        int turns = ((steps % 4) + 4) % 4;
        VoxelShape current = shape;

        for (int i = 0; i < turns; i++) {
            final VoxelShape[] rotated = {VoxelShapes.empty()};
            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> rotated[0] = VoxelShapes.union(
                    rotated[0],
                    VoxelShapes.cuboid(1.0d - maxZ, minY, minX, 1.0d - minZ, maxY, maxX)
            ));
            current = rotated[0];
        }

        return current;
    }

    private static int horizontalIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
