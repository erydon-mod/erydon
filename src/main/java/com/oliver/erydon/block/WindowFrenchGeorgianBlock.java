package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public class WindowFrenchGeorgianBlock extends Block implements ClusterRebuildableBlock {

    private static final String MANUAL_LOCK_SCOPE = ClusterManualLockState.WINDOW_FRENCH_GEORGIAN_SCOPE;

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = Properties.OPEN;
    public static final EnumProperty<DoorHinge> HINGE = Properties.DOOR_HINGE;
    public static final EnumProperty<Piece> PIECE = EnumProperty.of("piece", Piece.class);
    public static final BooleanProperty SILL = BooleanProperty.of("sill");

    // Prevent re-entrant cluster sync loops when we update many blocks at once.
    private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);

    private static boolean beginSync() {
        if (Boolean.TRUE.equals(SYNCING.get())) return false;
        SYNCING.set(true);
        return true;
    }

    private static void endSync(boolean started) {
        if (started) SYNCING.set(false);
    }

    private static boolean isSyncing() {
        return Boolean.TRUE.equals(SYNCING.get());
    }


    



    private enum ShapeKey {
        CLOSED_LOWER_SINGLE,
        CLOSED_UPPER_SINGLE,

        CLOSED_LOWER_MULTI_LH,
        CLOSED_LOWER_MULTI_MID,
        CLOSED_LOWER_MULTI_RH,

        CLOSED_UPPER_MULTI_LH,
        CLOSED_UPPER_MULTI_MID,
        CLOSED_UPPER_MULTI_RH,

        OPEN_LOWER_SINGLE_LH,
        OPEN_LOWER_SINGLE_RH,

        OPEN_UPPER_SINGLE_LH,
        OPEN_UPPER_SINGLE_RH,

        OPEN_LOWER_MULTI_LH,
        OPEN_LOWER_MULTI_RH,

        OPEN_UPPER_MULTI_LH,
        OPEN_UPPER_MULTI_MID,
        OPEN_UPPER_MULTI_RH,

        SILL
    }

    private static final EnumMap<ShapeKey, VoxelShape[]> SHAPES = new EnumMap<>(ShapeKey.class);

    static {
        register(ShapeKey.CLOSED_LOWER_SINGLE, makeClosedLowerSingleShape());
        register(ShapeKey.CLOSED_UPPER_SINGLE, makeClosedUpperSingleShape());

        register(ShapeKey.CLOSED_LOWER_MULTI_LH, makeClosedLowerMultiLhShape());
        register(ShapeKey.CLOSED_LOWER_MULTI_MID, makeClosedLowerMultiMidShape());
        register(ShapeKey.CLOSED_LOWER_MULTI_RH, makeClosedLowerMultiRhShape());

        register(ShapeKey.CLOSED_UPPER_MULTI_LH, makeClosedUpperMultiLhShape());
        register(ShapeKey.CLOSED_UPPER_MULTI_MID, makeClosedUpperMultiMidShape());
        register(ShapeKey.CLOSED_UPPER_MULTI_RH, makeClosedUpperMultiRhShape());

        register(ShapeKey.OPEN_LOWER_SINGLE_LH, makeOpenLowerSingleLhShape());
        register(ShapeKey.OPEN_LOWER_SINGLE_RH, makeOpenLowerSingleRhShape());

        register(ShapeKey.OPEN_UPPER_SINGLE_LH, makeOpenUpperSingleLhShape());
        register(ShapeKey.OPEN_UPPER_SINGLE_RH, makeOpenUpperSingleRhShape());

        register(ShapeKey.OPEN_LOWER_MULTI_LH, makeOpenLowerMultiLhShape());
        register(ShapeKey.OPEN_LOWER_MULTI_RH, makeOpenLowerMultiRhShape());

        register(ShapeKey.OPEN_UPPER_MULTI_LH, makeOpenUpperMultiLhShape());
        register(ShapeKey.OPEN_UPPER_MULTI_MID, makeOpenUpperMultiMidShape());
        register(ShapeKey.OPEN_UPPER_MULTI_RH, makeOpenUpperMultiRhShape());

        register(ShapeKey.SILL, makeSillShape());
    }

    private static void register(ShapeKey key, VoxelShape northShape) {
        VoxelShape[] byFacing = new VoxelShape[4];
        byFacing[0] = northShape;
        byFacing[1] = rotateYClockwise(byFacing[0]);
        byFacing[2] = rotateYClockwise(byFacing[1]);
        byFacing[3] = rotateYClockwise(byFacing[2]);
        SHAPES.put(key, byFacing);
    }

    private static VoxelShape rotateYClockwise(VoxelShape shape) {
        final VoxelShape[] acc = new VoxelShape[]{ VoxelShapes.empty() };
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // 90° clockwise around Y: (x,z) -> (1 - z, x)
            double nMinX = 1.0 - maxZ;
            double nMaxX = 1.0 - minZ;
            double nMinZ = minX;
            double nMaxZ = maxX;
            acc[0] = VoxelShapes.combine(acc[0], VoxelShapes.cuboid(nMinX, minY, nMinZ, nMaxX, maxY, nMaxZ), BooleanBiFunction.OR);
        });
        return acc[0];
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

    public WindowFrenchGeorgianBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(OPEN, false)
                .with(HINGE, DoorHinge.LEFT)
                .with(PIECE, Piece.LOWER_SINGLE)
                .with(SILL, false)
        );
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE, PIECE, SILL);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        // Default: player-based
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        boolean open = world.isReceivingRedstonePower(pos);
        DoorHinge hinge = getDoorLikeHinge(ctx, facing);

        // If we are placing against an existing window, inherit its cluster identity.
        BlockPos clickedPos = pos.offset(ctx.getSide().getOpposite());
        BlockState clicked = world.getBlockState(clickedPos);

        BlockState inherit = null;
        if (clicked.isOf(this)) {
            inherit = clicked;
        } else {
            // Fallback: any adjacent window
            for (Direction d : Direction.values()) {
                BlockState n = world.getBlockState(pos.offset(d));
                if (n.isOf(this)) {
                    inherit = n;
                    break;
                }
            }
        }

        if (inherit != null) {
            facing = inherit.get(FACING);
            hinge = inherit.get(HINGE);
            open = inherit.get(OPEN);
        }

        return this.getDefaultState()
                .with(FACING, facing)
                .with(OPEN, open)
                .with(HINGE, hinge);
    }


    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world.isClient()) return;

        clearManualLock(world, pos);
        reflowConnectedAutoComponent(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (world.isClient()) return;

        if (!state.isOf(newState.getBlock())) {
            clearManualLock(world, pos);
            for (BlockPos n : planeNeighbours(pos, state.get(FACING))) {
                BlockState ns = world.getBlockState(n);
                if (ns.isOf(this)) {
                    reflowConnectedAutoComponent(world, n);
                }
            }
        }
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient()) return;

        // Placement is handled in onPlaced(); this hook is mainly for single-block state edits (e.g. debug stick).
        if (!oldState.isOf(this)) return;
        if (isSyncing()) return;

        boolean openChanged = state.get(OPEN) != oldState.get(OPEN);
        if (!openChanged) return;

        boolean started = beginSync();
        try {
            applyOpenToCluster(world, pos, state.get(OPEN));
        } finally {
            endSync(started);
        }
    }
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {

        ItemStack held = player.getStackInHand(hand);

        if (held.isOf(Items.DEBUG_STICK)) {
            if (player.isSneaking()) {
                if (world.isClient) {
                    return ActionResult.SUCCESS;
                }

                boolean locked = toggleManualLock(world, pos);
                handleManualLockChanged(world, pos, state.get(FACING), locked);
                player.sendMessage(Text.literal("French Georgian window mode: " + (locked ? "manual" : "auto")), true);
                return ActionResult.CONSUME;
            }

            // Let vanilla handle property selection + cycling, without opening the window.
            return ActionResult.PASS;
        }
        boolean placementClick = player.isSneaking() || hit.getSide().getAxis() == Direction.Axis.Y;

        if (placementClick && held.getItem() instanceof BlockItem bi && bi.getBlock() == this) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }

            Direction facing = state.get(FACING);
            Direction right  = facing.rotateYClockwise();
            Direction left   = facing.rotateYCounterclockwise();
            Direction side   = hit.getSide();

            boolean inPlane = (side == Direction.UP || side == Direction.DOWN || side == left || side == right);

            if (inPlane) {
                BlockPos placePos = pos.offset(side);

                BlockHitResult placeHit = new BlockHitResult(hit.getPos(), side, placePos, false);
                ItemPlacementContext ctx = new ItemPlacementContext(world, player, hand, held, placeHit);

                BlockState target = world.getBlockState(placePos);
                if (target.canReplace(ctx)) {
                    BlockState placed = getPlacementState(ctx);
                    if (placed != null) {
                        world.setBlockState(placePos, placed, Block.NOTIFY_ALL);
                        reflowConnectedAutoComponent(world, placePos);
                        return ActionResult.SUCCESS; // do NOT open
                    }
                }
            }
            return ActionResult.SUCCESS;
        }

        if (placementClick) {
            return ActionResult.PASS;
        }

        // ---- Normal side-click open/close toggle ----
        if (world.isClient) return ActionResult.SUCCESS;

        boolean newOpen = !state.get(OPEN);
        applyOpenToCluster(world, pos, newOpen);

        world.playSound(
                null,
                pos,
                newOpen ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
                SoundCategory.BLOCKS,
                1.0f,
                1.0f
        );
        world.emitGameEvent(player, newOpen ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);

        return ActionResult.CONSUME;
    }


    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos,
                               Block block, BlockPos fromPos, boolean notify) {
        if (world.isClient()) return;

        // Preserve player-toggled open state when unpowered; redstone only forces opening.
        if (world.isReceivingRedstonePower(pos) && !state.get(OPEN)) {
            applyOpenToCluster(world, pos, true);
        }
    }

    private void applyOpenToCluster(World world, BlockPos anchor, boolean open) {
        boolean started = beginSync();
        try {

        BlockState anchorState = world.getBlockState(anchor);
        if (!anchorState.isOf(this)) return;

        Direction facing = anchorState.get(FACING);

        // Open/closed is cluster-level (door-like), even if some blocks are MANUAL.
        Set<BlockPos> component = collectPlaneComponentAnyMode(world, anchor, facing);

        for (BlockPos p : component) {
            BlockState s = world.getBlockState(p);
            if (!s.isOf(this)) continue;
            if (s.get(FACING) != facing) continue;

            BlockState ns = s.with(OPEN, open);
            if (!Objects.equals(ns, s)) {
                world.setBlockState(p, ns, Block.NOTIFY_ALL);
            }
        }
    
        } finally {
            endSync(started);
        }
}

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
        return getWindowShape(state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int idx = facingIndex(state.get(FACING));
        return SHAPES.get(ShapeKey.CLOSED_UPPER_SINGLE)[idx]; // pane-sized outline (rotated by facing)
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.fullCube(); // keep targeting easy (incl. openings)
    }

    private VoxelShape getWindowShape(BlockState state) {
        int idx = facingIndex(state.get(FACING));

        VoxelShape base = VoxelShapes.empty();
        ShapeKey key = shapeKeyForState(state);

        if (key != null) {
            base = SHAPES.get(key)[idx];
        }

        if (state.get(SILL)) {
            // Avoid simplify-heavy unions while Minecraft rebuilds the global shape cache.
            base = VoxelShapes.combine(base, SHAPES.get(ShapeKey.SILL)[idx], BooleanBiFunction.OR);
        }

        return base;
    }

    private ShapeKey shapeKeyForState(BlockState state) {
        boolean open = state.get(OPEN);
        Piece piece = state.get(PIECE);

        if (!open) {
            return switch (piece) {
                case UPPER_SINGLE -> ShapeKey.CLOSED_UPPER_SINGLE;
                case LOWER_SINGLE -> ShapeKey.CLOSED_LOWER_SINGLE;

                case UPPER_MULTI_LH -> ShapeKey.CLOSED_UPPER_MULTI_LH;
                case UPPER_MULTI_MID -> ShapeKey.CLOSED_UPPER_MULTI_MID;
                case UPPER_MULTI_RH -> ShapeKey.CLOSED_UPPER_MULTI_RH;

                case LOWER_MULTI_LH -> ShapeKey.CLOSED_LOWER_MULTI_LH;
                case LOWER_MULTI_MID -> ShapeKey.CLOSED_LOWER_MULTI_MID;
                case LOWER_MULTI_RH -> ShapeKey.CLOSED_LOWER_MULTI_RH;
            };
        }

        return switch (piece) {
            case UPPER_SINGLE -> (state.get(HINGE) == DoorHinge.LEFT) ? ShapeKey.OPEN_UPPER_SINGLE_LH : ShapeKey.OPEN_UPPER_SINGLE_RH;
            case LOWER_SINGLE -> (state.get(HINGE) == DoorHinge.LEFT) ? ShapeKey.OPEN_LOWER_SINGLE_LH : ShapeKey.OPEN_LOWER_SINGLE_RH;

            case UPPER_MULTI_LH -> ShapeKey.OPEN_UPPER_MULTI_LH;
            case UPPER_MULTI_MID -> ShapeKey.OPEN_UPPER_MULTI_MID;
            case UPPER_MULTI_RH -> ShapeKey.OPEN_UPPER_MULTI_RH;

            case LOWER_MULTI_LH -> ShapeKey.OPEN_LOWER_MULTI_LH;
            case LOWER_MULTI_RH -> ShapeKey.OPEN_LOWER_MULTI_RH;

            case LOWER_MULTI_MID -> null; // opening (empty) unless SILL adds geometry
        };
    }

    private void reflowConnectedAutoComponent(World world, BlockPos seed) {
        reflowConnectedAutoComponent(world, seed, null);
    }

    private void reflowConnectedAutoComponent(World world, BlockPos seed, Set<BlockPos> component) {
        boolean started = beginSync();
        try {

        ClusterPartition partition = partitionComponent(world, seed, component);
        for (Rect rect : partition.rects) {
            applyRectLayout(world, rect);
        }
    
        } finally {
            endSync(started);
        }
}

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Direction facing = seedState.get(FACING);
        if (isManualLocked(world, seed)) {
            Set<BlockPos> lockedComponent = collectPlaneComponentWithLock(world, seed, facing, true);
            if (lockedComponent.isEmpty()) {
                return ClusterRecalcResult.none();
            }
            ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(lockedComponent);
            if (unsafe != null) {
                return unsafe;
            }
            return new ClusterRecalcResult(lockedComponent, false);
        }

        Set<BlockPos> component = collectPlaneComponentWithLock(world, seed, facing, false);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        reflowConnectedAutoComponent(world, seed, component);
        unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }
        return new ClusterRecalcResult(component, true);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        Piece piece = state.get(PIECE);
        DoorHinge hinge = state.get(HINGE);

        if (mirrorSwapsLeftRight(facing, mirror)) {
            piece = swapLeftRight(piece);
            hinge = hinge == DoorHinge.LEFT ? DoorHinge.RIGHT : DoorHinge.LEFT;
        }

        return rotate(state, mirror.getRotation(facing))
                .with(PIECE, piece)
                .with(HINGE, hinge);
    }

    private void applyRectLayout(World world, Rect rect) {
        BlockState seedState = world.getBlockState(rect.bottomLeft);
        if (!seedState.isOf(this)) return;

        boolean open = seedState.get(OPEN);
        DoorHinge hinge = seedState.get(HINGE);

        int w = rect.width;
        int h = rect.height;

        for (int dy = 0; dy < h; dy++) {
            int y = rect.bottomLeft.getY() + dy;
            boolean isBottom = dy == 0;
            boolean isTop = dy == (h - 1);

            for (int dx = 0; dx < w; dx++) {
                BlockPos p = rect.bottomLeft.offset(rect.rightDir, dx).withY(y);

                BlockState s = world.getBlockState(p);
                if (!s.isOf(this)) continue;
                Piece piece = computePiece(w, isTop, dx);

                BlockState ns = s
                        .with(OPEN, open)
                        .with(HINGE, hinge)
                        .with(PIECE, piece)
                        .with(SILL, isBottom)
                        .with(FACING, rect.facing);

                if (!Objects.equals(ns, s)) {
                    world.setBlockState(p, ns, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                }
            }
        }
    }

    private Piece computePiece(int width, boolean isTop, int col) {
        if (width == 1) {
            return isTop ? Piece.UPPER_SINGLE : Piece.LOWER_SINGLE;
        }

        boolean leftEdge  = (col == 0);
        boolean rightEdge = (col == width - 1);

        if (isTop) {
            if (leftEdge)  return Piece.UPPER_MULTI_RH;   // swapped
            if (rightEdge) return Piece.UPPER_MULTI_LH;   // swapped
            return Piece.UPPER_MULTI_MID;
        }

        if (leftEdge)  return Piece.LOWER_MULTI_RH;       // swapped
        if (rightEdge) return Piece.LOWER_MULTI_LH;       // swapped
        return Piece.LOWER_MULTI_MID;
    }

    

    private ClusterPartition partitionFromComponent(Set<BlockPos> component, Direction facing) {
        if (component.isEmpty()) return new ClusterPartition(List.of(), facing);

        Direction rightDir = facing.rotateYClockwise();

        int dx = rightDir.getOffsetX();
        int dz = rightDir.getOffsetZ();

        int fx = facing.getOffsetX();
        int fz = facing.getOffsetZ();

        int minY    = Integer.MAX_VALUE;
        int maxY    = Integer.MIN_VALUE;
        int minCol  = Integer.MAX_VALUE;
        int maxCol  = Integer.MIN_VALUE;
        Integer dep = null;

        for (BlockPos p : component) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());

            int col = (p.getX() * dx) + (p.getZ() * dz);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);

            int d = (p.getX() * fx) + (p.getZ() * fz);
            if (dep == null) dep = d;
        }

        if (dep == null) return new ClusterPartition(List.of(), facing);

        java.util.Map<Long, BlockPos> grid = new java.util.HashMap<>();
        for (BlockPos p : component) {
            int row = p.getY() - minY;
            int col = ((p.getX() * dx) + (p.getZ() * dz)) - minCol;
            long key = (((long) row) << 32) ^ (col & 0xffffffffL);
            grid.put(key, p);
        }

        int rows = (maxY - minY) + 1;
        int cols = (maxCol - minCol) + 1;

        boolean[][] present = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                long key = (((long) r) << 32) ^ (c & 0xffffffffL);
                present[r][c] = grid.containsKey(key);
            }
        }

        java.util.List<Rect> rects = partitionRects(present, grid, minY, minCol, rightDir, facing, dep);
        return new ClusterPartition(rects, facing);
    }

    private static java.util.List<Rect> partitionRects(boolean[][] present,
                                                       java.util.Map<Long, BlockPos> grid,
                                                       int minY,
                                                       int minCol,
                                                       Direction rightDir,
                                                       Direction facing,
                                                       Integer dep) {

        int rows = present.length;
        int cols = (rows == 0) ? 0 : present[0].length;

        boolean[][] used = new boolean[rows][cols];
        java.util.List<Rect> rects = new java.util.ArrayList<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!present[r][c] || used[r][c]) continue;

                int w = 0;
                while (c + w < cols && present[r][c + w] && !used[r][c + w]) {
                    w++;
                }

                int h = 1;
                outer:
                while (r + h < rows) {
                    for (int cc = 0; cc < w; cc++) {
                        if (!present[r + h][c + cc] || used[r + h][c + cc]) {
                            break outer;
                        }
                    }
                    h++;
                }

                for (int rr = 0; rr < h; rr++) {
                    for (int cc = 0; cc < w; cc++) {
                        used[r + rr][c + cc] = true;
                    }
                }

                long key = (((long) r) << 32) ^ (c & 0xffffffffL);
                BlockPos bottomLeft = grid.get(key);
                if (bottomLeft != null) {
                    rects.add(new Rect(bottomLeft, w, h, facing));
                }
            }
        }
        return rects;
    }



private ClusterPartition partitionComponent(World world, BlockPos seed) {
        return partitionComponent(world, seed, null);
    }

private ClusterPartition partitionComponent(World world, BlockPos seed, Set<BlockPos> discoveredComponent) {
        BlockState seedState = world.getBlockState(seed);

        Direction facing   = seedState.get(FACING);
        Direction rightDir = facing.rotateYClockwise();

        Set<BlockPos> component = discoveredComponent == null
                ? collectPlaneComponent(world, seed, facing)
                : discoveredComponent;
        if (component.isEmpty()) {
            return new ClusterPartition(List.of(), facing);
        }

        // Project to a 2D grid using dot-product coords (same approach as SurroundBlock)
        int dx = rightDir.getOffsetX();
        int dz = rightDir.getOffsetZ();

        int fx = facing.getOffsetX();
        int fz = facing.getOffsetZ();

        int minY    = Integer.MAX_VALUE;
        int maxY    = Integer.MIN_VALUE;
        int minCol  = Integer.MAX_VALUE;
        int maxCol  = Integer.MIN_VALUE;
        Integer dep = null;

        for (BlockPos p : component) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());

            int col = (p.getX() * dx) + (p.getZ() * dz);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);

            int d = (p.getX() * fx) + (p.getZ() * fz);
            if (dep == null) dep = d;
            else if (!dep.equals(d)) {
                // Non-planar component: fail safe (treat each block as its own cluster)
                List<Rect> singles = new ArrayList<>();
                for (BlockPos q : component) singles.add(new Rect(q, 1, 1, facing));
                return new ClusterPartition(singles, facing);
            }
        }

        int width  = (maxCol - minCol) + 1;
        int height = (maxY   - minY)   + 1;

        ClusterRecalcSafety.requireLayoutArea((long) width * height);
        if (ClusterRecalcSafety.unsafeResult(component) != null) {
            return new ClusterPartition(List.of(), facing);
        }

        BlockPos[][] grid   = new BlockPos[height][width];
        boolean[][] filled  = new boolean[height][width];
        boolean[][] used    = new boolean[height][width];

        for (BlockPos p : component) {
            int col = (p.getX() * dx) + (p.getZ() * dz);
            int x   = col - minCol;
            int y   = p.getY() - minY;   // y=0 is bottom row

            if (x < 0 || x >= width || y < 0 || y >= height) continue;

            filled[y][x] = true;
            grid[y][x]   = p;
        }

        List<Rect> rects = new ArrayList<>();

        // Greedy rectangle packing: guarantees vertical growth works
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!filled[y][x] || used[y][x]) continue;

                int w = 0;
                while (x + w < width && filled[y][x + w] && !used[y][x + w]) w++;

                int h = 1;
                outer:
                while (y + h < height) {
                    for (int xx = 0; xx < w; xx++) {
                        if (!filled[y + h][x + xx] || used[y + h][x + xx]) break outer;
                    }
                    h++;
                }

                for (int yy = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++) {
                        used[y + yy][x + xx] = true;
                    }
                }

                BlockPos bottomLeft = grid[y][x];
                rects.add(new Rect(bottomLeft, w, h, facing));
            }
        }

        return new ClusterPartition(rects, facing);
    }


    private Set<BlockPos> collectPlaneComponent(World world, BlockPos start, Direction facing) {
        return collectPlaneComponentWithLock(world, start, facing, false);
    }

    private Set<BlockPos> collectPlaneComponentAnyMode(World world, BlockPos start, Direction facing) {
        Set<BlockPos> out = new HashSet<>();
        Queue<BlockPos> q = new ArrayDeque<>();

        BlockState startState = ClusterRecalcSafety.getBlockState(world, start);
        if (!startState.isOf(this)) return out;
        if (startState.get(FACING) != facing) return out;

        if (!ClusterRecalcSafety.claim(start)) return out;
        out.add(start);
        q.add(start);

        while (!q.isEmpty()) {
            BlockPos p = q.remove();
            for (BlockPos n : planeNeighbours(p, facing)) {
                if (out.contains(n)) continue;

                BlockState ns = ClusterRecalcSafety.getBlockState(world, n);
                if (!ns.isOf(this)) continue;
                if (ns.get(FACING) != facing) continue;

                if (!ClusterRecalcSafety.claim(n)) return out;
                out.add(n);
                q.add(n);
            }
        }
        return out;
    }

    private Set<BlockPos> collectPlaneComponentWithLock(World world, BlockPos start, Direction facing, boolean locked) {
        Set<BlockPos> out = new HashSet<>();
        Queue<BlockPos> q = new ArrayDeque<>();

        BlockState startState = ClusterRecalcSafety.getBlockState(world, start);
        if (!startState.isOf(this)) return out;
        if (startState.get(FACING) != facing) return out;
        if (isManualLocked(world, start) != locked) return out;

        if (!ClusterRecalcSafety.claim(start)) return out;
        out.add(start);
        q.add(start);

        while (!q.isEmpty()) {
            BlockPos p = q.remove();
            for (BlockPos n : planeNeighbours(p, facing)) {
                if (out.contains(n)) continue;

                BlockState ns = ClusterRecalcSafety.getBlockState(world, n);
                if (!ns.isOf(this)) continue;
                if (ns.get(FACING) != facing) continue;
                if (isManualLocked(world, n) != locked) continue;

                if (!ClusterRecalcSafety.claim(n)) return out;
                out.add(n);
                q.add(n);
            }
        }
        return out;
    }

    private void handleManualLockChanged(World world, BlockPos pos, Direction facing, boolean locked) {
        if (!locked) {
            reflowConnectedAutoComponent(world, pos);
            return;
        }

        for (BlockPos neighbourPos : planeNeighbours(pos, facing)) {
            BlockState neighbourState = world.getBlockState(neighbourPos);
            if (!neighbourState.isOf(this) || neighbourState.get(FACING) != facing) {
                continue;
            }
            if (isManualLocked(world, neighbourPos)) {
                continue;
            }
            reflowConnectedAutoComponent(world, neighbourPos);
        }
    }

    private static boolean isManualLocked(World world, BlockPos pos) {
        return ClusterManualLockState.isLocked(world, MANUAL_LOCK_SCOPE, pos);
    }

    private static boolean toggleManualLock(World world, BlockPos pos) {
        return ClusterManualLockState.toggleLocked(world, MANUAL_LOCK_SCOPE, pos);
    }

    private static void clearManualLock(World world, BlockPos pos) {
        if (ClusterManualLockState.isPreservedForSwap(pos)) {
            return;
        }
        ClusterManualLockState.clear(world, MANUAL_LOCK_SCOPE, pos);
    }
    private static Set<BlockPos> planeNeighbours(BlockPos pos, Direction facing) {
        Direction right = facing.rotateYClockwise();
        Direction left = facing.rotateYCounterclockwise();
        return Set.of(
                pos.up(),
                pos.down(),
                pos.offset(left),
                pos.offset(right)
        );
    }

    private static boolean mirrorSwapsLeftRight(Direction facing, BlockMirror mirror) {
        return mirror != BlockMirror.NONE;
    }

    private static Piece swapLeftRight(Piece piece) {
        return switch (piece) {
            case UPPER_MULTI_LH -> Piece.UPPER_MULTI_RH;
            case UPPER_MULTI_RH -> Piece.UPPER_MULTI_LH;
            case LOWER_MULTI_LH -> Piece.LOWER_MULTI_RH;
            case LOWER_MULTI_RH -> Piece.LOWER_MULTI_LH;
            default -> piece;
        };
    }

    private static BlockPos pickBottomLeft(Set<BlockPos> positions, Direction rightDir) {
        BlockPos best = null;
        int bestY = Integer.MAX_VALUE;
        int bestU = Integer.MAX_VALUE;

        for (BlockPos p : positions) {
            int y = p.getY();
            int u = axisCoord(p, rightDir);
            if (y < bestY || (y == bestY && u < bestU)) {
                best = p;
                bestY = y;
                bestU = u;
            }
        }
        return best;
    }

    private static Rect growMaxRect(BlockPos bottomLeft, Set<BlockPos> available, Direction facing, Direction rightDir) {
        int maxW = 0;
        while (available.contains(bottomLeft.offset(rightDir, maxW))) {
            maxW++;
        }
        if (maxW <= 0) maxW = 1;

        int bestW = 1;
        int bestH = 1;
        int bestArea = 1;

        for (int w = maxW; w >= 1; w--) {
            int h = 1;
            while (nextRowFilled(bottomLeft, available, rightDir, w, h)) {
                h++;
            }
            h = Math.max(1, h - 1);

            int area = w * h;
            if (area > bestArea || (area == bestArea && w > bestW)) {
                bestArea = area;
                bestW = w;
                bestH = h;
            }
        }

        return new Rect(bottomLeft, bestW, bestH, facing);
    }

    private static boolean nextRowFilled(BlockPos bottomLeft, Set<BlockPos> available, Direction rightDir, int width, int height) {
        int y = bottomLeft.getY() + height;
        for (int dx = 0; dx < width; dx++) {
            BlockPos p = bottomLeft.offset(rightDir, dx).withY(y);
            if (!available.contains(p)) return false;
        }
        return true;
    }

    private static int axisCoord(BlockPos pos, Direction dir) {
        return switch (dir) {
            case EAST -> pos.getX();
            case WEST -> -pos.getX();
            case SOUTH -> pos.getZ();
            case NORTH -> -pos.getZ();
            default -> 0;
        };
    }

    private static final class ClusterPartition {
        final List<Rect> rects;
        final Direction facing;

        ClusterPartition(List<Rect> rects, Direction facing) {
            this.rects = rects;
            this.facing = facing;
        }

        Rect rectContaining(BlockPos pos) {
            for (Rect r : rects) {
                if (r.contains(pos)) return r;
            }
            return null;
        }
    }

    private static final class Rect {
        final BlockPos bottomLeft;
        final int width;
        final int height;
        final Direction facing;
        final Direction rightDir;

        Rect(BlockPos bottomLeft, int width, int height, Direction facing) {
            this.bottomLeft = bottomLeft;
            this.width = width;
            this.height = height;
            this.facing = facing;
            this.rightDir = facing.rotateYClockwise();
        }

        boolean contains(BlockPos p) {
            if (p.getY() < bottomLeft.getY() || p.getY() >= bottomLeft.getY() + height) return false;

            int u0 = axisCoord(bottomLeft, rightDir);
            int u1 = axisCoord(p, rightDir);
            int dx = u1 - u0;
            return dx >= 0 && dx < width;
        }

        Iterable<BlockPos> iterate() {
            ArrayList<BlockPos> out = new ArrayList<>(width * height);
            for (int dy = 0; dy < height; dy++) {
                int y = bottomLeft.getY() + dy;
                for (int dx = 0; dx < width; dx++) {
                    out.add(bottomLeft.offset(rightDir, dx).withY(y));
                }
            }
            return out;
        }
    }

    private DoorHinge getDoorLikeHinge(ItemPlacementContext ctx, Direction facing) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();

        BlockPos leftPos = pos.offset(left);
        BlockPos rightPos = pos.offset(right);

        BlockState leftState = world.getBlockState(leftPos);
        BlockState rightState = world.getBlockState(rightPos);

        BlockPos leftUp = leftPos.up();
        BlockPos rightUp = rightPos.up();

        BlockState leftUpState = world.getBlockState(leftUp);
        BlockState rightUpState = world.getBlockState(rightUp);

        int score = 0;
        if (leftState.isOpaqueFullCube(world, leftPos)) score--;
        if (leftUpState.isOpaqueFullCube(world, leftUp)) score--;
        if (rightState.isOpaqueFullCube(world, rightPos)) score++;
        if (rightUpState.isOpaqueFullCube(world, rightUp)) score++;

        boolean leftSame = leftState.isOf(this) && leftState.get(FACING) == facing;
        boolean rightSame = rightState.isOf(this) && rightState.get(FACING) == facing;

        if (leftSame && !rightSame) return DoorHinge.RIGHT;
        if (rightSame && !leftSame) return DoorHinge.LEFT;

        if (score > 0) return DoorHinge.LEFT;
        if (score < 0) return DoorHinge.RIGHT;

        Vec3d hit = ctx.getHitPos();
        double hitX = hit.x - pos.getX();
        double hitZ = hit.z - pos.getZ();

        boolean clickedLeft = switch (facing) {
            case NORTH -> hitX < 0.5;
            case SOUTH -> hitX > 0.5;
            case WEST -> hitZ > 0.5;
            case EAST -> hitZ < 0.5;
            default -> true;
        };

        return clickedLeft ? DoorHinge.LEFT : DoorHinge.RIGHT;
    }

    public enum Piece implements StringIdentifiable {
        UPPER_SINGLE("upper_single"),
        LOWER_SINGLE("lower_single"),
        UPPER_MULTI_LH("upper_multi_lh"),
        UPPER_MULTI_MID("upper_multi_mid"),
        UPPER_MULTI_RH("upper_multi_rh"),
        LOWER_MULTI_LH("lower_multi_lh"),
        LOWER_MULTI_MID("lower_multi_mid"),
        LOWER_MULTI_RH("lower_multi_rh");
        private final String id;
        Piece(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    private static VoxelShape makeClosedLowerSingleShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.125), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedUpperSingleShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedLowerMultiLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.125), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedLowerMultiMidShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0006249999999999867, 0, 0, 0.999375, 1, 0.125), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedLowerMultiRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.125), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedUpperMultiLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedUpperMultiMidShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeClosedUpperMultiRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenLowerSingleLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.03125, 1, 0.125), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.875, 0, 0, 0.9999999999999999, 1, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenLowerSingleRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.12499999999999989, 0.96875, 1), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.96875, 0, 0, 1, 1, 0.125), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenUpperSingleLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8753747009594354, 0, 0.125, 1.0003747009594353, 0.5, 1), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenUpperSingleRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0003747009594353701, 0, 0.125, 0.12537470095943526, 0.5, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenLowerMultiLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.875, 0, 0, 0.9999999999999999, 1, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenLowerMultiRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenUpperMultiLhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8753747009594354, 0, 0, 1.0003747009594353, 0.5, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenUpperMultiMidShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.46875, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeOpenUpperMultiRhShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, -7.152557435219364e-9, 1, 1, 0.12499999284744256), BooleanBiFunction.OR);
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.0003747009594353701, 0, 0, 0.12462529904056463, 0.5, 1), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeSillShape() {
        VoxelShape shape = VoxelShapes.empty();
            shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0, 0, 1, 0.03125, 0.125), BooleanBiFunction.OR);
        return shape;
    }

}
