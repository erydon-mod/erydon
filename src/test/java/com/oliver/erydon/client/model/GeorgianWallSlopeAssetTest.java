package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallSlopeAssetTest {
    private static final String ROOT =
            "assets/erydon/authoring_models/block/wall/georgian/wall_georgian_";
    private static final List<Expectation> EXPECTATIONS = List.of(
            new Expectation("27_upper", 106, 312, 26.565D, 4, 72, 188, Map.of(
                    "down", 40, "east", 58, "north", 54,
                    "south", 54, "up", 56, "west", 50
            )),
            new Expectation("27_lower", 106, 636, 26.565D, 4, 72, 432, Map.of(
                    "down", 106, "east", 106, "north", 106,
                    "south", 106, "up", 106, "west", 106
            )),
            new Expectation("27_lower_onramp", 110, 347, 26.565D, 4, 72, 202, Map.of(
                    "down", 39, "east", 65, "north", 55,
                    "south", 69, "up", 58, "west", 61
            )),
            new Expectation("27_upper_offramp", 111, 348, 26.565D, 4, 72, 202, Map.of(
                    "down", 40, "east", 66, "north", 68,
                    "south", 53, "up", 59, "west", 62
            )),
            new Expectation("45", 106, 318, 45.0D, 4, 0, 0, Map.of(
                    "down", 43, "east", 60, "north", 54,
                    "south", 55, "up", 56, "west", 50
            )),
            new Expectation("45_onramp", 110, 350, 45.0D, 4, 0, 0, Map.of(
                    "down", 41, "east", 66, "north", 54,
                    "south", 70, "up", 58, "west", 61
            )),
            new Expectation("45_offramp", 110, 348, 45.0D, 4, 0, 0, Map.of(
                    "down", 41, "east", 66, "north", 68,
                    "south", 54, "up", 58, "west", 61
            ))
    );

    @Test
    void importedModelsPreserveTheAuthoredFacesAndUseSynapheiaSeedUvs() throws IOException {
        for (Expectation expectation : EXPECTATIONS) {
            assertCanonical(read(expectation.name()), expectation);
        }
    }

    @Test
    void loaderSelectsEveryProductionGeorgianWallNamingShape() {
        assertTrue(GeorgianWallSlopeModelLoadingPlugin.isGeorgianWall("aganite_wall_georgian"));
        assertTrue(GeorgianWallSlopeModelLoadingPlugin.isGeorgianWall("aganite_aged_wall_georgian"));
        assertTrue(GeorgianWallSlopeModelLoadingPlugin.isGeorgianWall(
                "calacattum_portorium_weave_bronze_wall_georgian"));
        assertFalse(GeorgianWallSlopeModelLoadingPlugin.isGeorgianWall("aganite_wall"));
        assertFalse(GeorgianWallSlopeModelLoadingPlugin.isGeorgianWall("wall_georgian_icon"));
    }

    @Test
    void transitionModelsKeepTheirAuthoredHandoffs() throws IOException {
        JsonArray shallowOnramp = read("27_lower_onramp").getAsJsonArray("elements");
        assertVector(
                shallowOnramp.get(0).getAsJsonObject().getAsJsonArray("to"),
                11.25D, 5.5D, 17.389D
        );

        JsonArray shallowOfframp = read("27_upper_offramp").getAsJsonArray("elements");
        JsonObject shallowOfframpHandoff = findElementStartingAt(
                shallowOfframp,
                4.75D, 0.0D, -2.5D
        );
        assertVector(
                shallowOfframpHandoff.getAsJsonArray("from"),
                4.75D, 0.0D, -2.5D
        );
        assertVector(
                shallowOfframpHandoff.getAsJsonArray("to"),
                11.25D, 1.5D, 0.179D
        );
        JsonObject shallowOfframpFiller = findElementStartingAt(
                shallowOfframp,
                6.26831D, -4.25D, 15.65132D
        );
        assertVector(
                shallowOfframpFiller.getAsJsonArray("from"),
                6.26831D, -4.25D, 15.65132D
        );
        assertVector(
                shallowOfframpFiller.getAsJsonArray("to"),
                7.51631D, 5.0D, 16.24806D
        );

        JsonArray steepOnramp = read("45_onramp").getAsJsonArray("elements");
        assertVector(
                steepOnramp.get(1).getAsJsonObject().getAsJsonArray("from"),
                4.75D, 0.0D, 5.0D
        );
        JsonArray steepOfframp = read("45_offramp").getAsJsonArray("elements");
        assertVector(
                steepOfframp.get(1).getAsJsonObject().getAsJsonArray("to"),
                11.25D, 1.5D, 6.52D
        );
    }

    @Test
    void steepAssetsRemainAtTheirAuthoredHeight() throws IOException {
        JsonArray steep = read("45").getAsJsonArray("elements");
        assertVector(
                steep.get(0).getAsJsonObject().getAsJsonArray("from"),
                4.75D, -5.5D, -2.814D
        );
    }

    private static void assertCanonical(JsonObject model, Expectation expectation) {
        JsonArray elements = model.getAsJsonArray("elements");
        assertEquals(expectation.elements(), elements.size(), expectation.name());
        int faces = 0;
        int slopeRotations = 0;
        int circularDetails = 0;
        int circularDetailFaces = 0;
        Map<String, Integer> directions = new HashMap<>();
        for (JsonElement elementValue : elements) {
            JsonObject element = elementValue.getAsJsonObject();
            boolean circularDetail = element.has("name")
                    && "circular detail".equals(element.get("name").getAsString());
            if (circularDetail) {
                circularDetails++;
            }
            JsonObject rotation = element.getAsJsonObject("rotation");
            if (rotation != null
                    && "x".equals(rotation.get("axis").getAsString())
                    && Math.abs(rotation.get("angle").getAsDouble()
                    - expectation.slopeAngle()) < 0.000001D) {
                slopeRotations++;
            }
            for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                faces++;
                if (circularDetail) {
                    circularDetailFaces++;
                }
                directions.merge(faceEntry.getKey(), 1, Integer::sum);
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                assertFalse(face.has("uv"), expectation.name());
                assertEquals("#wall", face.get("texture").getAsString(), expectation.name());
            }
        }
        assertEquals(expectation.faces(), faces, expectation.name());
        assertEquals(expectation.slopeRotations(), slopeRotations, expectation.name());
        assertEquals(expectation.circularDetails(), circularDetails, expectation.name());
        assertEquals(expectation.circularDetailFaces(), circularDetailFaces, expectation.name());
        assertEquals(expectation.directions(), directions, expectation.name());
    }

    private static void assertVector(JsonArray actual, double x, double y, double z) {
        assertEquals(x, actual.get(0).getAsDouble(), 0.000001D);
        assertEquals(y, actual.get(1).getAsDouble(), 0.000001D);
        assertEquals(z, actual.get(2).getAsDouble(), 0.000001D);
    }

    private static JsonObject findElementStartingAt(JsonArray elements,
                                                    double x,
                                                    double y,
                                                    double z) {
        for (JsonElement elementValue : elements) {
            JsonObject element = elementValue.getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            if (from != null
                    && Math.abs(from.get(0).getAsDouble() - x) < 0.000001D
                    && Math.abs(from.get(1).getAsDouble() - y) < 0.000001D
                    && Math.abs(from.get(2).getAsDouble() - z) < 0.000001D) {
                return element;
            }
        }
        throw new AssertionError("Missing element starting at [" + x + ", " + y + ", " + z + "]");
    }

    private static JsonObject read(String name) throws IOException {
        String path = ROOT + name + ".json";
        try (InputStream stream = GeorgianWallSlopeAssetTest.class
                .getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(stream, path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement value = JsonParser.parseReader(reader);
                assertTrue(value.isJsonObject());
                return value.getAsJsonObject();
            }
        }
    }

    private record Expectation(String name,
                               int elements,
                               int faces,
                               double slopeAngle,
                               int slopeRotations,
                               int circularDetails,
                               int circularDetailFaces,
                               Map<String, Integer> directions) {
    }
}
