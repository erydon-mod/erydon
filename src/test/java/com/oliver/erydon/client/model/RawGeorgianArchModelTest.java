package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RawGeorgianArchModelTest {
    @Test
    void everyFixedProfileBindsCanonicalIdsAndExistingAgedTextureResources() throws Exception {
        var suffixes = ArchGeorgianBakedModel.modelSuffixes("aganite_arch_georgian");
        assertEquals(56, suffixes.size());
        assertEquals(56, new HashSet<>(suffixes).size());
        Class<?> component = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawComponent");
        Method from = component.getDeclaredMethod("from", Identifier.class);
        Method parent = component.getDeclaredMethod("parentModelId");
        Field key = component.getDeclaredField("modelKey");
        Field texture = component.getDeclaredField("textureId");
        from.setAccessible(true);
        parent.setAccessible(true);
        key.setAccessible(true);
        texture.setAccessible(true);
        for (String suffix : suffixes) {
            for (String material : new String[]{"aganite", "aganite_ashlar", "aganite_aged"}) {
                String block = material + "_arch_georgian";
                Identifier model = new Identifier("erydon", "block/arch/georgian/" + block + "_" + suffix);
                assertEquals(model, ArchGeorgianBakedModel.modelId(block, suffix));
                Object routed = from.invoke(null, model);
                assertNotNull(routed, model.toString());
                assertEquals("arch_georgian/" + suffix, key.get(routed), model.toString());
                String texturePath = material.equals("aganite_aged") ? "aganite_block_aged" : material + "_block";
                assertEquals(new Identifier("erydon", "block/" + texturePath), texture.get(routed), model.toString());
                assertEquals(new Identifier("erydon", "block/arch/georgian/arch_georgian_" + suffix),
                        parent.invoke(routed), model.toString());
                assertNull(from.invoke(null, new Identifier("other_mod", model.getPath())));
            }
        }
        assertNull(from.invoke(null, new Identifier("erydon", "block/arch/georgian/aganite_arch_georgian_icon_aged")));
        assertNull(from.invoke(null, new Identifier("erydon", "block/arch/georgian/aganite_arch_georgian_unknown")));
        assertFalse(HorizontalUvLock.shouldProjectAtBake(new Identifier("erydon", "block/arch/georgian/aganite_aged_arch_georgian_icon")));
        assertTrue(HorizontalUvLock.shouldProjectAtBake(new Identifier("erydon", "block/arch/georgian/aganite_arch_georgian_base_left")));
    }

    @Test
    void allRawProfilesCacheAtlasSafeSurfacesIncludingDeliberatelyEmptyCrownCells() throws Exception {
        Path root = Path.of("src/main/resources/assets/erydon/authoring_models/block/arch/georgian");
        Set<String> checked = new HashSet<>();
        for (String suffix : ArchGeorgianBakedModel.modelSuffixes("aganite_arch_georgian")) {
            String filename = "arch_georgian_" + suffix + ".json";
            JsonObject source = JsonParser.parseString(Files.readString(root.resolve(filename))).getAsJsonObject();
            Identifier id = new Identifier("erydon", "authoring_models/block/arch/georgian/" + filename);
            assertTrue(source.get("erydon_split_world_uv").getAsBoolean(), suffix);
            var surfaces = ErydonRawModelLoadingPlugin.surfaceSnapshots("arch_georgian/" + suffix, id, source);
            assertEquals(source.getAsJsonArray("elements").isEmpty(), surfaces.isEmpty(), suffix);
            assertTrue(ErydonRawModelLoadingPlugin.reusesWorldUvSurfacesForTest(id, source), suffix);
            for (var surface : surfaces) {
                for (float[] uv : surface.normalizedUvs()) {
                    for (float value : uv) {
                        assertTrue(Float.isFinite(value) && value >= -1.0e-6F && value <= 1.000001F,
                                suffix + ": atlas coordinate " + value);
                    }
                }
            }
            checked.add(suffix);
        }
        assertEquals(56, checked.size());
    }
}
