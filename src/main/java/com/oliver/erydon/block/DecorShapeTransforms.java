package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Shared geometry helpers for decorative blocks whose visible model can move
 * beyond the cell that owns the block state.
 */
public final class DecorShapeTransforms {
    public static final float OIL_BURNER_OFFSET_SCALE = 0.6156F;
    public static final float OFFSET_DISTANCE = 0.8003375F;
    public static final float OFFSET_BASE_Y = 0.04335284F;

    /*
     * The outline must retain something inside the owning cell: Minecraft
     * performs its first targeting test against getOutlineShape. A narrow post
     * is easier to aim at than a floor plate and steals fewer neighbouring hits
     * than a full-cube anchor.
     */
    static final VoxelShape TARGET_POST = Block.createCuboidShape(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);

    private DecorShapeTransforms() {
    }

    static VoxelShape transformFromNorth(VoxelShape shape,
                                         double scale,
                                         double northOffset,
                                         double baseY,
                                         Direction facing) {
        final VoxelShape[] transformed = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> transformed[0] = VoxelShapes.union(
                transformed[0],
                VoxelShapes.cuboid(
                        0.5 + (minX - 0.5) * scale,
                        minY * scale + baseY,
                        0.5 + (minZ - 0.5) * scale - northOffset,
                        0.5 + (maxX - 0.5) * scale,
                        maxY * scale + baseY,
                        0.5 + (maxZ - 0.5) * scale - northOffset
                )
        ));

        VoxelShape rotated = transformed[0];
        int turns = horizontalIndex(facing);
        for (int turn = 0; turn < turns; turn++) {
            rotated = rotateClockwise(rotated);
        }
        return rotated.simplify();
    }

    static VoxelShape octagonalLayer(double minX,
                                     double minY,
                                     double minZ,
                                     double maxX,
                                     double maxY,
                                     double maxZ) {
        double cut = Math.min(maxX - minX, maxZ - minZ) / 8.0;
        return VoxelShapes.union(
                Block.createCuboidShape(minX + cut, minY, minZ, maxX - cut, maxY, maxZ),
                Block.createCuboidShape(minX, minY, minZ + cut, maxX, maxY, maxZ - cut)
        );
    }

    static VoxelShape radialLayer(double radius, double minY, double maxY) {
        double min = 8.0 - radius;
        double max = 8.0 + radius;
        return VoxelShapes.union(
                Block.createCuboidShape(min, minY, 8.0 - radius * 0.35, max, maxY, 8.0 + radius * 0.35),
                Block.createCuboidShape(8.0 - radius * 0.85, minY, 8.0 - radius * 0.72,
                        8.0 + radius * 0.85, maxY, 8.0 + radius * 0.72),
                Block.createCuboidShape(8.0 - radius * 0.72, minY, min,
                        8.0 + radius * 0.72, maxY, max)
        );
    }

    static int horizontalIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Decor facing must be horizontal: " + direction);
        };
    }

    private static VoxelShape rotateClockwise(VoxelShape shape) {
        final VoxelShape[] rotated = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> rotated[0] = VoxelShapes.union(
                rotated[0],
                VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)
        ));
        return rotated[0];
    }
}
