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
    void everyWindowPaneReusesTheApprovedCrystalGlazingTexture() throws Exception {
        Path models = Path.of("src/main/resources/assets/erydon/models/block");
        var crystal = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                models.resolve("glazing/block/glazing_crystal_block.json"))).getAsJsonObject();
        String approved = crystal.getAsJsonObject("textures").get("pane").getAsString();
        int arch = 0, french = 0, icons = 0;
        try (var paths = java.nio.file.Files.walk(models.resolve("window"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var model = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
                var textures = model.getAsJsonObject("textures");
                if (textures == null || !textures.has("pane")) continue;
                String pane = textures.get("pane").getAsString();
                if (pane.startsWith("#")) {
                    assertEquals("#pane", pane, path.toString());
                    assertTrue(path.getFileName().toString().startsWith("window_"),
                            "A material child must supply a concrete pane binding: " + path);
                    continue; // Abstract parent parameters are resolved by the checked material children.
                }
                if (!hasPaneFaces(model, models)) continue; // Sill/void placeholders do not render glass.
                assertEquals(approved, pane, path.toString());
                if (path.getParent().endsWith("arch")) arch++;
                if (path.getParent().endsWith("french_georgian")) french++;
                if (path.getFileName().toString().contains("icon")) icons++;
            }
        }
        assertTrue(arch > 0 && french > 0 && icons > 0,
                "Crystal pane coverage must include both placed window families and their item icons");
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

    private static boolean hasPaneFaces(com.google.gson.JsonObject model, Path models) throws Exception {
        for (int depth = 0; depth < 8; depth++) {
            if (model.has("elements")) {
                for (var element : model.getAsJsonArray("elements"))
                    for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet())
                        if ("#pane".equals(face.getValue().getAsJsonObject().get("texture").getAsString())) return true;
                return false;
            }
            if (!model.has("parent")) return false;
            String parent = model.get("parent").getAsString();
            assertTrue(parent.startsWith("erydon:block/window/"), "Unexpected window geometry parent: " + parent);
            model = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                    models.resolve(parent.substring("erydon:block/".length()) + ".json"))).getAsJsonObject();
        }
        throw new AssertionError("Window geometry parent chain did not terminate");
    }

    private RenderMaterial material() {
        return (RenderMaterial) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RenderMaterial.class}, (proxy, method, args) -> null);
    }
}
