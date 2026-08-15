package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
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
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.*;

/**
 * Romanesque arch block that auto-builds multipart components based on adjacent blocks
 * in its vertical plane. Width is segmented into independent clusters of max 3 blocks.
 *
 * Supports two auto-layout sets:
 *  - BASE: original "side_*" layouts
 *  - COLUMN: right-click (empty hand) forces 2- and 3-wide clusters to use side_column + upper + plinth.
 *  - WIDTH: debug stick width property chooses 1-, 2-, or 3-wide segmentation.
 *
 * Void cells (centre of width-3 clusters below the keystone) remain selectable (outline full cube),
 * but have no collision so players can walk through.
 */
public class ArchRomanesqueBlock extends HorizontalFacingBlock implements Waterloggable, ClusterRebuildableBlock {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Arrangement> ARRANGEMENT = EnumProperty.of("arr", Arrangement.class);
    public static final IntProperty WIDTH = IntProperty.of("width", 1, 3);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    private static final String WIDTH_OVERRIDE_SCOPE = ClusterManualLockState.ROMANESQUE_ARCH_SCOPE + "_width";
    private static final int DEFAULT_WIDTH = 3;

    private static final ThreadLocal<Boolean> SYNCING_CLUSTER_STATE = ThreadLocal.withInitial(() -> false);

    private static boolean beginClusterStateSync() {
        if (Boolean.TRUE.equals(SYNCING_CLUSTER_STATE.get())) {
            return false;
        }
        SYNCING_CLUSTER_STATE.set(true);
        return true;
    }

    private static void endClusterStateSync(boolean started) {
        if (started) {
            SYNCING_CLUSTER_STATE.set(false);
        }
    }

    private static boolean isClusterStateSyncing() {
        return Boolean.TRUE.equals(SYNCING_CLUSTER_STATE.get());
    }

    // --- Shapes (authored in NORTH-facing space) ------------------------------

    private static final VoxelShape SHAPE_EMPTY = VoxelShapes.empty();

    private static final VoxelShape SHAPE_CORNER_SMALL = makeCornerSmallShape();
    private static final VoxelShape SHAPE_CORNER_MEDIUM_LH = makeCornerMediumLHShape();
    private static final VoxelShape SHAPE_CORNER_MEDIUM_RH = makeCornerMediumRHShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_UPPER_LH = makeCornerLargeUpperLHShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_UPPER_RH = makeCornerLargeUpperRHShape();
    // Per brief: corner_large_lower has no strike (covered by other models).
    private static final VoxelShape SHAPE_CORNER_LARGE_LOWER = SHAPE_EMPTY;

    private static final VoxelShape SHAPE_SIDE_SMALL_LH = makeSideSmallLHShape();
    private static final VoxelShape SHAPE_SIDE_SMALL_RH = makeSideSmallRHShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM_LH = makeSideMediumLHShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM_RH = makeSideMediumRHShape();
    private static final VoxelShape SHAPE_SIDE_LARGE_LH = makeSideLargeLHShape();
    private static final VoxelShape SHAPE_SIDE_LARGE_RH = makeSideLargeRHShape();

    private static final VoxelShape SHAPE_SIDE_MEDIUM_UPPER_LH = makeSideMediumUpperLHShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM_UPPER_RH = makeSideMediumUpperRHShape();
    private static final VoxelShape SHAPE_SIDE_LARGE_UPPER_LH = makeSideLargeUpperLHShape();
    private static final VoxelShape SHAPE_SIDE_LARGE_UPPER_RH = makeSideLargeUpperRHShape();

    private static final VoxelShape SHAPE_SIDE_COLUMN_LH = makeSideColumnLHShape();
    private static final VoxelShape SHAPE_SIDE_COLUMN_RH = makeSideColumnRHShape();
    private static final VoxelShape SHAPE_PLINTH_LH = makePlinthLHShape();
    private static final VoxelShape SHAPE_PLINTH_RH = makePlinthRHShape();

    private static final VoxelShape SHAPE_TOP_LARGE = makeTopLargeShape();

    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[Arrangement.values().length * 4];

    // --- Enums / properties --------------------------------------------------

    public enum StyleSet implements StringIdentifiable {
        BASE("base"),
        COLUMN("column");

        private final String id;
        StyleSet(String id) { this.id = id; }
        @Override public String asString() { return id; }

        public StyleSet toggle() {
            return this == BASE ? COLUMN : BASE;
        }
    }

    public enum Corner implements StringIdentifiable {
        NONE("none"),
        SMALL("small"),
        MEDIUM("medium"),
        LARGE_UPPER("large_upper"),
        LARGE_LOWER("large_lower");

        private final String id;
        Corner(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    public enum Side implements StringIdentifiable {
        NONE("none"),
        SMALL("small"),
        MEDIUM("medium"),
        LARGE("large");

        private final String id;
        Side(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    public enum Upper implements StringIdentifiable {
        NONE("none"),
        MEDIUM("medium"),
        LARGE("large");

        private final String id;
        Upper(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    public enum Arrangement implements StringIdentifiable {
        // 1-wide cluster
        SMALL_TOP("small_top", Corner.SMALL, false, Side.SMALL, Side.SMALL, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        SMALL_BODY("small_body", Corner.NONE, false, Side.SMALL, Side.SMALL, Upper.NONE, Upper.NONE, false, false, false, false, false, false),

        // 2-wide cluster (base)
        DOUBLE_TOP_L("double_top_l", Corner.MEDIUM, false, Side.MEDIUM, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        DOUBLE_TOP_R("double_top_r", Corner.MEDIUM, true,  Side.NONE, Side.MEDIUM, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        // Style marker variants for width-2 top-only column selection (same geometry as base top).
        DOUBLE_TOP_L_COLUMN("double_top_l_column", Corner.MEDIUM, false, Side.MEDIUM, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        DOUBLE_TOP_R_COLUMN("double_top_r_column", Corner.MEDIUM, true,  Side.NONE, Side.MEDIUM, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        DOUBLE_BODY_L("double_body_l", Corner.NONE, false, Side.MEDIUM, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        DOUBLE_BODY_R("double_body_r", Corner.NONE, false, Side.NONE, Side.MEDIUM, Upper.NONE, Upper.NONE, false, false, false, false, false, false),

        // 2-wide cluster (column set)
        DOUBLE_COLUMN_UPPER_L("double_column_upper_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.MEDIUM, Upper.NONE, true, false, false, false, false, false),
        DOUBLE_COLUMN_UPPER_R("double_column_upper_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.MEDIUM, false, true, false, false, false, false),
        DOUBLE_COLUMN_SHAFT_L("double_column_shaft_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, true, false, false, false, false, false),
        DOUBLE_COLUMN_SHAFT_R("double_column_shaft_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, true, false, false, false, false),
        DOUBLE_COLUMN_BASE_L("double_column_base_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, true, false, true, false, false, false),
        DOUBLE_COLUMN_BASE_R("double_column_base_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, true, false, true, false, false),
        DOUBLE_COLUMN_UPPER_BASE_L("double_column_upper_base_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.MEDIUM, Upper.NONE, true, false, true, false, false, false),
        DOUBLE_COLUMN_UPPER_BASE_R("double_column_upper_base_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.MEDIUM, false, true, false, true, false, false),

        // 3-wide cluster (height == 1): top corners are MEDIUM, middle uses top_large
        TRIPLE_SINGLE_L("triple_single_l", Corner.MEDIUM, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TOP_LARGE("top_large", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, true, false),
        TRIPLE_SINGLE_R("triple_single_r", Corner.MEDIUM, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        // Style marker variants for width-3 height-1 column selection.
        TRIPLE_SINGLE_L_COLUMN("triple_single_l_column", Corner.MEDIUM, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TOP_LARGE_COLUMN("top_large_column", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, true, false),
        TRIPLE_SINGLE_R_COLUMN("triple_single_r_column", Corner.MEDIUM, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),

        // 3-wide cluster (height >= 2): top row uses LARGE_UPPER corners, row2 uses LARGE_LOWER corners, middle is void
        TRIPLE_TOP_L("triple_top_l", Corner.LARGE_UPPER, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_TOP_R("triple_top_r", Corner.LARGE_UPPER, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_ROW2_L("triple_row2_l", Corner.LARGE_LOWER, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_VOID("triple_void", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, true),
        TRIPLE_ROW2_R("triple_row2_r", Corner.LARGE_LOWER, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        // Style marker variants for width-3 height-2 column selection.
        TRIPLE_TOP_L_COLUMN("triple_top_l_column", Corner.LARGE_UPPER, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_TOP_R_COLUMN("triple_top_r_column", Corner.LARGE_UPPER, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_ROW2_L_COLUMN("triple_row2_l_column", Corner.LARGE_LOWER, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_VOID_COLUMN("triple_void_column", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, true),
        TRIPLE_ROW2_R_COLUMN("triple_row2_r_column", Corner.LARGE_LOWER, true,  Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),

        // 3-wide cluster body (base set)
        TRIPLE_BODY_L("triple_body_l", Corner.NONE, false, Side.LARGE, Side.NONE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),
        TRIPLE_BODY_R("triple_body_r", Corner.NONE, false, Side.NONE, Side.LARGE, Upper.NONE, Upper.NONE, false, false, false, false, false, false),

        // 3-wide cluster body (column set; begins from row3 (y==2))
        TRIPLE_COLUMN_UPPER_L("triple_column_upper_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.LARGE, Upper.NONE, true, false, false, false, false, false),
        TRIPLE_COLUMN_UPPER_R("triple_column_upper_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.LARGE, false, true, false, false, false, false),
        TRIPLE_COLUMN_SHAFT_L("triple_column_shaft_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, true, false, false, false, false, false),
        TRIPLE_COLUMN_SHAFT_R("triple_column_shaft_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, true, false, false, false, false),
        TRIPLE_COLUMN_BASE_L("triple_column_base_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, true, false, true, false, false, false),
        TRIPLE_COLUMN_BASE_R("triple_column_base_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.NONE, false, true, false, true, false, false),
        TRIPLE_COLUMN_UPPER_BASE_L("triple_column_upper_base_l", Corner.NONE, false, Side.NONE, Side.NONE, Upper.LARGE, Upper.NONE, true, false, true, false, false, false),
        TRIPLE_COLUMN_UPPER_BASE_R("triple_column_upper_base_r", Corner.NONE, false, Side.NONE, Side.NONE, Upper.NONE, Upper.LARGE, false, true, false, true, false, false);

        private final String id;
        private final Corner corner;
        private final boolean cornerFlip;
        private final Side sideL;
        private final Side sideR;
        private final Upper upperL;
        private final Upper upperR;
        private final boolean columnL;
        private final boolean columnR;
        private final boolean plinthL;
        private final boolean plinthR;
        private final boolean topLarge;
        private final boolean isVoid;

        Arrangement(String id,
                    Corner corner,
                    boolean cornerFlip,
                    Side sideL,
                    Side sideR,
                    Upper upperL,
                    Upper upperR,
                    boolean columnL,
                    boolean columnR,
                    boolean plinthL,
                    boolean plinthR,
                    boolean topLarge,
                    boolean isVoid) {
            this.id = id;
            this.corner = corner;
            this.cornerFlip = cornerFlip;
            this.sideL = sideL;
            this.sideR = sideR;
            this.upperL = upperL;
            this.upperR = upperR;
            this.columnL = columnL;
            this.columnR = columnR;
            this.plinthL = plinthL;
            this.plinthR = plinthR;
            this.topLarge = topLarge;
            this.isVoid = isVoid;
        }

        @Override public String asString() { return id; }

        public Corner corner() { return corner; }
        public boolean cornerFlip() { return cornerFlip; }
        public Side sideL() { return sideL; }
        public Side sideR() { return sideR; }
        public Upper upperL() { return upperL; }
        public Upper upperR() { return upperR; }
        public boolean columnL() { return columnL; }
        public boolean columnR() { return columnR; }
        public boolean plinthL() { return plinthL; }
        public boolean plinthR() { return plinthR; }
        public boolean hasTopLarge() { return topLarge; }
        public boolean isVoid() { return isVoid; }
    }

    public ArchRomanesqueBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(ARRANGEMENT, Arrangement.SMALL_TOP)
                .with(WIDTH, DEFAULT_WIDTH)
                .with(WATERLOGGED, false)
        );
    }

    protected boolean supportsColumnStyle() {
        return true;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, ARRANGEMENT, WIDTH, WATERLOGGED);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        Arrangement arrangement = state.get(ARRANGEMENT);

        if (mirror != BlockMirror.NONE) {
            arrangement = mirrorArrangement(arrangement);
        }

        return rotate(state, mirror.getRotation(facing)).with(ARRANGEMENT, arrangement);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing();
        int width = DEFAULT_WIDTH;

        // If we're attaching to an existing arch cluster, inherit its facing.
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN }) {
            BlockState neighbour = world.getBlockState(pos.offset(dir));
            if (isArchBlock(neighbour)) {
                facing = neighbour.get(FACING);
                width = neighbour.get(WIDTH);
                break;
            }
        }

        FluidState fluid = world.getFluidState(pos);

        // Default to a 1x1 top cell; component reflow will override as needed.
        return this.getDefaultState()
                .with(FACING, facing)
                .with(ARRANGEMENT, Arrangement.SMALL_TOP)
                .with(WIDTH, width)
                .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient) {
            clearWidthOverride(world, pos);
            handleClusterChangeOnPlaced(world, pos);
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);

        if (!world.isClient && !state.isOf(newState.getBlock())) {
            clearWidthOverride(world, pos);
            handleClusterChangeOnRemoved(world, pos);
        }
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient || !oldState.isOf(this) || isClusterStateSyncing()) {
            return;
        }

        boolean widthChanged = state.get(WIDTH) != oldState.get(WIDTH);
        boolean waterloggedChanged = state.get(WATERLOGGED) != oldState.get(WATERLOGGED);
        boolean arrangementChanged = state.get(ARRANGEMENT) != oldState.get(ARRANGEMENT);

        if (!widthChanged && !waterloggedChanged && !arrangementChanged) {
            return;
        }

        boolean started = beginClusterStateSync();
        try {
            if (widthChanged) {
                applyWidthToCluster(world, pos, state.get(WIDTH));
            }
            if (waterloggedChanged) {
                applyWaterloggedToSubmergedCluster(world, pos);
            }
            if (arrangementChanged && !widthChanged) {
                ClusterInfo cluster = discoverCluster(world, pos);
                if (cluster != null && !cluster.blocks.isEmpty()) {
                    reflowCluster(world, cluster);
                }
            }
        } finally {
            endClusterStateSync(started);
        }
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        // Allow placing into void cells without needing to break first.
        if (isVoidState(state)) {
            return true;
        }
        return super.canReplace(state, context);
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

    // --- Use ---------------------------------------------------------------

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);

        // Let the vanilla debug stick expose width and waterlogged as normal properties.
        if (isDebugStick(held)) {
            return ActionResult.PASS;
        }

        // Sneak right click should stay free for placing blocks against the arch.
        if (player.isSneaking()) {
            return ActionResult.PASS;
        }

        // Normal right click: toggle the circular column set on the whole connected component.
        if (hand == Hand.MAIN_HAND) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            toggleStyleForComponent(world, pos);
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    private boolean isDebugStick(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // Vanilla debug stick (creative-only).
        return stack.isOf(Items.DEBUG_STICK);
    }

    private void toggleStyleForComponent(World world, BlockPos pos) {
        ClusterInfo cluster = discoverCluster(world, pos);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return;
        }

        StyleSet newStyle = inferComponentStyle(world, cluster).toggle();

        // Reflow with an explicit style so every segment changes consistently.
        reflowCluster(world, cluster, newStyle);
    }

    private void applyWidthToCluster(World world, BlockPos pos, int width) {
        ClusterInfo cluster = discoverCluster(world, pos);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return;
        }

        int clusterWidth = clampClusterWidth(width);
        clearWidthOverride(cluster.blocks, world);

        for (BlockPos clusterPos : cluster.blocks) {
            BlockState clusterState = world.getBlockState(clusterPos);
            if (isArchBlock(clusterState) && clusterState.get(WIDTH) != clusterWidth) {
                world.setBlockState(clusterPos, clusterState.with(WIDTH, clusterWidth), Block.NOTIFY_LISTENERS);
            }
        }

        reflowCluster(world, cluster, null, clusterWidth);
    }

    private void applyWaterloggedToSubmergedCluster(World world, BlockPos pos) {
        ClusterInfo cluster = discoverCluster(world, pos);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return;
        }

        for (BlockPos clusterPos : cluster.blocks) {
            BlockState clusterState = world.getBlockState(clusterPos);
            if (!isArchBlock(clusterState)) {
                continue;
            }

            boolean waterlogged = world.getFluidState(clusterPos).getFluid() == Fluids.WATER;
            if (clusterState.get(WATERLOGGED) != waterlogged) {
                world.setBlockState(clusterPos, clusterState.with(WATERLOGGED, waterlogged), Block.NOTIFY_LISTENERS);
            }
            if (waterlogged) {
                world.scheduleFluidTick(clusterPos, Fluids.WATER, Fluids.WATER.getTickRate(world));
            }
        }
    }

    // --- Cluster / reflow ----------------------------------------------------

    private boolean isArchBlock(BlockState state) {
        return state.getBlock() == this;
    }

    private Direction[] getPlaneAdjacencyDirs(Direction facing) {
        // Vertical plane neighbours: UP/DOWN + left/right relative to facing
        Direction left  = facing.rotateYClockwise();
        Direction right = facing.rotateYCounterclockwise();
        return new Direction[] { Direction.UP, Direction.DOWN, left, right };
    }

    private static final class ClusterInfo {
        public final Set<BlockPos> blocks = new HashSet<>();
        public final Direction facing;

        public record LocalPos(int x, int y) {}
        public final Map<BlockPos, LocalPos> localCoords = new HashMap<>();

        public int minLocalX = Integer.MAX_VALUE;
        public int maxLocalX = Integer.MIN_VALUE;
        public int minLocalY = Integer.MAX_VALUE;
        public int maxLocalY = Integer.MIN_VALUE;

        public ClusterInfo(Direction facing) {
            this.facing = facing;
        }

        public void include(BlockPos pos, int localX, int localY) {
            blocks.add(pos);
            localCoords.put(pos, new LocalPos(localX, localY));

            if (localX < minLocalX) minLocalX = localX;
            if (localX > maxLocalX) maxLocalX = localX;
            if (localY < minLocalY) minLocalY = localY;
            if (localY > maxLocalY) maxLocalY = localY;
        }
    }

    private record SegmentSpan(int minX, int maxX) {
        private boolean contains(int x) {
            return x >= minX && x <= maxX;
        }
    }

    private ClusterInfo discoverCluster(World world, BlockPos seed) {
        BlockState seedState = ClusterRecalcSafety.getBlockState(world, seed);
        if (!isArchBlock(seedState)) {
            return null;
        }

        Direction facing = seedState.get(FACING);
        ClusterInfo cluster = new ClusterInfo(facing);

        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        q.add(seed);
        visited.add(seed);

        while (!q.isEmpty()) {
            BlockPos p = q.removeFirst();
            BlockState st = ClusterRecalcSafety.getBlockState(world, p);
            if (!isArchBlock(st) || st.get(FACING) != facing) {
                continue;
            }

            if (!ClusterRecalcSafety.claim(p)) {
                break;
            }
            cluster.blocks.add(p);

            for (Direction dir : getPlaneAdjacencyDirs(facing)) {
                BlockPos n = p.offset(dir);
                if (visited.add(n)) {
                    q.add(n);
                }
            }
        }

        return cluster;
    }

    private void computeLocalCoordinates(ClusterInfo cluster) {
        // Recompute localCoords + bounds from scratch.
        List<BlockPos> positions = new ArrayList<>(cluster.blocks);

        cluster.blocks.clear();
        cluster.localCoords.clear();
        cluster.minLocalX = Integer.MAX_VALUE;
        cluster.maxLocalX = Integer.MIN_VALUE;
        cluster.minLocalY = Integer.MAX_VALUE;
        cluster.maxLocalY = Integer.MIN_VALUE;

        if (positions.isEmpty()) {
            return;
        }

        // +X in grid space is "to the right" as defined by our plane convention.
        Direction rightDir = cluster.facing.rotateYClockwise();
        int dx = rightDir.getOffsetX();
        int dz = rightDir.getOffsetZ();

        // Determine origin (top-left) in projected coordinates.
        int topY = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE;

        // Enforce planar (1 block thick) along the facing axis.
        Direction forwardDir = cluster.facing;
        int fx = forwardDir.getOffsetX();
        int fz = forwardDir.getOffsetZ();
        Integer depth = null;

        for (BlockPos p : positions) {
            topY = Math.max(topY, p.getY());

            int col = p.getX() * dx + p.getZ() * dz;
            minCol = Math.min(minCol, col);

            int d = p.getX() * fx + p.getZ() * fz;
            if (depth == null) {
                depth = d;
            } else if (!depth.equals(d)) {
                // Non-planar: keep blocks but skip local coords so we don't reflow.
                cluster.blocks.addAll(positions);
                cluster.localCoords.clear();
                return;
            }
        }

        for (BlockPos p : positions) {
            int col = p.getX() * dx + p.getZ() * dz;
            int localX = col - minCol;
            int localY = topY - p.getY();
            cluster.include(p, localX, localY);
        }
    }

    private void handleClusterChangeOnPlaced(World world, BlockPos pos) {
        ClusterInfo cluster = discoverCluster(world, pos);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return;
        }
        reflowCluster(world, cluster);
    }

    private void handleClusterChangeOnRemoved(World world, BlockPos removedPos) {
        // Removal can split a component. Reflow each neighbouring component.
        Set<BlockPos> processed = new HashSet<>();

        for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN }) {
            BlockPos neighbourPos = removedPos.offset(dir);
            if (processed.contains(neighbourPos)) {
                continue;
            }

            BlockState neighbourState = world.getBlockState(neighbourPos);
            if (!isArchBlock(neighbourState)) {
                continue;
            }

            ClusterInfo cluster = discoverCluster(world, neighbourPos);
            if (cluster == null || cluster.blocks.isEmpty()) {
                continue;
            }

            reflowCluster(world, cluster);
            processed.addAll(cluster.blocks);
        }
    }

    /**
     * Applies the layout to every AUTO block in the component.
     *
     * Segmentation rule:
     *  - widths are partitioned into balanced segments of width <= 3
     *  - examples: 4 -> 2 + 2, 5 -> 1 + 3 + 1, 7 -> 2 + 3 + 2
     */
    private void reflowCluster(World world, ClusterInfo cluster) {
        reflowCluster(world, cluster, null, 0);
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        ClusterInfo cluster = discoverCluster(world, seed);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> positions = new HashSet<>(cluster.blocks);
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(positions);
        if (unsafe != null) {
            return unsafe;
        }
        reflowCluster(world, cluster);
        return new ClusterRecalcResult(positions, true);
    }

    private void reflowCluster(World world, ClusterInfo cluster, StyleSet forcedStyle) {
        reflowCluster(world, cluster, forcedStyle, 0);
    }

    private void reflowCluster(World world, ClusterInfo cluster, StyleSet forcedStyle, int forcedWidth) {
        boolean started = beginClusterStateSync();
        try {
            if (cluster.blocks.isEmpty()) {
                return;
            }

            computeLocalCoordinates(cluster);
            if (cluster.localCoords.isEmpty()) {
                return; // non-planar; do not reflow
            }

            int clusterWidth = forcedWidth == 0 ? inferClusterWidth(world, cluster) : clampClusterWidth(forcedWidth);
            List<SegmentSpan> segmentSpans = buildSegments(cluster.maxLocalX - cluster.minLocalX + 1, clusterWidth);
            if (segmentSpans.isEmpty()) {
                return;
            }
            clearWidthOverride(cluster.blocks, world);

            // Group positions by balanced segment.
            Map<Integer, List<BlockPos>> segments = new LinkedHashMap<>();
            for (int i = 0; i < segmentSpans.size(); i++) {
                segments.put(i, new ArrayList<>());
            }

            for (BlockPos p : cluster.blocks) {
                ClusterInfo.LocalPos lp = cluster.localCoords.get(p);
                if (lp == null) {
                    continue;
                }

                int seg = findSegmentIndex(segmentSpans, lp.x());
                if (seg >= 0) {
                    segments.get(seg).add(p);
                }
            }

            for (int segmentIndex = 0; segmentIndex < segmentSpans.size(); segmentIndex++) {
                List<BlockPos> segBlocks = segments.get(segmentIndex);
                if (segBlocks.isEmpty()) {
                    continue;
                }

                SegmentSpan segmentSpan = segmentSpans.get(segmentIndex);
                StyleSet segStyle = forcedStyle != null ? forcedStyle : inferSegmentStyle(world, segBlocks);
                if (!supportsColumnStyle()) {
                    segStyle = StyleSet.BASE;
                }

                // Compute vertical bounds (world Y) for the segment.
                int segTopY = Integer.MIN_VALUE;
                int segBottomY = Integer.MAX_VALUE;

                for (BlockPos p : segBlocks) {
                    segTopY = Math.max(segTopY, p.getY());
                    segBottomY = Math.min(segBottomY, p.getY());
                }

                int width = segmentSpan.maxX() - segmentSpan.minX() + 1;
                int height = segTopY - segBottomY + 1;

                // Apply arrangement to each block within this segment.
                for (BlockPos p : segBlocks) {
                    BlockState st = world.getBlockState(p);
                    if (!isArchBlock(st)) {
                        continue;
                    }

                    ClusterInfo.LocalPos lp = cluster.localCoords.get(p);
                    if (lp == null) {
                        continue;
                    }

                    int x = lp.x() - segmentSpan.minX();
                    int y = segTopY - p.getY();

                    Arrangement arrangement = computeArrangement(width, height, x, y, segStyle);

                    BlockState newState = st
                            .with(ARRANGEMENT, arrangement)
                            .with(WIDTH, clusterWidth);

                    if (newState != st) {
                        world.setBlockState(p, newState, ClusterRecalcSafety.updateFlags(Block.NOTIFY_LISTENERS));
                    }
                }
            }
        } finally {
            endClusterStateSync(started);
        }
    }

    private static List<SegmentSpan> buildSegments(int totalWidth, int widthOverride) {
        return switch (widthOverride) {
            case 1 -> buildSingleWidthSegments(totalWidth);
            case 2 -> buildWidthTwoSegments(totalWidth);
            default -> buildBalancedSegments(totalWidth);
        };
    }

    private static List<SegmentSpan> buildSingleWidthSegments(int totalWidth) {
        List<SegmentSpan> segments = new ArrayList<>();
        for (int x = 0; x < totalWidth; x++) {
            segments.add(new SegmentSpan(x, x));
        }
        return segments;
    }

    private static List<SegmentSpan> buildWidthTwoSegments(int totalWidth) {
        List<Integer> widths = new ArrayList<>();
        if (totalWidth <= 0) {
            return segmentsFromWidths(widths);
        }
        if (totalWidth <= 2) {
            widths.add(totalWidth);
            return segmentsFromWidths(widths);
        }

        int pairs = totalWidth / 2;
        if (totalWidth % 2 == 0) {
            for (int i = 0; i < pairs; i++) {
                widths.add(2);
            }
            return segmentsFromWidths(widths);
        }

        int leftPairs = pairs / 2;
        int rightPairs = pairs - leftPairs;
        for (int i = 0; i < leftPairs; i++) {
            widths.add(2);
        }
        widths.add(1);
        for (int i = 0; i < rightPairs; i++) {
            widths.add(2);
        }
        return segmentsFromWidths(widths);
    }

    private static List<SegmentSpan> buildBalancedSegments(int totalWidth) {
        List<SegmentSpan> segments = new ArrayList<>();
        if (totalWidth <= 0) {
            return segments;
        }

        List<Integer> widths = new ArrayList<>();
        if (totalWidth <= 3) {
            widths.add(totalWidth);
        } else {
            int fullTriples = totalWidth / 3;
            int remainder = totalWidth % 3;

            switch (remainder) {
                case 0 -> {
                    for (int i = 0; i < fullTriples; i++) {
                        widths.add(3);
                    }
                }
                case 1 -> {
                    widths.add(2);
                    for (int i = 0; i < fullTriples - 1; i++) {
                        widths.add(3);
                    }
                    widths.add(2);
                }
                case 2 -> {
                    widths.add(1);
                    for (int i = 0; i < fullTriples; i++) {
                        widths.add(3);
                    }
                    widths.add(1);
                }
                default -> throw new IllegalStateException("Unexpected width remainder: " + remainder);
            }
        }

        int minX = 0;
        for (int width : widths) {
            segments.add(new SegmentSpan(minX, minX + width - 1));
            minX += width;
        }

        return segments;
    }

    private static List<SegmentSpan> segmentsFromWidths(List<Integer> widths) {
        List<SegmentSpan> segments = new ArrayList<>();
        int minX = 0;
        for (int width : widths) {
            segments.add(new SegmentSpan(minX, minX + width - 1));
            minX += width;
        }
        return segments;
    }

    private static int findSegmentIndex(List<SegmentSpan> segments, int localX) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).contains(localX)) {
                return i;
            }
        }
        return -1;
    }

    private static void clearWidthOverride(World world, BlockPos pos) {
        if (ClusterManualLockState.isPreservedForSwap(pos)) {
            return;
        }
        ClusterManualLockState.clearInt(world, WIDTH_OVERRIDE_SCOPE, pos);
    }

    private static void clearWidthOverride(Set<BlockPos> positions, World world) {
        for (BlockPos pos : positions) {
            clearWidthOverride(world, pos);
        }
    }

    private static int inferClusterWidth(WorldAccess world, ClusterInfo cluster) {
        int widthOneCount = 0;
        int widthTwoCount = 0;

        for (BlockPos pos : cluster.blocks) {
            BlockState state = world.getBlockState(pos);
            if (!state.contains(WIDTH)) {
                continue;
            }

            int width = state.get(WIDTH);
            if (width == 1) {
                widthOneCount++;
            } else if (width == 2) {
                widthTwoCount++;
            }
        }

        if (widthOneCount > 0 || widthTwoCount > 0) {
            return widthOneCount > widthTwoCount ? 1 : 2;
        }

        int legacyWidth = inferLegacyWidthOverride(world, cluster);
        return legacyWidth == 0 ? DEFAULT_WIDTH : legacyWidth;
    }

    private static int inferLegacyWidthOverride(WorldAccess world, ClusterInfo cluster) {
        int widthOneCount = 0;
        int widthTwoCount = 0;

        for (BlockPos pos : cluster.blocks) {
            int widthOverride = ClusterManualLockState.getInt(world, WIDTH_OVERRIDE_SCOPE, pos);
            if (widthOverride == 1) {
                widthOneCount++;
            } else if (widthOverride == 2) {
                widthTwoCount++;
            }
        }

        if (widthOneCount == 0 && widthTwoCount == 0) {
            return 0;
        }
        return widthOneCount > widthTwoCount ? 1 : 2;
    }

    private static int clampClusterWidth(int width) {
        return (width >= 1 && width <= 3) ? width : DEFAULT_WIDTH;
    }

    private StyleSet inferComponentStyle(World world, ClusterInfo cluster) {
        for (BlockPos p : cluster.blocks) {
            BlockState st = world.getBlockState(p);
            if (isArchBlock(st) && styleFromArrangement(st.get(ARRANGEMENT)) == StyleSet.COLUMN) {
                return StyleSet.COLUMN;
            }
        }
        return StyleSet.BASE;
    }

    private StyleSet inferSegmentStyle(World world, List<BlockPos> segBlocks) {
        for (BlockPos p : segBlocks) {
            BlockState st = world.getBlockState(p);
            if (isArchBlock(st) && styleFromArrangement(st.get(ARRANGEMENT)) == StyleSet.COLUMN) {
                return StyleSet.COLUMN;
            }
        }
        return StyleSet.BASE;
    }

    private static Arrangement mirrorArrangement(Arrangement arrangement) {
        String[] tokens = arrangement.name().split("_");
        boolean changed = false;

        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "L" -> {
                    tokens[i] = "R";
                    changed = true;
                }
                case "R" -> {
                    tokens[i] = "L";
                    changed = true;
                }
                case "LH" -> {
                    tokens[i] = "RH";
                    changed = true;
                }
                case "RH" -> {
                    tokens[i] = "LH";
                    changed = true;
                }
                default -> {
                }
            }
        }

        if (!changed) {
            return arrangement;
        }

        String mirroredName = String.join("_", tokens);
        try {
            return Arrangement.valueOf(mirroredName);
        } catch (IllegalArgumentException ignored) {
            return arrangement;
        }
    }

    private static StyleSet styleFromArrangement(Arrangement arrangement) {
        return isColumnStyleArrangement(arrangement) ? StyleSet.COLUMN : StyleSet.BASE;
    }

    private static boolean isColumnStyleArrangement(Arrangement arrangement) {
        return switch (arrangement) {
            case DOUBLE_TOP_L_COLUMN,
                 DOUBLE_TOP_R_COLUMN,
                 TRIPLE_SINGLE_L_COLUMN,
                 TOP_LARGE_COLUMN,
                 TRIPLE_SINGLE_R_COLUMN,
                 TRIPLE_TOP_L_COLUMN,
                 TRIPLE_TOP_R_COLUMN,
                 TRIPLE_ROW2_L_COLUMN,
                 TRIPLE_VOID_COLUMN,
                 TRIPLE_ROW2_R_COLUMN,
                 DOUBLE_COLUMN_UPPER_L,
                 DOUBLE_COLUMN_UPPER_R,
                 DOUBLE_COLUMN_SHAFT_L,
                 DOUBLE_COLUMN_SHAFT_R,
                 DOUBLE_COLUMN_BASE_L,
                 DOUBLE_COLUMN_BASE_R,
                 DOUBLE_COLUMN_UPPER_BASE_L,
                 DOUBLE_COLUMN_UPPER_BASE_R,
                 TRIPLE_COLUMN_UPPER_L,
                 TRIPLE_COLUMN_UPPER_R,
                 TRIPLE_COLUMN_SHAFT_L,
                 TRIPLE_COLUMN_SHAFT_R,
                 TRIPLE_COLUMN_BASE_L,
                 TRIPLE_COLUMN_BASE_R,
                 TRIPLE_COLUMN_UPPER_BASE_L,
                 TRIPLE_COLUMN_UPPER_BASE_R -> true;
            default -> false;
        };
    }

    private Arrangement computeArrangement(int width, int height, int x, int y, StyleSet style) {
        if (width <= 0 || height <= 0) {
            return Arrangement.SMALL_TOP;
        }

        // Width 1: single column has BOTH sides; top row has the small arch crown.
        if (width == 1) {
            return (y == 0) ? Arrangement.SMALL_TOP : Arrangement.SMALL_BODY;
        }

        // Width 2: left column = non-#, right column = #.
        if (width == 2) {
            if (y == 0) {
                if (style == StyleSet.COLUMN && height == 1) {
                    return (x == 0) ? Arrangement.DOUBLE_TOP_L_COLUMN : Arrangement.DOUBLE_TOP_R_COLUMN;
                }
                return (x == 0) ? Arrangement.DOUBLE_TOP_L : Arrangement.DOUBLE_TOP_R;
            }

            if (style == StyleSet.COLUMN) {
                boolean bottom = (y == height - 1);

                // Height 2: the only body row must act as both "upper" and "base".
                if (height == 2) {
                    return (x == 0) ? Arrangement.DOUBLE_COLUMN_UPPER_BASE_L : Arrangement.DOUBLE_COLUMN_UPPER_BASE_R;
                }

                // First row below top uses the upper transition.
                if (y == 1) {
                    return (x == 0) ? Arrangement.DOUBLE_COLUMN_UPPER_L : Arrangement.DOUBLE_COLUMN_UPPER_R;
                }

                // Bottom row uses the plinth.
                if (bottom) {
                    return (x == 0) ? Arrangement.DOUBLE_COLUMN_BASE_L : Arrangement.DOUBLE_COLUMN_BASE_R;
                }

                // Middle rows are shaft-only.
                return (x == 0) ? Arrangement.DOUBLE_COLUMN_SHAFT_L : Arrangement.DOUBLE_COLUMN_SHAFT_R;
            }

            // Base set: plain medium sides
            return (x == 0) ? Arrangement.DOUBLE_BODY_L : Arrangement.DOUBLE_BODY_R;
        }

        // Width 3: through-arch with a top keystone and a void below.
        if (x == 1) {
            if (y == 0) {
                if (style == StyleSet.COLUMN && height == 1) {
                    return Arrangement.TOP_LARGE_COLUMN;
                }
                return Arrangement.TOP_LARGE;
            }
            if (style == StyleSet.COLUMN && height == 2) {
                return Arrangement.TRIPLE_VOID_COLUMN;
            }
            return Arrangement.TRIPLE_VOID;
        }

        boolean right = (x >= 2);

        // 3x1 uses medium corners (no large_upper / large_lower pair)
        if (height == 1) {
            if (style == StyleSet.COLUMN) {
                return right ? Arrangement.TRIPLE_SINGLE_R_COLUMN : Arrangement.TRIPLE_SINGLE_L_COLUMN;
            }
            return right ? Arrangement.TRIPLE_SINGLE_R : Arrangement.TRIPLE_SINGLE_L;
        }

        if (y == 0) {
            if (style == StyleSet.COLUMN && height == 2) {
                return right ? Arrangement.TRIPLE_TOP_R_COLUMN : Arrangement.TRIPLE_TOP_L_COLUMN;
            }
            return right ? Arrangement.TRIPLE_TOP_R : Arrangement.TRIPLE_TOP_L;
        }

        // Second row: large_lower corners + side_large on edges; void in middle.
        if (y == 1) {
            if (style == StyleSet.COLUMN && height == 2) {
                return right ? Arrangement.TRIPLE_ROW2_R_COLUMN : Arrangement.TRIPLE_ROW2_L_COLUMN;
            }
            return right ? Arrangement.TRIPLE_ROW2_R : Arrangement.TRIPLE_ROW2_L;
        }

        // Rows y >= 2: base body OR column set
        if (style == StyleSet.COLUMN) {
            boolean bottom = (y == height - 1);
            boolean firstColumnRow = (y == 2);

            // Height 3: the only column row is both transition + base (upper + column + plinth).
            if (height == 3) {
                return right ? Arrangement.TRIPLE_COLUMN_UPPER_BASE_R : Arrangement.TRIPLE_COLUMN_UPPER_BASE_L;
            }

            if (firstColumnRow) {
                return right ? Arrangement.TRIPLE_COLUMN_UPPER_R : Arrangement.TRIPLE_COLUMN_UPPER_L;
            }

            if (bottom) {
                return right ? Arrangement.TRIPLE_COLUMN_BASE_R : Arrangement.TRIPLE_COLUMN_BASE_L;
            }

            return right ? Arrangement.TRIPLE_COLUMN_SHAFT_R : Arrangement.TRIPLE_COLUMN_SHAFT_L;
        }

        return right ? Arrangement.TRIPLE_BODY_R : Arrangement.TRIPLE_BODY_L;
    }

    // --- Shapes --------------------------------------------------------------

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // Void cells must remain targetable so they can be deleted/changed.
        if (isVoidState(state)) {
            return VoxelShapes.fullCube();
        }
        return getWorldSpaceShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // Players must be able to walk through the void.
        if (isVoidState(state)) {
            return SHAPE_EMPTY;
        }
        return getWorldSpaceShape(state);
    }

    private static boolean isVoidState(BlockState state) {
        return state.get(ARRANGEMENT).isVoid();
    }

    private VoxelShape getWorldSpaceShape(BlockState state) {
        Direction facing = state.get(FACING);
        Arrangement arrangement = state.get(ARRANGEMENT);

        int idx = shapeCacheIndex(arrangement, facing);
        VoxelShape cached = SHAPE_CACHE[idx];
        if (cached != null) {
            return cached;
        }

        // Build shape in NORTH-authored space first
        VoxelShape shape = SHAPE_EMPTY;

        // Corner
        VoxelShape cornerShape = getCornerShape(arrangement.corner(), arrangement.cornerFlip());
        if (!cornerShape.isEmpty()) {
            shape = VoxelShapes.union(shape, cornerShape);
        }

        // Left edge components (non-#)
        shape = VoxelShapes.union(shape, getSideShape(arrangement.sideL(), false));
        shape = VoxelShapes.union(shape, getUpperShape(arrangement.upperL(), false));
        if (arrangement.columnL()) {
            shape = VoxelShapes.union(shape, SHAPE_SIDE_COLUMN_LH);
        }
        if (arrangement.plinthL()) {
            shape = VoxelShapes.union(shape, SHAPE_PLINTH_LH);
        }

        // Right edge components (# / rotated in blockstates)
        shape = VoxelShapes.union(shape, getSideShape(arrangement.sideR(), true));
        shape = VoxelShapes.union(shape, getUpperShape(arrangement.upperR(), true));
        if (arrangement.columnR()) {
            shape = VoxelShapes.union(shape, SHAPE_SIDE_COLUMN_RH);
        }
        if (arrangement.plinthR()) {
            shape = VoxelShapes.union(shape, SHAPE_PLINTH_RH);
        }

        if (arrangement.hasTopLarge()) {
            shape = VoxelShapes.union(shape, SHAPE_TOP_LARGE);
        }

        // Rotate NORTH-authored shapes into the block's actual FACING.
        VoxelShape rotated = rotateShapeFromNorthToFacing(shape, facing).simplify();
        SHAPE_CACHE[idx] = rotated;
        return rotated;
    }

    private static VoxelShape getCornerShape(Corner corner, boolean flip) {
        return switch (corner) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_CORNER_SMALL;
            case MEDIUM -> flip ? SHAPE_CORNER_MEDIUM_RH : SHAPE_CORNER_MEDIUM_LH;
            case LARGE_UPPER -> flip ? SHAPE_CORNER_LARGE_UPPER_RH : SHAPE_CORNER_LARGE_UPPER_LH;
            case LARGE_LOWER -> SHAPE_CORNER_LARGE_LOWER;
        };
    }

    private static VoxelShape getSideShape(Side side, boolean right) {
        return switch (side) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> right ? SHAPE_SIDE_SMALL_RH : SHAPE_SIDE_SMALL_LH;
            case MEDIUM -> right ? SHAPE_SIDE_MEDIUM_RH : SHAPE_SIDE_MEDIUM_LH;
            case LARGE -> right ? SHAPE_SIDE_LARGE_RH : SHAPE_SIDE_LARGE_LH;
        };
    }

    private static VoxelShape getUpperShape(Upper upper, boolean right) {
        return switch (upper) {
            case NONE -> SHAPE_EMPTY;
            case MEDIUM -> right ? SHAPE_SIDE_MEDIUM_UPPER_RH : SHAPE_SIDE_MEDIUM_UPPER_LH;
            case LARGE -> right ? SHAPE_SIDE_LARGE_UPPER_RH : SHAPE_SIDE_LARGE_UPPER_LH;
        };
    }

    private static int shapeCacheIndex(Arrangement arrangement, Direction facing) {
        return arrangement.ordinal() * 4 + facingIndex(facing);
    }

    private static int facingIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeFromNorthToFacing(VoxelShape shape, Direction facing) {
        if (shape.isEmpty()) {
            return shape;
        }

        if (facing == Direction.NORTH) {
            return shape;
        }

        int rotations;
        switch (facing) {
            case EAST -> rotations = 1;
            case SOUTH -> rotations = 2;
            case WEST -> rotations = 3;
            default -> {
                return shape;
            }
        }

        VoxelShape rotated = shape;
        for (int i = 0; i < rotations; i++) {
            rotated = rotateShape90Y(rotated);
        }
        return rotated;
    }

    /**
     * Rotates a shape 90 degrees clockwise around Y in block-local coords.
     * (x, z) -> (1 - z, x)
     */
    private static VoxelShape rotateShape90Y(VoxelShape shape) {
        if (shape.isEmpty()) {
            return shape;
        }

        final VoxelShape[] result = { VoxelShapes.empty() };

        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double newMinX = 1.0 - maxZ;
            double newMaxX = 1.0 - minZ;
            double newMinZ = minX;
            double newMaxZ = maxX;

            VoxelShape rotatedBox = VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ);
            result[0] = VoxelShapes.union(result[0], rotatedBox);
        });

        return result[0];
    }

    private static VoxelShape makeCornerSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08, 0.701434375, 0.015625, 0.144944375, 0.886444375, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08, 0.886444375, 0.015625, 0.92, 1.000774375, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.144944375, 0.8215, 0.015625, 0.265274375, 0.886444375, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.855055625, 0.701434375, 0.015625, 0.92, 0.886444375, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.734725625, 0.8215, 0.015625, 0.855055625, 0.886444375, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1892, 0.657315625, 0.1953125, 0.272764375, 0.7757225, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1892, 0.7757225, 0.1953125, 0.8108, 0.825853125, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.272764375, 0.7341575, 0.1953125, 0.349775625, 0.7757225, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.727235625, 0.657315625, 0.1953125, 0.8108, 0.7757225, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.650224375, 0.7341575, 0.1953125, 0.727235625, 0.7757225, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1115, 0.676690625, 0.1171875, 0.215955625, 0.82469875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1115, 0.82469875, 0.1171875, 0.8885, 0.8873625, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.215955625, 0.772743125, 0.1171875, 0.312219375, 0.82469875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.784044375, 0.676690625, 0.1171875, 0.8885, 0.82469875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.687780625, 0.772743125, 0.1171875, 0.784044375, 0.82469875, 0.8828125));
        return shape;
    }

    private static VoxelShape makeCornerMediumLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.16000000000000014, 0.40165312499999994, 0.015625, 0.289889375, 0.771673125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.289889375, 0.6417837500000001, 0.015625, 0.530549375, 0.771673125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.16000000000000014, 0.771673125, 0.015625, 1, 1.000333125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.22299999999999986, 0.6481818750000001, 0.115625, 1, 0.7735099999999999, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.22299999999999986, 0.35216562500000004, 0.115625, 0.43191124999999997, 0.6481818750000001, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.43191124999999997, 0.544270625, 0.115625, 0.624439375, 0.6481818750000001, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.37780000000000014, 0.55022875, 0.1953125, 1, 0.65049125, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.37840000000000007, 0.313415625, 0.1953125, 0.545529375, 0.55022875, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.545529375, 0.4670993750000001, 0.1953125, 0.6995512500000001, 0.55022875, 0.8046875));
        return shape;
    }

    private static VoxelShape makeCornerMediumRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.710110625, 0.40165312499999994, 0.015625, 0.8399999999999999, 0.771673125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.469450625, 0.6417837500000001, 0.015625, 0.710110625, 0.771673125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.771673125, 0.015625, 0.8399999999999999, 1.000333125, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.6481818750000001, 0.115625, 0.7770000000000001, 0.7735099999999999, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.56808875, 0.35216562500000004, 0.115625, 0.7770000000000001, 0.6481818750000001, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375560625, 0.544270625, 0.115625, 0.56808875, 0.6481818750000001, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.55022875, 0.1953125, 0.6221999999999999, 0.65049125, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.454470625, 0.313415625, 0.1953125, 0.6215999999999999, 0.55022875, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.30044874999999993, 0.4670993750000001, 0.1953125, 0.454470625, 0.55022875, 0.8046875));
        return shape;
    }

    private static VoxelShape makeCornerLargeUpperLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.239, 0.65701, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.23889000000000005, 0.10175, 0.015625, 0.43372374999999996, 0.65778, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.43372374999999996, 0.46217625, 0.015625, 0.79471375, 0.65701, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.647006875, 0.3157975, 0.1171875, 0.93579875, 0.471665, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.41813999999999996, 0.028, 0.125, 0.647006875, 0.47527375, 0.890625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.33399999999999996, 0.471665, 0.1171875, 1, 0.659656875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.81696625, 0.20004125, 0.1953125, 1, 0.324735, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.63024, 0, 0.1875, 0.81743375, 0.355219375, 0.796875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5669500000000001, 0.324735, 0.1953125, 1, 0.47512875, 0.8046875));
        return shape;
    }

    private static VoxelShape makeCornerLargeUpperRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.65701, 0.015625, 0.761, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.56627625, 0.10175, 0.015625, 0.76111, 0.65778, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.20528625, 0.46217625, 0.015625, 0.56627625, 0.65701, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.06420125, 0.3157975, 0.1171875, 0.352993125, 0.471665, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.352993125, 0.028, 0.125, 0.58186, 0.47527375, 0.890625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.471665, 0.1171875, 0.666, 0.659656875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.20004125, 0.1953125, 0.18303375, 0.324735, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.18256625, 0, 0.1875, 0.36976, 0.355219375, 0.796875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.324735, 0.1953125, 0.43305, 0.47512875, 0.8046875));
        return shape;
    }

    private static VoxelShape makeSideSmallLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0, 0.015625, 0.07962999999999998, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.07999999999999996, 0, 0.1171875, 0.16387375000000004, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.163710625, 0, 0.1953125, 0.23112374999999996, 1, 0.8046875));
        return shape;
    }

    private static VoxelShape makeSideSmallRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.92037, 0, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.83612625, 0, 0.1171875, 0.92, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76887625, 0, 0.1953125, 0.836289375, 1, 0.8046875));
        return shape;
    }

    private static VoxelShape makeSideMediumLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.32742125, 0, 0.1953125, 0.46224750000000014, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.16000000000000014, 0, 0.11562499999999998, 0.32774812499999983, 1, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0, 0.015625, 0.15925999999999996, 1, 0.984375));
        return shape;
    }

    private static VoxelShape makeSideMediumRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5377524999999999, 0, 0.1953125, 0.67257875, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6722518750000002, 0, 0.11562499999999998, 0.8399999999999999, 1, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.84074, 0, 0.015625, 1, 1, 0.984375));
        return shape;
    }

    private static VoxelShape makeSideLargeLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0, 0.015625, 0.23889000000000005, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.22889000000000004, 0, 0.1171875, 0.48051187500000003, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.49113125, 0, 0.1953125, 0.69337125, 1, 0.8046875));
        return shape;
    }

    private static VoxelShape makeSideLargeRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76111, 0, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.519488125, 0, 0.1171875, 0.77111, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.30662875, 0, 0.1953125, 0.50886875, 1, 0.8046875));
        return shape;
    }

    private static VoxelShape makeSideMediumUpperLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.32742125, 0.53125, 0.1953125, 0.46224750000000014, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.16000000000000014, 0.53125, 0.115625, 0.32774812499999983, 1, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.53125, 0.015625, 0.15925999999999996, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.32742125, 0.4375, 0.1796875, 0.47787250000000014, 0.53125, 0.8203125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.16000000000000014, 0.4375, 0.09999999999999998, 0.34337312499999983, 0.53125, 0.9));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.4375, 0, 0.17488499999999996, 0.53125, 1));
        return shape;
    }

    private static VoxelShape makeSideMediumUpperRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5377524999999999, 0.53125, 0.1953125, 0.67257875, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6722518750000002, 0.53125, 0.115625, 0.8399999999999999, 1, 0.884375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.84074, 0.53125, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5221274999999999, 0.4375, 0.1796875, 0.67257875, 0.53125, 0.8203125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6566268750000002, 0.4375, 0.09999999999999998, 0.8399999999999999, 0.53125, 0.9));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.825115, 0.4375, 0, 1, 0.53125, 1));
        return shape;
    }

    private static VoxelShape makeSideLargeUpperLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.78125, 0.015625, 0.23889000000000005, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.22889000000000004, 0.78125, 0.1171875, 0.48051187500000003, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.47550625, 0.78125, 0.1953125, 0.69337125, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.71875, 0, 0.25451500000000005, 0.78125, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.22889000000000004, 0.71875, 0.1015625, 0.49613687500000003, 0.78125, 0.8984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0008553358726790528, 0.5299684367780975, 0.1015625, 0.30735533587267905, 0.7487184367780975, 0.8984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0007334612875276036, 0.5299684665804199, 0, 0.06573346128752733, 0.7189684665804199, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.49113125, 0.71875, 0.1796875, 0.70899625, 0.78125, 0.8203125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.27721471359951166, 0.5299684611095984, 0.1796875, 0.49596471359951166, 0.7487184611095984, 0.8203125));
        return shape;
    }

    private static VoxelShape makeSideLargeUpperRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76111, 0.78125, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.519488125, 0.78125, 0.1171875, 0.77111, 1, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.30662875, 0.78125, 0.1953125, 0.52449375, 1, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.745485, 0.71875, 0, 1, 0.78125, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.503863125, 0.71875, 0.1015625, 0.77111, 0.78125, 0.8984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.692644664127321, 0.5299684367780975, 0.1015625, 0.999144664127321, 0.7487184367780975, 0.8984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.9342665387124727, 0.5299684665804199, 0, 0.9992665387124724, 0.7189684665804199, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.29100375, 0.71875, 0.1796875, 0.50886875, 0.78125, 0.8203125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5040352864004883, 0.5299684611095984, 0.1796875, 0.7227852864004883, 0.7487184611095984, 0.8203125));
        return shape;
    }

    private static VoxelShape makeSideColumnLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0005590214009751904, 0, 0.11086258599200038, 0.08502763052097895, 1, 0.8891374140080005));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08416762884716389, -5.551115123125783e-17, 0.1641416505121679, 0.21481827623916838, 1, 0.8345948785281679));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.2143698754564829, 2.7755575615628914e-17, 0.27177955263936115, 0.32702052284848626, 1, 0.7269215806553615));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.32570042878138616, -5.551115123125783e-17, 0.41416196767126273, 0.38972607617338606, 1, 0.5736483956872607));
        return shape;
    }

    private static VoxelShape makeSideColumnRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.914972369479021, 0, 0.11086258599200038, 0.9994409785990248, 1, 0.8891374140080005));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.7851817237608316, -5.551115123125783e-17, 0.1641416505121679, 0.9158323711528361, 1, 0.8345948785281679));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6729794771515137, 2.7755575615628914e-17, 0.27177955263936115, 0.7856301245435171, 1, 0.7269215806553615));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6102739238266139, -5.551115123125783e-17, 0.41416196767126273, 0.6742995712186138, 1, 0.5736483956872607));
        return shape;
    }

    private static VoxelShape makePlinthLHShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.3676688734725865, 0, 0.4135632738527729, 0.4367313734725864, 0.0625, 0.5874373507277729));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.00033175090241366334, 0, 0.06343750010277294, 0.08660622409758645, 0.0625, 0.9375625001027729));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08637003061156001, 0, 0.1299761660540692, 0.24724473186156004, 0.0625, 0.8705074160540693));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.24731777439050284, 0, 0.25292377997310206, 0.37019247564050284, 0.0625, 0.747330029973102));
        return shape;
    }

    private static VoxelShape makePlinthRHShape() {
        VoxelShape shape = VoxelShapes.empty();
        return shape;
    }

    private static VoxelShape makeTopLargeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.324735, 0.1953125, 1, 0.47512875, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.471665, 0.1171875, 1, 0.659656875, 0.8828125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.65701, 0.015625, 1, 1, 0.984375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00043375, 0.20004125, 0.1953125, 0.0484675, 0.324735, 0.8046875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.9515325, 0.20004125, 0.1953125, 0.99956625, 0.324735, 0.8046875));
        return shape;
    }

}
