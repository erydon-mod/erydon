package com.oliver.erydon.block;
import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;


/**
 * Core logic container for the modular Georgian surround.
 *
 * This class deliberately starts as a skeleton:
 * - Blockstate schema (facing/section) is defined.
 * - Collision-shape plumbing is in place.
 * - Cluster / layout hooks are declared but not fully implemented yet.
 *
 * We will fill in the cluster simulation and spreadsheet-driven layout logic
 * in later passes.
 */
public class SurroundBlock extends HorizontalFacingBlock implements ClusterRebuildableBlock {

    private static final String MANUAL_LOCK_SCOPE = ClusterManualLockState.SURROUND_SCOPE;

    // --- Blockstate properties ------------------------------------------------

    // FACING comes from HorizontalFacingBlock.FACING

    public static final EnumProperty<Section> SECTION =
            EnumProperty.of("section", Section.class);

    public static final TagKey<Block> SURROUND_PARTS_TAG =
            TagKey.of(RegistryKeys.BLOCK, new Identifier("erydon", "surround_parts"));

    // --- Static voxel shapes (local, facing NORTH) ----------------------------

    private static final VoxelShape SHAPE_EMPTY                = VoxelShapes.empty();

    private static final VoxelShape SHAPE_CORBEL                = makeGeorgianCorbelShape();
    private static final VoxelShape SHAPE_HEARTH                = makeGeorgianHearthShape();
    private static final VoxelShape SHAPE_HEARTH_STUB_LH        = makeGeorgianHearthStubLhShape();
    private static final VoxelShape SHAPE_HEARTH_STUB_RH        = makeGeorgianHearthStubRhShape();
    private static final VoxelShape SHAPE_MANTEL                = makeGeorgianMantleShape();
    private static final VoxelShape SHAPE_MANTEL_STUB_LH        = makeGeorgianMantleStubLhShape();
    private static final VoxelShape SHAPE_MANTEL_STUB_RH        = makeGeorgianMantleStubRhShape();
    private static final VoxelShape SHAPE_PLINTH                = makeGeorgianPlinthShape();
    private static final VoxelShape SHAPE_SHAFT                 = makeGeorgianShaftShape();
    private static final VoxelShape SHAPE_SHORT_JAMB            = makeGeorgianShortJambShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL          = makeGeorgianShortMantleShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL_STUB_LH  = makeGeorgianShortMantleStubLhShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL_STUB_RH  = makeGeorgianShortMantleStubRhShape();

    private static final int FACING_COUNT = 4;
    private final VoxelShape[][] shapeCache =
            new VoxelShape[Section.values().length][FACING_COUNT];

    private static int facingIndex(Direction f) {
        return switch (f) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }


    // --- Shape factories ------------------------------------------------------

    private static VoxelShape makeGeorgianCorbelShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15625, 0.65625, 0, 0.84375, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.09375, 0.78125, 0, 0.90625, 1, 0.5));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21875, 0, 0, 0.78125, 0.65625, 0.375));

        return shape;
    }

    private static VoxelShape makeGeorgianHearthShape(){
        VoxelShape shape = VoxelShapes.empty();
        // From surround_georgian_hearth.txt – slightly deeper and taller hearth
        shape = VoxelShapes.union(shape,
                VoxelShapes.cuboid(0.0, 0.0, 0.000625, 1.0, 0.125625, 0.635));

        return shape;
    }

    private static VoxelShape makeGeorgianHearthStubLhShape(){
        VoxelShape shape = VoxelShapes.empty();
        // Left stub: same depth/height as hearth, only left 1/4 of the block
        shape = VoxelShapes.union(shape,
                VoxelShapes.cuboid(0.0, 0.0, 0.000625, 0.25, 0.125625, 0.635));

        return shape;
    }

    private static VoxelShape makeGeorgianHearthStubRhShape(){
        VoxelShape shape = VoxelShapes.empty();
        // Right stub: same depth/height as hearth, right 1/4 of the block
        shape = VoxelShapes.union(shape,
                VoxelShapes.cuboid(0.75, 0.0, 0.000625, 1.0, 0.125625, 0.635));

        return shape;
    }

    private static VoxelShape makeGeorgianMantleShape(){
        VoxelShape shape = VoxelShapes.empty();

        // From surround_georgian_mantle.txt – five stacked "steps" of mantel
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.78125, 0.0, 1.0, 1.0, 0.5));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.65625, 0.0, 1.0, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.53125, 0.0, 1.0, 0.65625, 0.375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.421875, 0.0, 1.0, 0.53125, 0.328125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.28125, 0.0, 1.0, 0.421875, 0.234375));

        return shape;
    }


    private static VoxelShape makeGeorgianMantleStubLhShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.65625, 0, 0.25, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.78125, 0, 0.25, 1, 0.5));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.046875, 0.484375, 0.421875, 0.25, 0.625, 0.484375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1875, 0.1875, 0.421875, 0.28125, 0.546875, 0.484375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21875, 0.25, 0.421875, 0.25, 0.484375, 0.484375));

        return shape;
    }

    private static VoxelShape makeGeorgianMantleStubRhShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.65625, 0, 1, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.78125, 0, 1, 1, 0.5));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.484375, 0.421875, 0.953125, 0.625, 0.484375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.71875, 0.1875, 0.421875, 0.8125, 0.546875, 0.484375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.25, 0.421875, 0.78125, 0.484375, 0.484375));

        return shape;
    }

    private static VoxelShape makeGeorgianPlinthShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15625, 0, 0, 0.84375, 0.125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21875, 0.125, 0, 0.78125, 1, 0.375));

        return shape;
    }

    private static VoxelShape makeGeorgianShaftShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21875, 0, 0, 0.78125, 1, 0.375));

        return shape;
    }

    private static VoxelShape makeGeorgianShortJambShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15625, -0.03125, 0, 0.84375, 0.125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15625, 0.65625, 0, 0.84375, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.09375, 0.78125, 0, 0.90625, 1, 0.5));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21875, 0.125, 0, 0.78125, 0.65625, 0.375));

        return shape;
    }

    private static VoxelShape makeGeorgianShortMantleShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.65625, 0, 1, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.78125, 0, 1, 1, 0.5));

        return shape;
    }

    private static VoxelShape makeGeorgianShortMantleStubLhShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.65625, 0, 0.25, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.78125, 0, 0.25, 1, 0.5));

        return shape;
    }

    private static VoxelShape makeGeorgianShortMantleStubRhShape(){
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.65625, 0, 1, 0.78125, 0.4375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.78125, 0, 1, 1, 0.5));

        return shape;
    }


    public SurroundBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                // Default visual: 1×1 short surround (from the spreadsheet)
                .with(SECTION, Section.SHORT_JAMB));
    }

    private static Direction[] getPlaneAdjacencyDirs(Direction facing) {
        // Surround is a 2D vertical plane:
        //  - vertical neighbours: UP/DOWN
        //  - horizontal neighbours: left/right relative to facing (perpendicular to facing)
        Direction left  = facing.rotateYClockwise();
        Direction right = facing.rotateYCounterclockwise();
        return new Direction[] { Direction.UP, Direction.DOWN, left, right };
    }

    // --- Enums / small value types -------------------------------------------

    /**
     * All individual physical pieces the surround block can represent
     * in either its outer or inner slot.
     */
    public enum PieceType implements StringIdentifiable {
        NONE("none"),

        CORBEL("corbel"),
        CORBEL_LH("corbel_lh"),
        CORBEL_RH("corbel_rh"),
        MANTEL("mantel"),
        MANTEL_STUB_LH("mantel_stub_lh"),
        MANTEL_STUB_RH("mantel_stub_rh"),

        SHAFT("shaft"),
        SHAFT_LH("shaft_lh"),
        SHAFT_RH("shaft_rh"),
        PLINTH("plinth"),
        PLINTH_LH("plinth_lh"),
        PLINTH_RH("plinth_rh"),

        SHORT_JAMB("short_jamb"),
        SHORT_JAMB_LH("short_jamb_lh"),
        SHORT_JAMB_RH("short_jamb_rh"),
        SHORT_MANTEL("short_mantel"),
        SHORT_MANTEL_STUB_LH("short_mantel_stub_lh"),
        SHORT_MANTEL_STUB_RH("short_mantel_stub_rh"),

        HEARTH("hearth"),
        HEARTH_STUB_LH("hearth_stub_lh"),
        HEARTH_STUB_RH("hearth_stub_rh");

        private final String name;

        PieceType(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }
    }

    /**
     * Concrete outer/inner combinations serialized into blockstate.
     */
    public enum Section implements StringIdentifiable {
        EMPTY("empty", PieceType.NONE, PieceType.NONE),

        CORBEL("corbel", PieceType.CORBEL, PieceType.NONE),
        CORBEL_LH("corbel_lh", PieceType.CORBEL_LH, PieceType.NONE),
        CORBEL_RH("corbel_rh", PieceType.CORBEL_RH, PieceType.NONE),
        MANTEL("mantel", PieceType.MANTEL, PieceType.NONE),

        PLINTH("plinth", PieceType.PLINTH, PieceType.NONE),
        PLINTH_LH("plinth_lh", PieceType.PLINTH_LH, PieceType.NONE),
        PLINTH_RH("plinth_rh", PieceType.PLINTH_RH, PieceType.NONE),

        SHAFT("shaft", PieceType.SHAFT, PieceType.NONE),
        SHAFT_LH("shaft_lh", PieceType.SHAFT_LH, PieceType.NONE),
        SHAFT_RH("shaft_rh", PieceType.SHAFT_RH, PieceType.NONE),

        SHORT_JAMB("short_jamb", PieceType.SHORT_JAMB, PieceType.NONE),
        SHORT_JAMB_LH("short_jamb_lh", PieceType.SHORT_JAMB_LH, PieceType.NONE),
        SHORT_JAMB_RH("short_jamb_rh", PieceType.SHORT_JAMB_RH, PieceType.NONE),
        SHORT_MANTEL("short_mantel", PieceType.SHORT_MANTEL, PieceType.NONE),

        HEARTH("hearth", PieceType.NONE, PieceType.HEARTH),

        CORBEL_MANTEL_STUB_LH("corbel_mantel_stub_lh", PieceType.CORBEL, PieceType.MANTEL_STUB_LH),
        CORBEL_MANTEL_STUB_RH("corbel_mantel_stub_rh", PieceType.CORBEL, PieceType.MANTEL_STUB_RH),
        CORBEL_LH_MANTEL_STUB_LH("corbel_lh_mantel_stub_lh", PieceType.CORBEL_LH, PieceType.MANTEL_STUB_LH),
        CORBEL_LH_MANTEL_STUB_RH("corbel_lh_mantel_stub_rh", PieceType.CORBEL_LH, PieceType.MANTEL_STUB_RH),
        CORBEL_RH_MANTEL_STUB_LH("corbel_rh_mantel_stub_lh", PieceType.CORBEL_RH, PieceType.MANTEL_STUB_LH),
        CORBEL_RH_MANTEL_STUB_RH("corbel_rh_mantel_stub_rh", PieceType.CORBEL_RH, PieceType.MANTEL_STUB_RH),

        PLINTH_HEARTH_STUB_LH("plinth_hearth_stub_lh", PieceType.PLINTH, PieceType.HEARTH_STUB_LH),
        PLINTH_HEARTH_STUB_RH("plinth_hearth_stub_rh", PieceType.PLINTH, PieceType.HEARTH_STUB_RH),
        PLINTH_LH_HEARTH_STUB_LH("plinth_lh_hearth_stub_lh", PieceType.PLINTH_LH, PieceType.HEARTH_STUB_LH),
        PLINTH_RH_HEARTH_STUB_RH("plinth_rh_hearth_stub_rh", PieceType.PLINTH_RH, PieceType.HEARTH_STUB_RH),

        SHORT_JAMB_SHORT_MANTEL_STUB_LH("short_jamb_short_mantel_stub_lh", PieceType.SHORT_JAMB, PieceType.SHORT_MANTEL_STUB_LH),
        SHORT_JAMB_SHORT_MANTEL_STUB_RH("short_jamb_short_mantel_stub_rh", PieceType.SHORT_JAMB, PieceType.SHORT_MANTEL_STUB_RH),
        SHORT_JAMB_LH_SHORT_MANTEL_STUB_LH("short_jamb_lh_short_mantel_stub_lh", PieceType.SHORT_JAMB_LH, PieceType.SHORT_MANTEL_STUB_LH),
        SHORT_JAMB_LH_SHORT_MANTEL_STUB_RH("short_jamb_lh_short_mantel_stub_rh", PieceType.SHORT_JAMB_LH, PieceType.SHORT_MANTEL_STUB_RH),
        SHORT_JAMB_RH_SHORT_MANTEL_STUB_LH("short_jamb_rh_short_mantel_stub_lh", PieceType.SHORT_JAMB_RH, PieceType.SHORT_MANTEL_STUB_LH),
        SHORT_JAMB_RH_SHORT_MANTEL_STUB_RH("short_jamb_rh_short_mantel_stub_rh", PieceType.SHORT_JAMB_RH, PieceType.SHORT_MANTEL_STUB_RH);

        private static final Map<Long, Section> BY_PIECES = new HashMap<>();

        static {
            for (Section section : values()) {
                BY_PIECES.put(pieceKey(section.outerPiece, section.innerPiece), section);
            }
        }

        private final String name;
        public final PieceType outerPiece;
        public final PieceType innerPiece;

        Section(String name, PieceType outerPiece, PieceType innerPiece) {
            this.name = name;
            this.outerPiece = outerPiece;
            this.innerPiece = innerPiece;
        }

        @Override
        public String asString() {
            return this.name;
        }

        public boolean isEmpty() {
            return this == EMPTY;
        }

        public static Section fromPieces(PieceType outerPiece, PieceType innerPiece) {
            Section section = BY_PIECES.get(pieceKey(outerPiece, innerPiece));
            if (section == null) {
                throw new IllegalArgumentException("Unsupported surround section: outer=" + outerPiece.asString()
                        + ", inner=" + innerPiece.asString());
            }
            return section;
        }

        private static long pieceKey(PieceType outerPiece, PieceType innerPiece) {
            return (((long) outerPiece.ordinal()) << 32) | (innerPiece.ordinal() & 0xffffffffL);
        }
    }

    /**
     * Classification of a single logical grid cell according to the layout rules.
     * A cell may be:
     *  - VALID  : contains some combination of outer/inner pieces.
     *  - EMPTY  : logically part of the firebox opening (no geometry, replaceable).
     *  - INVALID: not allowed for that (width, height, x, y) combination.
     */
    public enum LayoutKind {
        VALID,
        EMPTY,
        INVALID
    }

    /**
     * The layout engine's decision for a single grid cell.
     */
    public static final class LayoutCell {
        public final LayoutKind kind;
        public final PieceType outerPiece;
        public final PieceType innerPiece;

        private LayoutCell(LayoutKind kind, PieceType outerPiece, PieceType innerPiece) {
            this.kind = kind;
            this.outerPiece = outerPiece;
            this.innerPiece = innerPiece;
        }

        public static LayoutCell valid(PieceType outer, PieceType inner) {
            Objects.requireNonNull(outer, "outer");
            Objects.requireNonNull(inner, "inner");
            return new LayoutCell(LayoutKind.VALID, outer, inner);
        }

        public static LayoutCell empty() {
            return new LayoutCell(LayoutKind.EMPTY, PieceType.NONE, PieceType.NONE);
        }

        public static LayoutCell invalid() {
            return new LayoutCell(LayoutKind.INVALID, PieceType.NONE, PieceType.NONE);
        }

        public boolean isEmpty() {
            return this.kind == LayoutKind.EMPTY;
        }

        public boolean isInvalid() {
            return this.kind == LayoutKind.INVALID;
        }

        public boolean isValid() {
            return this.kind == LayoutKind.VALID;
        }
    }

    private static Section getSectionForCell(LayoutCell cell) {
        if (cell.kind == LayoutKind.INVALID) {
            throw new IllegalArgumentException("Invalid surround layout cell cannot be serialized");
        }
        return Section.fromPieces(cell.outerPiece, cell.innerPiece);
    }

    /**
     * Simple record storing information about a discovered surround cluster.
     *
     * localX / localY here are *grid* coordinates in the surround's
     * local 2D layout space (x = left->right, y = top->bottom) – not world coords.
     */
    public static final class ClusterInfo {
        public final Set<BlockPos> blocks = new HashSet<>();
        public final Direction facing;

        public record LocalPos(int x, int y) {}
        public final Map<BlockPos, LocalPos> localCoords = new HashMap<>();

        public int minLocalX;
        public int maxLocalX;
        public int minLocalY;
        public int maxLocalY;

        public ClusterInfo(Direction facing) {
            this.facing = facing;
            this.minLocalX = Integer.MAX_VALUE;
            this.maxLocalX = Integer.MIN_VALUE;
            this.minLocalY = Integer.MAX_VALUE;
            this.maxLocalY = Integer.MIN_VALUE;
        }

        public void include(BlockPos pos, int localX, int localY) {
            blocks.add(pos);
            localCoords.put(pos, new LocalPos(localX, localY));

            if (localX < minLocalX) minLocalX = localX;
            if (localX > maxLocalX) maxLocalX = localX;
            if (localY < minLocalY) minLocalY = localY;
            if (localY > maxLocalY) maxLocalY = localY;
        }

        public int getWidth() {
            return (blocks.isEmpty() || minLocalX == Integer.MAX_VALUE) ? 0 : (maxLocalX - minLocalX + 1);
        }

        public int getHeight() {
            return (blocks.isEmpty() || minLocalY == Integer.MAX_VALUE) ? 0 : (maxLocalY - minLocalY + 1);
        }
    }


    // --- Blockstate / placement plumbing -------------------------------------

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SECTION);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        Section section = state.get(SECTION);

        if (mirror != BlockMirror.NONE) {
            section = mirrorSection(section);
        }

        return rotate(state, mirror.getRotation(facing)).with(SECTION, section);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Default: place facing the player
        Direction facing = ctx.getHorizontalPlayerFacing();

        // If we're attaching to an existing surround cluster, inherit its facing
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        for (Direction dir : EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN)) {
            BlockState neighbour = world.getBlockState(pos.offset(dir));
            if (isSurroundBlock(neighbour)) {
                facing = neighbour.get(FACING);
                break;
            }
        }

        return this.getDefaultState()
                .with(FACING, facing)
                // 1×1 short surround as the starting layout; auto-reflow will overwrite as needed.
                .with(SECTION, Section.SHORT_JAMB);
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        // Any logical "empty" cell should be replaceable so players can build through the opening.
        if (isEmptyState(state)) {
            return true;
        }
        return super.canReplace(state, context);
    }


    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient) {
            clearManualLock(world, pos);
            // We will later implement the simulation-first merging logic here.
            // For now this is just a hook.
            handleClusterChangeOnPlaced(world, pos, state);
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);

        if (!world.isClient && !state.isOf(newState.getBlock())) {
            clearManualLock(world, pos);
            // A surround block was removed / changed into something else.
            // We need to re-evaluate the remaining neighbouring clusters.
            handleClusterChangeOnRemoved(world, pos, state);
        }
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        // Standard JSON/BakedModel rendering.
        return BlockRenderType.MODEL;
    }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState neighborState, Direction direction) {
        // Only cull faces against blocks we explicitly tag as surround parts
        if (!neighborState.isIn(SURROUND_PARTS_TAG)) {
            return super.isSideInvisible(state, neighborState, direction);
        }

        // Don’t cull into the “opening”/empty cells (prevents weird see-through from the front)
        if (isEmptyState(state) || isEmptyState(neighborState)) {
            return super.isSideInvisible(state, neighborState, direction);
        }

        // Only cull within the surround plane: UP/DOWN + left/right relative to FACING
        Direction facing = state.get(FACING);

        boolean inPlane = false;
        for (Direction d : getPlaneAdjacencyDirs(facing)) {
            if (d == direction) {
                inPlane = true;
                break;
            }
        }
        if (!inPlane) {
            return super.isSideInvisible(state, neighborState, direction);
        }

        // Extra safety: only cull if the neighbour is aligned to the same plane system
        if (neighborState.contains(FACING) && neighborState.get(FACING) == facing) {
            return true;
        }

        return super.isSideInvisible(state, neighborState, direction);
    }

    // --- Use / debug-stick hook ----------------------------------------------

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);

        // Only care about debug stick
        if (!isDebugStick(held)) {
            return ActionResult.PASS;
        }

        if (player.isSneaking()) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }

            boolean locked = toggleManualLock(world, pos);
            if (!locked) {
                handleClusterChangeOnPlaced(world, pos, state);
            }
            player.sendMessage(Text.literal("Surround mode: " + (locked ? "manual" : "auto")), true);
            return ActionResult.SUCCESS;
        }

        // Client: just show hand animation, server does the logic
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        Section next = getNextManualSection(state.get(SECTION));
        world.setBlockState(pos, state.with(SECTION, next), Block.NOTIFY_LISTENERS);
        return ActionResult.SUCCESS;
    }

    private boolean isDebugStick(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        // Vanilla debug stick (creative-only). Replace/extend this later
        // if you make a custom surround-debug item.
        return stack.getItem() == Items.DEBUG_STICK;
    }


    // --- Collision / outline shapes ------------------------------------------

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world,
                                      BlockPos pos, ShapeContext context) {
        // Even an "empty" surround cell should still be targetable so we can
        // interact with it (debug stick, F3, etc). Collision is handled separately.
        if (isEmptyState(state)) {
            return VoxelShapes.fullCube();
        }

        return getWorldSpaceShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world,
                                        BlockPos pos, ShapeContext context) {
        return getWorldSpaceShape(state);
    }
    private VoxelShape getWorldSpaceShape(BlockState state) {
        if (isEmptyState(state)) {
            return SHAPE_EMPTY;
        }

        Direction facing = state.get(FACING);
        Section section = state.get(SECTION);
        int fi = facingIndex(facing);

        VoxelShape cached = this.shapeCache[section.ordinal()][fi];
        if (cached != null) {
            return cached;
        }

        PieceType outer = section.outerPiece;
        PieceType inner = section.innerPiece;
        VoxelShape baseOuter = getBaseShapeForPiece(outer);
        VoxelShape baseInner = getBaseShapeForPiece(inner);

        VoxelShape combined = VoxelShapes.union(baseOuter, baseInner);

        // Rotate NORTH-authored shapes into the block's actual FACING.
        VoxelShape rotated = rotateShapeFromNorthToFacing(combined, facing).simplify();

        this.shapeCache[section.ordinal()][fi] = rotated;
        return rotated;
    }


    private static boolean isEmptyState(BlockState state) {
        return state.get(SECTION).isEmpty();
    }

    /**
     * Returns the un-rotated shape for a single piece type, assuming
     * the block is facing NORTH.
     */
    protected VoxelShape getBaseShapeForPiece(PieceType piece) {
        return switch (piece) {
            case NONE -> SHAPE_EMPTY;

            case CORBEL -> SHAPE_CORBEL;
            case MANTEL -> SHAPE_MANTEL;
            case MANTEL_STUB_LH -> SHAPE_MANTEL_STUB_LH;
            case MANTEL_STUB_RH -> SHAPE_MANTEL_STUB_RH;

            case SHAFT -> SHAPE_SHAFT;
            case PLINTH -> SHAPE_PLINTH;

            case SHORT_JAMB -> SHAPE_SHORT_JAMB;
            case SHORT_MANTEL -> SHAPE_SHORT_MANTEL;
            case SHORT_MANTEL_STUB_LH -> SHAPE_SHORT_MANTEL_STUB_LH;
            case SHORT_MANTEL_STUB_RH -> SHAPE_SHORT_MANTEL_STUB_RH;

            case HEARTH -> SHAPE_HEARTH;
            case HEARTH_STUB_LH -> SHAPE_HEARTH_STUB_LH;
            case HEARTH_STUB_RH -> SHAPE_HEARTH_STUB_RH;

            case CORBEL_LH, CORBEL_RH -> SHAPE_CORBEL;
            case SHAFT_LH, SHAFT_RH -> SHAPE_SHAFT;
            case PLINTH_LH, PLINTH_RH -> SHAPE_PLINTH;
            case SHORT_JAMB_LH, SHORT_JAMB_RH -> SHAPE_SHORT_JAMB;

        };
    }

    /**
     * Rotates a shape defined for FACING = NORTH into the requested facing.
     *
     * Shapes are defined in local block coordinates [0,1]x[0,1]x[0,1].
     * We rotate around the Y axis so that:
     *  - NORTH  -> no rotation
     *  - EAST   -> 90° clockwise
     *  - SOUTH  -> 180°
     *  - WEST   -> 270°
     */
    private static VoxelShape rotateShapeFromNorthToFacing(VoxelShape shape, Direction facing) {
        if (shape.isEmpty()) {
            return shape;
        }

        // Only horizontal facings matter here
        if (facing == Direction.NORTH) {
            return shape;
        }

        int rotations;
        switch (facing) {
            case EAST -> rotations = 1;
            case SOUTH -> rotations = 2;
            case WEST -> rotations = 3;
            default -> {
                // Up/Down should never happen for this block, but just in case:
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
     * Rotates the given shape 90 degrees clockwise around the Y axis,
     * in block-local coordinates (0..1).
     *
     * Mapping for each box:
     *   (x, z) -> (1 - z, x)
     */
    private static VoxelShape rotateShape90Y(VoxelShape shape) {
        if (shape.isEmpty()) {
            return shape;
        }

        final VoxelShape[] result = { VoxelShapes.empty() };

        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // Rotate 90° clockwise around Y:
            // newX = 1 - z, newZ = x  (applied to both min/max)
            double newMinX = 1.0 - maxZ;
            double newMaxX = 1.0 - minZ;
            double newMinZ = minX;
            double newMaxZ = maxX;

            VoxelShape rotatedBox = VoxelShapes.cuboid(
                    newMinX, minY, newMinZ,
                    newMaxX, maxY, newMaxZ
            );
            result[0] = VoxelShapes.union(result[0], rotatedBox);
        });

        return result[0];
    }

    // --- Cluster / layout entry points ---------------------------------------

    /**
     * Called on the server when a surround block is first placed.
     *
     * Current simple behaviour:
     *  1. Discover the connected surround cluster (4-way adjacency, same FACING)
     *     that includes this newly placed block.
     *  2. For now, clear OUTER/INNER pieces on all AUTO blocks in that cluster.
     *
     * This is a minimal placeholder so the plumbing is in place.
     * We'll replace this with the full spreadsheet-driven layout logic later.
     */
    private void handleClusterChangeOnPlaced(World world, BlockPos pos, BlockState state) {
        ClusterInfo cluster = discoverCluster(world, pos);
        if (cluster == null || cluster.blocks.isEmpty()) {
            return;
        }
        reflowCluster(world, cluster);
    }



    /**
     * Called on the server when a surround block is removed or replaced by another block.
     *
     * Current simple behaviour:
     *  - For each neighbouring surround block (4-way adjacency, same FACING),
     *    discover its connected cluster and clear OUTER/INNER pieces on all AUTO
     *    blocks in that cluster.
     *
     * This keeps things deterministic without committing to the final spreadsheet
     * layout logic yet.
     */
    private void handleClusterChangeOnRemoved(World world, BlockPos pos, BlockState oldState) {
        // Removal can split a cluster into multiple components. Reflow each neighbouring component.
        Set<BlockPos> processed = new HashSet<>();

        for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN }) {
            BlockPos neighbourPos = pos.offset(dir);
            if (processed.contains(neighbourPos)) {
                continue;
            }

            BlockState neighbourState = world.getBlockState(neighbourPos);
            if (!isSurroundBlock(neighbourState)) {
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
     * Applies the spreadsheet-derived layout to every block in the cluster.
     *
     * Rules:
     *  - If any block maps to an INVALID layout cell, the entire reflow is aborted.
     */
    private void reflowCluster(World world, ClusterInfo cluster) {
        computeLocalCoordinates(cluster);

        final int width = cluster.getWidth();
        final int height = cluster.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!hasUnlockedBlocks(world, cluster)) {
            return;
        }
        final boolean hearthEnabled = shouldEnableHearth(cluster, width, height);

        // Validate first (simulation-first safety hook)

        for (BlockPos p : cluster.blocks) {
            BlockState st = world.getBlockState(p);
            if (!isSurroundBlock(st) || st.get(FACING) != cluster.facing) {
                continue;
            }
            if (isManualLocked(world, p)) {
                continue;
            }
            ClusterInfo.LocalPos lp = cluster.localCoords.get(p);
            if (lp == null) {
                continue;
            }

            LayoutCell cell = computeLayoutCell(width, height, lp.x(), lp.y(), hearthEnabled);
            if (cell.kind == LayoutKind.INVALID) {
                return; // abort entire reflow
            }
        }

        // Apply to the full cluster
        for (BlockPos p : cluster.blocks) {
            BlockState st = world.getBlockState(p);
            if (!isSurroundBlock(st) || st.get(FACING) != cluster.facing) {
                continue;
            }
            ClusterInfo.LocalPos lp = cluster.localCoords.get(p);
            if (lp == null) {
                continue;
            }

            LayoutCell cell = computeLayoutCell(width, height, lp.x(), lp.y(), hearthEnabled);
            BlockState newState = st.with(SECTION, getSectionForCell(cell));

            if (!newState.equals(st)) {
                world.setBlockState(p, newState,
                        ClusterRecalcSafety.updateFlags(Block.NOTIFY_LISTENERS));
            }
        }
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
        if (!hasUnlockedBlocks(world, cluster)) {
            return new ClusterRecalcResult(positions, false);
        }
        reflowCluster(world, cluster);
        return new ClusterRecalcResult(positions, true);
    }

    private boolean hasUnlockedBlocks(World world, ClusterInfo cluster) {
        for (BlockPos pos : cluster.blocks) {
            BlockState state = world.getBlockState(pos);
            if (!isSurroundBlock(state) || state.get(FACING) != cluster.facing) {
                continue;
            }
            if (!isManualLocked(world, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hearth should only appear for large clusters (3x3+) when the entire bottom row is present.
     * If any bottom-row block is missing, we disable hearth for the whole bottom row.
     */
    private boolean shouldEnableHearth(ClusterInfo cluster, int width, int height) {
        if (width < 3 || height < 3) {
            return true; // preserve existing behaviour for smaller clusters
        }

        int bottomY = height - 1;
        boolean[] present = new boolean[width];

        for (ClusterInfo.LocalPos lp : cluster.localCoords.values()) {
            if (lp == null) continue;
            if (lp.y() == bottomY && lp.x() >= 0 && lp.x() < width) {
                present[lp.x()] = true;
            }
        }

        for (boolean b : present) {
            if (!b) return false; // bottom row has a gap
        }

        return true;
    }


    // --- Cluster discovery helpers (skeleton) --------------------------------

    /**
     * Convenience predicate: is this state our surround block?
     */
    private boolean isSurroundBlock(BlockState state) {
        return state.getBlock() == this;
    }

    /**
     * Collects a single connected surround cluster (4-way adjacency) starting from
     * the given seed position. All blocks in the cluster must share the same FACING
     * as the seed.
     *
     * NOTE: This method only discovers the set of block positions; the mapping into
     * local grid coordinates (x,y) is delegated to {@link #computeLocalCoordinates}.
     */
    private ClusterInfo discoverCluster(WorldAccess world, BlockPos seedPos) {
        BlockState seedState = ClusterRecalcSafety.getBlockState(world, seedPos);
        if (!isSurroundBlock(seedState)) {
            return null;
        }

        Direction facing = seedState.get(FACING);
        ClusterInfo cluster = new ClusterInfo(facing);

        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        visited.add(seedPos);
        queue.add(seedPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.remove();
            BlockState currentState = ClusterRecalcSafety.getBlockState(world, current);

            if (!isSurroundBlock(currentState) || currentState.get(FACING) != facing) {
                continue;
            }

            // For now we just record membership; local grid coords come later.
            // We temporarily use 0,0 as placeholders – {@link #computeLocalCoordinates}
            // will rebuild accurate localX/localY later when we know the bounds.
            if (!ClusterRecalcSafety.claim(current)) {
                break;
            }
            cluster.blocks.add(current);

            for (Direction dir : getPlaneAdjacencyDirs(facing)) {
                BlockPos neighbourPos = current.offset(dir);
                if (!visited.contains(neighbourPos)) {
                    visited.add(neighbourPos);
                    queue.add(neighbourPos);
                }
            }
        }

        // Local coordinate fields will be filled after we compute bounds.
        return cluster;
    }

    /**
     * Computes local grid coordinates (x,y) for every block in the cluster, and
     * updates the ClusterInfo's bounding box fields accordingly.
     *
     * The mapping is:
     *  - x axis: left -> right as the player faces the surround.
     *  - y axis: top -> bottom (world Y decreasing).
     *
     * The coordinate origin (0,0) is the top-left block in the cluster.
     */
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

        // +X in grid space is "to the right" as the player faces the surround.
        Direction rightDir = cluster.facing.rotateYClockwise();
        int dx = rightDir.getOffsetX();
        int dz = rightDir.getOffsetZ();

        // Determine origin (top-left) in projected coordinates.
        int topY = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        int bottomY = Integer.MAX_VALUE;

        // Also enforce the cluster is planar (1 block thick) in the facing axis.
        Direction forwardDir = cluster.facing;
        int fx = forwardDir.getOffsetX();
        int fz = forwardDir.getOffsetZ();
        Integer depth = null;

        for (BlockPos p : positions) {
            topY = Math.max(topY, p.getY());
            bottomY = Math.min(bottomY, p.getY());

            int col = p.getX() * dx + p.getZ() * dz;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);

            int d = p.getX() * fx + p.getZ() * fz;
            if (depth == null) {
                depth = d;
            } else if (depth != d) {
                // Non-planar: keep coordinates empty so we don't reflow into nonsense.
                cluster.blocks.addAll(positions);
                return;
            }
        }

        // Build local coords and bounds
        for (BlockPos p : positions) {
            int col = p.getX() * dx + p.getZ() * dz;
            int localX = col - minCol;
            int localY = topY - p.getY();

            cluster.include(p, localX, localY);
        }
    }


    // --- Layout helper (spreadsheet-driven, skeleton) ------------------------

    /**
     * Spreadsheet-driven layout function.
     *
     * Given a surround rectangle of (width, height) in grid cells, and a particular
     * cell coordinate (x,y) within that rectangle (0-based, with x increasing left->right
     * and y increasing top->bottom), returns the logical content of that cell:
     *
     *  - VALID  : specific outer/inner pieces.
     *  - EMPTY  : logical firebox opening, no geometry, replaceable.
     *  - INVALID: the cell is not allowed for that overall size.
     *
     * For now this returns a very permissive placeholder; we will replace this
     * with the real rules derived from the spreadsheet in a later step.
     */
    protected PieceType mapOuterForPosition(PieceType baseOuter, int x, int y, int width, int height) {
        return baseOuter; // default (Georgian behaviour unchanged)
    }

    private LayoutCell computeLayoutCell(int width, int height, int x, int y, boolean hearthEnabled) {
        // Defensive bounds
        if (width <= 0 || height <= 0) {
            return LayoutCell.invalid();
        }
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return LayoutCell.invalid();
        }

        boolean singleWidth = (width == 1);
        boolean leftEdge = (x == 0);
        boolean rightEdge = (x == width - 1);
        boolean edge = leftEdge || rightEdge;

        boolean topRow = (y == 0);
        boolean bottomRow = (y == height - 1);
        // --- Height 1: short surround variants --------------------------------
        if (height == 1) {
            if (singleWidth) {
                // A single "short jamb" block (no short mantel stubs possible)
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.SHORT_JAMB, x, y, width, height),
                        PieceType.NONE
                );
            }

            if (leftEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.SHORT_JAMB, x, y, width, height),
                        PieceType.SHORT_MANTEL_STUB_RH
                );
            }
            if (rightEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.SHORT_JAMB, x, y, width, height),
                        PieceType.SHORT_MANTEL_STUB_LH
                );
            }

            // Middle
            return LayoutCell.valid(PieceType.SHORT_MANTEL, PieceType.NONE);
        }

        // --- Standard rules (height >= 2) ---------------------------------------

        // Top row: corbels at edges + mantel stubs, mantel in middle
        if (topRow) {
            if (singleWidth) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.CORBEL, x, y, width, height),
                        PieceType.NONE
                );
            }

            if (leftEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.CORBEL, x, y, width, height),
                        PieceType.MANTEL_STUB_RH
                );
            }
            if (rightEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.CORBEL, x, y, width, height),
                        PieceType.MANTEL_STUB_LH
                );
            }

            return LayoutCell.valid(PieceType.MANTEL, PieceType.NONE);
        }

        // Bottom row: plinth at edges + hearth stubs, hearth in middle
        if (bottomRow) {
            if (singleWidth) {
                // width==1 has no opening/middle; use plinth (bottom) instead of hearth
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.PLINTH, x, y, width, height),
                        PieceType.NONE
                );
            }

            // NEW RULE:
            // For clusters 3x3+, only show hearth (and hearth stubs) if the entire bottom row is present.
            // If hearthEnabled is false, render plinth edges with NO stubs, and no hearth in the middle.
            if (!hearthEnabled) {
                if (leftEdge) {
                    return LayoutCell.valid(
                            mapOuterForPosition(PieceType.PLINTH, x, y, width, height),
                            PieceType.NONE
                    );
                }

                if (rightEdge) {
                    return LayoutCell.valid(
                            mapOuterForPosition(PieceType.PLINTH, x, y, width, height),
                            PieceType.NONE
                    );
                }

                // middle cells: no hearth
                return LayoutCell.empty();
            }

            // Normal behaviour (hearth enabled)
            if (leftEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.PLINTH, x, y, width, height),
                        PieceType.HEARTH_STUB_LH
                );
            }

            if (rightEdge) {
                return LayoutCell.valid(
                        mapOuterForPosition(PieceType.PLINTH, x, y, width, height),
                        PieceType.HEARTH_STUB_RH
                );
            }

            return LayoutCell.valid(PieceType.NONE, PieceType.HEARTH);
        }

        // Middle rows: shafts on edges, empty opening in the middle
        if (singleWidth || edge) {
            return LayoutCell.valid(
                    mapOuterForPosition(PieceType.SHAFT, x, y, width, height),
                    PieceType.NONE
            );
        }

        // Interior opening
        return LayoutCell.empty();
    }


    // --- Debug section cycling -----------------------------------------

    /**
     * The list of (outer, inner) combinations we cycle through when using
     * the debug stick.
     *
     * You can add/remove entries here as you refine what "allowed" manual
     * sections should be.
     */
    private static final Section[] MANUAL_SECTIONS = new Section[] {
            // Completely empty (no geometry)
            Section.EMPTY,

            // Simple single-piece variants
            Section.CORBEL,
            Section.PLINTH,
            Section.SHAFT,
            Section.MANTEL,
            Section.HEARTH,

            // Edge combos: corbel + mantel stub, plinth + hearth stub
            Section.CORBEL_MANTEL_STUB_RH,
            Section.CORBEL_MANTEL_STUB_LH,
            Section.PLINTH_HEARTH_STUB_LH,
            Section.PLINTH_HEARTH_STUB_RH,

            // Short variants for 1-high surrounds
            Section.SHORT_JAMB,
            Section.SHORT_MANTEL,
            Section.SHORT_JAMB_SHORT_MANTEL_STUB_RH,
            Section.SHORT_JAMB_SHORT_MANTEL_STUB_LH,
    };

    /**
     * Find the index of the current (outer, inner) pair inside MANUAL_SECTIONS.
     * Returns -1 if not found.
     */

    protected Section[] getManualSections() {
        return MANUAL_SECTIONS;
    }

    private int findManualSectionIndex(Section section) {
        Section[] sections = getManualSections();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == section) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the "next" manual section after the given (outer, inner) pair.
     * If the pair is not present in MANUAL_SECTIONS, we start at index 0.
     */
    private Section getNextManualSection(Section currentSection) {
        Section[] sections = getManualSections();
        int currentIndex = findManualSectionIndex(currentSection);
        if (currentIndex < 0) {
            return sections[0];
        }
        int nextIndex = (currentIndex + 1) % sections.length;
        return sections[nextIndex];
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

    private static Section mirrorSection(Section section) {
        return Section.fromPieces(
                mirrorPiece(section.outerPiece),
                mirrorPiece(section.innerPiece)
        );
    }

    private static PieceType mirrorPiece(PieceType piece) {
        return switch (piece) {
            case CORBEL_LH -> PieceType.CORBEL_RH;
            case CORBEL_RH -> PieceType.CORBEL_LH;
            case MANTEL_STUB_LH -> PieceType.MANTEL_STUB_RH;
            case MANTEL_STUB_RH -> PieceType.MANTEL_STUB_LH;
            case SHAFT_LH -> PieceType.SHAFT_RH;
            case SHAFT_RH -> PieceType.SHAFT_LH;
            case PLINTH_LH -> PieceType.PLINTH_RH;
            case PLINTH_RH -> PieceType.PLINTH_LH;
            case SHORT_JAMB_LH -> PieceType.SHORT_JAMB_RH;
            case SHORT_JAMB_RH -> PieceType.SHORT_JAMB_LH;
            case SHORT_MANTEL_STUB_LH -> PieceType.SHORT_MANTEL_STUB_RH;
            case SHORT_MANTEL_STUB_RH -> PieceType.SHORT_MANTEL_STUB_LH;
            case HEARTH_STUB_LH -> PieceType.HEARTH_STUB_RH;
            case HEARTH_STUB_RH -> PieceType.HEARTH_STUB_LH;
            default -> piece;
        };
    }
}
