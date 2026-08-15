package com.oliver.erydon.block;

import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CoverBlock extends Block implements Waterloggable, ClusterRebuildableBlock {

    public static int luminance(BlockState state) {
        return state.get(LIGHT).level;
    }

    /**
     * We store the SUPPORT direction (towards the block we’re clinging to).
     */
    public static final DirectionProperty ATTACH = Properties.FACING;

    public static final EnumProperty<CoverSize> SIZE = EnumProperty.of("size", CoverSize.class);
    public static final EnumProperty<CoverFinish> FINISH = EnumProperty.of("finish", CoverFinish.class);
    public static final EnumProperty<CoverLight> LIGHT = EnumProperty.of("light", CoverLight.class);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    // Internal “no-overlap” flags for the 3x3 render. (Corners are derived in blockstate JSON.)
    public static final EnumProperty<CoverExt> EXT = EnumProperty.of("ext", CoverExt.class);
// Paper-thin outline
    private static final double T = 0.01;

    private static final VoxelShape SHAPE_DOWN  = createCuboidShape(0, 0,       0, 16, T,       16);
    private static final VoxelShape SHAPE_UP    = createCuboidShape(0, 16 - T,  0, 16, 16,     16);
    private static final VoxelShape SHAPE_NORTH = createCuboidShape(0, 0,       0, 16, 16,     T);
    private static final VoxelShape SHAPE_SOUTH = createCuboidShape(0, 0, 16 - T, 16, 16,     16);
    private static final VoxelShape SHAPE_WEST  = createCuboidShape(0, 0,       0, T,  16,     16);
    private static final VoxelShape SHAPE_EAST  = createCuboidShape(16 - T, 0,  0, 16, 16,     16);

    public CoverBlock(Settings settings) {
        super(settings);

        setDefaultState(getStateManager().getDefaultState()
                .with(ATTACH, Direction.DOWN)
                .with(SIZE, CoverSize.LARGE)
                .with(FINISH, CoverFinish.MATTE)
                .with(LIGHT, CoverLight.OFF)
                .with(WATERLOGGED, false)
                .with(EXT, CoverExt.M0)
        );
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        FluidState fluid = ctx.getWorld().getFluidState(ctx.getBlockPos());

        BlockState placed = getDefaultState()
                .with(ATTACH, ctx.getSide().getOpposite())
                .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER);

        if (!ctx.getWorld().isClient) {
            placed = updateExtensions(placed, ctx.getWorld(), ctx.getBlockPos());
        }
        return placed;
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Direction attach = state.get(ATTACH);
        BlockPos supportPos = pos.offset(attach);
        BlockState support = world.getBlockState(supportPos);
        return support.isSideSolidFullSquare(world, supportPos, attach.getOpposite());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {

        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }

        // If our supporting block was removed, pop off.
        Direction attach = state.get(ATTACH);
        if (direction == attach && !state.canPlaceAt(world, pos)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }

        // Any neighbour change might affect overlap culling for LARGE.
        if (!world.isClient()) {
            return updateExtensions(state, world, pos);
        }

        return state;
    }
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world.isClient) return;

        // Fix this block
        BlockState fixed = updateExtensions(state, world, pos);
        if (fixed != state) {
            world.setBlockState(pos, fixed, Block.NOTIFY_LISTENERS);
            state = fixed;
        }

        // Fix neighbours so earlier blocks stop overhanging into this one
        updateNeighboursInPlane(world, pos, state.get(ATTACH));
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient) {
            boolean remainsSameBlock = newState.isOf(this);
            CoverStateChangePolicy.Action action = CoverStateChangePolicy.classify(
                    remainsSameBlock,
                    remainsSameBlock && state.get(ATTACH) != newState.get(ATTACH),
                    remainsSameBlock && state.get(SIZE) != newState.get(SIZE));

            if (action == CoverStateChangePolicy.Action.REMOVED) {
                // The cover left this position, so old-plane neighbours may re-enable strips.
                updateNeighboursInPlane(world, pos, state.get(ATTACH));
            } else if (action == CoverStateChangePolicy.Action.TOPOLOGY_CHANGED) {
                // ATTACH/SIZE changes affect topology. EXT, finish, light, and water changes do not;
                // reacting to those internal changes re-enters this hook once per connected cover.
                Direction oldAttach = state.get(ATTACH);
                Direction newAttach = newState.get(ATTACH);
                updateNeighboursInPlane(world, pos, oldAttach);

                BlockState fixed = updateExtensions(newState, world, pos);
                if (fixed.get(EXT) != newState.get(EXT)) {
                    world.setBlockState(pos, fixed, Block.NOTIFY_LISTENERS);
                    newState = fixed;
                }
                updateNeighboursInPlane(world, pos, newAttach);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }

    /**
     * Public so our DebugStick mixin can re-run this after the user changes SIZE/FACING.
     */
    public BlockState updateExtensions(BlockState state, WorldAccess world, BlockPos pos) {
        if (state.get(SIZE) != CoverSize.LARGE) {
            return state.with(EXT, CoverExt.M0);
        }

        Direction attach = state.get(ATTACH);

        Direction wN = mapLocalToWorld(attach, LocalDir.NORTH);
        Direction wE = mapLocalToWorld(attach, LocalDir.EAST);
        Direction wS = mapLocalToWorld(attach, LocalDir.SOUTH);
        Direction wW = mapLocalToWorld(attach, LocalDir.WEST);

        // If a cover exists in the adjacent block-space (any size), do NOT render into it.
        boolean n = !isSamePlaneCover(world, pos.offset(wN), attach);
        boolean e = !isSamePlaneCover(world, pos.offset(wE), attach);
        boolean s = !isSamePlaneCover(world, pos.offset(wS), attach);
        boolean w = !isSamePlaneCover(world, pos.offset(wW), attach);

        int mask = 0;
        if (n) mask |= 1;
        if (e) mask |= 2;
        if (s) mask |= 4;
        if (w) mask |= 8;

        return state.with(EXT, CoverExt.fromMask(mask));
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        Direction attach = state.get(ATTACH);
        Direction rotatedAttach = rotateDirection(attach, rotation);
        BlockState rotated = state.with(ATTACH, rotatedAttach);

        if (state.get(SIZE) == CoverSize.LARGE) {
            int rotatedMask = rotateMask(state.get(EXT).mask, attach, rotatedAttach, rotation);
            rotated = rotated.with(EXT, CoverExt.fromMask(rotatedMask));
        }

        return rotated;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction attach = state.get(ATTACH);
        Direction mirroredAttach = mirrorDirection(attach, mirror);
        BlockState mirrored = state.with(ATTACH, mirroredAttach);

        if (state.get(SIZE) == CoverSize.LARGE) {
            int mirroredMask = mirrorMask(state.get(EXT).mask, attach, mirroredAttach, mirror);
            mirrored = mirrored.with(EXT, CoverExt.fromMask(mirroredMask));
        }

        return mirrored;
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Direction attach = seedState.get(ATTACH);
        Set<BlockPos> component = collectPlaneComponent(world, seed, attach);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(this) && !state.canPlaceAt(world, pos)) {
                world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(),
                        ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }

        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(this) || state.get(ATTACH) != attach) {
                continue;
            }

            BlockState updated = updateExtensions(state, world, pos);
            if (!updated.equals(state)) {
                world.setBlockState(pos, updated, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }

        return new ClusterRecalcResult(component, true);
    }

    private boolean isSamePlaneCover(WorldAccess world, BlockPos otherPos, Direction attach) {
        BlockState other = world.getBlockState(otherPos);
        if (!other.isOf(this)) return false;
        return other.get(ATTACH) == attach;
    }

    private void updateNeighboursInPlane(WorldAccess world, BlockPos pos, Direction attach) {
        Direction wN = mapLocalToWorld(attach, LocalDir.NORTH);
        Direction wE = mapLocalToWorld(attach, LocalDir.EAST);
        Direction wS = mapLocalToWorld(attach, LocalDir.SOUTH);
        Direction wW = mapLocalToWorld(attach, LocalDir.WEST);

        // Cardinals
        updateIfCoverSamePlane(world, pos.offset(wN), attach);
        updateIfCoverSamePlane(world, pos.offset(wE), attach);
        updateIfCoverSamePlane(world, pos.offset(wS), attach);
        updateIfCoverSamePlane(world, pos.offset(wW), attach);

        // Diagonals (important for corner tiles so placement “settles” immediately)
        updateIfCoverSamePlane(world, pos.offset(wN).offset(wE), attach);
        updateIfCoverSamePlane(world, pos.offset(wN).offset(wW), attach);
        updateIfCoverSamePlane(world, pos.offset(wS).offset(wE), attach);
        updateIfCoverSamePlane(world, pos.offset(wS).offset(wW), attach);
    }

    private void updateIfCoverSamePlane(WorldAccess world, BlockPos p, Direction attach) {
        BlockState other = world.getBlockState(p);
        if (!other.isOf(this)) return;
        if (other.get(ATTACH) != attach) return;

        BlockState fixed = updateExtensions(other, world, p);
        if (fixed.get(EXT) != other.get(EXT)) {
            world.setBlockState(p, fixed, Block.NOTIFY_LISTENERS);
        }
    }

    private Set<BlockPos> collectPlaneComponent(WorldAccess world, BlockPos seed, Direction attach) {
        Set<BlockPos> component = new LinkedHashSet<>();
        BlockState seedState = ClusterRecalcSafety.getBlockState(world, seed);
        if (!seedState.isOf(this) || seedState.get(ATTACH) != attach) {
            return component;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        if (!ClusterRecalcSafety.claim(seed)) {
            return component;
        }
        component.add(seed);
        queue.add(seed);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (BlockPos neighbor : planeNeighbours(pos, attach)) {
                if (component.contains(neighbor)) {
                    continue;
                }

                BlockState neighborState = ClusterRecalcSafety.getBlockState(world, neighbor);
                if (!neighborState.isOf(this) || neighborState.get(ATTACH) != attach) {
                    continue;
                }

                if (!ClusterRecalcSafety.claim(neighbor)) {
                    return component;
                }
                component.add(neighbor);
                queue.add(neighbor);
            }
        }

        return component;
    }

    private List<BlockPos> planeNeighbours(BlockPos pos, Direction attach) {
        Direction wN = mapLocalToWorld(attach, LocalDir.NORTH);
        Direction wE = mapLocalToWorld(attach, LocalDir.EAST);
        Direction wS = mapLocalToWorld(attach, LocalDir.SOUTH);
        Direction wW = mapLocalToWorld(attach, LocalDir.WEST);

        return List.of(
                pos.offset(wN),
                pos.offset(wE),
                pos.offset(wS),
                pos.offset(wW),
                pos.offset(wN).offset(wE),
                pos.offset(wE).offset(wS),
                pos.offset(wS).offset(wW),
                pos.offset(wW).offset(wN)
        );
    }

    private int rotateMask(int mask, Direction attach, Direction rotatedAttach, BlockRotation rotation) {
        int rotatedMask = 0;

        for (LocalDir local : LocalDir.values()) {
            int bit = bitFor(local);
            if ((mask & bit) == 0) {
                continue;
            }

            Direction worldDir = mapLocalToWorld(attach, local);
            Direction rotatedWorldDir = rotateDirection(worldDir, rotation);
            rotatedMask |= bitFor(mapWorldToLocal(rotatedAttach, rotatedWorldDir));
        }

        return rotatedMask;
    }

    private int mirrorMask(int mask, Direction attach, Direction mirroredAttach, BlockMirror mirror) {
        int mirroredMask = 0;

        for (LocalDir local : LocalDir.values()) {
            int bit = bitFor(local);
            if ((mask & bit) == 0) {
                continue;
            }

            Direction worldDir = mapLocalToWorld(attach, local);
            Direction mirroredWorldDir = mirrorDirection(worldDir, mirror);
            mirroredMask |= bitFor(mapWorldToLocal(mirroredAttach, mirroredWorldDir));
        }

        return mirroredMask;
    }



    private boolean isSamePlaneLargeCover(WorldAccess world, BlockPos otherPos, Direction attach) {
        BlockState other = world.getBlockState(otherPos);
        if (!other.isOf(this)) return false;
        if (other.get(SIZE) != CoverSize.LARGE) return false;
        return other.get(ATTACH) == attach;
    }

    private enum LocalDir { NORTH, EAST, SOUTH, WEST }

    /**
     * Converts “local” N/E/S/W on the unrotated floor-plate model into world directions
     * for each ATTACH orientation. This matches the x/y rotations used in blockstates.
     */
    private Direction mapLocalToWorld(Direction attach, LocalDir local) {
        return switch (attach) {
            case DOWN -> switch (local) {
                case NORTH -> Direction.NORTH;
                case EAST  -> Direction.EAST;
                case SOUTH -> Direction.SOUTH;
                case WEST  -> Direction.WEST;
            };
            case UP -> switch (local) {
                case NORTH -> Direction.SOUTH;
                case EAST  -> Direction.EAST;
                case SOUTH -> Direction.NORTH;
                case WEST  -> Direction.WEST;
            };
            case NORTH -> switch (local) {
                case NORTH -> Direction.UP;
                case EAST  -> Direction.EAST;
                case SOUTH -> Direction.DOWN;
                case WEST  -> Direction.WEST;
            };
            case SOUTH -> switch (local) {
                case NORTH -> Direction.DOWN;
                case EAST  -> Direction.EAST;
                case SOUTH -> Direction.UP;
                case WEST  -> Direction.WEST;
            };
            case WEST -> switch (local) {
                case NORTH -> Direction.UP;
                case EAST  -> Direction.SOUTH;
                case SOUTH -> Direction.DOWN;
                case WEST  -> Direction.NORTH;
            };
            case EAST -> switch (local) {
                case NORTH -> Direction.UP;
                case EAST  -> Direction.NORTH;
                case SOUTH -> Direction.DOWN;
                case WEST  -> Direction.SOUTH;
            };
        };
    }

    private LocalDir mapWorldToLocal(Direction attach, Direction worldDir) {
        for (LocalDir local : LocalDir.values()) {
            if (mapLocalToWorld(attach, local) == worldDir) {
                return local;
            }
        }
        throw new IllegalArgumentException("No local direction for attach=" + attach + ", worldDir=" + worldDir);
    }

    private static int bitFor(LocalDir local) {
        return switch (local) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 4;
            case WEST -> 8;
        };
    }

    private static Direction rotateDirection(Direction direction, BlockRotation rotation) {
        return direction.getAxis().isHorizontal() ? rotation.rotate(direction) : direction;
    }

    private static Direction mirrorDirection(Direction direction, BlockMirror mirror) {
        return mirror.apply(direction);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public int getOpacity(BlockState state, BlockView world, BlockPos pos) {
        // This is a “visual skin” block – do NOT block light (avoids lighting propagation weirdness).
        return 0;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(ATTACH)) {
            case UP -> SHAPE_UP;
            case DOWN -> SHAPE_DOWN;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // So it truly “slips under” things and never blocks movement.
        return VoxelShapes.empty();
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return false;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(ATTACH, SIZE, FINISH, LIGHT, WATERLOGGED, EXT);
    }

    public enum CoverSize implements StringIdentifiable {
        SMALL("small"),
        LARGE("large");

        private final String id;
        CoverSize(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    public enum CoverFinish implements StringIdentifiable {
        MATTE("matte"),
        GLOSS("gloss");

        private final String id;
        CoverFinish(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    public 
enum CoverExt implements StringIdentifiable {
    M0("0", 0),
    M1("1", 1),
    M2("2", 2),
    M3("3", 3),
    M4("4", 4),
    M5("5", 5),
    M6("6", 6),
    M7("7", 7),
    M8("8", 8),
    M9("9", 9),
    M10("10", 10),
    M11("11", 11),
    M12("12", 12),
    M13("13", 13),
    M14("14", 14),
    M15("15", 15);

    private final String id;
    public final int mask;

    CoverExt(String id, int mask) {
        this.id = id;
        this.mask = mask;

    }

    @Override public String asString() {
        return id;
    }

    public static CoverExt fromMask(int mask) {
        return switch (mask & 15) {
            case 0 -> M0;
            case 1 -> M1;
            case 2 -> M2;
            case 3 -> M3;
            case 4 -> M4;
            case 5 -> M5;
            case 6 -> M6;
            case 7 -> M7;
            case 8 -> M8;
            case 9 -> M9;
            case 10 -> M10;
            case 11 -> M11;
            case 12 -> M12;
            case 13 -> M13;
            case 14 -> M14;
            default -> M15;
        };
    }
}
enum CoverLight implements StringIdentifiable {
        OFF("off", 0),
        // Keep the string "low" so existing worlds don't break.
        DIM("low", 13),
        BRIGHT("bright", 15);

        private final String id;
        public final int level;

        CoverLight(String id, int level) {
            this.id = id;
            this.level = level;
        }

        @Override public String asString() { return id; }

}
}
