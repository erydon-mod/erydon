package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import net.minecraft.resource.*;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Small reload-only overlay; original pack files and their resolution are preserved. */
public final class HighPolishSpecularPack implements ResourcePack {
    private static final List<String> HONED = List.of("kelastrion", "latmion", "psamatheon");
    private final Map<Identifier, InputSupplier<InputStream>> resources;

    private HighPolishSpecularPack(Map<Identifier, InputSupplier<InputStream>> resources) {
        this.resources = Map.copyOf(resources);
    }

    public static List<ResourcePack> append(ResourceType type, List<ResourcePack> packs,
                                             HighPolishSettings settings) {
        if (type != ResourceType.CLIENT_RESOURCES || !settings.enabled()) return packs;
        Map<Identifier, InputSupplier<InputStream>> overrides = new HashMap<>();
        for (var language : Map.of("en_us", "Polished", "de_de", "Poliert", "es_es", "Pulido").entrySet()) {
            var labels = new com.google.gson.JsonObject();
            for (String material : HONED) {
                if (settings.enables(material, HighPolishSettings.Finish.PLAIN)) {
                    labels.addProperty("command.erydon.swap.material." + material,
                            Character.toUpperCase(material.charAt(0)) + material.substring(1) + " " + language.getValue());
                }
            }
            if (labels.size() > 0) {
                byte[] bytes = labels.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                overrides.put(new Identifier("erydon", "lang/" + language.getKey() + ".json"),
                        () -> new ByteArrayInputStream(bytes));
            }
        }
        for (String material : HONED) {
            for (String suffix : List.of("", "_herringbone_bronze", "_herringbone_grout")) {
                var finish = suffix.isEmpty() ? HighPolishSettings.Finish.PLAIN : HighPolishSettings.Finish.HERRINGBONE;
                if (!settings.enables(material, finish)) continue;
                String family = material + suffix;
                add(overrides, packs, new Identifier("erydon", "textures/block/" + family + "_block_s.png"));
                for (int tile = 0; tile < 36; tile++) {
                    add(overrides, packs, new Identifier("minecraft", "textures/optifine/ctm/" + family + "/" + tile + "_s.png"));
                }
            }
        }
        if (overrides.isEmpty()) return packs;
        List<ResourcePack> result = new ArrayList<>(packs);
        result.add(new HighPolishSpecularPack(overrides));
        return result;
    }

    private static void add(Map<Identifier, InputSupplier<InputStream>> result, List<ResourcePack> packs, Identifier id) {
        for (int i = packs.size() - 1; i >= 0; i--) {
            var source = packs.get(i).open(ResourceType.CLIENT_RESOURCES, id);
            if (source != null) {
                result.put(id, new PolishedImage(source));
                return;
            }
        }
    }

    static BufferedImage polish(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int color = source.getRGB(x, y);
            int smoothness = (color >>> 16) & 255, metalness = (color >>> 8) & 255;
            // Match the existing polished stone values; leave grout, metal and alpha intact.
            if (smoothness == 175 && metalness == 0) color = (color & 0xFF00FF00) | 0x00FF000A;
            result.setRGB(x, y, color);
        }
        return result;
    }

    private static final class PolishedImage implements InputSupplier<InputStream> {
        private final InputSupplier<InputStream> source;
        private byte[] bytes;
        private PolishedImage(InputSupplier<InputStream> source) { this.source = source; }
        @Override public synchronized InputStream get() throws IOException {
            if (bytes == null) {
                try (InputStream input = source.get(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    BufferedImage image = ImageIO.read(input);
                    if (image == null) throw new IOException("Invalid honed specular image");
                    ImageIO.write(polish(image), "PNG", output);
                    bytes = output.toByteArray();
                }
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    @Override public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        return type == ResourceType.CLIENT_RESOURCES ? resources.get(id) : null;
    }
    @Override public InputSupplier<InputStream> openRoot(String... segments) { return null; }
    @Override public Set<String> getNamespaces(ResourceType type) {
        return type == ResourceType.CLIENT_RESOURCES ? Set.of("erydon", "minecraft") : Set.of();
    }
    @Override public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        if (type != ResourceType.CLIENT_RESOURCES) return;
        resources.forEach((id, input) -> {
            if (id.getNamespace().equals(namespace) && (prefix.isEmpty() || id.getPath().startsWith(prefix + "/"))) consumer.accept(id, input);
        });
    }
    @Override public <T> T parseMetadata(ResourceMetadataReader<T> reader) { return null; }
    @Override public String getName() { return "erydon:high_polish_stone_specular"; }
    @Override public void close() { }
}
