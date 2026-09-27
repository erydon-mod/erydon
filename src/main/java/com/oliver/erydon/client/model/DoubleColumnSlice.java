package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Clips doubled child quads to their local cells before Synapheia processes CTM. */
final class DoubleColumnSlice {
    private static final float EPSILON = 0.00001F;

    private DoubleColumnSlice() {}

    static boolean transform(MutableQuadView quad, int layer, int partX, int partZ,
                             boolean shaft, int pass, int[] passCount, Sprite sprite) {
        Direction face = quad.lightFace();
        List<Vertex> vertices = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) vertices.add(new Vertex(
                quad.x(i) * 2, quad.y(i) * (shaft ? 1 : 2), quad.z(i) * 2,
                quad.u(i), quad.v(i), quad.color(i), quad.lightmap(i),
                quad.hasNormal(i), quad.normalX(i), quad.normalY(i), quad.normalZ(i)));
        vertices = clipToCell(vertices, layer, partX, partZ, shaft);
        int needed = (vertices.size() - 1) / 2;
        passCount[0] = Math.max(passCount[0], needed);
        if (vertices.size() < 3 || pass >= needed) return false;
        vertices = fragment(vertices, pass);
        for (int i = 0; i < 4; i++) {
            Vertex vertex = vertices.get(Math.min(i, vertices.size() - 1));
            quad.pos(i, vertex.x - partX, vertex.y - (shaft ? 0 : layer), vertex.z - partZ);
            quad.uv(i, vertex.u, vertex.v);
            quad.color(i, vertex.color);
            quad.lightmap(i, vertex.lightmap);
            if (vertex.hasNormal) quad.normal(i, vertex.nx, vertex.ny, vertex.nz);
        }
        projectUv(quad, sprite, face);
        // Dense flutes otherwise pick up AO from the invisible 2x2 support cells.
        var renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer != null) quad.material(renderer.materialFinder().copyFrom(quad.material())
                .ambientOcclusion(TriState.FALSE).find());
        quad.cullFace(null);
        return true;
    }

    static List<BakedQuad> bake(BakedQuad quad, int layer, int partX, int partZ, boolean shaft) {
        int[] source = quad.getVertexData();
        List<Vertex> vertices = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int at = i * 8;
            int normal = source[at + 7];
            vertices.add(new Vertex(Float.intBitsToFloat(source[at]) * 2,
                    Float.intBitsToFloat(source[at + 1]) * (shaft ? 1 : 2),
                    Float.intBitsToFloat(source[at + 2]) * 2,
                    Float.intBitsToFloat(source[at + 4]), Float.intBitsToFloat(source[at + 5]),
                    source[at + 3], source[at + 6], normal != 0,
                    (byte) normal / 127F, (byte) (normal >> 8) / 127F,
                    (byte) (normal >> 16) / 127F));
        }
        vertices = clipToCell(vertices, layer, partX, partZ, shaft);
        if (vertices.size() < 3) return List.of();
        List<BakedQuad> result = new ArrayList<>((vertices.size() - 1) / 2);
        for (int pass = 0; pass < (vertices.size() - 1) / 2; pass++)
            result.add(bakeFragment(quad, fragment(vertices, pass), layer, partX, partZ, shaft));
        return result;
    }

    private static BakedQuad bakeFragment(BakedQuad quad, List<Vertex> vertices,
                                          int layer, int partX, int partZ, boolean shaft) {
        int[] result = new int[32];
        for (int i = 0; i < 4; i++) {
            Vertex vertex = vertices.get(Math.min(i, vertices.size() - 1));
            int at = i * 8;
            result[at] = Float.floatToRawIntBits(vertex.x - partX);
            result[at + 1] = Float.floatToRawIntBits(vertex.y - (shaft ? 0 : layer));
            result[at + 2] = Float.floatToRawIntBits(vertex.z - partZ);
            result[at + 3] = vertex.color;
            result[at + 4] = Float.floatToRawIntBits(vertex.u);
            result[at + 5] = Float.floatToRawIntBits(vertex.v);
            result[at + 6] = vertex.lightmap;
            result[at + 7] = vertex.hasNormal ?
                    (Math.round(vertex.nx * 127) & 255)
                            | ((Math.round(vertex.ny * 127) & 255) << 8)
                            | ((Math.round(vertex.nz * 127) & 255) << 16) : 0;
        }
        projectUv(result, quad.getSprite(), quad.getFace());
        return new BakedQuad(result, quad.getColorIndex(), quad.getFace(), quad.getSprite(), quad.hasShade());
    }

    /** Project simple planar faces onto their actual one-block footprint. */
    private static void projectUv(MutableQuadView quad, Sprite sprite, Direction face) {
        if (sprite == null || face == null) return;
        float[] u = new float[4], v = new float[4];
        for (int i = 0; i < 4; i++) {
            if (!project(face, quad.x(i), quad.y(i), quad.z(i), u, v, i)) return;
        }
        if (!planar(face, quad.x(0), quad.y(0), quad.z(0),
                i -> quad.x(i), i -> quad.y(i), i -> quad.z(i))) return;
        for (int i = 0; i < 4; i++) quad.uv(i, sprite.getFrameU(u[i]), sprite.getFrameV(v[i]));
    }

    private static void projectUv(int[] data, Sprite sprite, Direction face) {
        if (sprite == null || face == null) return;
        float[] x = new float[4], y = new float[4], z = new float[4];
        float[] u = new float[4], v = new float[4];
        for (int i = 0; i < 4; i++) {
            int at = i * 8;
            x[i] = Float.intBitsToFloat(data[at]);
            y[i] = Float.intBitsToFloat(data[at + 1]);
            z[i] = Float.intBitsToFloat(data[at + 2]);
            if (!project(face, x[i], y[i], z[i], u, v, i)) return;
        }
        if (!planar(face, x[0], y[0], z[0], i -> x[i], i -> y[i], i -> z[i])) return;
        for (int i = 0; i < 4; i++) {
            data[i * 8 + 4] = Float.floatToRawIntBits(sprite.getFrameU(u[i]));
            data[i * 8 + 5] = Float.floatToRawIntBits(sprite.getFrameV(v[i]));
        }
    }

    private static boolean project(Direction face, float x, float y, float z,
                                   float[] u, float[] v, int i) {
        float projectedU = switch (face) {
            case NORTH -> 1 - x;
            case SOUTH, UP, DOWN -> x;
            case WEST -> z;
            case EAST -> 1 - z;
        };
        float projectedV = switch (face) {
            case UP -> z;
            case DOWN -> 1 - z;
            default -> 1 - y;
        };
        if (projectedU < -EPSILON || projectedU > 1 + EPSILON
                || projectedV < -EPSILON || projectedV > 1 + EPSILON) return false;
        u[i] = Math.max(0, Math.min(1, projectedU)) * 16;
        v[i] = Math.max(0, Math.min(1, projectedV)) * 16;
        return true;
    }

    private static boolean planar(Direction face, float x, float y, float z,
                                  java.util.function.IntToDoubleFunction xs,
                                  java.util.function.IntToDoubleFunction ys,
                                  java.util.function.IntToDoubleFunction zs) {
        for (int i = 1; i < 4; i++) {
            float difference = switch (face.getAxis()) {
                case X -> (float) xs.applyAsDouble(i) - x;
                case Y -> (float) ys.applyAsDouble(i) - y;
                case Z -> (float) zs.applyAsDouble(i) - z;
            };
            if (Math.abs(difference) > EPSILON) return false;
        }
        return true;
    }

    private static List<Vertex> fragment(List<Vertex> polygon, int pass) {
        int first = pass * 2 + 1;
        if (first + 2 >= polygon.size()) return List.of(
                polygon.get(0), polygon.get(first), polygon.get(first + 1));
        return List.of(polygon.get(0), polygon.get(first),
                polygon.get(first + 1), polygon.get(first + 2));
    }

    private static List<Vertex> clipToCell(List<Vertex> source, int layer,
                                            int partX, int partZ, boolean shaft) {
        // Faces exactly on an internal boundary belong to just one cell.
        if (onPlane(source, 0, 1) && partX == 0
                || onPlane(source, 2, 1) && partZ == 0
                || !shaft && onPlane(source, 1, 1) && layer == 1) return List.of();
        List<Vertex> vertices = source;
        vertices = clip(vertices, 0, partX, true);
        vertices = clip(vertices, 0, partX + 1, false);
        vertices = clip(vertices, 2, partZ, true);
        vertices = clip(vertices, 2, partZ + 1, false);
        if (!shaft) {
            vertices = clip(vertices, 1, layer, true);
            vertices = clip(vertices, 1, layer + 1, false);
        }
        return hasArea(vertices) ? vertices : List.of();
    }

    private static boolean onPlane(List<Vertex> vertices, int axis, float at) {
        return vertices.stream().allMatch(v -> Math.abs(v.coordinate(axis) - at) < EPSILON);
    }

    private static List<Vertex> clip(List<Vertex> input, int axis, float bound, boolean lower) {
        if (input.isEmpty()) return input;
        List<Vertex> output = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Vertex a = input.get(i), b = input.get((i + 1) % input.size());
            float av = a.coordinate(axis), bv = b.coordinate(axis);
            boolean aInside = lower ? av >= bound - EPSILON : av <= bound + EPSILON;
            boolean bInside = lower ? bv >= bound - EPSILON : bv <= bound + EPSILON;
            if (aInside) output.add(a);
            if (aInside != bInside) {
                float t = (bound - av) / (bv - av);
                output.add(Vertex.lerp(a, b, t));
            }
        }
        for (int i = output.size() - 1; i > 0; i--)
            if (output.get(i).samePosition(output.get(i - 1))) output.remove(i);
        if (output.size() > 1 && output.get(0).samePosition(output.get(output.size() - 1)))
            output.remove(output.size() - 1);
        return output;
    }

    private static boolean hasArea(List<Vertex> vertices) {
        if (vertices.size() < 3) return false;
        Vertex a = vertices.get(0);
        for (int i = 1; i + 1 < vertices.size(); i++) {
            Vertex b = vertices.get(i), c = vertices.get(i + 1);
            float ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
            float vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
            float crossX = uy * vz - uz * vy;
            float crossY = uz * vx - ux * vz;
            float crossZ = ux * vy - uy * vx;
            if (crossX * crossX + crossY * crossY + crossZ * crossZ > EPSILON * EPSILON)
                return true;
        }
        return false;
    }

    private record Vertex(float x, float y, float z, float u, float v, int color,
                          int lightmap, boolean hasNormal, float nx, float ny, float nz) {
        float coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
        boolean samePosition(Vertex other) {
            return Math.abs(x - other.x) < EPSILON && Math.abs(y - other.y) < EPSILON
                    && Math.abs(z - other.z) < EPSILON;
        }
        static Vertex lerp(Vertex a, Vertex b, float t) {
            return new Vertex(mix(a.x, b.x, t), mix(a.y, b.y, t), mix(a.z, b.z, t),
                    mix(a.u, b.u, t), mix(a.v, b.v, t), a.color, a.lightmap,
                    a.hasNormal, mix(a.nx, b.nx, t), mix(a.ny, b.ny, t), mix(a.nz, b.nz, t));
        }
        private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    }
}
