package com.oliver.erydon.block;

import com.google.gson.JsonParser;
import net.minecraft.util.shape.VoxelShape;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authored component boxes are loaded once; block and wide shape caches reuse them. */
final class RomanesqueArchInteractionShapes {
    private static final String RESOURCE = "assets/erydon/interaction_shapes/arch_romanesque.json";
    private static final Map<String, List<double[]>> COMPONENTS = load();

    private RomanesqueArchInteractionShapes() { }

    static VoxelShape north(ArchRomanesqueBlock.Arrangement arrangement) {
        var boxes = new ArrayList<double[]>();
        String corner = arrangement.authoredCornerSuffix();
        add(boxes, corner, arrangement.cornerFlip() ? 2 : 1);
        add(boxes, arrangement.authoredSideSuffix(arrangement.sideL()), 1);
        add(boxes, arrangement.authoredSideSuffix(arrangement.sideR()), 2);
        add(boxes, upper(arrangement.upperL()), 1);
        add(boxes, upper(arrangement.upperR()), 2);
        if (arrangement.columnL()) add(boxes, column(arrangement.upperL()), 1);
        if (arrangement.columnR()) add(boxes, column(arrangement.upperR()), 2);
        if (arrangement.plinthL()) add(boxes, "plinth", 1);
        if (arrangement.plinthR()) add(boxes, "plinth", 2);
        if (arrangement.hasTopLarge()) add(boxes, "top_large", 0);
        return AlcoveVoxelShapes.fromBoxes(boxes);
    }

    private static String upper(ArchRomanesqueBlock.Upper upper) {
        return switch (upper) {
            case MEDIUM -> "side_medium_upper";
            case LARGE -> "side_large_upper";
            case NONE -> null;
        };
    }

    private static String column(ArchRomanesqueBlock.Upper upper) {
        return upper == ArchRomanesqueBlock.Upper.NONE ? "side_column" : "side_column_upper";
    }

    private static void add(List<double[]> target, String suffix, int transform) {
        if (suffix == null) return;
        List<double[]> source = COMPONENTS.get(suffix);
        if (source == null) throw new IllegalStateException("Missing authored Romanesque component " + suffix);
        for (double[] box : source) {
            if (transform == 0) target.add(box);
            else target.add(new double[]{transform == 1 ? 1-box[3] : box[0], box[1], 1-box[5],
                    transform == 1 ? 1-box[0] : box[3], box[4], 1-box[2]});
        }
    }

    private static Map<String, List<double[]>> load() {
        var stream = RomanesqueArchInteractionShapes.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) throw new IllegalStateException("Missing authored Romanesque shapes " + RESOURCE);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var document = JsonParser.parseReader(reader).getAsJsonObject();
            if (document.get("schema").getAsInt() != 1 || document.get("units_per_block").getAsInt() != 16) {
                throw new IllegalStateException("Unsupported Romanesque shape contract");
            }
            Map<String, List<double[]>> result = new HashMap<>();
            for (var entry : document.getAsJsonObject("components").entrySet()) {
                var boxes = new ArrayList<double[]>();
                for (var value : entry.getValue().getAsJsonArray()) {
                    var encoded = value.getAsJsonArray();
                    if (encoded.size() != 6) throw new IllegalStateException("Invalid Romanesque shape box");
                    double[] box = new double[6];
                    for (int i = 0; i < 6; i++) {
                        box[i] = encoded.get(i).getAsDouble() / 16;
                        if (!Double.isFinite(box[i])) throw new IllegalStateException("Non-finite Romanesque shape box");
                    }
                    if (box[0] >= box[3] || box[1] >= box[4] || box[2] >= box[5]) {
                        throw new IllegalStateException("Empty Romanesque shape box");
                    }
                    boxes.add(box);
                }
                result.put(entry.getKey(), List.copyOf(boxes));
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Romanesque shape boxes", exception);
        }
    }
}
