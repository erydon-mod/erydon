package com.oliver.erydon.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes bounded all-material surface dumps for the supplied comparison tool. */
public final class SharedGeometryParityDump {
    private static final Pattern COMPONENT = Pattern.compile(
            "^(.+)_column_gothic_(plinth|base|pillar|capital)(_aged)?\\.json$");
    private static final Set<String> EXPECTED_PARTS = Set.of(
            "plinth", "base", "pillar", "capital");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private SharedGeometryParityDump() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected resource root, baseline dump, and prototype dump paths.");
        }

        Path resourceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path baseline = Path.of(args[1]).toAbsolutePath().normalize();
        Path prototype = Path.of(args[2]).toAbsolutePath().normalize();
        Path componentRoot = resourceRoot.resolve("assets/erydon/models/block/column/gothic");
        Path authoringRoot = resourceRoot.resolve(
                "assets/erydon/authoring_models/block/column/gothic");
        Files.createDirectories(baseline.getParent());
        Files.createDirectories(prototype.getParent());

        Map<String, List<ComponentModel>> byBlock = new LinkedHashMap<>();
        try (var paths = Files.list(componentRoot)) {
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> addComponent(byBlock, path));
        }

        if (byBlock.size() != 54) {
            throw new IllegalStateException(
                    "Expected 54 Gothic-column material variants, found " + byBlock.size());
        }
        for (Map.Entry<String, List<ComponentModel>> entry : byBlock.entrySet()) {
            Set<String> parts = new LinkedHashSet<>();
            entry.getValue().forEach(component -> parts.add(component.part));
            if (!parts.equals(EXPECTED_PARTS) || entry.getValue().size() != EXPECTED_PARTS.size()) {
                throw new IllegalStateException(
                        entry.getKey() + " does not have exactly the four Gothic components: " + parts);
            }
        }
        validateItemModels(resourceRoot, byBlock);

        Map<String, List<ErydonRawModelLoadingPlugin.SurfaceSnapshot>> surfacesByPart =
                new LinkedHashMap<>();
        for (String part : List.of("plinth", "base", "pillar", "capital")) {
            String fileName = "column_gothic_" + part + ".json";
            Path source = authoringRoot.resolve(fileName);
            JsonElement root;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader);
            }
            surfacesByPart.put(
                    part,
                    ErydonRawModelLoadingPlugin.surfaceSnapshots(
                            "column_gothic/" + part,
                            new Identifier(
                                    "erydon",
                                    "authoring_models/block/column/gothic/" + fileName
                            ),
                            root
                    )
            );
        }

        long records = 0L;
        try (BufferedWriter baselineWriter = Files.newBufferedWriter(
                baseline, StandardCharsets.UTF_8);
             BufferedWriter prototypeWriter = Files.newBufferedWriter(
                     prototype, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, List<ComponentModel>> blockEntry : byBlock.entrySet()) {
                List<ComponentModel> components = new ArrayList<>(blockEntry.getValue());
                components.sort(Comparator.comparingInt(component -> partOrder(component.part)));
                for (ComponentModel component : components) {
                    List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces =
                            surfacesByPart.get(component.part);
                    for (int surfaceIndex = 0; surfaceIndex < surfaces.size(); surfaceIndex++) {
                        ErydonRawModelLoadingPlugin.SurfaceSnapshot surface =
                                surfaces.get(surfaceIndex);
                        Map<String, Object> common = record(
                                component,
                                surface,
                                surfaceIndex
                        );

                        Map<String, Object> baselineRecord = new LinkedHashMap<>(common);
                        baselineRecord.put(
                                "cache_backing_object_identifier",
                                "baseline:" + component.modelIdentifier
                        );
                        baselineWriter.write(GSON.toJson(baselineRecord));
                        baselineWriter.newLine();

                        Map<String, Object> prototypeRecord = new LinkedHashMap<>(common);
                        prototypeRecord.put(
                                "cache_backing_object_identifier",
                                "shared:" + component.geometryKey
                        );
                        prototypeWriter.write(GSON.toJson(prototypeRecord));
                        prototypeWriter.newLine();
                        records++;
                    }
                }
            }
        }

        System.out.println("Shared-geometry parity dumps written: material variants="
                + byBlock.size() + ", item models=" + byBlock.size()
                + ", records per mode=" + records + ".");
    }

    private static void validateItemModels(Path resourceRoot,
                                           Map<String, List<ComponentModel>> byBlock) throws IOException {
        Path itemRoot = resourceRoot.resolve("assets/erydon/models/item");
        for (Map.Entry<String, List<ComponentModel>> entry : byBlock.entrySet()) {
            String blockPath = entry.getKey().substring("erydon:".length());
            Path itemPath = itemRoot.resolve(blockPath + ".json");
            JsonElement parsed;
            try (Reader reader = Files.newBufferedReader(itemPath, StandardCharsets.UTF_8)) {
                parsed = JsonParser.parseReader(reader);
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Item model is not an object: " + itemPath);
            }
            var object = parsed.getAsJsonObject();
            String parent = object.has("parent") ? object.get("parent").getAsString() : "";
            if (!"erydon:block/column/gothic/column_gothic_item".equals(parent)) {
                throw new IllegalStateException(
                        "Unexpected Gothic-column item parent for " + blockPath + ": " + parent);
            }
            String expectedTexture = entry.getValue().get(0).textureId;
            String actualTexture = object.has("textures")
                    && object.getAsJsonObject("textures").has("all")
                    ? object.getAsJsonObject("textures").get("all").getAsString()
                    : "";
            if (!expectedTexture.equals(actualTexture)) {
                throw new IllegalStateException(
                        "Unexpected Gothic-column item texture for " + blockPath
                                + ": expected " + expectedTexture + ", found " + actualTexture);
            }
        }
    }

    private static Map<String, Object> record(
            ComponentModel component,
            ErydonRawModelLoadingPlugin.SurfaceSnapshot surface,
            int surfaceIndex) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema_version", 1);
        record.put("event", "emitted_surface_fingerprint");
        record.put("block_id", component.blockId);
        record.put("blockstate", Map.of(
                "base", "all_equivalent_for_gothic",
                "capital", "all_equivalent_for_gothic",
                "part", component.part
        ));
        record.put("model_identifier", component.modelIdentifier);
        record.put("geometry_key", component.geometryKey);
        record.put("material_binding", Map.of(
                "particle", component.textureId,
                "stone", component.textureId
        ));
        record.put("primitive", "quad");
        record.put("surface_index", surfaceIndex);
        record.put("vertex_positions", surface.vertexPositions());
        record.put("winding", List.of(0, 1, 2, 3));
        record.put("face_direction", surface.faceDirection());
        record.put("source_uvs", surface.sourceUvs());
        record.put("final_uvs", surface.normalizedUvs());
        record.put("sprite_identifier", component.textureId);
        record.put("cull_face", surface.cullFace());
        record.put("tint_index", -1);
        record.put("render_material", "fabric-default");
        record.put("render_layer", "solid");
        record.put("ao_intent", true);
        record.put("diffuse_intent", true);
        return record;
    }

    private static void addComponent(Map<String, List<ComponentModel>> byBlock, Path path) {
        Matcher matcher = COMPONENT.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return;
        }
        String material = matcher.group(1);
        String part = matcher.group(2);
        boolean aged = matcher.group(3) != null;
        String blockPath = aged
                ? material + "_aged_column_gothic"
                : material + "_column_gothic";
        String modelStem = path.getFileName().toString().replaceFirst("\\.json$", "");
        String modelIdentifier = "erydon:block/column/gothic/" + modelStem;
        String geometryKey = "erydon:authoring_models/block/column/gothic/column_gothic_"
                + part + ".json";
        String textureId = "erydon:block/" + material + "_block" + (aged ? "_aged" : "");
        byBlock.computeIfAbsent("erydon:" + blockPath, ignored -> new ArrayList<>())
                .add(new ComponentModel(
                        "erydon:" + blockPath,
                        part,
                        modelIdentifier,
                        geometryKey,
                        textureId
                ));
    }

    private static int partOrder(String part) {
        return switch (part) {
            case "plinth" -> 0;
            case "base" -> 1;
            case "pillar" -> 2;
            case "capital" -> 3;
            default -> throw new IllegalArgumentException("Unexpected part " + part);
        };
    }

    private record ComponentModel(String blockId,
                                  String part,
                                  String modelIdentifier,
                                  String geometryKey,
                                  String textureId) {
    }
}
