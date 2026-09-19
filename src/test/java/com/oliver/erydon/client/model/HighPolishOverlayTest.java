package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishOverlayTest {
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
