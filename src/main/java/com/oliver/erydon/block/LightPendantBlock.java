package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.ModBlocks;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LightPendantBlock extends Block implements ClusterRebuildableBlock {
    public static final EnumProperty<LightBlock.Frame> FRAME = LightBlock.FRAME;
    public static final BooleanProperty LIT = LightBlock.LIT;
    public static final EnumProperty<Body> BODY = EnumProperty.of("body", Body.class);
    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);

    private static final ThreadLocal<Boolean> REFLOWING = ThreadLocal.withInitial(() -> false);

    private static final VoxelShape ROSE_SHAPE = Block.createCuboidShape(3.0, 14.0, 3.0, 13.0, 16.0, 13.0);
    private static final VoxelShape ROD_SHAPE = Block.createCuboidShape(7.5, 0.0, 7.5, 8.5, 16.0, 8.5);
    private static final VoxelShape SMALL_BODY_SHAPE = Block.createCuboidShape(2.0, 0.0, 2.0, 14.0, 13.0, 14.0);
    private static final VoxelShape LARGE_BODY_SHAPE = Block.createCuboidShape(0.5, 0.0, 0.5, 15.5, 13.5, 15.5);

    public LightPendantBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FRAME, LightBlock.Frame.BRONZE)
                .with(BODY, Body.SMALL)
                .with(PART, Part.SINGLE)
                .with(LIT, true));
    }

    public static int luminance(BlockState state) {
        return state.get(LIT) ? ErydonConfig.pendantLightLevel() : 0;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FRAME, BODY, PART, LIT);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState source = findPendantSource(ctx.getWorld(), ctx.getBlockPos());

        LightBlock.Frame frame = source == null ? LightBlock.Frame.BRONZE : source.get(FRAME);
        Body body = source == null ? Body.SMALL : source.get(BODY);
        boolean lit = source == null || source.get(LIT);

        BlockState placed = this.getDefaultState()
                .with(FRAME, frame)
                .with(BODY, body)
                .with(LIT, lit);

        return placed.canPlaceAt(ctx.getWorld(), ctx.getBlockPos()) ? placed : null;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient && !REFLOWING.get()) {
            reflowCluster(world, pos, state);
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !REFLOWING.get() && !newState.isOf(this)) {
            reflowCluster(world, pos.up());
            reflowCluster(world, pos.down());
            cleanupUnsupportedHalosAround(world, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient || REFLOWING.get() || !oldState.isOf(this)) {
            return;
        }

        boolean changed = state.get(FRAME) != oldState.get(FRAME)
                || state.get(BODY) != oldState.get(BODY)
                || state.get(PART) != oldState.get(PART)
                || state.get(LIT) != oldState.get(LIT);
        if (changed) {
            reflowCluster(world, pos, state);
        }
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockPos supportPos = pos.up();
        BlockState support = world.getBlockState(supportPos);
        return support.isOf(this) || support.isSideSolidFullSquare(world, supportPos, Direction.DOWN);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canPlaceAt(world, pos)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        return state;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        if (held.isOf(Items.DEBUG_STICK)) {
            if (!player.isSneaking()
                    && state.get(BODY) == Body.SMALL
                    && isDebugBodySelected(held, state)
                    && !hasRoomForLargeBody(world, findClusterBottom(world, pos))) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Pendant needs empty space around the lamp body before it can become large."), false);
                }
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        if (!hit.getSide().getAxis().isHorizontal()) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        reflowCluster(world, pos, state.with(LIT, !state.get(LIT)));
        return ActionResult.SUCCESS;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape body = state.get(BODY) == Body.LARGE ? LARGE_BODY_SHAPE : SMALL_BODY_SHAPE;
        return switch (state.get(PART)) {
            case SINGLE -> VoxelShapes.combine(ROSE_SHAPE, body, BooleanBiFunction.OR);
            case TOP -> VoxelShapes.combine(ROSE_SHAPE, ROD_SHAPE, BooleanBiFunction.OR);
            case ROD -> ROD_SHAPE;
            case BOTTOM -> body;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.fullCube();
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState anchor = world.getBlockState(seed);
        if (!anchor.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        BlockPos bottom = findClusterBottom(world, seed);
        BlockPos top = findClusterTop(world, seed);
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            if (ClusterRecalcSafety.isActive()
                    && positions.size() >= ClusterRecalcSafety.MAX_PENDANT_BLOCKS) {
                ClusterRecalcSafety.markTooLarge();
                break;
            }
            BlockPos pos = new BlockPos(seed.getX(), y, seed.getZ());
            if (!ClusterRecalcSafety.claim(pos)) {
                break;
            }
            positions.add(pos);
        }

        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(positions);
        if (unsafe != null) {
            return unsafe;
        }

        reflowCluster(world, seed, anchor);
        return new ClusterRecalcResult(positions, true);
    }

    private void reflowCluster(World world, BlockPos seed) {
        BlockState anchor = world.getBlockState(seed);
        if (anchor.isOf(this)) {
            reflowCluster(world, seed, anchor);
        }
    }

    private void reflowCluster(World world, BlockPos seed, BlockState source) {
        if (REFLOWING.get() || !source.isOf(this)) {
            return;
        }

        BlockPos bottom = findClusterBottom(world, seed);
        BlockPos top = findClusterTop(world, seed);
        int height = top.getY() - bottom.getY() + 1;

        REFLOWING.set(true);
        try {
            for (int y = bottom.getY(); y <= top.getY(); y++) {
                BlockPos currentPos = new BlockPos(seed.getX(), y, seed.getZ());
                BlockState currentState = world.getBlockState(currentPos);
                if (!currentState.isOf(this)) {
                    continue;
                }

                int indexFromBottom = y - bottom.getY();
                BlockState updated = currentState
                        .with(FRAME, source.get(FRAME))
                        .with(BODY, source.get(BODY))
                        .with(LIT, source.get(LIT))
                        .with(PART, resolvePart(indexFromBottom, height));

                if (updated != currentState) {
                    world.setBlockState(currentPos, updated,
                            ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                }
            }
        } finally {
            REFLOWING.set(false);
        }

        syncHalosForCluster(world, bottom, top);
    }

    private BlockPos findClusterBottom(WorldAccess world, BlockPos seed) {
        BlockPos current = seed;
        while (world.getBlockState(current.down()).isOf(this)) {
            current = current.down();
        }
        return current;
    }

    private BlockPos findClusterTop(WorldAccess world, BlockPos seed) {
        BlockPos current = seed;
        while (world.getBlockState(current.up()).isOf(this)) {
            current = current.up();
        }
        return current;
    }

    @Nullable
    private static BlockState findPendantSource(WorldAccess world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        if (above.getBlock() instanceof LightPendantBlock) {
            return above;
        }

        BlockState below = world.getBlockState(pos.down());
        if (below.getBlock() instanceof LightPendantBlock) {
            return below;
        }

        return null;
    }

    private static Part resolvePart(int indexFromBottom, int height) {
        if (height <= 1) {
            return Part.SINGLE;
        }
        if (indexFromBottom == 0) {
            return Part.BOTTOM;
        }
        if (indexFromBottom == height - 1) {
            return Part.TOP;
        }
        return Part.ROD;
    }

    private void syncHalosForCluster(World world, BlockPos bottom, BlockPos top) {
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos currentPos = new BlockPos(bottom.getX(), y, bottom.getZ());
            BlockState currentState = world.getBlockState(currentPos);
            if (currentState.isOf(this)) {
                syncHaloForBlock(world, currentPos, currentState);
            }
        }
    }

    private void syncHaloForBlock(World world, BlockPos center, BlockState state) {
        boolean shouldHaveHalo = shouldHaveHalo(state);

        for (BlockPos haloPos : haloPositions(center)) {
            if (shouldHaveHalo) {
                BlockState existing = world.getBlockState(haloPos);
                if (existing.isOf(ModBlocks.LIGHT_PENDANT_HALO)) {
                    continue;
                }
                if (existing.isAir() || existing.isReplaceable()) {
                    world.setBlockState(haloPos, ModBlocks.LIGHT_PENDANT_HALO.getDefaultState(),
                            ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                }
            } else {
                removeUnsupportedHalo(world, haloPos);
            }
        }
    }

    private static void cleanupUnsupportedHalosAround(World world, BlockPos center) {
        for (BlockPos haloPos : haloPositions(center)) {
            removeUnsupportedHalo(world, haloPos);
        }
    }

    private static void removeUnsupportedHalo(World world, BlockPos haloPos) {
        BlockState state = world.getBlockState(haloPos);
        if (state.isOf(ModBlocks.LIGHT_PENDANT_HALO) && !isSupportedHalo(world, haloPos)) {
            world.setBlockState(haloPos, net.minecraft.block.Blocks.AIR.getDefaultState(),
                    ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
        }
    }

    private static boolean isSupportedHalo(WorldAccess world, BlockPos haloPos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos center = haloPos.add(dx, 0, dz);
                BlockState centerState = world.getBlockState(center);
                if (centerState.getBlock() instanceof LightPendantBlock && shouldHaveHalo(centerState)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRoomForLargeBody(WorldAccess world, BlockPos bodyPos) {
        for (BlockPos haloPos : haloPositions(bodyPos)) {
            BlockState existing = world.getBlockState(haloPos);
            if (!existing.isAir() && !existing.isReplaceable() && !existing.isOf(ModBlocks.LIGHT_PENDANT_HALO)) {
                return false;
            }
        }
        return true;
    }

    private static List<BlockPos> haloPositions(BlockPos center) {
        List<BlockPos> positions = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                positions.add(center.add(dx, 0, dz));
            }
        }
        return positions;
    }

    private static boolean shouldHaveHalo(BlockState state) {
        if (!state.get(LIT) || state.get(BODY) != Body.LARGE) {
            return false;
        }

        Part part = state.get(PART);
        return part == Part.SINGLE || part == Part.BOTTOM;
    }

    private static boolean isDebugBodySelected(ItemStack stack, BlockState state) {
        NbtCompound debugProperties = stack.getSubNbt("DebugProperty");
        if (debugProperties == null) {
            return false;
        }

        String selectedProperty = debugProperties.getString(state.getBlock().getTranslationKey());
        return BODY.getName().equals(selectedProperty);
    }

    public enum Body implements StringIdentifiable {
        SMALL("small"),
        LARGE("large");

        private final String id;

        Body(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public enum Part implements StringIdentifiable {
        SINGLE("single"),
        TOP("top"),
        ROD("rod"),
        BOTTOM("bottom");

        private final String id;

        Part(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }
}
