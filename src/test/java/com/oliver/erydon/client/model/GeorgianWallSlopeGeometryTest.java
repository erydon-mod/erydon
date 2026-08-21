package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallSlopeGeometryTest {
    private static final ModelExpectation[] EXPECTATIONS = {
            new ModelExpectation("27_upper", 312, 188),
            new ModelExpectation("27_lower", 636, 432),
            new ModelExpectation("27_lower_onramp", 347, 202),
            new ModelExpectation("27_upper_offramp", 344, 202),
            new ModelExpectation("45", 318, 0),
            new ModelExpectation("45_onramp", 350, 0),
            new ModelExpectation("45_offramp", 348, 0)
    };

    @Test
    void emitsOnlyFacesPresentInTheCanonicalModel() throws Exception {
        var model = JsonParser.parseString("""
                {
                  "elements": [
                    {
                      "from": [0, 0, 0],
                      "to": [16, 16, 16],
                      "faces": {
                        "up": {"texture": "#wall"}
                      }
                    }
                  ]
                }
                """).getAsJsonObject();

        GeorgianWallSlopeGeometry geometry = GeorgianWallSlopeGeometry.parse(
                model,
                new Identifier("erydon", "canonical_faces")
        );

        assertEquals(1, geometry.surfaceCount());
        assertEquals(0, geometry.surfaceCount("circular detail"));
    }

    @Test
    void emitsEveryAuthoredFaceAndRetainsCircularDetailIdentity() throws Exception {
        for (ModelExpectation expectation : EXPECTATIONS) {
            GeorgianWallSlopeGeometry geometry = GeorgianWallSlopeGeometry.parse(
                    read(expectation.name()),
                    new Identifier("erydon", "wall_georgian_" + expectation.name())
            );
            assertEquals(expectation.faces(), geometry.surfaceCount(), expectation.name());
            assertEquals(
                    expectation.circularDetailFaces(),
                    geometry.surfaceCount("circular detail"),
                    expectation.name()
            );
        }
    }

    @Test
    void emitsOneSafeSourceQuadPerAuthoredFaceForEveryRotation() throws Exception {
        for (ModelExpectation expectation : EXPECTATIONS) {
            assertSourceEmission(expectation);
        }
    }

    @Test
    void flatCornerArmExcludesTheCentrePostAlreadyProvidedByTheSlope() throws Exception {
        var model = readResource(
                "assets/erydon/models/block/wall/georgian/wall_georgian_side.json"
        );
        GeorgianWallSlopeGeometry fullSide = GeorgianWallSlopeGeometry.parse(
                model,
                new Identifier("erydon", "flat_side")
        );
        GeorgianWallSlopeGeometry arm = GeorgianWallSlopeGeometry.parseFlatSideArm(
                model,
                new Identifier("erydon", "flat_side_arm")
        );

        assertEquals(224, fullSide.surfaceCount());
        assertEquals(117, arm.surfaceCount());
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
        }) {
            assertEquals(117, capture(arm, direction).emitted());
        }
    }

    private static void assertSourceEmission(ModelExpectation expectation) throws Exception {
        GeorgianWallSlopeGeometry geometry = GeorgianWallSlopeGeometry.parse(
                read(expectation.name()),
                new Identifier("erydon", "wall_georgian_" + expectation.name())
        );
        for (Direction uphill : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
        }) {
            CapturedEmission capture = capture(geometry, uphill);

            assertEquals(
                    expectation.faces(),
                    capture.emitted(),
                    expectation.name() + " facing " + uphill
            );
            for (float[] vertexUv : capture.uv()) {
                assertTrue(vertexUv[0] >= 0.0F && vertexUv[0] <= 1.0F);
                assertTrue(vertexUv[1] >= 0.0F && vertexUv[1] <= 1.0F);
            }
            for (int quadIndex = 0; quadIndex < capture.quads().size(); quadIndex++) {
                SourceQuad sourceQuad = capture.quads().get(quadIndex);
                assertTrue(
                        !SpiralStairCtmGeometry.split(sourceQuad.face(), sourceQuad.vertices()).isEmpty(),
                        expectation.name() + " face " + quadIndex + " (" + sourceQuad.face()
                                + ", " + sourceQuad.vertices() + ") cannot be split when facing " + uphill
                );
            }
        }
    }

    private static CapturedEmission capture(GeorgianWallSlopeGeometry geometry,
                                             Direction uphill) {
        AtomicInteger emitted = new AtomicInteger();
        float[][] uv = new float[4][2];
        List<SourceQuad> sourceQuads = new ArrayList<>();
        QuadEmitter emitter = proxyEmitter(emitted, uv, sourceQuads);
        RenderContext context = (RenderContext) Proxy.newProxyInstance(
                RenderContext.class.getClassLoader(),
                new Class<?>[]{RenderContext.class},
                (proxy, method, arguments) -> {
                    if ("getEmitter".equals(method.getName())) {
                        return emitter;
                    }
                    return defaultValue(method.getReturnType(), proxy);
                }
        );
        geometry.emit(context, uphill, null);
        return new CapturedEmission(emitted.get(), uv, List.copyOf(sourceQuads));
    }

    private static QuadEmitter proxyEmitter(AtomicInteger emitted,
                                            float[][] uv,
                                            List<SourceQuad> sourceQuads) {
        Object[] holder = new Object[1];
        float[][] positions = new float[4][3];
        QuadEmitter emitter = (QuadEmitter) Proxy.newProxyInstance(
                QuadEmitter.class.getClassLoader(),
                new Class<?>[]{QuadEmitter.class},
                (proxy, method, arguments) -> {
                    if ("emit".equals(method.getName())) {
                        emitted.incrementAndGet();
                        List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                        for (float[] position : positions) {
                            vertices.add(new SpiralStairCtmGeometry.Vertex(
                                    position[0], position[1], position[2],
                                    -1, 0, false, 0.0F, 0.0F, 0.0F
                            ));
                        }
                        sourceQuads.add(new SourceQuad(lightFace(positions), List.copyOf(vertices)));
                        return null;
                    }
                    if ("pos".equals(method.getName()) && arguments != null
                            && arguments.length == 4 && arguments[0] instanceof Integer index
                            && arguments[1] instanceof Float x && arguments[2] instanceof Float y
                            && arguments[3] instanceof Float z) {
                        positions[index][0] = x;
                        positions[index][1] = y;
                        positions[index][2] = z;
                    }
                    if ("uv".equals(method.getName()) && arguments != null
                            && arguments.length == 3 && arguments[0] instanceof Integer index
                            && arguments[1] instanceof Float u && arguments[2] instanceof Float v) {
                        uv[index][0] = u;
                        uv[index][1] = v;
                    }
                    return defaultValue(method.getReturnType(), holder[0]);
                }
        );
        holder[0] = emitter;
        return emitter;
    }

    private static Direction lightFace(float[][] positions) {
        float firstX = positions[1][0] - positions[0][0];
        float firstY = positions[1][1] - positions[0][1];
        float firstZ = positions[1][2] - positions[0][2];
        float secondX = positions[2][0] - positions[0][0];
        float secondY = positions[2][1] - positions[0][1];
        float secondZ = positions[2][2] - positions[0][2];
        return Direction.getFacing(
                firstY * secondZ - firstZ * secondY,
                firstZ * secondX - firstX * secondZ,
                firstX * secondY - firstY * secondX
        );
    }

    private static Object defaultValue(Class<?> type, Object fluentValue) {
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type.isInstance(fluentValue)) {
            return fluentValue;
        }
        return null;
    }

    private record SourceQuad(Direction face, List<SpiralStairCtmGeometry.Vertex> vertices) {
    }

    private record CapturedEmission(int emitted, float[][] uv, List<SourceQuad> quads) {
    }

    private record ModelExpectation(String name, int faces, int circularDetailFaces) {
    }

    private static com.google.gson.JsonObject read(String name) throws IOException {
        String path = "assets/erydon/authoring_models/block/wall/georgian/wall_georgian_"
                + name + ".json";
        return readResource(path);
    }

    private static com.google.gson.JsonObject readResource(String path) throws IOException {
        try (InputStream stream = GeorgianWallSlopeGeometryTest.class
                .getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(stream, path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
