package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class SpiralStairCtmGeometryAudit {
    private static final float EPSILON = 0.00001F;

    private SpiralStairCtmGeometryAudit() {
    }

    public static void main(String[] args) {
        verifyAreaPreservingCellSplit();
        verifyEveryFaceProjectionAndOffset();
        verifyTinyBoundaryOffsetsAreSnapped();
        verifyRepeatTileArithmetic();
        verifyAuthoredSpiralPomBudgets();
        System.out.println("Spiral stair CTM geometry audit passed.");
    }

    private static void verifyAuthoredSpiralPomBudgets() {
        PomMetrics main = inspectAuthoredSpiralPomGeometry("stairs_spiral_large");
        PomMetrics offstep = inspectAuthoredSpiralPomGeometry("stairs_spiral_large_offstep");

        assertPomMetrics("main", main, 72, 102, 28, 118, 284);
        assertPomMetrics("offstep", offstep, 22, 36, 2, 36, 48);
        if (main.maximumFallbackPrimitives() > 16 || offstep.maximumFallbackPrimitives() > 16) {
            throw new IllegalStateException("A real spiral fragment exceeded the measured POM fallback ceiling: "
                    + main.maximumFallbackPrimitives() + "/" + offstep.maximumFallbackPrimitives());
        }

        System.out.println("Spiral POM budget: main=" + main.boundedPrimitiveCount()
                + " bounded/" + main.fallbackPrimitiveCount() + " fallback, offstep="
                + offstep.boundedPrimitiveCount() + " bounded/"
                + offstep.fallbackPrimitiveCount() + " fallback, max fallback fragment="
                + Math.max(main.maximumFallbackPrimitives(), offstep.maximumFallbackPrimitives()) + ".");
    }

    private static PomMetrics inspectAuthoredSpiralPomGeometry(String modelName) {
        String resourcePath = "assets/erydon/models/block/stairs/spiral/" + modelName + ".json";
        int faceCount = 0;
        int fragmentCount = 0;
        int unsafeFragmentCount = 0;
        int boundedPrimitiveCount = 0;
        int fallbackPrimitiveCount = 0;
        int maximumPrimitives = 0;

        try (InputStream input = SpiralStairCtmGeometryAudit.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing spiral master " + resourcePath);
            }
            JsonElement root = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            Class<?> modelDataClass = Class.forName(
                    "com.oliver.erydon.client.model.ErydonRawModelLoadingPlugin$RawModelData");
            Method parse = modelDataClass.getDeclaredMethod(
                    "parse", Identifier.class, JsonElement.class);
            parse.setAccessible(true);
            Object model = parse.invoke(null,
                    new Identifier("erydon", "block/stairs/spiral/" + modelName), root);
            Field elementsField = modelDataClass.getDeclaredField("elements");
            elementsField.setAccessible(true);
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
                    faceCount++;
                    Vector3f[] positions = (Vector3f[]) transformedVertices.invoke(element, authoredFace);
                    Direction lightFace = closestDirection(positions);
                    List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                    for (Vector3f position : positions) {
                        vertices.add(vertex(position.x / 16.0F,
                                position.y / 16.0F, position.z / 16.0F));
                    }
                    for (SpiralStairCtmGeometry.Fragment fragment
                            : SpiralStairCtmGeometry.split(lightFace, vertices)) {
                        fragmentCount++;
                        if (!ArchRepeatCtmRenderer.hasStablePomBounds(fragment.vertices())) {
                            unsafeFragmentCount++;
                        }
                        boundedPrimitiveCount += SynapheiaRepeatBakedModel
                                .repeatPrimitives(fragment.vertices(), false).size();
                        int fallbackPrimitives = SynapheiaRepeatBakedModel
                                .repeatPrimitives(fragment.vertices(), true).size();
                        fallbackPrimitiveCount += fallbackPrimitives;
                        maximumPrimitives = Math.max(maximumPrimitives, fallbackPrimitives);
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not inspect spiral raw model geometry.", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Could not read spiral master " + modelName + ".", exception);
        }
        return new PomMetrics(faceCount, fragmentCount, unsafeFragmentCount,
                boundedPrimitiveCount, fallbackPrimitiveCount, maximumPrimitives);
    }

    private static void assertPomMetrics(String label,
                                         PomMetrics actual,
                                         int faces,
                                         int fragments,
                                         int unsafe,
                                         int bounded,
                                         int fallback) {
        PomMetrics expected = new PomMetrics(faces, fragments, unsafe, bounded, fallback,
                actual.maximumFallbackPrimitives());
        if (!actual.equals(expected)) {
            throw new IllegalStateException(label + " spiral POM budget changed: expected "
                    + expected + ", found " + actual);
        }
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

    private record PomMetrics(int faceCount,
                              int fragmentCount,
                              int unsafeFragmentCount,
                              int boundedPrimitiveCount,
                              int fallbackPrimitiveCount,
                              int maximumFallbackPrimitives) {
    }

    private static void verifyAreaPreservingCellSplit() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(-0.25F, 0.5F, 0.2F),
                vertex(-0.25F, 0.5F, 0.8F),
                vertex(1.25F, 0.5F, 0.8F),
                vertex(1.25F, 0.5F, 0.2F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(Direction.UP, vertices);
        if (fragments.size() != 3) {
            throw new IllegalStateException("Expected three X cells, found " + fragments.size());
        }

        double area = 0.0D;
        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            area += area(fragment);
            assertUvRange(Direction.UP, fragment);
        }
        assertNear("split area", 0.9D, area);
        assertCells("split cells", fragments, List.of(-1, 0, 1));
    }

    private static void verifyEveryFaceProjectionAndOffset() {
        Map<Direction, int[]> expectedOffsets = new EnumMap<>(Direction.class);
        expectedOffsets.put(Direction.UP, new int[]{1, 0, -2});
        expectedOffsets.put(Direction.DOWN, new int[]{1, 0, -2});
        expectedOffsets.put(Direction.NORTH, new int[]{1, -2, 0});
        expectedOffsets.put(Direction.SOUTH, new int[]{1, -2, 0});
        expectedOffsets.put(Direction.WEST, new int[]{0, -2, 1});
        expectedOffsets.put(Direction.EAST, new int[]{0, -2, 1});

        for (Direction face : Direction.values()) {
            List<SpiralStairCtmGeometry.Vertex> vertices = faceRectangle(face);
            List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(face, vertices);
            if (fragments.size() != 1) {
                throw new IllegalStateException(face + " should remain one fragment, found " + fragments.size());
            }
            SpiralStairCtmGeometry.Fragment fragment = fragments.get(0);
            assertUvRange(face, fragment);
            int[] expected = expectedOffsets.get(face);
            int[] actual = {
                    SpiralStairCtmGeometry.offsetX(face, fragment),
                    SpiralStairCtmGeometry.offsetY(face, fragment),
                    SpiralStairCtmGeometry.offsetZ(face, fragment)
            };
            for (int index = 0; index < 3; index++) {
                if (actual[index] != expected[index]) {
                    throw new IllegalStateException(face + " offset mismatch at axis " + index
                            + ": expected " + expected[index] + ", found " + actual[index]);
                }
            }
        }
    }

    private static void verifyTinyBoundaryOffsetsAreSnapped() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(0.4F, 1.0004375F, 0.2F),
                vertex(0.8F, 1.0004375F, 0.2F),
                vertex(0.8F, 0.5F, 0.2F),
                vertex(0.4F, 0.5F, 0.2F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);
        if (fragments.size() != 1 || fragments.get(0).cellT() != 0) {
            throw new IllegalStateException("Tiny anti-z-fighting offset created a false CTM cell: " + fragments);
        }
    }

    private static void verifyRepeatTileArithmetic() {
        int x = -7;
        int y = 13;
        int z = 8;
        Map<Direction, Integer> expected = Map.of(
                Direction.DOWN, Math.floorMod(-z - 1, 6) * 6 + Math.floorMod(x, 6),
                Direction.UP, Math.floorMod(z, 6) * 6 + Math.floorMod(x, 6),
                Direction.NORTH, Math.floorMod(-y, 6) * 6 + Math.floorMod(-x - 1, 6),
                Direction.SOUTH, Math.floorMod(-y, 6) * 6 + Math.floorMod(x, 6),
                Direction.WEST, Math.floorMod(-y, 6) * 6 + Math.floorMod(z, 6),
                Direction.EAST, Math.floorMod(-y, 6) * 6 + Math.floorMod(-z - 1, 6)
        );
        for (Direction face : Direction.values()) {
            int actual = ErydonCtmService.repeatTileIndex(x, y, z, face);
            if (actual != expected.get(face)) {
                throw new IllegalStateException(face + " repeat tile mismatch: expected "
                        + expected.get(face) + ", found " + actual);
            }
        }
    }

    private static List<SpiralStairCtmGeometry.Vertex> faceRectangle(Direction face) {
        float lowS = 1.25F;
        float highS = 1.75F;
        float lowT = -1.75F;
        float highT = -1.25F;
        return switch (face) {
            case UP, DOWN -> List.of(
                    vertex(lowS, 0.5F, lowT), vertex(lowS, 0.5F, highT),
                    vertex(highS, 0.5F, highT), vertex(highS, 0.5F, lowT));
            case NORTH, SOUTH -> List.of(
                    vertex(lowS, lowT, 0.5F), vertex(lowS, highT, 0.5F),
                    vertex(highS, highT, 0.5F), vertex(highS, lowT, 0.5F));
            case EAST, WEST -> List.of(
                    vertex(0.5F, lowT, lowS), vertex(0.5F, highT, lowS),
                    vertex(0.5F, highT, highS), vertex(0.5F, lowT, highS));
        };
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x, float y, float z) {
        return new SpiralStairCtmGeometry.Vertex(x, y, z, -1, 0, false, 0.0F, 0.0F, 0.0F);
    }

    private static void assertUvRange(Direction face, SpiralStairCtmGeometry.Fragment fragment) {
        for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
            float u = SpiralStairCtmGeometry.u(face, vertex);
            float v = SpiralStairCtmGeometry.v(face, vertex);
            if (u < -EPSILON || u > 1.0F + EPSILON || v < -EPSILON || v > 1.0F + EPSILON) {
                throw new IllegalStateException(face + " produced out-of-cell UV " + u + ", " + v);
            }
        }
    }

    private static double area(SpiralStairCtmGeometry.Fragment fragment) {
        double twiceArea = 0.0D;
        List<SpiralStairCtmGeometry.CellVertex> vertices = fragment.vertices();
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex current = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex next = vertices.get((index + 1) % vertices.size());
            double currentS = fragment.cellS() + current.localS();
            double currentT = fragment.cellT() + current.localT();
            double nextS = fragment.cellS() + next.localS();
            double nextT = fragment.cellT() + next.localT();
            twiceArea += currentS * nextT - nextS * currentT;
        }
        return Math.abs(twiceArea) * 0.5D;
    }

    private static void assertCells(String label,
                                    List<SpiralStairCtmGeometry.Fragment> fragments,
                                    List<Integer> expected) {
        List<Integer> actual = fragments.stream().map(SpiralStairCtmGeometry.Fragment::cellS).sorted().toList();
        if (!actual.equals(expected)) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

    private static void assertNear(String label, double expected, double actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

}
