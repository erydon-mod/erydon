package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Exact exposed, full-height edges of half-depth supports; no world or shape scans. */
public final class CorniceHalfSupports {
    private static final double EPS = .00001;
    private static final List<List<Face>> GRID = createGrid();
    private static final List<List<Face>> BROAD = createBroad();
    private static final List<List<Face>> NARROW = createNarrow();
    private CorniceHalfSupports() { }

    public record Face(CorniceBlock.CorniceShape shape, Direction facing, Direction outward, CorniceRun supportRun) {
        public boolean contains(Vec3d localHit) {
            double x = localHit.x - supportRun.a().x(), z = localHit.z - supportRun.a().z();
            double normal = x * supportRun.normalX() + z * supportRun.normalZ();
            double along = (x * supportRun.tangentX() + z * supportRun.tangentZ()) / supportRun.length();
            return Math.abs(normal) <= EPS && along >= -EPS && along <= 1 + EPS;
        }
    }

    public static boolean candidate(BlockState state) {
        return state.getBlock() instanceof LayerVerticalBlock || state.getBlock() instanceof VerticalSliceBlock
                || state.getBlock() instanceof LayerMultifaceBlock || state.getBlock() instanceof SlopeVerticalShallowBroadBlock
                || state.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
    }

    public static List<Face> faces(BlockState state) {
        if (state.getBlock() instanceof SlopeVerticalShallowNarrowBlock) {
            int hand = state.get(SlopeVerticalShallowNarrowBlock.HAND) == SlopeVerticalShallowNarrowBlock.Handedness.LEFT ? 1 : 0;
            return NARROW.get(turns(state.get(SlopeVerticalShallowNarrowBlock.FACING)) * 2 + hand);
        }
        if (state.getBlock() instanceof SlopeVerticalShallowBroadBlock) {
            int hand = state.get(SlopeVerticalShallowBroadBlock.HAND) == SlopeVerticalShallowBroadBlock.Handedness.LEFT ? 1 : 0;
            return BROAD.get(turns(state.get(SlopeVerticalShallowBroadBlock.FACING)) * 2 + hand);
        }
        if (!candidate(state) || state.get(net.minecraft.state.property.Properties.LAYERS) != 4) return List.of();
        int mask;
        if (state.getBlock() instanceof LayerVerticalBlock) mask = switch (state.get(LayerVerticalBlock.FACING)) {
            case NORTH -> 3; case SOUTH -> 12; case WEST -> 5; case EAST -> 10; default -> 0;
        };
        else if (state.getBlock() instanceof VerticalSliceBlock) mask = switch (state.get(VerticalSliceBlock.FACING)) {
            case NORTH -> 1; case EAST -> 2; case SOUTH -> 8; case WEST -> 4; default -> 0;
        };
        else {
            mask = (state.get(LayerMultifaceBlock.NORTH) ? 3 : 0) | (state.get(LayerMultifaceBlock.SOUTH) ? 12 : 0)
                    | (state.get(LayerMultifaceBlock.WEST) ? 5 : 0) | (state.get(LayerMultifaceBlock.EAST) ? 10 : 0);
            // Horizontal caps change the exposed wall plane over the moulding's height.
            // A full cube retains the existing ordinary full-wall route.
            if (state.get(LayerMultifaceBlock.UP) || state.get(LayerMultifaceBlock.DOWN)) return List.of();
        }
        return GRID.get(mask);
    }

    public static Face face(BlockState support, Direction outward, CorniceBlock.CorniceShape shape) {
        for (Face face : faces(support)) if (face.outward == outward && face.shape == shape) return face;
        return null;
    }

    public static Face hit(BlockState support, Direction outward, Vec3d localHit) {
        for (Face face : faces(support)) if (face.outward == outward && face.contains(localHit)) return face;
        return null;
    }

    /** The saved cardinal poses use the same EAST-outward local basis as primary slopes. */
    public static CorniceRun supportRun(CorniceBlock.CorniceShape shape) {
        return switch (shape) {
            case HALF_INSET -> new CorniceRun(.5, 1, .5, 0);
            case HALF_EDGE_LOW -> new CorniceRun(1, .5, 1, 0);
            case HALF_EDGE_HIGH -> new CorniceRun(1, 1, 1, .5);
            case HALF_INSET_LOW -> new CorniceRun(.5, .5, .5, 0);
            case HALF_INSET_HIGH -> new CorniceRun(.5, 1, .5, .5);
            default -> throw new IllegalArgumentException("Not a half-support pose: " + shape);
        };
    }

    private static List<List<Face>> createGrid() {
        List<List<Face>> variants = new ArrayList<>();
        for (int mask = 0; mask < 16; mask++) {
            List<Face> result = new ArrayList<>();
            for (Direction side : Direction.Type.HORIZONTAL) for (int plane = 0; plane <= 2; plane++) {
                boolean[] edge = new boolean[2];
                for (int along = 0; along < 2; along++) {
                    int x = side.getAxis() == Direction.Axis.X ? (side == Direction.EAST ? plane - 1 : plane) : along;
                    int z = side.getAxis() == Direction.Axis.Z ? (side == Direction.SOUTH ? plane - 1 : plane) : along;
                    edge[along] = occupied(mask, x, z) && !occupied(mask, x + side.getOffsetX(), z + side.getOffsetZ());
                }
                for (int start = 0; start < 2; start++) {
                    if (!edge[start]) continue;
                    int end = start + 1;
                    if (end < 2 && edge[end]) end++;
                    CorniceRun run = switch (side) {
                        case EAST -> new CorniceRun(plane / 2.0, end / 2.0, plane / 2.0, start / 2.0);
                        case WEST -> new CorniceRun(plane / 2.0, start / 2.0, plane / 2.0, end / 2.0);
                        case SOUTH -> new CorniceRun(start / 2.0, plane / 2.0, end / 2.0, plane / 2.0);
                        default -> new CorniceRun(end / 2.0, plane / 2.0, start / 2.0, plane / 2.0);
                    };
                    Face face = pose(run, side);
                    if (face != null) result.add(face); // Full boundary squares use the unchanged native wall route.
                    start = end - 1;
                }
            }
            variants.add(List.copyOf(result));
        }
        return List.copyOf(variants);
    }

    private static boolean occupied(int mask, int x, int z) {
        return x >= 0 && x < 2 && z >= 0 && z < 2 && (mask & (1 << (x + 2 * z))) != 0;
    }

    private static List<List<Face>> createBroad() {
        List<List<Face>> variants = new ArrayList<>();
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            CorniceRun right = new CorniceRun(0, 1, .5, 1).rotate(facing);
            CorniceRun left = new CorniceRun(.5, 0, 0, 0).rotate(facing);
            Direction rightOut = Direction.SOUTH, leftOut = Direction.NORTH;
            for (int turn = 0; turn < turns(facing); turn++) { rightOut = rightOut.rotateYClockwise(); leftOut = leftOut.rotateYClockwise(); }
            variants.add(List.of(pose(right, rightOut))); variants.add(List.of(pose(left, leftOut)));
        }
        return List.copyOf(variants);
    }

    private static List<List<Face>> createNarrow() {
        List<List<Face>> variants = new ArrayList<>();
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            CorniceRun right = new CorniceRun(.5, 0, 0, 0).rotate(facing);
            CorniceRun left = new CorniceRun(0, 1, .5, 1).rotate(facing);
            Direction rightOut = Direction.NORTH, leftOut = Direction.SOUTH;
            for (int turn = 0; turn < turns(facing); turn++) { rightOut = rightOut.rotateYClockwise(); leftOut = leftOut.rotateYClockwise(); }
            variants.add(List.of(pose(right, rightOut))); variants.add(List.of(pose(left, leftOut)));
        }
        return List.copyOf(variants);
    }

    private static Face pose(CorniceRun run, Direction outward) {
        Direction facing = switch (outward) { case EAST -> Direction.NORTH; case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH; default -> Direction.WEST; };
        for (var shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isHalfSupport()) continue;
            CorniceRun candidate = supportRun(shape).rotate(facing);
            if (close(run.a(), candidate.a()) && close(run.b(), candidate.b())) return new Face(shape, facing, outward, run);
        }
        return null;
    }

    private static boolean close(CorniceRun.Point a, CorniceRun.Point b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()) < .000001; }
    private static int turns(Direction facing) { return switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; }; }
}
