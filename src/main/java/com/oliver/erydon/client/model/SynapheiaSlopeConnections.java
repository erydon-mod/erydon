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
    private static final Surface[] CUBE_FACES = createCubeFaces();

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

    static Surface cubeFace(Direction face) {
        return CUBE_FACES[face.ordinal()];
    }

    /** Actual cube faces sharing this edge, rather than the ramp's UV projection face. */
    static int cubeJoinFaces(Direction projection, Surface source, Edge edge, int dx, int dy, int dz) {
        int faces = 0;
        for (Surface cube : CUBE_FACES) {
            Direction face = cube.face;
            // Ordinary cubes still connect on one plane. Folding is limited to real ramps.
            if (!source.sloped() && face != projection) continue;
            if (surfacesJoin(projection, source, cube, edge, dx, dy, dz))
                faces |= 1 << face.ordinal();
        }
        return faces;
    }

    static boolean surfacesJoin(Direction projection, Surface source, Surface target,
                                Edge edge, int dx, int dy, int dz) {
        if (!target.meets(edge, dx, dy, dz)) return false;
        Point a = source.normal(projection), b = target.normal(target.face);
        float aLength = a.dot(a), bLength = b.dot(b);
        if (aLength < EPS * EPS || bLength < EPS * EPS) return false;
        float lengths = aLength * bLength;
        Point cross = a.cross(b);
        boolean parallel = cross.dot(cross) <= EPS * EPS * lengths;
        // A real fold can be obtuse (the cube above a ramp faces back towards it).
        // Only back-to-back parallel faces cannot form one visible surface.
        if (parallel && a.dot(b) < 0) return false;
        return source.sloped() || target.sloped() || parallel;
    }

    static boolean faceVisibilityInCache(int dx, int dy, int dz, Direction face) {
        return Math.abs(dx + face.getOffsetX()) <= 1 && Math.abs(dy + face.getOffsetY()) <= 1
                && Math.abs(dz + face.getOffsetZ()) <= 1;
    }

    private static Surface[] createCubeFaces() {
        Surface[] faces = new Surface[Direction.values().length];
        for (Direction face : Direction.values()) {
            Point[] points = new Point[4];
            float boundary = face.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int i = 0; i < 4; i++) {
                float a = i == 1 || i == 2 ? 1 : 0;
                float b = i >= 2 ? 1 : 0;
                points[i] = switch (face.getAxis()) {
                    case X -> new Point(boundary, a, b);
                    case Y -> new Point(a, boundary, b);
                    case Z -> new Point(a, b, boundary);
                };
            }
            faces[face.ordinal()] = new Surface(face,points);
        }
        return faces;
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
            Surface surface = new Surface(quad.getFace(),points);
            result.add(surface);
        }
        // Side faces are emitted as strips. Reassemble their outline before choosing
        // a CTM tile so each strip uses the same physical triangular/trapezoid face.
        for (Direction face : Direction.values()) {
            List<Point> points = new ArrayList<>();
            for (Surface surface : result) if (surface.onBoundary(face)) points.addAll(List.of(surface.points));
            if (points.size() >= 3) {
                Surface merged=hull(face,points);
                if(merged.points.length>=3) result.add(merged);
            }
        }
        cached = List.copyOf(result);
        SHAPES.compareAndSet(index, null, cached);
        return SHAPES.get(index);
    }

    static Surface hull(Direction face, List<Point> points) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(java.util.Comparator.comparingDouble((Point p) -> horizontal(face,p))
                .thenComparingDouble(p -> vertical(face,p)));
        List<Point> unique = new ArrayList<>();
        for (Point p : sorted) if (unique.isEmpty() || !unique.get(unique.size()-1).near(p)) unique.add(p);
        List<Point> boundary = new ArrayList<>();
        for (Point p : unique) {
            while (boundary.size() >= 2 && turn(face,boundary.get(boundary.size()-2),boundary.get(boundary.size()-1),p) <= EPS)
                boundary.remove(boundary.size()-1);
            boundary.add(p);
        }
        int lower = boundary.size();
        for (int i=unique.size()-2;i>=0;i--) {
            Point p=unique.get(i);
            while (boundary.size() > lower && turn(face,boundary.get(boundary.size()-2),boundary.get(boundary.size()-1),p) <= EPS)
                boundary.remove(boundary.size()-1);
            boundary.add(p);
        }
        if (boundary.size()>1) boundary.remove(boundary.size()-1);
        return new Surface(face,boundary.toArray(Point[]::new));
    }

    private static float horizontal(Direction face,Point p) { return face.getAxis()==Direction.Axis.X ? p.z : p.x; }
    private static float vertical(Direction face,Point p) { return face.getAxis()==Direction.Axis.Y ? p.z : p.y; }
    private static float turn(Direction face,Point a,Point b,Point c) {
        return (horizontal(face,b)-horizontal(face,a))*(vertical(face,c)-vertical(face,a))
                -(vertical(face,b)-vertical(face,a))*(horizontal(face,c)-horizontal(face,a));
    }

    static Surface wholeFace(BlockState state,ErydonSlopeModelClassifier.Family family,Direction face,Surface source) {
        if (!source.onBoundary(face)) return source;
        // Merged boundary faces are appended after the raw quads.
        List<Surface> candidates=surfaces(state,family);
        for (int i=candidates.size()-1;i>=0;i--) if (candidates.get(i).onBoundary(face)) return candidates.get(i);
        return source;
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
        if (!edge.a.near(edge.b)) {
            // A ramp endpoint on two grid planes may meet the tangent, normal-adjacent,
            // or diagonally raised cube. All three cells use the existing 3x3x3 cache.
            for (int x = 0; x <= (xExtra != 0 ? 1 : 0); x++) {
                for (int y = 0; y <= (yExtra != 0 ? 1 : 0); y++) {
                    for (int z = 0; z <= (zExtra != 0 ? 1 : 0); z++) {
                        int dx = x * xExtra, dy = y * yExtra, dz = z * zExtra;
                        if ((dx != 0 || dy != 0 || dz != 0) && lookup.connects(dx, dy, dz, edge)) return true;
                    }
                }
            }
            return false;
        }
        // A CTM corner still requires the projected diagonal, not either cardinal cell.
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
        boolean overlaps(Edge other) {
            Point d=b.minus(a);
            float length=d.dot(d);
            if (length<EPS*EPS) return other.contains(a);
            Point e=other.b.minus(other.a);
            if (e.dot(e)<EPS*EPS) return contains(other.a);
            if (d.cross(other.a.minus(a)).dot(d.cross(other.a.minus(a)))>EPS*EPS*length
                    || d.cross(other.b.minus(a)).dot(d.cross(other.b.minus(a)))>EPS*EPS*length) return false;
            float t0=other.a.minus(a).dot(d)/length,t1=other.b.minus(a).dot(d)/length;
            return Math.min(1,Math.max(t0,t1))-Math.max(0,Math.min(t0,t1))>EPS;
        }
    }

    static final class Surface {
        final Point[] points;
        final Direction face;
        private final Point geometricNormal;
        private final Point reversedNormal;
        Surface(Point... points) { this(null,points); }
        Surface(Direction face,Point... points) {
            this.face=face;
            this.points = points;
            Point normal = new Point(0, 0, 0);
            // Some emitted triangle quads duplicate a vertex. Find the first real fan triangle.
            for (int i = 1; i + 1 < points.length; i++) {
                normal = points[i].minus(points[0]).cross(points[i + 1].minus(points[0]));
                if (normal.dot(normal) > EPS * EPS) break;
            }
            geometricNormal = normal;
            reversedNormal = new Point(-normal.x, -normal.y, -normal.z);
        }

        Point normal(Direction hint) {
            if (hint == null) return geometricNormal;
            float dot = geometricNormal.x * hint.getOffsetX() + geometricNormal.y * hint.getOffsetY()
                    + geometricNormal.z * hint.getOffsetZ();
            return dot < 0 ? reversedNormal : geometricNormal;
        }

        boolean sloped() {
            Point normal = geometricNormal;
            int axes = (Math.abs(normal.x) > EPS ? 1 : 0) + (Math.abs(normal.y) > EPS ? 1 : 0)
                    + (Math.abs(normal.z) > EPS ? 1 : 0);
            return axes > 1;
        }

        boolean onBoundary(Direction face) {
            float boundary=face.getDirection()==Direction.AxisDirection.POSITIVE ? 1 : 0;
            for(Point p:points) {
                float value=switch(face.getAxis()) { case X -> p.x; case Y -> p.y; case Z -> p.z; };
                if (Math.abs(value-boundary)>.0006F) return false;
            }
            return true;
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
                // Triangle quads duplicate a vertex. That zero-length target edge
                // is a point contact, not a shared border. CTM corner queries are
                // deliberately points and retain their existing containment test.
                if (!a.near(b) && target.a.near(target.b)) continue;
                if (target.overlaps(new Edge(a,b))) return true;
            }
            return false;
        }
    }

    private static boolean close(float a, float b) { return Math.abs(a - b) < EPS; }
}
