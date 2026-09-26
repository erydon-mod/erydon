package com.oliver.erydon.client.model;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Matches actual ramp edges during chunk building. Shape data is shared across materials. */
final class SynapheiaSlopeConnections {
    private static final float EPS = 0.0001F;
    private static final AtomicReferenceArray<List<Surface>> SHAPES = new AtomicReferenceArray<>(9 * 4 * 5 * 2 * 2);

    private SynapheiaSlopeConnections() { }

    static void clear() {
        for (int i = 0; i < SHAPES.length(); i++) SHAPES.set(i, null);
    }

    static Surface surface(List<SpiralStairCtmGeometry.Vertex> vertices) {
        Point[] points = new Point[vertices.size()];
        for (int i = 0; i < points.length; i++) {
            var v = vertices.get(i);
            points[i] = new Point(v.x(), v.y(), v.z());
        }
        return new Surface(points);
    }

    static boolean sloped(List<SpiralStairCtmGeometry.Vertex> vertices) {
        var a = vertices.get(0); var b = vertices.get(1); var c = vertices.get(2);
        float x1 = b.x() - a.x(), y1 = b.y() - a.y(), z1 = b.z() - a.z();
        float x2 = c.x() - a.x(), y2 = c.y() - a.y(), z2 = c.z() - a.z();
        return (Math.abs(y1 * z2 - z1 * y2) > EPS ? 1 : 0)
                + (Math.abs(z1 * x2 - x1 * z2) > EPS ? 1 : 0)
                + (Math.abs(x1 * y2 - y1 * x2) > EPS ? 1 : 0) > 1;
    }

    static List<Surface> surfaces(BlockState state, ErydonSlopeModelClassifier.Family family) {
        int facing = enumIndex(state, "facing");
        // Direction enum has vertical entries; the horizontal index is compact and stable.
        Property<?> facingProperty = state.getBlock().getStateManager().getProperty("facing");
        if (facingProperty != null && state.get(facingProperty) instanceof Direction direction)
            facing = direction.getHorizontal();
        int index = ((((family.ordinal() * 4 + facing) * 5 + enumIndex(state, "shape")) * 2
                + enumIndex(state, "half")) * 2 + enumIndex(state, "hand"));
        List<Surface> cached = SHAPES.get(index);
        if (cached != null) return cached;
        var model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        List<Surface> result = new ArrayList<>();
        Random random = Random.create(0);
        // Slopes expose their smooth unculled Axiom quads, including their hypotenuses.
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            int[] data = quad.getVertexData();
            int stride = data.length / 4;
            Point[] points = new Point[4];
            for (int i = 0; i < 4; i++) points[i] = new Point(Float.intBitsToFloat(data[i * stride]),
                    Float.intBitsToFloat(data[i * stride + 1]), Float.intBitsToFloat(data[i * stride + 2]));
            Surface surface = new Surface(points);
            if (surface.sloped()) result.add(surface);
        }
        cached = List.copyOf(result);
        SHAPES.compareAndSet(index, null, cached);
        return SHAPES.get(index);
    }

    private static int enumIndex(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateManager().getProperty(name);
        return property != null && state.get(property) instanceof Enum<?> value ? value.ordinal() : 0;
    }

    static int mask(Direction face, Surface source, Lookup lookup) {
        Edge[] edges = new Edge[4];
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            var direction = SynapheiaRepeatBakedModel.tangentOffset(face, i);
            edges[i] = source.boundary(direction);
            if (edges[i] != null && connects(edges[i], direction, lookup)) mask |= 1 << (2 * i);
        }
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) & 3;
            if ((mask & (1 << (2 * i))) == 0 || (mask & (1 << (2 * next))) == 0) continue;
            Point corner = edges[i].sharedEnd(edges[next]);
            if (corner == null) continue;
            var a = SynapheiaRepeatBakedModel.tangentOffset(face, i);
            var b = SynapheiaRepeatBakedModel.tangentOffset(face, next);
            if (connects(new Edge(corner, corner), new SynapheiaRepeatBakedModel.Offset(
                    a.x() + b.x(), a.y() + b.y(), a.z() + b.z()), lookup)) mask |= 1 << (2 * i + 1);
        }
        return mask;
    }

    private static boolean connects(Edge edge, SynapheiaRepeatBakedModel.Offset direction, Lookup lookup) {
        Point midpoint = edge.midpoint();
        int xExtra = boundaryOffset(midpoint.x), yExtra = boundaryOffset(midpoint.y), zExtra = boundaryOffset(midpoint.z);
        // A ramp edge on two grid planes may meet either block on the second axis.
        // At most two candidates per edge, still inside the existing 3x3x3 cache.
        for (int x = 0; x <= (direction.x() == 0 && xExtra != 0 ? 1 : 0); x++) {
            for (int y = 0; y <= (direction.y() == 0 && yExtra != 0 ? 1 : 0); y++) {
                for (int z = 0; z <= (direction.z() == 0 && zExtra != 0 ? 1 : 0); z++) {
                    int dx = direction.x() != 0 ? direction.x() : x * xExtra;
                    int dy = direction.y() != 0 ? direction.y() : y * yExtra;
                    int dz = direction.z() != 0 ? direction.z() : z * zExtra;
                    if (lookup.connects(dx, dy, dz, edge)) return true;
                }
            }
        }
        return false;
    }

    private static int boundaryOffset(float coordinate) {
        return close(coordinate, 0) ? -1 : close(coordinate, 1) ? 1 : 0;
    }

    interface Lookup { boolean connects(int x, int y, int z, Edge sourceEdge); }

    record Point(float x, float y, float z) {
        Point minus(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
        Point shifted(int dx, int dy, int dz) { return new Point(x + dx, y + dy, z + dz); }
        float dot(Point other) { return x * other.x + y * other.y + z * other.z; }
        Point cross(Point p) { return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x); }
        boolean near(Point p) { return close(x, p.x) && close(y, p.y) && close(z, p.z); }
        float along(SynapheiaRepeatBakedModel.Offset d) { return x * d.x() + y * d.y() + z * d.z(); }
    }

    record Edge(Point a, Point b) {
        Point midpoint() { return new Point((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2); }
        Point sharedEnd(Edge other) {
            if (a.near(other.a) || a.near(other.b)) return a;
            return b.near(other.a) || b.near(other.b) ? b : null;
        }
        boolean contains(Point p) {
            Point d = b.minus(a), relative = p.minus(a);
            float length = d.dot(d);
            if (length < EPS * EPS) return a.near(p);
            Point cross = d.cross(relative);
            float projection = relative.dot(d);
            return cross.dot(cross) <= EPS * EPS * length && projection >= -EPS && projection <= length + EPS;
        }
    }

    static final class Surface {
        final Point[] points;
        Surface(Point... points) { this.points = points; }

        boolean sloped() {
            Point normal = points[1].minus(points[0]).cross(points[2].minus(points[0]));
            int axes = (Math.abs(normal.x) > EPS ? 1 : 0) + (Math.abs(normal.y) > EPS ? 1 : 0)
                    + (Math.abs(normal.z) > EPS ? 1 : 0);
            return axes > 1;
        }

        Edge boundary(SynapheiaRepeatBakedModel.Offset direction) {
            // CTM borders live on projected block boundaries, not internal triangle edges.
            float coordinate = direction.x() + direction.y() + direction.z() > 0 ? 1 : 0;
            for (int i = 0; i < points.length; i++) {
                Point a = points[i], b = points[(i + 1) % points.length];
                if (!a.near(b) && close(a.along(direction), coordinate) && close(b.along(direction), coordinate))
                    return new Edge(a, b);
            }
            return null;
        }

        boolean meets(Edge edge, int dx, int dy, int dz) {
            Point a = edge.a.shifted(-dx, -dy, -dz), b = edge.b.shifted(-dx, -dy, -dz);
            for (int i = 0; i < points.length; i++) {
                Edge target = new Edge(points[i], points[(i + 1) % points.length]);
                if (target.contains(a) && target.contains(b)) return true;
            }
            return false;
        }
    }

    private static boolean close(float a, float b) { return Math.abs(a - b) < EPS; }
}
