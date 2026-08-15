package com.oliver.erydon.block;

import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CorniceBlock extends HorizontalFacingBlock implements ClusterRebuildableBlock {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<CorniceShape> SHAPE =
            EnumProperty.of("shape", CorniceShape.class);

    private enum CorniceProfile {
        MODERN,
        GEORGIAN,
        GUILLOCHE,
        GOTHIC
    }

    private static final double[][] MODERN_STRAIGHT_BOXES = new double[][] {
            {0.0D, 8.0D, 0.0D, 16.0D, 10.0D, 2.0D},
            {0.0D, 10.0D, 0.0D, 16.0D, 12.0D, 4.0D},
            {0.0D, 12.0D, 0.0D, 16.0D, 14.0D, 6.0D},
            {0.0D, 14.0D, 0.0D, 16.0D, 16.0D, 8.0D}
    };

    private static final double[][] MODERN_OUTER_BOXES = new double[][] {
            {0.0D, 8.0D, 0.0D, 2.0D, 10.0D, 2.0D},
            {0.0D, 10.0D, 0.0D, 4.0D, 12.0D, 4.0D},
            {0.0D, 12.0D, 0.0D, 6.0D, 14.0D, 6.0D},
            {0.0D, 14.0D, 0.0D, 8.0D, 16.0D, 8.0D}
    };

    private static final double[][] GEORGIAN_STRAIGHT_BOXES = new double[][] {
            {0.0D, 10.0D, 1.0D, 16.0D, 15.0D, 2.0D},
            {0.0D, 11.0D, 2.0D, 16.0D, 15.0D, 3.0D},
            {0.0D, 8.0D, 0.0D, 16.0D, 15.0D, 1.0D},
            {0.5D, 10.0D, 2.0D, 1.5D, 11.0D, 3.0D},
            {2.5D, 10.0D, 2.0D, 3.5D, 11.0D, 3.0D},
            {4.5D, 10.0D, 2.0D, 5.5D, 11.0D, 3.0D},
            {6.5D, 10.0D, 2.0D, 7.5D, 11.0D, 3.0D},
            {12.5D, 10.0D, 2.0D, 13.5D, 11.0D, 3.0D},
            {10.5D, 10.0D, 2.0D, 11.5D, 11.0D, 3.0D},
            {8.5D, 10.0D, 2.0D, 9.5D, 11.0D, 3.0D},
            {14.5D, 10.0D, 2.0D, 15.5D, 11.0D, 3.0D},
            {0.0D, 8.0D, 1.0D, 16.0D, 10.0D, 2.0D},
            {0.0D, 6.0D, 0.0D, 16.0D, 8.0D, 1.0D},
            {0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 5.0D}
    };

    private static final double[][] GEORGIAN_OUTER_BOXES = new double[][] {
            {13.0D, 10.004D, 0.5D, 14.0D, 11.004D, 1.5D},
            {13.0D, 11.0D, 0.0D, 16.0D, 15.0D, 3.0D},
            {11.0D, 15.0D, 0.0D, 16.0D, 16.0D, 5.0D},
            {14.0D, 8.0D, 0.0D, 16.0D, 11.0D, 2.0D},
            {14.5D, 10.0D, 2.0D, 15.5D, 11.0D, 3.0D},
            {15.0D, 6.0D, 0.0D, 16.0D, 8.0D, 1.0D}
    };

    private static final double[][] GUILLOCHE_STRAIGHT_BOXES = new double[][] {
            {0.0D, 9.5D, 1.0D, 16.0D, 14.5D, 3.0D},
            {0.0D, 5.5D, 0.0D, 16.0D, 15.5D, 1.0D},
            {0.0D, 14.5D, 1.0D, 16.0D, 15.5D, 4.0D},
            {0.0D, 7.5D, 1.0D, 16.0D, 9.5D, 3.0D},
            {0.01D, 8.5D, 2.0D, 15.99D, 9.5D, 4.0D},
            {0.0D, 15.5D, 0.0D, 16.0D, 16.0D, 5.5D},
            {1.0D, 11.5D, 3.0D, 2.5D, 12.5D, 4.0D},
            {3.01D, 9.49D, 3.0D, 4.01D, 10.99D, 3.8D},
            {0.01D, 13.01D, 3.0D, 0.51D, 14.51D, 3.8D},
            {0.01D, 9.49D, 3.0D, 0.51D, 10.99D, 3.8D},
            {4.5D, 11.5D, 3.0D, 6.0D, 12.5D, 4.0D},
            {6.51D, 9.49D, 3.0D, 9.51D, 10.99D, 3.8D},
            {3.01D, 13.01D, 3.0D, 4.01D, 14.51D, 3.8D},
            {10.0D, 11.5D, 3.0D, 11.5D, 12.5D, 4.0D},
            {12.01D, 9.49D, 3.0D, 13.01D, 10.99D, 3.8D},
            {6.51D, 13.01D, 3.0D, 9.51D, 14.51D, 3.8D},
            {13.5D, 11.5D, 3.0D, 15.0D, 12.5D, 4.0D},
            {15.51D, 9.49D, 3.0D, 16.0D, 10.99D, 3.8D},
            {12.01D, 13.01D, 3.0D, 13.01D, 14.51D, 3.8D},
            {15.51D, 13.01D, 3.0D, 16.0D, 14.51D, 3.8D}
    };

    private static final double[][] GUILLOCHE_OUTER_BOXES = new double[][] {
            {10.5D, 15.5D, 0.0D, 16.0D, 16.0D, 5.5D},
            {14.0D, 6.5D, 0.0D, 16.0D, 7.5D, 2.0D},
            {13.0D, 9.5D, 0.0D, 16.0D, 14.5D, 3.0D},
            {15.0D, 5.5D, 0.0D, 16.0D, 6.5D, 1.0D},
            {12.0D, 14.5D, 0.0D, 16.0D, 15.5D, 4.0D},
            {13.0D, 7.5D, 0.0D, 16.0D, 8.5D, 3.0D},
            {12.0D, 8.5D, 0.0D, 16.0D, 9.5D, 4.0D},
            {15.51367D, 13.01367D, 3.0D, 16.01367D, 14.51367D, 3.8D},
            {13.5D, 11.5D, 3.0D, 15.0D, 12.5D, 3.6D},
            {12.4D, 11.5D, 1.0D, 13.0D, 12.5D, 2.5D},
            {12.2D, 9.48633D, 0.01833D, 13.0D, 10.98633D, 0.48633D},
            {12.2D, 12.98633D, 0.00233D, 13.0D, 14.48633D, 0.48633D},
            {15.51367D, 9.48633D, 3.0D, 16.01367D, 10.98633D, 3.8D}
    };

    private static final double[][] GOTHIC_STRAIGHT_BOXES = new double[][] {
            {0.0D, 8.0D, 0.0D, 16.0D, 15.0D, 2.0D},
            {0.0D, 7.0D, 0.0D, 16.0D, 8.0D, 2.80268D},
            {0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 5.0D},
            {0.0D, 4.30533D, 0.0D, 16.0D, 7.0D, 1.03067D}
    };

    private static final double[][] GOTHIC_OUTER_BOXES = new double[][] {
            {15.73D, 8.0D, 1.79999D, 16.0D, 9.09408D, 2.79999D},
            {14.02667D, 8.0D, 0.0D, 16.0D, 15.0D, 1.96401D},
            {13.19733D, 7.0D, 0.0D, 16.0D, 8.0D, 2.79999D},
            {11.0D, 15.0D, 0.0D, 16.0D, 16.0D, 5.0D},
            {14.96934D, 4.30533D, 0.0D, 16.0D, 7.0D, 1.02667D},
            {13.19733D, 8.0D, 1.81067D, 14.19733D, 9.09408D, 2.79999D},
            {13.19733D, 8.0D, 0.0D, 14.19733D, 9.09408D, 0.26999D}
    };

    private static final VoxelShape[] MODERN_RUN_SHAPES = orientedShapes(Direction.NORTH, createShape(MODERN_STRAIGHT_BOXES));
    private static final VoxelShape[] MODERN_INNER_SHAPES = innerShapes(MODERN_RUN_SHAPES);
    private static final VoxelShape[] MODERN_OUTER_SHAPES = orientedShapes(Direction.NORTH, createShape(MODERN_OUTER_BOXES));

    private static final VoxelShape[] GEORGIAN_RUN_SHAPES = orientedShapes(Direction.NORTH, createShape(GEORGIAN_STRAIGHT_BOXES));
    private static final VoxelShape[] GEORGIAN_INNER_SHAPES = innerShapes(GEORGIAN_RUN_SHAPES);
    private static final VoxelShape[] GEORGIAN_OUTER_SHAPES = orientedShapes(Direction.EAST, createShape(GEORGIAN_OUTER_BOXES));

    private static final VoxelShape[] GUILLOCHE_RUN_SHAPES = orientedShapes(Direction.NORTH, createShape(GUILLOCHE_STRAIGHT_BOXES));
    private static final VoxelShape[] GUILLOCHE_INNER_SHAPES = innerShapes(GUILLOCHE_RUN_SHAPES);
    private static final VoxelShape[] GUILLOCHE_OUTER_SHAPES = orientedShapes(Direction.EAST, createShape(GUILLOCHE_OUTER_BOXES));

    private static final VoxelShape[] GOTHIC_RUN_SHAPES = orientedShapes(Direction.NORTH, createShape(GOTHIC_STRAIGHT_BOXES));
    private static final VoxelShape[] GOTHIC_INNER_SHAPES = innerShapes(GOTHIC_RUN_SHAPES);
    private static final VoxelShape[] GOTHIC_OUTER_SHAPES = orientedShapes(Direction.EAST, createShape(GOTHIC_OUTER_BOXES));

    private static VoxelShape createShape(double[][] boxes) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] box : boxes) {
            shape = VoxelShapes.combine(
                    shape,
                    Block.createCuboidShape(box[0], box[1], box[2], box[3], box[4], box[5]),
                    BooleanBiFunction.OR
            );
        }
        return shape.simplify();
    }

    private static VoxelShape[] orientedShapes(Direction baseFacing, VoxelShape baseShape) {
        VoxelShape[] shapes = new VoxelShape[4];
        int baseIndex = facingIndex(baseFacing);
        shapes[baseIndex] = baseShape;
        for (int step = 1; step < 4; step++) {
            int previousIndex = (baseIndex + step - 1) % 4;
            int currentIndex = (baseIndex + step) % 4;
            shapes[currentIndex] = rotateYClockwise(shapes[previousIndex]);
        }
        return shapes;
    }

    private static VoxelShape[] innerShapes(VoxelShape[] runShapes) {
        VoxelShape[] shapes = new VoxelShape[4];
        for (Direction facing : Direction.Type.HORIZONTAL) {
            shapes[facingIndex(facing)] = VoxelShapes.combine(
                    runShape(runShapes, facing),
                    runShape(runShapes, facing.rotateYCounterclockwise()),
                    BooleanBiFunction.OR
            ).simplify();
        }
        return shapes;
    }

    private static VoxelShape rotateYClockwise(VoxelShape shape) {
        final VoxelShape[] acc = new VoxelShape[] {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double nextMinX = 1.0D - maxZ;
            double nextMaxX = 1.0D - minZ;
            double nextMinZ = minX;
            double nextMaxZ = maxX;
            acc[0] = VoxelShapes.combine(
                    acc[0],
                    VoxelShapes.cuboid(nextMinX, minY, nextMinZ, nextMaxX, maxY, nextMaxZ),
                    BooleanBiFunction.OR
            );
        });
        return acc[0].simplify();
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

    private static VoxelShape runShape(VoxelShape[] shapes, Direction facing) {
        return shapes[facingIndex(facing)];
    }

    private static CorniceProfile resolveProfile(BlockState state) {
        String path = ErydonIdMigration.legacyResourcePath(
                Registries.BLOCK.getId(state.getBlock()).getPath());
        if (path.contains("_cornice_georgian")) {
            return CorniceProfile.GEORGIAN;
        }
        if (path.contains("_cornice_guilloche")) {
            return CorniceProfile.GUILLOCHE;
        }
        if (path.contains("_cornice_gothic")) {
            return CorniceProfile.GOTHIC;
        }
        return CorniceProfile.MODERN;
    }

    private static VoxelShape[] runShapes(CorniceProfile profile) {
        return switch (profile) {
            case GEORGIAN -> GEORGIAN_RUN_SHAPES;
            case GUILLOCHE -> GUILLOCHE_RUN_SHAPES;
            case GOTHIC -> GOTHIC_RUN_SHAPES;
            case MODERN -> MODERN_RUN_SHAPES;
        };
    }

    private static VoxelShape[] innerShapes(CorniceProfile profile) {
        return switch (profile) {
            case GEORGIAN -> GEORGIAN_INNER_SHAPES;
            case GUILLOCHE -> GUILLOCHE_INNER_SHAPES;
            case GOTHIC -> GOTHIC_INNER_SHAPES;
            case MODERN -> MODERN_INNER_SHAPES;
        };
    }

    private static VoxelShape[] outerShapes(CorniceProfile profile) {
        return switch (profile) {
            case GEORGIAN -> GEORGIAN_OUTER_SHAPES;
            case GUILLOCHE -> GUILLOCHE_OUTER_SHAPES;
            case GOTHIC -> GOTHIC_OUTER_SHAPES;
            case MODERN -> MODERN_OUTER_SHAPES;
        };
    }

    public CorniceBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(SHAPE, CorniceShape.STRAIGHT));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        EnumSet<Direction> mirroredWalls = EnumSet.noneOf(Direction.class);
        for (Direction wall : coveredWalls(state)) {
            mirroredWalls.add(mirrorWall(wall, mirror));
        }

        return state.with(FACING, facingFromWalls(mirroredWalls));
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction preferredWall = getPreferredWall(ctx);
        BlockState resolved = this.resolveState(
                this.getDefaultState().with(FACING, preferredWall),
                ctx.getWorld(),
                ctx.getBlockPos(),
                preferredWall
        );

        return resolved.canPlaceAt(ctx.getWorld(), ctx.getBlockPos()) ? resolved : null;
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return this.hasAttachment(state, world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            WorldAccess world,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        BlockState resolved = this.resolveState(state, world, pos, state.get(FACING));
        return resolved.canPlaceAt(world, pos) ? resolved : net.minecraft.block.Blocks.AIR.getDefaultState();
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world.isClient) {
            return;
        }

        refreshCorniceAt(world, pos);
        refreshNearbyCornices(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient) {
            if (state.getBlock() instanceof CorniceBlock) {
                refreshNearbyCornices(world, pos);
            }

            if (newState.getBlock() instanceof CorniceBlock) {
                refreshCorniceAt(world, pos);
                refreshNearbyCornices(world, pos);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }

    private BlockState resolveState(BlockState state, BlockView world, BlockPos pos, Direction preferredWall) {
        boolean northWall = isWall(world, pos, Direction.NORTH);
        boolean eastWall = isWall(world, pos, Direction.EAST);
        boolean southWall = isWall(world, pos, Direction.SOUTH);
        boolean westWall = isWall(world, pos, Direction.WEST);

        if (northWall && westWall) {
            return state.with(FACING, Direction.NORTH).with(SHAPE, CorniceShape.INNER_CORNER);
        }

        if (northWall && eastWall) {
            return state.with(FACING, Direction.EAST).with(SHAPE, CorniceShape.INNER_CORNER);
        }

        if (eastWall && southWall) {
            return state.with(FACING, Direction.SOUTH).with(SHAPE, CorniceShape.INNER_CORNER);
        }

        if (southWall && westWall) {
            return state.with(FACING, Direction.WEST).with(SHAPE, CorniceShape.INNER_CORNER);
        }

        Direction outerFacing = this.resolveOuterFacing(world, pos, northWall, eastWall, southWall, westWall);
        if (outerFacing != null) {
            return state.with(FACING, outerFacing).with(SHAPE, CorniceShape.OUTER_CORNER);
        }

        Direction straightFacing = resolveStraightFacing(
                northWall,
                eastWall,
                southWall,
                westWall,
                preferredWall,
                state.get(FACING)
        );

        return state.with(FACING, straightFacing).with(SHAPE, CorniceShape.STRAIGHT);
    }

    private Direction resolveOuterFacing(BlockView world, BlockPos pos,
                                         boolean northWall, boolean eastWall, boolean southWall, boolean westWall) {
        if (this.hasOuterCorner(world, pos, Direction.NORTH, Direction.EAST, northWall, eastWall)) {
            return Direction.EAST;
        }

        if (this.hasOuterCorner(world, pos, Direction.SOUTH, Direction.EAST, southWall, eastWall)) {
            return Direction.SOUTH;
        }

        if (this.hasOuterCorner(world, pos, Direction.SOUTH, Direction.WEST, southWall, westWall)) {
            return Direction.WEST;
        }

        if (this.hasOuterCorner(world, pos, Direction.NORTH, Direction.WEST, northWall, westWall)) {
            return Direction.NORTH;
        }

        return null;
    }

    private boolean hasOuterCorner(BlockView world, BlockPos pos, Direction first, Direction second,
                                   boolean firstWall, boolean secondWall) {
        if (firstWall || secondWall) {
            return false;
        }

        if (!hasFilledCorner(world, pos, first, second)) {
            return false;
        }

        return this.hasCorniceRun(world, pos.offset(first), second)
                && this.hasCorniceRun(world, pos.offset(second), first);
    }

    private boolean hasCorniceRun(BlockView world, BlockPos pos, Direction wallDirection) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(this)) {
            return this.coversWall(state, wallDirection);
        }

        return isWall(world, pos, wallDirection);
    }

    private boolean coversWall(BlockState state, Direction wallDirection) {
        if (!state.isOf(this)) {
            return false;
        }

        Direction facing = state.get(FACING);
        CorniceShape shape = state.get(SHAPE);

        if (shape == CorniceShape.STRAIGHT) {
            return facing == wallDirection;
        }

        return wallDirection == facing || wallDirection == facing.rotateYCounterclockwise();
    }

    private boolean hasAttachment(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);
        CorniceShape shape = state.get(SHAPE);

        if (shape == CorniceShape.STRAIGHT) {
            return isWall(world, pos, facing);
        }

        if (shape == CorniceShape.INNER_CORNER) {
            return isWall(world, pos, facing) && isWall(world, pos, facing.rotateYCounterclockwise());
        }

        Direction first = facing.rotateYCounterclockwise();
        Direction second = facing;
        return this.hasOuterCorner(
                world,
                pos,
                first,
                second,
                isWall(world, pos, first),
                isWall(world, pos, second)
        );
    }

    private static Direction resolveStraightFacing(boolean northWall, boolean eastWall, boolean southWall, boolean westWall,
                                                   Direction preferredWall, Direction fallbackWall) {
        if (hasWall(preferredWall, northWall, eastWall, southWall, westWall)) {
            return preferredWall;
        }

        if (hasWall(fallbackWall, northWall, eastWall, southWall, westWall)) {
            return fallbackWall;
        }

        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (hasWall(direction, northWall, eastWall, southWall, westWall)) {
                return direction;
            }
        }

        return preferredWall;
    }

    private static boolean hasWall(Direction direction, boolean northWall, boolean eastWall, boolean southWall, boolean westWall) {
        return switch (direction) {
            case NORTH -> northWall;
            case EAST -> eastWall;
            case SOUTH -> southWall;
            case WEST -> westWall;
            default -> false;
        };
    }

    private static Direction getPreferredWall(ItemPlacementContext ctx) {
        Direction side = ctx.getSide();
        if (side.getAxis().isHorizontal()) {
            return side.getOpposite();
        }

        return ctx.getHorizontalPlayerFacing().getOpposite();
    }

    private static boolean isWall(BlockView world, BlockPos pos, Direction direction) {
        BlockPos wallPos = pos.offset(direction);
        BlockState wallState = world.getBlockState(wallPos);
        return wallState.isSideSolidFullSquare(world, wallPos, direction.getOpposite());
    }

    private static boolean hasFilledCorner(BlockView world, BlockPos pos, Direction first, Direction second) {
        BlockPos cornerPos = pos.offset(first).offset(second);
        BlockState cornerState = world.getBlockState(cornerPos);
        return cornerState.isSideSolidFullSquare(world, cornerPos, first.getOpposite())
                && cornerState.isSideSolidFullSquare(world, cornerPos, second.getOpposite());
    }

    private static EnumSet<Direction> coveredWalls(BlockState state) {
        EnumSet<Direction> walls = EnumSet.of(state.get(FACING));
        if (state.get(SHAPE) != CorniceShape.STRAIGHT) {
            walls.add(state.get(FACING).rotateYCounterclockwise());
        }
        return walls;
    }

    private static Direction mirrorWall(Direction wall, BlockMirror mirror) {
        return mirror.apply(wall);
    }

    private static Direction facingFromWalls(EnumSet<Direction> walls) {
        if (walls.size() == 1) {
            return walls.iterator().next();
        }
        if (walls.contains(Direction.NORTH) && walls.contains(Direction.WEST)) {
            return Direction.NORTH;
        }
        if (walls.contains(Direction.NORTH) && walls.contains(Direction.EAST)) {
            return Direction.EAST;
        }
        if (walls.contains(Direction.SOUTH) && walls.contains(Direction.EAST)) {
            return Direction.SOUTH;
        }
        if (walls.contains(Direction.SOUTH) && walls.contains(Direction.WEST)) {
            return Direction.WEST;
        }
        return walls.iterator().next();
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> component = collectPlanarComponent(world, seed);
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
            refreshCorniceAt(world, pos);
        }
        return new ClusterRecalcResult(component, true);
    }

    private Set<BlockPos> collectPlanarComponent(WorldAccess world, BlockPos seed) {
        Set<BlockPos> component = new LinkedHashSet<>();
        if (!ClusterRecalcSafety.getBlockState(world, seed).isOf(this)) {
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
            for (BlockPos neighbor : planarNeighbours(pos)) {
                if (component.contains(neighbor)
                        || !ClusterRecalcSafety.getBlockState(world, neighbor).isOf(this)) {
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

    private static void refreshNearbyCornices(WorldAccess world, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            refreshCorniceAt(world, pos.offset(direction));
        }

        refreshCorniceAt(world, pos.north().east());
        refreshCorniceAt(world, pos.east().south());
        refreshCorniceAt(world, pos.south().west());
        refreshCorniceAt(world, pos.west().north());
    }

    private static void refreshCorniceAt(WorldAccess world, BlockPos pos) {
        BlockState current = world.getBlockState(pos);
        if (!(current.getBlock() instanceof CorniceBlock cornice)) {
            return;
        }

        BlockState resolved = cornice.resolveState(current, world, pos, current.get(FACING));
        if (!resolved.equals(current)) {
            world.setBlockState(pos, resolved, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
        }
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction facing = state.get(FACING);
        CorniceShape shape = state.get(SHAPE);
        CorniceProfile profile = resolveProfile(state);

        if (shape == CorniceShape.OUTER_CORNER) {
            return runShape(outerShapes(profile), facing);
        }

        if (shape == CorniceShape.INNER_CORNER) {
            return runShape(innerShapes(profile), facing);
        }

        return runShape(runShapes(profile), facing);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    public enum CorniceShape implements StringIdentifiable {
        STRAIGHT("straight"),
        INNER_CORNER("inner_corner"),
        OUTER_CORNER("outer_corner");

        private final String name;

        CorniceShape(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}
