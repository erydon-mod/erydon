package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.block.enums.WallShape;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects the support patterns used by Georgian walls on 1:2 and 1:1 stairs.
 * No extra persisted blockstate is needed: the render choice is derived from
 * the live run.
 */
public final class GeorgianWallSlopeResolver {
    private static final double EPSILON = 0.0001D;

    private GeorgianWallSlopeResolver() {
    }

    public static Mode resolve(BlockView world, BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof DiagonalWallBlock)) {
            return Mode.NONE;
        }

        Part supportPart = partForSupport(world, pos.down());
        SlopeCandidate candidate = findSlopeCandidate(world, pos, supportPart);
        if (candidate == null
                || candidate.profile() == Profile.NONE
                || !isSlopeCompatible(state)) {
            return Mode.NONE;
        }
        if (candidate.profile() == Profile.STEEP_45) {
            supportPart = Part.LOWER;
        } else if (supportPart == Part.NONE) {
            return Mode.NONE;
        }

        // A lower 27-degree piece which has only an upper piece behind it is
        // the first flat wall at the top, not another incline piece. This is
        // what guarantees that a shallow run finishes on its upper model.
        if (isShallowFlatContinuation(world, pos, supportPart, candidate)) {
            return Mode.NONE;
        }

        boolean flatBelow = hasFlatWall(
                world,
                lowFlatPos(pos, candidate)
        );
        boolean flatAbove = hasFlatWall(
                world,
                pos.offset(candidate.uphill())
        );
        boolean hasFlatCorner = !flatCornerDirections(state, candidate.uphill()).isEmpty();
        boolean continuesUphill = hasSlopeNeighbour(world, pos, supportPart, candidate, true);
        boolean continuesDownhill = hasSlopeNeighbour(world, pos, supportPart, candidate, false);
        flatBelow |= isLowCornerBoundary(hasFlatCorner, continuesUphill, continuesDownhill);
        flatAbove |= isHighCornerBoundary(hasFlatCorner, continuesUphill, continuesDownhill);
        flatBelow |= isLowShallowTermination(
                candidate.profile(),
                continuesUphill,
                continuesDownhill
        );
        flatAbove |= isHighShallowTermination(
                candidate.profile(),
                continuesUphill,
                continuesDownhill
        );
        boolean steepOnrampAnchor = candidate.profile() == Profile.STEEP_45
                && isSteepOnrampAnchor(world, pos, candidate.uphill());

        Variant variant = transitionVariant(
                supportPart,
                candidate.profile(),
                flatBelow,
                flatAbove,
                steepOnrampAnchor
        );
        return modeForSlope(supportPart, candidate.uphill(), candidate.profile(), variant);
    }

    static Mode modeForSlope(Part supportPart,
                             Direction uphill,
                             Profile profile,
                             Variant variant) {
        if (supportPart == Part.NONE
                || uphill == null
                || profile == null
                || profile == Profile.NONE
                || variant == null) {
            return Mode.NONE;
        }

        if (profile == Profile.SHALLOW_27) {
            if (variant == Variant.ONRAMP && supportPart != Part.LOWER) {
                return Mode.NONE;
            }
            if (variant == Variant.OFFRAMP && supportPart != Part.UPPER) {
                return Mode.NONE;
            }
        } else if (supportPart != Part.LOWER) {
            return Mode.NONE;
        }

        return new Mode(supportPart, uphill, profile, variant);
    }

    public static Part partForSupport(BlockView world, BlockPos supportPos) {
        BlockState support = world.getBlockState(supportPos);
        // A Georgian wall is never a stair support. Avoid recursively asking a
        // stacked wall for its own world-derived interaction shape.
        if (support.getBlock() instanceof DiagonalWallBlock) {
            return Part.NONE;
        }
        if (support.getBlock() instanceof ShallowStairsBlockBase shallowStair) {
            StairShape shape = support.contains(Properties.STAIR_SHAPE)
                    ? support.get(Properties.STAIR_SHAPE)
                    : null;
            return partForShallowStair(shallowStair.isTopHalf(), shape);
        }

        if (support.getBlock() instanceof SlabBlock && support.contains(Properties.SLAB_TYPE)) {
            return partForSlab(support.get(Properties.SLAB_TYPE));
        }

        if (support.getBlock() instanceof LayerBlock
                && support.contains(LayerBlock.LAYERS)
                && support.contains(LayerBlock.TOP)) {
            return partForLayer(
                    support.get(LayerBlock.LAYERS),
                    support.get(LayerBlock.TOP)
            );
        }

        VoxelShape outline = support.getOutlineShape(world, supportPos, ShapeContext.absent());
        if (isLowerHalfLayer(outline)) {
            return Part.UPPER;
        }

        VoxelShape collision = support.getCollisionShape(world, supportPos, ShapeContext.absent());
        return Block.isShapeFullCube(collision) ? Part.LOWER : Part.NONE;
    }

    private static SlopeCandidate findSlopeCandidate(BlockView world, BlockPos pos, Part part) {
        SlopeCandidate result = null;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (part != Part.NONE && hasShallowSlopeNeighbour(world, pos, part, direction)) {
                if (result != null) {
                    return SlopeCandidate.AMBIGUOUS;
                }
                result = new SlopeCandidate(Profile.SHALLOW_27, direction);
            }
            if (hasSteepSlopeNeighbour(world, pos, direction)) {
                if (result != null) {
                    return SlopeCandidate.AMBIGUOUS;
                }
                result = new SlopeCandidate(Profile.STEEP_45, direction);
            }
        }
        return result;
    }

    private static boolean hasShallowSlopeNeighbour(BlockView world,
                                                    BlockPos pos,
                                                    Part part,
                                                    Direction uphill) {
        if (!supportsShallowDirection(world.getBlockState(pos.down()), uphill)) {
            return false;
        }
        if (part == Part.LOWER) {
            return isShallowRunWall(world, pos.offset(uphill).up(), Part.UPPER, uphill)
                    || isShallowRunWall(world, pos.offset(uphill.getOpposite()), Part.UPPER, uphill);
        }
        return isShallowRunWall(world, pos.offset(uphill), Part.LOWER, uphill)
                || isShallowRunWall(world, pos.offset(uphill.getOpposite()).down(), Part.LOWER, uphill);
    }

    private static boolean hasSteepSlopeNeighbour(BlockView world,
                                                  BlockPos pos,
                                                  Direction uphill) {
        if (isSteepOnrampAnchor(world, pos, uphill)) {
            return true;
        }
        if (!isAlignedBottomStair(world.getBlockState(pos.down()), uphill)) {
            return false;
        }
        return isSteepRunWall(world, pos.offset(uphill).up(), uphill)
                || isSteepRunWall(world, pos.offset(uphill.getOpposite()).down(), uphill);
    }

    private static boolean isSteepOnrampAnchor(BlockView world,
                                                BlockPos pos,
                                                Direction uphill) {
        BlockState currentSupport = world.getBlockState(pos.down());
        BlockPos firstStairWallPos = pos.offset(uphill).up();
        BlockState firstStairWall = world.getBlockState(firstStairWallPos);
        boolean firstStairWallIsAligned = firstStairWall.getBlock() instanceof DiagonalWallBlock
                && isSlopeCompatible(firstStairWall)
                && isAlignedBottomStair(world.getBlockState(firstStairWallPos.down()), uphill);
        return isSteepOnrampAnchor(
                partForSupport(world, pos.down()),
                isAlignedBottomStair(currentSupport, uphill),
                firstStairWallIsAligned
        );
    }

    static boolean isSteepOnrampAnchor(Part supportPart,
                                       boolean currentSupportIsAlignedStair,
                                       boolean firstStairWallIsAligned) {
        return supportPart == Part.LOWER
                && !currentSupportIsAlignedStair
                && firstStairWallIsAligned;
    }

    private static boolean hasSlopeNeighbour(BlockView world,
                                             BlockPos pos,
                                             Part part,
                                             SlopeCandidate candidate,
                                             boolean uphillSide) {
        Direction uphill = candidate.uphill();
        Direction horizontal = uphillSide ? uphill : uphill.getOpposite();
        if (candidate.profile() == Profile.STEEP_45) {
            BlockPos neighbour = pos.offset(horizontal).add(0, uphillSide ? 1 : -1, 0);
            return isSteepRunWall(world, neighbour, uphill);
        }
        if (part == Part.LOWER) {
            BlockPos neighbour = uphillSide
                    ? pos.offset(uphill).up()
                    : pos.offset(uphill.getOpposite());
            return isShallowRunWall(world, neighbour, Part.UPPER, uphill);
        }
        if (part == Part.UPPER) {
            BlockPos neighbour = uphillSide
                    ? pos.offset(uphill)
                    : pos.offset(uphill.getOpposite()).down();
            return isShallowRunWall(world, neighbour, Part.LOWER, uphill);
        }
        return false;
    }

    private static boolean isShallowRunWall(BlockView world,
                                            BlockPos pos,
                                            Part expected,
                                            Direction uphill) {
        BlockState state = world.getBlockState(pos);
        BlockState support = world.getBlockState(pos.down());
        return state.getBlock() instanceof DiagonalWallBlock
                && partForSupport(world, pos.down()) == expected
                && supportsShallowDirection(support, uphill);
    }

    private static boolean supportsShallowDirection(BlockState support, Direction uphill) {
        if (!(support.getBlock() instanceof ShallowStairsBlockBase)) {
            return true;
        }
        return support.contains(Properties.STAIR_SHAPE)
                && support.contains(Properties.HORIZONTAL_FACING)
                && isAlignedShallowStair(
                        support.get(Properties.STAIR_SHAPE),
                        support.get(Properties.HORIZONTAL_FACING),
                        uphill
                );
    }

    private static boolean isSteepRunWall(BlockView world,
                                          BlockPos pos,
                                          Direction uphill) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DiagonalWallBlock)) {
            return false;
        }
        BlockState support = world.getBlockState(pos.down());
        return isAlignedBottomStair(support, uphill)
                || partForSupport(world, pos.down()) == Part.LOWER;
    }

    private static boolean isAlignedBottomStair(BlockState support, Direction uphill) {
        return support.contains(Properties.BLOCK_HALF)
                && support.contains(Properties.STAIR_SHAPE)
                && support.contains(Properties.HORIZONTAL_FACING)
                && isAlignedSteepStair(
                        support.getBlock() instanceof StairsBlock,
                        support.getBlock() instanceof ShallowStairsBlockBase,
                        support.get(Properties.BLOCK_HALF),
                        support.get(Properties.STAIR_SHAPE),
                        support.get(Properties.HORIZONTAL_FACING),
                        uphill
                );
    }

    private static BlockPos lowFlatPos(BlockPos pos, SlopeCandidate candidate) {
        BlockPos flatPos = pos.offset(candidate.uphill().getOpposite());
        return candidate.profile() == Profile.STEEP_45 ? flatPos.down() : flatPos;
    }

    private static boolean hasFlatWall(BlockView world, BlockPos flatPos) {
        BlockState flatState = world.getBlockState(flatPos);
        if (!(flatState.getBlock() instanceof DiagonalWallBlock)
                || !isSlopeCompatible(flatState)) {
            return false;
        }

        Part flatPart = partForSupport(world, flatPos.down());
        if (flatPart == Part.NONE) {
            return false;
        }
        SlopeCandidate candidate = findSlopeCandidate(world, flatPos, flatPart);
        return candidate == null
                || (candidate.profile() != Profile.NONE
                && isShallowFlatContinuation(world, flatPos, flatPart, candidate));
    }

    private static boolean isShallowFlatContinuation(BlockView world,
                                                     BlockPos pos,
                                                     Part part,
                                                     SlopeCandidate candidate) {
        Direction uphill = candidate.uphill();
        return isShallowFlatContinuation(
                part,
                candidate.profile(),
                isShallowRunWall(world, pos.offset(uphill.getOpposite()), Part.UPPER, uphill),
                isShallowRunWall(world, pos.offset(uphill).up(), Part.UPPER, uphill)
        );
    }

    /**
     * The wall above an ERYDON upper shallow stair uses the flat wall model at
     * the high handoff, but the support is still part of the incline. The
     * preceding upper-offramp supplies the small filler, and this position must
     * never be promoted to a stair-joint or periodic pier.
     */
    static boolean isPierFreeShallowStairEndpoint(BlockView world,
                                                  BlockState state,
                                                  BlockPos pos) {
        if (!(state.getBlock() instanceof DiagonalWallBlock)
                || !isSlopeCompatible(state)) {
            return false;
        }

        BlockState support = world.getBlockState(pos.down());
        boolean upperShallowStair = support.getBlock() instanceof ShallowStairsBlockBase shallowStair
                && shallowStair.isTopHalf();
        if (!upperShallowStair) {
            return false;
        }

        Part part = partForSupport(world, pos.down());
        SlopeCandidate candidate = findSlopeCandidate(world, pos, part);
        if (candidate == null) {
            return false;
        }

        Direction uphill = candidate.uphill();
        return isPierFreeShallowStairEndpoint(
                part,
                candidate.profile(),
                isShallowRunWall(world, pos.offset(uphill.getOpposite()), Part.UPPER, uphill),
                isShallowRunWall(world, pos.offset(uphill).up(), Part.UPPER, uphill),
                upperShallowStair
        );
    }

    static boolean isPierFreeShallowStairEndpoint(Part part,
                                                  Profile profile,
                                                  boolean hasUpperBehind,
                                                  boolean hasUpperAhead,
                                                  boolean upperShallowStair) {
        return upperShallowStair
                && isShallowFlatContinuation(part, profile, hasUpperBehind, hasUpperAhead);
    }

    static boolean isShallowFlatContinuation(Part part,
                                             Profile profile,
                                             boolean hasUpperBehind,
                                             boolean hasUpperAhead) {
        return part == Part.LOWER
                && profile == Profile.SHALLOW_27
                && hasUpperBehind
                && !hasUpperAhead;
    }

    static Variant transitionVariant(Part part,
                                     Profile profile,
                                     boolean flatBelow,
                                     boolean flatAbove) {
        return transitionVariant(part, profile, flatBelow, flatAbove, false);
    }

    static Variant transitionVariant(Part part,
                                     Profile profile,
                                     boolean flatBelow,
                                     boolean flatAbove,
                                     boolean steepOnrampAnchor) {
        if (profile == Profile.STEEP_45
                && part == Part.LOWER
                && steepOnrampAnchor) {
            return Variant.ONRAMP;
        }
        boolean supportsOnramp = part == Part.LOWER;
        boolean supportsOfframp = profile == Profile.SHALLOW_27
                ? part == Part.UPPER
                : part == Part.LOWER;
        boolean onramp = flatBelow && supportsOnramp;
        boolean offramp = flatAbove && supportsOfframp;
        if (onramp == offramp) {
            return Variant.REGULAR;
        }
        return onramp ? Variant.ONRAMP : Variant.OFFRAMP;
    }

    static boolean isLowCornerBoundary(boolean hasFlatCorner,
                                       boolean continuesUphill,
                                       boolean continuesDownhill) {
        return hasFlatCorner && continuesUphill && !continuesDownhill;
    }

    static boolean isHighCornerBoundary(boolean hasFlatCorner,
                                        boolean continuesUphill,
                                        boolean continuesDownhill) {
        return hasFlatCorner && !continuesUphill && continuesDownhill;
    }

    static boolean isLowShallowTermination(Profile profile,
                                           boolean continuesUphill,
                                           boolean continuesDownhill) {
        return profile == Profile.SHALLOW_27
                && continuesUphill
                && !continuesDownhill;
    }

    static boolean isHighShallowTermination(Profile profile,
                                            boolean continuesUphill,
                                            boolean continuesDownhill) {
        return profile == Profile.SHALLOW_27
                && !continuesUphill
                && continuesDownhill;
    }

    public static List<Direction> flatCornerDirections(BlockState state, Direction uphill) {
        if (!(state.getBlock() instanceof DiagonalWallBlock) || uphill == null) {
            return List.of();
        }
        return flatCornerDirections(
                uphill,
                state.get(DiagonalWallBlock.NORTH_SHAPE),
                state.get(DiagonalWallBlock.EAST_SHAPE),
                state.get(DiagonalWallBlock.SOUTH_SHAPE),
                state.get(DiagonalWallBlock.WEST_SHAPE)
        );
    }

    /**
     * Returns only the vertical-run neighbours which belong to the same
     * incline. Flat neighbours remain the wall block's normal responsibility.
     * The reverse check is important at a flat endpoint: that endpoint has no
     * slope model of its own, but the adjacent onramp/offramp still points to it.
     */
    static List<BlockPos> connectedSlopeNeighbours(BlockView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DiagonalWallBlock)) {
            return List.of();
        }

        Set<BlockPos> result = new LinkedHashSet<>();
        addExistingWalls(world, result, directSlopeNeighbours(world, state, pos));

        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                BlockPos candidatePos = pos.offset(direction).add(0, yOffset, 0);
                BlockState candidate = world.getBlockState(candidatePos);
                if (!(candidate.getBlock() instanceof DiagonalWallBlock)) {
                    continue;
                }
                if (directSlopeNeighbours(world, candidate, candidatePos).contains(pos)) {
                    result.add(candidatePos.toImmutable());
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * A stair joint belongs to the genuinely flat wall immediately beyond an
     * incline endpoint. Transition models remain ordinary incline posts.
     */
    static boolean isFlatStairJoint(BlockView world, BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof DiagonalWallBlock)
                || resolve(world, state, pos).isSlope()
                || isPierFreeShallowStairEndpoint(world, state, pos)) {
            return false;
        }

        for (BlockPos slopePos : connectedSlopeNeighbours(world, pos)) {
            BlockState slopeState = world.getBlockState(slopePos);
            Mode slopeMode = resolve(world, slopeState, slopePos);
            if (isSlopeEndpoint(pos, slopePos, slopeMode)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSlopeEndpoint(BlockPos candidate, BlockPos slopePos, Mode slopeMode) {
        return slopeMode.isSlope()
                && (candidate.equals(uphillNeighbourPos(slopePos, slopeMode))
                || candidate.equals(downhillNeighbourPos(slopePos, slopeMode)));
    }

    static BlockPos uphillNeighbourPos(BlockPos pos, Mode mode) {
        if (!mode.isSlope()) {
            return pos;
        }
        if (mode.profile() == Profile.STEEP_45) {
            int yOffset = mode.variant() == Variant.OFFRAMP ? 0 : 1;
            return pos.offset(mode.uphill()).add(0, yOffset, 0);
        }
        int yOffset = mode.part() == Part.LOWER ? 1 : 0;
        return pos.offset(mode.uphill()).add(0, yOffset, 0);
    }

    static BlockPos downhillNeighbourPos(BlockPos pos, Mode mode) {
        if (!mode.isSlope()) {
            return pos;
        }
        if (mode.profile() == Profile.STEEP_45) {
            int yOffset = mode.variant() == Variant.ONRAMP ? 0 : -1;
            return pos.offset(mode.uphill().getOpposite()).add(0, yOffset, 0);
        }
        int yOffset = mode.part() == Part.UPPER ? -1 : 0;
        return pos.offset(mode.uphill().getOpposite()).add(0, yOffset, 0);
    }

    private static List<BlockPos> directSlopeNeighbours(BlockView world,
                                                         BlockState state,
                                                         BlockPos pos) {
        Mode mode = resolve(world, state, pos);
        if (!mode.isSlope()) {
            return List.of();
        }
        return List.of(
                downhillNeighbourPos(pos, mode),
                uphillNeighbourPos(pos, mode)
        );
    }

    private static void addExistingWalls(BlockView world,
                                         Set<BlockPos> target,
                                         List<BlockPos> candidates) {
        for (BlockPos candidate : candidates) {
            if (world.getBlockState(candidate).getBlock() instanceof DiagonalWallBlock) {
                target.add(candidate.toImmutable());
            }
        }
    }

    static List<Direction> flatCornerDirections(Direction uphill,
                                                WallShape north,
                                                WallShape east,
                                                WallShape south,
                                                WallShape west) {
        List<Direction> result = new ArrayList<>(2);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (direction.getAxis() != uphill.getAxis()
                    && cardinalShape(direction, north, east, south, west) != WallShape.NONE) {
                result.add(direction);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isSlopeCompatible(BlockState state) {
        return !state.get(DiagonalWallBlock.NORTH_EAST)
                && !state.get(DiagonalWallBlock.SOUTH_EAST)
                && !state.get(DiagonalWallBlock.SOUTH_WEST)
                && !state.get(DiagonalWallBlock.NORTH_WEST);
    }

    private static WallShape cardinalShape(BlockState state, Direction direction) {
        return cardinalShape(
                direction,
                state.get(DiagonalWallBlock.NORTH_SHAPE),
                state.get(DiagonalWallBlock.EAST_SHAPE),
                state.get(DiagonalWallBlock.SOUTH_SHAPE),
                state.get(DiagonalWallBlock.WEST_SHAPE)
        );
    }

    private static WallShape cardinalShape(Direction direction,
                                           WallShape north,
                                           WallShape east,
                                           WallShape south,
                                           WallShape west) {
        return switch (direction) {
            case NORTH -> north;
            case EAST -> east;
            case SOUTH -> south;
            case WEST -> west;
            default -> WallShape.NONE;
        };
    }

    private static boolean isLowerHalfLayer(VoxelShape shape) {
        if (shape.isEmpty()) {
            return false;
        }
        var bounds = shape.getBoundingBox();
        return near(bounds.minX, 0.0D)
                && near(bounds.maxX, 1.0D)
                && near(bounds.minY, 0.0D)
                && near(bounds.maxY, 0.5D)
                && near(bounds.minZ, 0.0D)
                && near(bounds.maxZ, 1.0D);
    }

    static Part partForShallowStair(boolean topHalf, StairShape shape) {
        if (shape != StairShape.STRAIGHT) {
            return Part.NONE;
        }
        return topHalf ? Part.LOWER : Part.UPPER;
    }

    static Part partForSlab(SlabType type) {
        return switch (type) {
            case BOTTOM -> Part.UPPER;
            case DOUBLE -> Part.LOWER;
            case TOP -> Part.NONE;
        };
    }

    static Part partForLayer(int layers, boolean topAnchored) {
        if (layers == 8) {
            return Part.LOWER;
        }
        if (layers == 4 && !topAnchored) {
            return Part.UPPER;
        }
        return Part.NONE;
    }

    static boolean isAlignedShallowStair(StairShape shape,
                                          Direction facing,
                                          Direction uphill) {
        return shape == StairShape.STRAIGHT && facing == uphill;
    }

    static boolean isAlignedSteepStair(boolean stairBlock,
                                       boolean shallowStair,
                                       BlockHalf half,
                                       StairShape shape,
                                       Direction facing,
                                       Direction uphill) {
        return stairBlock
                && !shallowStair
                && half == BlockHalf.BOTTOM
                && shape == StairShape.STRAIGHT
                && facing == uphill;
    }

    private static boolean near(double first, double second) {
        return Math.abs(first - second) <= EPSILON;
    }

    public enum Part {
        NONE,
        LOWER,
        UPPER
    }

    public enum Profile {
        NONE,
        SHALLOW_27,
        STEEP_45
    }

    public enum Variant {
        REGULAR,
        ONRAMP,
        OFFRAMP
    }

    public record Mode(Part part,
                       Direction uphill,
                       Profile profile,
                       Variant variant) {
        public static final Mode NONE = new Mode(
                Part.NONE,
                Direction.NORTH,
                Profile.NONE,
                Variant.REGULAR
        );

        public boolean isSlope() {
            return part != Part.NONE && profile != Profile.NONE;
        }
    }

    private record SlopeCandidate(Profile profile, Direction uphill) {
        private static final SlopeCandidate AMBIGUOUS = new SlopeCandidate(
                Profile.NONE,
                Direction.NORTH
        );
    }
}
