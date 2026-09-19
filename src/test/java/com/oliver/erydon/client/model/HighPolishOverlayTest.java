package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishOverlayTest {
    @Test
    void triangularInlaysKeepStableShaderBoundsAfterWorldProjection() {
        int[][] orders = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
        for (Direction face : Direction.values()) {
            for (int[] order : orders) {
                float[][] corners = {{0,0}, {1,0}, {0,1}};
                float[][] data = new float[4][5];
                for (int i = 0; i < 4; i++) {
                    float[] point = corners[order[Math.min(i, 2)]];
                    switch (face.getAxis()) {
                        case X -> { data[i][1] = point[0]; data[i][2] = point[1]; }
                        case Y -> { data[i][0] = point[0]; data[i][2] = point[1]; }
                        case Z -> { data[i][0] = point[0]; data[i][1] = point[1]; }
                    }
                }
                MutableQuadView quad = (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{MutableQuadView.class}, (proxy, method, args) -> {
                            int i = (Integer) args[0];
                            return switch (method.getName()) {
                                case "x" -> data[i][0]; case "y" -> data[i][1]; case "z" -> data[i][2];
                                case "u" -> data[i][3]; case "v" -> data[i][4];
                                case "uv" -> { data[i][3] = (Float) args[1]; data[i][4] = (Float) args[2]; yield proxy; }
                                default -> throw new AssertionError("Only invisible UVs may change");
                            };
                        });
                var cell = SynapheiaCellGeometry.singleCell(face, quad);
                assertNotNull(cell);
                for (int i = 0; i < 4; i++) {
                    quad.uv(i, SynapheiaCellGeometry.u(face, quad, i, cell),
                            SynapheiaCellGeometry.v(face, quad, i, cell));
                }
                float[][] before = java.util.Arrays.stream(data).map(float[]::clone).toArray(float[][]::new);
                SynapheiaRepeatBakedModel.restoreTriangleOverlayBounds(quad);
                for (int i = 0; i < 3; i++) assertArrayEquals(before[i], data[i]);
                for (int axis = 0; axis < 3; axis++) assertEquals(before[3][axis], data[3][axis]);
                for (int uv = 3; uv < 5; uv++) {
                    float mid = (data[0][uv] + data[1][uv] + data[2][uv] + data[3][uv]) / 4;
                    float radius = Math.abs(data[0][uv] - mid);
                    for (int i = 0; i < 4; i++) {
                        assertEquals(radius, Math.abs(data[i][uv] - mid), 0.00001F);
                        assertTrue(data[i][uv] >= 0 && data[i][uv] <= 1);
                    }
                }
            }
        }
    }

    @Test
    void coatingMovesOnlyOutwardAndKeepsItsSizeAndTextureCoordinates() {
        for (Direction face : Direction.values()) {
            for (boolean enabled : new boolean[]{false, true}) {
                float[][] positions = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
                float[][] original = java.util.Arrays.stream(positions).map(float[]::clone).toArray(float[][]::new);
                MutableQuadView quad = (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{MutableQuadView.class}, (proxy, method, args) -> {
                            int i = (Integer) args[0];
                            return switch (method.getName()) {
                                case "x" -> positions[i][0];
                                case "y" -> positions[i][1];
                                case "z" -> positions[i][2];
                                case "pos" -> {
                                    positions[i] = new float[]{(Float) args[1], (Float) args[2], (Float) args[3]};
                                    yield proxy;
                                }
                                default -> throw new AssertionError("Must not change UVs, colour or surface count");
                            };
                        });
                SynapheiaRepeatBakedModel.offsetPolishedOverlay(quad, face, enabled);
                float d = enabled ? 1F / 1024F : 0;
                for (int i = 0; i < 4; i++) {
                    assertEquals(original[i][0] + face.getOffsetX() * d, positions[i][0]);
                    assertEquals(original[i][1] + face.getOffsetY() * d, positions[i][1]);
                    assertEquals(original[i][2] + face.getOffsetZ() * d, positions[i][2]);
                }
            }
        }
    }
}
