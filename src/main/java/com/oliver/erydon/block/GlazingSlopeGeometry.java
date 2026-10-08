package com.oliver.erydon.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.AffineTransformations;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.ArrayVoxelShape;
import net.minecraft.util.shape.BitSetVoxelSet;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/** Shared authored glazing surfaces and their conservative, bounded-grid collision shapes. */
public final class GlazingSlopeGeometry {
    public enum Profile {
        STANDARD("slope/glazing_framed_slope", true),
        SHALLOW_LOWER("shallow_slope/glazing_slope_shallow_lower_straight", false),
        SHALLOW_UPPER("shallow_slope/glazing_slope_shallow_upper_straight", false);

        private final String model;
        private final boolean flipX;

        Profile(String model, boolean flipX) {
            this.model = model;
            this.flipX = flipX;
        }
    }

    public record Vertex(double x, double y, double z, double u, double v) {
        private Vertex blend(Vertex other, double t) {
            return new Vertex(x + (other.x - x) * t, y + (other.y - y) * t,
                    z + (other.z - z) * t, u + (other.u - u) * t, v + (other.v - v) * t);
        }
    }

    public record Face(List<Vertex> vertices, String texture) {
        public Face {
            vertices = List.copyOf(vertices);
            if (vertices.size() < 3) throw new IllegalArgumentException("A glazing face needs three vertices");
            if (!texture.equals("glass") && !texture.equals("lead")) {
                throw new IllegalArgumentException("Unknown glazing texture: " + texture);
            }
        }
    }

    /** Reload-local client templates have their own small pose cache; native server templates stay shared. */
    public static final class Template {
        private final List<SourceFace> source;
        private final double creaseWidth;
        private final Map<Pose, List<Face>> faces = new ConcurrentHashMap<>();

        private Template(List<SourceFace> source, double creaseWidth) {
            this.source = List.copyOf(source);
            this.creaseWidth = creaseWidth;
        }
    }

    private record SourceFace(List<Vertex> vertices, String texture, Direction direction,
                              float[] uv, int rotation) { }
    private record Pose(Direction facing, String shape, boolean top) { }
    private record ShapeKey(Profile profile, Pose pose) { }
    private record Plane(double x, double z, double c) {
        double height(double px, double pz) { return x * px + z * pz + c; }
        Plane minus(Plane other) { return new Plane(x - other.x, z - other.z, c - other.c); }
    }
    private record Column(int bottom, int top) {
        Column include(int low, int high) { return new Column(Math.min(bottom, low), Math.max(top, high)); }
    }

    private static final double EPSILON = 1.0e-9;
    private static final int HORIZONTAL_GRID = 32;
    private static final int VERTICAL_GRID = 128;
    private static final Map<ShapeKey, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    private GlazingSlopeGeometry() { }

    private static final class NativeTemplates {
        private static final Map<Profile, Template> VALUES = Map.of(
                Profile.STANDARD, load(Profile.STANDARD),
                Profile.SHALLOW_LOWER, load(Profile.SHALLOW_LOWER),
                Profile.SHALLOW_UPPER, load(Profile.SHALLOW_UPPER));
    }

    public static List<Face> faces(Profile profile, Direction facing, String shape, BlockHalf half) {
        return faces(NativeTemplates.VALUES.get(profile), facing, shape, half, profile.flipX);
    }

    public static VoxelShape shape(Profile profile, Direction facing, String shape, BlockHalf half) {
        Pose pose = pose(facing, shape, half, profile.flipX);
        return SHAPES.computeIfAbsent(new ShapeKey(profile, pose), ignored ->
                voxelize(faces(profile, facing, shape, half)));
    }

    /** A true flipX applies the published X=180 blockstate transform to STANDARD top states. */
    public static List<Face> faces(Template template, Direction facing, String shape,
                                   BlockHalf half, boolean flipX) {
        Pose pose = pose(facing, shape, half, flipX);
        return template.faces.computeIfAbsent(pose, ignored -> build(template, pose));
    }

    public static Template parse(JsonObject model) {
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null || elements.isEmpty()) throw new IllegalArgumentException("Empty glazing template");
        List<SourceFace> source = new ArrayList<>();
        double creaseWidth = 1.0 / 16.0;
        for (var value : elements) {
            JsonObject element = value.getAsJsonObject();
            double[] from = vector(element.getAsJsonArray("from"));
            double[] to = vector(element.getAsJsonArray("to"));
            JsonObject rotation = element.has("rotation") ? element.getAsJsonObject("rotation") : null;
            for (var entry : element.getAsJsonObject("faces").entrySet()) {
                Direction direction = Objects.requireNonNull(Direction.byName(entry.getKey()));
                JsonObject face = entry.getValue().getAsJsonObject();
                String texture = face.get("texture").getAsString().replace("#", "");
                float[] uv = face.has("uv") ? uv(face.getAsJsonArray("uv")) : defaultUv(direction, from, to);
                int turns = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
                List<Vertex> vertices = new ArrayList<>(4);
                for (double[] point : corners(direction, from, to)) {
                    double[] rotated = rotateElement(point, rotation);
                    vertices.add(new Vertex(rotated[0] / 16, rotated[1] / 16, rotated[2] / 16, 0, 0));
                }
                source.add(new SourceFace(List.copyOf(vertices), texture, direction, uv, turns));
                if (texture.equals("glass")) creaseWidth = Math.max(EPSILON, from[0] / 16.0);
            }
        }
        if (source.stream().filter(face -> face.texture.equals("glass")).count() < 2) {
            throw new IllegalArgumentException("Glazing template needs both glass surfaces");
        }
        return new Template(source, creaseWidth);
    }

    private static Template load(Profile profile) {
        String resource = "/assets/erydon/models/block/glazing/" + profile.model + ".json";
        try (var stream = GlazingSlopeGeometry.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing native glazing template: " + resource);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read native glazing template: " + resource, exception);
        }
    }

    private static Pose pose(Direction facing, String shape, BlockHalf half, boolean flipX) {
        if (!facing.getAxis().isHorizontal()) throw new IllegalArgumentException("Glazing facing must be horizontal");
        if (!List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right").contains(shape)) {
            throw new IllegalArgumentException("Unknown glazing shape: " + shape);
        }
        return new Pose(facing, shape, flipX && half == BlockHalf.TOP);
    }

    private static List<Face> build(Template template, Pose pose) {
        List<Face> own = oriented(template, pose.facing, pose.top);
        if (pose.shape.equals("straight")) return List.copyOf(own);
        Direction otherFacing = pose.shape.endsWith("left")
                ? pose.facing.rotateYCounterclockwise() : pose.facing.rotateYClockwise();
        List<Face> other = oriented(template, otherFacing, pose.top);
        Plane difference = centrePlane(own).minus(centrePlane(other));
        double length = Math.hypot(difference.x, difference.z);
        if (length < EPSILON) throw new IllegalArgumentException("Glazing corner planes are parallel");
        Plane bisector = new Plane(difference.x / length, difference.z / length, difference.c / length);
        boolean inner = pose.shape.startsWith("inner");
        List<Face> result = new ArrayList<>();
        addCornerHalf(result, own, bisector, inner ? -1 : 1, template.creaseWidth);
        addCornerHalf(result, other, bisector, inner ? 1 : -1, template.creaseWidth);
        return List.copyOf(result);
    }

    private static void addCornerHalf(List<Face> result, List<Face> source, Plane bisector,
                                      int side, double width) {
        ToDoubleFunction<Vertex> distance = vertex -> side * bisector.height(vertex.x, vertex.z);
        double halfWidth = width / 2;
        for (Face face : source) {
            List<Vertex> polygon = clip(face.vertices, distance, 0, false);
            if (polygon.size() < 3) continue;
            if (!face.texture.equals("glass")) {
                add(result, polygon, "lead");
                continue;
            }
            // The crease bar replaces glass, so transparent glass never lies behind its coplanar lead.
            add(result, clip(polygon, distance, halfWidth, false), "glass");
            List<Vertex> bar = clip(polygon, distance, halfWidth, true);
            if (bar.size() >= 3) {
                List<Vertex> mapped = bar.stream().map(vertex -> {
                    double across = bisector.height(vertex.x, vertex.z);
                    double along = -bisector.z * (vertex.x - .5) + bisector.x * (vertex.z - .5);
                    return new Vertex(vertex.x, vertex.y, vertex.z,
                            Math.max(0, Math.min(1, across / width + .5)),
                            Math.max(0, Math.min(16, (along / Math.sqrt(2) + .5) * 16)));
                }).toList();
                add(result, mapped, "lead");
            }
        }
    }

    private static List<Face> oriented(Template template, Direction facing, boolean top) {
        int steps = steps(facing);
        var affine = new AffineTransformation(null,
                new Quaternionf().rotateYXZ(-steps * 90 * 0.017453292F,
                        top ? -180 * 0.017453292F : 0, 0), null, null);
        List<Face> result = new ArrayList<>(template.source.size());
        for (SourceFace source : template.source) {
            float[] locked = lockedUvs(source, affine);
            List<Vertex> vertices = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                Vertex vertex = source.vertices.get(index);
                double x = vertex.x, y = top ? 1 - vertex.y : vertex.y;
                double z = top ? 1 - vertex.z : vertex.z;
                for (int turn = 0; turn < steps; turn++) {
                    double nextX = 1 - z;
                    z = x;
                    x = nextX;
                }
                vertices.add(new Vertex(x, y, z, locked[index * 2], locked[index * 2 + 1]));
            }
            result.add(new Face(vertices, source.texture));
        }
        return result;
    }

    /** Vanilla's uvlock calculation, using common math classes rather than client model classes. */
    private static float[] lockedUvs(SourceFace face, AffineTransformation affine) {
        Matrix4f matrix = AffineTransformations.uvLock(affine, face.direction,
                () -> "Cannot lock glazing texture coordinates").getMatrix();
        Vector4f low = matrix.transform(new Vector4f(face.uv[0] / 16, face.uv[1] / 16, 0, 1));
        Vector4f high = matrix.transform(new Vector4f(face.uv[2] / 16, face.uv[3] / 16, 0, 1));
        float u0 = low.x() * 16, v0 = low.y() * 16, u1 = high.x() * 16, v1 = high.y() * 16;
        if (Math.signum(face.uv[2] - face.uv[0]) != Math.signum(u1 - u0)) {
            float swap = u0; u0 = u1; u1 = swap;
        }
        if (Math.signum(face.uv[3] - face.uv[1]) != Math.signum(v1 - v0)) {
            float swap = v0; v0 = v1; v1 = swap;
        }
        float angle = (float) Math.toRadians(face.rotation);
        Vector3f rotated = new Matrix3f(matrix).transform(new Vector3f(MathHelper.cos(angle), MathHelper.sin(angle), 0));
        int turns = Math.floorMod(-(int) Math.round(Math.toDegrees(Math.atan2(rotated.y(), rotated.x())) / 90), 4);
        float[] result = new float[8];
        for (int vertex = 0; vertex < 4; vertex++) {
            int uvIndex = (vertex + turns) & 3;
            result[vertex * 2] = uvIndex == 0 || uvIndex == 1 ? u0 : u1;
            result[vertex * 2 + 1] = uvIndex == 0 || uvIndex == 3 ? v0 : v1;
        }
        return result;
    }

    private static Plane centrePlane(List<Face> faces) {
        List<Plane> planes = new ArrayList<>(2);
        for (Face face : faces) if (face.texture.equals("glass")) {
            Vertex a = face.vertices.get(0), b = face.vertices.get(1), c = face.vertices.get(2);
            double ax = b.x - a.x, ay = b.y - a.y, az = b.z - a.z;
            double bx = c.x - a.x, by = c.y - a.y, bz = c.z - a.z;
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            if (Math.abs(ny) > EPSILON) planes.add(new Plane(-nx / ny, -nz / ny,
                    a.y + nx / ny * a.x + nz / ny * a.z));
        }
        if (planes.size() != 2) throw new IllegalArgumentException("Glazing template needs two planar glass faces");
        return new Plane((planes.get(0).x + planes.get(1).x) / 2,
                (planes.get(0).z + planes.get(1).z) / 2, (planes.get(0).c + planes.get(1).c) / 2);
    }

    private static void add(List<Face> result, List<Vertex> polygon, String texture) {
        if (polygon.size() < 3 || areaSquared(polygon) < EPSILON * EPSILON) return;
        result.add(new Face(polygon, texture));
    }

    private static double areaSquared(List<Vertex> polygon) {
        double x = 0, y = 0, z = 0;
        for (int index = 0; index < polygon.size(); index++) {
            Vertex a = polygon.get(index), b = polygon.get((index + 1) % polygon.size());
            x += (a.y - b.y) * (a.z + b.z);
            y += (a.z - b.z) * (a.x + b.x);
            z += (a.x - b.x) * (a.y + b.y);
        }
        return x * x + y * y + z * z;
    }

    private static List<Vertex> clip(List<Vertex> polygon, ToDoubleFunction<Vertex> distance,
                                      double boundary, boolean less) {
        if (polygon.isEmpty()) return List.of();
        List<Vertex> result = new ArrayList<>();
        Vertex previous = polygon.get(polygon.size() - 1);
        double previousDistance = distance.applyAsDouble(previous) - boundary;
        boolean previousInside = less ? previousDistance <= EPSILON : previousDistance >= -EPSILON;
        for (Vertex current : polygon) {
            double currentDistance = distance.applyAsDouble(current) - boundary;
            boolean inside = less ? currentDistance <= EPSILON : currentDistance >= -EPSILON;
            if (inside != previousInside) {
                appendDistinct(result, previous.blend(current, previousDistance / (previousDistance - currentDistance)));
            }
            if (inside) appendDistinct(result, current);
            previous = current;
            previousDistance = currentDistance;
            previousInside = inside;
        }
        if (result.size() > 1 && samePoint(result.get(0), result.get(result.size() - 1))) result.remove(result.size() - 1);
        return result;
    }

    private static void appendDistinct(List<Vertex> result, Vertex vertex) {
        if (result.isEmpty() || !samePoint(result.get(result.size() - 1), vertex)) result.add(vertex);
    }

    private static boolean samePoint(Vertex a, Vertex b) {
        return Math.abs(a.x - b.x) < EPSILON && Math.abs(a.y - b.y) < EPSILON && Math.abs(a.z - b.z) < EPSILON;
    }

    private static VoxelShape voxelize(List<Face> faces) {
        Map<Long, Column> columns = new java.util.HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Face face : faces) {
            double left = face.vertices.stream().mapToDouble(Vertex::x).min().orElseThrow();
            double right = face.vertices.stream().mapToDouble(Vertex::x).max().orElseThrow();
            double front = face.vertices.stream().mapToDouble(Vertex::z).min().orElseThrow();
            double back = face.vertices.stream().mapToDouble(Vertex::z).max().orElseThrow();
            int x0 = (int) Math.floor(left * HORIZONTAL_GRID + EPSILON);
            int x1 = Math.max(x0, (int) Math.ceil(right * HORIZONTAL_GRID - EPSILON) - 1);
            int z0 = (int) Math.floor(front * HORIZONTAL_GRID + EPSILON);
            int z1 = Math.max(z0, (int) Math.ceil(back * HORIZONTAL_GRID - EPSILON) - 1);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                List<Vertex> cell = clip(face.vertices, Vertex::x, x / (double) HORIZONTAL_GRID, false);
                cell = clip(cell, Vertex::x, (x + 1) / (double) HORIZONTAL_GRID, true);
                cell = clip(cell, Vertex::z, z / (double) HORIZONTAL_GRID, false);
                cell = clip(cell, Vertex::z, (z + 1) / (double) HORIZONTAL_GRID, true);
                if (cell.isEmpty()) continue;
                int bottom = (int) Math.floor(cell.stream().mapToDouble(Vertex::y).min().orElseThrow() * VERTICAL_GRID + EPSILON);
                int top = (int) Math.ceil(cell.stream().mapToDouble(Vertex::y).max().orElseThrow() * VERTICAL_GRID - EPSILON);
                if (top <= bottom) top = bottom + 1;
                long key = ((long) x << 32) | (z & 0xffffffffL);
                Column previous = columns.get(key);
                columns.put(key, previous == null ? new Column(bottom, top) : previous.include(bottom, top));
                minX = Math.min(minX, x); maxX = Math.max(maxX, x + 1);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z + 1);
                minY = Math.min(minY, bottom); maxY = Math.max(maxY, top);
            }
        }
        if (columns.isEmpty()) return VoxelShapes.empty();
        BitSetVoxelSet voxels = new BitSetVoxelSet(maxX - minX, maxY - minY, maxZ - minZ);
        for (var entry : columns.entrySet()) {
            int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
            for (int y = entry.getValue().bottom; y < entry.getValue().top; y++) voxels.set(x - minX, y - minY, z - minZ);
        }
        return new GridShape(voxels, points(minX, maxX, HORIZONTAL_GRID),
                points(minY, maxY, VERTICAL_GRID), points(minZ, maxZ, HORIZONTAL_GRID));
    }

    private static final class GridShape extends ArrayVoxelShape {
        private final List<Box> boundingBoxes;

        private GridShape(BitSetVoxelSet voxels, double[] x, double[] y, double[] z) {
            super(voxels, x, y, z);
            boundingBoxes = List.copyOf(super.getBoundingBoxes());
        }

        @Override public List<Box> getBoundingBoxes() {
            // Geometry is immutable; callers still receive the mutable list that vanilla promises.
            return new ArrayList<>(boundingBoxes);
        }
    }

    private static double[] points(int from, int to, int resolution) {
        double[] result = new double[to - from + 1];
        for (int index = 0; index < result.length; index++) result[index] = (from + index) / (double) resolution;
        return result;
    }

    private static int steps(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> throw new IllegalArgumentException("Glazing facing must be horizontal");
        };
    }

    private static double[] vector(JsonArray values) {
        return new double[]{values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble()};
    }

    private static float[] uv(JsonArray values) {
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                values.get(2).getAsFloat(), values.get(3).getAsFloat()};
    }

    private static float[] defaultUv(Direction face, double[] from, double[] to) {
        return switch (face) {
            case DOWN -> new float[]{(float) from[0], (float) (16 - to[2]), (float) to[0], (float) (16 - from[2])};
            case UP -> new float[]{(float) from[0], (float) from[2], (float) to[0], (float) to[2]};
            case NORTH -> new float[]{(float) (16 - to[0]), (float) (16 - to[1]), (float) (16 - from[0]), (float) (16 - from[1])};
            case SOUTH -> new float[]{(float) from[0], (float) (16 - to[1]), (float) to[0], (float) (16 - from[1])};
            case WEST -> new float[]{(float) from[2], (float) (16 - to[1]), (float) to[2], (float) (16 - from[1])};
            case EAST -> new float[]{(float) (16 - to[2]), (float) (16 - to[1]), (float) (16 - from[2]), (float) (16 - from[1])};
        };
    }

    private static double[][] corners(Direction face, double[] from, double[] to) {
        double x0 = from[0], y0 = from[1], z0 = from[2], x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case DOWN -> new double[][]{{x0,y0,z1},{x0,y0,z0},{x1,y0,z0},{x1,y0,z1}};
            case UP -> new double[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}};
            case NORTH -> new double[][]{{x1,y1,z0},{x1,y0,z0},{x0,y0,z0},{x0,y1,z0}};
            case SOUTH -> new double[][]{{x0,y1,z1},{x0,y0,z1},{x1,y0,z1},{x1,y1,z1}};
            case WEST -> new double[][]{{x0,y1,z0},{x0,y0,z0},{x0,y0,z1},{x0,y1,z1}};
            case EAST -> new double[][]{{x1,y1,z1},{x1,y0,z1},{x1,y0,z0},{x1,y1,z0}};
        };
    }

    private static double[] rotateElement(double[] point, JsonObject rotation) {
        if (rotation == null) return point;
        double[] origin = vector(rotation.getAsJsonArray("origin"));
        double x = point[0] - origin[0], y = point[1] - origin[1], z = point[2] - origin[2];
        double angle = Math.toRadians(rotation.get("angle").getAsDouble());
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        String axis = rotation.get("axis").getAsString();
        double scale = rotation.has("rescale") && rotation.get("rescale").getAsBoolean() ? 1 / cosine : 1;
        return switch (axis) {
            case "x" -> new double[]{x + origin[0], (y * cosine - z * sine) * scale + origin[1],
                    (y * sine + z * cosine) * scale + origin[2]};
            case "y" -> new double[]{(x * cosine + z * sine) * scale + origin[0], y + origin[1],
                    (-x * sine + z * cosine) * scale + origin[2]};
            case "z" -> new double[]{(x * cosine - y * sine) * scale + origin[0],
                    (x * sine + y * cosine) * scale + origin[1], z + origin[2]};
            default -> throw new IllegalArgumentException("Unknown glazing rotation axis: " + axis);
        };
    }
}
