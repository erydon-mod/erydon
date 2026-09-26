package com.oliver.erydon.block;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EmptyBlockView;

import java.lang.reflect.Method;

import static com.oliver.erydon.block.AlcoveBlock.*;

/** Regression for Axiom's Move Builder Tool: verify placed states and whole-cluster geometry. */
public final class AlcoveTransformLaunchProbe implements PreLaunchEntrypoint {
    private int cases;
    private Method axiomRotate;
    private Method axiomFlip;
    private Method axiomAdd;
    private Method axiomGet;
    private Class<?> regionClass;

    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            if (Boolean.getBoolean("erydon.alcove_test.axiom")) {
                regionClass = Class.forName("com.moulberry.axiom.render.regions.ChunkedBlockRegion");
                axiomRotate = regionClass.getMethod("rotate", Direction.Axis.class, int.class);
                axiomFlip = regionClass.getMethod("flip", Direction.Axis.class);
                axiomAdd = regionClass.getMethod("addBlock", int.class, int.class, int.class, BlockState.class);
                axiomGet = regionClass.getMethod("getBlockStateOrNull", int.class, int.class, int.class);
            }
            for (boolean gothic : new boolean[]{false, true}) {
                AlcoveBlock block = new AlcoveBlock(AbstractBlock.Settings.create(), 3, gothic);
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    for (int width = 1; width <= 3; width++) {
                        for (AlcovePart part : AlcovePart.values()) {
                            for (boolean waterlogged : new boolean[]{false, true}) {
                                for (BlockMirror mirror : BlockMirror.values()) {
                                    for (BlockRotation rotation : BlockRotation.values()) {
                                        verify(block, facing, width, part, waterlogged, mirror, rotation, false);
                                        verify(block, facing, width, part, waterlogged, mirror, rotation, true);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("ERYDON_ALCOVE_TRANSFORMS_OK: " + cases
                    + " clusters; both styles, widths 1/2/3, both operation orders, all facings, parts and water states; "
                    + (regionClass == null ? "Minecraft state entry points" : "actual Axiom region transformations"));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void verify(AlcoveBlock block, Direction facing, int width, AlcovePart part,
                        boolean waterlogged, BlockMirror mirror, BlockRotation rotation,
                        boolean rotateFirst) throws Exception {
        Direction across = facing.rotateYCounterclockwise();
        Direction movedAcross = transform(across, mirror, rotation, rotateFirst);
        Direction movedFacing = transform(facing, mirror, rotation, rotateFirst);
        Direction newAcross = movedFacing.rotateYCounterclockwise();
        boolean reversed = movedAcross != newAcross;
        BlockState[] source = new BlockState[width];
        Box sourceBounds = null;
        Object region = regionClass == null ? null : regionClass.getConstructor().newInstance();
        for (int x = 0; x < width; x++) {
            source[x] = block.getDefaultState().with(FACING, facing).with(SPAN, span(width, x))
                    .with(PART, part).with(WATERLOGGED, waterlogged);
            Box sourceBox = bounds(source[x]).offset(x * across.getOffsetX(), 0, x * across.getOffsetZ());
            sourceBounds = sourceBounds == null ? sourceBox : sourceBounds.union(sourceBox);
            if (region != null) axiomAdd.invoke(region, x * across.getOffsetX(), 0,
                    x * across.getOffsetZ(), source[x]);
        }
        if (region != null) {
            region = rotateFirst ? flipRegion(rotateRegion(region, rotation), mirror)
                    : rotateRegion(flipRegion(region, mirror), rotation);
        }
        Box actualBounds = null;
        for (int x = 0; x < width; x++) {
            BlockState actual = region == null
                    ? (rotateFirst ? source[x].rotate(rotation).mirror(mirror) : source[x].mirror(mirror).rotate(rotation))
                    : (BlockState) axiomGet.invoke(region, x * movedAcross.getOffsetX(), 0, x * movedAcross.getOffsetZ());
            BlockState expected = source[x].with(FACING, movedFacing)
                    .with(SPAN, span(width, reversed ? width - 1 - x : x));
            String context = "width=" + width + " facing=" + facing + " part=" + part + " col=" + x
                    + " " + mirror + " " + rotation + " rotateFirst=" + rotateFirst;
            require(actual != null, "Missing transformed block: " + context);
            // Use the actual shape too: the old mapping extends both sides outwards by whole blocks.
            Box actualBox = bounds(actual).offset(x * movedAcross.getOffsetX(), 0, x * movedAcross.getOffsetZ());
            actualBounds = actualBounds == null ? actualBox : actualBounds.union(actualBox);
            require(actual.get(FACING) == movedFacing && actual.get(SPAN) == expected.get(SPAN),
                    "Wrong alcove half: " + context + " expected " + expected.get(SPAN) + " got " + actual.get(SPAN));
            require(actual.get(PART) == part && actual.get(WATERLOGGED) == waterlogged,
                    "Transform changed the row or water state: " + context);
            require(actual.getBlock() == block, "Transform changed material/style: " + context);
            require(source[x].mirror(mirror).mirror(mirror) == source[x], "Double flip did not restore state");
            require(source[x].rotate(BlockRotation.CLOCKWISE_90).rotate(BlockRotation.CLOCKWISE_90)
                    .rotate(BlockRotation.CLOCKWISE_90).rotate(BlockRotation.CLOCKWISE_90) == source[x],
                    "Four quarter-turns did not restore state");
        }
        Box correctBounds = transformBounds(sourceBounds, mirror, rotation, rotateFirst);
        require(actualBounds.equals(correctBounds), "Transformed cluster extends beyond its intended width: "
                + actualBounds + " != " + correctBounds);
        cases++;
    }

    private Object flipRegion(Object region, BlockMirror mirror) throws Exception {
        return mirror == BlockMirror.NONE ? region : axiomFlip.invoke(region,
                mirror == BlockMirror.FRONT_BACK ? Direction.Axis.X : Direction.Axis.Z);
    }

    private Object rotateRegion(Object region, BlockRotation rotation) throws Exception {
        int turns = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> -1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 1;
        };
        return axiomRotate.invoke(region, Direction.Axis.Y, turns);
    }

    private static Direction transform(Direction direction, BlockMirror mirror, BlockRotation rotation, boolean rotateFirst) {
        return rotateFirst ? mirror.apply(rotation.rotate(direction)) : rotation.rotate(mirror.apply(direction));
    }

    private static AlcoveSpan span(int width, int column) {
        if (width == 1) return AlcoveSpan.SINGLE;
        if (width == 2) return column == 0 ? AlcoveSpan.LEFT : AlcoveSpan.RIGHT;
        return switch (column) {
            case 0 -> AlcoveSpan.TRIPLE_LEFT;
            case 1 -> AlcoveSpan.TRIPLE_CENTER;
            default -> AlcoveSpan.TRIPLE_RIGHT;
        };
    }

    private static Box bounds(BlockState state) {
        return state.getBlock().getCollisionShape(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()).getBoundingBox();
    }

    private static Box transformBounds(Box bounds, BlockMirror mirror, BlockRotation rotation, boolean rotateFirst) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                Vec3d point = new Vec3d(x - 0.5, 0, z - 0.5);
                point = rotateFirst ? mirror(rotate(point, rotation), mirror) : rotate(mirror(point, mirror), rotation);
                minX = Math.min(minX, point.x + 0.5); maxX = Math.max(maxX, point.x + 0.5);
                minZ = Math.min(minZ, point.z + 0.5); maxZ = Math.max(maxZ, point.z + 0.5);
            }
        }
        return new Box(minX, bounds.minY, minZ, maxX, bounds.maxY, maxZ);
    }

    private static Vec3d mirror(Vec3d point, BlockMirror mirror) {
        return new Vec3d(mirror == BlockMirror.FRONT_BACK ? -point.x : point.x, point.y,
                mirror == BlockMirror.LEFT_RIGHT ? -point.z : point.z);
    }

    private static Vec3d rotate(Vec3d point, BlockRotation rotation) {
        return switch (rotation) {
            case NONE -> point;
            case CLOCKWISE_90 -> new Vec3d(-point.z, point.y, point.x);
            case CLOCKWISE_180 -> new Vec3d(-point.x, point.y, -point.z);
            case COUNTERCLOCKWISE_90 -> new Vec3d(point.z, point.y, -point.x);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
