package com.oliver.erydon.block;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

public class LayerVerticalTranslucentBlock extends Block implements Waterloggable {
    public static final IntProperty LAYERS = Properties.LAYERS;               // 1..8
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    // SHAPES[FacingIndex][layers-1]
    private static final VoxelShape[][] SHAPES = new VoxelShape[4][8];

    static {
        for (int i = 1; i <= 8; i++) {
            int d = i * 2; // thickness in px (2..16)
            // NORTH: z 0..d
            SHAPES[idx(Direction.NORTH)][i-1] = Block.createCuboidShape(0, 0, 0, 16, 16, d);
            // SOUTH: z 16-d..16
            SHAPES[idx(Direction.SOUTH)][i-1] = Block.createCuboidShape(0, 0, 16 - d, 16, 16, 16);
            // WEST:  x 0..d
            SHAPES[idx(Direction.WEST)][i-1]  = Block.createCuboidShape(0, 0, 0, d, 16, 16);
            // EAST:  x 16-d..16
            SHAPES[idx(Direction.EAST)][i-1]  = Block.createCuboidShape(16 - d, 0, 0, 16, 16, 16);
        }
    }

    private static int idx(Direction d) {
        return switch (d) {
            case NORTH -> 0;
            case SOUTH -> 1;
            case WEST  -> 2;
            case EAST  -> 3;
            default    -> 0;
        };
    }

    public LayerVerticalTranslucentBlock(Settings settings) {
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
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        ItemStack stack = ctx.getStack();
        if (!stack.isOf(this.asItem())) return false;
        if (state.get(LAYERS) >= 8) return false;

        Direction side = ctx.getSide();
        // We place with FACING = opposite(clicked side) for horizontal faces.
        if (side.getAxis().isHorizontal()) {
            return state.get(FACING) == side.getOpposite();
        } else {
            // Clicking top/bottom: qualify stacking using player's horizontal facing.
            Direction playerFacing = (ctx.getPlayer() != null) ? ctx.getPlayer().getHorizontalFacing() : Direction.NORTH;
            return state.get(FACING) == playerFacing;
        }
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        FluidState fluid = ctx.getWorld().getFluidState(pos);
        BlockState existing = ctx.getWorld().getBlockState(pos);

        // stacking onto same block
        if (existing.getBlock() == this) {
            int layers = Math.min(8, existing.get(LAYERS) + 1);
            return existing.with(LAYERS, layers).with(WATERLOGGED, false);
        }

        Direction side = ctx.getSide();
        // Paste onto the opposite face from the player when clicking a side.
        Direction facing = side.getAxis().isHorizontal()
                ? side.getOpposite()
                : (ctx.getPlayer() != null ? ctx.getPlayer().getHorizontalFacing() : Direction.NORTH);

        return this.getDefaultState()
                .with(FACING, facing)
                .with(LAYERS, 1)
                .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER);
    }


    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        if (!held.isOf(this.asItem())) return ActionResult.PASS;
        if (player.isSneaking() || hit.getSide().getAxis() != Direction.Axis.Y) return ActionResult.PASS;

        int current = state.get(LAYERS);
        if (current >= 8) return ActionResult.PASS;

        BlockState newState = state.with(LAYERS, current + 1);
        world.setBlockState(pos, newState, Block.NOTIFY_ALL);
        if (!world.isClient) {
            BlockSoundGroup soundGroup = newState.getSoundGroup();
            world.playSound(null, pos, soundGroup.getPlaceSound(), SoundCategory.BLOCKS,
                    (soundGroup.getVolume() + 1.0F) / 2.0F,
                    soundGroup.getPitch() * 0.8F);
        }
        if (!player.isCreative()) held.decrement(1);
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

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
        if (stateFrom.getBlock() instanceof LayerVerticalTranslucentBlock) {
            Direction facing = state.get(FACING);
            Direction facingFrom = stateFrom.get(FACING);

            if (facing == facingFrom) {
                return direction.getAxis() != facing.getAxis();
            }
        }
        return super.isSideInvisible(state, stateFrom, direction);
    }
}
