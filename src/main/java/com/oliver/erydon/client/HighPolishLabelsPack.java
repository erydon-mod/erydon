package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import net.minecraft.resource.*;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;
import java.io.*;
import java.util.*;

/** Reload-only stone and command labels. Never inspects textures, registries or worlds. */
public final class HighPolishLabelsPack implements ResourcePack {
    private final Map<Identifier, InputSupplier<InputStream>> resources;

    private HighPolishLabelsPack(Map<Identifier, InputSupplier<InputStream>> resources) {
        this.resources = Map.copyOf(resources);
    }

    public static List<ResourcePack> append(ResourceType type, List<ResourcePack> packs,
                                             HighPolishSettings settings) {
        if (type != ResourceType.CLIENT_RESOURCES) return packs;
        Map<Identifier, InputSupplier<InputStream>> overrides = new HashMap<>();
        for (var language : Map.of("en_us", List.of("Honed", "Polished", "Ganosis"),
                "de_de", List.of("Geschliffen", "Poliert", "Ganosis"),
                "es_es", List.of("Apomazado", "Pulido", "Ganosis")).entrySet()) {
            var labels = new com.google.gson.JsonObject();
            Bundled.LANGUAGES.get(language.getKey()).forEach((key, name) -> {
                if (!key.startsWith("block.erydon.") && !key.startsWith("item.erydon.")) return;
                String path = key.substring(key.indexOf("erydon.") + "erydon.".length());
                if (!PolishedStoneMaterials.includes("erydon", path)) return;
                String label = language.getValue().get(PolishedStoneMaterials.level(settings, path).ordinal());
                labels.addProperty(key, finishedName(name, path, label));
            });
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

    static String finishedName(String original, String path, String label) {
        // Only a stone-finish word immediately after the known material is replaceable.
        // In particular, "Bronze Polished" and other authored metal descriptors stay intact.
        String material = path.substring(0, path.indexOf('_'));
        String prefix = Character.toUpperCase(material.charAt(0)) + material.substring(1) + " ";
        if (original.startsWith(prefix)) {
            String rest = original.substring(prefix.length());
            for (String old : List.of("Honed", "Polished", "Mirror", "Ganosis", "Geschliffen", "Poliert",
                    "Spiegelglanz", "Apomazado", "Pulido", "Espejo")) {
                if (rest.startsWith(old + " ")) { original = prefix + rest.substring(old.length() + 1); break; }
            }
        }
        return original + " (" + label + ")";
    }

    private static final class Bundled {
        private static final Map<String, Map<String, String>> LANGUAGES = read();
        private static Map<String, Map<String, String>> read() {
            Map<String, Map<String, String>> result = new HashMap<>();
            for (String language : List.of("en_us", "de_de", "es_es")) {
                String path = "/assets/erydon/lang/" + language + ".json";
                try (InputStream input = HighPolishLabelsPack.class.getResourceAsStream(path)) {
                    if (input == null) throw new IOException("Missing bundled language " + path);
                    var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(input,
                            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    Map<String, String> names = new HashMap<>();
                    json.entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsString()));
                    result.put(language, Map.copyOf(names));
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            return Map.copyOf(result);
        }
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
