package com.oliver.erydon.block;

import com.oliver.erydon.block.WindowArchBlock.Piece;
import net.minecraft.util.math.Direction;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import java.util.EnumMap;

/** Shared geometry for every arch-window material; independent of world and registry state. */
final class WindowArchShapes {
    private WindowArchShapes() {}

    // --- Shapes (strike/collision) ---
    enum ShapeKey {
        CLOSED_UPPER_SINGLE,
        CLOSED_UPPER_LEFT,
        CLOSED_UPPER_MID,
        CLOSED_UPPER_RIGHT,

        CLOSED_LOWER_SINGLE,
        CLOSED_LOWER_LEFT,
        CLOSED_LOWER_GLASS,
        CLOSED_LOWER_RIGHT,

        OPEN_LOWER_SINGLE,
        OPEN_LOWER_LEFT,
        OPEN_LOWER_RIGHT,

        SILL
    }

    private static final double[][] WALL_BOXES = new double[][] {
            {14.0D, 0.0D, 1.0D, 15.0D, 16.0D, 2.07143D},
            {14.0D, 0.0D, 7.42857D, 15.0D, 16.0D, 8.5D},
            {14.0D, 0.0D, 9.57143D, 15.0D, 16.0D, 10.64286D},
            {14.0D, 0.0D, 3.14286D, 15.0D, 16.0D, 4.21429D},
            {14.0D, 0.0D, 11.71429D, 15.0D, 16.0D, 12.78571D},
            {14.0D, 0.0D, 5.28571D, 15.0D, 16.0D, 6.35714D},
            {14.0D, 0.0D, 14.0D, 15.0D, 16.0D, 15.07143D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D}
    };

    private static final double[][] SILL_BOXES = new double[][] {
            {0.0D, 0.0D, 0.0D, 16.0D, 1.478D, 16.05D}
    };

    private static final double[][] GLASS_LOWER_BOXES = new double[][] {
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {9.125D, 0.0D, 7.574D, 9.375D, 16.0D, 8.378D},
            {14.469D, 0.0D, 7.574D, 14.719D, 16.0D, 8.378D},
            {11.813D, 0.0D, 7.574D, 12.063D, 16.0D, 8.378D},
            {0.0D, 9.077D, 7.56D, 16.0D, 9.327D, 8.364D},
            {0.0D, 11.813D, 7.56D, 16.0D, 12.063D, 8.364D},
            {0.0D, 14.437D, 7.56D, 16.0D, 14.687D, 8.364D},
            {0.0D, 6.453D, 7.56D, 16.0D, 6.703D, 8.364D},
            {0.0D, 3.813D, 7.56D, 16.0D, 4.063D, 8.364D},
            {0.0D, 1.26D, 7.56D, 16.0D, 1.51D, 8.364D},
            {3.781D, 0.0D, 7.574D, 4.031D, 16.0D, 8.378D},
            {6.453D, 0.0D, 7.574D, 6.703D, 16.0D, 8.378D},
            {1.26D, 0.0D, 7.574D, 1.51D, 16.0D, 8.378D}
    };

    private static final double[][] MID_UPPER_BOXES = new double[][] {
            {0.0D, 1.612D, 6.47286D, 16.0D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 11.256D, 0.372D, 16.0D, 12.272D, 15.372D},
            {0.0D, 12.272D, 1.0D, 16.0D, 15.0D, 15.0D}
    };

    private static final double[][] MULTI_UPPER_BOXES = new double[][] {
            {0.0D, 1.612D, 6.47286D, 16.0D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {13.0D, 1.604D, 0.096D, 15.968D, 2.104D, 15.92D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 15.0D, 0.0D, 14.992D, 16.0D, 16.0D},
            {0.0D, 11.256D, 0.372D, 5.442D, 12.272D, 15.372D},
            {0.0D, 11.272D, 1.0D, 15.004D, 15.0D, 15.0D},
            {11.5D, 7.58D, 1.0D, 12.88D, 11.272D, 15.0D},
            {7.892D, 10.332D, 1.0D, 10.12D, 11.272D, 15.0D},
            {10.12D, 8.972D, 1.0D, 11.5D, 11.272D, 15.0D},
            {12.88D, 5.66D, 1.0D, 13.892D, 11.272D, 15.0D},
            {13.896D, 3.096D, 1.0D, 15.004D, 11.272D, 15.0D}
    };

    private static final double[][] SINGLE_UPPER_BOXES = new double[][] {
            {0.01D, 1.612D, 6.47286D, 15.99D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.01D, 7.67857D, 16.0D, 15.99D, 8.25D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D},
            {1.0D, 15.0D, 0.0D, 15.0D, 16.0D, 16.0D},
            {6.844D, 11.256D, 0.372D, 9.21D, 12.272D, 15.372D},
            {4.5D, 11.272D, 1.0D, 11.504D, 15.272D, 15.0D},
            {11.5D, 9.836D, 1.0D, 12.88D, 15.272D, 15.0D},
            {12.88D, 8.428D, 1.0D, 14.004D, 15.272D, 15.0D},
            {3.12D, 9.836D, 1.0D, 4.5D, 15.272D, 15.0D},
            {1.996D, 8.428D, 1.0D, 3.12D, 15.272D, 15.0D},
            {14.004D, 5.928D, 1.0D, 15.128D, 15.272D, 15.0D},
            {14.0D, 2.0D, 1.0D, 15.0D, 6.0D, 2.07143D},
            {14.0D, 2.0D, 3.14286D, 15.0D, 6.0D, 4.21429D},
            {14.0D, 2.0D, 5.28571D, 15.0D, 6.0D, 6.35714D},
            {14.0D, 2.0D, 7.42857D, 15.0D, 6.0D, 8.5D},
            {14.0D, 2.0D, 9.57143D, 15.0D, 6.0D, 10.64286D},
            {14.0D, 2.0D, 11.71429D, 15.0D, 6.0D, 12.78571D},
            {14.0D, 2.0D, 14.0D, 15.0D, 6.0D, 15.07143D},
            {13.0D, 1.604D, 0.096D, 15.968D, 2.104D, 15.92D},
            {0.872D, 5.928D, 1.0D, 1.996D, 15.272D, 15.0D},
            {1.0D, 2.0D, 1.0D, 2.0D, 6.0D, 2.07143D},
            {1.0D, 2.0D, 3.14286D, 2.0D, 6.0D, 4.21429D},
            {1.0D, 2.0D, 5.28571D, 2.0D, 6.0D, 6.35714D},
            {1.0D, 2.0D, 7.42857D, 2.0D, 6.0D, 8.5D},
            {1.0D, 2.0D, 9.57143D, 2.0D, 6.0D, 10.64286D},
            {1.0D, 2.0D, 11.71429D, 2.0D, 6.0D, 12.78571D},
            {1.0D, 2.0D, 14.0D, 2.0D, 6.0D, 15.07143D},
            {0.032D, 1.604D, 0.096D, 3.0D, 2.104D, 15.92D}
    };

    private static final EnumMap<ShapeKey, VoxelShape[]> SHAPES = new EnumMap<>(ShapeKey.class);
    private static final EnumMap<ShapeKey, VoxelShape[]> SILL_SHAPES = new EnumMap<>(ShapeKey.class);
    private static final EnumMap<ShapeKey, VoxelShape[]> PARTICLE_SHAPES = new EnumMap<>(ShapeKey.class);
    private static final EnumMap<ShapeKey, VoxelShape[]> SILL_PARTICLE_SHAPES = new EnumMap<>(ShapeKey.class);

    static {
        // Components (unrotated for "north" baseline). We pre-bake a few combined shapes for convenience.
        VoxelShape wall = makeWallShape();
        VoxelShape wall180 = rotateY180(wall);

        VoxelShape glassLower = makeGlassLowerShape();

        // Uppers
        register(ShapeKey.CLOSED_UPPER_SINGLE, makeSingleUpperShape());
        register(ShapeKey.CLOSED_UPPER_LEFT, makeMultiUpperShape());
        register(ShapeKey.CLOSED_UPPER_MID, makeMidUpperShape());
        register(ShapeKey.CLOSED_UPPER_RIGHT, rotateY180(makeMultiUpperShape()));

        // Lowers (closed)
        register(ShapeKey.CLOSED_LOWER_GLASS, glassLower);
        register(ShapeKey.CLOSED_LOWER_LEFT, VoxelShapes.union(wall, glassLower).simplify());
        register(ShapeKey.CLOSED_LOWER_RIGHT, VoxelShapes.union(glassLower, wall180).simplify());
        register(ShapeKey.CLOSED_LOWER_SINGLE, VoxelShapes.union(wall, glassLower, wall180).simplify());

        // Lowers (open) - opening is handled by returning null for Piece.LOWER_GLASS in shapeKeyForState()
        register(ShapeKey.OPEN_LOWER_LEFT, wall);
        register(ShapeKey.OPEN_LOWER_RIGHT, wall180);
        register(ShapeKey.OPEN_LOWER_SINGLE, VoxelShapes.union(wall, wall180).simplify());

        // Sill
        register(ShapeKey.SILL, makeSillShape());
        for (ShapeKey key : ShapeKey.values()) {
            VoxelShape[] withSill = new VoxelShape[4];
            VoxelShape[] particles = new VoxelShape[4];
            VoxelShape[] sillParticles = new VoxelShape[4];
            for (int i = 0; i < 4; i++) {
                VoxelShape base = SHAPES.get(key)[i];
                withSill[i] = VoxelShapes.combine(base, SHAPES.get(ShapeKey.SILL)[i], BooleanBiFunction.OR);
                // Vanilla emits at least eight particles per box. Detail belongs in collision,
                // not thousands of debris particles; a single box emits at most 64.
                particles[i] = VoxelShapes.cuboid(base.getBoundingBox());
                sillParticles[i] = VoxelShapes.cuboid(withSill[i].getBoundingBox());
            }
            SILL_SHAPES.put(key, withSill);
            PARTICLE_SHAPES.put(key, particles);
            SILL_PARTICLE_SHAPES.put(key, sillParticles);
        }
    }

    private static void register(ShapeKey key, VoxelShape base) {
        VoxelShape[] arr = new VoxelShape[4];
        arr[0] = base.simplify();
        arr[1] = rotateYClockwise(arr[0]); // EAST
        arr[2] = rotateYClockwise(arr[1]); // SOUTH
        arr[3] = rotateYClockwise(arr[2]); // WEST
        SHAPES.put(key, arr);
    }

    private static VoxelShape makeShape(double[][] boxes) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] box : boxes) {
            shape = VoxelShapes.combine(
                    shape,
                    VoxelShapes.cuboid(box[0] / 16, box[1] / 16, box[2] / 16, box[3] / 16, box[4] / 16, box[5] / 16),
                    BooleanBiFunction.OR
            );
        }
        return shape.simplify();
    }

    static VoxelShape rotateYClockwise(VoxelShape shape) {
        final VoxelShape[] acc = new VoxelShape[]{ VoxelShapes.empty() };
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // 90° clockwise around Y: (x,z) -> (1 - z, x)
            double nMinX = 1.0 - maxZ;
            double nMaxX = 1.0 - minZ;
            double nMinZ = minX;
            double nMaxZ = maxX;
            acc[0] = VoxelShapes.combine(acc[0], VoxelShapes.cuboid(nMinX, minY, nMinZ, nMaxX, maxY, nMaxZ), BooleanBiFunction.OR);
        });
        return acc[0].simplify();
    }

    static VoxelShape rotateY180(VoxelShape shape) {
        return rotateYClockwise(rotateYClockwise(shape));
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

    // --- Voxel shape definitions (from provided model voxel tables) ---
    private static VoxelShape makeWallShape() {
        return makeShape(WALL_BOXES);
    }

    private static VoxelShape makeSillShape() {
        return makeShape(SILL_BOXES);
    }

    private static VoxelShape makeGlassLowerShape() {
        return makeShape(GLASS_LOWER_BOXES);
    }

    private static VoxelShape makeMidUpperShape() {
        return makeShape(MID_UPPER_BOXES);
    }

    private static VoxelShape makeMultiUpperShape() {
        return makeShape(MULTI_UPPER_BOXES);
    }

    private static VoxelShape makeSingleUpperShape() {
        return makeShape(SINGLE_UPPER_BOXES);
    }
    static VoxelShape shapeFor(Piece piece, boolean open, boolean sill, Direction facing) {
        int idx = facingIndex(facing);

        ShapeKey key = shapeKeyForState(piece, open);
        if (key == null) {
            return sill ? SHAPES.get(ShapeKey.SILL)[idx] : VoxelShapes.empty();
        }
        return (sill ? SILL_SHAPES : SHAPES).get(key)[idx];
    }

    static VoxelShape particleShapeFor(Piece piece, boolean open, boolean sill, Direction facing) {
        int idx = facingIndex(facing);
        ShapeKey key = shapeKeyForState(piece, open);
        if (key == null) {
            return sill ? PARTICLE_SHAPES.get(ShapeKey.SILL)[idx] : VoxelShapes.empty();
        }
        return (sill ? SILL_PARTICLE_SHAPES : PARTICLE_SHAPES).get(key)[idx];
    }

    private static ShapeKey shapeKeyForState(Piece piece, boolean open) {

        // Uppers do not visually change with OPEN in the table.
        return switch (piece) {
            case UPPER_SINGLE -> ShapeKey.CLOSED_UPPER_SINGLE;
            case UPPER_LEFT -> ShapeKey.CLOSED_UPPER_LEFT;
            case UPPER_MID -> ShapeKey.CLOSED_UPPER_MID;
            case UPPER_RIGHT -> ShapeKey.CLOSED_UPPER_RIGHT;

            case LOWER_SINGLE -> open ? ShapeKey.OPEN_LOWER_SINGLE : ShapeKey.CLOSED_LOWER_SINGLE;
            case LOWER_LEFT -> open ? ShapeKey.OPEN_LOWER_LEFT : ShapeKey.CLOSED_LOWER_LEFT;
            case LOWER_RIGHT -> open ? ShapeKey.OPEN_LOWER_RIGHT : ShapeKey.CLOSED_LOWER_RIGHT;
            case LOWER_GLASS -> open ? null : ShapeKey.CLOSED_LOWER_GLASS; // opening ('void')
        };
    }

}
