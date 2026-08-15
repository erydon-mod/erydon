package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
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

import java.util.LinkedHashSet;
import java.util.Set;

public class ColumnBlock extends Block implements ClusterRebuildableBlock {
    private static final String MANUAL_LOCK_SCOPE = ClusterManualLockState.COLUMN_SCOPE;

    public static final EnumProperty<ColumnPart> PART = EnumProperty.of("part", ColumnPart.class);
    public static final EnumProperty<CapitalStyle> CAPITAL = EnumProperty.of("capital", CapitalStyle.class);
    public static final EnumProperty<BaseStyle> BASE = EnumProperty.of("base", BaseStyle.class);

    private static final VoxelShape BASE_SHAPE = VoxelShapes.union(
            VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 2.0/16.0, 1.0),
            VoxelShapes.cuboid(0.1, 2.0/16.0, 0.1, 0.9, 6.0/16.0, 0.9),
            VoxelShapes.cuboid(0.15, 6.0/16.0, 0.15, 0.85, 1.0, 0.85)
    );

    // If your plinth has different collision/outline later, swap this constant.
    private static final VoxelShape PLINTH_SHAPE = BASE_SHAPE;

    private static final VoxelShape PILLAR_SHAPE =
            VoxelShapes.cuboid(0.15, 0.0, 0.15, 0.85, 1.0, 0.85);

    private static final VoxelShape CAPITAL_SHAPE = VoxelShapes.union(
            VoxelShapes.cuboid(0.15, 0.0, 0.15, 0.85, 10.0/16.0, 0.85),
            VoxelShapes.cuboid(0.1, 10.0/16.0, 0.1, 0.9, 14.0/16.0, 0.9),
            VoxelShapes.cuboid(0.0, 14.0/16.0, 0.0, 1.0, 1.0, 1.0)
    );

    private static final VoxelShape GOTHIC_COLUMN_SHAPE = makeGothicColumnShape();

    public ColumnBlock(Settings settings) {
        super(settings);
        this.setDefaultState(
                this.stateManager.getDefaultState()
                        .with(PART, ColumnPart.BASE)
                        .with(CAPITAL, CapitalStyle.GEORGIAN)
                        .with(BASE, BaseStyle.FULL)
        );
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PART, CAPITAL, BASE);
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
                player.sendMessage(Text.literal("Column mode: " + (locked ? "manual" : "auto")), true);

                if (!locked) {
                    BlockState updated = recompute(state, world, pos);
                    if (!updated.equals(state)) {
                        world.setBlockState(pos, updated, Block.NOTIFY_ALL);
                    }
                    updateNeighbor(world, pos.up());
                    updateNeighbor(world, pos.down());
                }

                return ActionResult.CONSUME;
            }

            // Allow vanilla debug stick to handle property cycling.
            return ActionResult.PASS;
        }

        if (player.isSneaking() || hit.getSide().getAxis() == Direction.Axis.Y) {
            return ActionResult.PASS;
        }

        if (isFixedStyleColumn(state)) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        ColumnToggleTarget target = getToggleTarget(state, hit);
        BlockPos bottom = findBottom(world, pos);
        BlockPos top = findTop(world, pos);

        if (target == ColumnToggleTarget.CAPITAL) {
            BlockState topState = world.getBlockState(top);
            CapitalStyle next = topState.get(CAPITAL).next(isCircularColumn(topState));
            setCapitalForStack(world, bottom, top, next);
            return ActionResult.SUCCESS;
        }
        if (target == ColumnToggleTarget.BASE) {
            BaseStyle next = world.getBlockState(bottom).get(BASE).next();
            setBaseForStack(world, bottom, top, next);
            return ActionResult.SUCCESS;
        }

        return ActionResult.SUCCESS;
    }

    private BlockPos findBottom(WorldAccess world, BlockPos pos) {
        BlockPos cur = pos;
        while (world.getBlockState(cur.down()).isOf(this)) {
            cur = cur.down();
        }
        return cur;
    }

    private BlockPos findTop(WorldAccess world, BlockPos pos) {
        BlockPos cur = pos;
        while (world.getBlockState(cur.up()).isOf(this)) {
            cur = cur.up();
        }
        return cur;
    }

    private void setCapitalForStack(World world, BlockPos bottom, BlockPos top, CapitalStyle style) {
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos p = new BlockPos(bottom.getX(), y, bottom.getZ());
            BlockState s = world.getBlockState(p);
            if (s.isOf(this)) {
                world.setBlockState(p, s.with(CAPITAL, style), Block.NOTIFY_ALL);
            }
        }
    }

    private void setBaseForStack(World world, BlockPos bottom, BlockPos top, BaseStyle style) {
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos p = new BlockPos(bottom.getX(), y, bottom.getZ());
            BlockState s = world.getBlockState(p);
            if (s.isOf(this)) {
                world.setBlockState(p, s.with(BASE, style), Block.NOTIFY_ALL);
            }
        }
    }

    // Placement: isolated -> PLINTH, otherwise follow normal stack rules
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        boolean sameBelow = world.getBlockState(pos.down()).isOf(this);
        boolean sameAbove = world.getBlockState(pos.up()).isOf(this);

        return this.getDefaultState()
                .with(PART, RecalcSelection.automaticPart(sameBelow, sameAbove));
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (!oldState.isOf(this)) {
            clearManualLock(world, pos);
        }

        // Settle self based on neighbors unless this stack position is manual-locked.
        BlockState updated = recompute(state, world, pos);
        if (updated != state) {
            world.setBlockState(pos, updated, Block.NOTIFY_ALL);
        }
        // Promote/demote neighbors so the stack stabilizes in one tick
        updateNeighbor(world, pos.up());
        updateNeighbor(world, pos.down());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Recompute whenever a vertical neighbor changes (unless mode=manual)
        return recompute(state, world, pos);
    }

    // When broken/replaced, update vertical neighbors so the new top/bottom resolve correctly
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            clearManualLock(world, pos);
            updateNeighbor(world, pos.up());
            updateNeighbor(world, pos.down());
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    private void updateNeighbor(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        if (s.isOf(this)) {
            BlockState ns = recompute(s, world, pos);
            if (ns != s) {
                world.setBlockState(pos, ns, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        boolean locked = isManualLocked(world, seed);
        Set<BlockPos> component = collectVerticalComponent(world, seed, locked);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }

        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        if (locked) {
            return new ClusterRecalcResult(component, false);
        }

        for (BlockPos pos : component) {
            updateNeighbor(world, pos);
        }
        return new ClusterRecalcResult(component, true);
    }

    /** Decide PLINTH/BASE/PILLAR/CAPITAL using same-block neighbors only unless manual-locked. */
    private BlockState recompute(BlockState state, WorldAccess world, BlockPos pos) {
        if (world instanceof World blockWorld && blockWorld.isClient) {
            return state;
        }
        if (isManualLocked(world, pos)) {
            return state; // allow Debug Stick cycling to persist
        }

        boolean sameBelow = world.getBlockState(pos.down()).isOf(this);
        boolean sameAbove = world.getBlockState(pos.up()).isOf(this);

        return withRecalculatedPart(state, sameBelow, sameAbove);
    }

    static BlockState withRecalculatedPart(BlockState state, boolean sameBelow, boolean sameAbove) {
        // /erydon recalc may rebuild the structural part, but player-selected
        // capital and base options must survive unchanged.
        RecalcSelection selection = RecalcSelection.preservingOptions(
                state.get(CAPITAL), state.get(BASE), sameBelow, sameAbove);
        return state.with(PART, selection.part())
                .with(CAPITAL, selection.capital())
                .with(BASE, selection.base());
    }

    private VoxelShape shapeFor(BlockState s) {
        if (isGothicColumn(s)) {
            return GOTHIC_COLUMN_SHAPE;
        }

        if (s.get(PART) == ColumnPart.CAPITAL && !s.get(CAPITAL).hasCapital()) {
            return PILLAR_SHAPE;
        }

        switch (s.get(PART)) {
            case PLINTH: return PLINTH_SHAPE;
            case BASE: return BASE_SHAPE;
            case PILLAR: return PILLAR_SHAPE;
            case CAPITAL:
            default: return CAPITAL_SHAPE;
        }
    }

    private ColumnToggleTarget getToggleTarget(BlockState state, BlockHitResult hit) {
        if (state.get(PART) == ColumnPart.BASE) {
            return ColumnToggleTarget.BASE;
        }
        if (state.get(PART) == ColumnPart.CAPITAL) {
            return ColumnToggleTarget.CAPITAL;
        }

        double localY = hit.getPos().y - hit.getBlockPos().getY();
        return localY <= 0.45 ? ColumnToggleTarget.BASE : ColumnToggleTarget.CAPITAL;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return shapeFor(state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return shapeFor(state);
    }

    public enum ColumnPart implements StringIdentifiable {
        PLINTH("plinth"),
        BASE("base"),
        PILLAR("pillar"),
        CAPITAL("capital");

        private final String name;
        ColumnPart(String n) { this.name = n; }
        @Override public String asString() { return name; }
        @Override public String toString() { return name; }
    }

    static record RecalcSelection(ColumnPart part, CapitalStyle capital, BaseStyle base) {
        static RecalcSelection preservingOptions(CapitalStyle capital, BaseStyle base,
                                                  boolean sameBelow, boolean sameAbove) {
            return new RecalcSelection(automaticPart(sameBelow, sameAbove), capital, base);
        }

        private static ColumnPart automaticPart(boolean sameBelow, boolean sameAbove) {
            if (sameAbove && sameBelow) {
                return ColumnPart.PILLAR;   // middle
            } else if (sameAbove) {
                return ColumnPart.BASE;     // bottom
            } else if (sameBelow) {
                return ColumnPart.CAPITAL;  // top
            } else {
                return ColumnPart.PLINTH;   // isolated
            }
        }
    }

    public enum CapitalStyle implements StringIdentifiable {
        GEORGIAN("georgian"),
        GUILLOCHE("guilloche"),
        NARROW("narrow"),
        NONE("none");

        private final String name;
        CapitalStyle(String n) { this.name = n; }
        @Override public String asString() { return name; }
        @Override public String toString() { return name; }

        public boolean hasCapital() {
            return this != NONE;
        }

        public CapitalStyle next() {
            return next(true);
        }

        public CapitalStyle next(boolean includeNarrow) {
            return switch (this) {
                case GEORGIAN -> GUILLOCHE;
                case GUILLOCHE -> includeNarrow ? NARROW : NONE;
                case NARROW -> NONE;
                case NONE -> GEORGIAN;
            };
        }
    }

    public enum BaseStyle implements StringIdentifiable {
        FULL("full"),
        NARROW("narrow");

        private final String name;
        BaseStyle(String n) { this.name = n; }
        @Override public String asString() { return name; }
        @Override public String toString() { return name; }

        public BaseStyle next() {
            return this == FULL ? NARROW : FULL;
        }
    }

    private enum ColumnToggleTarget {
        BASE,
        CAPITAL
    }

    private static boolean isManualLocked(WorldAccess world, BlockPos pos) {
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

    private static boolean isCircularColumn(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath().contains("column_circular");
    }

    private static boolean isFixedStyleColumn(BlockState state) {
        return isGothicColumn(state);
    }

    private static boolean isGothicColumn(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath().contains("column_gothic");
    }

    private static VoxelShape makeGothicColumnShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.109375, 0, 0.125, 0.890625, 1, 0.875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.060914375, 0, 0.573493125, 0.427301875, 1, 0.939878125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.572888125, 0, 0.05915125, 0.9392725, 1, 0.42553875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.572888125, 0, 0.57446125, 0.9392725, 1, 0.94084875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.060914375, 0, 0.060121875, 0.427301875, 1, 0.426506875));
        return shape;
    }

    private Set<BlockPos> collectVerticalComponent(WorldAccess world, BlockPos seed, boolean locked) {
        Set<BlockPos> component = new LinkedHashSet<>();
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this) || isManualLocked(world, seed) != locked) {
            return component;
        }

        BlockPos bottom = seed;
        while (true) {
            BlockPos next = bottom.down();
            BlockState nextState = ClusterRecalcSafety.getBlockState(world, next);
            if (!nextState.isOf(this) || isManualLocked(world, next) != locked) {
                break;
            }
            bottom = next;
        }

        for (BlockPos current = bottom; ; current = current.up()) {
            BlockState currentState = ClusterRecalcSafety.getBlockState(world, current);
            if (!currentState.isOf(this) || isManualLocked(world, current) != locked) {
                break;
            }
            if (!ClusterRecalcSafety.claim(current)) {
                break;
            }
            component.add(current);
        }

        return component;
    }

}
