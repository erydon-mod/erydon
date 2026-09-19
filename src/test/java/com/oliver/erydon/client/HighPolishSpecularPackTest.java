package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishSpecularPackTest {
    private static final HighPolishSettings ON = HighPolishSettings.defaults().withEnabled(true);

    @Test void preservesAllPackResolutionsAndNonStonePixels() {
        for (int size : new int[]{16, 32, 64}) {
            BufferedImage original = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            int[] colors = {0xFFAF0018, 0xFFAF0024, 0xFF000000, 0xFFFFE600, 0x00AF0018};
            for (int x = 0; x < colors.length; x++) original.setRGB(x, 0, colors[x]);
            BufferedImage polished = HighPolishSpecularPack.polish(original);
            assertEquals(size, polished.getWidth());
            assertEquals(size, polished.getHeight());
            int[] expected = {0xFFFF000A, 0xFFFF000A, 0xFF000000, 0xFFFFE600, 0x00FF000A};
            for (int x = 0; x < colors.length; x++) {
                assertEquals(expected[x], polished.getRGB(x, 0));
                assertEquals(colors[x], original.getRGB(x, 0), "Must not mutate pack pixels");
            }
        }
    }

    @Test void winningPackResolutionIsPreservedAndOnlySelectedMaterialsOverride() throws Exception {
        var id = new Identifier("minecraft", "textures/optifine/ctm/kelastrion/0_s.png");
        var latmion = new Identifier("erydon", "textures/block/latmion_block_s.png");
        var packs = List.of(pack(Map.of(id, png(16), latmion, png(16))), pack(Map.of(id, png(64))));
        var settings = ON.withStone("latmion", new HighPolishSettings.Stone(false,
                HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT));
        var result = HighPolishSpecularPack.append(ResourceType.CLIENT_RESOURCES, packs, settings);
        assertEquals(3, result.size());
        var overlay = result.get(2);
        try (var in = overlay.open(ResourceType.CLIENT_RESOURCES, id).get()) {
            var image = ImageIO.read(in);
            assertEquals(64, image.getWidth());
            assertEquals(0xFFFF000A, image.getRGB(0, 0));
        }
        assertNull(overlay.open(ResourceType.CLIENT_RESOURCES, latmion));
        assertNull(overlay.open(ResourceType.SERVER_DATA, id));
        var found = new HashSet<Identifier>();
        overlay.findResources(ResourceType.CLIENT_RESOURCES, "minecraft", "textures", (key, supplier) -> found.add(key));
        assertEquals(Set.of(id), found);
        var language = new Identifier("erydon", "lang/en_us.json");
        try (var in = overlay.open(ResourceType.CLIENT_RESOURCES, language).get()) {
            String labels = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(labels.contains("Kelastrion Polished"));
            assertFalse(labels.contains("Latmion"));
        }
        assertSame(packs, HighPolishSpecularPack.append(ResourceType.SERVER_DATA, packs, ON));
        assertSame(packs, HighPolishSpecularPack.append(ResourceType.CLIENT_RESOURCES, packs, HighPolishSettings.defaults()));
    }

    private static byte[] png(int size) throws Exception {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFAF0018);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static ResourcePack pack(Map<Identifier, byte[]> files) {
        return (ResourcePack) Proxy.newProxyInstance(ResourcePack.class.getClassLoader(), new Class[]{ResourcePack.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("open")) {
                        byte[] bytes = files.get(args[1]);
                        return bytes == null ? null : (InputSupplier<InputStream>) () -> new ByteArrayInputStream(bytes);
                    }
                    throw new AssertionError(method.getName());
                });
    }
}
