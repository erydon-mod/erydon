package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RawRomanesqueArchModelTest {
    private static final String[] SUFFIXES = {
            "corner_small", "corner_medium", "corner_large_upper", "corner_large_lower",
            "side_small", "side_medium", "side_large", "side_large_lower",
            "side_medium_upper", "side_large_upper", "side_column", "side_column_upper", "plinth", "top_large", "icon",
            "non_column_side_small",
            "non_column_side_small_base",
            "non_column_side_small_crown",
            "non_column_side_medium",
            "non_column_side_medium_base",
            "non_column_side_medium_crown",
            "non_column_side_large",
            "non_column_side_large_base",
            "non_column_side_large_lower",
            "non_column_side_large_crown",
            "non_column_corner_small",
            "non_column_corner_medium",
            "non_column_corner_large_lower",
            "non_column_corner_large_upper"
    };

    @Test
    void everyComponentBindsItsMaterialAndPermanentAgedResourceSpelling() throws Exception {
        Class<?> component = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawComponent");
        Method from = component.getDeclaredMethod("from", Identifier.class);
        Method parent = component.getDeclaredMethod("parentModelId");
        Field key = component.getDeclaredField("modelKey");
        Field texture = component.getDeclaredField("textureId");
        from.setAccessible(true);
        parent.setAccessible(true);
        key.setAccessible(true);
        texture.setAccessible(true);
        for (String suffix : SUFFIXES) {
            for (String material : new String[]{"aganite", "white_marble_high_polish"}) {
                for (boolean aged : new boolean[]{false, true}) {
                    String path = "block/arch/romanesque/" + material + "_arch_romanesque_" + suffix
                            + (aged ? "_aged" : "");
                    Object routed = from.invoke(null, new Identifier("erydon", path));
                    assertNotNull(routed, path);
                    assertEquals("arch_romanesque/" + suffix, key.get(routed), path);
                    assertEquals(new Identifier("erydon", "block/" + material + "_block"
                            + (aged ? "_aged" : "")), texture.get(routed), path);
                    assertEquals(new Identifier("erydon", "block/arch/romanesque/arch_romanesque_" + suffix),
                            parent.invoke(routed), path);
                    assertNull(from.invoke(null, new Identifier("other_mod", path)), path);
                }
            }
        }
        assertNull(from.invoke(null, new Identifier("erydon", "block/arch/modern/aganite_arch_modern_side_small")));
        assertNull(from.invoke(null, new Identifier("erydon", "block/arch/romanesque/aganite_arch_romanesque_unknown")));
    }

    @Test
    void authoredOverhangsBakeToAtlasSafeSurfacesAndReuseTheSplitCache() throws Exception {
        Path root = Path.of("src/main/resources/assets/erydon/authoring_models/block/arch/romanesque");
        for (String suffix : SUFFIXES) {
            String filename = "arch_romanesque_" + suffix + ".json";
            JsonObject source = JsonParser.parseString(Files.readString(root.resolve(filename))).getAsJsonObject();
            Identifier id = new Identifier("erydon", "authoring_models/block/arch/romanesque/" + filename);
            var faces = ErydonRawModelLoadingPlugin.surfaceSnapshots("arch_romanesque/" + suffix, id, source);
            assertFalse(faces.isEmpty(), suffix);
            assertTrue(ErydonRawModelLoadingPlugin.reusesWorldUvSurfacesForTest(id, source), suffix);
            for (var face : faces) {
                for (float[] uv : face.normalizedUvs()) {
                    for (float value : uv) {
                        assertTrue(Float.isFinite(value) && value >= -1.0e-6F && value <= 1.000001F,
                                suffix + ": atlas coordinate " + value);
                    }
                }
            }
        }
    }
}
