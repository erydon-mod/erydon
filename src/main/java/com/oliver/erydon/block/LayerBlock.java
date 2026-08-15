package com.oliver.erydon.block;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
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
import net.minecraft.world.WorldView;

import java.util.stream.IntStream;

public class LayerBlock extends Block implements Waterloggable {
    public static final IntProperty LAYERS = IntProperty.of("layers", 1, 8);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    // When true, the stack is anchored to the top of the block space (ceiling-oriented)
    public static final BooleanProperty TOP = BooleanProperty.of("top");

    private static final VoxelShape[] BOTTOM_SHAPES = IntStream.rangeClosed(1, 8)
            .mapToObj(i -> Block.createCuboidShape(0, 0, 0, 16, i * 2, 16)) // 2 px per layer from bottom up
            .toArray(VoxelShape[]::new);

    private static final VoxelShape[] TOP_SHAPES = IntStream.rangeClosed(1, 8)
            .mapToObj(i -> Block.createCuboidShape(0, 16 - (i * 2), 0, 16, 16, 16)) // 2 px per layer from top down
            .toArray(VoxelShape[]::new);

    public LayerBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LAYERS, 1)
                .with(WATERLOGGED, false)
                .with(TOP, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, WATERLOGGED, TOP);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int layers = state.get(LAYERS);
        return state.get(TOP) ? TOP_SHAPES[layers - 1] : BOTTOM_SHAPES[layers - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int layers = state.get(LAYERS);
        return state.get(TOP) ? TOP_SHAPES[layers - 1] : BOTTOM_SHAPES[layers - 1];
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        // Allow floating placement like standard blocks (no support requirement)
        return true;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbour, WorldAccess world, BlockPos pos, BlockPos neighbourPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        // Do not auto-break when support is removed; behave like floating block
        return super.getStateForNeighborUpdate(state, dir, neighbour, world, pos, neighbourPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state;
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        int layers = state.get(LAYERS);
        if (layers < 8 && context.getStack().getItem() instanceof BlockItem blockItem && blockItem.getBlock() == this) {
            Direction side = context.getSide();
            // Only allow in-place stacking when clicking top or bottom faces; side faces should place adjacent
            if (side == Direction.UP || side == Direction.DOWN) {
                return true;
            }
            return false;
        }
        return layers == 1 && super.canReplace(state, context);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState existing = ctx.getWorld().getBlockState(ctx.getBlockPos());
        if (existing.isOf(this)) {
            int layers = existing.get(LAYERS);
            // Preserve orientation and waterlogged when adding layers in-place
            return existing
                    .with(LAYERS, Math.min(8, layers + 1))
                    .with(WATERLOGGED, existing.get(WATERLOGGED))
                    .with(TOP, existing.get(TOP));
        }
        boolean water = ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER;

        Direction side = ctx.getSide();
        boolean top;
        if (side == Direction.UP) {
            // Placing on top face -> bottom-anchored stack
            top = false;
        } else if (side == Direction.DOWN) {
            // Placing on bottom face (underside) -> top-anchored stack
            top = true;
        } else {
            // Placing on a horizontal face: decide by vertical hit position within the target block space
            double localY = ctx.getHitPos().y - ctx.getBlockPos().getY();
            top = localY > 0.5d;
        }

        return this.getDefaultState().with(WATERLOGGED, water).with(TOP, top);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == this) {
            int layers = state.get(LAYERS);
            if (layers < 8 && (hit.getSide() == Direction.UP || hit.getSide() == Direction.DOWN)) {
                BlockState newState = state.with(LAYERS, layers + 1);
                world.setBlockState(pos, newState, Block.NOTIFY_ALL);
                if (!world.isClient) {
                    BlockSoundGroup soundGroup = newState.getSoundGroup();
                    world.playSound(null, pos, soundGroup.getPlaceSound(), SoundCategory.BLOCKS,
                            (soundGroup.getVolume() + 1.0F) / 2.0F,
                            soundGroup.getPitch() * 0.8F);
                }
                if (!player.getAbilities().creativeMode) stack.decrement(1);
                return ActionResult.SUCCESS;
            }
        }
        return super.onUse(state, world, pos, player, hand, hit);
    }
}
