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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

public class GlazingVerticalSlopeBlock extends Block implements Waterloggable {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        VoxelShape northShape = createNorthDiagonalFrameShape();
        SHAPES.put(Direction.NORTH, northShape);
        SHAPES.put(Direction.EAST, rotateShape(Direction.NORTH, Direction.EAST, northShape));
        SHAPES.put(Direction.SOUTH, rotateShape(Direction.NORTH, Direction.SOUTH, northShape));
        SHAPES.put(Direction.WEST, rotateShape(Direction.NORTH, Direction.WEST, northShape));
    }

    public GlazingVerticalSlopeBlock(AbstractBlock.Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(WATERLOGGED, false));
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

        if (side == Direction.UP || side == Direction.DOWN) {
            Vec3d hit = ctx.getHitPos();
            double fracX = hit.x - Math.floor(hit.x);
            double fracZ = hit.z - Math.floor(hit.z);

            boolean east = fracX >= 0.5D;
            boolean south = fracZ >= 0.5D;

            if (!east && !south) {
                facing = Direction.NORTH;
            } else if (east && !south) {
                facing = Direction.EAST;
            } else if (east && south) {
                facing = Direction.SOUTH;
            } else {
                facing = Direction.WEST;
            }
        } else {
            facing = ctx.getHorizontalPlayerFacing().getOpposite();
        }

        return this.getDefaultState()
                .with(FACING, facing)
                .with(WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER);
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
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES.getOrDefault(state.get(FACING), VoxelShapes.fullCube());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
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
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    private static VoxelShape createNorthDiagonalFrameShape() {
        VoxelShape shape = VoxelShapes.empty();

        for (int z = 0; z < 16; z++) {
            double minZ = z / 16.0;
            double maxZ = (z + 1) / 16.0;
            double minX = (15 - z) / 16.0;
            double maxX = (17 - z) / 16.0;

            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                    Math.max(0.0, minX),
                    0.0,
                    minZ,
                    Math.min(1.0, maxX),
                    1.0,
                    maxZ
            ));
        }

        return shape.simplify();
    }

    private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        if (from == to) {
            return shape;
        }

        int turns = (to.getHorizontal() - from.getHorizontal() + 4) % 4;
        VoxelShape current = shape;

        for (int i = 0; i < turns; i++) {
            final VoxelShape[] rotated = {VoxelShapes.empty()};
            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> rotated[0] = VoxelShapes.union(
                    rotated[0],
                    VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)
            ));
            current = rotated[0].simplify();
        }

        return current;
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
