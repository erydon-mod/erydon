package com.oliver.erydon.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.shape.VoxelShape;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Shared authored detailing is added to the existing alcove profile once per state. */
final class GeorgianAlcoveInteractionShapes {
    private static final String RESOURCE = "assets/erydon/interaction_shapes/alcove_georgian.json";
    private static final VoxelShape[] DETAILS = load();

    private GeorgianAlcoveInteractionShapes() { }

    static VoxelShape details(AlcoveBlock.AlcovePart part, AlcoveBlock.AlcoveSpan span) {
        return DETAILS[part.ordinal() * AlcoveBlock.AlcoveSpan.values().length + span.ordinal()];
    }

    private static VoxelShape[] load() {
        var stream = GeorgianAlcoveInteractionShapes.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) throw new IllegalStateException("Missing authored Georgian alcove shapes " + RESOURCE);
        VoxelShape[] shapes = new VoxelShape[AlcoveBlock.AlcovePart.values().length * AlcoveBlock.AlcoveSpan.values().length];
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            if (document.get("schema").getAsInt() != 1 || document.get("units_per_block").getAsInt() != 16
                    || !document.get("additive_to_legacy").getAsBoolean()) {
                throw new IllegalStateException("Unsupported Georgian alcove shape contract " + RESOURCE);
            }
            JsonObject parts = document.getAsJsonObject("parts");
            Map<JsonArray, VoxelShape> sharedShapes = new HashMap<>();
            for (var part : AlcoveBlock.AlcovePart.values()) {
                JsonObject spans = parts.getAsJsonObject(part.asString());
                for (var span : AlcoveBlock.AlcoveSpan.values()) {
                    JsonArray authoredBoxes = spans.getAsJsonArray(span.asString());
                    VoxelShape shared = sharedShapes.get(authoredBoxes);
                    if (shared != null) {
                        shapes[part.ordinal() * AlcoveBlock.AlcoveSpan.values().length + span.ordinal()] = shared;
                        continue;
                    }
                    var boxes = new ArrayList<double[]>(authoredBoxes.size());
                    for (var value : authoredBoxes) {
                        var box = value.getAsJsonArray();
                        if (box.size() != 6) throw new IllegalStateException("Invalid Georgian alcove shape box");
                        double[] coordinates = new double[6];
                        for (int i = 0; i < coordinates.length; i++) {
                            coordinates[i] = box.get(i).getAsDouble() / 16.0;
                            if (!Double.isFinite(coordinates[i])) throw new IllegalStateException("Non-finite Georgian alcove shape box");
                        }
                        if (coordinates[0] >= coordinates[3] || coordinates[1] >= coordinates[4]
                                || coordinates[2] >= coordinates[5]) {
                            throw new IllegalStateException("Empty Georgian alcove shape box");
                        }
                        boxes.add(coordinates);
                    }
                    VoxelShape shape = AlcoveVoxelShapes.fromBoxes(boxes);
                    sharedShapes.put(authoredBoxes, shape);
                    shapes[part.ordinal() * AlcoveBlock.AlcoveSpan.values().length + span.ordinal()] = shape;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read authored Georgian alcove shapes " + RESOURCE, exception);
        }
        return shapes;
    }
}
