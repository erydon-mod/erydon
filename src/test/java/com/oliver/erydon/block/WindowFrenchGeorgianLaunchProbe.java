package com.oliver.erydon.block;

import com.oliver.erydon.client.model.GeorgianWindowRenderingLaunchChecks;
import com.oliver.erydon.state.ClusterManualLockState;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.oliver.erydon.block.WindowFrenchGeorgianBlock.*;

/** Real persisted states, placement reflow and collision without a game window or save. */
public final class WindowFrenchGeorgianLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            run();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static void run() throws Exception {
        var block = Registry.register(Registries.BLOCK, new Identifier("erydon", "aganite_window_french_georgian"),
                new WindowFrenchGeorgianBlock(AbstractBlock.Settings.create()));
        int states = states(block);
        int layouts = layouts(block);
        cornerAttachment(block);
        WindowDebugControlsLaunchChecks.run(block);
        sillInheritance(block);
        GeorgianWindowRenderingLaunchChecks.run(block);
        System.out.println("ERYDON_GEORGIAN_WINDOWS_OK states=" + states + " layouts=" + layouts
                + " oldStateKeys=preserved closedStraightCollision=single openClusterCentres=clear cornerOpenings=clear clusterSync=preserved cornerAttachment=runEnds sillSide=legacyDefault+clusterSync+placementInheritance");
    }

    private static void cornerAttachment(WindowFrenchGeorgianBlock block) throws Exception {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            TestWorld world = world();
            Direction right = facing.rotateYClockwise();
            for (Piece legacy : Piece.values()) {
                BlockState state = block.getDefaultState().with(FACING, facing).with(PIECE, legacy);
                for (int x = -1; x <= 1; x++) world.states.put(BlockPos.ORIGIN.offset(right, x), state);
                for (Corner corner : new Corner[]{Corner.LEFT, Corner.RIGHT}) {
                    Direction secondary = corner.secondaryFacing(facing);
                    var middle = new AutomaticItemPlacementContext(world, BlockPos.ORIGIN,
                            secondary.getOpposite(), new ItemStack(Items.STONE), secondary);
                    require(block.getPlacementState(middle) == null, "A one-wide column permitted an interior T junction");
                    BlockPos end = BlockPos.ORIGIN.offset(secondary);
                    var exterior = new AutomaticItemPlacementContext(world, end,
                            secondary.getOpposite(), new ItemStack(Items.STONE), secondary);
                    BlockState placed = block.getPlacementState(exterior);
                    require(placed != null && placed.get(CORNER) == corner, "A real run end rejected a corner attachment");
                }
            }
        }
    }

    private static int states(WindowFrenchGeorgianBlock block) throws Exception {
        require(Piece.values().length == 8, "Published piece schema changed");
        require(SillSide.values().length == 2 && block.getDefaultState().get(SILL_SIDE) == SillSide.OUTWARD,
                "The new sill side must default to the published outward appearance");
        require(block.getStateManager().getStates().size() == 3072, "Sill side must only double the published 1536 states");
        Method oldFace = WindowFrenchGeorgianBlock.class.getDeclaredMethod("getWindowFaceShape", BlockState.class,
                Direction.class, Piece.class, DoorHinge.class, boolean.class);
        oldFace.setAccessible(true);
        int checked = 0;
        for (BlockState state : block.getStateManager().getStates()) {
            var saved = NbtHelper.fromBlockState(state);
            require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved) == state,
                    "A piece/glass/hinge/corner/sill-side state no longer round-trips");
            if (state.get(SILL_SIDE) == SillSide.OUTWARD) {
                saved.getCompound("Properties").remove("sill_side");
                require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved) == state,
                        "A published state without sill_side no longer loads as outward");
            }
            Piece expected = state.get(CORNER) == Corner.NONE && !state.get(OPEN)
                    ? state.get(PIECE).single() : state.get(PIECE);
            require(primaryPiece(state) == expected, "Wrong effective primary piece");
            VoxelShape shape = collision(block, state);
            VoxelShape frameOnly = collision(block, state.with(SILL, false).with(SILL_SIDE, SillSide.OUTWARD));
            VoxelShape expectedShape = frameOnly;
            if (state.get(SILL)) {
                expectedShape = VoxelShapes.combine(expectedShape, expectedSill(state.get(FACING), state.get(SILL_SIDE)), BooleanBiFunction.OR);
                if (secondaryFacing(state) != null) expectedShape = VoxelShapes.combine(expectedShape,
                        expectedSill(secondaryFacing(state), state.get(SILL_SIDE)), BooleanBiFunction.OR);
            }
            same(expectedShape, shape, "Changing sill side changed collision outside the sill or lost a corner arm");
            if (state.get(CORNER) == Corner.NONE) {
                if (!state.get(OPEN)) {
                    same(shape, collision(block, state.with(PIECE, state.get(PIECE).single())),
                            "Closed straight collision differs from the authored one-wide window");
                } else {
                    // A wide cluster opens only at the ends. Its centre must be traversable,
                    // even though easy targeting deliberately keeps a full outline/raycast.
                    Piece piece = state.get(PIECE);
                    Direction facing = state.get(FACING);
                    if (piece == Piece.LOWER_MULTI_MID) {
                        if (!state.get(SILL)) require(shape.isEmpty(), "Open lower centre retained collision");
                        else require(shape.getBoundingBoxes().stream().allMatch(box -> box.maxY <= .031251),
                                "Open lower centre retained collision above the sill");
                    }
                    if (piece == Piece.LOWER_MULTI_MID || piece == Piece.UPPER_MULTI_MID) {
                        require(!contains(shape, rotate(.0625, .125, .75, facing))
                                        && !contains(shape, rotate(.9375, .125, .75, facing)),
                                "An open centre retained a side wing's collision");
                    } else if (piece == Piece.LOWER_MULTI_LH || piece == Piece.LOWER_MULTI_RH) {
                        double wingX = piece == Piece.LOWER_MULTI_LH ? .9375 : .0625;
                        require(contains(shape, rotate(wingX, .5, .75, facing)), "An open outer wing lost collision");
                    }
                }
            } else if (!state.get(OPEN)) {
                VoxelShape first = (VoxelShape) oldFace.invoke(block, state, state.get(FACING), state.get(PIECE),
                        state.get(HINGE), state.get(SILL));
                VoxelShape second = (VoxelShape) oldFace.invoke(block, state, secondaryFacing(state), cornerPiece(state),
                        state.get(HINGE), state.get(SILL));
                same(shape, VoxelShapes.combine(first, second, BooleanBiFunction.OR), "Closed corner collision changed");
            } else {
                require(shape == collision(block, state), "Open corner collision was rebuilt per query");
                Direction facing = state.get(FACING);
                // Probe above the sill and below the fixed fanlight, away from either end jamb.
                double[] front = rotate(.5, .125, .0625, facing);
                double[] side = rotate(state.get(CORNER) == Corner.LEFT ? .0625 : .9375, .125, .5, facing);
                require(!contains(shape, front) && !contains(shape, side), "An invisible hinged leaf blocks a corner opening");
                double[] post = rotate(state.get(CORNER) == Corner.LEFT ? .09375 : .90625, .5, .09375, facing);
                require(contains(shape, post), "Fixed corner mullion has no collision");
                if (state.get(PIECE).single() == Piece.UPPER_SINGLE) {
                    require(contains(shape, rotate(.5, .9, .0625, facing)), "Fixed upper fanlight lost collision");
                }
            }
            for (BlockMirror mirror : BlockMirror.values()) {
                BlockState mirrored = block.mirror(state, mirror);
                require(block.mirror(mirrored, mirror) == state, "Mirroring is not reversible");
                require(primaryPiece(mirrored).single() == state.get(PIECE).single(), "Mirror changed upper/lower placement");
                require(mirrored.get(SILL_SIDE) == state.get(SILL_SIDE), "Mirroring changed inward/outward sill selection");
            }
            for (BlockRotation rotation : BlockRotation.values()) {
                require(block.rotate(state, rotation).get(SILL_SIDE) == state.get(SILL_SIDE),
                        "Rotation changed inward/outward sill selection");
            }
            checked++;
        }
        return checked;
    }

    private static int layouts(WindowFrenchGeorgianBlock block) throws Exception {
        int checked = 0;
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (int[] heights : new int[][]{{1}, {3}, {3, 3}, {3, 3, 3}, {3, 3, 3, 3, 3}, {2, 4, 3, 4, 2}}) {
                TestWorld world = world();
                Direction right = facing.rotateYClockwise();
                BlockPos anchor = new BlockPos(0, 64, 0);
                for (int column = 0; column < heights.length; column++) for (int y = 0; y < heights[column]; y++) {
                    BlockPos pos = anchor.offset(right, column).up(y);
                    // Start from 1.5.44-style independent SINGLE columns, including a
                    // previously open save, so reflow must restore horizontal end metadata.
                    Piece piece = y == heights[column] - 1 ? Piece.UPPER_SINGLE : Piece.LOWER_SINGLE;
                    world.states.put(pos, block.getDefaultState().with(FACING, facing).with(OPEN, true)
                            .with(PIECE, piece).with(HINGE, column % 2 == 0 ? DoorHinge.LEFT : DoorHinge.RIGHT)
                            .with(SILL_SIDE, column % 2 == 0 ? SillSide.INWARD : SillSide.OUTWARD));
                }
                Set<BlockPos> positions = Set.copyOf(world.states.keySet());
                require(block.recalcCluster(world, anchor).recalculated(), "Straight cluster did not recalculate");
                require(world.states.keySet().equals(positions), "Layout created/deleted a block");
                assertAutoColumns(world, positions, facing);
                for (int column = 0; column < heights.length; column++) for (int y = 0; y < heights[column]; y++) {
                    BlockState actual = world.getBlockState(anchor.offset(right, column).up(y));
                    require(actual.get(HINGE) == (column % 2 == 0 ? DoorHinge.LEFT : DoorHinge.RIGHT),
                            "Adjacent columns lost their hinges");
                    require(actual.get(SILL_SIDE) == (column % 2 == 0 ? SillSide.INWARD : SillSide.OUTWARD),
                            "Column reflow reset the selected sill side");
                }
                sync(block, world, anchor);
                checked++;
            }
            for (Corner corner : new Corner[]{Corner.LEFT, Corner.RIGHT}) {
                TestWorld world = world();
                BlockPos owner = new BlockPos(0, 64, 0);
                Direction run = corner == Corner.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                Direction secondary = corner.secondaryFacing(facing);
                for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                    world.states.put(owner.offset(run, x).up(y), block.getDefaultState().with(FACING, facing)
                            .with(CORNER, x == 0 ? corner : Corner.NONE));
                    if (x > 0) world.states.put(owner.offset(facing.getOpposite(), x).up(y),
                            block.getDefaultState().with(FACING, secondary));
                }
                Set<BlockPos> positions = Set.copyOf(world.states.keySet());
                require(block.recalcCluster(world, owner).recalculated(), "Corner cluster did not recalculate");
                require(world.states.keySet().equals(positions), "Corner reflow created/deleted a block");
                for (int y = 0; y < 3; y++) {
                    Piece expected = corner == Corner.LEFT
                            ? (y == 2 ? Piece.UPPER_MULTI_RH : Piece.LOWER_MULTI_RH)
                            : (y == 2 ? Piece.UPPER_MULTI_LH : Piece.LOWER_MULTI_LH);
                    require(world.getBlockState(owner.up(y)).get(PIECE) == expected, "Closed corner lost its existing end piece");
                    for (Direction plane : new Direction[]{facing, secondary}) {
                        Direction extension = plane == facing ? run : facing.getOpposite();
                        Piece middle = y == 2 ? Piece.UPPER_MULTI_MID : Piece.LOWER_MULTI_MID;
                        require(world.getBlockState(owner.offset(extension).up(y)).get(PIECE) == middle,
                                "A corner arm retained an interior opening wing");
                        BlockPos end = owner.offset(extension, 2).up(y);
                        Piece endPiece = rowPiece(y == 2, positions.contains(end.offset(plane.rotateYCounterclockwise())),
                                positions.contains(end.offset(plane.rotateYClockwise())));
                        require(world.getBlockState(end).get(PIECE) == endPiece, "A corner arm lost its outer opening wing");
                    }
                }
                sync(block, world, owner);
                checked++;
            }
            // Vertical holes form independent true-height columns; adjacency is row-local.
            Set<BlockPos> localHoles = Set.of(new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 3, 0),
                    new BlockPos(1, 1, 0), new BlockPos(1, 2, 0), new BlockPos(1, 3, 0));
            TestWorld gapped = world();
            Direction right = facing.rotateYClockwise();
            for (BlockPos local : localHoles) {
                BlockPos pos = new BlockPos(0, 64, 0).offset(right, local.getX()).up(local.getY());
                gapped.states.put(pos, block.getDefaultState().with(FACING, facing).with(PIECE, Piece.LOWER_MULTI_MID));
            }
            Set<BlockPos> holes = Set.copyOf(gapped.states.keySet());
            Set<BlockPos> covered = new HashSet<>();
            var runs = partitionColumns(holes, facing);
            require(runs.size() == 3, "Vertical gap was bridged or adjacent columns merged");
            for (Rect run : runs) {
                require(run.width == 1, "A new straight run is wider than one block");
                for (BlockPos pos : run.iterate()) require(covered.add(pos), "Column partitions overlap");
            }
            require(covered.equals(holes), "Column partition lost or invented blocks");
            BlockPos anchor = new BlockPos(0, 64, 0);
            require(block.recalcCluster(gapped, anchor).recalculated(), "Gapped component did not recalculate");
            assertAutoColumns(gapped, holes, facing);
            sync(block, gapped, anchor);
            checked++;

            TestWorld locked = world();
            for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) {
                locked.states.put(anchor.offset(right, x).up(y), block.getDefaultState()
                        .with(FACING, facing).with(PIECE, Piece.LOWER_MULTI_MID));
            }
            BlockPos manual = anchor.up();
            ClusterManualLockState.setLocked(locked, ClusterManualLockState.WINDOW_FRENCH_GEORGIAN_SCOPE, manual, true);
            BlockState original = locked.getBlockState(manual);
            require(block.recalcCluster(locked, manual.down()).recalculated(), "Auto component beside manual state did not recalculate");
            require(locked.getBlockState(manual) == original, "Reflow overwrote a manual state");
            Set<BlockPos> automatic = new HashSet<>(locked.states.keySet());
            automatic.remove(manual);
            assertAutoColumns(locked, automatic, facing);
            checked++;
        }
        return checked;
    }

    private static void assertAutoColumns(TestWorld world, Set<BlockPos> component, Direction facing) {
        Direction right = facing.rotateYClockwise();
        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            boolean top = !component.contains(pos.up());
            Piece expected = rowPiece(top, component.contains(pos.offset(right.getOpposite())), component.contains(pos.offset(right)));
            require(state.get(PIECE) == expected, "Wrong row edge/top metadata at " + pos + ": " + state.get(PIECE) + " expected " + expected);
            require(state.get(SILL) == !component.contains(pos.down()), "Column sill is not at its own bottom");
        }
    }

    private static Piece rowPiece(boolean upper, boolean left, boolean right) {
        if (!left && !right) return upper ? Piece.UPPER_SINGLE : Piece.LOWER_SINGLE;
        if (!left) return upper ? Piece.UPPER_MULTI_RH : Piece.LOWER_MULTI_RH;
        if (!right) return upper ? Piece.UPPER_MULTI_LH : Piece.LOWER_MULTI_LH;
        return upper ? Piece.UPPER_MULTI_MID : Piece.LOWER_MULTI_MID;
    }

    private static void sync(WindowFrenchGeorgianBlock block, TestWorld world, BlockPos anchor) throws Exception {
        Method open = WindowFrenchGeorgianBlock.class.getDeclaredMethod("applyOpenToCluster", World.class, BlockPos.class, boolean.class);
        Method glass = WindowFrenchGeorgianBlock.class.getDeclaredMethod("applyGlassToCluster", World.class, BlockPos.class, WindowArchBlock.Glass.class);
        open.setAccessible(true); glass.setAccessible(true);
        Map<BlockPos, BlockState> layout = Map.copyOf(world.states);
        for (SillSide side : SillSide.values()) {
            // Exercise the actual single-property edit hook used by the debug stick.
            // Straight columns and both corner arms must follow the edited owner.
            BlockState before = world.getBlockState(anchor);
            if (before.get(SILL_SIDE) == side) before = before.with(SILL_SIDE,
                    side == SillSide.INWARD ? SillSide.OUTWARD : SillSide.INWARD);
            world.states.put(anchor, before.with(SILL_SIDE, side));
            block.onBlockAdded(world.getBlockState(anchor), world, anchor, before, false);
            require(world.states.values().stream().allMatch(state -> state.get(SILL_SIDE) == side),
                    "A sill-side edit did not synchronize every connected window/corner arm");
            require(block.recalcCluster(world, anchor).recalculated(), "Sill-side reflow failed");
            require(world.states.values().stream().allMatch(state -> state.get(SILL_SIDE) == side),
                    "Cluster reflow reset the selected sill side");
            for (WindowArchBlock.Glass finish : WindowArchBlock.Glass.values()) {
                glass.invoke(block, world, anchor, finish);
                require(world.states.values().stream().allMatch(state -> state.get(GLASS) == finish), "Cluster glazing stopped synchronizing");
                for (boolean value : new boolean[]{true, false}) {
                    open.invoke(block, world, anchor, value);
                    require(world.states.values().stream().allMatch(state -> state.get(OPEN) == value), "Adjacent windows stopped opening together");
                    for (var entry : layout.entrySet()) {
                        BlockState expected = entry.getValue().with(SILL_SIDE, side).with(OPEN, value).with(GLASS, finish);
                        require(expected == world.getBlockState(entry.getKey()),
                                "Sill/open/glazing synchronization changed the fanlight, sill, hinge or cluster-end placement");
                    }
                }
            }
        }
    }

    private static void sillInheritance(WindowFrenchGeorgianBlock block) throws Exception {
        BlockPos anchor = new BlockPos(0, 64, 0);
        for (Direction facing : Direction.Type.HORIZONTAL) for (SillSide side : SillSide.values()) {
            for (Corner corner : Corner.values()) {
                TestWorld world = world();
                BlockState source = block.getDefaultState().with(FACING, facing).with(CORNER, corner)
                        .with(SILL_SIDE, side).with(GLASS, WindowArchBlock.Glass.TWO_WAY).with(OPEN, true).with(HINGE, DoorHinge.RIGHT);
                world.states.put(anchor, source);
                for (Direction extension : new Direction[]{Direction.UP, facing.rotateYClockwise()}) {
                    BlockPos target = anchor.offset(extension);
                    var context = new AutomaticItemPlacementContext(world, target, facing.getOpposite(),
                            new ItemStack(Items.STONE), extension);
                    BlockState placed = block.getPlacementState(context);
                    require(placed != null && placed.get(SILL_SIDE) == side && placed.get(GLASS) == source.get(GLASS)
                                    && placed.get(OPEN) == source.get(OPEN) && placed.get(HINGE) == source.get(HINGE),
                            "An added window did not inherit sill side, glass, opening and hinge from its connected neighbour");
                }
            }
        }
    }

    private static VoxelShape expectedSill(Direction facing, SillSide side) {
        double z0 = side == SillSide.INWARD ? 1 / 16.0 : 0;
        double z1 = side == SillSide.INWARD ? 3 / 16.0 : 1 / 8.0;
        double[] first = rotate(0, 0, z0, facing), second = rotate(1, 1 / 32.0, z1, facing);
        return VoxelShapes.cuboid(Math.min(first[0], second[0]), 0, Math.min(first[2], second[2]),
                Math.max(first[0], second[0]), 1 / 32.0, Math.max(first[2], second[2]));
    }

    private static VoxelShape collision(WindowFrenchGeorgianBlock block, BlockState state) {
        return block.getCollisionShape(state, null, BlockPos.ORIGIN, null);
    }
    private static boolean contains(VoxelShape shape, double[] point) {
        return shape.getBoundingBoxes().stream().anyMatch(box -> box.contains(point[0], point[1], point[2]));
    }
    private static double[] rotate(double x, double y, double z, Direction facing) {
        return switch (facing) {
            case EAST -> new double[]{1 - z, y, x};
            case SOUTH -> new double[]{1 - x, y, 1 - z};
            case WEST -> new double[]{z, y, 1 - x};
            default -> new double[]{x, y, z};
        };
    }
    private static void same(VoxelShape expected, VoxelShape actual, String message) {
        require(!VoxelShapes.matchesAnywhere(expected, actual, BooleanBiFunction.NOT_SAME), message);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static TestWorld world() throws Exception {
        TestWorld world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.states = new HashMap<>();
        world.persistentStates = new PersistentStateManager(new java.io.File("in-memory-window-state"), null);
        return world;
    }
    private static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        PersistentStateManager persistentStates;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public PersistentStateManager getPersistentStateManager() { return persistentStates; }
        @Override public boolean isChunkLoaded(BlockPos pos) { return true; }
        @Override public int getBottomY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
            return states.put(pos.toImmutable(), state) != state;
        }
    }
}
