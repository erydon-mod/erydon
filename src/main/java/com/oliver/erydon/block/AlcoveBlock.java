package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
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
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
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

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

public class AlcoveBlock extends HorizontalFacingBlock implements Waterloggable, ClusterRebuildableBlock {
    private static final String SPAN_OVERRIDE_SCOPE = ClusterManualLockState.ALCOVE_SCOPE + "_span";
    private static final String WIDTH_OVERRIDE_SCOPE = ClusterManualLockState.ALCOVE_SCOPE + "_width";

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<AlcovePart> PART = EnumProperty.of("part", AlcovePart.class);
    public static final EnumProperty<AlcoveSpan> SPAN = EnumProperty.of("span", AlcoveSpan.class);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final VoxelShape[] SHAPES = new VoxelShape[AlcovePart.values().length * AlcoveSpan.values().length * 4];
    private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);
    private final int maxClusterWidth;

    public AlcoveBlock(Settings settings, int maxClusterWidth) {
        super(settings);
        if (maxClusterWidth != 2 && maxClusterWidth != 3) {
            throw new IllegalArgumentException("Alcove maxClusterWidth must be 2 or 3");
        }
        this.maxClusterWidth = maxClusterWidth;
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.SOUTH)
                .with(PART, AlcovePart.SINGLE)
                .with(SPAN, AlcoveSpan.SINGLE)
                .with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, SPAN, WATERLOGGED);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        Direction facing = inheritedFacing(world, pos, ctx.getHorizontalPlayerFacing().getOpposite());
        FluidState fluid = world.getFluidState(pos);

        return recompute(this.getDefaultState()
                .with(FACING, facing)
                .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER), world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient || isSyncing() || oldState.isOf(this)) {
            return;
        }
        updateClusterAround(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        boolean sameBlock = state.isOf(newState.getBlock());
        if (!world.isClient && !sameBlock && !isSyncing()) {
            clearSpanOverride(world, pos);
            clearWidthOverride(world, pos);
            updateClustersNearRemovedBlock(world, pos, state.get(FACING));
        }
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
        return recompute(state, world, pos);
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
        return rotate(state, mirror.getRotation(state.get(FACING)));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getShape(state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getShape(state);
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        if (!held.isOf(Items.DEBUG_STICK)) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        Set<BlockPos> component = collectComponent(world, pos, state.get(FACING));
        if (component.isEmpty()) {
            return ActionResult.PASS;
        }

        AlcoveClusterWidth current = inferWidthOverride(world, component);
        AlcoveClusterWidth next = current.next(player.isSneaking(), maxClusterWidth);
        applyWidthOverride(world, component, next);
        updateClusterAround(world, pos);
        player.sendMessage(Text.literal("Alcove cluster width: " + next.debugLabel()), true);
        return ActionResult.CONSUME;
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> component = collectComponent(world, seed, seedState.get(FACING));
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        boolean changed = false;
        boolean started = beginSync();
        try {
            synchronizeWidthOverride(world, component);
            for (BlockPos pos : component) {
                BlockState state = world.getBlockState(pos);
                BlockState updated = recompute(state, world, pos);
                if (!updated.equals(state)) {
                    world.setBlockState(pos, updated, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                    changed = true;
                }
            }
        } finally {
            endSync(started);
        }
        return new ClusterRecalcResult(component, changed || ClusterRecalcSafety.isActive());
    }

    private static Direction inheritedFacing(WorldAccess world, BlockPos pos, Direction fallback) {
        BlockState below = loadedState(world, pos.down());
        if (below.getBlock() instanceof AlcoveBlock && below.contains(FACING)) {
            return below.get(FACING);
        }
        BlockState above = loadedState(world, pos.up());
        if (above.getBlock() instanceof AlcoveBlock && above.contains(FACING)) {
            return above.get(FACING);
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockState side = loadedState(world, pos.offset(direction));
            if (side.getBlock() instanceof AlcoveBlock && side.contains(FACING)) {
                Direction sideFacing = side.get(FACING);
                if (direction == frontLeftDirection(sideFacing) || direction == frontRightDirection(sideFacing)) {
                    return sideFacing;
                }
            }
        }
        return fallback;
    }

    private void updateClusterAround(World world, BlockPos pos) {
        boolean started = beginSync();
        try {
            updateComponent(world, pos);
        } finally {
            endSync(started);
        }
    }

    private void updateClustersNearRemovedBlock(World world, BlockPos pos, Direction facing) {
        boolean started = beginSync();
        try {
            updateComponent(world, pos.up());
            updateComponent(world, pos.down());
            updateComponent(world, pos.offset(frontLeftDirection(facing)));
            updateComponent(world, pos.offset(frontRightDirection(facing)));
        } finally {
            endSync(started);
        }
    }

    private void updateComponent(World world, BlockPos seed) {
        BlockState state = world.getBlockState(seed);
        if (!state.isOf(this)) {
            return;
        }

        Set<BlockPos> component = collectComponent(world, seed, state.get(FACING));
        synchronizeWidthOverride(world, component);
        for (BlockPos componentPos : component) {
            BlockState componentState = world.getBlockState(componentPos);
            BlockState updated = recompute(componentState, world, componentPos);
            if (!updated.equals(componentState)) {
                world.setBlockState(componentPos, updated, Block.NOTIFY_ALL);
            }
        }
    }

    private BlockState recompute(BlockState state, WorldAccess world, BlockPos pos) {
        if (world instanceof World blockWorld && blockWorld.isClient) {
            return state;
        }

        Direction facing = state.get(FACING);
        boolean sameBelow = sameClusterBlock(loadedState(world, pos.down()), facing);
        boolean sameAbove = sameClusterBlock(loadedState(world, pos.up()), facing);

        AlcovePart part;
        if (sameBelow && sameAbove) {
            part = AlcovePart.MIDDLE;
        } else if (sameAbove) {
            part = AlcovePart.BASE;
        } else if (sameBelow) {
            part = AlcovePart.TOP;
        } else {
            part = AlcovePart.SINGLE;
        }

        AlcoveClusterWidth widthOverride = getWidthOverride(world, pos);
        AlcoveSpan span;
        if (widthOverride != AlcoveClusterWidth.AUTO) {
            span = automaticSpan(world, pos, facing, widthOverride.effectiveMaxWidth(maxClusterWidth));
        } else {
            span = getSpanOverride(world, pos);
            if (span == null || (maxClusterWidth < 3 && span.isTriple())) {
                span = automaticSpan(world, pos, facing, maxClusterWidth);
            }
        }
        return state.with(PART, part).with(SPAN, span);
    }

    private AlcoveSpan automaticSpan(WorldAccess world, BlockPos pos, Direction facing, int partitionWidth) {
        Direction leftDirection = frontLeftDirection(facing);
        Direction rightDirection = frontRightDirection(facing);
        int blocksToLeft = contiguousCount(world, pos, leftDirection, facing);
        int blocksToRight = contiguousCount(world, pos, rightDirection, facing);
        AlcoveRunPartition.Segment segment = AlcoveRunPartition.segmentAt(
                blocksToLeft + 1 + blocksToRight,
                blocksToLeft,
                partitionWidth);
        return switch (segment.width()) {
            case 1 -> AlcoveSpan.SINGLE;
            case 2 -> segment.index() == 0 ? AlcoveSpan.LEFT : AlcoveSpan.RIGHT;
            case 3 -> switch (segment.index()) {
                case 0 -> AlcoveSpan.TRIPLE_LEFT;
                case 1 -> AlcoveSpan.TRIPLE_CENTER;
                default -> AlcoveSpan.TRIPLE_RIGHT;
            };
            default -> throw new IllegalStateException("Unsupported alcove segment width: " + segment.width());
        };
    }

    private int contiguousCount(WorldAccess world, BlockPos origin, Direction direction, Direction facing) {
        int count = 0;
        BlockPos.Mutable cursor = origin.mutableCopy();
        while (sameClusterBlock(loadedState(world, cursor.move(direction)), facing)) {
            count++;
        }
        return count;
    }

    private boolean sameClusterBlock(BlockState state, Direction facing) {
        return state.isOf(this) && state.contains(FACING) && state.get(FACING) == facing;
    }

    private Set<BlockPos> collectComponent(WorldAccess world, BlockPos seed, Direction facing) {
        Set<BlockPos> component = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);

        while (!queue.isEmpty()) {
            BlockPos current = queue.remove();
            if (component.contains(current)
                    || !sameClusterBlock(loadedState(world, current), facing)) {
                continue;
            }
            if (!ClusterRecalcSafety.claim(current)) {
                break;
            }
            component.add(current);
            queue.add(current.up());
            queue.add(current.down());
            queue.add(current.offset(frontLeftDirection(facing)));
            queue.add(current.offset(frontRightDirection(facing)));
        }
        return component;
    }

    static Direction frontLeftDirection(Direction facing) {
        return facing.rotateYClockwise();
    }

    static Direction frontRightDirection(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    private static BlockState loadedState(WorldAccess world, BlockPos pos) {
        if (world instanceof World blockWorld && !blockWorld.isChunkLoaded(pos)) {
            return Blocks.VOID_AIR.getDefaultState();
        }
        return ClusterRecalcSafety.getBlockState(world, pos);
    }

    private static boolean beginSync() {
        if (SYNCING.get()) {
            return false;
        }
        SYNCING.set(true);
        return true;
    }

    private static void endSync(boolean started) {
        if (started) {
            SYNCING.set(false);
        }
    }

    private static boolean isSyncing() {
        return SYNCING.get();
    }

    private static void applyWidthOverride(World world,
                                           Set<BlockPos> component,
                                           AlcoveClusterWidth width) {
        for (BlockPos componentPos : component) {
            clearSpanOverride(world, componentPos);
            setWidthOverride(world, componentPos, width);
        }
    }

    private static void synchronizeWidthOverride(World world, Set<BlockPos> component) {
        AlcoveClusterWidth width = inferWidthOverride(world, component);
        for (BlockPos componentPos : component) {
            setWidthOverride(world, componentPos, width);
        }
    }

    private static AlcoveClusterWidth inferWidthOverride(WorldAccess world, Set<BlockPos> component) {
        int[] counts = new int[AlcoveClusterWidth.values().length];
        for (BlockPos componentPos : component) {
            AlcoveClusterWidth width = getWidthOverride(world, componentPos);
            if (width != AlcoveClusterWidth.AUTO) {
                counts[width.ordinal()]++;
            }
        }

        AlcoveClusterWidth selected = AlcoveClusterWidth.AUTO;
        int selectedCount = 0;
        for (AlcoveClusterWidth width : AlcoveClusterWidth.values()) {
            if (counts[width.ordinal()] > selectedCount) {
                selected = width;
                selectedCount = counts[width.ordinal()];
            }
        }
        return selected;
    }

    private static void setWidthOverride(World world, BlockPos pos, AlcoveClusterWidth width) {
        ClusterManualLockState.setInt(world, WIDTH_OVERRIDE_SCOPE, pos, width.storedValue());
    }

    private static void clearWidthOverride(WorldAccess world, BlockPos pos) {
        if (ClusterManualLockState.isPreservedForSwap(pos)) {
            return;
        }
        ClusterManualLockState.clearInt(world, WIDTH_OVERRIDE_SCOPE, pos);
    }

    private static AlcoveClusterWidth getWidthOverride(WorldAccess world, BlockPos pos) {
        return AlcoveClusterWidth.fromStoredValue(
                ClusterManualLockState.getInt(world, WIDTH_OVERRIDE_SCOPE, pos));
    }

    private static void setSpanOverride(World world, BlockPos pos, AlcoveSpan span) {
        ClusterManualLockState.setInt(world, SPAN_OVERRIDE_SCOPE, pos, spanOverrideValue(span));
    }

    private static void clearSpanOverride(WorldAccess world, BlockPos pos) {
        if (ClusterManualLockState.isPreservedForSwap(pos)) {
            return;
        }
        ClusterManualLockState.clearInt(world, SPAN_OVERRIDE_SCOPE, pos);
    }

    private static AlcoveSpan getSpanOverride(WorldAccess world, BlockPos pos) {
        return switch (ClusterManualLockState.getInt(world, SPAN_OVERRIDE_SCOPE, pos)) {
            case 1 -> AlcoveSpan.SINGLE;
            case 2 -> AlcoveSpan.LEFT;
            case 3 -> AlcoveSpan.RIGHT;
            case 4 -> AlcoveSpan.TRIPLE_LEFT;
            case 5 -> AlcoveSpan.TRIPLE_CENTER;
            case 6 -> AlcoveSpan.TRIPLE_RIGHT;
            default -> null;
        };
    }

    private static int spanOverrideValue(AlcoveSpan span) {
        return switch (span) {
            case SINGLE -> 1;
            case LEFT -> 2;
            case RIGHT -> 3;
            case TRIPLE_LEFT -> 4;
            case TRIPLE_CENTER -> 5;
            case TRIPLE_RIGHT -> 6;
        };
    }

    private static VoxelShape getShape(BlockState state) {
        AlcovePart part = state.get(PART);
        AlcoveSpan span = state.get(SPAN);
        int turns = turnsFromSouth(state.get(FACING));
        int index = (part.ordinal() * AlcoveSpan.values().length + span.ordinal()) * 4 + turns;
        VoxelShape shape = SHAPES[index];
        if (shape == null) {
            shape = rotateShapeY(makeSouthShape(part, span), turns).simplify();
            SHAPES[index] = shape;
        }
        return shape;
    }

    private static VoxelShape makeSouthShape(AlcovePart part, AlcoveSpan span) {
        double minX = switch (span) {
            case RIGHT, TRIPLE_CENTER -> -16.0;
            case TRIPLE_RIGHT -> -32.0;
            default -> 0.0;
        };
        double maxX = switch (span) {
            case LEFT, TRIPLE_CENTER -> 32.0;
            case TRIPLE_LEFT -> 48.0;
            default -> 16.0;
        };

        VoxelShape shape = VoxelShapes.union(
                cuboidUnits(minX, 0.0, 0.0, maxX, 16.0, 0.125),
                cuboidUnits(minX + 0.0467, 0.0, 6.58509, maxX - 0.0479, 16.0, 6.60389),
                cuboidUnits(minX, 0.0, 0.03978, minX + 0.6148, 16.0, 16.0),
                cuboidUnits(maxX - 0.64147, 0.0, 0.05984, maxX, 16.0, 16.0));

        if (part == AlcovePart.SINGLE || part == AlcovePart.BASE) {
            shape = VoxelShapes.union(shape, cuboidUnits(minX, 0.0, 0.0, maxX, 0.6415, 16.0));
        }
        if (part == AlcovePart.SINGLE || part == AlcovePart.TOP) {
            shape = VoxelShapes.union(shape, cuboidUnits(minX, 14.976, 0.0, maxX, 16.0, 16.0));
        }
        return shape;
    }

    private static VoxelShape cuboidUnits(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return VoxelShapes.cuboid(minX / 16.0, minY / 16.0, minZ / 16.0, maxX / 16.0, maxY / 16.0, maxZ / 16.0);
    }

    private static int turnsFromSouth(Direction facing) {
        return switch (facing) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeY(VoxelShape shape, int turns) {
        VoxelShape rotated = shape;
        for (int i = 0; i < turns; i++) {
            rotated = rotateShape90Y(rotated);
        }
        return rotated;
    }

    private static VoxelShape rotateShape90Y(VoxelShape shape) {
        final VoxelShape[] rotated = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                rotated[0] = VoxelShapes.union(rotated[0],
                        VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)));
        return rotated[0];
    }

    public enum AlcovePart implements StringIdentifiable {
        SINGLE("single"),
        BASE("base"),
        MIDDLE("middle"),
        TOP("top");

        private final String name;

        AlcovePart(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum AlcoveSpan implements StringIdentifiable {
        SINGLE("single"),
        LEFT("left"),
        RIGHT("right"),
        TRIPLE_LEFT("triple_left"),
        TRIPLE_CENTER("triple_center"),
        TRIPLE_RIGHT("triple_right");

        private final String name;

        AlcoveSpan(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }

        public boolean isTriple() {
            return this == TRIPLE_LEFT || this == TRIPLE_CENTER || this == TRIPLE_RIGHT;
        }
    }

    public enum AlcoveClusterWidth implements StringIdentifiable {
        AUTO("auto", 0),
        SINGLE("single", 1),
        DOUBLE("double", 2),
        TRIPLE("triple", 3);

        private static final AlcoveClusterWidth[] UP_TO_DOUBLE = {AUTO, SINGLE, DOUBLE};
        private final String name;
        private final int width;

        AlcoveClusterWidth(String name, int width) {
            this.name = name;
            this.width = width;
        }

        @Override
        public String asString() {
            return name;
        }

        int storedValue() {
            return width;
        }

        int effectiveMaxWidth(int automaticMaxWidth) {
            return this == AUTO ? automaticMaxWidth : Math.min(width, automaticMaxWidth);
        }

        AlcoveClusterWidth next(boolean reverse, int supportedMaxWidth) {
            AlcoveClusterWidth[] cycle = supportedMaxWidth >= 3 ? values() : UP_TO_DOUBLE;
            int currentIndex = 0;
            for (int index = 0; index < cycle.length; index++) {
                if (cycle[index] == this) {
                    currentIndex = index;
                    break;
                }
            }
            int step = reverse ? -1 : 1;
            return cycle[Math.floorMod(currentIndex + step, cycle.length)];
        }

        String debugLabel() {
            return this == AUTO ? "automatic" : Integer.toString(width);
        }

        static AlcoveClusterWidth fromStoredValue(int value) {
            return switch (value) {
                case 1 -> SINGLE;
                case 2 -> DOUBLE;
                case 3 -> TRIPLE;
                default -> AUTO;
            };
        }
    }
}
