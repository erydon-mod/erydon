package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SynapheiaCellGeometryTest {
    private static final float EPSILON = 0.000001F;

    @Test
    void exactAndSnappedBoundariesMatchTheSplitterForEveryFace() {
        for (Direction face : Direction.values()) {
            assertCellAndSplitter(face, 0.0F, 1.0F, 0.0F, 1.0F, 0, 0);
            assertCellAndSplitter(face, -2.0F, -1.0F, 3.0F, 4.0F, -2, 3);
            assertCellAndSplitter(face, 0.0005F, 0.9995F,
                    -1.9995F, -1.0005F, 0, -2);
        }
    }

    @Test
    void genuinelyCrossCellGeometryAlwaysUsesTheSplitter() {
        for (Direction face : Direction.values()) {
            float[][] coordinates = coordinates(face, -0.5F, 0.5F, 0.0F, 1.0F);
            assertNull(SynapheiaCellGeometry.singleCell(face, quad(coordinates)));
            assertEquals(2, SpiralStairCtmGeometry.split(face, vertices(coordinates)).size());

            float[][] beyondSnap = coordinates(face, 0.0011F, 1.0011F, 0.0F, 1.0F);
            assertNull(SynapheiaCellGeometry.singleCell(face, quad(beyondSnap)));
        }
    }

    @Test
    void uvOrientationAndWorldOffsetsMatchTheFragmentPath() {
        for (Direction face : Direction.values()) {
            float[][] coordinates = coordinates(face, -2.0F, -1.0F, 3.0F, 4.0F);
            QuadView quad = quad(coordinates);
            SynapheiaCellGeometry.Cell cell = SynapheiaCellGeometry.singleCell(face, quad);
            assertNotNull(cell);
            List<SpiralStairCtmGeometry.CellVertex> splitVertices =
                    SpiralStairCtmGeometry.split(face, vertices(coordinates)).get(0).vertices();
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(SpiralStairCtmGeometry.u(face, splitVertices.get(vertex)),
                        SynapheiaCellGeometry.u(face, quad, vertex, cell), EPSILON);
                assertEquals(SpiralStairCtmGeometry.v(face, splitVertices.get(vertex)),
                        SynapheiaCellGeometry.v(face, quad, vertex, cell), EPSILON);
            }

            int expectedX = switch (face) {
                case UP, DOWN, NORTH, SOUTH -> -2;
                default -> 0;
            };
            int expectedY = switch (face) {
                case NORTH, SOUTH, EAST, WEST -> 3;
                default -> 0;
            };
            int expectedZ = switch (face) {
                case UP, DOWN -> 3;
                case EAST, WEST -> -2;
                default -> 0;
            };
            assertEquals(expectedX, cell.offsetX(face));
            assertEquals(expectedY, cell.offsetY(face));
            assertEquals(expectedZ, cell.offsetZ(face));
        }
    }

    private static void assertCellAndSplitter(Direction face,
                                              float minS,
                                              float maxS,
                                              float minT,
                                              float maxT,
                                              int expectedS,
                                              int expectedT) {
        float[][] coordinates = coordinates(face, minS, maxS, minT, maxT);
        SynapheiaCellGeometry.Cell cell = SynapheiaCellGeometry.singleCell(face, quad(coordinates));
        assertNotNull(cell);
        assertEquals(expectedS, cell.cellS());
        assertEquals(expectedT, cell.cellT());
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(face, vertices(coordinates));
        assertEquals(1, fragments.size());
        assertEquals(expectedS, fragments.get(0).cellS());
        assertEquals(expectedT, fragments.get(0).cellT());
    }

    private static float[][] coordinates(Direction face,
                                         float minS,
                                         float maxS,
                                         float minT,
                                         float maxT) {
        float[][] projected = {
                {minS, minT}, {minS, maxT}, {maxS, maxT}, {maxS, minT}
        };
        float[][] result = new float[4][3];
        for (int vertex = 0; vertex < 4; vertex++) {
            float s = projected[vertex][0];
            float t = projected[vertex][1];
            switch (face.getAxis()) {
                case X -> { result[vertex][0] = 0.5F; result[vertex][1] = t; result[vertex][2] = s; }
                case Y -> { result[vertex][0] = s; result[vertex][1] = 0.5F; result[vertex][2] = t; }
                case Z -> { result[vertex][0] = s; result[vertex][1] = t; result[vertex][2] = 0.5F; }
            }
        }
        return result;
    }

    private static QuadView quad(float[][] coordinates) {
        return (QuadView) Proxy.newProxyInstance(
                QuadView.class.getClassLoader(), new Class<?>[]{QuadView.class},
                (proxy, method, arguments) -> {
                    int vertex = arguments == null || arguments.length == 0
                            ? 0 : (int) arguments[0];
                    return switch (method.getName()) {
                        case "x" -> coordinates[vertex][0];
                        case "y" -> coordinates[vertex][1];
                        case "z" -> coordinates[vertex][2];
                        case "toString" -> "SynapheiaCellGeometryTestQuad";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static List<SpiralStairCtmGeometry.Vertex> vertices(float[][] coordinates) {
        return java.util.Arrays.stream(coordinates)
                .map(point -> new SpiralStairCtmGeometry.Vertex(
                        point[0], point[1], point[2], -1, 0,
                        false, 0.0F, 0.0F, 0.0F))
                .toList();
    }
}
