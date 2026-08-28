package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a baked spiral-stair face into world texture cells without moving its geometry.
 * The physical stair deliberately extends beyond its owning block, so a single repeat-CTM
 * sprite cannot cover every face correctly.
 */
final class SpiralStairCtmGeometry {
    static final float CELL_SNAP_EPSILON = 0.001F;
    static final float RANGE_EPSILON = 0.000001F;
    private static final double AREA_EPSILON = 0.000000001D;

    private SpiralStairCtmGeometry() {
    }

    static List<Fragment> split(Direction face, List<Vertex> vertices) {
        if (face == null || vertices.size() < 3) {
            return List.of();
        }

        List<ProjectedVertex> source = new ArrayList<>(vertices.size());
        float minS = Float.POSITIVE_INFINITY;
        float maxS = Float.NEGATIVE_INFINITY;
        float minT = Float.POSITIVE_INFINITY;
        float maxT = Float.NEGATIVE_INFINITY;

        for (Vertex vertex : vertices) {
            float s = snapToCell(textureS(face, vertex.x, vertex.y, vertex.z));
            float t = snapToCell(textureT(face, vertex.x, vertex.y, vertex.z));
            source.add(new ProjectedVertex(vertex, s, t));
            minS = Math.min(minS, s);
            maxS = Math.max(maxS, s);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }

        int minCellS = floorCell(minS);
        int maxCellS = floorCell(maxS - RANGE_EPSILON);
        int minCellT = floorCell(minT);
        int maxCellT = floorCell(maxT - RANGE_EPSILON);
        if (maxCellS < minCellS) {
            maxCellS = minCellS;
        }
        if (maxCellT < minCellT) {
            maxCellT = minCellT;
        }

        List<Fragment> fragments = new ArrayList<>();
        for (int cellS = minCellS; cellS <= maxCellS; cellS++) {
            for (int cellT = minCellT; cellT <= maxCellT; cellT++) {
                List<ProjectedVertex> clipped = clip(source, Axis.S, cellS, true);
                clipped = clip(clipped, Axis.S, cellS + 1.0F, false);
                clipped = clip(clipped, Axis.T, cellT, true);
                clipped = clip(clipped, Axis.T, cellT + 1.0F, false);
                clipped = removeDuplicateVertices(clipped);
                if (clipped.size() < 3 || projectedArea(clipped) <= AREA_EPSILON) {
                    continue;
                }

                List<CellVertex> cellVertices = new ArrayList<>(clipped.size());
                for (ProjectedVertex vertex : clipped) {
                    float localS = clamp01(vertex.s - cellS);
                    float localT = clamp01(vertex.t - cellT);
                    cellVertices.add(new CellVertex(vertex.vertex, localS, localT));
                }
                fragments.add(new Fragment(cellS, cellT, List.copyOf(cellVertices)));
            }
        }
        return List.copyOf(fragments);
    }

    static float u(Direction face, CellVertex vertex) {
        return u(face, vertex.localS);
    }

    static float v(Direction face, CellVertex vertex) {
        return v(face, vertex.localT);
    }

    static float u(Direction face, float localS) {
        return switch (face) {
            case NORTH, EAST -> 1.0F - localS;
            default -> localS;
        };
    }

    static float v(Direction face, float localT) {
        return face == Direction.UP ? localT : 1.0F - localT;
    }

    static int offsetX(Direction face, Fragment fragment) {
        return offsetX(face, fragment.cellS, fragment.cellT);
    }

    static int offsetX(Direction face, int cellS, int cellT) {
        return switch (face) {
            case UP, DOWN, NORTH, SOUTH -> cellS;
            default -> 0;
        };
    }

    static int offsetY(Direction face, Fragment fragment) {
        return offsetY(face, fragment.cellS, fragment.cellT);
    }

    static int offsetY(Direction face, int cellS, int cellT) {
        return switch (face) {
            case NORTH, SOUTH, EAST, WEST -> cellT;
            default -> 0;
        };
    }

    static int offsetZ(Direction face, Fragment fragment) {
        return offsetZ(face, fragment.cellS, fragment.cellT);
    }

    static int offsetZ(Direction face, int cellS, int cellT) {
        return switch (face) {
            case UP, DOWN -> cellT;
            case EAST, WEST -> cellS;
            default -> 0;
        };
    }

    private static List<ProjectedVertex> clip(List<ProjectedVertex> input,
                                              Axis axis,
                                              float boundary,
                                              boolean keepGreater) {
        if (input.isEmpty()) {
            return input;
        }

        List<ProjectedVertex> output = new ArrayList<>(input.size() + 2);
        ProjectedVertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, axis, boundary, keepGreater);

        for (ProjectedVertex current : input) {
            boolean currentInside = inside(current, axis, boundary, keepGreater);
            if (currentInside != previousInside) {
                output.add(intersection(previous, current, axis, boundary));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(ProjectedVertex vertex,
                                  Axis axis,
                                  float boundary,
                                  boolean keepGreater) {
        float value = axis == Axis.S ? vertex.s : vertex.t;
        return keepGreater ? value >= boundary : value <= boundary;
    }

    private static ProjectedVertex intersection(ProjectedVertex from,
                                                ProjectedVertex to,
                                                Axis axis,
                                                float boundary) {
        float fromValue = axis == Axis.S ? from.s : from.t;
        float toValue = axis == Axis.S ? to.s : to.t;
        float amount = (boundary - fromValue) / (toValue - fromValue);
        amount = clamp01(amount);
        Vertex vertex = Vertex.interpolate(from.vertex, to.vertex, amount);
        float s = lerp(from.s, to.s, amount);
        float t = lerp(from.t, to.t, amount);
        if (axis == Axis.S) {
            s = boundary;
        } else {
            t = boundary;
        }
        return new ProjectedVertex(vertex, s, t);
    }

    private static List<ProjectedVertex> removeDuplicateVertices(List<ProjectedVertex> vertices) {
        if (vertices.size() < 2) {
            return vertices;
        }
        List<ProjectedVertex> result = new ArrayList<>(vertices.size());
        for (ProjectedVertex vertex : vertices) {
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), vertex)) {
                result.add(vertex);
            }
        }
        if (result.size() > 1 && samePoint(result.get(0), result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static boolean samePoint(ProjectedVertex first, ProjectedVertex second) {
        return Math.abs(first.s - second.s) <= RANGE_EPSILON
                && Math.abs(first.t - second.t) <= RANGE_EPSILON;
    }

    private static double projectedArea(List<ProjectedVertex> vertices) {
        double twiceArea = 0.0D;
        for (int index = 0; index < vertices.size(); index++) {
            ProjectedVertex current = vertices.get(index);
            ProjectedVertex next = vertices.get((index + 1) % vertices.size());
            twiceArea += (double) current.s * next.t - (double) next.s * current.t;
        }
        return Math.abs(twiceArea) * 0.5D;
    }

    static float textureS(Direction face, float x, float y, float z) {
        return switch (face) {
            case EAST, WEST -> z;
            default -> x;
        };
    }

    static float textureT(Direction face, float x, float y, float z) {
        return switch (face) {
            case UP, DOWN -> z;
            default -> y;
        };
    }

    static float snapToCell(float value) {
        float nearest = Math.round(value);
        return Math.abs(value - nearest) <= CELL_SNAP_EPSILON ? nearest : value;
    }

    static int floorCell(float value) {
        return (int) Math.floor(value);
    }

    static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private enum Axis {
        S,
        T
    }

    record Vertex(float x,
                  float y,
                  float z,
                  int color,
                  int lightmap,
                  boolean hasNormal,
                  float normalX,
                  float normalY,
                  float normalZ,
                  float sourceU,
                  float sourceV) {
        Vertex(float x,
               float y,
               float z,
               int color,
               int lightmap,
               boolean hasNormal,
               float normalX,
               float normalY,
               float normalZ) {
            this(x, y, z, color, lightmap, hasNormal, normalX, normalY, normalZ, Float.NaN, Float.NaN);
        }

        private static Vertex interpolate(Vertex from, Vertex to, float amount) {
            boolean interpolateNormal = from.hasNormal && to.hasNormal;
            float normalX = interpolateNormal ? lerp(from.normalX, to.normalX, amount) : 0.0F;
            float normalY = interpolateNormal ? lerp(from.normalY, to.normalY, amount) : 0.0F;
            float normalZ = interpolateNormal ? lerp(from.normalZ, to.normalZ, amount) : 0.0F;
            if (interpolateNormal) {
                float lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
                if (lengthSquared > 0.0000001F) {
                    float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
                    normalX *= inverseLength;
                    normalY *= inverseLength;
                    normalZ *= inverseLength;
                }
            }
            return new Vertex(
                    lerp(from.x, to.x, amount),
                    lerp(from.y, to.y, amount),
                    lerp(from.z, to.z, amount),
                    interpolateColor(from.color, to.color, amount),
                    interpolateLightmap(from.lightmap, to.lightmap, amount),
                    interpolateNormal,
                    normalX,
                    normalY,
                    normalZ,
                    lerp(from.sourceU, to.sourceU, amount),
                    lerp(from.sourceV, to.sourceV, amount)
            );
        }

        private static int interpolateColor(int from, int to, float amount) {
            int alpha = interpolateByte(from >>> 24, to >>> 24, amount);
            int red = interpolateByte(from >>> 16, to >>> 16, amount);
            int green = interpolateByte(from >>> 8, to >>> 8, amount);
            int blue = interpolateByte(from, to, amount);
            return alpha << 24 | red << 16 | green << 8 | blue;
        }

        private static int interpolateLightmap(int from, int to, float amount) {
            int block = Math.round(lerp(from & 0xFFFF, to & 0xFFFF, amount));
            int sky = Math.round(lerp((from >>> 16) & 0xFFFF, (to >>> 16) & 0xFFFF, amount));
            return sky << 16 | block;
        }

        private static int interpolateByte(int from, int to, float amount) {
            return Math.max(0, Math.min(255, Math.round(lerp(from & 0xFF, to & 0xFF, amount))));
        }
    }

    record CellVertex(Vertex vertex, float localS, float localT) {
    }

    record Fragment(int cellS, int cellT, List<CellVertex> vertices) {
    }

    private record ProjectedVertex(Vertex vertex, float s, float t) {
    }
}
