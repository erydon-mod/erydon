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
public class VerticalSliceBlock extends Block implements Waterloggable {
    public static final IntProperty LAYERS = Properties.LAYERS;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    private static final VoxelShape[][] SHAPES = new VoxelShape[4][8];

    static {
        for (int i = 1; i <= 8; i++) {
            int d = i * 2;
            SHAPES[idx(Direction.NORTH)][i - 1] = Block.createCuboidShape(0, 0, 0, d, 16, d);
            SHAPES[idx(Direction.EAST)][i - 1] = Block.createCuboidShape(16 - d, 0, 0, 16, 16, d);
            SHAPES[idx(Direction.SOUTH)][i - 1] = Block.createCuboidShape(16 - d, 0, 16 - d, 16, 16, 16);
            SHAPES[idx(Direction.WEST)][i - 1] = Block.createCuboidShape(0, 0, 16 - d, d, 16, 16);
        }
    }

    public VerticalSliceBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LAYERS, 1)
                .with(WATERLOGGED, false)
                .with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, WATERLOGGED, FACING);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[idx(state.get(FACING))][state.get(LAYERS) - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[idx(state.get(FACING))][state.get(LAYERS) - 1];
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
                    .with(FACING, existing.get(FACING));
        }

        boolean waterlogged = ctx.getWorld().getFluidState(pos).getFluid() == Fluids.WATER;
        Direction facing = resolveFacing(pos, ctx.getHitPos(), ctx.getSide());

        return this.getDefaultState()
                .with(FACING, facing)
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

    private static Direction resolveFacing(BlockPos pos, Vec3d hit, Direction side) {
        double fracX = hit.x - pos.getX();
        double fracZ = hit.z - pos.getZ();

        if (side == Direction.UP || side == Direction.DOWN) {
            boolean east = fracX >= 0.5D;
            boolean south = fracZ >= 0.5D;
            if (!east && !south) {
                return Direction.NORTH;
            }
            if (east && !south) {
                return Direction.EAST;
            }
            if (east) {
                return Direction.SOUTH;
            }
            return Direction.WEST;
        }

        Direction attachFace = side.getOpposite();
        return switch (attachFace) {
            case NORTH -> fracX < 0.5D ? Direction.NORTH : Direction.EAST;
            case SOUTH -> fracX < 0.5D ? Direction.WEST : Direction.SOUTH;
            case EAST -> fracZ < 0.5D ? Direction.EAST : Direction.SOUTH;
            case WEST -> fracZ < 0.5D ? Direction.NORTH : Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static int idx(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
