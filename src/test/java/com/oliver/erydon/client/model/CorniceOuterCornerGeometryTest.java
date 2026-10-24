package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.block.CorniceRun;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorniceOuterCornerGeometryTest {
    @Test void reciprocalWedgesHaveOneSquareOfCoverageInsteadOfTwoOverlappingButtEnds() {
        float depth = .3125F;
        BakedQuad source = top(depth);
        int[] original = source.getVertexData().clone();
        var first = CorniceOuterCornerGeometry.arm(List.of(source), false, Direction.NORTH);
        var second = CorniceOuterCornerGeometry.arm(List.of(source), true, Direction.NORTH);
        assertEquals(depth * depth / 2, area(first), 1e-9);
        assertEquals(depth * depth / 2, area(second), 1e-9);
        assertEquals(depth * depth, area(CorniceOuterCornerGeometry.outer(List.of(source), Direction.NORTH)), 1e-9);
        // An unmitred quarter-length copy on each arm covers the same square twice.
        double brokenButtArea = 2 * depth * depth;
        assertNotEquals(brokenButtArea, area(first) + area(second), 1e-9);
        for (BakedQuad quad : first) for (int i = 0; i < 4; i++) assertTrue(value(quad, i, 0) <= value(quad, i, 2));
        for (BakedQuad quad : second) for (int i = 0; i < 4; i++) assertTrue(value(quad, i, 2) <= value(quad, i, 0));
        assertArrayEquals(original, source.getVertexData(), "Cached straight source was mutated");
    }

    @Test void allFacingsRetainAreaNormalsMetadataAndSafeInterpolatedUvs() {
        BakedQuad source = top(.5F);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            var result = CorniceOuterCornerGeometry.outer(List.of(source), facing);
            assertEquals(.25, area(result), 1e-9);
            for (BakedQuad quad : result) {
                assertEquals(Direction.UP, quad.getFace());
                assertEquals(source.getColorIndex(), quad.getColorIndex());
                assertEquals(source.hasShade(), quad.hasShade());
                for (int i = 0; i < 4; i++) {
                    assertEquals(127, (byte) (quad.getVertexData()[i * 8 + 7] >> 8));
                    assertEquals(1, value(quad, i, 1));
                    assertTrue(value(quad, i, 4) >= 0 && value(quad, i, 4) <= 1);
                    assertTrue(value(quad, i, 5) >= 0 && value(quad, i, 5) <= 1);
                    assertEquals(0xFF234567, quad.getVertexData()[i * 8 + 3]);
                    assertEquals(0x01230045, quad.getVertexData()[i * 8 + 6]);
                }
            }
        }
    }

    @Test void fittedOuterJoinsAlreadyUseTheSameReciprocalDepthIntersection() {
        CorniceRun first = new CorniceRun(0, 0, 1, 0), second = new CorniceRun(1, 0, 1, -1);
        var join = CorniceRun.join(first, 1, second, -1);
        assertNotNull(join);
        var a = new CorniceConnections.Segment(first, new CorniceRun.Cut(0, 0), join.sourceCut(), false, true);
        var b = new CorniceConnections.Segment(second, join.targetCut(), new CorniceRun.Cut(0, 1), true, false);
        for (int i = 0; i <= 128; i++) {
            double depth = i / 256.0;
            assertEquals(a.point(1, depth).x(), b.point(0, depth).x(), 1e-12);
            assertEquals(a.point(1, depth).z(), b.point(0, depth).z(), 1e-12);
        }
        assertEquals(1, CorniceAngledGeometry.fit(List.of(top(.5F)), a).size());
        assertEquals(1, CorniceAngledGeometry.fit(List.of(top(.5F)), b).size());
    }

    private static BakedQuad top(float depth) {
        int[] data = new int[32];
        float[][] points = {{0, 1, 0}, {0, 1, depth}, {1, 1, depth}, {1, 1, 0}};
        for (int i = 0; i < 4; i++) {
            int at = i * 8;
            for (int axis = 0; axis < 3; axis++) data[at + axis] = Float.floatToRawIntBits(points[i][axis]);
            data[at + 3] = 0xFF234567; data[at + 6] = 0x01230045;
            data[at + 4] = Float.floatToRawIntBits(points[i][0]);
            data[at + 5] = Float.floatToRawIntBits(points[i][2]);
        }
        return new BakedQuad(data, 6, Direction.UP, null, false);
    }

    private static double area(List<BakedQuad> quads) {
        return quads.stream().mapToDouble(q -> triangle(q, 0, 1, 2) + triangle(q, 0, 2, 3)).sum();
    }
    private static double triangle(BakedQuad q, int a, int b, int c) {
        double x = value(q, b, 0) - value(q, a, 0), z = value(q, b, 2) - value(q, a, 2);
        double u = value(q, c, 0) - value(q, a, 0), w = value(q, c, 2) - value(q, a, 2);
        return Math.abs(x * w - z * u) / 2;
    }
    private static float value(BakedQuad q, int vertex, int slot) { return Float.intBitsToFloat(q.getVertexData()[vertex * 8 + slot]); }
}
