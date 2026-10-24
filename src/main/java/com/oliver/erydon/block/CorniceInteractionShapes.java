package com.oliver.erydon.block;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.util.shape.ArrayVoxelShape;
import net.minecraft.util.shape.BitSetVoxelSet;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Cached 1/32-cell outlines follow the same run stretch and profile-depth end cuts as the mesh. */
final class CorniceInteractionShapes {
    private record Key(double[][] boxes, List<CorniceConnections.Segment> segments, Direction outerFacing) { }
    private static final Cache<Key, VoxelShape> CACHE = CacheBuilder.newBuilder().maximumWeight(16 * 1024 * 1024)
            .weigher((Key key, VoxelShape value) -> value instanceof RasterShape raster ? raster.estimatedBytes : 256).build();
    private static final Map<String, double[][]> PROFILE_BOXES = new ConcurrentHashMap<>();
    private CorniceInteractionShapes() { }

    static int estimatedBytes(VoxelShape shape) {
        if (shape instanceof RasterShape raster) return raster.estimatedBytes;
        if (shape instanceof CorniceAssemblyBlock.CombinedShape combined) return combined.estimatedBytes;
        if (shape.isEmpty()) return 128;
        Box bounds = shape.getBoundingBox();
        double cells = (Math.ceil((bounds.maxX - bounds.minX) * 32) + 2) * (Math.ceil((bounds.maxY - bounds.minY) * 32) + 2)
                * (Math.ceil((bounds.maxZ - bounds.minZ) * 32) + 2);
        return (int) Math.min(Integer.MAX_VALUE, 512 + cells / 8 + 80L * shape.getBoundingBoxes().size());
    }

    /** Rolled mouldings and rotated ornaments use their actual authored element envelopes. */
    static double[][] profileBoxes(String style, double[][] existingBoxes) {
        return PROFILE_BOXES.computeIfAbsent(style, ignored -> loadProfileBoxes(style, existingBoxes));
    }

    private static double[][] loadProfileBoxes(String style, double[][] existingBoxes) {
        String resource = "assets/erydon/models/block/cornice/" + style + "/cornice_" + style + "_straight.json";
        var stream = CorniceInteractionShapes.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("Missing authored cornice profile " + resource);
        List<double[]> boxes = new ArrayList<>(List.of(existingBoxes));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject model = JsonParser.parseReader(reader).getAsJsonObject();
            for (var value : model.getAsJsonArray("elements")) {
                JsonObject element = value.getAsJsonObject();
                double[] from = vector(element, "from"), to = vector(element, "to");
                JsonObject rotation = element.has("rotation") ? element.getAsJsonObject("rotation") : null;
                double angle = rotation == null ? 0 : Math.toRadians(rotation.get("angle").getAsDouble());
                String axis = rotation == null ? "y" : rotation.get("axis").getAsString();
                double[] origin = rotation == null ? new double[3] : vector(rotation, "origin");
                boolean rescale = rotation != null && rotation.has("rescale") && rotation.get("rescale").getAsBoolean();
                double cosine = Math.cos(angle), sine = Math.sin(angle);
                double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
                for (int corner = 0; corner < 8; corner++) {
                    double x = ((corner & 1) == 0 ? from[0] : to[0]) - origin[0];
                    double y = ((corner & 2) == 0 ? from[1] : to[1]) - origin[1];
                    double z = ((corner & 4) == 0 ? from[2] : to[2]) - origin[2];
                    double rx = x, ry = y, rz = z;
                    switch (axis) {
                        case "x" -> { ry = y * cosine - z * sine; rz = y * sine + z * cosine; }
                        case "y" -> { rx = x * cosine + z * sine; rz = z * cosine - x * sine; }
                        case "z" -> { rx = x * cosine - y * sine; ry = x * sine + y * cosine; }
                        default -> throw new IllegalStateException("Unknown cornice rotation axis " + axis);
                    }
                    if (rescale && Math.abs(angle) > .000001) {
                        double scale = 1 / Math.cos(Math.abs(angle));
                        if (!axis.equals("x")) rx *= scale;
                        if (!axis.equals("y")) ry *= scale;
                        if (!axis.equals("z")) rz *= scale;
                    }
                    double[] point = {rx + origin[0], ry + origin[1], rz + origin[2]};
                    for (int coordinate = 0; coordinate < 3; coordinate++) {
                        box[coordinate] = Math.min(box[coordinate], point[coordinate]);
                        box[coordinate + 3] = Math.max(box[coordinate + 3], point[coordinate]);
                    }
                }
                if (box[3] - box[0] > .000001 && box[4] - box[1] > .000001 && box[5] - box[2] > .000001
                        && boxes.stream().noneMatch(existing -> contains(existing, box))) boxes.add(box);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read authored cornice profile " + resource, exception);
        }
        return boxes.toArray(double[][]::new);
    }

    private static double[] vector(JsonObject object, String key) {
        var values = object.getAsJsonArray(key);
        return new double[]{values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble()};
    }

    private static boolean contains(double[] outer, double[] inner) {
        for (int axis = 0; axis < 3; axis++) {
            if (inner[axis] < outer[axis] - .000001 || inner[axis + 3] > outer[axis + 3] + .000001) return false;
        }
        return true;
    }

    static VoxelShape shape(BlockState state, List<CorniceConnections.Segment> segments, double[][] boxes) {
        Key key = new Key(boxes, List.copyOf(segments), null);
        VoxelShape cached = CACHE.getIfPresent(key);
        if (cached != null) return cached;
        List<Prism> prisms = new ArrayList<>();
        for (CorniceConnections.Segment segment : segments) for (double[] box : boxes) {
            if (segment.usesProfileCrop()) {
                for (int tile = -2; tile <= 2; tile++) {
                    double shift = tile - segment.sourcePhase();
                    List<CorniceRun.Point> polygon = List.of(new CorniceRun.Point(box[0] / 16 + shift, box[2] / 16),
                            new CorniceRun.Point(box[3] / 16 + shift, box[2] / 16),
                            new CorniceRun.Point(box[3] / 16 + shift, box[5] / 16),
                            new CorniceRun.Point(box[0] / 16 + shift, box[5] / 16));
                    polygon = profileClip(profileClip(polygon, segment, true), segment, false);
                    if (polygon.size() < 3) continue;
                    List<CorniceRun.Point> placed = new ArrayList<>(polygon.size());
                    for (CorniceRun.Point point : polygon) placed.add(segment.profilePoint(point.x(), point.z()));
                    prisms.add(new Prism(List.copyOf(placed), floor(box[1] / 16), ceil(box[4] / 16)));
                }
                continue;
            }
            List<CorniceRun.Point> polygon = List.of(segment.point(box[0] / 16, box[2] / 16),
                    segment.point(box[3] / 16, box[2] / 16), segment.point(box[3] / 16, box[5] / 16),
                    segment.point(box[0] / 16, box[5] / 16));
            prisms.add(new Prism(polygon, floor(box[1] / 16), ceil(box[4] / 16)));
        }
        VoxelShape result = raster(prisms);
        CACHE.put(key, result);
        return result;
    }

    private static List<CorniceRun.Point> profileClip(List<CorniceRun.Point> polygon, CorniceConnections.Segment segment, boolean start) {
        if (polygon.isEmpty()) return polygon;
        List<CorniceRun.Point> output = new ArrayList<>();
        CorniceRun.Cut cut = start ? segment.start() : segment.end();
        CorniceRun.Point a = polygon.get(polygon.size() - 1);
        double da = (a.x() - segment.run().length() * cut.at(a.z())) * (start ? 1 : -1);
        for (CorniceRun.Point b : polygon) {
            double db = (b.x() - segment.run().length() * cut.at(b.z())) * (start ? 1 : -1);
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db), depth = a.z() + (b.z() - a.z()) * t;
                output.add(new CorniceRun.Point(segment.run().length() * cut.at(depth), depth));
            }
            if (db >= 0) output.add(b);
            a = b; da = db;
        }
        return output;
    }

    /** Clip the unchanged authored envelopes at the two actual outer mitres; never stretch ornaments. */
    static VoxelShape outerShape(double[][] boxes, Direction facing) {
        Key key = new Key(boxes, List.of(), facing);
        VoxelShape cached = CACHE.getIfPresent(key);
        if (cached != null) return cached;
        List<Prism> prisms = new ArrayList<>();
        for (double[] box : boxes) {
            List<CorniceRun.Point> polygon = List.of(new CorniceRun.Point(box[0] / 16, box[2] / 16),
                    new CorniceRun.Point(box[3] / 16, box[2] / 16), new CorniceRun.Point(box[3] / 16, box[5] / 16),
                    new CorniceRun.Point(box[0] / 16, box[5] / 16));
            for (int arm = 0; arm < 2; arm++) {
                List<CorniceRun.Point> clipped = outerClip(polygon, arm == 0);
                if (clipped.size() < 3) continue;
                List<CorniceRun.Point> placed = new ArrayList<>();
                for (CorniceRun.Point point : clipped) {
                    double x = arm == 0 ? point.x() : point.z(), z = arm == 0 ? point.z() : 1 - point.x();
                    int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
                    for (int turn = 0; turn < turns; turn++) { double next = 1 - z; z = x; x = next; }
                    placed.add(new CorniceRun.Point(x, z));
                }
                prisms.add(new Prism(List.copyOf(placed), floor(box[1] / 16), ceil(box[4] / 16)));
            }
        }
        VoxelShape result = raster(prisms);
        CACHE.put(key, result);
        return result;
    }

    private static List<CorniceRun.Point> outerClip(List<CorniceRun.Point> polygon, boolean first) {
        List<CorniceRun.Point> result = new ArrayList<>();
        CorniceRun.Point a = polygon.get(polygon.size() - 1);
        double da = first ? a.z() - a.x() : a.x() + a.z() - 1;
        for (CorniceRun.Point b : polygon) {
            double db = first ? b.z() - b.x() : b.x() + b.z() - 1;
            if ((da >= 0) != (db >= 0)) {
                double amount = da / (da - db);
                result.add(new CorniceRun.Point(a.x() + (b.x() - a.x()) * amount, a.z() + (b.z() - a.z()) * amount));
            }
            if (db >= 0) result.add(b);
            a = b; da = db;
        }
        return result;
    }

    private static VoxelShape raster(List<Prism> prisms) {
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (Prism prism : prisms) {
            minY = Math.min(minY, prism.lowY); maxY = Math.max(maxY, prism.highY);
            for (CorniceRun.Point point : prism.polygon) {
                minX = Math.min(minX, floor(point.x())); maxX = Math.max(maxX, ceil(point.x()));
                minZ = Math.min(minZ, floor(point.z())); maxZ = Math.max(maxZ, ceil(point.z()));
            }
        }
        if (prisms.isEmpty()) return VoxelShapes.empty();
        BitSetVoxelSet cells = new BitSetVoxelSet(maxX - minX, maxY - minY, maxZ - minZ);
        for (Prism prism : prisms) {
            int first = prism.polygon.stream().mapToInt(point -> floor(point.z())).min().orElseThrow();
            int last = prism.polygon.stream().mapToInt(point -> ceil(point.z())).max().orElseThrow();
            for (int row = first; row < last; row++) {
                List<CorniceRun.Point> strip = clip(clip(prism.polygon, row / 32.0, true), (row + 1) / 32.0, false);
                if (strip.size() < 3) continue;
                double low = strip.stream().mapToDouble(CorniceRun.Point::x).min().orElseThrow();
                double high = strip.stream().mapToDouble(CorniceRun.Point::x).max().orElseThrow();
                if (high - low <= .000001) continue;
                for (int x = floor(low); x < ceil(high); x++) for (int y = prism.lowY; y < prism.highY; y++)
                    cells.set(x - minX, y - minY, row - minZ);
            }
        }
        // Fill one small uniform grid instead of repeatedly unioning and
        // simplifying thousands of differently spaced authored envelopes.
        return new RasterShape(cells, coordinates(minX, maxX), coordinates(minY, maxY), coordinates(minZ, maxZ));
    }

    private record Prism(List<CorniceRun.Point> polygon, int lowY, int highY) { }
    private static int floor(double coordinate) { return (int) Math.floor(coordinate * 32 + .000001); }
    private static int ceil(double coordinate) { return (int) Math.ceil(coordinate * 32 - .000001); }
    private static double[] coordinates(int first, int last) {
        double[] points = new double[last - first + 1];
        for (int i = 0; i < points.length; i++) points[i] = (first + i) / 32.0;
        return points;
    }
    private static final class RasterShape extends ArrayVoxelShape {
        private final int estimatedBytes;
        private final List<Box> outlineBoxes;
        private RasterShape(VoxelSet cells, double[] x, double[] y, double[] z) {
            super(cells, x, y, z);
            outlineBoxes = List.copyOf(super.getBoundingBoxes());
            estimatedBytes = 512 + 8 * (x.length + y.length + z.length)
                    + ((x.length - 1) * (y.length - 1) * (z.length - 1) + 7) / 8
                    + 80 * outlineBoxes.size();
        }
        @Override public List<Box> getBoundingBoxes() { return outlineBoxes; }
    }

    private static List<CorniceRun.Point> clip(List<CorniceRun.Point> polygon, double boundary, boolean greater) {
        if (polygon.isEmpty()) return polygon;
        List<CorniceRun.Point> result = new ArrayList<>();
        CorniceRun.Point a = polygon.get(polygon.size() - 1);
        double da = a.z() - boundary;
        for (CorniceRun.Point b : polygon) {
            double db = b.z() - boundary;
            boolean ia = greater ? da >= 0 : da <= 0, ib = greater ? db >= 0 : db <= 0;
            if (ia != ib) {
                double t = da / (da - db);
                result.add(new CorniceRun.Point(a.x() + (b.x() - a.x()) * t, boundary));
            }
            if (ib) result.add(b);
            a = b; da = db;
        }
        return result;
    }
}
