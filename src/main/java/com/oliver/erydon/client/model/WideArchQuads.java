package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.WideArchLayout;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Clips enlarged component surfaces into their owning cells, without adding seam faces. */
final class WideArchQuads {
    private WideArchQuads() { }

    static List<BakedQuad> create(BlockState state, BiFunction<BlockState, Direction, List<BakedQuad>> source) {
        var target = state.get(ArchRomanesqueBlock.ARRANGEMENT);
        int width = state.get(ArchRomanesqueBlock.WIDTH);
        Direction facing = state.get(ArchRomanesqueBlock.FACING);
        List<BakedQuad> result = new ArrayList<>();
        for (var component : WideArchLayout.components(target)) {
            BlockState child = state.with(ArchRomanesqueBlock.ARRANGEMENT, component.arrangement())
                    .with(ArchRomanesqueBlock.WIDTH, 3).with(ArchRomanesqueBlock.FACING, Direction.NORTH);
            List<Direction> buckets = new ArrayList<>();
            buckets.add(null);
            buckets.addAll(List.of(Direction.values()));
            for (Direction bucket : buckets) {
                for (BakedQuad quad : source.apply(child, bucket)) {
                    List<Vertex> polygon = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        int[] data = quad.getVertexData();
                        int start = i * 8;
                        polygon.add(new Vertex(
                                (float) WideArchLayout.x(Float.intBitsToFloat(data[start]), component, target, width),
                                (float) WideArchLayout.y(Float.intBitsToFloat(data[start + 1]), component, target, width),
                                Float.intBitsToFloat(data[start + 2]), Float.intBitsToFloat(data[start + 4]),
                                Float.intBitsToFloat(data[start + 5]), data[start + 3], data[start + 6],
                                normal(data[start + 7], WideArchLayout.scaleX(width), WideArchLayout.scaleY(target, width))));
                    }
                    polygon = clip(polygon, 0, 0, true);
                    polygon = clip(polygon, 0, 1, false);
                    polygon = clip(polygon, 1, 0, true);
                    polygon = clip(polygon, 1, 1, false);
                    if (polygon.size() == 4) {
                        add(result, quad, facing, polygon.get(0), polygon.get(1), polygon.get(2), polygon.get(3));
                    } else if (polygon.size() >= 3) {
                        for (int i = 1; i < polygon.size() - 1; i++) {
                            add(result, quad, facing, polygon.get(0), polygon.get(i), polygon.get(i + 1), polygon.get(i + 1));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Vertex> clip(List<Vertex> input, int axis, float boundary, boolean greater) {
        if (input.isEmpty()) return input;
        List<Vertex> output = new ArrayList<>();
        Vertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, axis, boundary, greater);
        for (Vertex current : input) {
            boolean currentInside = inside(current, axis, boundary, greater);
            if (currentInside != previousInside) {
                float t = (boundary - previous.coordinate(axis)) / (current.coordinate(axis) - previous.coordinate(axis));
                output.add(previous.interpolate(current, t));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(Vertex v, int axis, float boundary, boolean greater) {
        return greater ? v.coordinate(axis) >= boundary : v.coordinate(axis) <= boundary;
    }

    private static void add(List<BakedQuad> output, BakedQuad source, Direction facing,
                            Vertex a, Vertex b, Vertex c, Vertex d) {
        // Clipping can leave a line exactly on the neighbouring cell's boundary.
        double area = area(a, b, c) + area(a, c, d);
        if (area < 1.0e-10) return;
        int[] data = new int[32];
        Vertex[] vertices = {a, b, c, d};
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (int i = 0; i < 4; i++) {
            Vertex v = vertices[i];
            float x = v.x, z = v.z;
            int normal = v.normal;
            for (int turn = 0; turn < turns; turn++) {
                float oldX = x;
                x = 1 - z;
                z = oldX;
                int nx = (byte) normal, nz = (byte) (normal >> 16);
                normal = (normal & 0xFF00FF00) | (-nz & 255) | ((nx & 255) << 16);
            }
            int start = i * 8;
            data[start] = Float.floatToRawIntBits(x);
            data[start + 1] = Float.floatToRawIntBits(v.y);
            data[start + 2] = Float.floatToRawIntBits(z);
            data[start + 3] = v.color;
            data[start + 4] = Float.floatToRawIntBits(v.u);
            data[start + 5] = Float.floatToRawIntBits(v.v);
            data[start + 6] = v.light;
            data[start + 7] = normal;
        }
        Direction face = source.getFace();
        for (int i = 0; i < turns; i++) if (face != null && face.getAxis() != Direction.Axis.Y) face = face.rotateYClockwise();
        projectCellUvs(data, source, face);
        output.add(new BakedQuad(data, source.getColorIndex(), face, source.getSprite(), source.hasShade()));
    }

    private static void projectCellUvs(int[] data, BakedQuad source, Direction face) {
        if (source.getSprite() == null || face == null) return;
        float[] uv = new float[8];
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = Float.intBitsToFloat(data[vertex * 8]);
            float y = Float.intBitsToFloat(data[vertex * 8 + 1]);
            float z = Float.intBitsToFloat(data[vertex * 8 + 2]);
            float u = switch (face) { case NORTH -> 1-x; case EAST -> 1-z; case WEST -> z; default -> x; };
            float v = switch (face) { case UP -> z; case DOWN -> 1-z; default -> 1-y; };
            // Authored depth nudges may leave a texture cell. Retain that whole
            // quad's safe interpolated atlas UVs rather than shifting its phase.
            if (u < 0 || u > 1 || v < 0 || v > 1) return;
            uv[vertex * 2] = u;
            uv[vertex * 2 + 1] = v;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            data[vertex * 8 + 4] = Float.floatToRawIntBits(source.getSprite().getFrameU(uv[vertex * 2] * 16));
            data[vertex * 8 + 5] = Float.floatToRawIntBits(source.getSprite().getFrameV(uv[vertex * 2 + 1] * 16));
        }
    }

    private static double area(Vertex a, Vertex b, Vertex c) {
        double ax = b.x-a.x, ay = b.y-a.y, az = b.z-a.z;
        double bx = c.x-a.x, by = c.y-a.y, bz = c.z-a.z;
        double x = ay*bz-az*by, y = az*bx-ax*bz, z = ax*by-ay*bx;
        return Math.sqrt(x*x+y*y+z*z);
    }

    private static int normal(int packed, double scaleX, double scaleY) {
        double x = (byte) packed / scaleX, y = (byte) (packed >> 8) / scaleY, z = (byte) (packed >> 16);
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length == 0) return 0;
        return ((int) Math.round(x/length*127) & 255) | (((int) Math.round(y/length*127) & 255) << 8)
                | (((int) Math.round(z/length*127) & 255) << 16);
    }

    private record Vertex(float x, float y, float z, float u, float v, int color, int light, int normal) {
        float coordinate(int axis) { return axis == 0 ? x : y; }
        Vertex interpolate(Vertex next, float t) {
            return new Vertex(x+(next.x-x)*t, y+(next.y-y)*t, z+(next.z-z)*t,
                    u+(next.u-u)*t, v+(next.v-v)*t, color, light, normal);
        }
    }
}
