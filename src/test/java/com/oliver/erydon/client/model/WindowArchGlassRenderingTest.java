package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowArchBlock.Glass;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WindowArchGlassRenderingTest {
    @Test
    void upperPaneCannotBreakThroughTheStoneRoof() throws Exception {
        var path = Path.of("src/main/resources/assets/erydon/models/block/window/arch/window_arch_multi_upper.json");
        var model = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
        var elements = model.getAsJsonArray("elements");
        var pane = elements.get(15).getAsJsonObject();
        var roof = elements.get(25).getAsJsonObject();
        assertTrue(pane.getAsJsonArray("to").get(1).getAsDouble() <= roof.getAsJsonArray("from").get(1).getAsDouble());
        assertFalse(pane.getAsJsonObject("faces").has("up"));
    }

    @Test
    void exposedCornerPostHasAnUnculledStoneTop() throws Exception {
        var path = Path.of("src/main/resources/assets/erydon/models/block/window/arch/window_arch_multi_upper.json");
        var model = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
        int exposedPosts = 0;
        for (var raw : model.getAsJsonArray("elements")) {
            var element = raw.getAsJsonObject();
            var rotation = element.getAsJsonObject("rotation");
            if (rotation == null || !rotation.get("axis").getAsString().equals("y")
                    || rotation.get("angle").getAsFloat() == 0
                    || element.getAsJsonArray("to").get(1).getAsFloat() != 16) continue;
            var faces = element.getAsJsonObject("faces");
            assertTrue(faces.has("up"), "Rotated post reaching the roof needs a top face");
            assertEquals("#stone", faces.getAsJsonObject("up").get("texture").getAsString());
            assertFalse(faces.getAsJsonObject("up").has("cullface"));
            exposedPosts++;
        }
        assertEquals(1, exposedPosts);
    }

    @Test
    void glassHasOnlyTwoStableValues() {
        assertEquals(2, Glass.values().length);
        assertEquals("normal", Glass.NORMAL.asString());
        assertEquals("two_way", Glass.TWO_WAY.asString());
    }

    @Test
    void twoWayGlassKeepsItsMaterialWithHighPolishOnAndOff() {
        RenderMaterial solid = material();
        RenderMaterial translucent = material();
        for (RenderMaterial mirrorMaterial : new RenderMaterial[]{solid, translucent}) {
            for (RenderMaterial stone : new RenderMaterial[]{solid, translucent}) {
                for (Glass glass : Glass.values()) {
                    for (Direction outside : Direction.Type.HORIZONTAL) {
                        for (Direction face : Direction.values()) {
                            for (int originalTint : new int[]{-1, 0, 1}) {
                                AtomicInteger tint = new AtomicInteger(originalTint);
                                AtomicInteger rebakes = new AtomicInteger();
                                AtomicReference<RenderMaterial> assigned = new AtomicReference<>();
                                MutableQuadView quad = (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[]{MutableQuadView.class}, (proxy, method, args) -> {
                                            switch (method.getName()) {
                                                case "lightFace": return face;
                                                case "colorIndex":
                                                    if (args == null) return tint.get();
                                                    tint.set((Integer) args[0]);
                                                    return proxy;
                                                case "spriteBake":
                                                    assertEquals(MutableQuadView.BAKE_LOCK_UV, args[1]);
                                                    rebakes.incrementAndGet();
                                                    return proxy;
                                                case "material":
                                                    assigned.set((RenderMaterial) args[0]);
                                                    return proxy;
                                                default: throw new AssertionError("Glass finish must not modify geometry: " + method.getName());
                                            }
                                        });
                                WindowArchBakedModel.applyGlassFinish(quad, glass, outside, null, solid, translucent, stone, mirrorMaterial);
                                boolean mirrored = glass == Glass.TWO_WAY && face == outside && originalTint == 0;
                                assertEquals(mirrored ? 1 : 0, rebakes.get());
                                assertEquals(mirrored ? -1 : originalTint, tint.get());
                                RenderMaterial expected = mirrored ? mirrorMaterial : originalTint == 0 ? translucent
                                        : originalTint < 0 ? stone : solid;
                                assertSame(expected, assigned.get());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void mirrorUsesTheApprovedOpaqueNeutralLabPbrMaterial() throws Exception {
        var root = Path.of("src/main/resources/assets/erydon/textures/block");
        var color = ImageIO.read(root.resolve("window_arch_mirror.png").toFile());
        var specular = ImageIO.read(root.resolve("window_arch_mirror_s.png").toFile());
        assertEquals(16, color.getWidth());
        assertEquals(16, color.getHeight());
        assertEquals(color.getWidth(), specular.getWidth());
        assertEquals(color.getHeight(), specular.getHeight());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(0xFF505050, color.getRGB(x, y));
                assertEquals(0xFFFFFF00, specular.getRGB(x, y));
            }
        }
    }

    private RenderMaterial material() {
        return (RenderMaterial) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RenderMaterial.class}, (proxy, method, args) -> null);
    }
}
