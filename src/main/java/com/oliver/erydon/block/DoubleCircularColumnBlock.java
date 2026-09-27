package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One material ID per 2x2 circular column; all four cells share the same ID. */
public final class DoubleCircularColumnBlock extends Block {
    public static final IntProperty X = IntProperty.of("part_x", 0, 1);
    public static final IntProperty Z = IntProperty.of("part_z", 0, 1);
    public static final EnumProperty<Section> SECTION = EnumProperty.of("section", Section.class);
    public static final EnumProperty<ColumnBlock.CapitalStyle> CAPITAL = ColumnBlock.CAPITAL;
    public static final EnumProperty<ColumnBlock.BaseStyle> BASE = ColumnBlock.BASE;

    public DoubleCircularColumnBlock(Settings settings) {
        super(settings.dynamicBounds());
        setDefaultState(getStateManager().getDefaultState()
                .with(X, 0).with(Z, 0).with(SECTION, Section.BASE_LOWER)
                .with(CAPITAL, ColumnBlock.CapitalStyle.GEORGIAN)
                .with(BASE, ColumnBlock.BaseStyle.FULL));
    }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(X, Z, SECTION, CAPITAL, BASE);
    }

    public enum Section implements StringIdentifiable {
        BASE_LOWER("base_lower"), BASE_UPPER("base_upper"), SHAFT("shaft"),
        CAPITAL_LOWER("capital_lower"), CAPITAL_UPPER("capital_upper");
        private final String id;
        Section(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    /** The block cells that form the selected physical section in building tools. */
    public static List<BlockPos> selectionCells(BlockView world, BlockPos hit) {
        BlockState state = world.getBlockState(hit);
        if (!(state.getBlock() instanceof DoubleCircularColumnBlock)) return List.of();
        BlockPos anchor = hit.add(-state.get(X), 0, -state.get(Z));
        Section section = state.get(SECTION);
        int lowerY = switch (section) {
            case BASE_UPPER, CAPITAL_UPPER -> -1;
            default -> 0;
        };
        int upperY = switch (section) {
            case BASE_LOWER, CAPITAL_LOWER -> 1;
            default -> 0;
        };
        List<BlockPos> cells = new ArrayList<>(8);
        for (int dy = lowerY; dy <= upperY; dy++) {
            Section expected = switch (section) {
                case BASE_LOWER, BASE_UPPER -> dy == lowerY ? Section.BASE_LOWER : Section.BASE_UPPER;
                case CAPITAL_LOWER, CAPITAL_UPPER -> dy == lowerY ? Section.CAPITAL_LOWER : Section.CAPITAL_UPPER;
                case SHAFT -> Section.SHAFT;
            };
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                BlockPos cell = anchor.add(dx, dy, dz);
                BlockState part = world.getBlockState(cell);
                if (part.isOf(state.getBlock()) && part.get(X) == dx && part.get(Z) == dz
                        && part.get(SECTION) == expected) cells.add(cell);
            }
        }
        return cells;
    }

    @Override public ActionResult onUse(BlockState state, World world, BlockPos pos,
                                        PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hit.getSide().getAxis() == Direction.Axis.Y || player.isSneaking()) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        BlockPos anchor = pos.add(-state.get(X), 0, -state.get(Z));
        BlockPos bottom = anchor;
        while (world.getBlockState(bottom.down()).isOf(this)) bottom = bottom.down();
        BlockPos top = anchor;
        while (world.getBlockState(top.up()).isOf(this)) top = top.up();
        boolean base = state.get(SECTION) == Section.BASE_LOWER || state.get(SECTION) == Section.BASE_UPPER;
        boolean capital = state.get(SECTION) == Section.CAPITAL_LOWER || state.get(SECTION) == Section.CAPITAL_UPPER;
        if (!base && !capital) base = hit.getPos().y - pos.getY() <= 0.45;
        ColumnBlock.BaseStyle nextBase = base ? world.getBlockState(bottom).get(BASE).next() : null;
        ColumnBlock.CapitalStyle nextCapital = base ? null
                : world.getBlockState(top).get(CAPITAL).next(true);
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                BlockPos cell = new BlockPos(anchor.getX() + x, y, anchor.getZ() + z);
                BlockState current = world.getBlockState(cell);
                if (current.isOf(this)) world.setBlockState(cell,
                        base ? current.with(BASE, nextBase) : current.with(CAPITAL, nextCapital), Block.NOTIFY_ALL);
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override public @Nullable BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        World world = context.getWorld();
        BlockState below = world.getBlockState(pos.down());
        int x = below.isOf(this) ? below.get(X) : 0;
        int z = below.isOf(this) ? below.get(Z) : 0;
        BlockPos anchor = pos.add(-x, 0, -z);
        int height = below.isOf(this) ? 1 : 4;
        if (!ColumnPlacementArea.isClear(anchor, height, cell ->
                !world.isOutOfHeightLimit(cell) && world.getWorldBorder().contains(cell)
                        && world.getBlockState(cell).isAir())) return null;
        BlockState placed = getDefaultState().with(X, x).with(Z, z);
        return below.isOf(this) ? placed.with(BASE, below.get(BASE)).with(CAPITAL, below.get(CAPITAL)) : placed;
    }

    @Override public void onPlaced(World world, BlockPos pos, BlockState state,
                                   @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        BlockPos anchor = pos.add(-state.get(X), 0, -state.get(Z));
        boolean extension = world.getBlockState(anchor.down()).isOf(this);
        int height = extension ? 1 : 4;
        // The primary cell is already placed. Recheck the other cells before changing any of them.
        if (!ColumnPlacementArea.isClear(anchor, height, cell ->
                cell.equals(pos) || (!world.isOutOfHeightLimit(cell)
                        && world.getWorldBorder().contains(cell) && world.getBlockState(cell).isAir()))) {
            world.removeBlock(pos, false);
            return;
        }
        for (int y = 0; y < height; y++) for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            BlockPos cell = anchor.add(dx, y, dz);
            if (cell.equals(pos)) continue;
            world.setBlockState(cell, getDefaultState().with(X, dx).with(Z, dz)
                    .with(BASE, state.get(BASE)).with(CAPITAL, state.get(CAPITAL)), Block.NOTIFY_ALL);
        }
        updateSections(world, anchor);
    }

    private void updateSections(World world, BlockPos anchor) {
        BlockPos bottom = anchor;
        while (world.getBlockState(bottom.down()).isOf(this)) bottom = bottom.down();
        BlockPos top = anchor;
        while (world.getBlockState(top.up()).isOf(this)) top = top.up();
        int length = top.getY() - bottom.getY() + 1;
        for (int y = 0; y < length; y++) {
            Section section = y == 0 ? Section.BASE_LOWER
                    : y == 1 ? Section.BASE_UPPER
                    : y == length - 2 ? Section.CAPITAL_LOWER
                    : y == length - 1 ? Section.CAPITAL_UPPER : Section.SHAFT;
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                BlockPos cell = bottom.add(dx, y, dz);
                BlockState existing = world.getBlockState(cell);
                if (existing.isOf(this) && existing.get(SECTION) != section)
                    world.setBlockState(cell, existing.with(SECTION, section), Block.NOTIFY_ALL);
            }
        }
    }

    @Override public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            BlockPos anchor = pos.add(-state.get(X), 0, -state.get(Z));
            // A player's break removes this horizontal layer, not the full column.
            // Programmatic edits remain independent so Axiom can move the selected cells.
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                BlockPos cell = anchor.add(dx, 0, dz);
                BlockState part = world.getBlockState(cell);
                if (!cell.equals(pos) && part.isOf(this)
                        && part.get(X) == dx && part.get(Z) == dz)
                    world.removeBlock(cell, false);
            }
        }
        super.onBreak(world, pos, state, player);
    }

    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCollisionShape(state, world, pos, context);
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(SECTION)) {
            case BASE_LOWER -> VoxelShapes.union(
                    box(state, 0, 0, 0, 2, 0.25, 2),
                    roundShape(state, 0.12, 0.14, 0.25, 1));
            case BASE_UPPER, SHAFT -> shaftShape(state, 0, 1);
            case CAPITAL_LOWER -> state.get(CAPITAL) == ColumnBlock.CapitalStyle.GUILLOCHE
                    ? VoxelShapes.union(shaftShape(state, 0, 0.5),
                    roundShape(state, 0.04, 0.12, 0.5, 1))
                    : shaftShape(state, 0, 1);
            case CAPITAL_UPPER -> VoxelShapes.union(
                    roundShape(state, 0.12, 0.14, 0, 0.5),
                    box(state, 0, 0.5, 0, 2, 1, 2));
        };
    }

    @Override public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.fullCube();
    }

    private static VoxelShape shaftShape(BlockState state, double minY, double maxY) {
        return VoxelShapes.union(
                box(state, 0.31, minY, 0.53, 1.69, maxY, 1.47),
                box(state, 0.43, minY, 0.36, 1.57, maxY, 1.64));
    }

    private static VoxelShape roundShape(BlockState state, double inset,
                                         double corner, double minY, double maxY) {
        return VoxelShapes.union(
                box(state, inset, minY, inset + corner,
                        2 - inset, maxY, 2 - inset - corner),
                box(state, inset + corner, minY, inset,
                        2 - inset - corner, maxY, 2 - inset));
    }

    private static VoxelShape box(BlockState state, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        int x = state.get(X), z = state.get(Z);
        double x0 = Math.max(0, minX - x), x1 = Math.min(1, maxX - x);
        double z0 = Math.max(0, minZ - z), z1 = Math.min(1, maxZ - z);
        return x0 >= x1 || z0 >= z1 ? VoxelShapes.empty()
                : VoxelShapes.cuboid(x0, minY, z0, x1, maxY, z1);
    }

    @Override public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.empty();
    }

    @Override public BlockState rotate(BlockState state, BlockRotation rotation) {
        int x = state.get(X), z = state.get(Z);
        return switch (rotation) {
            case CLOCKWISE_90 -> state.with(X, 1 - z).with(Z, x);
            case CLOCKWISE_180 -> state.with(X, 1 - x).with(Z, 1 - z);
            case COUNTERCLOCKWISE_90 -> state.with(X, z).with(Z, 1 - x);
            default -> state;
        };
    }

    @Override public BlockState mirror(BlockState state, BlockMirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> state.with(Z, 1 - state.get(Z));
            case FRONT_BACK -> state.with(X, 1 - state.get(X));
            default -> state;
        };
    }
}
