package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GothicArchCtmGeometryAudit {
    private static final float EPSILON = 0.00001F;

    private GothicArchCtmGeometryAudit() {
    }

    public static void main(String[] args) {
        verifyCtmSetAndModelClassification();
        verifyFrontFaceCellSplit();
        verifyPomSafeTriangleUv();
        verifyPomSafeTessellation();
        verifyRotatedRectangleSweep();
        verifyAuthoredGothicModels();
        System.out.println("Gothic arch CTM geometry audit passed.");
    }

    private static void verifyCtmSetAndModelClassification() {
        ErydonCtmService service = ErydonCtmService.get(null);
        assertEquals("normal", "aganite", service.gothicArchCtmSetName("aganite_arch_gothic"));
        assertEquals("aged", "aganite_aged", service.gothicArchCtmSetName("aganite_aged_arch_gothic"));
        assertEquals("ashlar", "aganite_ashlar", service.gothicArchCtmSetName("aganite_ashlar_arch_gothic"));
        assertEquals("rusticated", "aganite_rusticated",
                service.gothicArchCtmSetName("aganite_rusticated_arch_gothic"));
        assertEquals("hewn", "aganite_hewn", service.gothicArchCtmSetName("aganite_hewn_arch_gothic"));
        assertEquals("rock", "aganite_rock", service.gothicArchCtmSetName("aganite_rock_arch_gothic"));
        assertEquals("unrelated", null, service.gothicArchCtmSetName("aganite_arch_modern"));

        Identifier blockId = new Identifier("erydon", "aganite_arch_gothic");
        if (!GothicArchCtmModelLoadingPlugin.isWorldGothicArchModel(
                new ModelIdentifier(blockId, "arr=small_top,facing=north,width=1,waterlogged=false"))) {
            throw new IllegalStateException("World Gothic arch model was not classified.");
        }
        if (GothicArchCtmModelLoadingPlugin.isWorldGothicArchModel(
                new ModelIdentifier(blockId, "inventory"))) {
            throw new IllegalStateException("Inventory Gothic arch must retain its authored icon model.");
        }
        if (GothicArchCtmModelLoadingPlugin.isWorldGothicArchModel(
                new ModelIdentifier(new Identifier("erydon", "aganite_arch_modern"), "normal"))) {
            throw new IllegalStateException("Modern arch was classified as Gothic.");
        }
        if (GothicArchCtmModelLoadingPlugin.isWorldGothicArchModel(
                new ModelIdentifier(new Identifier("minecraft", "stone"), "normal"))) {
            throw new IllegalStateException("Foreign model was classified as an ERYDON Gothic arch.");
        }
    }

    private static void verifyFrontFaceCellSplit() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(-0.25F, -0.10F, 0.5F),
                vertex(1.25F, -0.10F, 0.5F),
                vertex(1.25F, 1.10F, 0.5F),
                vertex(-0.25F, 1.10F, 0.5F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);
        if (fragments.size() != 9) {
            throw new IllegalStateException("Expected nine front-face cells, found " + fragments.size());
        }

        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            int offsetX = SpiralStairCtmGeometry.offsetX(Direction.SOUTH, fragment);
            int offsetY = SpiralStairCtmGeometry.offsetY(Direction.SOUTH, fragment);
            int offsetZ = SpiralStairCtmGeometry.offsetZ(Direction.SOUTH, fragment);
            assertEquals("front X offset", fragment.cellS(), offsetX);
            assertEquals("front Y offset", fragment.cellT(), offsetY);
            assertEquals("front Z offset", 0, offsetZ);
            for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
                float u = SpiralStairCtmGeometry.u(Direction.SOUTH, vertex);
                float v = SpiralStairCtmGeometry.v(Direction.SOUTH, vertex);
                if (u < -EPSILON || u > 1.0F + EPSILON
                        || v < -EPSILON || v > 1.0F + EPSILON) {
                    throw new IllegalStateException("Out-of-cell Gothic UV: " + u + ", " + v);
                }
            }
        }
    }

    private static void verifyPomSafeTriangleUv() {
        SpiralStairCtmGeometry.CellVertex first = cellVertex(0.15F, 0.25F);
        SpiralStairCtmGeometry.CellVertex second = cellVertex(0.85F, 0.25F);
        SpiralStairCtmGeometry.CellVertex third = cellVertex(0.85F, 0.90F);
        SpiralStairCtmGeometry.CellVertex ghost =
                ArchRepeatCtmRenderer.pomSafeTriangleGhost(first, second, third);

        if (!third.vertex().equals(ghost.vertex())) {
            throw new IllegalStateException("POM ghost must duplicate the final geometry position.");
        }
        assertNear("ghost S", 0.15F, ghost.localS());
        assertNear("ghost T", 0.90F, ghost.localT());
        if (ghost.localS() < 0.0F || ghost.localS() > 1.0F
                || ghost.localT() < 0.0F || ghost.localT() > 1.0F) {
            throw new IllegalStateException("POM ghost UV escaped its CTM cell.");
        }
        if (!ArchRepeatCtmRenderer.hasStablePomBounds(List.of(first, second, third))) {
            throw new IllegalStateException("Right-triangle POM bounds are not stable.");
        }

        for (Direction face : Direction.values()) {
            float minU = Math.min(SpiralStairCtmGeometry.u(face, first),
                    Math.min(SpiralStairCtmGeometry.u(face, second),
                            SpiralStairCtmGeometry.u(face, third)));
            float maxU = Math.max(SpiralStairCtmGeometry.u(face, first),
                    Math.max(SpiralStairCtmGeometry.u(face, second),
                            SpiralStairCtmGeometry.u(face, third)));
            float minV = Math.min(SpiralStairCtmGeometry.v(face, first),
                    Math.min(SpiralStairCtmGeometry.v(face, second),
                            SpiralStairCtmGeometry.v(face, third)));
            float maxV = Math.max(SpiralStairCtmGeometry.v(face, first),
                    Math.max(SpiralStairCtmGeometry.v(face, second),
                            SpiralStairCtmGeometry.v(face, third)));
            float averageU = (SpiralStairCtmGeometry.u(face, first)
                    + SpiralStairCtmGeometry.u(face, second)
                    + SpiralStairCtmGeometry.u(face, third)
                    + SpiralStairCtmGeometry.u(face, ghost)) / 4.0F;
            float averageV = (SpiralStairCtmGeometry.v(face, first)
                    + SpiralStairCtmGeometry.v(face, second)
                    + SpiralStairCtmGeometry.v(face, third)
                    + SpiralStairCtmGeometry.v(face, ghost)) / 4.0F;
            assertNear(face + " POM U centre", (minU + maxU) * 0.5F, averageU);
            assertNear(face + " POM V centre", (minV + maxV) * 0.5F, averageV);
        }
    }

    private static void verifyPomSafeTessellation() {
        verifyPomPolygon("real Gothic parallelogram", List.of(
                cellVertex(0.2913737F, 0.3331292F),
                cellVertex(0.5095935F, 0.1374568F),
                cellVertex(0.9746482F, 0.6560998F),
                cellVertex(0.7564283F, 0.8517722F)
        ));
        verifyPomPolygon("arbitrary clipped triangle", List.of(
                cellVertex(0.15F, 0.25F),
                cellVertex(0.85F, 0.35F),
                cellVertex(0.65F, 0.90F)
        ));
        verifyPomPolygon("thin shifted Gothic strip", List.of(
                cellVertex(0.05F, 0.05F),
                cellVertex(0.18F, 0.05F),
                cellVertex(0.95F, 0.95F),
                cellVertex(0.82F, 0.95F)
        ));
        verifyPomPolygon("five-vertex cell clip", List.of(
                cellVertex(0.00F, 0.2152F),
                cellVertex(0.00F, 0.4532F),
                cellVertex(0.1292F, 0.5430F),
                cellVertex(0.3883F, 0.1700F),
                cellVertex(0.1476F, 0.0028F)
        ));
    }

    private static void verifyRotatedRectangleSweep() {
        for (int degrees = 0; degrees < 180; degrees += 5) {
            double radians = Math.toRadians(degrees);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            float halfWidth = 0.38F;
            float halfHeight = 0.045F;
            List<SpiralStairCtmGeometry.CellVertex> vertices = List.of(
                    rotatedCellVertex(-halfWidth, -halfHeight, cos, sin),
                    rotatedCellVertex(halfWidth, -halfHeight, cos, sin),
                    rotatedCellVertex(halfWidth, halfHeight, cos, sin),
                    rotatedCellVertex(-halfWidth, halfHeight, cos, sin)
            );
            verifyPomPolygon("rotated rectangle " + degrees, vertices);
        }
    }

    private static void verifyAuthoredGothicModels() {
        String[] modelNames = {
                "arch_gothic_corner_large_lower.json",
                "arch_gothic_corner_large_upper.json",
                "arch_gothic_corner_medium.json",
                "arch_gothic_corner_small.json",
                "arch_gothic_side_large.json",
                "arch_gothic_side_medium.json",
                "arch_gothic_side_small.json",
                "arch_gothic_top_large.json"
        };
        int fragmentCount = 0;
        int unsafeSourceCount = 0;
        int primitiveCount = 0;

        try {
            Class<?> modelDataClass = Class.forName(
                    "com.oliver.erydon.client.model.ErydonRawModelLoadingPlugin$RawModelData");
            Method parse = modelDataClass.getDeclaredMethod(
                    "parse", Identifier.class, JsonElement.class);
            parse.setAccessible(true);
            Field elementsField = modelDataClass.getDeclaredField("elements");
            elementsField.setAccessible(true);

            for (String modelName : modelNames) {
                String resourcePath = "assets/erydon/authoring_models/block/arch/gothic/" + modelName;
                try (InputStream input = GothicArchCtmGeometryAudit.class.getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    if (input == null) {
                        throw new IllegalStateException("Missing Gothic authoring model " + resourcePath);
                    }
                    JsonElement root = JsonParser.parseReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8));
                    Object model = parse.invoke(null,
                            new Identifier("erydon", "authoring_models/block/arch/gothic/" + modelName),
                            root);
                    @SuppressWarnings("unchecked")
                    List<Object> elements = (List<Object>) elementsField.get(model);
                    for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
                        Object element = elements.get(elementIndex);
                        Field facesField = element.getClass().getDeclaredField("faces");
                        facesField.setAccessible(true);
                        Method transformedVertices = element.getClass()
                                .getDeclaredMethod("transformedVertices", Direction.class);
                        transformedVertices.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Map<Direction, Object> faces = (Map<Direction, Object>) facesField.get(element);
                        for (Direction authoredFace : faces.keySet()) {
                            Vector3f[] positions = (Vector3f[]) transformedVertices.invoke(element, authoredFace);
                            Direction lightFace = closestDirection(positions);
                            List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                            for (Vector3f position : positions) {
                                vertices.add(vertex(
                                        position.x / 16.0F,
                                        position.y / 16.0F,
                                        position.z / 16.0F));
                            }
                            for (SpiralStairCtmGeometry.Fragment fragment
                                    : SpiralStairCtmGeometry.split(lightFace, vertices)) {
                                fragmentCount++;
                                if (!ArchRepeatCtmRenderer.hasStablePomBounds(fragment.vertices())) {
                                    unsafeSourceCount++;
                                }
                                primitiveCount += verifyPomPolygon(
                                        modelName + " element " + elementIndex + " " + authoredFace,
                                        fragment.vertices());
                            }
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not inspect Gothic raw model geometry.", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Could not read Gothic authoring models.", exception);
        }

        if (fragmentCount == 0 || unsafeSourceCount == 0 || primitiveCount < fragmentCount) {
            throw new IllegalStateException("Gothic authoring-model POM audit did not exercise unsafe geometry.");
        }
        System.out.println("Gothic arch POM audit: " + fragmentCount + " cell fragments, "
                + unsafeSourceCount + " required tessellation, " + primitiveCount
                + " stable output primitives.");
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

    private static SpiralStairCtmGeometry.CellVertex rotatedCellVertex(float x,
                                                                        float y,
                                                                        float cos,
                                                                        float sin) {
        return cellVertex(
                0.5F + x * cos - y * sin,
                0.5F + x * sin + y * cos
        );
    }

    private static int verifyPomPolygon(String label,
                                        List<SpiralStairCtmGeometry.CellVertex> source) {
        double sourceArea = signedArea(source);
        List<List<SpiralStairCtmGeometry.CellVertex>> primitives =
                ArchRepeatCtmRenderer.pomSafePrimitives(source);
        if (primitives.isEmpty()) {
            throw new IllegalStateException(label + " produced no POM-safe primitives.");
        }

        double primitiveArea = 0.0D;
        for (List<SpiralStairCtmGeometry.CellVertex> primitive : primitives) {
            if (primitive.size() != 3 && primitive.size() != 4) {
                throw new IllegalStateException(label + " emitted a " + primitive.size() + "-vertex primitive.");
            }
            double area = signedArea(primitive);
            if (Math.abs(area) <= EPSILON * EPSILON) {
                throw new IllegalStateException(label + " emitted degenerate geometry.");
            }
            if (Math.signum(area) != Math.signum(sourceArea)) {
                throw new IllegalStateException(label + " changed primitive winding.");
            }
            if (!ArchRepeatCtmRenderer.hasStablePomBounds(primitive)) {
                throw new IllegalStateException(label + " emitted unstable POM bounds: " + primitive);
            }
            for (SpiralStairCtmGeometry.CellVertex vertex : primitive) {
                if (vertex.localS() < -EPSILON || vertex.localS() > 1.0F + EPSILON
                        || vertex.localT() < -EPSILON || vertex.localT() > 1.0F + EPSILON) {
                    throw new IllegalStateException(label + " escaped its CTM cell: " + vertex);
                }
            }
            primitiveArea += area;
        }
        if (Math.abs(sourceArea - primitiveArea) > 0.00002D) {
            throw new IllegalStateException(label + " changed projected area from "
                    + sourceArea + " to " + primitiveArea + ".");
        }
        return primitives.size();
    }

    private static double signedArea(List<SpiralStairCtmGeometry.CellVertex> vertices) {
        double twiceArea = 0.0D;
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex current = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex next = vertices.get((index + 1) % vertices.size());
            twiceArea += (double) current.localS() * next.localT()
                    - (double) next.localS() * current.localT();
        }
        return twiceArea * 0.5D;
    }

    private static SpiralStairCtmGeometry.CellVertex cellVertex(float localS, float localT) {
        return new SpiralStairCtmGeometry.CellVertex(
                vertex(localS, localT, 0.5F), localS, localT);
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x, float y, float z) {
        return new SpiralStairCtmGeometry.Vertex(
                x, y, z, -1, 0, false, 0.0F, 0.0F, 0.0F);
    }

    private static void assertNear(String label, float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }
}
