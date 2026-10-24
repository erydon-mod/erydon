package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceConnections;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Reworks the baked, editable profile onto a wall edge and its shared end cuts. */
final class CorniceAngledGeometry {
    private static final double EPS = 0.00001;
    private CorniceAngledGeometry() { }

    static List<BakedQuad> fit(List<BakedQuad> source, CorniceConnections.Segment segment) {
        return fit(source, source, segment);
    }

    static List<BakedQuad> fit(List<BakedQuad> source, List<BakedQuad> volumes, CorniceConnections.Segment segment) {
        if (segment.usesProfileCrop()) return CorniceCroppedGeometry.fit(source, volumes, segment);
        List<BakedQuad> result = new ArrayList<>(source.size());
        for (BakedQuad quad : source) {
            int[] original = quad.getVertexData();
            if ((segment.joinedStart() && onEnd(original, 0))
                    || (segment.joinedEnd() && onEnd(original, 1))) continue;
            int[] data = original.clone();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8;
                var point = segment.point(Float.intBitsToFloat(data[offset]), Float.intBitsToFloat(data[offset + 2]));
                data[offset] = Float.floatToRawIntBits((float) point.x());
                data[offset + 2] = Float.floatToRawIntBits((float) point.z());
            }
            // A profile face that varies in height and depth can become nonplanar
            // between two different end cuts. Give both renderers the same triangles.
            if (nonplanar(data)) {
                result.add(quad(quad, triangle(data, 0, 1, 2)));
                result.add(quad(quad, triangle(data, 0, 2, 3)));
            } else {
                result.add(quad(quad, data));
            }
        }
        return List.copyOf(result);
    }

    private static boolean onEnd(int[] data, double end) {
        for (int vertex = 0; vertex < 4; vertex++)
            if (Math.abs(Float.intBitsToFloat(data[vertex * 8]) - end) > EPS) return false;
        return true;
    }

    private static int[] triangle(int[] source, int a, int b, int c) {
        int[] result = new int[32];
        int[] indices = {a, b, c, a};
        for (int vertex = 0; vertex < 4; vertex++)
            System.arraycopy(source, indices[vertex] * 8, result, vertex * 8, 8);
        return result;
    }

    private static boolean nonplanar(int[] data) {
        double[] normal = normal(data);
        return Math.abs(normal[0] * (coordinate(data, 3, 0) - coordinate(data, 0, 0))
                + normal[1] * (coordinate(data, 3, 1) - coordinate(data, 0, 1))
                + normal[2] * (coordinate(data, 3, 2) - coordinate(data, 0, 2))) > EPS;
    }

    private static BakedQuad quad(BakedQuad source, int[] data) {
        double[] normal = normal(data);
        int packed = (Math.round((float) normal[0] * 127) & 255)
                | ((Math.round((float) normal[1] * 127) & 255) << 8)
                | ((Math.round((float) normal[2] * 127) & 255) << 16);
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * 8 + 7;
            data[index] = (data[index] & 0xFF000000) | packed;
        }
        Direction face = Direction.getFacing((float) normal[0], (float) normal[1], (float) normal[2]);
        return new BakedQuad(data, source.getColorIndex(), face, source.getSprite(), source.hasShade());
    }

    private static double[] normal(int[] data) {
        for (int i = 1; i < 3; i++) {
            double ax = coordinate(data, i, 0) - coordinate(data, 0, 0);
            double ay = coordinate(data, i, 1) - coordinate(data, 0, 1);
            double az = coordinate(data, i, 2) - coordinate(data, 0, 2);
            double bx = coordinate(data, i + 1, 0) - coordinate(data, 0, 0);
            double by = coordinate(data, i + 1, 1) - coordinate(data, 0, 1);
            double bz = coordinate(data, i + 1, 2) - coordinate(data, 0, 2);
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1.0e-12) return new double[]{nx / length, ny / length, nz / length};
        }
        return new double[]{0, 1, 0};
    }

    private static double coordinate(int[] data, int vertex, int axis) {
        return Float.intBitsToFloat(data[vertex * 8 + axis]);
    }
}
