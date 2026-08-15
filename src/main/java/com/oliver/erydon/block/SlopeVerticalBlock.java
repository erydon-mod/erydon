package com.oliver.erydon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;


public class SlopeVerticalBlock extends Block implements Waterloggable {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    public SlopeVerticalBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(WATERLOGGED, false));
    }

    static {
        VoxelShape northShape = createNorthWedgeShape();
        SHAPES.put(Direction.NORTH, northShape);
        SHAPES.put(Direction.EAST, rotateShape(Direction.NORTH, Direction.EAST, northShape));
        SHAPES.put(Direction.SOUTH, rotateShape(Direction.NORTH, Direction.SOUTH, northShape));
        SHAPES.put(Direction.WEST, rotateShape(Direction.NORTH, Direction.WEST, northShape));
    }


    private static VoxelShape createNorthWedgeShape() {
        VoxelShape shape = VoxelShapes.empty();

        // z: north -> south, x: west -> east
        // Approximate region: x + z <= 16 (right triangle).
        for (int z = 0; z < 16; z++) {
            double zMin = z;
            double zMax = z + 1;
            double xMax = 16 - z; // shrink X as z increases (towards south)

            if (xMax <= 0) continue;

            shape = VoxelShapes.union(
                    shape,
                    VoxelShapes.cuboid(
                            0.0 / 16.0,      // minX
                            0.0 / 16.0,      // minY
                            zMin / 16.0,     // minZ
                            xMax / 16.0,     // maxX
                            16.0 / 16.0,     // maxY
                            zMax / 16.0      // maxZ
                    )
            );
        }

        return shape.simplify();
    }


    private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        if (from == to) return shape;
        if (!from.getAxis().isHorizontal() || !to.getAxis().isHorizontal()) {
            return shape;
        }

        int times = (to.getHorizontal() - from.getHorizontal() + 4) % 4;
        VoxelShape result = shape;

        for (int i = 0; i < times; i++) {
            VoxelShape[] buffer = new VoxelShape[]{VoxelShapes.empty()};
            VoxelShape before = result;

            before.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                // 90° rotation: (x, z) -> (1 - z, x)
                double newMinX = 1.0 - maxZ;
                double newMinZ = minX;
                double newMaxX = 1.0 - minZ;
                double newMaxZ = maxX;

                buffer[0] = VoxelShapes.union(
                        buffer[0],
                        VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ)
                );
            });

            result = buffer[0].simplify();
        }

        return result;
    }


    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }
    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction side = ctx.getSide();
        Direction facing;
        boolean waterlogged = ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER;

        // Only do quadrant logic when placing on a horizontal face
        if (side == Direction.UP || side == Direction.DOWN) {
            Vec3d hit = ctx.getHitPos();

            // Fractional position within the block: 0..1 where
            // x: 0 = west, 1 = east
            // z: 0 = north, 1 = south
            double fracX = hit.x - Math.floor(hit.x);
            double fracZ = hit.z - Math.floor(hit.z);

            boolean east = fracX >= 0.5D;
            boolean south = fracZ >= 0.5D;

            // Map quadrants to which corner gets the 90° edge.
            // Based on createNorthWedgeShape:
            //  - FACING.NORTH  -> right angle at NW
            //  - FACING.EAST   -> right angle at NE
            //  - FACING.SOUTH  -> right angle at SE
            //  - FACING.WEST   -> right angle at SW
            if (!east && !south) {
                // NW quadrant
                facing = Direction.NORTH;
            } else if (east && !south) {
                // NE quadrant
                facing = Direction.EAST;
            } else if (east && south) {
                // SE quadrant
                facing = Direction.SOUTH;
            } else {
                // SW quadrant
                facing = Direction.WEST;
            }
        } else {
            // Placing on a vertical face? Keep the old "away from player" logic.
            facing = ctx.getHorizontalPlayerFacing().getOpposite();
        }

        return this.getDefaultState()
                .with(FACING, facing)
                .with(WATERLOGGED, waterlogged);
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

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }


    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction mirroredFacing = switch (mirror) {
            case LEFT_RIGHT -> mirrorLeftRight(state.get(FACING));
            case FRONT_BACK -> mirrorFrontBack(state.get(FACING));
            case NONE -> state.get(FACING);
        };
        return state.with(FACING, mirroredFacing);
    }


    @Override
    public VoxelShape getOutlineShape(BlockState state,
                                      BlockView world,
                                      BlockPos pos,
                                      ShapeContext context) {
        return SHAPES.getOrDefault(state.get(FACING), VoxelShapes.fullCube());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state,
                                        BlockView world,
                                        BlockPos pos,
                                        ShapeContext context) {
        return SHAPES.getOrDefault(state.get(FACING), VoxelShapes.fullCube());
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    private static Direction mirrorLeftRight(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.NORTH;
            default -> facing;
        };
    }

    private static Direction mirrorFrontBack(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            default -> facing;
        };
    }
}
