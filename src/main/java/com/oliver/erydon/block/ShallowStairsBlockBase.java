package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.util.HashMap;
import java.util.Map;

public abstract class ShallowStairsBlockBase extends StairsBlock implements Waterloggable {
    protected static final int LOW_Y = 4;
    protected static final int HIGH_Y = 8;
    protected static final int MID_Y = 12;

    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private final Map<BlockState, VoxelShape> SHAPE_CACHE = new HashMap<>();

    private BlockState normalizeForCache(BlockState s) {
        if (s.contains(HALF)) {
            return s.with(HALF, net.minecraft.block.enums.BlockHalf.BOTTOM);
        }
        return s;
    }

    public ShallowStairsBlockBase(BlockState baseBlockState, Settings settings) {
        super(baseBlockState, settings);
    }

    // HALF must be inert for geometry. Subclasses override if needed.
    protected boolean isTopHalf() { return false; }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockState key = normalizeForCache(state);
        return SHAPE_CACHE.computeIfAbsent(key, this::calculateShape);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState state = super.getPlacementState(ctx);
        if (state == null) {
            return null;
        }
        boolean waterlogged = ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER;
        return state.with(WATERLOGGED, waterlogged);
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
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    private VoxelShape calculateShape(BlockState state) {
        Direction facing = state.get(FACING);
        StairShape shape = state.get(SHAPE);
        BlockHalf half = isTopHalf() ? BlockHalf.TOP : BlockHalf.BOTTOM;

        VoxelShape baseShapeNorth = createBaseShape(shape, half);
        int deg = (facingDeg(facing) + shapeOffsetDeg(shape)) % 360;
        return rotateY(baseShapeNorth, deg);
    }

    private VoxelShape createBaseShape(StairShape shape, BlockHalf half) {
        if (half == BlockHalf.BOTTOM) {
            return createBottomShape(shape);
        } else {
            return createTopShape(shape);
        }
    }

    // Rotation helpers to align collision/outline with blockstate render rotations
    private static int facingDeg(Direction f) {
        switch (f) {
            case NORTH: return 0;
            case EAST:  return 90;
            case SOUTH: return 180;
            case WEST:  return 270;
            default:    return 0;
        }
    }

    private static int shapeOffsetDeg(StairShape shape) {
        // Match blockstate rotations: baseline +180 for all; outer_left extra +270; outer_right extra +90
        switch (shape) {
            case STRAIGHT:
            case INNER_LEFT:
            case INNER_RIGHT:
                return 180;
            case OUTER_LEFT:
                return 90;   // 180 + 270 ≡ 90
            case OUTER_RIGHT:
                return 270;  // 180 +  90 ≡ 270
            default:
                return 0;
        }
    }

    private static VoxelShape rotateY(VoxelShape shape, int degrees) {
        int steps = ((degrees % 360) + 360) % 360 / 90;
        VoxelShape out = shape;
        for (int i = 0; i < steps; i++) {
            out = rotateY90(out);
        }
        return out;
    }

    // Rotate boxes 90° clockwise around Y within 0..16 block space
    private static VoxelShape rotateY90(VoxelShape shape) {
        VoxelShape[] acc = new VoxelShape[]{VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // forEachBox delivers normalized [0..1] coords; convert to [0..16] and rotate 90° clockwise around Y
            double nx1 = 16 - (maxZ * 16);
            double nz1 = minX * 16;
            double nx2 = 16 - (minZ * 16);
            double nz2 = maxX * 16;
            acc[0] = VoxelShapes.union(acc[0], Block.createCuboidShape(nx1, minY * 16, nz1, nx2, maxY * 16, nz2));
        });
        return acc[0];
    }

    private VoxelShape createBottomShape(StairShape shape) {
        switch (shape) {
            case STRAIGHT:
                // Front section: y -4..4, z 0..8 (extends below and up to LOW_Y)
                // Back raised section: y 0..8, z 8..16
                return VoxelShapes.union(
                        Block.createCuboidShape(0, -4, 0, 16, LOW_Y, 8),
                        Block.createCuboidShape(0, 0, 8, 16, HIGH_Y, 16)
                );
            case INNER_LEFT:
                // Front: y 0..8, z 0..8
                // Back: y 0..8, z 8..16
                // No additional side piece needed as both are same height
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 0, 0, 16, HIGH_Y, 8),
                        Block.createCuboidShape(0, 0, 8, 16, HIGH_Y, 16)
                );
            case INNER_RIGHT:
                // Front: y 0..8, z 0..8
                // Back: y 0..8, z 8..16
                // No additional side piece needed as both are same height
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 0, 0, 16, HIGH_Y, 8),
                        Block.createCuboidShape(0, 0, 8, 16, HIGH_Y, 16)
                );
            case OUTER_LEFT:
                // Base layer: y 0..4, full 16x16
                // Left-back raised corner: y 4..8, x 0..8, z 8..16
                // Back lower trim: y -4..0, z 8..16
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 0, 0, 16, LOW_Y, 16),
                        Block.createCuboidShape(0, LOW_Y, 8, 8, HIGH_Y, 16),
                        Block.createCuboidShape(0, -4, 8, 16, 0, 16)
                );
            case OUTER_RIGHT:
                // Base layer: y 0..4, full 16x16
                // Right-back raised corner: y 4..8, x 8..16, z 8..16
                // Right-side lower trim: y -4..0, x 8..16, full depth
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 0, 0, 16, LOW_Y, 16),
                        Block.createCuboidShape(8, LOW_Y, 8, 16, HIGH_Y, 16),
                        Block.createCuboidShape(8, -4, 0, 16, 0, 16)
                );
            default:
                return VoxelShapes.fullCube();
        }
    }

    private VoxelShape createTopShape(StairShape shape) {
        switch (shape) {
            case STRAIGHT:
                // Front tread: y 4..12, z 0..8 (8 pixels tall)
                // Back tread: y 8..16, z 8..16 (8 pixels tall)
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 4, 0, 16, MID_Y, 8),
                        Block.createCuboidShape(0, 8, 8, 16, 16, 16)
                );
            case INNER_LEFT:
                // Front: y 4..12, z 0..8
                // Back: y 8..16, z 8..16
                // Left side front filler: y 8..12, x 0..8, z 0..8
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 4, 0, 16, MID_Y, 8),
                        Block.createCuboidShape(0, 8, 8, 16, 16, 16),
                        Block.createCuboidShape(0, 8, 0, 8, MID_Y, 8)
                );
            case INNER_RIGHT:
                // Front: y 4..12, z 0..8
                // Back: y 8..16, z 8..16
                // Right side front filler: y 8..12, x 8..16, z 0..8
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 4, 0, 16, MID_Y, 8),
                        Block.createCuboidShape(0, 8, 8, 16, 16, 16),
                        Block.createCuboidShape(8, 8, 0, 16, MID_Y, 8)
                );
            case OUTER_LEFT:
                // Front: y 4..12, z 0..8
                // Back left: y 8..16, x 0..8, z 8..16
                // Opposite cap: y 8..12, x 8..16, z 8..16
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 4, 0, 16, MID_Y, 8),
                        Block.createCuboidShape(0, 8, 8, 8, 16, 16),
                        Block.createCuboidShape(8, 8, 8, 16, MID_Y, 16)
                );
            case OUTER_RIGHT:
                // Front: y 4..12, z 0..8
                // Back right: y 8..16, x 8..16, z 8..16
                // Opposite cap: y 8..12, x 0..8, z 8..16
                return VoxelShapes.union(
                        Block.createCuboidShape(0, 4, 0, 16, MID_Y, 8),
                        Block.createCuboidShape(8, 8, 8, 16, 16, 16),
                        Block.createCuboidShape(0, 8, 8, 8, MID_Y, 16)
                );
            default:
                return VoxelShapes.fullCube();
        }
    }

    private VoxelShape rotateShape(VoxelShape shape, Direction facing) {
        VoxelShape[] buffer = new VoxelShape[]{shape, VoxelShapes.empty()};

        switch (facing) {
            case EAST:
                buffer[0].forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                    buffer[1] = VoxelShapes.union(buffer[1],
                            Block.createCuboidShape(
                                    (1 - maxZ) * 16, minY * 16, minX * 16,
                                    (1 - minZ) * 16, maxY * 16, maxX * 16
                            ));
                });
                return buffer[1];
            case SOUTH:
                buffer[0].forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                    buffer[1] = VoxelShapes.union(buffer[1],
                            Block.createCuboidShape(
                                    (1 - maxX) * 16, minY * 16, (1 - maxZ) * 16,
                                    (1 - minX) * 16, maxY * 16, (1 - minZ) * 16
                            ));
                });
                return buffer[1];
            case WEST:
                buffer[0].forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                    buffer[1] = VoxelShapes.union(buffer[1],
                            Block.createCuboidShape(
                                    minZ * 16, minY * 16, (1 - maxX) * 16,
                                    maxZ * 16, maxY * 16, (1 - minX) * 16
                            ));
                });
                return buffer[1];
            case NORTH:
            default:
                return shape;
        }
    }
}
