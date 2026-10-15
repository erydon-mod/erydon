package com.oliver.erydon.client.model;

import com.google.gson.stream.JsonReader;
import com.oliver.erydon.util.ErydonIdNaming;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RawModelParentCompatibilityTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");
    private static final Map<Identifier, SourceModel> SOURCES = new LinkedHashMap<>();
    private static Method rawComponentFrom;
    private static Field rawTexture;

    @BeforeAll
    static void indexModelParents() throws Exception {
        Class<?> component = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawComponent");
        rawComponentFrom = component.getDeclaredMethod("from", Identifier.class);
        rawTexture = component.getDeclaredField("textureId");
        rawComponentFrom.setAccessible(true);
        rawTexture.setAccessible(true);
        try (var namespaces = Files.list(ASSETS)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).sorted().toList()) {
                Path models = namespace.resolve("models");
                if (!Files.isDirectory(models)) continue;
                try (var paths = Files.walk(models)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        String relative = models.relativize(path).toString().replace('\\', '/');
                        Identifier id = new Identifier(namespace.getFileName().toString(),
                                relative.substring(0, relative.length() - ".json".length()));
                        SOURCES.put(id, new SourceModel(path, readParent(path)));
                    }
                }
            }
        }
        assertFalse(SOURCES.isEmpty(), "No source models were inspected");
    }

    @Test
    void noSourceJsonParentChainInheritsARawComponent() throws Exception {
        List<String> incompatible = new ArrayList<>();
        for (var entry : SOURCES.entrySet()) {
            Identifier parent = entry.getValue().parent();
            if (parent != null && rawComponentFrom.invoke(null, parent) != null) {
                incompatible.add(entry.getKey() + " -> " + parent);
            }
        }
        // Checking every source edge also covers transitive chains without
        // repeatedly opening their common ancestors or parsing their geometry.
        assertTrue(incompatible.isEmpty(), () -> incompatible.size()
                + " JSON models inherit non-JSON raw components: "
                + String.join(", ", incompatible.stream().limit(8).toList()));
    }

    @Test
    void vanillaRejectsAnItemInheritingAMaterialRawIcon() {
        JsonUnbakedModel brokenItem = JsonUnbakedModel.deserialize(
                "{\"parent\":\"erydon:block/arch/georgian/aganite_arch_georgian_icon\"}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> brokenItem.setParents(new ParentResolver()::resolve));
        assertEquals("BlockModel parent has to be a block model.", failure.getMessage());
    }

    @TestFactory
    Stream<DynamicTest> archItemsResolveVanillaParentsMaterialAndInheritedDisplay() {
        List<Identifier> items = SOURCES.keySet().stream()
                .filter(id -> id.getNamespace().equals("erydon") && id.getPath().startsWith("item/"))
                .filter(id -> id.getPath().endsWith("_arch_georgian")
                        || id.getPath().endsWith("_arch_romanesque"))
                .toList();
        assertEquals(162, items.stream().filter(id -> id.getPath().endsWith("_arch_georgian")).count());
        assertEquals(162, items.stream().filter(id -> id.getPath().endsWith("_arch_romanesque")).count());
        return items.stream().map(id -> DynamicTest.dynamicTest(id.toString(), () -> verifyArchItem(id)));
    }

    private static void verifyArchItem(Identifier itemId) throws Exception {
        String block = itemId.getPath().substring("item/".length());
        boolean georgian = block.endsWith("_arch_georgian");
        String style = georgian ? "georgian" : "romanesque";
        Identifier rawIcon = georgian ? ArchGeorgianBakedModel.modelId(block, "icon")
                : ArchRomanesqueBakedModel.modelId(block, "icon");
        Object component = rawComponentFrom.invoke(null, rawIcon);
        assertNotNull(component, "Material icon must remain on the raw rendering path: " + rawIcon);
        String plainBlock = ErydonIdNaming.withoutAged(block);
        String material = plainBlock.substring(0, plainBlock.length() - ("_arch_" + style).length());
        Identifier texture = new Identifier("erydon", "block/" + material + "_block"
                + (ErydonIdNaming.isAged(block) ? "_aged" : ""));
        assertEquals(texture, rawTexture.get(component), rawIcon.toString());

        ParentResolver resolver = new ParentResolver();
        JsonUnbakedModel item = resolver.json(itemId);
        assertDoesNotThrow(() -> item.setParents(resolver::resolve), itemId.toString());
        assertEquals(texture, item.resolveSprite("stone").getTextureId(), itemId.toString());
        assertEquals(texture, item.resolveSprite("particle").getTextureId(), itemId.toString());

        Identifier anchorId = new Identifier("erydon", "block/arch/" + style + "/arch_" + style + "_icon");
        JsonUnbakedModel anchor = resolver.json(anchorId);
        anchor.setParents(resolver::resolve);
        assertNotEquals(net.minecraft.client.render.model.json.Transformation.IDENTITY,
                anchor.getTransformations().gui, "Icon anchor must retain authored display settings");
        for (ModelTransformationMode mode : ModelTransformationMode.values()) {
            assertEquals(anchor.getTransformations().getTransformation(mode),
                    item.getTransformations().getTransformation(mode), itemId + " display " + mode);
        }
    }

    private static Identifier readParent(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("parent")) return new Identifier(reader.nextString());
                reader.skipValue();
            }
            return null;
        }
    }

    private record SourceModel(Path path, Identifier parent) { }

    private static final class ParentResolver {
        private final Map<Identifier, JsonUnbakedModel> models = new HashMap<>();

        private UnbakedModel resolve(Identifier id) {
            try {
                // Match the production resolver's non-JSON type, then let the
                // real vanilla setParents enforce its parent compatibility rule.
                if (rawComponentFrom.invoke(null, id) != null) return new RawParent();
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not inspect raw component " + id, exception);
            }
            return json(id);
        }

        private JsonUnbakedModel json(Identifier id) {
            return models.computeIfAbsent(id, key -> {
                SourceModel source = SOURCES.get(key);
                try {
                    JsonUnbakedModel model;
                    if (source != null) {
                        try (var reader = Files.newBufferedReader(source.path())) {
                            model = JsonUnbakedModel.deserialize(reader);
                        }
                    } else {
                        String resource = "assets/" + key.getNamespace() + "/models/" + key.getPath() + ".json";
                        var stream = RawModelParentCompatibilityTest.class.getClassLoader().getResourceAsStream(resource);
                        assertNotNull(stream, "Missing inherited model " + key);
                        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                            model = JsonUnbakedModel.deserialize(reader);
                        }
                    }
                    model.id = key.toString();
                    return model;
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    private static final class RawParent implements UnbakedModel {
        @Override
        public Collection<Identifier> getModelDependencies() { return List.of(); }

        @Override
        public void setParents(Function<Identifier, UnbakedModel> modelLoader) { }

        @Override
        public BakedModel bake(Baker baker, Function<SpriteIdentifier, Sprite> textureGetter,
                               ModelBakeSettings settings, Identifier id) {
            throw new UnsupportedOperationException("Parent compatibility tests do not bake geometry");
        }
    }
}
