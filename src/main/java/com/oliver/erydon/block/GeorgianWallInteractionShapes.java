package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached interaction shapes for the authored Georgian wall incline models.
 *
 * <p>The visual models contain many small circular and bevel elements. Using
 * every element as collision would be needlessly expensive, so these shapes
 * follow the pieces which materially affect interaction: the three posts,
 * lower ramp, upper handrail, transition extensions, and any flat corner arm.
 * Rotated pieces use eight narrow steps per model section.</p>
 */
final class GeorgianWallInteractionShapes {
    private static final int SLOPE_SLICES = 8;
    private static final Map<ShapeKey, VoxelShape> CACHE = new ConcurrentHashMap<>();
    private static final Map<GeorgianWallSlopeResolver.Mode, VoxelShape> BASE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<NorthShapeKey, VoxelShape> NORTH_CACHE = new ConcurrentHashMap<>();

    private static final VoxelShape NORTH_FLAT_ARM = VoxelShapes.union(
            box(4.75D, 0.0D, 0.0D, 11.25D, 1.5D, 8.0D),
            box(5.0D, 13.5D, 0.0D, 11.0D, 15.0D, 8.0D),
            box(5.5D, 1.5D, 0.0D, 10.5D, 16.0D, 2.5D)
    );

    private GeorgianWallInteractionShapes() {
    }

    static VoxelShape shapeFor(BlockState state, GeorgianWallSlopeResolver.Mode mode) {
        int flatArmMask = 0;
        for (Direction direction : GeorgianWallSlopeResolver.flatCornerDirections(
                state,
                mode.uphill()
        )) {
            flatArmMask |= directionBit(direction);
        }
        return shapeFor(mode, flatArmMask);
    }

    static VoxelShape shapeFor(GeorgianWallSlopeResolver.Mode mode, int flatArmMask) {
        if (!mode.isSlope()) {
            return VoxelShapes.empty();
        }
        int horizontalArms = flatArmMask & 0xF;
        return CACHE.computeIfAbsent(
                new ShapeKey(mode, horizontalArms),
                GeorgianWallInteractionShapes::createShape
        );
    }

    static int directionBit(Direction direction) {
        return switch (direction) {
            case NORTH -> 1;
            case EAST -> 1 << 1;
            case SOUTH -> 1 << 2;
            case WEST -> 1 << 3;
            default -> 0;
        };
    }

    private static VoxelShape createShape(ShapeKey key) {
        GeorgianWallSlopeResolver.Mode mode = key.mode();
        VoxelShape shape = BASE_CACHE.computeIfAbsent(
                mode,
                ignored -> rotateNorthTo(
                        NORTH_CACHE.computeIfAbsent(
                                new NorthShapeKey(mode.part(), mode.profile(), mode.variant()),
                                unused -> createNorthSlopeShape(mode)
                        ),
                        mode.uphill()
                )
        );
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if ((key.flatArmMask() & directionBit(direction)) != 0) {
                shape = VoxelShapes.union(shape, rotateNorthTo(NORTH_FLAT_ARM, direction));
            }
        }
        return shape;
    }

    private static VoxelShape createNorthSlopeShape(GeorgianWallSlopeResolver.Mode mode) {
        if (mode.profile() == GeorgianWallSlopeResolver.Profile.STEEP_45) {
            return switch (mode.variant()) {
                case ONRAMP -> steepOnramp();
                case OFFRAMP -> steepOfframp();
                case REGULAR -> steepRegular();
            };
        }
        if (mode.part() == GeorgianWallSlopeResolver.Part.UPPER) {
            return mode.variant() == GeorgianWallSlopeResolver.Variant.OFFRAMP
                    ? shallowUpperOfframp()
                    : shallowUpper();
        }
        return mode.variant() == GeorgianWallSlopeResolver.Variant.ONRAMP
                ? shallowLowerOnramp()
                : shallowLower();
    }

    private static VoxelShape shallowUpper() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -11.0D, -0.73D, 11.25D, -2.5D, 17.159D,
                        -3.25D, 8.0D, 26.565D),
                rotatedXElement(5.0D, 10.0D, 0.056D, 11.0D, 11.5D, 17.945D,
                        10.75D, 8.0D, 26.565D)
        );
        return addPosts(
                shape,
                new Post(0.0D, 2.5D, 0.0D, 17.0D),
                new Post(5.5D, 10.5D, -4.0D, 13.0D),
                new Post(13.5D, 16.0D, -8.0D, 9.0D)
        );
    }

    private static VoxelShape shallowLower() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -3.0D, -0.73D, 11.25D, 5.5D, 17.159D,
                        4.75D, 8.0D, 26.565D),
                rotatedXElement(5.0D, 18.0D, 0.056D, 11.0D, 19.5D, 17.945D,
                        18.75D, 8.0D, 26.565D)
        );
        return addPosts(
                shape,
                new Post(0.0D, 2.5D, 8.0D, 25.0D),
                new Post(5.5D, 10.5D, 4.0D, 21.0D),
                new Post(13.5D, 16.0D, 0.0D, 17.0D)
        );
    }

    private static VoxelShape shallowLowerOnramp() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -3.0D, -0.73D, 11.25D, 5.5D, 17.389D,
                        4.75D, 8.0D, 26.565D),
                rotatedXElement(5.0D, 18.0D, 0.056D, 11.0D, 19.5D, 18.215D,
                        18.75D, 8.0D, 26.565D),
                box(4.75D, 0.0D, 16.161D, 11.25D, 1.5D, 19.25D),
                box(5.0D, 13.5D, 16.801D, 11.0D, 15.0D, 18.97D)
        );
        return addPosts(
                shape,
                new Post(0.0D, 2.5D, 8.0D, 25.0D),
                new Post(5.5D, 10.5D, 4.0D, 21.0D),
                new Post(13.5D, 18.5D, 0.0D, 17.0D)
        );
    }

    private static VoxelShape shallowUpperOfframp() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -11.0D, -1.12D, 11.25D, -2.5D, 17.159D,
                        -3.25D, 8.0D, 26.565D),
                rotatedXElement(5.0D, 10.0D, -0.014D, 11.0D, 11.5D, 17.945D,
                        10.75D, 8.0D, 26.565D),
                box(4.75D, -7.0006D, -15.82177D, 11.25D, 1.4994D, 0.17823D),
                box(4.75D, 0.0D, -2.5D, 11.25D, 1.5D, 0.179D),
                box(5.0D, 13.5D, -3.0D, 11.0D, 15.0D, 1.169D)
        );
        return addPosts(
                shape,
                new Post(-2.5D, 2.5D, 0.0D, 17.0D),
                new Post(5.5D, 10.5D, -4.0D, 13.0D),
                new Post(13.5D, 16.0D, -8.0D, 9.0D)
        );
    }

    private static VoxelShape steepRegular() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -5.5D, -2.814D, 11.25D, 0.25D, 19.813D,
                        0.75D, 8.0D, 45.0D),
                rotatedXElement(5.0D, 13.5D, -3.314D, 11.0D, 15.0D, 19.313D,
                        14.25D, 8.0D, 45.0D)
        );
        return addPosts(
                shape,
                new Post(0.0D, 2.5D, 5.5D, 25.5D),
                new Post(5.5D, 10.5D, -2.5D, 17.5D),
                new Post(13.5D, 16.0D, -10.5D, 9.5D)
        );
    }

    private static VoxelShape steepOnramp() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -5.5D, -2.814D, 11.25D, 0.25D, 6.503D,
                        0.75D, 8.0D, 45.0D),
                rotatedXElement(5.0D, 13.5D, -3.314D, 11.0D, 15.0D, 8.323D,
                        14.25D, 8.0D, 45.0D),
                box(4.75D, 0.0D, 5.0D, 11.25D, 1.5D, 19.25D),
                box(5.0D, 13.5D, 7.703D, 11.0D, 15.0D, 19.0D)
        );
        return addPosts(
                shape,
                new Post(0.0D, 2.5D, 5.5D, 25.5D),
                new Post(5.5D, 10.5D, 0.0D, 17.5D),
                new Post(13.5D, 18.5D, 1.5D, 16.0D)
        );
    }

    private static VoxelShape steepOfframp() {
        VoxelShape shape = VoxelShapes.union(
                rotatedXElement(4.75D, -5.5D, 6.426D, 11.25D, 0.25D, 19.813D,
                        0.75D, 8.0D, 45.0D),
                rotatedXElement(5.0D, 13.5D, 7.686D, 11.0D, 15.0D, 19.313D,
                        14.25D, 8.0D, 45.0D),
                box(4.75D, 0.0D, -3.25D, 11.25D, 1.5D, 6.52D),
                box(5.0D, 13.5D, -3.0D, 11.0D, 15.0D, 8.307D)
        );
        return addPosts(
                shape,
                new Post(-2.5D, 2.5D, 1.5D, 16.0D),
                new Post(5.5D, 10.5D, -2.5D, 16.0D),
                new Post(13.5D, 16.0D, -10.5D, 9.5D)
        );
    }

    private static VoxelShape addPosts(VoxelShape shape, Post... posts) {
        VoxelShape result = shape;
        for (Post post : posts) {
            result = VoxelShapes.union(
                    result,
                    box(5.5D, post.minY(), post.minZ(), 10.5D, post.maxY(), post.maxZ())
            );
        }
        return result;
    }

    private static VoxelShape rotatedXElement(double minX,
                                               double minY,
                                               double minZ,
                                               double maxX,
                                               double maxY,
                                               double maxZ,
                                               double originY,
                                               double originZ,
                                               double angleDegrees) {
        VoxelShape shape = VoxelShapes.empty();
        double sliceDepth = (maxZ - minZ) / SLOPE_SLICES;
        double radians = Math.toRadians(angleDegrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        for (int slice = 0; slice < SLOPE_SLICES; slice++) {
            double sliceMinZ = minZ + sliceDepth * slice;
            double sliceMaxZ = slice == SLOPE_SLICES - 1
                    ? maxZ
                    : sliceMinZ + sliceDepth;
            double transformedMinY = Double.POSITIVE_INFINITY;
            double transformedMinZ = Double.POSITIVE_INFINITY;
            double transformedMaxY = Double.NEGATIVE_INFINITY;
            double transformedMaxZ = Double.NEGATIVE_INFINITY;
            for (double y : new double[]{minY, maxY}) {
                for (double z : new double[]{sliceMinZ, sliceMaxZ}) {
                    double localY = y - originY;
                    double localZ = z - originZ;
                    double transformedY = localY * cosine - localZ * sine + originY;
                    double transformedZ = localY * sine + localZ * cosine + originZ;
                    transformedMinY = Math.min(transformedMinY, transformedY);
                    transformedMinZ = Math.min(transformedMinZ, transformedZ);
                    transformedMaxY = Math.max(transformedMaxY, transformedY);
                    transformedMaxZ = Math.max(transformedMaxZ, transformedZ);
                }
            }
            shape = VoxelShapes.union(
                    shape,
                    box(minX, transformedMinY, transformedMinZ,
                            maxX, transformedMaxY, transformedMaxZ)
            );
        }
        return shape;
    }

    private static VoxelShape rotateNorthTo(VoxelShape shape, Direction direction) {
        int turns = switch (direction) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        VoxelShape result = shape;
        for (int turn = 0; turn < turns; turn++) {
            VoxelShape current = result;
            VoxelShape[] rotated = {VoxelShapes.empty()};
            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                    rotated[0] = VoxelShapes.union(
                            rotated[0],
                            VoxelShapes.cuboid(
                                    1.0D - maxZ,
                                    minY,
                                    minX,
                                    1.0D - minZ,
                                    maxY,
                                    maxX
                            )
                    ));
            result = rotated[0];
        }
        return result;
    }

    private static VoxelShape box(double minX,
                                  double minY,
                                  double minZ,
                                  double maxX,
                                  double maxY,
                                  double maxZ) {
        return VoxelShapes.cuboid(
                minX / 16.0D,
                minY / 16.0D,
                minZ / 16.0D,
                maxX / 16.0D,
                maxY / 16.0D,
                maxZ / 16.0D
        );
    }

    private record Post(double minZ, double maxZ, double minY, double maxY) {
    }

    private record ShapeKey(GeorgianWallSlopeResolver.Mode mode, int flatArmMask) {
    }

    private record NorthShapeKey(GeorgianWallSlopeResolver.Part part,
                                 GeorgianWallSlopeResolver.Profile profile,
                                 GeorgianWallSlopeResolver.Variant variant) {
    }
}
