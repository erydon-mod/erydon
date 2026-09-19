package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GeorgianWindowGlassTest {
    @Test
    void everyAuthoredPaneHasExactlyOneCoatedBroadFaceIncludingOpenFanlights() throws Exception {
        Path root = Path.of("src/main/resources/assets/erydon/models/block/window/french_georgian");
        int panes = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("window_french_georgian_")).toList()) {
                String name = file.getFileName().toString();
                if (name.contains("icon")) continue;
                var model = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!model.has("elements")) continue;
                for (var raw : model.getAsJsonArray("elements")) {
                    var element = raw.getAsJsonObject();
                    var faces = element.getAsJsonObject("faces");
                    boolean pane = faces.entrySet().stream().anyMatch(e ->
                            e.getValue().getAsJsonObject().get("texture").getAsString().equals("#pane"));
                    if (!pane) continue;
                    panes++;
                    var from = element.getAsJsonArray("from");
                    var to = element.getAsJsonArray("to");
                    float depth = (to.get(2).getAsFloat() - from.get(2).getAsFloat()) / 16;
                    boolean open = name.contains("_open_");
                    boolean wing = open && depth > 0.25F;
                    Direction authoredOutside = wing ? (name.contains("_lh") ? Direction.WEST : Direction.EAST) : Direction.NORTH;
                    assertTrue(faces.has(authoredOutside.getName()), name);
                    assertEquals(0, faces.getAsJsonObject(authoredOutside.getName()).get("tintindex").getAsInt(), name);
                    Direction worldOutside = authoredOutside;
                    Direction facing = Direction.NORTH;
                    for (int rotation = 0; rotation < 4; rotation++) {
                        Direction selected = open
                                ? WindowFrenchGeorgianBakedModel.outsideForPane(facing, name.contains("_lh"), depth) : facing;
                        assertEquals(worldOutside, selected, name + " " + facing);
                        assertNotEquals(worldOutside.getOpposite(), selected);
                        facing = facing.rotateYClockwise();
                        worldOutside = worldOutside.rotateYClockwise();
                    }
                }
            }
        }
        assertTrue(panes >= 20, "Cover fixed and hinged panes from the actual templates");
    }
}
