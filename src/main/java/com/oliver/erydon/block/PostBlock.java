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
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

@SuppressWarnings("deprecation")
public class PostBlock extends Block implements Waterloggable {
    public static final IntProperty LAYERS = Properties.LAYERS;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final EnumProperty<AxisMode> AXIS = EnumProperty.of("axis", AxisMode.class);

    private static final VoxelShape[][] SHAPES = new VoxelShape[AxisMode.values().length][8];

    static {
        for (int i = 1; i <= 8; i++) {
            int d = i * 2;
            int min = (16 - d) / 2;
            int max = min + d;

            SHAPES[AxisMode.VERTICAL.ordinal()][i - 1] = Block.createCuboidShape(min, 0, min, max, 16, max);
            SHAPES[AxisMode.HORIZONTAL_X.ordinal()][i - 1] = Block.createCuboidShape(0, min, min, 16, max, max);
            SHAPES[AxisMode.HORIZONTAL_Z.ordinal()][i - 1] = Block.createCuboidShape(min, min, 0, max, max, 16);
        }
    }

    public PostBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LAYERS, 1)
                .with(WATERLOGGED, false)
                .with(AXIS, AxisMode.VERTICAL));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, WATERLOGGED, AXIS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(AXIS).ordinal()][state.get(LAYERS) - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(AXIS).ordinal()][state.get(LAYERS) - 1];
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
                    .with(AXIS, existing.get(AXIS));
        }

        boolean waterlogged = ctx.getWorld().getFluidState(pos).getFluid() == Fluids.WATER;
        return this.getDefaultState()
                .with(AXIS, AxisMode.fromSide(ctx.getSide()))
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
        AxisMode axis = state.get(AXIS);
        if (axis == AxisMode.VERTICAL) {
            return state;
        }
        if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90) {
            return state.with(AXIS, axis == AxisMode.HORIZONTAL_X ? AxisMode.HORIZONTAL_Z : AxisMode.HORIZONTAL_X);
        }
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, dir, neighborState, world, pos, neighborPos);
    }

    public enum AxisMode implements StringIdentifiable {
        VERTICAL("y"),
        HORIZONTAL_X("x"),
        HORIZONTAL_Z("z");

        private final String name;

        AxisMode(String name) {
            this.name = name;
        }

        static AxisMode fromSide(Direction side) {
            return switch (side.getAxis()) {
                case X -> HORIZONTAL_X;
                case Z -> HORIZONTAL_Z;
                case Y -> VERTICAL;
            };
        }

        @Override
        public String asString() {
            return this.name;
        }
    }
}
