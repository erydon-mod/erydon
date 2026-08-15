package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

@SuppressWarnings("deprecation")
public class HorizontalSliceBlock extends Block implements Waterloggable {
    public static final IntProperty LAYERS = Properties.LAYERS;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty TOP = BooleanProperty.of("top");

    private static final VoxelShape[][][] SHAPES = new VoxelShape[4][2][8];

    static {
        for (int i = 1; i <= 8; i++) {
            int d = i * 2;

            SHAPES[idx(Direction.NORTH)][0][i - 1] = Block.createCuboidShape(0, 0, 0, 16, d, d);
            SHAPES[idx(Direction.NORTH)][1][i - 1] = Block.createCuboidShape(0, 16 - d, 0, 16, 16, d);

            SHAPES[idx(Direction.SOUTH)][0][i - 1] = Block.createCuboidShape(0, 0, 16 - d, 16, d, 16);
            SHAPES[idx(Direction.SOUTH)][1][i - 1] = Block.createCuboidShape(0, 16 - d, 16 - d, 16, 16, 16);

            SHAPES[idx(Direction.EAST)][0][i - 1] = Block.createCuboidShape(16 - d, 0, 0, 16, d, 16);
            SHAPES[idx(Direction.EAST)][1][i - 1] = Block.createCuboidShape(16 - d, 16 - d, 0, 16, 16, 16);

            SHAPES[idx(Direction.WEST)][0][i - 1] = Block.createCuboidShape(0, 0, 0, d, d, 16);
            SHAPES[idx(Direction.WEST)][1][i - 1] = Block.createCuboidShape(0, 16 - d, 0, d, 16, 16);
        }
    }

    public HorizontalSliceBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LAYERS, 1)
                .with(WATERLOGGED, false)
                .with(FACING, Direction.NORTH)
                .with(TOP, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, WATERLOGGED, FACING, TOP);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[idx(state.get(FACING))][state.get(TOP) ? 1 : 0][state.get(LAYERS) - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[idx(state.get(FACING))][state.get(TOP) ? 1 : 0][state.get(LAYERS) - 1];
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (ctx.getStack().isOf(this.asItem()) && state.get(LAYERS) < 8) {
            return true;
        }
        return state.get(LAYERS) == 1 && super.canReplace(state, ctx);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        BlockState existing = ctx.getWorld().getBlockState(pos);
        if (existing.isOf(this)) {
            return existing
                    .with(LAYERS, Math.min(8, existing.get(LAYERS) + 1))
                    .with(WATERLOGGED, existing.get(WATERLOGGED))
                    .with(FACING, existing.get(FACING))
                    .with(TOP, existing.get(TOP));
        }

        boolean waterlogged = ctx.getWorld().getFluidState(pos).getFluid() == Fluids.WATER;
        Placement placement = resolvePlacement(pos, ctx.getHitPos(), ctx.getSide());

        return this.getDefaultState()
                .with(FACING, placement.facing())
                .with(TOP, placement.top())
                .with(WATERLOGGED, waterlogged);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        if (!held.isOf(this.asItem()) || state.get(LAYERS) >= 8) {
            return ActionResult.PASS;
        }
        if (player.isSneaking() || hit.getSide().getAxis() != Direction.Axis.Y) {
            return ActionResult.PASS;
        }

        BlockState newState = state.with(LAYERS, state.get(LAYERS) + 1);
        world.setBlockState(pos, newState, Block.NOTIFY_ALL);
        if (!world.isClient) {
            BlockSoundGroup soundGroup = newState.getSoundGroup();
            world.playSound(player, pos, soundGroup.getPlaceSound(), SoundCategory.BLOCKS,
                    (soundGroup.getVolume() + 1.0F) / 2.0F,
                    soundGroup.getPitch() * 0.8F);
        }
        if (!player.isCreative()) {
            held.decrement(1);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
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
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, dir, neighborState, world, pos, neighborPos);
    }

    private static Placement resolvePlacement(BlockPos pos, Vec3d hit, Direction side) {
        double fracX = hit.x - pos.getX();
        double fracY = hit.y - pos.getY();
        double fracZ = hit.z - pos.getZ();

        if (side == Direction.UP) {
            return new Placement(nearestEdge(fracX, fracZ), false);
        }
        if (side == Direction.DOWN) {
            return new Placement(nearestEdge(fracX, fracZ), true);
        }

        return new Placement(side.getOpposite(), fracY > 0.5D);
    }

    private static Direction nearestEdge(double fracX, double fracZ) {
        double north = fracZ;
        double south = 1.0D - fracZ;
        double west = fracX;
        double east = 1.0D - fracX;

        double min = north;
        Direction edge = Direction.NORTH;
        if (south < min) {
            min = south;
            edge = Direction.SOUTH;
        }
        if (west < min) {
            min = west;
            edge = Direction.WEST;
        }
        if (east < min) {
            edge = Direction.EAST;
        }
        return edge;
    }

    private static int idx(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private record Placement(Direction facing, boolean top) {
    }
}
