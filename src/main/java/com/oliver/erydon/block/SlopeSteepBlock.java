package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluid;
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
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

public class SlopeSteepBlock extends HorizontalFacingBlock implements Waterloggable {

    public enum Variant {
        LOWER,
        UPPER
    }

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
            return this.name;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<SlopeShape> SHAPE = EnumProperty.of("shape", SlopeShape.class);
    public static final EnumProperty<BlockHalf> HALF = Properties.BLOCK_HALF;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    // Base orientation: facing=EAST with y=0 is treated as native.
    private static final VoxelShape LOWER_STRAIGHT = buildLowerStraight();
    private static final VoxelShape LOWER_INNER = buildLowerInner();
    private static final VoxelShape LOWER_OUTER = buildLowerOuter();
    private static final VoxelShape UPPER_STRAIGHT = buildUpperStraight();
    private static final VoxelShape UPPER_INNER = buildUpperInner();
    private static final VoxelShape UPPER_OUTER = buildUpperOuter();

    private static final Direction[] HORIZONTALS = new Direction[]{
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private static final VoxelShape[][] LOWER_BOTTOM_CACHE = buildVoxelCache(Variant.LOWER, BlockHalf.BOTTOM);
    private static final VoxelShape[][] LOWER_TOP_CACHE = buildVoxelCache(Variant.LOWER, BlockHalf.TOP);
    private static final VoxelShape[][] UPPER_BOTTOM_CACHE = buildVoxelCache(Variant.UPPER, BlockHalf.BOTTOM);
    private static final VoxelShape[][] UPPER_TOP_CACHE = buildVoxelCache(Variant.UPPER, BlockHalf.TOP);

    private final Variant variant;

    public SlopeSteepBlock(Settings settings, Variant variant) {
        super(settings);
        this.variant = variant;
        this.setDefaultState(
                this.stateManager.getDefaultState()
                        .with(FACING, Direction.NORTH)
                        .with(SHAPE, SlopeShape.STRAIGHT)
                        .with(HALF, BlockHalf.BOTTOM)
                        .with(WATERLOGGED, false)
        );
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE, HALF, WATERLOGGED);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        boolean waterlogged = ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER;

        BlockHalf half;
        if (ctx.getSide() == Direction.DOWN) {
            half = BlockHalf.TOP;
        } else if (ctx.getSide() == Direction.UP) {
            half = BlockHalf.BOTTOM;
        } else {
            double hitY = ctx.getHitPos().y - ctx.getBlockPos().getY();
            half = (hitY > 0.5) ? BlockHalf.TOP : BlockHalf.BOTTOM;
        }

        BlockState placed = this.getDefaultState()
                .with(FACING, facing)
                .with(HALF, half)
                .with(WATERLOGGED, waterlogged)
                .with(SHAPE, SlopeShape.STRAIGHT);

        return getStateWithShape(placed, ctx.getWorld(), ctx.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state,
                                                Direction direction,
                                                BlockState neighborState,
                                                WorldAccess world,
                                                BlockPos pos,
                                                BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        if (direction.getAxis().isHorizontal()) {
            return getStateWithShape(state, world, pos);
        }
        return state;
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
    public boolean canFillWithFluid(BlockView world, BlockPos pos, BlockState state, Fluid fluid) {
        return !state.get(WATERLOGGED) && fluid == Fluids.WATER;
    }

    @Override
    public boolean tryFillWithFluid(WorldAccess world, BlockPos pos, BlockState state, FluidState fluidState) {
        if (!state.get(WATERLOGGED) && fluidState.getFluid() == Fluids.WATER) {
            world.setBlockState(pos, state.with(WATERLOGGED, true), Block.NOTIFY_ALL);
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
            return true;
        }
        return false;
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        SlopeShape shape = state.get(SHAPE);

        if (mirror == BlockMirror.LEFT_RIGHT && facing.getAxis() == Direction.Axis.Z) {
            return rotate(state, BlockRotation.CLOCKWISE_180).with(SHAPE, swapLeftRight(shape));
        }
        if (mirror == BlockMirror.FRONT_BACK && facing.getAxis() == Direction.Axis.X) {
            return rotate(state, BlockRotation.CLOCKWISE_180).with(SHAPE, swapOuter(shape));
        }
        return state;
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

    private static SlopeShape swapOuter(SlopeShape shape) {
        return switch (shape) {
            case OUTER_LEFT -> SlopeShape.OUTER_RIGHT;
            case OUTER_RIGHT -> SlopeShape.OUTER_LEFT;
            default -> shape;
        };
    }

    private VoxelShape getVoxelForState(BlockState state) {
        Direction facing = state.get(FACING);
        SlopeShape shape = state.get(SHAPE);
        BlockHalf half = state.get(HALF);

        VoxelShape[][] cache;
        if (variant == Variant.UPPER) {
            cache = (half == BlockHalf.TOP) ? UPPER_TOP_CACHE : UPPER_BOTTOM_CACHE;
        } else {
            cache = (half == BlockHalf.TOP) ? LOWER_TOP_CACHE : LOWER_BOTTOM_CACHE;
        }

        return cache[shape.ordinal()][horizontalIndex(facing)];
    }

    private BlockState getStateWithShape(BlockState state, BlockView world, BlockPos pos) {
        SlopeShape computed = computeCornerShape(state, world, pos);
        return state.with(SHAPE, computed);
    }

    private SlopeShape computeCornerShape(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);

        BlockState front = world.getBlockState(pos.offset(facing));
        if (isSameVariantSlope(front, state)) {
            Direction frontFacing = front.get(FACING);
            if (frontFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, frontFacing.getOpposite())) {
                return (frontFacing == facing.rotateYCounterclockwise())
                        ? SlopeShape.INNER_LEFT
                        : SlopeShape.INNER_RIGHT;
            }
        }

        BlockState back = world.getBlockState(pos.offset(facing.getOpposite()));
        if (isSameVariantSlope(back, state)) {
            Direction backFacing = back.get(FACING);
            if (backFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, backFacing)) {
                return (backFacing == facing.rotateYCounterclockwise())
                        ? SlopeShape.OUTER_LEFT
                        : SlopeShape.OUTER_RIGHT;
            }
        }

        return SlopeShape.STRAIGHT;
    }

    private boolean isDifferentOrientation(BlockState state, BlockView world, BlockPos pos, Direction dir) {
        BlockState other = world.getBlockState(pos.offset(dir));
        return !(isSameVariantSlope(other, state) && other.get(FACING) == state.get(FACING));
    }

    private boolean isSameVariantSlope(BlockState otherState, BlockState selfState) {
        if (!(otherState.getBlock() instanceof SlopeSteepBlock other)) {
            return false;
        }
        if (other.variant != this.variant) {
            return false;
        }
        return otherState.get(HALF) == selfState.get(HALF);
    }

    private static VoxelShape[][] buildVoxelCache(Variant variant, BlockHalf half) {
        boolean upper = variant == Variant.UPPER;

        VoxelShape straight = upper ? UPPER_STRAIGHT : LOWER_STRAIGHT;
        VoxelShape inner = upper ? UPPER_INNER : LOWER_INNER;
        VoxelShape outer = upper ? UPPER_OUTER : LOWER_OUTER;

        if (half == BlockHalf.TOP) {
            straight = flipY(straight);
            inner = flipY(inner);
            outer = flipY(outer);
        }

        VoxelShape[][] cache = new VoxelShape[SlopeShape.values().length][4];

        for (SlopeShape shape : SlopeShape.values()) {
            VoxelShape base = switch (shape) {
                case INNER_LEFT, INNER_RIGHT -> inner;
                case OUTER_LEFT, OUTER_RIGHT -> outer;
                case STRAIGHT -> straight;
            };

            for (Direction facing : HORIZONTALS) {
                int steps = yStepsForFacing(facing);
                if (shape == SlopeShape.INNER_RIGHT || shape == SlopeShape.OUTER_RIGHT) {
                    steps = (steps + 1) & 3;
                }

                cache[shape.ordinal()][horizontalIndex(facing)] = rotateShapeSteps(base, steps);
            }
        }

        return cache;
    }

    private static VoxelShape flipY(VoxelShape in) {
        final VoxelShape[] out = {VoxelShapes.empty()};

        in.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double newMinY = 1.0 - maxY;
            double newMaxY = 1.0 - minY;

            out[0] = VoxelShapes.union(
                    out[0],
                    VoxelShapes.cuboid(minX, newMinY, minZ, maxX, newMaxY, maxZ)
            );
        });

        return out[0];
    }

    private static VoxelShape buildLowerStraight() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0, 0.03125, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.0625, 0, 0.09375, 0.875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.03125, 0.0625, 0, 0.0625, 0.9375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09375, 0.0625, 0, 0.125, 0.8125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.125, 0.0625, 0, 0.15625, 0.75, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.15625, 0.0625, 0, 0.1875, 0.6875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.1875, 0.0625, 0, 0.21875, 0.625, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.21875, 0.0625, 0, 0.25, 0.5625, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.25, 0.0625, 0, 0.28125, 0.5, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.28125, 0.0625, 0, 0.3125, 0.4375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3125, 0.0625, 0, 0.34375, 0.375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.34375, 0.0625, 0, 0.375, 0.3125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.375, 0.0625, 0, 0.40625, 0.25, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.40625, 0.0625, 0, 0.4375, 0.1875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4375, 0.0625, 0, 0.46875, 0.125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.5, 0.0625, 1), BooleanBiFunction.OR);

        return shape;
    }

    private static VoxelShape buildLowerInner() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0006250000000000006, 0, 0.5, 0.063125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4375, 0.063125, 0, 0.46875, 0.125625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.40625, 0.06312499999999999, 0, 0.4375, 0.188125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.375, 0.06312499999999999, 0, 0.40625, 0.250625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.34375, 0.06312499999999999, 0, 0.375, 0.313125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3125, 0.06312499999999999, 0, 0.34375, 0.375625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.28125, 0.06312499999999999, 0, 0.3125, 0.438125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.25, 0.06312499999999999, 0, 0.28125, 0.500625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.21875, 0.06312499999999999, 0, 0.25, 0.563125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.1875, 0.06312499999999999, 0, 0.21875, 0.625625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.15625, 0.06312499999999999, 0, 0.1875, 0.688125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.125, 0.06312499999999999, 0, 0.15625, 0.750625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09375, 0.06312499999999999, 0, 0.125, 0.813125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.03125, 0.06312499999999999, 0, 0.0625, 0.938125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.06312499999999999, 0, 0.09375, 0.875625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.06312499999999999, 0, 0.03125, 0.999375, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.96875, 0.999375, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.90625, 1, 0.875, 0.9375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.9375, 1, 0.9375, 0.96875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.875, 1, 0.8125, 0.90625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.84375, 1, 0.75, 0.875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.8125, 1, 0.6875, 0.84375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.78125, 1, 0.625, 0.8125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.75, 1, 0.5625, 0.78125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.71875, 1, 0.5, 0.75), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.6875, 1, 0.4375, 0.71875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.65625, 1, 0.375, 0.6875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.625, 1, 0.3125, 0.65625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.59375, 1, 0.25, 0.625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.5625, 1, 0.1875, 0.59375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0.53125, 1, 0.125, 0.5625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0.5, 1, 0.0625, 1), BooleanBiFunction.OR);

        return shape;
    }

    private static VoxelShape buildLowerOuter() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.9375, 0.9687499737739563, 0.031875014305114746, 1, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.0625, 0.5312499737739563, 0.46937501430511475, 0.125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0, 0.49999997377395633, 0.5006250143051147, 0.0625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.125, 0.5624999737739563, 0.43812501430511475, 0.1875, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.1875, 0.5937499737739563, 0.40687501430511475, 0.25, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.25, 0.6249999737739563, 0.37562501430511475, 0.3125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.3125, 0.6562499737739563, 0.34437501430511475, 0.375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.375, 0.6874999737739563, 0.31312501430511475, 0.4375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.4375, 0.7187499737739563, 0.28187501430511475, 0.5, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.5, 0.7499999737739563, 0.25062501430511475, 0.5625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.5625, 0.7812499737739563, 0.21937501430511475, 0.625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.625, 0.8124999737739563, 0.18812501430511475, 0.6875, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.6875, 0.8437499737739563, 0.15687501430511475, 0.75, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.75, 0.8749999737739563, 0.12562501430511475, 0.8125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.875, 0.9374999737739563, 0.06312501430511475, 0.9375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.8125, 0.9062499737739563, 0.09437501430511475, 0.875, 0.9999999737739563), BooleanBiFunction.OR);

        return shape;
    }

    private static VoxelShape buildUpperStraight() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.0625, 0, 0.53125, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5625, 0.0625, 0, 0.59375, 0.875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.53125, 0.0625, 0, 0.5625, 0.9375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.59375, 0.0625, 0, 0.625, 0.8125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.625, 0.0625, 0, 0.65625, 0.75, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.65625, 0.0625, 0, 0.6875, 0.6875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6875, 0.0625, 0, 0.71875, 0.625, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.71875, 0.0625, 0, 0.75, 0.5625, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.75, 0.0625, 0, 0.78125, 0.5, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.78125, 0.0625, 0, 0.8125, 0.4375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8125, 0.0625, 0, 0.84375, 0.375, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.84375, 0.0625, 0, 0.875, 0.3125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.875, 0.0625, 0, 0.90625, 0.25, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.90625, 0.0625, 0, 0.9375, 0.1875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9375, 0.0625, 0, 0.96875, 0.125, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 0.0625, 1), BooleanBiFunction.OR);

        return shape;
    }

    private static VoxelShape buildUpperInner() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9375, 0.063125, 0, 0.96875, 0.125625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.90625, 0.06312499999999999, 0, 0.9375, 0.188125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.875, 0.06312499999999999, 0, 0.90625, 0.250625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.84375, 0.06312499999999999, 0, 0.875, 0.313125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8125, 0.06312499999999999, 0, 0.84375, 0.375625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.78125, 0.06312499999999999, 0, 0.8125, 0.438125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.75, 0.06312499999999999, 0, 0.78125, 0.500625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.71875, 0.06312499999999999, 0, 0.75, 0.563125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6875, 0.06312499999999999, 0, 0.71875, 0.625625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.65625, 0.06312499999999999, 0, 0.6875, 0.688125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.625, 0.06312499999999999, 0, 0.65625, 0.750625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.59375, 0.06312499999999999, 0, 0.625, 0.813125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.53125, 0.06312499999999999, 0, 0.5625, 0.938125, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5625, 0.06312499999999999, 0, 0.59375, 0.875625, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.06312499999999999, 0, 0.53125, 0.999375, 0.999375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.46875, 0.999375, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.40625, 0.999375, 0.875, 0.4375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.4375, 0.999375, 0.9375, 0.46875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.375, 0.999375, 0.8125, 0.40625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.34375, 0.999375, 0.75, 0.375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.3125, 0.999375, 0.6875, 0.34375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.28125, 0.999375, 0.625, 0.3125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.25, 0.999375, 0.5625, 0.28125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.21875, 0.999375, 0.5, 0.25), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.1875, 0.999375, 0.4375, 0.21875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.15625, 0.999375, 0.375, 0.1875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.125, 0.999375, 0.3125, 0.15625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.09375, 0.999375, 0.25, 0.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.0625, 0.999375, 0.1875, 0.09375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0.0625, 0.03125, 0.999375, 0.125, 0.0625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.000625, 0, 0.000625, 0.999375, 0.0625, 1.000625), BooleanBiFunction.OR);

        return shape;
    }

    private static VoxelShape buildUpperOuter() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.9375, 0.46874997377395633, 0.5318750143051147, 1, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0, -2.622604367008563e-8, 1.0006250143051147, 0.0625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.0625, 0.03124997377395633, 0.9693750143051147, 0.125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.125, 0.06249997377395633, 0.9381250143051147, 0.1875, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.1875, 0.09374997377395633, 0.9068750143051147, 0.25, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.25, 0.12499997377395633, 0.8756250143051147, 0.3125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.3125, 0.15624997377395633, 0.8443750143051147, 0.375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.375, 0.18749997377395633, 0.8131250143051147, 0.4375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.4375, 0.21874997377395633, 0.7818750143051147, 0.5, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.5, 0.24999997377395633, 0.7506250143051147, 0.5625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.5625, 0.28124997377395633, 0.7193750143051147, 0.625, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.625, 0.31249997377395633, 0.6881250143051147, 0.6875, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.6875, 0.34374997377395633, 0.6568750143051147, 0.75, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.75, 0.37499997377395633, 0.6256250143051147, 0.8125, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.875, 0.43749997377395633, 0.5631250143051147, 0.9375, 0.9999999737739563), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006250143051147461, 0.8125, 0.40624997377395633, 0.5943750143051147, 0.875, 0.9999999737739563), BooleanBiFunction.OR);

        return shape;
    }

    private static int yStepsForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 0;
            case SOUTH -> 1;
            case WEST -> 2;
            case NORTH -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeSteps(VoxelShape shape, int steps) {
        int n = ((steps % 4) + 4) % 4;
        VoxelShape current = shape;

        for (int i = 0; i < n; i++) {
            final VoxelShape[] rotatedHolder = {VoxelShapes.empty()};

            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                double newMinX = 1.0 - maxZ;
                double newMinZ = minX;
                double newMaxX = 1.0 - minZ;
                double newMaxZ = maxX;

                rotatedHolder[0] = VoxelShapes.union(
                        rotatedHolder[0],
                        VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ)
                );
            });

            current = rotatedHolder[0];
        }

        return current;
    }

    private static int horizontalIndex(Direction d) {
        return switch (d) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
