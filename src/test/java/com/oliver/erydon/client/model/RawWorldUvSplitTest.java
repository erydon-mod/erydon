package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawWorldUvSplitTest {
    private static final Identifier SOURCE = new Identifier("erydon", "authoring_models/world_uv_split_test.json");

    @Test
    void boundaryCrossingFaceSplitsIntoAtlasSafeCellsWithoutClampingItsOverhang() {
        JsonObject model = crossingModel();
        var before = surfaces(model);
        assertEquals(1, before.size());
        model.addProperty("erydon_split_world_uv", true);
        var after = surfaces(model);
        assertEquals(9, after.size());
        assertEquals(area(before), area(after), 0.000001);
        assertArrayEquals(bounds(before), bounds(after), 0.000001F);
        assertAtlasSafe(after);
        for (var surface : after) {
            for (int vertex = 0; vertex < 4; vertex++) {
                double sourceU = surface.vertexPositions()[vertex][0] * 16;
                double sourceV = 16 - surface.vertexPositions()[vertex][1] * 16;
                assertWholeCellDifference(sourceU, surface.sourceUvs()[vertex][0]);
                assertWholeCellDifference(sourceV, surface.sourceUvs()[vertex][1]);
                assertEquals(16.75 / 16, surface.vertexPositions()[vertex][2], 0.000001);
            }
        }
    }

    @Test
    void rotatedFaceRetainsItsAreaWindingAndBoundsAcrossBothUvAxes() {
        JsonObject model = crossingModel();
        model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .add("rotation", JsonParser.parseString("[7,13,23]"));
        var before = surfaces(model);
        model.addProperty("erydon_split_world_uv", true);
        var after = surfaces(model);
        assertTrue(after.size() > 1);
        assertAtlasSafe(after);
        assertEquals(area(before), area(after), 0.00001);
        assertArrayEquals(bounds(before), bounds(after), 0.000001F);
        double[] sourceNormal = normal(before.get(0).vertexPositions());
        for (var surface : after) {
            double[] splitNormal = normal(surface.vertexPositions());
            assertTrue(sourceNormal[0] * splitNormal[0] + sourceNormal[1] * splitNormal[1]
                    + sourceNormal[2] * splitNormal[2] > 0);
            assertEquals(before.get(0).faceDirection(), surface.faceDirection());
        }
    }

    @Test
    void normalSeparationAndAuthoredUvPhaseOffsetAreAppliedBeforeCellSplitting() {
        JsonObject model = crossingModel();
        JsonObject face = face(model);
        face.addProperty("erydon_normal_offset", 0.02);
        face.add("erydon_uv_offset", JsonParser.parseString("[2.5,-3.75]"));
        var before = surfaces(model);
        model.addProperty("erydon_split_world_uv", true);
        var after = surfaces(model);
        assertAtlasSafe(after);
        assertEquals(area(before), area(after), 0.00001);
        assertArrayEquals(bounds(before), bounds(after), 0.000001F);
        for (var surface : after) {
            for (int vertex = 0; vertex < 4; vertex++) {
                assertWholeCellDifference(surface.vertexPositions()[vertex][0] * 16 + 2.5,
                        surface.sourceUvs()[vertex][0]);
                assertWholeCellDifference(16 - surface.vertexPositions()[vertex][1] * 16 - 3.75,
                        surface.sourceUvs()[vertex][1]);
            }
        }
    }

    @Test
    void explicitFaceMappingAndNonOptedInModelsKeepTheirExistingGeometryAndUvs() {
        JsonObject model = crossingModel();
        face(model).add("uv", JsonParser.parseString("[-2,-1,18,17]"));
        var before = surfaces(model);
        model.addProperty("erydon_split_world_uv", true);
        var after = surfaces(model);
        assertEquals(1, after.size());
        for (int vertex = 0; vertex < 4; vertex++) {
            assertArrayEquals(before.get(0).sourceUvs()[vertex], after.get(0).sourceUvs()[vertex]);
            assertArrayEquals(before.get(0).vertexPositions()[vertex], after.get(0).vertexPositions()[vertex]);
        }
    }

    @Test
    void optionIsStrictlyBooleanAndIncludedInGeometryIdentity() {
        JsonObject model = crossingModel();
        String before = ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model);
        model.addProperty("erydon_split_world_uv", false);
        assertEquals(before, ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
        model.addProperty("erydon_split_world_uv", true);
        assertNotEquals(before, ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
        for (JsonElement invalid : List.of(JsonNull.INSTANCE, new JsonPrimitive(1), new JsonPrimitive("true"))) {
            model.add("erydon_split_world_uv", invalid);
            assertThrows(IllegalArgumentException.class, () -> surfaces(model));
        }
    }

    @Test
    void optedInSplitGeometryIsCachedForAllMaterialBakesInOneReload() {
        JsonObject model = crossingModel();
        assertFalse(ErydonRawModelLoadingPlugin.reusesWorldUvSurfacesForTest(SOURCE, model));
        model.addProperty("erydon_split_world_uv", true);
        assertTrue(ErydonRawModelLoadingPlugin.reusesWorldUvSurfacesForTest(SOURCE, model));
    }

    @Test
    void allGeneratedGeorgianSurfacesReachBothRawBakingPathsWithBoundedUvs() throws Exception {
        Path root = Path.of("src/main/resources/assets/erydon/authoring_models/block/alcove");
        int checked = 0;
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("alcove_georgian_"))
                    .filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject model;
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    model = JsonParser.parseReader(reader).getAsJsonObject();
                }
                assertTrue(model.get("erydon_split_world_uv").getAsBoolean(), path.toString());
                assertAtlasSafe(ErydonRawModelLoadingPlugin.surfaceSnapshots("alcove_georgian/test",
                        new Identifier("erydon", "authoring_models/block/alcove/" + path.getFileName()), model));
                checked++;
            }
        }
        assertEquals(27, checked);
    }

    private static JsonObject crossingModel() {
        return JsonParser.parseString("""
                {"elements":[{"from":[-2,-1,0],"to":[18,17,16.75],"origin":[8,8,8],
                  "faces":{"south":{"texture":"#stone"}}}]}
                """).getAsJsonObject();
    }

    private static JsonObject face(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("south");
    }

    private static List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces(JsonObject model) {
        return ErydonRawModelLoadingPlugin.surfaceSnapshots("world_uv_split_test", SOURCE, model);
    }

    private static void assertWholeCellDifference(double authored, float local) {
        double cells = (authored - local) / 16;
        assertEquals(Math.rint(cells), cells, 0.000001);
    }

    private static void assertAtlasSafe(List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces) {
        for (var surface : surfaces) {
            for (float[] uv : surface.sourceUvs()) {
                assertTrue(uv[0] >= 0 && uv[0] <= 16, "U outside sprite: " + uv[0]);
                assertTrue(uv[1] >= 0 && uv[1] <= 16, "V outside sprite: " + uv[1]);
            }
        }
    }

    private static float[] bounds(List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces) {
        float[] result = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var surface : surfaces) {
            for (float[] vertex : surface.vertexPositions()) {
                for (int axis = 0; axis < 3; axis++) {
                    result[axis] = Math.min(result[axis], vertex[axis]);
                    result[axis + 3] = Math.max(result[axis + 3], vertex[axis]);
                }
            }
        }
        return result;
    }

    private static double area(List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> surfaces) {
        double result = 0;
        for (var surface : surfaces) {
            float[][] vertices = surface.vertexPositions();
            result += triangleArea(vertices[0], vertices[1], vertices[2]);
            result += triangleArea(vertices[0], vertices[2], vertices[3]);
        }
        return result;
    }

    private static double triangleArea(float[] first, float[] second, float[] third) {
        double[] normal = normal(new float[][]{first, second, third});
        return Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]) / 2;
    }

    private static double[] normal(float[][] vertices) {
        double ax = vertices[1][0] - vertices[0][0], ay = vertices[1][1] - vertices[0][1],
                az = vertices[1][2] - vertices[0][2];
        double bx = vertices[2][0] - vertices[0][0], by = vertices[2][1] - vertices[0][1],
                bz = vertices[2][2] - vertices[0][2];
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }
}
