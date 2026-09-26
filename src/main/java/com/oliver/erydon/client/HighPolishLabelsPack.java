package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import net.minecraft.resource.*;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;
import java.io.*;
import java.util.*;

/** Reload-only command labels. Never replaces or decodes specular/normal textures. */
public final class HighPolishLabelsPack implements ResourcePack {
    private final Map<Identifier, InputSupplier<InputStream>> resources;

    private HighPolishLabelsPack(Map<Identifier, InputSupplier<InputStream>> resources) {
        this.resources = Map.copyOf(resources);
    }

    public static List<ResourcePack> append(ResourceType type, List<ResourcePack> packs,
                                             HighPolishSettings settings) {
        if (type != ResourceType.CLIENT_RESOURCES) return packs;
        Map<Identifier, InputSupplier<InputStream>> overrides = new HashMap<>();
        for (var language : Map.of("en_us", List.of("Honed", "Polished", "Mirror"),
                "de_de", List.of("Geschliffen", "Poliert", "Spiegelglanz"),
                "es_es", List.of("Apomazado", "Pulido", "Espejo")).entrySet()) {
            var labels = new com.google.gson.JsonObject();
            for (String material : HighPolishSettings.MATERIALS) {
                var level = settings.level(material, HighPolishSettings.Finish.PLAIN);
                labels.addProperty("command.erydon.swap.material." + material,
                        Character.toUpperCase(material.charAt(0)) + material.substring(1) + " "
                                + language.getValue().get(level.ordinal()));
            }
            byte[] bytes = labels.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            overrides.put(new Identifier("erydon", "lang/" + language.getKey() + ".json"),
                    () -> new ByteArrayInputStream(bytes));
        }
        List<ResourcePack> result = new ArrayList<>(packs);
        result.add(new HighPolishLabelsPack(overrides));
        return result;
    }

    @Override public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        return type == ResourceType.CLIENT_RESOURCES ? resources.get(id) : null;
    }
    @Override public InputSupplier<InputStream> openRoot(String... segments) { return null; }
    @Override public Set<String> getNamespaces(ResourceType type) {
        return type == ResourceType.CLIENT_RESOURCES ? Set.of("erydon") : Set.of();
    }
    @Override public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        if (type != ResourceType.CLIENT_RESOURCES) return;
        resources.forEach((id, input) -> {
            if (id.getNamespace().equals(namespace) && (prefix.isEmpty() || id.getPath().startsWith(prefix + "/"))) consumer.accept(id, input);
        });
    }
    @Override public <T> T parseMetadata(ResourceMetadataReader<T> reader) { return null; }
    @Override public String getName() { return "erydon:stone_finish_labels"; }
    @Override public void close() { }
}
