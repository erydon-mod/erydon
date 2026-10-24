package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Reciprocal mitres cut from the actual straight profile, without distorting its ornaments. */
final class CorniceOuterCornerGeometry {
    private CorniceOuterCornerGeometry() { }

    /** Common NORTH corner at (0,0), then rotated into the placed facing. */
    static List<BakedQuad> outer(List<BakedQuad> straight, Direction facing) {
        List<BakedQuad> result = new ArrayList<>();
        result.addAll(arm(straight, false, facing));
        result.addAll(arm(straight, true, facing));
        return List.copyOf(result);
    }

    // Package-visible so the regression can independently inspect both sides of the join.
    static List<BakedQuad> arm(List<BakedQuad> straight, boolean second, Direction facing) {
        int turns = switch (facing) {
            case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3;
            default -> throw new IllegalArgumentException("Cornice facing must be horizontal");
        };
        List<BakedQuad> result = new ArrayList<>();
        for (BakedQuad source : straight) {
            int[] packed = source.getVertexData(); int stride = packed.length / 4;
            if (stride < 8) throw new IllegalArgumentException("Incomplete cornice vertex data");
            List<Vertex> polygon = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                int[] data = new int[stride]; System.arraycopy(packed, i * stride, data, 0, stride);
                polygon.add(new Vertex(data, false));
            }
            double[] normal = normal(polygon);
            if (normal == null) continue;
            polygon = clip(polygon, second);
            if (polygon.size() < 3) continue;
            int rotation = Math.floorMod(turns + (second ? 3 : 0), 4);
            Direction face = source.getFace();
            for (int i = 0; i < rotation; i++) if (face.getAxis() != Direction.Axis.Y) face = face.rotateYClockwise();
            double nx = normal[0], nz = normal[2];
            for (int i = 0; i < rotation; i++) { double oldX = nx; nx = -nz; nz = oldX; }
            int fallbackNormal = packedNormal(nx, normal[1], nz);
            List<Vertex> placed = new ArrayList<>(polygon.size());
            for (Vertex vertex : polygon) placed.add(place(vertex, second, turns, fallbackNormal));
            if (placed.size() == 4) add(result, source, face, placed.get(0), placed.get(1), placed.get(2), placed.get(3));
            else for (int i = 1; i + 1 < placed.size(); i++)
                add(result, source, face, placed.get(0), placed.get(i), placed.get(i + 1), placed.get(i + 1));
        }
        return List.copyOf(result);
    }

    private static List<Vertex> clip(List<Vertex> input, boolean second) {
        List<Vertex> output = new ArrayList<>();
        Vertex previous = input.get(input.size() - 1); double previousSide = distance(previous, second);
        for (Vertex current : input) {
            double currentSide = distance(current, second);
            if ((previousSide >= 0) != (currentSide >= 0)) {
                double t = previousSide / (previousSide - currentSide);
                append(output, previous.interpolate(current, t, second));
            }
            if (currentSide >= 0) append(output, currentSide == 0 ? new Vertex(current.data, true) : current);
            previous = current; previousSide = currentSide;
        }
        if (output.size() > 1 && same(output.get(0), output.get(output.size() - 1))) output.remove(output.size() - 1);
        return output;
    }

    private static double distance(Vertex vertex, boolean second) {
        return second ? vertex.value(0) + vertex.value(2) - 1 : vertex.value(2) - vertex.value(0);
    }

    private static void append(List<Vertex> vertices, Vertex vertex) {
        if (vertices.isEmpty() || !same(vertices.get(vertices.size() - 1), vertex)) vertices.add(vertex);
    }

    private static boolean same(Vertex a, Vertex b) {
        return a.value(0) == b.value(0) && a.value(1) == b.value(1) && a.value(2) == b.value(2);
    }

    private static Vertex place(Vertex vertex, boolean second, int turns, int fallbackNormal) {
        int[] data = vertex.data.clone(); double x = vertex.value(0), z = vertex.value(2);
        if (second) { double oldX = x; x = z; z = vertex.seam ? z : 1 - oldX; }
        for (int i = 0; i < turns; i++) { double oldX = x; x = 1 - z; z = oldX; }
        data[0] = Float.floatToRawIntBits((float) x); data[2] = Float.floatToRawIntBits((float) z);
        int normal = data[7] & 0xFFFFFF;
        if (normal == 0) normal = fallbackNormal;
        else for (int i = 0; i < Math.floorMod(turns + (second ? 3 : 0), 4); i++) {
            int nx = (byte) normal, nz = (byte) (normal >> 16);
            normal = (normal & 0x00FF00) | (-nz & 255) | ((nx & 255) << 16);
        }
        data[7] = (data[7] & 0xFF000000) | normal;
        return new Vertex(data, vertex.seam);
    }

    private static void add(List<BakedQuad> output, BakedQuad source, Direction face,
                            Vertex a, Vertex b, Vertex c, Vertex d) {
        if (triangleArea(a, b, c) + triangleArea(a, c, d) <= 1.0e-12) return;
        int stride = a.data.length; int[] data = new int[stride * 4]; Vertex[] vertices = {a, b, c, d};
        for (int i = 0; i < 4; i++) System.arraycopy(vertices[i].data, 0, data, i * stride, stride);
        BakedQuad quad = new BakedQuad(data, source.getColorIndex(), face, source.getSprite(), source.hasShade());
        // The second profile and the placed corner both rotate after baking.
        // Use the same atlas-safe horizontal projection as the existing cornice children.
        output.add(HorizontalUvLock.projectFlatHorizontal(quad));
    }

    private static int packedNormal(double x, double y, double z) {
        return ((int) Math.round(x * 127) & 255) | (((int) Math.round(y * 127) & 255) << 8)
                | (((int) Math.round(z * 127) & 255) << 16);
    }

    private static double[] normal(List<Vertex> polygon) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            Vertex a = polygon.get(0), b = polygon.get(i), c = polygon.get(i + 1);
            double x = b.value(0) - a.value(0), y = b.value(1) - a.value(1), z = b.value(2) - a.value(2);
            double u = c.value(0) - a.value(0), v = c.value(1) - a.value(1), w = c.value(2) - a.value(2);
            double nx = y * w - z * v, ny = z * u - x * w, nz = x * v - y * u;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1.0e-12) return new double[]{nx / length, ny / length, nz / length};
        }
        return null;
    }

    private static double triangleArea(Vertex a, Vertex b, Vertex c) {
        double x = b.value(0) - a.value(0), y = b.value(1) - a.value(1), z = b.value(2) - a.value(2);
        double u = c.value(0) - a.value(0), v = c.value(1) - a.value(1), w = c.value(2) - a.value(2);
        double nx = y * w - z * v, ny = z * u - x * w, nz = x * v - y * u;
        return Math.sqrt(nx * nx + ny * ny + nz * nz) / 2;
    }

    private record Vertex(int[] data, boolean seam) {
        double value(int slot) { return Float.intBitsToFloat(data[slot]); }
        Vertex interpolate(Vertex other, double t, boolean second) {
            int[] result = data.clone();
            for (int slot : new int[]{0, 1, 2, 4, 5}) result[slot] = Float.floatToRawIntBits((float)
                    (value(slot) + (other.value(slot) - value(slot)) * t));
            float depth = Float.intBitsToFloat(result[2]);
            result[0] = Float.floatToRawIntBits(second ? 1 - depth : depth);
            return new Vertex(result, true);
        }
    }
}
