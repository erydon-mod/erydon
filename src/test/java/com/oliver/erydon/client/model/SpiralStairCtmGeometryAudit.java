package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class SpiralStairCtmGeometryAudit {
    private static final float EPSILON = 0.00001F;

    private SpiralStairCtmGeometryAudit() {
    }

    public static void main(String[] args) {
        verifyAreaPreservingCellSplit();
        verifyEveryFaceProjectionAndOffset();
        verifyTinyBoundaryOffsetsAreSnapped();
        verifyRepeatTileArithmetic();
        System.out.println("Spiral stair CTM geometry audit passed.");
    }

    private static void verifyAreaPreservingCellSplit() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(-0.25F, 0.5F, 0.2F),
                vertex(-0.25F, 0.5F, 0.8F),
                vertex(1.25F, 0.5F, 0.8F),
                vertex(1.25F, 0.5F, 0.2F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(Direction.UP, vertices);
        if (fragments.size() != 3) {
            throw new IllegalStateException("Expected three X cells, found " + fragments.size());
        }

        double area = 0.0D;
        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            area += area(fragment);
            assertUvRange(Direction.UP, fragment);
        }
        assertNear("split area", 0.9D, area);
        assertCells("split cells", fragments, List.of(-1, 0, 1));
    }

    private static void verifyEveryFaceProjectionAndOffset() {
        Map<Direction, int[]> expectedOffsets = new EnumMap<>(Direction.class);
        expectedOffsets.put(Direction.UP, new int[]{1, 0, -2});
        expectedOffsets.put(Direction.DOWN, new int[]{1, 0, -2});
        expectedOffsets.put(Direction.NORTH, new int[]{1, -2, 0});
        expectedOffsets.put(Direction.SOUTH, new int[]{1, -2, 0});
        expectedOffsets.put(Direction.WEST, new int[]{0, -2, 1});
        expectedOffsets.put(Direction.EAST, new int[]{0, -2, 1});

        for (Direction face : Direction.values()) {
            List<SpiralStairCtmGeometry.Vertex> vertices = faceRectangle(face);
            List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(face, vertices);
            if (fragments.size() != 1) {
                throw new IllegalStateException(face + " should remain one fragment, found " + fragments.size());
            }
            SpiralStairCtmGeometry.Fragment fragment = fragments.get(0);
            assertUvRange(face, fragment);
            int[] expected = expectedOffsets.get(face);
            int[] actual = {
                    SpiralStairCtmGeometry.offsetX(face, fragment),
                    SpiralStairCtmGeometry.offsetY(face, fragment),
                    SpiralStairCtmGeometry.offsetZ(face, fragment)
            };
            for (int index = 0; index < 3; index++) {
                if (actual[index] != expected[index]) {
                    throw new IllegalStateException(face + " offset mismatch at axis " + index
                            + ": expected " + expected[index] + ", found " + actual[index]);
                }
            }
        }
    }

    private static void verifyTinyBoundaryOffsetsAreSnapped() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(0.4F, 1.0004375F, 0.2F),
                vertex(0.8F, 1.0004375F, 0.2F),
                vertex(0.8F, 0.5F, 0.2F),
                vertex(0.4F, 0.5F, 0.2F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);
        if (fragments.size() != 1 || fragments.get(0).cellT() != 0) {
            throw new IllegalStateException("Tiny anti-z-fighting offset created a false CTM cell: " + fragments);
        }
    }

    private static void verifyRepeatTileArithmetic() {
        int x = -7;
        int y = 13;
        int z = 8;
        Map<Direction, Integer> expected = Map.of(
                Direction.DOWN, Math.floorMod(-z - 1, 6) * 6 + Math.floorMod(x, 6),
                Direction.UP, Math.floorMod(z, 6) * 6 + Math.floorMod(x, 6),
                Direction.NORTH, Math.floorMod(-y, 6) * 6 + Math.floorMod(-x - 1, 6),
                Direction.SOUTH, Math.floorMod(-y, 6) * 6 + Math.floorMod(x, 6),
                Direction.WEST, Math.floorMod(-y, 6) * 6 + Math.floorMod(z, 6),
                Direction.EAST, Math.floorMod(-y, 6) * 6 + Math.floorMod(-z - 1, 6)
        );
        for (Direction face : Direction.values()) {
            int actual = ErydonCtmService.repeatTileIndex(x, y, z, face);
            if (actual != expected.get(face)) {
                throw new IllegalStateException(face + " repeat tile mismatch: expected "
                        + expected.get(face) + ", found " + actual);
            }
        }
    }

    private static List<SpiralStairCtmGeometry.Vertex> faceRectangle(Direction face) {
        float lowS = 1.25F;
        float highS = 1.75F;
        float lowT = -1.75F;
        float highT = -1.25F;
        return switch (face) {
            case UP, DOWN -> List.of(
                    vertex(lowS, 0.5F, lowT), vertex(lowS, 0.5F, highT),
                    vertex(highS, 0.5F, highT), vertex(highS, 0.5F, lowT));
            case NORTH, SOUTH -> List.of(
                    vertex(lowS, lowT, 0.5F), vertex(lowS, highT, 0.5F),
                    vertex(highS, highT, 0.5F), vertex(highS, lowT, 0.5F));
            case EAST, WEST -> List.of(
                    vertex(0.5F, lowT, lowS), vertex(0.5F, highT, lowS),
                    vertex(0.5F, highT, highS), vertex(0.5F, lowT, highS));
        };
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x, float y, float z) {
        return new SpiralStairCtmGeometry.Vertex(x, y, z, -1, 0, false, 0.0F, 0.0F, 0.0F);
    }

    private static void assertUvRange(Direction face, SpiralStairCtmGeometry.Fragment fragment) {
        for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
            float u = SpiralStairCtmGeometry.u(face, vertex);
            float v = SpiralStairCtmGeometry.v(face, vertex);
            if (u < -EPSILON || u > 1.0F + EPSILON || v < -EPSILON || v > 1.0F + EPSILON) {
                throw new IllegalStateException(face + " produced out-of-cell UV " + u + ", " + v);
            }
        }
    }

    private static double area(SpiralStairCtmGeometry.Fragment fragment) {
        double twiceArea = 0.0D;
        List<SpiralStairCtmGeometry.CellVertex> vertices = fragment.vertices();
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex current = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex next = vertices.get((index + 1) % vertices.size());
            double currentS = fragment.cellS() + current.localS();
            double currentT = fragment.cellT() + current.localT();
            double nextS = fragment.cellS() + next.localS();
            double nextT = fragment.cellT() + next.localT();
            twiceArea += currentS * nextT - nextS * currentT;
        }
        return Math.abs(twiceArea) * 0.5D;
    }

    private static void assertCells(String label,
                                    List<SpiralStairCtmGeometry.Fragment> fragments,
                                    List<Integer> expected) {
        List<Integer> actual = fragments.stream().map(SpiralStairCtmGeometry.Fragment::cellS).sorted().toList();
        if (!actual.equals(expected)) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

    private static void assertNear(String label, double expected, double actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

}
