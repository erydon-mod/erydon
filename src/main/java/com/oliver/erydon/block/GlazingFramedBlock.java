package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

@SuppressWarnings("deprecation")
public class GlazingFramedBlock extends Block implements Waterloggable {

    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final BooleanProperty NORTH = BooleanProperty.of("north");
    public static final BooleanProperty SOUTH = BooleanProperty.of("south");
    public static final BooleanProperty EAST = BooleanProperty.of("east");
    public static final BooleanProperty WEST = BooleanProperty.of("west");
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    private static final VoxelShape NORTH_SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 1);
    private static final VoxelShape SOUTH_SHAPE = Block.createCuboidShape(0, 0, 15, 16, 16, 16);
    private static final VoxelShape WEST_SHAPE = Block.createCuboidShape(0, 0, 0, 1, 16, 16);
    private static final VoxelShape EAST_SHAPE = Block.createCuboidShape(15, 0, 0, 16, 16, 16);
    private static final VoxelShape TOP_SHAPE = Block.createCuboidShape(0, 15, 0, 16, 16, 16);
    private static final VoxelShape BOTTOM_SHAPE = Block.createCuboidShape(0, 0, 0, 16, 1, 16);
    private static final VoxelShape[] SHAPES = new VoxelShape[64];

    // Keeps the block easy to target when adding another face to the same block.
    private static final VoxelShape TARGET_POST = Block.createCuboidShape(6, 0, 6, 10, 16, 10);
    private static final double FACE_EPS = 1.0e-2;

    static {
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = VoxelShapes.empty();

            if ((mask & bit(Direction.NORTH)) != 0) shape = VoxelShapes.union(shape, NORTH_SHAPE);
            if ((mask & bit(Direction.SOUTH)) != 0) shape = VoxelShapes.union(shape, SOUTH_SHAPE);
            if ((mask & bit(Direction.EAST)) != 0) shape = VoxelShapes.union(shape, EAST_SHAPE);
            if ((mask & bit(Direction.WEST)) != 0) shape = VoxelShapes.union(shape, WEST_SHAPE);
            if ((mask & bit(Direction.UP)) != 0) shape = VoxelShapes.union(shape, TOP_SHAPE);
            if ((mask & bit(Direction.DOWN)) != 0) shape = VoxelShapes.union(shape, BOTTOM_SHAPE);

            SHAPES[mask] = shape;
        }
    }

    public GlazingFramedBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(WATERLOGGED, false)
                .with(NORTH, false)
                .with(SOUTH, false)
                .with(EAST, false)
                .with(WEST, false)
                .with(UP, false)
                .with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.union(shapeFor(state), TARGET_POST);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.union(shapeFor(state), TARGET_POST);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (!ctx.getStack().isOf(this.asItem()) || faceCount(state) >= 6) {
            return false;
        }

        // Clicking a pane edge should place into the next block instead of adding to this one.
        // The center helper post still works for same-block additions.
        if (isPaneEdgeClick(ctx)) {
            return false;
        }

        Direction want = requestedFace(state, ctx);
        return want != null && !state.get(prop(want));
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        BlockState existing = ctx.getWorld().getBlockState(pos);

        if (existing.isOf(this)) {
            return nextState(existing, ctx);
        }

        boolean water = ctx.getWorld().getFluidState(pos).getFluid() == Fluids.WATER;
        BlockState state = this.getDefaultState().with(WATERLOGGED, water);

        // First placement uses the player's look direction so the pane appears on the far side.
        Direction first = bestAwayFromPlayerAvailableFace(state, ctx.getPlayer());
        if (first == null) {
            first = Direction.SOUTH;
        }

        return state.with(prop(first), true);
    }

    private BlockState nextState(BlockState state, ItemPlacementContext ctx) {
        if (faceCount(state) >= 6) {
            return state;
        }

        Direction want = requestedFace(state, ctx);
        if (want != null && !state.get(prop(want))) {
            return state.with(prop(want), true);
        }

        return state;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return withFaceMask(state, rotateMask(faceMask(state), rotation));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return withFaceMask(state, mirrorMask(faceMask(state), mirror));
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbour,
                                                WorldAccess world, BlockPos pos, BlockPos neighbourPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, dir, neighbour, world, pos, neighbourPos);
    }

    private static VoxelShape shapeFor(BlockState state) {
        return SHAPES[faceMask(state)];
    }

    private static int faceMask(BlockState state) {
        int mask = 0;
        if (state.get(NORTH)) mask |= bit(Direction.NORTH);
        if (state.get(SOUTH)) mask |= bit(Direction.SOUTH);
        if (state.get(EAST)) mask |= bit(Direction.EAST);
        if (state.get(WEST)) mask |= bit(Direction.WEST);
        if (state.get(UP)) mask |= bit(Direction.UP);
        if (state.get(DOWN)) mask |= bit(Direction.DOWN);
        return mask;
    }

    private static int faceCount(BlockState state) {
        int count = 0;
        if (state.get(NORTH)) count++;
        if (state.get(SOUTH)) count++;
        if (state.get(EAST)) count++;
        if (state.get(WEST)) count++;
        if (state.get(UP)) count++;
        if (state.get(DOWN)) count++;
        return count;
    }

    private static int bit(Direction direction) {
        return switch (direction) {
            case NORTH -> 1;
            case SOUTH -> 2;
            case EAST -> 4;
            case WEST -> 8;
            case UP -> 16;
            case DOWN -> 32;
            default -> 0;
        };
    }

    private static BooleanProperty prop(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
            default -> throw new IllegalArgumentException("Unexpected direction: " + direction);
        };
    }

    private static Direction requestedFace(BlockState state, ItemPlacementContext ctx) {
        if (isAdjacentFaceClick(ctx)) {
            return adjacentClickedFace(ctx);
        }

        Direction side = ctx.getSide();
        if (side != null) {
            return side;
        }

        return bestAwayFromPlayerAvailableFace(state, ctx.getPlayer());
    }

    private static Direction bestAwayFromPlayerAvailableFace(BlockState state, PlayerEntity player) {
        if (player == null) {
            if (!state.get(SOUTH)) return Direction.SOUTH;
            if (!state.get(NORTH)) return Direction.NORTH;
            if (!state.get(WEST)) return Direction.WEST;
            if (!state.get(EAST)) return Direction.EAST;
            if (!state.get(DOWN)) return Direction.DOWN;
            if (!state.get(UP)) return Direction.UP;
            return null;
        }

        Vec3d look = player.getRotationVec(1.0f);
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};

        double bestScore = -9999.0;
        Direction best = null;

        for (Direction direction : directions) {
            if (state.get(prop(direction))) {
                continue;
            }

            Vec3d normal = new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
            double score = normal.dotProduct(look);
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }

        return best;
    }

    private static Direction boundaryFaceFromHitPos(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        Vec3d hit = ctx.getHitPos();

        double rx = hit.x - pos.getX();
        double ry = hit.y - pos.getY();
        double rz = hit.z - pos.getZ();

        Direction best = null;
        double bestDist = FACE_EPS + 1.0;

        best = closerBoundary(best, bestDist, Direction.WEST, Math.abs(rx - 0.0));
        if (best != null) bestDist = boundaryDistance(best, rx, ry, rz);

        Direction east = closerBoundary(best, bestDist, Direction.EAST, Math.abs(rx - 1.0));
        if (east != best) {
            best = east;
            bestDist = boundaryDistance(best, rx, ry, rz);
        }

        Direction down = closerBoundary(best, bestDist, Direction.DOWN, Math.abs(ry - 0.0));
        if (down != best) {
            best = down;
            bestDist = boundaryDistance(best, rx, ry, rz);
        }

        Direction up = closerBoundary(best, bestDist, Direction.UP, Math.abs(ry - 1.0));
        if (up != best) {
            best = up;
            bestDist = boundaryDistance(best, rx, ry, rz);
        }

        Direction north = closerBoundary(best, bestDist, Direction.NORTH, Math.abs(rz - 0.0));
        if (north != best) {
            best = north;
            bestDist = boundaryDistance(best, rx, ry, rz);
        }

        Direction south = closerBoundary(best, bestDist, Direction.SOUTH, Math.abs(rz - 1.0));
        if (south != best) {
            best = south;
        }

        return best;
    }

    private static Direction closerBoundary(Direction currentBest, double currentBestDist, Direction candidate, double candidateDist) {
        if (candidateDist > FACE_EPS) {
            return currentBest;
        }
        if (currentBest == null) {
            return candidate;
        }
        if (candidateDist + 1.0e-6 < currentBestDist) {
            return candidate;
        }
        if (Math.abs(candidateDist - currentBestDist) <= 1.0e-6
                && candidate.getAxis() == Direction.Axis.Y
                && currentBest.getAxis() != Direction.Axis.Y) {
            return candidate;
        }
        return currentBest;
    }

    private static double boundaryDistance(Direction direction, double rx, double ry, double rz) {
        return switch (direction) {
            case WEST -> Math.abs(rx - 0.0);
            case EAST -> Math.abs(rx - 1.0);
            case DOWN -> Math.abs(ry - 0.0);
            case UP -> Math.abs(ry - 1.0);
            case NORTH -> Math.abs(rz - 0.0);
            case SOUTH -> Math.abs(rz - 1.0);
            default -> FACE_EPS + 1.0;
        };
    }

    private static boolean isPaneEdgeClick(ItemPlacementContext ctx) {
        Direction boundary = boundaryFaceFromHitPos(ctx);
        if (boundary == null) {
            return false;
        }

        // Top/bottom clicks on the center helper post should still edit this block.
        if (boundary == Direction.UP || boundary == Direction.DOWN) {
            BlockPos pos = ctx.getBlockPos();
            Vec3d hit = ctx.getHitPos();
            double rx = hit.x - pos.getX();
            double rz = hit.z - pos.getZ();

            return rx < 0.375 || rx > 0.625 || rz < 0.375 || rz > 0.625;
        }

        return true;
    }

    private static boolean isAdjacentFaceClick(ItemPlacementContext ctx) {
        Direction boundary = boundaryFaceFromHitPos(ctx);
        return boundary != null && boundary == ctx.getSide().getOpposite();
    }

    private static Direction adjacentClickedFace(ItemPlacementContext ctx) {
        return boundaryFaceFromHitPos(ctx);
    }

    private static BlockState withFaceMask(BlockState state, int mask) {
        return state
                .with(NORTH, (mask & bit(Direction.NORTH)) != 0)
                .with(SOUTH, (mask & bit(Direction.SOUTH)) != 0)
                .with(EAST, (mask & bit(Direction.EAST)) != 0)
                .with(WEST, (mask & bit(Direction.WEST)) != 0)
                .with(UP, (mask & bit(Direction.UP)) != 0)
                .with(DOWN, (mask & bit(Direction.DOWN)) != 0);
    }

    private static int rotateMask(int mask, BlockRotation rotation) {
        int rotatedMask = 0;
        for (Direction direction : Direction.values()) {
            if ((mask & bit(direction)) != 0) {
                rotatedMask |= bit(rotateDirection(direction, rotation));
            }
        }
        return rotatedMask;
    }

    private static int mirrorMask(int mask, BlockMirror mirror) {
        int mirroredMask = 0;
        for (Direction direction : Direction.values()) {
            if ((mask & bit(direction)) != 0) {
                mirroredMask |= bit(mirror.apply(direction));
            }
        }
        return mirroredMask;
    }

    private static Direction rotateDirection(Direction direction, BlockRotation rotation) {
        return direction.getAxis().isHorizontal() ? rotation.rotate(direction) : direction;
    }
}
