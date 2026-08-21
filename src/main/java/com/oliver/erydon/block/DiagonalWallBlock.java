package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.WallShape;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DiagonalWallBlock extends WallBlock implements ClusterRebuildableBlock {
    public static final BooleanProperty NORTH_EAST = BooleanProperty.of("north_east");
    public static final BooleanProperty SOUTH_EAST = BooleanProperty.of("south_east");
    public static final BooleanProperty SOUTH_WEST = BooleanProperty.of("south_west");
    public static final BooleanProperty NORTH_WEST = BooleanProperty.of("north_west");

    // Keep the original persisted key so existing test worlds retain their selected spacing.
    private static final String PIER_SPACING_SCOPE =
            ClusterManualLockState.GEORGIAN_WALL_SCOPE + "_corner_spacing";
    private static final VoxelShape PIER_SHAPE =
            Block.createCuboidShape(2.13133D, 0.0D, 2.13133D, 13.86867D, 16.0D, 13.86867D);
    private static final VoxelShape NORTH_PIER_STUB_SHAPE =
            Block.createCuboidShape(4.75D, 0.0D, 0.0D, 11.25D, 16.0D, 4.2D);
    private static final VoxelShape EAST_PIER_STUB_SHAPE =
            Block.createCuboidShape(11.8D, 0.0D, 4.75D, 16.0D, 16.0D, 11.25D);
    private static final VoxelShape SOUTH_PIER_STUB_SHAPE =
            Block.createCuboidShape(4.75D, 0.0D, 11.8D, 11.25D, 16.0D, 16.0D);
    private static final VoxelShape WEST_PIER_STUB_SHAPE =
            Block.createCuboidShape(0.0D, 0.0D, 4.75D, 4.2D, 16.0D, 11.25D);
    private static final VoxelShape POST_SHAPE = Block.createCuboidShape(4.0D, 0.0D, 4.0D, 12.0D, 16.0D, 12.0D);
    private static final VoxelShape NORTH_LOW_SHAPE = Block.createCuboidShape(5.0D, 0.0D, 0.0D, 11.0D, 14.0D, 8.0D);
    private static final VoxelShape EAST_LOW_SHAPE = Block.createCuboidShape(8.0D, 0.0D, 5.0D, 16.0D, 14.0D, 11.0D);
    private static final VoxelShape SOUTH_LOW_SHAPE = Block.createCuboidShape(5.0D, 0.0D, 8.0D, 11.0D, 14.0D, 16.0D);
    private static final VoxelShape WEST_LOW_SHAPE = Block.createCuboidShape(0.0D, 0.0D, 5.0D, 8.0D, 14.0D, 11.0D);
    private static final VoxelShape NORTH_EAST_SHAPE = createNorthEastShape(14.0D);
    private static final VoxelShape SOUTH_EAST_SHAPE = createSouthEastShape(14.0D);
    private static final VoxelShape SOUTH_WEST_SHAPE = createSouthWestShape(14.0D);
    private static final VoxelShape NORTH_WEST_SHAPE = createNorthWestShape(14.0D);
    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[2 * 3 * 3 * 3 * 3 * 2 * 2 * 2 * 2];

    public DiagonalWallBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(NORTH_EAST, false)
                .with(SOUTH_EAST, false)
                .with(SOUTH_WEST, false)
                .with(NORTH_WEST, false));
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        GeorgianWallPierSpacing spacing = inheritedPierSpacing(context.getWorld(), context.getBlockPos());
        return withDiagonalProperties(state, context.getWorld(), context.getBlockPos(), spacing);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        boolean preserveStartingPier = !isSlopeRunSection(world, pos, state)
                && (isStartingPierMarker(state) || isIsolatedPierSection(state));
        BlockState updated = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        if (world instanceof ServerWorld serverWorld && !ClusterRecalcSafety.isActive()) {
            serverWorld.scheduleBlockTick(pos, this, 1);
        }
        return withDiagonalProperties(updated, world, pos, pierSpacing(world, pos), preserveStartingPier);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!oldState.isOf(this)) {
            refreshSlopeNeighbours(world, pos);
        }
        if (world.isClient || oldState.isOf(this)) {
            return;
        }
        setPierSpacing(world, pos, inheritedPierSpacing(world, pos));
        updateDiagonalNeighbors(world, pos);
        scheduleWallRebuild(world, pos);
        scheduleNearbyWallRebuilds(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!state.isOf(newState.getBlock())) {
            refreshSlopeNeighbours(world, pos);
        }
        if (!world.isClient && !state.isOf(newState.getBlock())) {
            if (!(newState.getBlock() instanceof DiagonalWallBlock)) {
                clearPierSpacing(world, pos);
            }
            updateDiagonalNeighbors(world, pos);
            scheduleNearbyWallRebuilds(world, pos);
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player.getStackInHand(hand).isOf(Items.DEBUG_STICK)) {
            return ActionResult.PASS;
        }
        if (hand != Hand.MAIN_HAND
                || player.isSneaking()
                || hit.getSide().getAxis() == Direction.Axis.Y) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }

        GeorgianWallPierSpacing next = pierSpacing(world, pos).next();
        ClusterRecalcResult result = ClusterRecalcSafety.run(
                serverWorld,
                () -> rebuildComponent(world, pos, next)
        );
        if (result.recalculated()) {
            player.sendMessage(Text.translatable(
                    "message.erydon.georgian_wall.pier_spacing",
                    Text.translatable(next.translationKey())), true);
        } else {
            player.sendMessage(Text.translatable(
                    "message.erydon.georgian_wall.pier_spacing.failed"), true);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        ClusterRecalcSafety.run(world, () -> recalcCluster(world, pos));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return cachedShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return cachedShape(state);
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        return rebuildComponent(world, seed, pierSpacing(world, seed));
    }

    private static ClusterRecalcResult rebuildComponent(World world, BlockPos seed,
                                                        GeorgianWallPierSpacing spacing) {
        if (!(world.getBlockState(seed).getBlock() instanceof DiagonalWallBlock)) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> component = collectConnectedComponent(world, seed);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        setPierSpacing(world, component, spacing);
        for (BlockPos pos : component) {
            refreshWallAt(world, pos, spacing);
        }

        Set<BlockPos> periodicPiers = periodicPierPositions(world, component, spacing);
        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof DiagonalWallBlock)) {
                continue;
            }
            GeorgianWallSlopeResolver.Mode slopeMode =
                    GeorgianWallSlopeResolver.resolve(world, state, pos);
            BlockState updated;
            if (slopeMode.isSlope()) {
                updated = withoutSlopePierMarker(state);
            } else if (straightAxis(state) == null || isActualPierSection(world, pos, state)) {
                continue;
            } else {
                updated = withPeriodicPierMarker(state, periodicPiers.contains(pos));
            }
            if (!updated.equals(state)) {
                world.setBlockState(pos, updated, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }
        return new ClusterRecalcResult(component, true);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(NORTH_EAST, SOUTH_EAST, SOUTH_WEST, NORTH_WEST);
    }

    private BlockState withDiagonalProperties(BlockState state, BlockView world, BlockPos pos,
                                              GeorgianWallPierSpacing spacing) {
        return withDiagonalProperties(state, world, pos, spacing, false);
    }

    private BlockState withDiagonalProperties(BlockState state, BlockView world, BlockPos pos,
                                              GeorgianWallPierSpacing spacing,
                                              boolean preserveStartingPier) {
        boolean northEast = connectsDiagonal(state, world, pos, Direction.NORTH, Direction.EAST);
        boolean southEast = connectsDiagonal(state, world, pos, Direction.SOUTH, Direction.EAST);
        boolean southWest = connectsDiagonal(state, world, pos, Direction.SOUTH, Direction.WEST);
        boolean northWest = connectsDiagonal(state, world, pos, Direction.NORTH, Direction.WEST);

        return withPierState(state
                .with(NORTH_EAST, northEast)
                .with(SOUTH_EAST, southEast)
                .with(SOUTH_WEST, southWest)
                .with(NORTH_WEST, northWest), world, pos, spacing, preserveStartingPier);
    }

    private boolean connectsDiagonal(BlockState state, BlockView world, BlockPos pos, Direction first, Direction second) {
        if (!cardinalPriorityAllowsDiagonal(cardinalShape(state, first), cardinalShape(state, second))) {
            return false;
        }
        BlockPos diagonalPos = pos.offset(first).offset(second);
        BlockState diagonalState = world.getBlockState(diagonalPos);
        if (isFullDiagonalCorner(diagonalState, world, diagonalPos, first, second)) {
            return true;
        }
        return diagonalState.getBlock() instanceof DiagonalWallBlock;
    }

    static boolean cardinalPriorityAllowsDiagonal(WallShape first, WallShape second) {
        return GeorgianWallConnectionPolicy.allowsDiagonal(
                first != WallShape.NONE,
                second != WallShape.NONE);
    }

    private static boolean isFullDiagonalCorner(BlockState state, BlockView world, BlockPos pos,
                                                Direction first, Direction second) {
        return state.isSideSolidFullSquare(world, pos, first.getOpposite())
                && state.isSideSolidFullSquare(world, pos, second.getOpposite());
    }

    private static WallShape cardinalShape(BlockState state, Direction direction) {
        return switch (direction) {
            case NORTH -> state.get(NORTH_SHAPE);
            case EAST -> state.get(EAST_SHAPE);
            case SOUTH -> state.get(SOUTH_SHAPE);
            case WEST -> state.get(WEST_SHAPE);
            default -> WallShape.NONE;
        };
    }

    private static void updateDiagonalNeighbors(World world, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        BlockPos.Mutable neighborPos = new BlockPos.Mutable();

        neighborPos.set(x + 1, y, z - 1);
        updateDiagonalNeighbor(world, neighborPos);
        neighborPos.set(x + 1, y, z + 1);
        updateDiagonalNeighbor(world, neighborPos);
        neighborPos.set(x - 1, y, z + 1);
        updateDiagonalNeighbor(world, neighborPos);
        neighborPos.set(x - 1, y, z - 1);
        updateDiagonalNeighbor(world, neighborPos);
    }

    public static void refreshAroundChangedBlock(World world, BlockPos pos) {
        if (!world.isClient && !ClusterRecalcSafety.isActive()) {
            updateDiagonalNeighbors(world, pos);
        }
    }

    private static void updateDiagonalNeighbor(World world, BlockPos pos) {
        refreshWallAt(world, pos);
        scheduleWallRebuild(world, pos);
    }

    private static boolean refreshWallAt(World world, BlockPos pos) {
        return refreshWallAt(world, pos, pierSpacing(world, pos));
    }

    private static boolean refreshWallAt(World world, BlockPos pos, GeorgianWallPierSpacing spacing) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DiagonalWallBlock block) {
            BlockState updated = block.withDiagonalProperties(state, world, pos, spacing);
            if (!updated.equals(state)) {
                world.setBlockState(pos.toImmutable(), updated,
                        ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                return true;
            }
        }
        return false;
    }

    private static Set<BlockPos> collectConnectedComponent(BlockView world, BlockPos seed) {
        Set<BlockPos> component = new LinkedHashSet<>();
        if (!(ClusterRecalcSafety.getBlockState(world, seed).getBlock() instanceof DiagonalWallBlock)) {
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
            for (BlockPos neighbor : connectedNeighbours(world, pos)) {
                if (component.contains(neighbor)
                        || !(ClusterRecalcSafety.getBlockState(world, neighbor).getBlock() instanceof DiagonalWallBlock)) {
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

    private static List<BlockPos> planarNeighbours(BlockPos pos) {
        return List.of(
                pos.north(),
                pos.east(),
                pos.south(),
                pos.west(),
                pos.north().east(),
                pos.east().south(),
                pos.south().west(),
                pos.west().north()
        );
    }

    private static List<BlockPos> connectedNeighbours(BlockView world, BlockPos pos) {
        Set<BlockPos> neighbours = new LinkedHashSet<>(planarNeighbours(pos));
        neighbours.addAll(GeorgianWallSlopeResolver.connectedSlopeNeighbours(world, pos));
        return List.copyOf(neighbours);
    }

    private static VoxelShape cachedShape(BlockState state) {
        int index = shapeIndex(state);
        VoxelShape shape = SHAPE_CACHE[index];
        if (shape != null) {
            return shape;
        }

        synchronized (SHAPE_CACHE) {
            shape = SHAPE_CACHE[index];
            if (shape == null) {
                shape = createShape(state);
                SHAPE_CACHE[index] = shape;
            }
            return shape;
        }
    }

    private static int shapeIndex(BlockState state) {
        int index = state.get(UP) ? 1 : 0;
        index = index * 3 + wallShapeIndex(state.get(NORTH_SHAPE));
        index = index * 3 + wallShapeIndex(state.get(EAST_SHAPE));
        index = index * 3 + wallShapeIndex(state.get(SOUTH_SHAPE));
        index = index * 3 + wallShapeIndex(state.get(WEST_SHAPE));
        index = index * 2 + (state.get(NORTH_EAST) ? 1 : 0);
        index = index * 2 + (state.get(SOUTH_EAST) ? 1 : 0);
        index = index * 2 + (state.get(SOUTH_WEST) ? 1 : 0);
        index = index * 2 + (state.get(NORTH_WEST) ? 1 : 0);
        return index;
    }

    private static int wallShapeIndex(WallShape shape) {
        return switch (shape) {
            case NONE -> 0;
            case LOW -> 1;
            case TALL -> 2;
        };
    }

    private static VoxelShape createShape(BlockState state) {
        VoxelShape shape = VoxelShapes.empty();
        boolean pierSection = isPierSection(state);
        if (pierSection) {
            shape = VoxelShapes.union(shape, PIER_SHAPE);
        }
        if (showsPost(state)) {
            shape = VoxelShapes.union(shape, POST_SHAPE);
        }
        if (pierSection) {
            shape = addPierStubs(shape, state);
        } else {
            shape = addCardinal(shape, state.get(NORTH_SHAPE), NORTH_LOW_SHAPE);
            shape = addCardinal(shape, state.get(EAST_SHAPE), EAST_LOW_SHAPE);
            shape = addCardinal(shape, state.get(SOUTH_SHAPE), SOUTH_LOW_SHAPE);
            shape = addCardinal(shape, state.get(WEST_SHAPE), WEST_LOW_SHAPE);
        }
        return addDiagonal(shape, state);
    }

    private static VoxelShape addPierStubs(VoxelShape shape, BlockState state) {
        if (state.get(NORTH_SHAPE) != WallShape.NONE) {
            shape = VoxelShapes.union(shape, NORTH_PIER_STUB_SHAPE);
        }
        if (state.get(EAST_SHAPE) != WallShape.NONE) {
            shape = VoxelShapes.union(shape, EAST_PIER_STUB_SHAPE);
        }
        if (state.get(SOUTH_SHAPE) != WallShape.NONE) {
            shape = VoxelShapes.union(shape, SOUTH_PIER_STUB_SHAPE);
        }
        if (state.get(WEST_SHAPE) != WallShape.NONE) {
            shape = VoxelShapes.union(shape, WEST_PIER_STUB_SHAPE);
        }
        return shape;
    }

    private static VoxelShape addCardinal(VoxelShape shape, WallShape wallShape, VoxelShape sideShape) {
        if (wallShape == WallShape.NONE) {
            return shape;
        }
        return VoxelShapes.union(shape, sideShape);
    }

    private static VoxelShape addDiagonal(VoxelShape shape, BlockState state) {
        if (state.get(NORTH_EAST)) {
            shape = VoxelShapes.union(shape, NORTH_EAST_SHAPE);
        }
        if (state.get(SOUTH_EAST)) {
            shape = VoxelShapes.union(shape, SOUTH_EAST_SHAPE);
        }
        if (state.get(SOUTH_WEST)) {
            shape = VoxelShapes.union(shape, SOUTH_WEST_SHAPE);
        }
        if (state.get(NORTH_WEST)) {
            shape = VoxelShapes.union(shape, NORTH_WEST_SHAPE);
        }
        return shape;
    }

    private static BlockState withPierState(BlockState state,
                                            BlockView world,
                                            BlockPos pos,
                                            GeorgianWallPierSpacing spacing,
                                            boolean preserveStartingPier) {
        boolean startingPier = spacing.piersEnabled()
                && (preserveStartingPier || isStartingPierMarker(state));
        BlockState normalized = normalizeCardinalShapes(state);
        int cardinals = cardinalMask(normalized);
        boolean hasDiagonal = hasAnyDiagonal(normalized);
        GeorgianWallSlopeResolver.Mode slopeMode = GeorgianWallSlopeResolver.resolve(world, normalized, pos);
        if (slopeMode.isSlope()) {
            return normalized.with(UP, true);
        }

        boolean stairJoint = spacing.piersEnabled()
                && GeorgianWallSlopeResolver.isFlatStairJoint(world, normalized, pos);
        if (stairJoint) {
            if (cardinals == 0) {
                return normalized.with(UP, false);
            }
            return markFirstCardinalTall(normalized.with(UP, true));
        }

        if (startingPier && cardinals != 0) {
            return markFirstCardinalTall(normalized.with(UP, true));
        }
        if (hasConnectionTurn(normalized)) {
            if (!spacing.piersEnabled()) {
                return normalized.with(UP, true);
            }
            return markFirstCardinalTall(normalized.with(UP, false));
        }

        if (isExactStraightWithoutDiagonals(normalized)) {
            return normalized.with(UP, false);
        }

        boolean northEast = normalized.get(NORTH_EAST);
        boolean southEast = normalized.get(SOUTH_EAST);
        boolean southWest = normalized.get(SOUTH_WEST);
        boolean northWest = normalized.get(NORTH_WEST);
        if (!hasDiagonal) {
            if (cardinals == 0) {
                return normalized.with(UP, !spacing.piersEnabled());
            }
            return normalized;
        }

        boolean straightDiagonalOnly = cardinals == 0
                && ((northEast && southWest && !southEast && !northWest)
                || (northWest && southEast && !northEast && !southWest));
        return normalized.with(UP, !straightDiagonalOnly);
    }

    private static BlockState normalizeCardinalShapes(BlockState state) {
        return state
                .with(NORTH_SHAPE, normalizedShape(state.get(NORTH_SHAPE)))
                .with(EAST_SHAPE, normalizedShape(state.get(EAST_SHAPE)))
                .with(SOUTH_SHAPE, normalizedShape(state.get(SOUTH_SHAPE)))
                .with(WEST_SHAPE, normalizedShape(state.get(WEST_SHAPE)));
    }

    private static WallShape normalizedShape(WallShape shape) {
        return shape == WallShape.NONE ? WallShape.NONE : WallShape.LOW;
    }

    private static BlockState markFirstCardinalTall(BlockState state) {
        if (state.get(NORTH_SHAPE) != WallShape.NONE) {
            return state.with(NORTH_SHAPE, WallShape.TALL);
        }
        if (state.get(EAST_SHAPE) != WallShape.NONE) {
            return state.with(EAST_SHAPE, WallShape.TALL);
        }
        if (state.get(SOUTH_SHAPE) != WallShape.NONE) {
            return state.with(SOUTH_SHAPE, WallShape.TALL);
        }
        if (state.get(WEST_SHAPE) != WallShape.NONE) {
            return state.with(WEST_SHAPE, WallShape.TALL);
        }
        return state;
    }

    private static BlockState withPeriodicPierMarker(BlockState state, boolean selected) {
        BlockState normalized = normalizeCardinalShapes(state);
        if (straightAxis(normalized) == null) {
            return normalized;
        }
        if (!selected) {
            return Integer.bitCount(cardinalMask(normalized)) == 1
                    ? normalized.with(UP, true)
                    : normalized;
        }
        return markConnectedCardinalsTall(normalized.with(UP, false));
    }

    private static BlockState withoutSlopePierMarker(BlockState state) {
        return normalizeCardinalShapes(state).with(UP, true);
    }

    private static BlockState markConnectedCardinalsTall(BlockState state) {
        BlockState marked = state;
        if (marked.get(NORTH_SHAPE) != WallShape.NONE) {
            marked = marked.with(NORTH_SHAPE, WallShape.TALL);
        }
        if (marked.get(EAST_SHAPE) != WallShape.NONE) {
            marked = marked.with(EAST_SHAPE, WallShape.TALL);
        }
        if (marked.get(SOUTH_SHAPE) != WallShape.NONE) {
            marked = marked.with(SOUTH_SHAPE, WallShape.TALL);
        }
        if (marked.get(WEST_SHAPE) != WallShape.NONE) {
            marked = marked.with(WEST_SHAPE, WallShape.TALL);
        }
        return marked;
    }

    public static boolean isPierSection(BlockState state) {
        return hasTallCardinal(state)
                || isDiagonalOnlyPierSection(state)
                || isIsolatedPierSection(state);
    }

    public static List<Direction> pierStubDirections(BlockState state) {
        if (!isPierSection(state)) {
            return List.of();
        }
        List<Direction> directions = new ArrayList<>(4);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (cardinalShape(state, direction) != WallShape.NONE) {
                directions.add(direction);
            }
        }
        return List.copyOf(directions);
    }

    private static boolean isPeriodicPierSection(BlockState state) {
        return GeorgianWallConnectionPolicy.isPeriodicPierSection(
                cardinalMask(state), diagonalMask(state), tallCardinalMask(state), state.get(UP));
    }

    private static boolean isDiagonalOnlyPierSection(BlockState state) {
        if (state.get(UP) || cardinalMask(state) != 0) {
            return false;
        }
        boolean northEast = state.get(NORTH_EAST);
        boolean southEast = state.get(SOUTH_EAST);
        boolean southWest = state.get(SOUTH_WEST);
        boolean northWest = state.get(NORTH_WEST);
        return (northEast && southEast)
                || (southEast && southWest)
                || (southWest && northWest)
                || (northWest && northEast);
    }

    private static boolean isIsolatedPierSection(BlockState state) {
        return !state.get(UP)
                && cardinalMask(state) == 0
                && !hasAnyDiagonal(state);
    }

    private static boolean isStartingPierMarker(BlockState state) {
        return state.get(UP) && hasTallCardinal(state);
    }

    private static boolean showsPost(BlockState state) {
        return state.get(UP) && !hasTallCardinal(state);
    }

    private static boolean hasTallCardinal(BlockState state) {
        return state.get(NORTH_SHAPE) == WallShape.TALL
                || state.get(EAST_SHAPE) == WallShape.TALL
                || state.get(SOUTH_SHAPE) == WallShape.TALL
                || state.get(WEST_SHAPE) == WallShape.TALL;
    }

    private static boolean isExactStraightWithoutDiagonals(BlockState state) {
        return cardinalStraightAxis(state) != null && !hasAnyDiagonal(state);
    }

    private static Direction.Axis straightAxis(BlockState state) {
        if (!GeorgianWallConnectionPolicy.isStraightRunSection(
                cardinalMask(state), diagonalMask(state))) {
            return null;
        }
        boolean northOrSouth = state.get(NORTH_SHAPE) != WallShape.NONE
                || state.get(SOUTH_SHAPE) != WallShape.NONE;
        return northOrSouth ? Direction.Axis.Z : Direction.Axis.X;
    }

    private static Direction.Axis cardinalStraightAxis(BlockState state) {
        boolean north = state.get(NORTH_SHAPE) != WallShape.NONE;
        boolean east = state.get(EAST_SHAPE) != WallShape.NONE;
        boolean south = state.get(SOUTH_SHAPE) != WallShape.NONE;
        boolean west = state.get(WEST_SHAPE) != WallShape.NONE;
        if (north && south && !east && !west) {
            return Direction.Axis.Z;
        }
        if (east && west && !north && !south) {
            return Direction.Axis.X;
        }
        return null;
    }

    private static boolean hasAnyDiagonal(BlockState state) {
        return state.get(NORTH_EAST)
                || state.get(SOUTH_EAST)
                || state.get(SOUTH_WEST)
                || state.get(NORTH_WEST);
    }

    private static Set<BlockPos> periodicPierPositions(World world, Set<BlockPos> component,
                                                       GeorgianWallPierSpacing spacing) {
        if (spacing.interval() <= 0) {
            return Set.of();
        }

        Map<BlockPos, Direction.Axis> candidates = new HashMap<>();
        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            Direction.Axis axis = straightAxis(state);
            if (axis != null
                    && !isSlopeRunSection(world, pos, state)
                    && !isActualPierSection(world, pos, state)) {
                candidates.put(pos.toImmutable(), axis);
            }
        }

        Set<BlockPos> selected = new HashSet<>();
        for (Map.Entry<BlockPos, Direction.Axis> entry : candidates.entrySet()) {
            BlockPos start = entry.getKey();
            Direction.Axis axis = entry.getValue();
            Direction negative = axis == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
            Direction positive = negative.getOpposite();
            if (candidates.get(start.offset(negative)) == axis) {
                continue;
            }

            List<BlockPos> run = new ArrayList<>();
            BlockPos cursor = start;
            while (candidates.get(cursor) == axis) {
                run.add(cursor.toImmutable());
                cursor = cursor.offset(positive);
            }

            BlockPos before = start.offset(negative);
            boolean beginsAtPier = isActualPierSection(world, before, world.getBlockState(before));
            boolean endsAtPier = isActualPierSection(world, cursor, world.getBlockState(cursor));
            for (int index = 0; index < run.size(); index++) {
                int runIndex = GeorgianWallConnectionPolicy.anchoredRunIndex(
                        index, run.size(), beginsAtPier, endsAtPier);
                BlockPos candidate = run.get(runIndex);
                if (GeorgianWallConnectionPolicy.shouldUsePeriodicPier(
                        index, spacing.interval(), isAdjacentToActualPier(world, candidate))) {
                    selected.add(candidate);
                }
            }
        }
        return selected;
    }

    private static boolean isAdjacentToActualPier(BlockView world, BlockPos pos) {
        for (BlockPos neighbor : connectedNeighbours(world, pos)) {
            if (isActualPierSection(world, neighbor, world.getBlockState(neighbor))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActualPierSection(BlockView world, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DiagonalWallBlock
                && isPierSection(state)
                && !isPeriodicPierSection(state)
                && !isSlopeRunSection(world, pos, state);
    }

    private static boolean isSlopeRunSection(BlockView world, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DiagonalWallBlock
                && GeorgianWallSlopeResolver.resolve(world, state, pos).isSlope();
    }

    private static GeorgianWallPierSpacing inheritedPierSpacing(WorldAccess world, BlockPos pos) {
        int stored = ClusterManualLockState.getInt(world, PIER_SPACING_SCOPE, pos);
        if (stored != 0) {
            return GeorgianWallPierSpacing.fromStoredValue(stored);
        }
        for (BlockPos neighbor : spacingNeighbours(pos)) {
            if (!(world.getBlockState(neighbor).getBlock() instanceof DiagonalWallBlock)) {
                continue;
            }
            int neighborStored = ClusterManualLockState.getInt(world, PIER_SPACING_SCOPE, neighbor);
            if (neighborStored != 0) {
                return GeorgianWallPierSpacing.fromStoredValue(neighborStored);
            }
        }
        return GeorgianWallPierSpacing.EVERY_4;
    }

    private static GeorgianWallPierSpacing pierSpacing(WorldAccess world, BlockPos pos) {
        return GeorgianWallPierSpacing.fromStoredValue(
                ClusterManualLockState.getInt(world, PIER_SPACING_SCOPE, pos));
    }

    private static void setPierSpacing(World world, BlockPos pos, GeorgianWallPierSpacing spacing) {
        ClusterManualLockState.setInt(world, PIER_SPACING_SCOPE, pos, spacing.storedValue());
    }

    private static void setPierSpacing(World world, Set<BlockPos> positions,
                                       GeorgianWallPierSpacing spacing) {
        for (BlockPos pos : positions) {
            setPierSpacing(world, pos, spacing);
        }
    }

    private static void clearPierSpacing(WorldAccess world, BlockPos pos) {
        if (!ClusterManualLockState.isPreservedForSwap(pos)) {
            ClusterManualLockState.clearInt(world, PIER_SPACING_SCOPE, pos);
        }
    }

    private static void scheduleWallRebuild(World world, BlockPos pos) {
        if (ClusterRecalcSafety.isActive()) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DiagonalWallBlock block && world instanceof ServerWorld serverWorld) {
            serverWorld.scheduleBlockTick(pos.toImmutable(), block, 1);
        }
    }

    private static List<BlockPos> spacingNeighbours(BlockPos pos) {
        Set<BlockPos> neighbours = new LinkedHashSet<>(planarNeighbours(pos));
        for (Direction direction : Direction.Type.HORIZONTAL) {
            neighbours.add(pos.offset(direction).up());
            neighbours.add(pos.offset(direction).down());
        }
        return List.copyOf(neighbours);
    }

    private static void scheduleNearbyWallRebuilds(World world, BlockPos pos) {
        if (world.isClient || ClusterRecalcSafety.isActive()) {
            return;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int distance = 1; distance <= 2; distance++) {
                for (int yOffset = -1; yOffset <= 1; yOffset++) {
                    scheduleWallRebuild(world, pos.offset(direction, distance).add(0, yOffset, 0));
                }
            }
        }
    }

    private static void refreshSlopeNeighbours(World world, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int distance = 1; distance <= 2; distance++) {
                for (int yOffset = -1; yOffset <= 1; yOffset++) {
                    BlockPos neighbourPos = pos.offset(direction, distance).add(0, yOffset, 0);
                    BlockState neighbour = world.getBlockState(neighbourPos);
                    if (neighbour.getBlock() instanceof DiagonalWallBlock) {
                        world.updateListeners(neighbourPos, neighbour, neighbour, Block.NOTIFY_LISTENERS);
                    }
                }
            }
        }
    }

    private static boolean hasConnectionTurn(BlockState state) {
        return GeorgianWallConnectionPolicy.hasConnectionTurn(
                cardinalMask(state), diagonalMask(state));
    }

    private static int cardinalMask(BlockState state) {
        int mask = 0;
        mask |= state.get(NORTH_SHAPE) != WallShape.NONE ? 1 : 0;
        mask |= state.get(EAST_SHAPE) != WallShape.NONE ? 1 << 1 : 0;
        mask |= state.get(SOUTH_SHAPE) != WallShape.NONE ? 1 << 2 : 0;
        mask |= state.get(WEST_SHAPE) != WallShape.NONE ? 1 << 3 : 0;
        return mask;
    }

    private static int tallCardinalMask(BlockState state) {
        int mask = 0;
        mask |= state.get(NORTH_SHAPE) == WallShape.TALL ? 1 : 0;
        mask |= state.get(EAST_SHAPE) == WallShape.TALL ? 1 << 1 : 0;
        mask |= state.get(SOUTH_SHAPE) == WallShape.TALL ? 1 << 2 : 0;
        mask |= state.get(WEST_SHAPE) == WallShape.TALL ? 1 << 3 : 0;
        return mask;
    }

    private static int diagonalMask(BlockState state) {
        int mask = 0;
        mask |= state.get(NORTH_EAST) ? 1 : 0;
        mask |= state.get(SOUTH_EAST) ? 1 << 1 : 0;
        mask |= state.get(SOUTH_WEST) ? 1 << 2 : 0;
        mask |= state.get(NORTH_WEST) ? 1 << 3 : 0;
        return mask;
    }

    private static VoxelShape createNorthEastShape(double height) {
        return VoxelShapes.union(
                Block.createCuboidShape(13.0D, 0.0D, 0.0D, 16.0D, height, 3.0D),
                Block.createCuboidShape(11.0D, 0.0D, 2.0D, 15.0D, height, 5.0D),
                Block.createCuboidShape(9.0D, 0.0D, 4.0D, 13.0D, height, 7.0D),
                Block.createCuboidShape(8.0D, 0.0D, 6.0D, 11.0D, height, 8.0D));
    }

    private static VoxelShape createSouthEastShape(double height) {
        return VoxelShapes.union(
                Block.createCuboidShape(13.0D, 0.0D, 13.0D, 16.0D, height, 16.0D),
                Block.createCuboidShape(11.0D, 0.0D, 11.0D, 15.0D, height, 14.0D),
                Block.createCuboidShape(9.0D, 0.0D, 9.0D, 13.0D, height, 12.0D),
                Block.createCuboidShape(8.0D, 0.0D, 8.0D, 11.0D, height, 10.0D));
    }

    private static VoxelShape createSouthWestShape(double height) {
        return VoxelShapes.union(
                Block.createCuboidShape(0.0D, 0.0D, 13.0D, 3.0D, height, 16.0D),
                Block.createCuboidShape(1.0D, 0.0D, 11.0D, 5.0D, height, 14.0D),
                Block.createCuboidShape(3.0D, 0.0D, 9.0D, 7.0D, height, 12.0D),
                Block.createCuboidShape(5.0D, 0.0D, 8.0D, 8.0D, height, 10.0D));
    }

    private static VoxelShape createNorthWestShape(double height) {
        return VoxelShapes.union(
                Block.createCuboidShape(0.0D, 0.0D, 0.0D, 3.0D, height, 3.0D),
                Block.createCuboidShape(1.0D, 0.0D, 2.0D, 5.0D, height, 5.0D),
                Block.createCuboidShape(3.0D, 0.0D, 4.0D, 7.0D, height, 7.0D),
                Block.createCuboidShape(5.0D, 0.0D, 6.0D, 8.0D, height, 8.0D));
    }

}
