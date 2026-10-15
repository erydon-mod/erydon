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
        GeorgianWindowRenderingLaunchChecks.run(block);
        System.out.println("ERYDON_GEORGIAN_WINDOWS_OK states=" + states + " layouts=" + layouts
                + " oldStateKeys=preserved straightCollision=single cornerOpenings=clear clusterSync=preserved cornerAttachment=runEnds");
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
        require(block.getStateManager().getStates().size() == 1536, "Published state combinations changed");
        Method oldFace = WindowFrenchGeorgianBlock.class.getDeclaredMethod("getWindowFaceShape", BlockState.class,
                Direction.class, Piece.class, DoorHinge.class, boolean.class);
        oldFace.setAccessible(true);
        int checked = 0;
        for (BlockState state : block.getStateManager().getStates()) {
            var saved = NbtHelper.fromBlockState(state);
            require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved) == state,
                    "An old piece/glass/hinge/corner state no longer round-trips");
            Piece expected = state.get(CORNER) == Corner.NONE ? state.get(PIECE).single() : state.get(PIECE);
            require(primaryPiece(state) == expected, "Wrong effective primary piece");
            VoxelShape shape = collision(block, state);
            if (state.get(CORNER) == Corner.NONE) {
                same(shape, collision(block, state.with(PIECE, state.get(PIECE).single())),
                        "Legacy straight collision differs from the authored one-wide window");
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
            }
            checked++;
        }
        return checked;
    }

    private static int layouts(WindowFrenchGeorgianBlock block) throws Exception {
        int checked = 0;
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (int[] heights : new int[][]{{1}, {3}, {3, 3, 3}, {2, 4, 3, 4, 2}}) {
                TestWorld world = world();
                Direction right = facing.rotateYClockwise();
                for (int column = 0; column < heights.length; column++) for (int y = 0; y < heights[column]; y++) {
                    BlockPos pos = new BlockPos(0, 64, 0).offset(right, column).up(y);
                    // Old greedy rectangles placed fanlights at y=1 across the
                    // whole ragged row and y=2 across its three inner columns.
                    Piece legacy = heights.length == 5 && (y == 1 || y == 2)
                            ? Piece.UPPER_MULTI_MID : y == 3 ? Piece.UPPER_SINGLE : Piece.LOWER_MULTI_MID;
                    world.states.put(pos, block.getDefaultState().with(FACING, facing)
                            .with(PIECE, legacy).with(HINGE, column % 2 == 0 ? DoorHinge.LEFT : DoorHinge.RIGHT));
                }
                Set<BlockPos> positions = Set.copyOf(world.states.keySet());
                require(block.recalcCluster(world, new BlockPos(0, 64, 0)).recalculated(), "Straight cluster did not recalculate");
                require(world.states.keySet().equals(positions), "Layout created/deleted a block");
                for (int column = 0; column < heights.length; column++) for (int y = 0; y < heights[column]; y++) {
                    BlockState actual = world.getBlockState(new BlockPos(0, 64, 0).offset(right, column).up(y));
                    require(actual.get(PIECE) == (y == heights[column] - 1 ? Piece.UPPER_SINGLE : Piece.LOWER_SINGLE),
                            "Ragged layout retained an intermediate fanlight");
                    require(actual.get(SILL) == (y == 0), "Column sill is not at its own bottom");
                    require(actual.get(HINGE) == (column % 2 == 0 ? DoorHinge.LEFT : DoorHinge.RIGHT), "Adjacent columns lost their hinges");
                }
                sync(block, world, new BlockPos(0, 64, 0));
                checked++;
            }
            for (Corner corner : new Corner[]{Corner.LEFT, Corner.RIGHT}) {
                TestWorld world = world();
                BlockPos owner = new BlockPos(0, 64, 0);
                Direction run = corner == Corner.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                    world.states.put(owner.offset(run, x).up(y), block.getDefaultState().with(FACING, facing)
                            .with(CORNER, x == 0 ? corner : Corner.NONE));
                }
                require(block.recalcCluster(world, owner).recalculated(), "Corner cluster did not recalculate");
                for (int y = 0; y < 3; y++) {
                    Piece expected = corner == Corner.LEFT
                            ? (y == 2 ? Piece.UPPER_MULTI_RH : Piece.LOWER_MULTI_RH)
                            : (y == 2 ? Piece.UPPER_MULTI_LH : Piece.LOWER_MULTI_LH);
                    require(world.getBlockState(owner.up(y)).get(PIECE) == expected, "Closed corner lost its existing end piece");
                }
                sync(block, world, owner);
                checked++;
            }
        }
        // Vertical holes create separate real runs, without depending on the width of a rectangle.
        Set<BlockPos> holes = Set.of(new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 3, 0),
                new BlockPos(1, 1, 0), new BlockPos(1, 2, 0), new BlockPos(1, 3, 0));
        Set<BlockPos> covered = new HashSet<>();
        var runs = partitionColumns(holes, Direction.NORTH);
        require(runs.size() == 3, "Vertical gap was bridged or adjacent columns merged");
        for (Rect run : runs) {
            require(run.width == 1, "A new straight run is wider than one block");
            for (BlockPos pos : run.iterate()) require(covered.add(pos), "Column partitions overlap");
        }
        require(covered.equals(holes), "Column partition lost or invented blocks");
        TestWorld gapped = world();
        for (BlockPos pos : holes) gapped.states.put(pos.up(64), block.getDefaultState()
                .with(FACING, Direction.NORTH).with(PIECE, Piece.LOWER_MULTI_MID));
        require(block.recalcCluster(gapped, new BlockPos(0, 64, 0)).recalculated(), "Gapped component did not recalculate");
        for (BlockPos pos : holes) {
            BlockState state = gapped.getBlockState(pos.up(64));
            require(state.get(PIECE) == (holes.contains(pos.up()) ? Piece.LOWER_SINGLE : Piece.UPPER_SINGLE),
                    "Gapped connected columns used another column's fanlight height");
            require(state.get(SILL) == !holes.contains(pos.down()), "Gapped run lost its own lower sill");
        }
        sync(block, gapped, new BlockPos(0, 64, 0));
        TestWorld locked = world();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) {
            locked.states.put(new BlockPos(x, 64 + y, 0), block.getDefaultState().with(PIECE, Piece.LOWER_MULTI_MID));
        }
        BlockPos manual = new BlockPos(0, 65, 0);
        ClusterManualLockState.setLocked(locked, ClusterManualLockState.WINDOW_FRENCH_GEORGIAN_SCOPE, manual, true);
        BlockState original = locked.getBlockState(manual);
        block.recalcCluster(locked, manual.down());
        require(locked.getBlockState(manual) == original, "Reflow overwrote a manual state");
        require(locked.getBlockState(manual.down()).get(PIECE) == Piece.UPPER_SINGLE, "Manual boundary did not end the auto column");
        require(locked.getBlockState(manual.up()).get(PIECE) == Piece.UPPER_SINGLE, "Connected path above manual boundary was not partitioned");
        return checked + 3;
    }

    private static void sync(WindowFrenchGeorgianBlock block, TestWorld world, BlockPos anchor) throws Exception {
        Method open = WindowFrenchGeorgianBlock.class.getDeclaredMethod("applyOpenToCluster", World.class, BlockPos.class, boolean.class);
        Method glass = WindowFrenchGeorgianBlock.class.getDeclaredMethod("applyGlassToCluster", World.class, BlockPos.class, WindowArchBlock.Glass.class);
        open.setAccessible(true); glass.setAccessible(true);
        for (boolean value : new boolean[]{true, false}) {
            open.invoke(block, world, anchor, value);
            require(world.states.values().stream().allMatch(state -> state.get(OPEN) == value), "Adjacent windows stopped opening together");
        }
        glass.invoke(block, world, anchor, WindowArchBlock.Glass.TWO_WAY);
        require(world.states.values().stream().allMatch(state -> state.get(GLASS) == WindowArchBlock.Glass.TWO_WAY), "Cluster glazing stopped synchronizing");
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
