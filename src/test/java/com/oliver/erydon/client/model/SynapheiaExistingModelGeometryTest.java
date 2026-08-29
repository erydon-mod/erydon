package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class SynapheiaExistingModelGeometryTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void existingStoneModelsOnlyProduceEmptySplitsForZeroAreaFaces() throws Exception {
        assertEmptyStoneSplits("block/ceiling/coffered/guilloche/ceiling_coffered_guilloche", 0);
        assertEmptyStoneSplits("block/cornice/guilloche/cornice_guilloche_outer_corner", 4);
    }

    private static void assertEmptyStoneSplits(String modelPath, int expectedEmptyCount) throws Exception {
        String resourcePath = "assets/erydon/models/" + modelPath + ".json";
        List<String> failures = new ArrayList<>();
        try (InputStream input = SynapheiaExistingModelGeometryTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing model " + resourcePath);
            }
            JsonElement root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            Class<?> modelDataClass = Class.forName(
                    "com.oliver.erydon.client.model.ErydonRawModelLoadingPlugin$RawModelData");
            Method parse = modelDataClass.getDeclaredMethod("parse", Identifier.class, JsonElement.class);
            parse.setAccessible(true);
            Object model = parse.invoke(null, new Identifier("erydon", modelPath), root);
            Field elementsField = modelDataClass.getDeclaredField("elements");
            elementsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> elements = (List<Object>) elementsField.get(model);

            for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
                Object element = elements.get(elementIndex);
                Field facesField = element.getClass().getDeclaredField("faces");
                facesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Direction, Object> faces = (Map<Direction, Object>) facesField.get(element);
                Method transformedVertices = element.getClass()
                        .getDeclaredMethod("transformedVertices", Direction.class);
                transformedVertices.setAccessible(true);
                for (Map.Entry<Direction, Object> entry : faces.entrySet()) {
                    Field textureKeyField = entry.getValue().getClass().getDeclaredField("textureKey");
                    textureKeyField.setAccessible(true);
                    if (!"#stone".equals(textureKeyField.get(entry.getValue()))) {
                        continue;
                    }
                    Vector3f[] positions = (Vector3f[]) transformedVertices.invoke(element, entry.getKey());
                    Direction lightFace = closestDirection(positions);
                    List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                    for (Vector3f position : positions) {
                        vertices.add(new SpiralStairCtmGeometry.Vertex(
                                position.x / 16.0F, position.y / 16.0F, position.z / 16.0F,
                                -1, 0, false, 0.0F, 0.0F, 0.0F));
                    }
                    if (SpiralStairCtmGeometry.split(lightFace, vertices).isEmpty()) {
                        assertFalse(SpiralStairCtmGeometry.hasProjectedArea(lightFace, vertices),
                                modelPath + " element " + elementIndex + " lost valid projected area");
                        failures.add("element " + elementIndex + " authored " + entry.getKey()
                                + " light " + lightFace + " vertices " + vertices);
                    }
                }
            }
        }
        assertEquals(expectedEmptyCount, failures.size(), modelPath + " empty splits: " + failures);
    }

    private static Direction closestDirection(Vector3f[] vertices) {
        Vector3f firstEdge = new Vector3f(vertices[1]).sub(vertices[0]);
        Vector3f secondEdge = new Vector3f(vertices[2]).sub(vertices[0]);
        Vector3f normal = firstEdge.cross(secondEdge);
        if (normal.lengthSquared() <= EPSILON) {
            return Direction.UP;
        }
        return Direction.getFacing(normal.x, normal.y, normal.z);
    }
}
