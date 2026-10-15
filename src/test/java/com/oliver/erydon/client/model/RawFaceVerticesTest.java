package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawFaceVerticesTest {
    private static final Identifier SOURCE = new Identifier("erydon", "authoring_models/face_vertices_test.json");

    @Test
    void trapezoidUsesItsOwnVerticesAndTheirDefaultWorldUvMapping() {
        JsonObject model = model();
        var snapshot = surfaces(model).get(0);
        float[][] points = {{2, 3, 10}, {12, 4, 10}, {10, 9, 10}, {4, 8, 10}};
        for (int vertex = 0; vertex < 4; vertex++) {
            float[] point = points[vertex];
            assertArrayEquals(new float[]{point[0] / 16, point[1] / 16, point[2] / 16},
                    snapshot.vertexPositions()[vertex]);
            assertArrayEquals(new float[]{point[0], 16 - point[1]}, snapshot.sourceUvs()[vertex]);
        }
        assertEquals("south", snapshot.faceDirection());
    }

    @Test
    void independentVerticesRotateThroughElementThenGroupWithoutAccumulatingTransforms() {
        JsonObject model = model();
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        element.add("origin", JsonParser.parseString("[0,0,0]"));
        element.add("rotation", JsonParser.parseString("[0,0,90]"));
        model.add("groups", JsonParser.parseString("[{\"origin\":[0,0,0],\"rotation\":[0,90,0],\"children\":[0]}]"));
        var first = surfaces(model).get(0);
        var second = surfaces(model).get(0);
        float[][] expected = {{10, 2, 3}, {10, 12, 4}, {10, 10, 9}, {10, 4, 8}};
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(expected[vertex][axis] / 16, first.vertexPositions()[vertex][axis], 0.000001);
            }
            assertArrayEquals(first.vertexPositions()[vertex], second.vertexPositions()[vertex]);
        }
        assertEquals("east", first.faceDirection());
    }

    @Test
    void mitredOverhangKeepsItsAreaAndBoundsWithNormalSeparationAndAtlasSplitting() {
        JsonObject model = model();
        face(model).add("erydon_vertices", JsonParser.parseString("[[-2,-1,16.45],[18,2,16.45],[15,17,16.45],[0,15,16.45]]"));
        face(model).addProperty("erydon_normal_offset", 0.01);
        model.addProperty("erydon_split_world_uv", true);
        var snapshots = surfaces(model);
        assertTrue(snapshots.size() > 1);
        double area = 0;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (var snapshot : snapshots) {
            float[][] positions = snapshot.vertexPositions();
            area += triangleArea(positions[0], positions[1], positions[2]);
            area += triangleArea(positions[0], positions[2], positions[3]);
            for (int vertex = 0; vertex < 4; vertex++) {
                float[] uv = snapshot.sourceUvs()[vertex];
                assertTrue(uv[0] >= 0 && uv[0] <= 16);
                assertTrue(uv[1] >= 0 && uv[1] <= 16);
                float[] position = positions[vertex];
                assertEquals(16.46 / 16, position[2], 0.000001);
                double cellsU = (position[0] * 16 - uv[0]) / 16;
                double cellsV = (16 - position[1] * 16 - uv[1]) / 16;
                assertEquals(Math.rint(cellsU), cellsU, 0.000001);
                assertEquals(Math.rint(cellsV), cellsV, 0.000001);
                minX = Math.min(minX, position[0]); maxX = Math.max(maxX, position[0]);
                minY = Math.min(minY, position[1]); maxY = Math.max(maxY, position[1]);
            }
        }
        assertEquals(272.5 / 256, area, 0.00001);
        assertEquals(-2.0 / 16, minX, 0.000001);
        assertEquals(18.0 / 16, maxX, 0.000001);
        assertEquals(-1.0 / 16, minY, 0.000001);
        assertEquals(17.0 / 16, maxY, 0.000001);
    }

    @Test
    void malformedVertexOverridesFailBeforeBaking() {
        List<JsonElement> invalid = new ArrayList<>();
        for (String json : List.of("null", "true", "[]", "[[0,0,0],[1,0,0],[1,1,0]]",
                "[[0,0,0],[1,0,0],[1,1,0],[0,1,0],[2,2,0]]",
                "[[0,0],[1,0,0],[1,1,0],[0,1,0]]",
                "[[0,0,0,0],[1,0,0],[1,1,0],[0,1,0]]",
                "[[\"0\",0,0],[1,0,0],[1,1,0],[0,1,0]]",
                "[[false,0,0],[1,0,0],[1,1,0],[0,1,0]]")) {
            invalid.add(JsonParser.parseString(json));
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 1e100}) {
            JsonArray vertices = face(model()).getAsJsonArray("erydon_vertices").deepCopy();
            vertices.get(0).getAsJsonArray().set(0, new JsonPrimitive(value));
            invalid.add(vertices);
        }
        for (JsonElement vertices : invalid) {
            JsonObject model = model();
            face(model).add("erydon_vertices", vertices);
            assertThrows(IllegalArgumentException.class, () -> surfaces(model), vertices.toString());
        }
    }

    @Test
    void geometryIdentityIncludesTheActualFaceVertices() {
        JsonObject model = model();
        String first = ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model);
        face(model).getAsJsonArray("erydon_vertices").get(0).getAsJsonArray().set(0, new JsonPrimitive(3));
        assertNotEquals(first, ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
        face(model).remove("erydon_vertices");
        assertEquals(SOURCE.toString(), ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
    }

    private static JsonObject model() {
        return JsonParser.parseString("""
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"south":{"texture":"#stone",
                    "erydon_vertices":[[2,3,10],[12,4,10],[10,9,10],[4,8,10]]}}}]}
                """).getAsJsonObject();
    }

    private static JsonObject face(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("south");
    }

    private static List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces(JsonObject model) {
        return ErydonRawModelLoadingPlugin.surfaceSnapshots("face_vertices_test", SOURCE, model);
    }

    private static double triangleArea(float[] first, float[] second, float[] third) {
        double ax = second[0] - first[0], ay = second[1] - first[1], az = second[2] - first[2];
        double bx = third[0] - first[0], by = third[1] - first[1], bz = third[2] - first[2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        return Math.sqrt(nx * nx + ny * ny + nz * nz) / 2;
    }
}
