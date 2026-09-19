package com.oliver.erydon.client;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GlazingSpecularResourcesTest {
    @Test
    void everyGlazingTextureHasAnExactOpaqueRedSpecularMapInTheOptionalPack() throws Exception {
        Path textures = Path.of("src/main/resources/assets/erydon/textures/block");
        Path pack = Path.of("src/main/resources/resourcepacks/high_polish_glazing");
        assertTrue(Files.readString(pack.resolve("pack.mcmeta")).contains("\"pack_format\": 15"));
        int checked = 0;
        try (var sources = Files.list(textures)) {
            for (Path source : sources.filter(p -> p.getFileName().toString().matches("glazing.*\\.png")).toList()) {
                var color = ImageIO.read(source.toFile());
                String specularName = source.getFileName().toString().replace(".png", "_s.png");
                var specular = ImageIO.read(pack.resolve("assets/erydon/textures/block/" + specularName).toFile());
                assertEquals(color.getWidth(), specular.getWidth());
                assertEquals(color.getHeight(), specular.getHeight());
                for (int y = 0; y < specular.getHeight(); y++) {
                    for (int x = 0; x < specular.getWidth(); x++) assertEquals(0xFFFF0000, specular.getRGB(x, y));
                }
                checked++;
            }
        }
        assertEquals(10, checked);
    }
}
