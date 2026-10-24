package com.oliver.erydon.client.model;

import com.oliver.erydon.block.LayerMultifaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static com.oliver.erydon.client.model.SpiralStairCtmGeometry.Vertex;
import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.Edge;
import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.Point;
import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.Surface;

/** Physical veneer geometry; selection data never includes the raycast-only targeting post. */
final class SynapheiaMultifaceConnections {
    private static final float EPS = 0.0001F;
    private static final AtomicReferenceArray<Geometry> GEOMETRY = new AtomicReferenceArray<>(8 * 64);

    private SynapheiaMultifaceConnections() { }

    static boolean supports(BlockState state) {
        return state.getBlock() instanceof LayerMultifaceBlock;
    }

    static List<Surface> surfaces(BlockState state) {
        return supports(state) ? geometry(state).surfaces : List.of();
    }

    /** Unlike an overlap test, every part of the queried border must have a neighbour. */
    static boolean coversEdge(Direction projection, Surface source, List<Surface> targets,
                              Edge edge, int dx, int dy, int dz, Set<Direction> allowedFaces) {
        List<Interval> coverage = new ArrayList<>();
        Point a = edge.a().shifted(-dx, -dy, -dz), b = edge.b().shifted(-dx, -dy, -dz);
        for (Surface target : targets) {
            if (!allowedFaces.contains(target.face)) continue;
            if (!SynapheiaSlopeConnections.surfacesJoin(projection, source, target, edge, dx, dy, dz)) continue;
            boundaryIntervals(target, a, b, coverage);
        }
        return complete(coverage);
    }

    /** Covering a whole edge requires real solid volume on its visible side. */
    static boolean occludesEdge(BlockState state, Edge edge, int dx, int dy, int dz, Direction face) {
        if (!supports(state)) return false;
        return complete(volumeIntervals(state, edge, dx, dy, dz, face));
    }

    /**
     * Null retains the existing renderer path. Only a matching veneer interaction creates
     * fragments, and cuts retain the original world-cell coordinates and vertex attributes.
     * Axis rectangles are the authored multiface/cube surfaces; sloped/triangular sources
     * retain their established path.
     */
    static List<List<Vertex>> fragments(SynapheiaNeighbourCache cache, BlockState state,
                                       Direction face, SynapheiaManifest.Rule rule, List<Vertex> source) {
        if (source.size() != 4 || face == null) return null;
        float minS = Float.POSITIVE_INFINITY, minT = Float.POSITIVE_INFINITY;
        float maxS = Float.NEGATIVE_INFINITY, maxT = Float.NEGATIVE_INFINITY;
        float plane = coordinate(source.get(0), face.getAxis());
        for (Vertex vertex : source) {
            if (Math.abs(coordinate(vertex, face.getAxis()) - plane) > EPS) return null;
            minS = Math.min(minS, s(face, vertex)); maxS = Math.max(maxS, s(face, vertex));
            minT = Math.min(minT, t(face, vertex)); maxT = Math.max(maxT, t(face, vertex));
        }
        if (maxS - minS <= EPS || maxT - minT <= EPS) return null;
        int corners = 0;
        for (Vertex vertex : source) {
            float s = s(face, vertex), t = t(face, vertex);
            if ((!close(s, minS) && !close(s, maxS)) || (!close(t, minT) && !close(t, maxT))) return null;
            corners |= 1 << ((close(s, maxS) ? 1 : 0) | (close(t, maxT) ? 2 : 0));
        }
        if (corners != 15) return null;

        boolean own = supports(state), physical = own;
        Surface surface = null;
        TreeSet<Float> cutsS = null, cutsT = null;
        if (own) {
            surface = SynapheiaSlopeConnections.surface(source);
            cutsS = cuts(minS, maxS); cutsT = cuts(minT, maxT);
            for (Surface part : surfaces(state)) {
                if (part.face != face || !close(coordinate(part.points[0], face.getAxis()), plane)) continue;
                addSurfaceCuts(part, face, 0, 0, 0, cutsS, cutsT, minS, maxS, minT, maxT);
            }
        }
        var sourceId = Registries.BLOCK.getId(state.getBlock());
        // These are the same cardinal/seam cells used by ordinary axis-face CTM.
        // Diagonal cells cannot introduce a breakpoint inside an axis rectangle edge.
        for (int i = 0; i < 4; i++) {
            var offset = SynapheiaRepeatBakedModel.tangentOffset(face, i);
            float bound = offset.x() + offset.y() + offset.z() > 0 ? 1 : 0;
            boolean alongS = face.getAxis() == Direction.Axis.X ? offset.z() != 0 : offset.x() != 0;
            if (!close(bound, alongS ? (bound == 0 ? minS : maxS) : (bound == 0 ? minT : maxT))) continue;
            BlockState neighbour = cache.get(offset.x(), offset.y(), offset.z());
            if (!SynapheiaRepeatBakedModel.overlayBlocksConnect(rule, sourceId,
                    Registries.BLOCK.getId(neighbour.getBlock()))) continue;
            int sx = offset.x() + face.getOffsetX(), sy = offset.y() + face.getOffsetY();
            int sz = offset.z() + face.getOffsetZ();
            BlockState seam = rule.innerSeams() ? cache.get(sx, sy, sz) : null;
            boolean partialNeighbour = supports(neighbour);
            boolean partialSeam = seam != null && supports(seam)
                    && SynapheiaRepeatBakedModel.overlayBlocksConnect(rule, sourceId,
                    Registries.BLOCK.getId(seam.getBlock()));
            if (!partialNeighbour && !partialSeam) continue;
            if (!physical) {
                physical = true; surface = SynapheiaSlopeConnections.surface(source);
                cutsS = cuts(minS, maxS); cutsT = cuts(minT, maxT);
            }
            Edge edge = surface.boundary(offset);
            if (edge == null) continue;
            if (partialNeighbour) {
                Point a = edge.a().shifted(-offset.x(), -offset.y(), -offset.z());
                Point b = edge.b().shifted(-offset.x(), -offset.y(), -offset.z());
                for (Surface target : surfaces(neighbour)) {
                    if (!rule.faces().contains(target.face)
                            || !SynapheiaSlopeConnections.surfacesJoin(face, surface, target, edge,
                            offset.x(), offset.y(), offset.z())) continue;
                    List<Interval> intervals = new ArrayList<>();
                    boundaryIntervals(target, a, b, intervals);
                    addIntervalCuts(intervals, edge, face, cutsS, cutsT, minS, maxS, minT, maxT);
                }
            }
            if (partialSeam) addIntervalCuts(volumeIntervals(seam, edge, sx, sy, sz, face),
                    edge, face, cutsS, cutsT, minS, maxS, minT, maxT);
        }
        if (!physical) return null;
        List<Float> ss = new ArrayList<>(cutsS), ts = new ArrayList<>(cutsT);
        List<List<Vertex>> result = new ArrayList<>();
        for (int si = 0; si + 1 < ss.size(); si++) for (int ti = 0; ti + 1 < ts.size(); ti++) {
            float loS = ss.get(si), hiS = ss.get(si + 1), loT = ts.get(ti), hiT = ts.get(ti + 1);
            if (own && !exposed(state, face, plane, (loS + hiS) / 2, (loT + hiT) / 2)) continue;
            if (ss.size() == 2 && ts.size() == 2) { result.add(source); continue; }
            List<Vertex> fragment = new ArrayList<>(4);
            for (Vertex original : source) fragment.add(interpolate(source, face,
                    close(s(face, original), minS) ? loS : hiS,
                    close(t(face, original), minT) ? loT : hiT, minS, maxS, minT, maxT));
            result.add(List.copyOf(fragment));
        }
        return List.copyOf(result);
    }

    private static Geometry geometry(BlockState state) {
        int layers = state.get(LayerMultifaceBlock.LAYERS), mask = 0;
        if (state.get(LayerMultifaceBlock.NORTH)) mask |= 1;
        if (state.get(LayerMultifaceBlock.SOUTH)) mask |= 2;
        if (state.get(LayerMultifaceBlock.EAST)) mask |= 4;
        if (state.get(LayerMultifaceBlock.WEST)) mask |= 8;
        if (state.get(LayerMultifaceBlock.UP)) mask |= 16;
        if (state.get(LayerMultifaceBlock.DOWN)) mask |= 32;
        int index = (layers - 1) * 64 + mask;
        Geometry cached = GEOMETRY.get(index);
        if (cached != null) return cached;
        Geometry created = build(layers, mask);
        GEOMETRY.compareAndSet(index, null, created);
        return GEOMETRY.get(index);
    }

    private static Geometry build(int layers, int mask) {
        float depth = layers / 8.0F;
        List<Box> boxes = new ArrayList<>(6);
        if ((mask & 1) != 0) boxes.add(new Box(0, 0, 0, 1, 1, depth));
        if ((mask & 2) != 0) boxes.add(new Box(0, 0, 1 - depth, 1, 1, 1));
        if ((mask & 4) != 0) boxes.add(new Box(1 - depth, 0, 0, 1, 1, 1));
        if ((mask & 8) != 0) boxes.add(new Box(0, 0, 0, depth, 1, 1));
        if ((mask & 16) != 0) boxes.add(new Box(0, 1 - depth, 0, 1, 1, 1));
        if ((mask & 32) != 0) boxes.add(new Box(0, 0, 0, 1, depth, 1));
        float[] grid = uniqueSorted(new float[]{0, depth, 1 - depth, 1});
        int cells = grid.length - 1;
        boolean[][][] filled = new boolean[cells][cells][cells];
        for (int x = 0; x < cells; x++) for (int y = 0; y < cells; y++) for (int z = 0; z < cells; z++) {
            Point center = new Point((grid[x] + grid[x + 1]) / 2, (grid[y] + grid[y + 1]) / 2,
                    (grid[z] + grid[z + 1]) / 2);
            for (Box box : boxes) if (box.contains(center)) { filled[x][y][z] = true; break; }
        }
        List<Surface> surfaces = new ArrayList<>();
        for (Direction face : Direction.values()) for (int plane = 0; plane <= cells; plane++) {
            boolean positive = face.getDirection() == Direction.AxisDirection.POSITIVE;
            int inside = positive ? plane - 1 : plane, outside = positive ? plane : plane - 1;
            boolean[][] tiles = new boolean[cells][cells];
            for (int a = 0; a < cells; a++) for (int b = 0; b < cells; b++) {
                int[] p = indices(face.getAxis(), inside, a, b), q = indices(face.getAxis(), outside, a, b);
                tiles[a][b] = occupied(filled, p) && !occupied(filled, q);
            }
            for (int a = 0; a < cells; a++) for (int b = 0; b < cells; b++) {
                if (!tiles[a][b]) continue;
                int endA = a + 1;
                while (endA < cells && tiles[endA][b]) endA++;
                int endB = b + 1;
                outer: while (endB < cells) {
                    for (int u = a; u < endA; u++) if (!tiles[u][endB]) break outer;
                    endB++;
                }
                for (int u = a; u < endA; u++) for (int v = b; v < endB; v++) tiles[u][v] = false;
                surfaces.add(new Surface(face, point(face.getAxis(), grid[plane], grid[a], grid[b]),
                        point(face.getAxis(), grid[plane], grid[endA], grid[b]),
                        point(face.getAxis(), grid[plane], grid[endA], grid[endB]),
                        point(face.getAxis(), grid[plane], grid[a], grid[endB])));
            }
        }
        return new Geometry(List.copyOf(boxes), List.copyOf(surfaces));
    }

    private static void boundaryIntervals(Surface target, Point a, Point b, List<Interval> out) {
        Point d = b.minus(a); float length = d.dot(d);
        for (int i = 0; i < target.points.length; i++) {
            Point p = target.points[i], q = target.points[(i + 1) % target.points.length];
            Edge boundary = new Edge(p, q);
            if (length <= EPS * EPS) { if (boundary.contains(a)) out.add(new Interval(0, 1)); continue; }
            Point cp = d.cross(p.minus(a)), cq = d.cross(q.minus(a));
            if (cp.dot(cp) > EPS * EPS * length || cq.dot(cq) > EPS * EPS * length) continue;
            float u = p.minus(a).dot(d) / length, v = q.minus(a).dot(d) / length;
            float lo = Math.max(0, Math.min(u, v)), hi = Math.min(1, Math.max(u, v));
            if (hi - lo > EPS) out.add(new Interval(lo, hi));
        }
    }

    private static List<Interval> volumeIntervals(BlockState state, Edge edge,
                                                 int dx, int dy, int dz, Direction face) {
        Point n = new Point(face.getOffsetX() * EPS * 4, face.getOffsetY() * EPS * 4, face.getOffsetZ() * EPS * 4);
        Point a = edge.a().shifted(-dx, -dy, -dz);
        a = new Point(a.x() + n.x(), a.y() + n.y(), a.z() + n.z());
        Point d = edge.b().minus(edge.a());
        List<Interval> result = new ArrayList<>();
        for (Box box : geometry(state).boxes) {
            float lo = 0, hi = 1;
            for (Direction.Axis axis : Direction.Axis.values()) {
                float origin = coordinate(a, axis), delta = coordinate(d, axis);
                float min = box.min(axis), max = box.max(axis);
                if (Math.abs(delta) <= EPS) {
                    if (origin < min - EPS || origin > max + EPS) { hi = -1; break; }
                } else {
                    float p = (min - origin) / delta, q = (max - origin) / delta;
                    lo = Math.max(lo, Math.min(p, q)); hi = Math.min(hi, Math.max(p, q));
                }
            }
            if (hi >= lo) result.add(new Interval(lo, hi));
        }
        return result;
    }

    private static boolean complete(List<Interval> intervals) {
        if (intervals.isEmpty()) return false;
        intervals.sort(Comparator.comparingDouble(Interval::lo));
        float reached = 0;
        for (Interval interval : intervals) {
            if (interval.lo > reached + EPS) return false;
            reached = Math.max(reached, interval.hi);
            if (reached >= 1 - EPS) return true;
        }
        return false;
    }

    private static boolean exposed(BlockState state, Direction face, float plane, float s, float t) {
        for (Surface surface : surfaces(state)) {
            if (surface.face != face || !close(coordinate(surface.points[0], face.getAxis()), plane)) continue;
            float minS = Float.POSITIVE_INFINITY, minT = Float.POSITIVE_INFINITY;
            float maxS = Float.NEGATIVE_INFINITY, maxT = Float.NEGATIVE_INFINITY;
            for (Point p : surface.points) { minS = Math.min(minS, s(face, p)); maxS = Math.max(maxS, s(face, p));
                minT = Math.min(minT, t(face, p)); maxT = Math.max(maxT, t(face, p)); }
            if (s >= minS - EPS && s <= maxS + EPS && t >= minT - EPS && t <= maxT + EPS) return true;
        }
        return false;
    }

    private static void addSurfaceCuts(Surface surface, Direction face, int dx, int dy, int dz,
                                       TreeSet<Float> ss, TreeSet<Float> ts,
                                       float minS, float maxS, float minT, float maxT) {
        for (Point p : surface.points) {
            Point moved = p.shifted(dx, dy, dz);
            addCut(ss, s(face, moved), minS, maxS); addCut(ts, t(face, moved), minT, maxT);
        }
    }

    private static void addIntervalCuts(List<Interval> intervals, Edge edge, Direction face,
                                        TreeSet<Float> ss, TreeSet<Float> ts,
                                        float minS, float maxS, float minT, float maxT) {
        Point a = edge.a(), d = edge.b().minus(a);
        for (Interval interval : intervals) for (float amount : new float[]{interval.lo, interval.hi}) {
            Point p = new Point(a.x() + amount * d.x(), a.y() + amount * d.y(), a.z() + amount * d.z());
            addCut(ss, s(face, p), minS, maxS); addCut(ts, t(face, p), minT, maxT);
        }
    }

    private static Vertex interpolate(List<Vertex> source, Direction face, float s, float t,
                                      float minS, float maxS, float minT, float maxT) {
        Vertex[] corners = new Vertex[4];
        for (Vertex v : source) corners[(close(s(face, v), maxS) ? 1 : 0) | (close(t(face, v), maxT) ? 2 : 0)] = v;
        float u = (s - minS) / (maxS - minS), v = (t - minT) / (maxT - minT);
        if ((u == 0 || u == 1) && (v == 0 || v == 1)) return corners[(u == 1 ? 1 : 0) | (v == 1 ? 2 : 0)];
        return Vertex.interpolate(Vertex.interpolate(corners[0], corners[1], u),
                Vertex.interpolate(corners[2], corners[3], u), v);
    }

    private static TreeSet<Float> cuts(float min, float max) { TreeSet<Float> result = new TreeSet<>(); result.add(min); result.add(max); return result; }
    private static void addCut(TreeSet<Float> cuts, float value, float min, float max) { if (value > min + EPS && value < max - EPS) cuts.add(value); }
    private static float[] uniqueSorted(float[] values) { Arrays.sort(values); int n = 0; for (float v : values) if (n == 0 || !close(values[n - 1], v)) values[n++] = v; return Arrays.copyOf(values, n); }
    private static boolean occupied(boolean[][][] cells, int[] p) { int n = cells.length; return p[0] >= 0 && p[1] >= 0 && p[2] >= 0 && p[0] < n && p[1] < n && p[2] < n && cells[p[0]][p[1]][p[2]]; }
    private static int[] indices(Direction.Axis axis, int plane, int a, int b) { return switch (axis) { case X -> new int[]{plane, a, b}; case Y -> new int[]{a, plane, b}; case Z -> new int[]{a, b, plane}; }; }
    private static Point point(Direction.Axis axis, float plane, float a, float b) { return switch (axis) { case X -> new Point(plane, a, b); case Y -> new Point(a, plane, b); case Z -> new Point(a, b, plane); }; }
    private static float coordinate(Vertex p, Direction.Axis axis) { return switch (axis) { case X -> p.x(); case Y -> p.y(); case Z -> p.z(); }; }
    private static float coordinate(Point p, Direction.Axis axis) { return switch (axis) { case X -> p.x(); case Y -> p.y(); case Z -> p.z(); }; }
    private static float s(Direction face, Vertex p) { return SpiralStairCtmGeometry.textureS(face, p.x(), p.y(), p.z()); }
    private static float t(Direction face, Vertex p) { return SpiralStairCtmGeometry.textureT(face, p.x(), p.y(), p.z()); }
    private static float s(Direction face, Point p) { return face.getAxis() == Direction.Axis.X ? p.z() : p.x(); }
    private static float t(Direction face, Point p) { return face.getAxis() == Direction.Axis.Y ? p.z() : p.y(); }
    private static boolean close(float a, float b) { return Math.abs(a - b) <= EPS; }
    private record Interval(float lo, float hi) { }
    private record Geometry(List<Box> boxes, List<Surface> surfaces) { }
    private record Box(float x0, float y0, float z0, float x1, float y1, float z1) {
        boolean contains(Point p) { return p.x() >= x0 && p.x() <= x1 && p.y() >= y0 && p.y() <= y1 && p.z() >= z0 && p.z() <= z1; }
        float min(Direction.Axis a) { return switch (a) { case X -> x0; case Y -> y0; case Z -> z0; }; }
        float max(Direction.Axis a) { return switch (a) { case X -> x1; case Y -> y1; case Z -> z1; }; }
    }
}
