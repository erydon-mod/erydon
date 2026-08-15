package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public class ArchGothicBlock extends ArchModernBlock {
    private static final VoxelShape SHAPE_EMPTY = VoxelShapes.empty();
    private static final VoxelShape SHAPE_CORNER_SMALL = makeCornerSmallShape();
    private static final VoxelShape SHAPE_CORNER_MEDIUM = makeCornerMediumShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_UPPER = makeCornerLargeUpperShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_LOWER = makeCornerLargeLowerShape();
    private static final VoxelShape SHAPE_SIDE_SMALL = makeSideSmallShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM = makeSideMediumShape();
    private static final VoxelShape SHAPE_SIDE_LARGE = makeSideLargeShape();
    private static final VoxelShape SHAPE_TOP_LARGE = makeTopLargeShape();
    private static final VoxelShape[] GOTHIC_SHAPE_CACHE = new VoxelShape[Arrangement.values().length * 4];

    public ArchGothicBlock(Settings settings) {
        super(settings);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(ARRANGEMENT).isVoid()) {
            return VoxelShapes.fullCube();
        }
        return getGothicWorldSpaceShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(ARRANGEMENT).isVoid()) {
            return SHAPE_EMPTY;
        }
        return getGothicWorldSpaceShape(state);
    }

    private static VoxelShape getGothicWorldSpaceShape(BlockState state) {
        Direction facing = state.get(FACING);
        Arrangement arrangement = state.get(ARRANGEMENT);
        int index = arrangement.ordinal() * 4 + facingTurns(facing);
        VoxelShape cached = GOTHIC_SHAPE_CACHE[index];
        if (cached != null) {
            return cached;
        }

        VoxelShape shape = SHAPE_EMPTY;
        shape = VoxelShapes.union(shape, cornerShape(arrangement.corner(), arrangement.cornerFlip()));
        shape = VoxelShapes.union(shape, sideShape(arrangement.sideL(), false));
        shape = VoxelShapes.union(shape, sideShape(arrangement.sideR(), true));
        if (arrangement.hasTopLarge()) {
            shape = VoxelShapes.union(shape, SHAPE_TOP_LARGE);
        }

        VoxelShape rotated = rotateShapeY(shape, facingTurns(facing)).simplify();
        GOTHIC_SHAPE_CACHE[index] = rotated;
        return rotated;
    }

    private static VoxelShape cornerShape(Corner corner, boolean flip) {
        VoxelShape shape = switch (corner) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_CORNER_SMALL;
            case MEDIUM -> SHAPE_CORNER_MEDIUM;
            case LARGE_UPPER -> SHAPE_CORNER_LARGE_UPPER;
            case LARGE_LOWER -> SHAPE_CORNER_LARGE_LOWER;
        };
        return rotateShapeY(shape, flip ? 0 : 2);
    }

    private static VoxelShape sideShape(Side side, boolean right) {
        VoxelShape shape = switch (side) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_SIDE_SMALL;
            case MEDIUM -> SHAPE_SIDE_MEDIUM;
            case LARGE -> SHAPE_SIDE_LARGE;
        };
        return rotateShapeY(shape, right ? 0 : 2);
    }

    private static int facingTurns(Direction facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeY(VoxelShape shape, int turns) {
        if (shape.isEmpty()) {
            return shape;
        }
        VoxelShape rotated = shape;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            rotated = rotateShape90Y(rotated);
        }
        return rotated;
    }

    private static VoxelShape rotateShape90Y(VoxelShape shape) {
        final VoxelShape[] result = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                result[0] = VoxelShapes.union(result[0],
                        VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)));
        return result[0].simplify();
    }

    private static VoxelShape makeCornerSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.92634833, 0.42002225, 0.00157505, 0.99554825, 0.57016081, 1.00157499));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.88036531, 0.5421623, 0.00160003, 0.99788177, 0.66739124, 1.00160003));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.55619055, 0.82860547, 0.00172502, 0.7138359, 0.99729532, 1.00172496));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.48406091, 0.87381816, 0.00174999, 0.61353052, 0.99804139, 1.00174999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00262929, 0.41272867, 0.00156248, 0.07357667, 0.57016081, 1.00156248));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00151184, 0.54216236, 0.00158745, 0.11955963, 0.66772032, 1.00158751));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00196362, 0.61187094, 0.00161254, 0.17577744, 0.78239048, 1.00161254));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00165676, 0.67362046, 0.00163746, 0.23805895, 0.92097878, 1.00163746));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.06476229, 0.72938675, 0.00166249, 0.30422279, 0.99771452, 1.00166249));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1810187, 0.78070056, 0.00168747, 0.37302944, 0.99589568, 1.00168753));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.82414758, 0.61187094, 0.001625, 0.99842632, 0.78280818, 1.00162506));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76186603, 0.67362046, 0.00165004, 0.99785095, 0.92051339, 1.00165009));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6957022, 0.72938681, 0.00167501, 0.93324941, 0.99524373, 1.00167501));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.62689555, 0.78070056, 0.00170004, 0.82033247, 0.997949, 1.00170004));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.28575298, 0.82860547, 0.00171256, 0.44373435, 0.99782223, 1.00171256));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.38735333, 0.87381816, 0.00173753, 0.51586413, 0.9964301, 1.00173748));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.936, 0, 1, 1, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.038425, 0.62489562, 0.0000625, 0.100925, 0.9375625, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.89740813, 0.62489562, 0.0000625, 0.95990813, 0.9375625, 1));
        return shape.simplify();
    }

    private static VoxelShape makeCornerMediumShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.86075962, -0.00017231, 0.00032502, 0.99162024, 0.21124697, 0.99970007));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76824403, 0.15779084, 0.00035, 0.9944126, 0.40081152, 0.99972498));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.65525264, 0.29811054, 0.00037497, 0.98970401, 0.62677741, 0.99975002));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.53038621, 0.42191505, 0.00039995, 0.98832351, 0.90065622, 0.99977499));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.39795247, 0.53350705, 0.00042498, 0.8123275, 0.98832655, 0.99979997));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.26036659, 0.63607889, 0.00044996, 0.59534186, 0.9981302, 0.999825));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.11907712, 0.73177493, 0.00047499, 0.38485232, 0.99443913, 0.99984998));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.02499472, 0.82205534, 0.00050002, 0.18824175, 0.99716777, 0.99987501));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.79927437, 0.8780025, 0.2224525, 0.79927437, 1.06583625, 1.2224525));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1575, 0.86292313, 0, 0.90625, 1.00033313, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.0005, 0.91345813, 0, 0.1575, 1.00033313, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.859605, 0.28875, 0.0000625, 0.906365, 0.871875, 0.9999375));
        return shape.simplify();
    }

    private static VoxelShape makeCornerLargeUpperShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.47768259, -0.04726829, 0.00037497, 0.97670734, 0.44312558, 0.99975002));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.29137367, 0.13745676, 0.00039995, 0.97464824, 0.85177225, 0.99977499));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.09377281, 0.30395997, 0.00042498, 0.71204901, 0.98258215, 0.99979997));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.11151432, 0.45700455, 0.00045002, 0.38829181, 0.99721009, 0.999825));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.26499875, 0.79547188, 0, 0.85218812, 1.00049688, 1));
        return shape.simplify();
    }

    private static VoxelShape makeCornerLargeLowerShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.7843135, 0.50767308, 0.00032496, 0.97956675, 0.82312512, 0.99970001));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.64627361, 0.74336493, 0.00035, 0.98373312, 1.10596871, 0.99972498));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.78259062, 0.938765, 0.0000625, 0.85236, 1.80882875, 0.9999375));
        return shape.simplify();
    }

    private static VoxelShape makeSideSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.96037, 0, 0.0000625, 1, 1, 0.9999375));
        return shape.simplify();
    }

    private static VoxelShape makeSideMediumShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.906365, 0, 0.0000625, 1, 1, 0.9999375));
        return shape.simplify();
    }

    private static VoxelShape makeSideLargeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.85236, 0, 0.0000625, 1, 1, 0.9999375));
        return shape.simplify();
    }

    private static VoxelShape makeTopLargeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.67767173, 0.59978962, 0.00047499, 1.07422638, 0.9917025, 0.99984998));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.462706, 0.73449439, 0.00049996, 0.78086913, 0.99577379, 0.99987501));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.49925375, 0.87087375, 0, 0.73500125, 1.00049688, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.07422638, 0.59978962, 0.00015, 0.32232824, 0.99170244, 0.99952501));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.21913089, 0.73449439, 0.000125, 0.53729415, 0.99577385, 0.99949998));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.26499875, 0.87087375, 0.0000625, 0.50074625, 1.00043437, 0.9999375));
        return shape.simplify();
    }
}
