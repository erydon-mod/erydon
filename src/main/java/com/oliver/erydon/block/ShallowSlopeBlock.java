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

public class ShallowSlopeBlock extends HorizontalFacingBlock implements Waterloggable {

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

    // Use 1/32 increments to match your Blockbench voxel dumps (0.03125 steps).
    private static final int STEPS = 32;

    // --- Procedural voxel shapes (collision/outline) ---
    // Base orientation matches your current blockstate pattern:
    // facing=EAST with y=0 is treated as "native".
    // The wedge is LOW on the EAST edge (x=1) and rises toward WEST (x=0).
    private static final VoxelShape LOWER_STRAIGHT = createSteppedSlopeX(0.0, 0.5); // 0.0 -> 0.5
    private static final VoxelShape UPPER_STRAIGHT = createSteppedSlopeX(0.5, 0.5); // 0.5 -> 1.0 (still solid below)

    // Inner/outer are derived from the straight slope by combining with a 90° CCW rotated copy.
    // NOTE: rotateShapeSteps is CW, so CCW = 3 CW steps.
    private static final VoxelShape LOWER_INNER = createInnerFromStraight(LOWER_STRAIGHT);
    private static final VoxelShape LOWER_OUTER = createOuterFromStraight(LOWER_STRAIGHT);
    private static final VoxelShape UPPER_INNER = createInnerFromStraight(UPPER_STRAIGHT);
    private static final VoxelShape UPPER_OUTER = createOuterFromStraight(UPPER_STRAIGHT);

    // Horizontal directions in index order matching horizontalIndex()
    private static final Direction[] HORIZONTALS = new Direction[]{
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    // Cache rotated voxel shapes for (shape x facing) so we don't rebuild per-call.
    private static final VoxelShape[][] LOWER_BOTTOM_CACHE = buildVoxelCache(Variant.LOWER, BlockHalf.BOTTOM);
    private static final VoxelShape[][] LOWER_TOP_CACHE = buildVoxelCache(Variant.LOWER, BlockHalf.TOP);
    private static final VoxelShape[][] UPPER_BOTTOM_CACHE = buildVoxelCache(Variant.UPPER, BlockHalf.BOTTOM);
    private static final VoxelShape[][] UPPER_TOP_CACHE = buildVoxelCache(Variant.UPPER, BlockHalf.TOP);

    private static VoxelShape[][] buildVoxelCache(Variant variant, BlockHalf half) {
        boolean upper = variant == Variant.UPPER;

        VoxelShape straight = upper ? UPPER_STRAIGHT : LOWER_STRAIGHT;
        VoxelShape inner = upper ? UPPER_INNER : LOWER_INNER;
        VoxelShape outer = upper ? UPPER_OUTER : LOWER_OUTER;

        // TOP-half shapes: flip vertically around y=0.5
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
                // Match your JSON rotation pattern:
                // facing=EAST => y=0
                // SOUTH => 90, WEST => 180, NORTH => 270
                int steps = yStepsForFacing(facing);

                // *_RIGHT is rendered 90° clockwise relative to *_LEFT in your model set
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
            // Mirror around the block mid-plane (y=0.5): y -> 1 - y
            double newMinY = 1.0 - maxY;
            double newMaxY = 1.0 - minY;

            out[0] = VoxelShapes.union(
                    out[0],
                    VoxelShapes.cuboid(minX, newMinY, minZ, maxX, newMaxY, maxZ)
            );
        });

        return out[0];
    }

    private final Variant variant;

    public ShallowSlopeBlock(Settings settings, Variant variant) {
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

        // Stairs-style half placement:
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

    /**
     * Stairs-style corner detection.
     */
    private SlopeShape computeCornerShape(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);

        // --- Check in front for INNER corners ---
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

        // --- Check behind for OUTER corners ---
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

    /**
     * Prevents creating inner/outer corners when a matching slope continues straight through the corner cell.
     */
    private boolean isDifferentOrientation(BlockState state, BlockView world, BlockPos pos, Direction dir) {
        BlockState other = world.getBlockState(pos.offset(dir));
        return !(isSameVariantSlope(other, state) && other.get(FACING) == state.get(FACING));
    }

    private boolean isSameVariantSlope(BlockState otherState, BlockState selfState) {
        if (!(otherState.getBlock() instanceof ShallowSlopeBlock other)) {
            return false;
        }
        if (other.variant != this.variant) {
            return false;
        }
        // Only connect corners within the same half (top/bottom), like vanilla stairs.
        return otherState.get(HALF) == selfState.get(HALF);
    }

    // --- Voxel helpers ---------------------------------------------------------

    private static VoxelShape createSteppedSlopeX(double baseY, double rise) {
        VoxelShape shape = VoxelShapes.empty();
        double step = 1.0 / STEPS;
        double stepRise = rise / STEPS;

        // Wedge profile along X (east-west): low on EAST (x=1), high on WEST (x=0).
        // Each slice is a full Z strip (0..1).
        for (int i = 0; i < STEPS; i++) {
            double minX = i * step;
            double maxX = (i + 1) * step;

            // Height decreases as x increases, so the last slice hits baseY exactly.
            double maxY = (baseY + rise) - stepRise * (i + 1);

            shape = VoxelShapes.union(
                    shape,
                    VoxelShapes.cuboid(minX, 0.0, 0.0, maxX, maxY, 1.0)
            );
        }

        // Peak face at the WEST edge to hit the exact target height.
        shape = VoxelShapes.union(
                shape,
                VoxelShapes.cuboid(0.0, 0.0, 0.0, step, baseY + rise, 1.0)
        );

        return shape;
    }

    private static VoxelShape createInnerFromStraight(VoxelShape straight) {
        // CCW = 3 CW steps (rotateShapeSteps is clockwise)
        VoxelShape rotated = rotateShapeSteps(straight, 3);
        return VoxelShapes.union(straight, rotated);
    }

    private static VoxelShape createOuterFromStraight(VoxelShape straight) {
        // CCW = 3 CW steps (rotateShapeSteps is clockwise)
        VoxelShape rotated = rotateShapeSteps(straight, 3);
        return VoxelShapes.combineAndSimplify(straight, rotated, BooleanBiFunction.AND);
    }

    /**
     * Maps your blockstate render pattern to quarter-turn steps.
     * In your JSON: facing=EAST uses y=0; SOUTH y=90; WEST y=180; NORTH y=270.
     */
    private static int yStepsForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 0;
            case SOUTH -> 1;
            case WEST -> 2;
            case NORTH -> 3;
            default -> 0;
        };
    }

    /** Rotates the given shape by N * 90° clockwise around Y. */
    private static VoxelShape rotateShapeSteps(VoxelShape shape, int steps) {
        int n = ((steps % 4) + 4) % 4;
        VoxelShape current = shape;

        for (int i = 0; i < n; i++) {
            final VoxelShape[] rotatedHolder = {VoxelShapes.empty()};

            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                // 90° clockwise around Y: (x, z) -> (1 - z, x)
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
