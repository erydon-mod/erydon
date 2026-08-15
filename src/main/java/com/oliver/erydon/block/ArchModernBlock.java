package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class ArchModernBlock extends ArchRomanesqueBlock {

    private static final VoxelShape SHAPE_EMPTY = VoxelShapes.empty();
    private static final VoxelShape SHAPE_CORNER_SMALL = makeCornerSmallShape();
    private static final VoxelShape SHAPE_CORNER_MEDIUM = makeCornerMediumShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_UPPER = makeCornerLargeUpperShape();
    private static final VoxelShape SHAPE_CORNER_LARGE_LOWER = makeCornerLargeLowerShape();
    private static final VoxelShape SHAPE_SIDE_SMALL = makeSideSmallShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM = makeSideMediumShape();
    private static final VoxelShape SHAPE_SIDE_LARGE = makeSideLargeShape();
    private static final VoxelShape SHAPE_SIDE_MEDIUM_UPPER = makeSideMediumUpperShape();
    private static final VoxelShape SHAPE_SIDE_LARGE_UPPER = makeSideLargeUpperShape();
    private static final VoxelShape SHAPE_TOP_LARGE = makeTopLargeShape();
    private static final VoxelShape[] MODERN_SHAPE_CACHE = new VoxelShape[Arrangement.values().length * 4];

    public ArchModernBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected boolean supportsColumnStyle() {
        return false;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ActionResult.PASS;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(ARRANGEMENT).isVoid()) {
            return VoxelShapes.fullCube();
        }
        return getModernWorldSpaceShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(ARRANGEMENT).isVoid()) {
            return SHAPE_EMPTY;
        }
        return getModernWorldSpaceShape(state);
    }

    private static VoxelShape getModernWorldSpaceShape(BlockState state) {
        Direction facing = state.get(FACING);
        Arrangement arrangement = state.get(ARRANGEMENT);
        int index = shapeCacheIndex(arrangement, facing);
        VoxelShape cached = MODERN_SHAPE_CACHE[index];
        if (cached != null) {
            return cached;
        }

        VoxelShape shape = SHAPE_EMPTY;
        shape = VoxelShapes.union(shape, modernCornerShape(arrangement.corner(), arrangement.cornerFlip()));
        shape = VoxelShapes.union(shape, modernSideShape(arrangement.sideL(), false));
        shape = VoxelShapes.union(shape, modernSideShape(arrangement.sideR(), true));
        shape = VoxelShapes.union(shape, modernUpperShape(arrangement.upperL(), false));
        shape = VoxelShapes.union(shape, modernUpperShape(arrangement.upperR(), true));
        if (arrangement.hasTopLarge()) {
            shape = VoxelShapes.union(shape, SHAPE_TOP_LARGE);
        }

        VoxelShape rotated = rotateShapeY(shape, facingTurns(facing)).simplify();
        MODERN_SHAPE_CACHE[index] = rotated;
        return rotated;
    }

    private static VoxelShape modernCornerShape(Corner corner, boolean flip) {
        VoxelShape shape = switch (corner) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_CORNER_SMALL;
            case MEDIUM -> SHAPE_CORNER_MEDIUM;
            case LARGE_UPPER -> SHAPE_CORNER_LARGE_UPPER;
            case LARGE_LOWER -> SHAPE_CORNER_LARGE_LOWER;
        };
        return rotateShapeY(shape, flip ? 0 : 2);
    }

    private static VoxelShape modernSideShape(Side side, boolean right) {
        VoxelShape shape = switch (side) {
            case NONE -> SHAPE_EMPTY;
            case SMALL -> SHAPE_SIDE_SMALL;
            case MEDIUM -> SHAPE_SIDE_MEDIUM;
            case LARGE -> SHAPE_SIDE_LARGE;
        };
        return rotateShapeY(shape, right ? 0 : 2);
    }

    private static VoxelShape modernUpperShape(Upper upper, boolean right) {
        VoxelShape shape = switch (upper) {
            case NONE -> SHAPE_EMPTY;
            case MEDIUM -> SHAPE_SIDE_MEDIUM_UPPER;
            case LARGE -> SHAPE_SIDE_LARGE_UPPER;
        };
        return rotateShapeY(shape, right ? 0 : 2);
    }

    private static int shapeCacheIndex(Arrangement arrangement, Direction facing) {
        return arrangement.ordinal() * 4 + facingTurns(facing);
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
        final VoxelShape[] result = { VoxelShapes.empty() };
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            result[0] = VoxelShapes.union(result[0], VoxelShapes.cuboid(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX));
        });
        return result[0].simplify();
    }

    private static VoxelShape makeCornerSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.00169286, 0.54481846, 0.0, 0.14475544, 0.73382622, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08491329, 0.7014392, 0.0, 0.26462846, 0.88115436, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.23224183, 0.8213123, 0.0, 0.42125016, 0.96437511, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08, 0.70143438, 0.0000625, 0.14494437, 0.88644437, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.08, 0.88644437, 0.0000625, 0.92, 1.00077437, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.14494437, 0.8215, 0.0000625, 0.26527438, 0.88644437, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.85524456, 0.54481846, 0.0, 0.99830714, 0.73382622, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.73537154, 0.7014392, 0.0, 0.91508671, 0.88115436, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.57874984, 0.8213123, 0.0, 0.76775817, 0.96437511, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.85505562, 0.70143438, 0.0000625, 0.92, 0.88644437, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.73472563, 0.8215, 0.0000625, 0.85505562, 0.88644437, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.42125, 0.8861875, 0.0, 0.57875, 0.9649375, 1.0));
        return shape.simplify();
    }
    private static VoxelShape makeCornerMediumShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.71011062, 0.40165312, 0.0000625, 0.84, 0.77167312, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.46945063, 0.64178375, 0.0000625, 0.71011062, 0.77167312, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.77167312, 0.0000625, 0.84, 1.00033313, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.71048907, 0.08842027, 0.0, 0.99661445, 0.46643636, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.47074306, 0.40166369, 0.0, 0.83017384, 0.76109447, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15750006, 0.64140897, 0.0, 0.53551615, 0.92753435, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.77115875, 0.0, 0.158, 0.92865875, 1.0));
        return shape.simplify();
    }
    private static VoxelShape makeCornerLargeUpperShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.20697453, 0.10188703, 0.0, 0.74612047, 0.64103297, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.65701, 0.0000625, 0.761, 1.0, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.56627625, 0.10175, 0.0000625, 0.76111, 0.65778, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.20528625, 0.46217625, 0.0000625, 0.56627625, 0.65701, 0.9999375));
        return shape.simplify();
    }
    private static VoxelShape makeCornerLargeLowerShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.56659319, 0.63202298, 0.0, 0.99578115, 1.19904682, 1.0));
        return shape.simplify();
    }
    private static VoxelShape makeSideSmallShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.92037, 0.0, 0.0000625, 1.0, 1.0, 0.9999375));
        return shape.simplify();
    }
    private static VoxelShape makeSideMediumShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.84074, 0.0, 0.0000625, 1.0, 1.0, 0.9999375));
        return shape.simplify();
    }
    private static VoxelShape makeSideLargeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76111, 0.0, 0.0000625, 1.0, 1.0, 0.9999375));
        return shape.simplify();
    }
    private static VoxelShape makeSideMediumUpperShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.84074, 0.40625, 0.0000625, 1.0, 1.0, 0.9999375));
        return shape.simplify();
    }
    private static VoxelShape makeSideLargeUpperShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.76111, 0.78125, 0.0000625, 1.0, 1.0, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.745485, 0.71875, 0.0, 1.0, 0.78125, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.74548491, 0.67091432, 0.000625, 0.88488756, 0.77649222, 0.999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.83209906, 0.59448508, 0.00125, 0.99614783, 0.75853386, 0.99875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.88643092, 0.52996831, 0.000625, 0.99801403, 0.67185841, 0.999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.93426625, 0.52996875, 0.0, 0.99926625, 0.71896875, 1.0));
        return shape.simplify();
    }
    private static VoxelShape makeTopLargeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.65701, 0.0000625, 1.0, 1.0, 0.9999375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.263, 0.65613, 0.0, 0.7365, 0.89238, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.73711038, 0.46150549, 0.0, 1.30413422, 0.89069345, 1.0));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(-0.30413383, 0.4615059, 0.0, 0.26289001, 0.89069385, 1.0));
        return shape.simplify();
    }
}
