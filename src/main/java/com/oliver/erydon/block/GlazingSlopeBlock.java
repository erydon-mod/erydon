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
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

public class GlazingSlopeBlock extends HorizontalFacingBlock implements Waterloggable {
    public enum SlopeShape implements StringIdentifiable {
        STRAIGHT("straight"),
        INNER_LEFT("inner_left"),
        INNER_RIGHT("inner_right"),
        OUTER_LEFT("outer_left"),
        OUTER_RIGHT("outer_right");

        private final String name;

        SlopeShape(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<BlockHalf> HALF = Properties.BLOCK_HALF;
    public static final EnumProperty<SlopeShape> SHAPE = EnumProperty.of("shape", SlopeShape.class);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    public GlazingSlopeBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, BlockHalf.BOTTOM)
                .with(SHAPE, SlopeShape.STRAIGHT)
                .with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SHAPE, WATERLOGGED);
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

        BlockState placed = this.getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing())
                .with(HALF, half)
                .with(WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER);
        return getStateWithShape(placed, ctx.getWorld(), ctx.getBlockPos());
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
        return direction.getAxis().isHorizontal()
                ? getStateWithShape(state, world, pos)
                : super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }
        Direction facing = state.get(FACING);
        return state.with(FACING, mirror.getRotation(facing).rotate(facing))
                .with(SHAPE, swapLeftRight(state.get(SHAPE)));
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    private BlockState getStateWithShape(BlockState state, BlockView world, BlockPos pos) {
        return state.with(SHAPE, computeCornerShape(state, world, pos));
    }

    private SlopeShape computeCornerShape(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);

        BlockState front = world.getBlockState(pos.offset(facing));
        if (isCompatibleSlope(state, front)) {
            Direction frontFacing = front.get(FACING);
            if (frontFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, frontFacing.getOpposite())) {
                return frontFacing == facing.rotateYCounterclockwise()
                        ? SlopeShape.INNER_LEFT
                        : SlopeShape.INNER_RIGHT;
            }
        }

        BlockState back = world.getBlockState(pos.offset(facing.getOpposite()));
        if (isCompatibleSlope(state, back)) {
            Direction backFacing = back.get(FACING);
            if (backFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, backFacing)) {
                return backFacing == facing.rotateYCounterclockwise()
                        ? SlopeShape.OUTER_LEFT
                        : SlopeShape.OUTER_RIGHT;
            }
        }

        return SlopeShape.STRAIGHT;
    }

    private boolean isDifferentOrientation(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        BlockState other = world.getBlockState(pos.offset(direction));
        return !(isCompatibleSlope(state, other) && other.get(FACING) == state.get(FACING));
    }

    private static boolean isCompatibleSlope(BlockState state, BlockState other) {
        return other.getBlock() instanceof GlazingSlopeBlock && other.get(HALF) == state.get(HALF);
    }

    private static SlopeShape swapLeftRight(SlopeShape shape) {
        return switch (shape) {
            case INNER_LEFT -> SlopeShape.INNER_RIGHT;
            case INNER_RIGHT -> SlopeShape.INNER_LEFT;
            case OUTER_LEFT -> SlopeShape.OUTER_RIGHT;
            case OUTER_RIGHT -> SlopeShape.OUTER_LEFT;
            default -> shape;
        };
    }

    private static VoxelShape getVoxelForState(BlockState state) {
        return GlazingSlopeGeometry.shape(GlazingSlopeGeometry.Profile.STANDARD,
                state.get(FACING), state.get(SHAPE).asString(), state.get(HALF));
    }
}
