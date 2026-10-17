package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/** A diagonal cornice keeps an adjacent air-cell owner and the clicked slope as its support. */
public final class CorniceSlopePlacement {
    private CorniceSlopePlacement() { }

    public static Direction facing(BlockState support) {
        return support.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
    }

    public static CorniceBlock.CorniceShape shapeFor(BlockState support, Direction outwardSide) {
        boolean diagonal = support.getBlock() instanceof SlopeVerticalBlock;
        boolean broad = support.getBlock() instanceof SlopeVerticalShallowBroadBlock;
        boolean narrow = support.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
        if (!diagonal && !broad && !narrow || !outwardSide.getAxis().isHorizontal()) return null;
        boolean left = broad ? support.get(SlopeVerticalShallowBroadBlock.HAND) == SlopeVerticalShallowBroadBlock.Handedness.LEFT
                : narrow && support.get(SlopeVerticalShallowNarrowBlock.HAND) == SlopeVerticalShallowNarrowBlock.Handedness.LEFT;
        Direction local = outwardSide;
        for (int turn = 0; turn < turns(facing(support)); turn++) local = local.rotateYCounterclockwise();
        if (local != Direction.EAST && local != (left ? Direction.NORTH : Direction.SOUTH)) return null;
        boolean primary = local == Direction.EAST;
        if (diagonal) return primary ? CorniceBlock.CorniceShape.DIAGONAL_PRIMARY : CorniceBlock.CorniceShape.DIAGONAL_SECONDARY;
        if (broad) return left
                ? primary ? CorniceBlock.CorniceShape.BROAD_LEFT_PRIMARY : CorniceBlock.CorniceShape.BROAD_LEFT_SECONDARY
                : primary ? CorniceBlock.CorniceShape.BROAD_RIGHT_PRIMARY : CorniceBlock.CorniceShape.BROAD_RIGHT_SECONDARY;
        return left
                ? primary ? CorniceBlock.CorniceShape.NARROW_LEFT_PRIMARY : CorniceBlock.CorniceShape.NARROW_LEFT_SECONDARY
                : primary ? CorniceBlock.CorniceShape.NARROW_RIGHT_PRIMARY : CorniceBlock.CorniceShape.NARROW_RIGHT_SECONDARY;
    }

    public static CorniceRun run(BlockState state) {
        CorniceBlock.CorniceShape shape = state.get(CorniceBlock.SHAPE);
        CorniceRun run = switch (shape) {
            case DIAGONAL_PRIMARY, DIAGONAL_SECONDARY -> new CorniceRun(0, 1, 1, 0);
            case BROAD_RIGHT_PRIMARY, BROAD_RIGHT_SECONDARY -> new CorniceRun(.5, 1, 1, 0);
            case BROAD_LEFT_PRIMARY, BROAD_LEFT_SECONDARY -> new CorniceRun(1, 1, .5, 0);
            case NARROW_RIGHT_PRIMARY, NARROW_RIGHT_SECONDARY -> new CorniceRun(0, 1, .5, 0);
            case NARROW_LEFT_PRIMARY, NARROW_LEFT_SECONDARY -> new CorniceRun(.5, 1, 0, 0);
            default -> new CorniceRun(0, 0, 1, 0);
        };
        if (shape.isAngled()) {
            Direction outward = localOutward(shape);
            run = run.translated(-outward.getOffsetX(), -outward.getOffsetZ());
        }
        return run.rotate(state.get(CorniceBlock.FACING));
    }

    public static Direction outward(BlockState state) {
        Direction outward = localOutward(state.get(CorniceBlock.SHAPE));
        for (int turn = 0; turn < turns(state.get(CorniceBlock.FACING)); turn++) outward = outward.rotateYClockwise();
        return outward;
    }

    public static BlockPos supportPos(BlockState state, BlockPos pos) {
        return pos.offset(outward(state).getOpposite());
    }

    public static BlockState resolve(BlockState state, BlockView world, BlockPos pos, Direction preferredSupport) {
        if (!preferredSupport.getAxis().isHorizontal()) return null;
        BlockPos supportPos = pos.offset(preferredSupport);
        BlockState support = world.getBlockState(supportPos);
        CorniceBlock.CorniceShape shape = shapeFor(support, preferredSupport.getOpposite());
        if (shape == null) return null;
        // The alternate front-side cell must never own a second moulding on the same face.
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (shapeFor(support, side) == null) continue;
            BlockPos otherPos = supportPos.offset(side);
            if (otherPos.equals(pos)) continue;
            for (BlockState other : CorniceAssembly.contents(world, otherPos).cornices()) {
                if (other.get(CorniceBlock.SHAPE).isAngled()
                        && supportPos(other, otherPos).equals(supportPos)) return null;
            }
        }
        return state.with(CorniceBlock.FACING, facing(support)).with(CorniceBlock.SHAPE, shape);
    }

    public static boolean attached(BlockState state, BlockView world, BlockPos pos) {
        BlockState support = world.getBlockState(supportPos(state, pos));
        return shapeFor(support, outward(state)) == state.get(CorniceBlock.SHAPE)
                && facing(support) == state.get(CorniceBlock.FACING);
    }

    /** Match the reflected physical edge, including its owner-cell translation and handedness. */
    public static BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) return state;
        CorniceRun source = run(state);
        CorniceRun expected = new CorniceRun(reflect(source.b(), mirror), reflect(source.a(), mirror));
        for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isAngled()) continue;
            for (Direction facing : Direction.Type.HORIZONTAL) {
                BlockState candidate = state.with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                CorniceRun actual = run(candidate);
                if (close(actual.a(), expected.a()) && close(actual.b(), expected.b())) return candidate;
            }
        }
        throw new IllegalStateException("Missing reflected cornice support pose: " + state);
    }

    private static CorniceRun.Point reflect(CorniceRun.Point point, BlockMirror mirror) {
        return mirror == BlockMirror.LEFT_RIGHT ? new CorniceRun.Point(point.x(), 1 - point.z())
                : new CorniceRun.Point(1 - point.x(), point.z());
    }

    private static boolean close(CorniceRun.Point a, CorniceRun.Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) < .000001;
    }

    private static Direction localOutward(CorniceBlock.CorniceShape shape) {
        return switch (shape) {
            case DIAGONAL_SECONDARY, BROAD_RIGHT_SECONDARY, NARROW_RIGHT_SECONDARY -> Direction.SOUTH;
            case BROAD_LEFT_SECONDARY, NARROW_LEFT_SECONDARY -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static int turns(Direction facing) {
        return switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }
}
