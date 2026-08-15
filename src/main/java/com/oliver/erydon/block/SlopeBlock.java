package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.util.Locale;

/**
 * 90° slope block (single block id per stem) with stairs-style corner logic:
 * - shape: straight / inner_left / inner_right / outer_left / outer_right
 * - facing: horizontal
 * - half: bottom/top (top is upside-down placement)
 *
 * Blockstate rotation convention assumed:
 * - straight + inner_left + outer_left use y based on facing
 * - inner_right + outer_right use y based on facing.rotateYClockwise()
 * - half=top uses x=180 and keeps the same y mapping
 */
public class SlopeBlock extends Block implements Waterloggable {

    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    public static final EnumProperty<SlopeShape> SHAPE = EnumProperty.of("shape", SlopeShape.class);
    public static final EnumProperty<BlockHalf> HALF = Properties.BLOCK_HALF;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final Direction[] HORIZONTALS = new Direction[]{
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    // --- Voxel resolution -----------------------------------------------------
    // 16 = chunkier but clean & fast
    // 32 = closer to your original exports
    private static final int VOXEL_STEPS = 16;
    private static final double STEP = 1.0D / VOXEL_STEPS;

    /**
     * Cached shapes: [halfIndex][shapeOrdinal][facingIndex]
     * halfIndex: 0 = BOTTOM, 1 = TOP
     */
    private static final VoxelShape[][][] SHAPE_CACHE = buildShapeCache();

    public SlopeBlock(Settings settings) {
        super(settings);
        this.setDefaultState(
                this.stateManager.getDefaultState()
                        .with(FACING, Direction.NORTH)
                        .with(SHAPE, SlopeShape.STRAIGHT)
                        .with(HALF, BlockHalf.BOTTOM)
                        .with(WATERLOGGED, false)
        );
    }

    // ----- Placement / neighbor updates ---------------------------------------

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();

        FluidState fluidState = ctx.getWorld().getFluidState(ctx.getBlockPos());
        boolean waterlogged = fluidState.getFluid() == Fluids.WATER;

        boolean top = ctx.getSide() == Direction.DOWN
                || (ctx.getSide() != Direction.UP && ctx.getHitPos().y - (double) ctx.getBlockPos().getY() > 0.5D);

        BlockState placed = this.getDefaultState()
                .with(FACING, facing)
                .with(HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM)
                .with(SHAPE, SlopeShape.STRAIGHT)
                .with(WATERLOGGED, waterlogged);

        return getStateWithShape(placed, ctx.getWorld(), ctx.getBlockPos());
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
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        if (direction.getAxis().isHorizontal()) {
            return getStateWithShape(state, world, pos);
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    private BlockState getStateWithShape(BlockState state, BlockView world, BlockPos pos) {
        return state.with(SHAPE, computeCornerShape(state, world, pos));
    }

    /**
     * Matches your shallow-slope convention:
     * - front check produces INNER corners
     * - back check produces OUTER corners
     */
    private SlopeShape computeCornerShape(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);

        // --- front => INNER ---
        BlockState front = world.getBlockState(pos.offset(facing));
        if (isSameSlope(front, state)) {
            Direction frontFacing = front.get(FACING);
            if (frontFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, frontFacing.getOpposite())) {
                return (frontFacing == facing.rotateYCounterclockwise())
                        ? SlopeShape.INNER_LEFT
                        : SlopeShape.INNER_RIGHT;
            }
        }

        // --- back => OUTER ---
        BlockState back = world.getBlockState(pos.offset(facing.getOpposite()));
        if (isSameSlope(back, state)) {
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
        BlockState neighbor = world.getBlockState(pos.offset(dir));
        return !(isSameSlope(neighbor, state) && neighbor.get(FACING) == state.get(FACING));
    }

    private boolean isSameSlope(BlockState other, BlockState self) {
        return other.getBlock() == this && other.get(HALF) == self.get(HALF);
    }

    // ----- Shapes (outline / collision) ---------------------------------------

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCachedShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCachedShape(state);
    }

    private static VoxelShape getCachedShape(BlockState state) {
        int halfIndex = (state.get(HALF) == BlockHalf.TOP) ? 1 : 0;
        int shapeIndex = state.get(SHAPE).ordinal();
        int facingIndex = horizontalIndex(state.get(FACING));
        return SHAPE_CACHE[halfIndex][shapeIndex][facingIndex];
    }

    private static int horizontalIndex(Direction dir) {
        return switch (dir) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    // ----- Rotation / mirroring ----------------------------------------------

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

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE, HALF, WATERLOGGED);
    }

    // ----- Cache build (procedural voxels) ------------------------------------

    private static VoxelShape[][][] buildShapeCache() {
        VoxelShape[][][] cache = new VoxelShape[2][SlopeShape.values().length][4];

        for (int halfIndex = 0; halfIndex < 2; halfIndex++) {
            BlockHalf half = (halfIndex == 1) ? BlockHalf.TOP : BlockHalf.BOTTOM;
            int xRot = (half == BlockHalf.TOP) ? 180 : 0;

            for (SlopeShape shape : SlopeShape.values()) {
                for (Direction facing : HORIZONTALS) {
                    int yRot = modelYRotationDegrees(facing, half, shape);
                    cache[halfIndex][shape.ordinal()][horizontalIndex(facing)] =
                            buildGeneratedShape(shape, xRot, yRot);
                }
            }
        }

        return cache;
    }

    private static VoxelShape buildGeneratedShape(SlopeShape shape, int xRot, int yRot) {
        return switch (shape) {
            case STRAIGHT -> buildStraight(xRot, yRot);
            case OUTER_LEFT, OUTER_RIGHT -> buildOuter(xRot, yRot);
            case INNER_LEFT, INNER_RIGHT -> buildInner(xRot, yRot);
        };
    }

    /** STRAIGHT: ramp (X shrinks with Y), Z full. */
    private static VoxelShape buildStraight(int xRot, int yRot) {
        VoxelShape out = VoxelShapes.empty();

        for (int y = 0; y < VOXEL_STEPS; y++) {
            double y0 = y * STEP;
            double y1 = (y + 1) * STEP;

            double x1 = (VOXEL_STEPS - y) * STEP;
            out = unionTransformed(out, 0, y0, 0, x1, y1, 1, xRot, yRot);
        }

        return out.simplify();
    }

    /** OUTER: corner pyramid (X and Z both shrink with Y). */
    private static VoxelShape buildOuter(int xRot, int yRot) {
        VoxelShape out = VoxelShapes.empty();

        for (int y = 0; y < VOXEL_STEPS; y++) {
            double y0 = y * STEP;
            double y1 = (y + 1) * STEP;

            double span = (VOXEL_STEPS - y) * STEP;
            out = unionTransformed(out, 0, y0, 0, span, y1, span, xRot, yRot);
        }

        return out.simplify();
    }

    /**
     * INNER: ramp union back-fill.
     * Back-fill is: Z >= Y (layer-aligned), which matches “even interval slices”.
     */
    private static VoxelShape buildInner(int xRot, int yRot) {
        VoxelShape out = VoxelShapes.empty();

        for (int y = 0; y < VOXEL_STEPS; y++) {
            double y0 = y * STEP;
            double y1 = (y + 1) * STEP;

            // ramp
            double x1 = (VOXEL_STEPS - y) * STEP;
            out = unionTransformed(out, 0, y0, 0, x1, y1, 1, xRot, yRot);

            // back-fill (Z starts at this layer)
            double z0 = y * STEP;
            out = unionTransformed(out, 0, y0, z0, 1, y1, 1, xRot, yRot);
        }

        return out.simplify();
    }

    /**
     * Union a cuboid after applying the same X/Y rotations as your blockstates.
     * Rotation order: X then Y.
     */
    private static VoxelShape unionTransformed(
            VoxelShape out,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            int xRot, int yRot
    ) {
        // X rotation (180): flip Y and Z
        if (xRot == 180) {
            double nMinY = 1.0D - maxY;
            double nMaxY = 1.0D - minY;
            double nMinZ = 1.0D - maxZ;
            double nMaxZ = 1.0D - minZ;
            minY = nMinY; maxY = nMaxY;
            minZ = nMinZ; maxZ = nMaxZ;
        }

        // Y rotation (0/90/180/270): rotate clockwise around vertical axis
        int ySteps = (((yRot % 360) + 360) % 360) / 90;
        for (int s = 0; s < ySteps; s++) {
            double nMinX = minZ;
            double nMaxX = maxZ;
            double nMinZ = 1.0D - maxX;
            double nMaxZ = 1.0D - minX;
            minX = nMinX; maxX = nMaxX;
            minZ = nMinZ; maxZ = nMaxZ;
        }

        return VoxelShapes.union(out, VoxelShapes.cuboid(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * Rotation mapping to match your blockstate convention:
     * - straight + LEFT shapes: y based on facing
     * - RIGHT shapes: y based on facing.rotateYClockwise()
     */
    // replace lines 334–355 with:
    private static int modelYRotationDegrees(Direction facing, BlockHalf half, SlopeShape shape) {
        Direction f = facing;

        // right-hand shapes use facing.rotateYClockwise() (matches your blockstates)
        if (shape == SlopeShape.INNER_RIGHT || shape == SlopeShape.OUTER_RIGHT) {
            f = f.rotateYClockwise();
        }

        int y = yForFacing(f);

        // global OUTER CCW (matches your blockstates baseline)
        if (shape == SlopeShape.OUTER_LEFT || shape == SlopeShape.OUTER_RIGHT) {
            y = (y + 270) % 360; // -90 (CCW)
        }

        // keep your current north/south baseline correction
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            y = (y + 180) % 360;
        }

        // apply the very specific strike-only overrides
        y = (y + strikeExtraYDegrees(facing, half, shape)) % 360;

        return y;
    }

    private static int strikeExtraYDegrees(Direction facing, BlockHalf half, SlopeShape shape) {
        // 180° strike overrides
        if (half == BlockHalf.BOTTOM) {
            if (facing == Direction.EAST && shape == SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.NORTH && shape == SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.WEST && shape == SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.NORTH && shape == SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.SOUTH && shape == SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.SOUTH && shape == SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.WEST  && shape == SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.EAST  && shape == SlopeShape.OUTER_LEFT)  return 180;

            return 0;
        }

// TOP (upside-down) specific strike overrides

// Outer (keep your existing behaviour)
        if (facing == Direction.SOUTH && shape == SlopeShape.OUTER_LEFT) return 90;   // CW

        if (facing == Direction.NORTH && shape == SlopeShape.OUTER_LEFT) return 90;   // CW
        if (facing == Direction.EAST && shape == SlopeShape.OUTER_LEFT) return 90;    // CW
        if (facing == Direction.EAST  && shape == SlopeShape.OUTER_RIGHT) return 270; // 90° CCW
        if (facing == Direction.SOUTH && shape == SlopeShape.OUTER_RIGHT) return 270;
        if (facing == Direction.WEST && shape == SlopeShape.OUTER_RIGHT) return 270;
        if (facing == Direction.NORTH && shape == SlopeShape.OUTER_RIGHT) return 270; // 90° CCW
        if (facing == Direction.WEST  && shape == SlopeShape.OUTER_LEFT)  return 90;  // 90° CW


// Inner: this is the underlying pattern we've been chasing
// - inner_left needs +90 CW
// - inner_right needs +90 CCW
        if (shape == SlopeShape.INNER_LEFT)  return 90;   // CW
        if (shape == SlopeShape.INNER_RIGHT) return 270;  // CCW

        return 0;


    }


    /** y mapping used in your existing blockstates: east=0, south=90, west=180, north=270 */
    private static int yForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 0;
            case SOUTH -> 90;
            case WEST -> 180;
            case NORTH -> 270;
            default -> 0;
        };
    }

    // ----- Enum ----------------------------------------------------------------

    public enum SlopeShape implements StringIdentifiable {
        STRAIGHT,
        INNER_LEFT,
        INNER_RIGHT,
        OUTER_LEFT,
        OUTER_RIGHT;

        @Override
        public String asString() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}
