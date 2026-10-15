package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawNormalOffsetTest {
    private static final Identifier SOURCE = new Identifier("erydon", "authoring_models/normal_offset_test.json");

    @Test
    void decorationOffsetFollowsTheTransformedNormalAndKeepsDefaultTexturePhase() {
        JsonObject model = rotatedModel();
        var before = surface(model);
        face(model).addProperty("erydon_normal_offset", 0.02);
        var after = surface(model);

        float[][] vertices = before.vertexPositions();
        double[] first = subtract(vertices[1], vertices[0]);
        double[] second = subtract(vertices[2], vertices[0]);
        double[] normal = {
                first[1] * second[2] - first[2] * second[1],
                first[2] * second[0] - first[0] * second[2],
                first[0] * second[1] - first[1] * second[0]
        };
        double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(vertices[vertex][axis] + normal[axis] / length * 0.02 / 16,
                        after.vertexPositions()[vertex][axis], 0.000001);
            }
            assertArrayEquals(before.sourceUvs()[vertex], after.sourceUvs()[vertex]);
            assertArrayEquals(before.normalizedUvs()[vertex], after.normalizedUvs()[vertex]);
        }
        assertEquals(before.faceDirection(), after.faceDirection());
    }

    @Test
    void explicitUvsAndTheirOffsetRemainUnchangedByNormalSeparation() {
        JsonObject model = rotatedModel();
        face(model).add("uv", JsonParser.parseString("[2,3,4,5]"));
        face(model).add("erydon_uv_offset", JsonParser.parseString("[0.25,-0.5]"));
        var before = surface(model);
        face(model).addProperty("erydon_normal_offset", 0.05);
        var after = surface(model);
        for (int vertex = 0; vertex < 4; vertex++) {
            assertArrayEquals(before.sourceUvs()[vertex], after.sourceUvs()[vertex]);
        }
    }

    @Test
    void absentAndZeroOffsetsKeepExistingVerticesAndCullBehaviour() {
        JsonObject model = boundaryModel();
        var before = surface(model);
        face(model).addProperty("erydon_normal_offset", 0);
        var zero = surface(model);
        assertEquals("south", zero.cullFace());
        for (int vertex = 0; vertex < 4; vertex++) {
            assertArrayEquals(before.vertexPositions()[vertex], zero.vertexPositions()[vertex]);
        }

        face(model).addProperty("erydon_normal_offset", 0.002);
        var separated = surface(model);
        assertNull(separated.cullFace());
        assertEquals(1 + 0.002 / 16, separated.vertexPositions()[0][2], 0.000001);
        face(model).addProperty("erydon_cull_boundary_override", true);
        assertEquals("south", surface(model).cullFace());
    }

    @Test
    void rejectsNonNumericNonFiniteAndOutOfRangeOffsets() {
        List<JsonElement> invalid = List.of(
                JsonNull.INSTANCE,
                new JsonPrimitive(true),
                new JsonPrimitive("0.01"),
                JsonParser.parseString("[0.01]"),
                JsonParser.parseString("{}"),
                new JsonPrimitive(-0.001),
                new JsonPrimitive(0.050000001),
                new JsonPrimitive(Double.NaN),
                new JsonPrimitive(Double.POSITIVE_INFINITY)
        );
        for (JsonElement value : invalid) {
            JsonObject model = boundaryModel();
            face(model).add("erydon_normal_offset", value);
            assertThrows(IllegalArgumentException.class, () -> surface(model), value.toString());
        }
    }

    @Test
    void sharedGeometryIdentityIncludesTheOptedInOffset() {
        JsonObject model = boundaryModel();
        String baseline = ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model);
        assertEquals(SOURCE.toString(), baseline);
        face(model).addProperty("erydon_normal_offset", 0);
        assertEquals(baseline, ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
        face(model).addProperty("erydon_normal_offset", 0.002);
        String first = ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model);
        assertNotEquals(baseline, first);
        face(model).addProperty("erydon_normal_offset", 0.003);
        assertNotEquals(first, ErydonRawModelLoadingPlugin.geometryKeyForTest(SOURCE, model));
    }

    private static JsonObject rotatedModel() {
        return JsonParser.parseString("""
                {"elements":[{"from":[2,3,4],"to":[6,8,10],"origin":[4,5,6],
                  "rotation":[17,31,-22],"faces":{"south":{"texture":"#stone"}}}],
                 "groups":[{"origin":[7,8,9],"rotation":[9,-13,7],"children":[0]}]}
                """).getAsJsonObject();
    }

    private static JsonObject boundaryModel() {
        return JsonParser.parseString("""
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"south":{"texture":"#stone","cullface":"south"}}}]}
                """).getAsJsonObject();
    }

    private static JsonObject face(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("south");
    }

    private static ErydonRawModelLoadingPlugin.SurfaceSnapshot surface(JsonObject model) {
        return ErydonRawModelLoadingPlugin.surfaceSnapshots("normal_offset_test", SOURCE, model).get(0);
    }

    private static double[] subtract(float[] first, float[] second) {
        return new double[]{first[0] - second[0], first[1] - second[1], first[2] - second[2]};
    }
}
