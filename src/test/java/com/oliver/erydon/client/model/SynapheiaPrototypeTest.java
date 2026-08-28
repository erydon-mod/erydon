package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SynapheiaPrototypeTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void synapheiaIsThePermanentEngine() {
        assertEquals(SynapheiaMode.SYNAPHEIA, SynapheiaMode.configured());
        assertEquals(SynapheiaMode.SYNAPHEIA, SynapheiaMode.fromConfig("Synapheia"));
        assertThrows(IllegalArgumentException.class, () -> SynapheiaMode.fromConfig("continuity"));
    }

    @Test
    void trianglePaddingSurvivesNormalAndFlippedRendererOrders() {
        TangentVertex first = new TangentVertex(0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        TangentVertex second = new TangentVertex(1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        TangentVertex third = new TangentVertex(0.0F, 1.0F, 0.0F, 0.0F, 1.0F);

        List<TangentVertex> padded =
                SynapheiaRepeatBakedModel.padTriangle(first, second, third);

        assertEquals(List.of(first, second, third, first), padded);
        assertValidTangentTriangle(padded, 0, 1, 2);
        assertValidTangentTriangle(padded, 1, 2, 3);
    }

    @Test
    void exactShaderBoundsKeepSkewedProjectedFragmentsBounded() {
        List<SpiralStairCtmGeometry.CellVertex> source = List.of(
                cellVertex(0.15F, 0.25F),
                cellVertex(0.85F, 0.35F),
                cellVertex(0.65F, 0.90F)
        );

        List<List<SpiralStairCtmGeometry.CellVertex>> primitives =
                SynapheiaRepeatBakedModel.repeatPrimitives(source, false);

        assertFalse(ArchRepeatCtmRenderer.hasStablePomBounds(source));
        assertEquals(List.of(source), primitives);
        assertEquals(List.of(source.get(0), source.get(1), source.get(2), source.get(0)),
                SynapheiaRepeatBakedModel.padTriangle(
                        source.get(0), source.get(1), source.get(2)));
    }

    @Test
    void unsupportedPomPipelinesRetainStableGeometryFallback() {
        List<SpiralStairCtmGeometry.CellVertex> source = List.of(
                cellVertex(0.15F, 0.25F),
                cellVertex(0.85F, 0.35F),
                cellVertex(0.65F, 0.90F)
        );

        List<List<SpiralStairCtmGeometry.CellVertex>> primitives =
                SynapheiaRepeatBakedModel.repeatPrimitives(source, true);

        assertTrue(primitives.size() > 1);
        assertNear(Math.abs(cellArea(source)), primitives.stream()
                .mapToDouble(primitive -> Math.abs(cellArea(primitive))).sum());
        assertTrue(primitives.stream().allMatch(ArchRepeatCtmRenderer::hasStablePomBounds));
    }

    @Test
    void alreadyStableProjectedTrianglesRetainPomBounds() {
        List<SpiralStairCtmGeometry.CellVertex> source = List.of(
                cellVertex(0.15F, 0.25F),
                cellVertex(0.85F, 0.25F),
                cellVertex(0.85F, 0.90F)
        );

        assertTrue(ArchRepeatCtmRenderer.hasStablePomBounds(source));
        List<SpiralStairCtmGeometry.CellVertex> padded =
                SynapheiaRepeatBakedModel.padPomSafeTriangle(
                        source.get(0), source.get(1), source.get(2));
        assertEquals(source.get(2).vertex(), padded.get(3).vertex());
        assertNear(0.5D, padded.stream()
                .mapToDouble(SpiralStairCtmGeometry.CellVertex::localS)
                .average().orElseThrow());
        assertNear(0.575D, padded.stream()
                .mapToDouble(SpiralStairCtmGeometry.CellVertex::localT)
                .average().orElseThrow());
    }

    @Test
    void generatedSignedCasesUseMathematicalFloorAndSeparateOwnerFromPhase() {
        JsonArray cases = resourceJson("/synapheia-fixtures/phase_cases.json")
                .getAsJsonObject().getAsJsonArray("cases");
        int checked = 0;
        for (JsonElementView view : elementViews(cases)) {
            JsonObject fixture = view.object();
            if (!fixture.has("local_probe_position")) {
                continue;
            }
            JsonArray local = fixture.getAsJsonArray("local_probe_position");
            JsonArray owner = fixture.getAsJsonArray("owner_position");
            JsonArray expectedOffset = fixture.getAsJsonArray("expected_cell_offset");
            JsonArray expectedPosition = fixture.getAsJsonArray("expected_geometric_cell_position");
            int offsetX = (int) Math.floor(local.get(0).getAsDouble() / 16.0D);
            assertEquals(expectedOffset.get(0).getAsInt(), offsetX, fixture.get("case_id").getAsString());
            assertEquals(expectedPosition.get(0).getAsInt(), owner.get(0).getAsInt() + offsetX);
            assertEquals(expectedPosition.get(1).getAsInt(), owner.get(1).getAsInt());
            assertEquals(expectedPosition.get(2).getAsInt(), owner.get(2).getAsInt());
            checked++;
        }
        assertEquals(12, checked);
    }

    @Test
    void authoredEightToTwentyFourProjectionSplitsWithoutClampingSourceUvs() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(model(8), 0.0F, 0.0F, -0.5F, 0.0F, 0xFFFF0000, 0x00100020, 1.0F, 0.0F, 0.0F),
                vertex(model(24), 0.0F, 0.0F, 1.5F, 0.0F, 0xFF0000FF, 0x00300040, 0.0F, 1.0F, 0.0F),
                vertex(model(24), 1.0F, 0.0F, 1.5F, 1.0F, 0xFF0000FF, 0x00300040, 0.0F, 1.0F, 0.0F),
                vertex(model(8), 1.0F, 0.0F, -0.5F, 1.0F, 0xFFFF0000, 0x00100020, 1.0F, 0.0F, 0.0F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);

        assertEquals(List.of(0, 1), fragments.stream()
                .map(SpiralStairCtmGeometry.Fragment::cellS).toList());
        assertNear(1.0D, fragments.stream().mapToDouble(SynapheiaPrototypeTest::area).sum());

        List<SpiralStairCtmGeometry.Vertex> boundary = fragments.stream()
                .flatMap(fragment -> fragment.vertices().stream())
                .map(SpiralStairCtmGeometry.CellVertex::vertex)
                .filter(vertex -> Math.abs(vertex.x() - 1.0F) <= EPSILON)
                .toList();
        assertFalse(boundary.isEmpty());
        for (SpiralStairCtmGeometry.Vertex vertex : boundary) {
            assertNear(0.5D, vertex.sourceU());
            assertEquals(0xFF800080, vertex.color());
            assertEquals(0x00200030, vertex.lightmap());
            double normalLength = Math.sqrt(vertex.normalX() * vertex.normalX()
                    + vertex.normalY() * vertex.normalY() + vertex.normalZ() * vertex.normalZ());
            assertNear(1.0D, normalLength);
        }
        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
                assertTrue(vertex.localS() >= 0.0F && vertex.localS() <= 1.0F);
                assertTrue(vertex.localT() >= 0.0F && vertex.localT() <= 1.0F);
                assertTrue(SpiralStairCtmGeometry.u(Direction.SOUTH, vertex) >= 0.0F
                        && SpiralStairCtmGeometry.u(Direction.SOUTH, vertex) <= 1.0F);
                assertTrue(SpiralStairCtmGeometry.v(Direction.SOUTH, vertex) >= 0.0F
                        && SpiralStairCtmGeometry.v(Direction.SOUTH, vertex) <= 1.0F);
            }
        }
    }

    @Test
    void explicitUvsOutsideZeroToSixteenSplitIntoAtlasSafeFragments() {
        JsonObject model = resourceJson(
                "/synapheia-fixtures/assets/synapheia_dev/models/block/uv_out_of_range.json")
                .getAsJsonObject();
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");
        JsonArray uv = element.getAsJsonObject("faces").getAsJsonObject("south").getAsJsonArray("uv");
        assertEquals(-8.0F, uv.get(0).getAsFloat());
        assertEquals(24.0F, uv.get(2).getAsFloat());

        float minX = model(from.get(0).getAsFloat());
        float maxX = model(to.get(0).getAsFloat());
        float minY = model(from.get(1).getAsFloat());
        float maxY = model(to.get(1).getAsFloat());
        float z = model(to.get(2).getAsFloat());
        float minU = model(uv.get(0).getAsFloat());
        float maxU = model(uv.get(2).getAsFloat());
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(
                Direction.SOUTH,
                List.of(
                        vertex(minX, minY, z, minU, 0.0F, -1, 0, 0.0F, 0.0F, 1.0F),
                        vertex(maxX, minY, z, maxU, 0.0F, -1, 0, 0.0F, 0.0F, 1.0F),
                        vertex(maxX, maxY, z, maxU, 1.0F, -1, 0, 0.0F, 0.0F, 1.0F),
                        vertex(minX, maxY, z, minU, 1.0F, -1, 0, 0.0F, 0.0F, 1.0F)
                ));

        assertEquals(List.of(-1, 0, 1), fragments.stream()
                .map(SpiralStairCtmGeometry.Fragment::cellS).toList());
        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
                assertTrue(SpiralStairCtmGeometry.u(Direction.SOUTH, vertex) >= 0.0F);
                assertTrue(SpiralStairCtmGeometry.u(Direction.SOUTH, vertex) <= 1.0F);
                assertTrue(SpiralStairCtmGeometry.v(Direction.SOUTH, vertex) >= 0.0F);
                assertTrue(SpiralStairCtmGeometry.v(Direction.SOUTH, vertex) <= 1.0F);
            }
        }
    }

    @Test
    void developmentThirtyTwoCubeUsesAutomaticUvsAndSplitsEveryFace() {
        JsonObject model = resourceJson(
                "/synapheia-test-pack/assets/synapheia_dev/models/block/aganite_cube_32_auto_uv.json")
                .getAsJsonObject();
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals(-8.0F, element.getAsJsonArray("from").get(0).getAsFloat());
        assertEquals(24.0F, element.getAsJsonArray("to").get(0).getAsFloat());

        JsonObject faces = element.getAsJsonObject("faces");
        for (Direction face : Direction.values()) {
            JsonObject faceModel = faces.getAsJsonObject(face.getName());
            assertFalse(faceModel.has("uv"), face.getName() + " must exercise automatic UV assignment");

            List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(
                    face, rectangle(face, -0.5F, 1.5F, -0.5F, 1.5F));
            assertEquals(9, fragments.size(), face.getName());
            assertEquals(Set.of(-1, 0, 1), fragments.stream()
                    .map(SpiralStairCtmGeometry.Fragment::cellS).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(-1, 0, 1), fragments.stream()
                    .map(SpiralStairCtmGeometry.Fragment::cellT).collect(java.util.stream.Collectors.toSet()));
            assertNear(4.0D, fragments.stream().mapToDouble(SynapheiaPrototypeTest::area).sum());
            for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
                for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
                    assertTrue(SpiralStairCtmGeometry.u(face, vertex) >= 0.0F);
                    assertTrue(SpiralStairCtmGeometry.u(face, vertex) <= 1.0F);
                    assertTrue(SpiralStairCtmGeometry.v(face, vertex) >= 0.0F);
                    assertTrue(SpiralStairCtmGeometry.v(face, vertex) <= 1.0F);
                }
            }
        }
    }

    @Test
    void exactAndNearCellBoundariesDoNotCreateSliverFragments() {
        for (float maximum : new float[]{1.0F, 1.0005F}) {
            List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(
                    Direction.SOUTH,
                    rectangle(Direction.SOUTH, 0.0F, maximum, 0.0F, 1.0F)
            );
            assertEquals(1, fragments.size());
            assertEquals(0, fragments.get(0).cellS());
            assertNear(1.0D, area(fragments.get(0)));
        }
    }

    @Test
    void everyFaceSupportsSignedWholeCellsAndWorldAlignedUvOrientation() {
        for (Direction face : Direction.values()) {
            List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(
                    face, rectangle(face, -1.0F, 0.0F, 2.0F, 3.0F));
            assertEquals(1, fragments.size(), face.getName());
            SpiralStairCtmGeometry.Fragment fragment = fragments.get(0);
            assertEquals(-1, fragment.cellS(), face.getName());
            assertEquals(2, fragment.cellT(), face.getName());

            int expectedX = switch (face) {
                case UP, DOWN, NORTH, SOUTH -> -1;
                default -> 0;
            };
            int expectedY = face.getAxis().isHorizontal() ? 2 : 0;
            int expectedZ = switch (face) {
                case UP, DOWN -> 2;
                case EAST, WEST -> -1;
                default -> 0;
            };
            assertEquals(expectedX, SpiralStairCtmGeometry.offsetX(face, fragment));
            assertEquals(expectedY, SpiralStairCtmGeometry.offsetY(face, fragment));
            assertEquals(expectedZ, SpiralStairCtmGeometry.offsetZ(face, fragment));

            for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
                float u = SpiralStairCtmGeometry.u(face, vertex);
                float v = SpiralStairCtmGeometry.v(face, vertex);
                assertTrue(u >= 0.0F && u <= 1.0F, face.getName());
                assertTrue(v >= 0.0F && v <= 1.0F, face.getName());
            }
        }
    }

    @Test
    void zeroAreaGeometryIsRejectedAndParallelSplitsAreDeterministic() {
        List<SpiralStairCtmGeometry.Vertex> line = List.of(
                vertex(0.0F, 0.0F, 0.0F),
                vertex(0.5F, 0.0F, 0.0F),
                vertex(1.0F, 0.0F, 0.0F));
        assertTrue(SpiralStairCtmGeometry.split(Direction.SOUTH, line).isEmpty());

        List<SpiralStairCtmGeometry.Vertex> span = rectangle(
                Direction.SOUTH, -1.0F, 3.0F, -1.0F, 2.0F);
        List<SpiralStairCtmGeometry.Fragment> expected =
                SpiralStairCtmGeometry.split(Direction.SOUTH, span);
        assertEquals(12, expected.size());
        assertTrue(IntStream.range(0, 64).parallel()
                .mapToObj(ignored -> SpiralStairCtmGeometry.split(Direction.SOUTH, span))
                .allMatch(expected::equals));
    }

    @Test
    void overhangingFragmentsClearOnlyUnsafeCullMetadata() {
        assertEquals(Direction.SOUTH,
                SynapheiaRepeatBakedModel.cullFaceForOffset(Direction.SOUTH, 0, 0, 0));
        assertEquals(null,
                SynapheiaRepeatBakedModel.cullFaceForOffset(Direction.SOUTH, -1, 0, 0));
        assertEquals(null,
                SynapheiaRepeatBakedModel.cullFaceForOffset(Direction.SOUTH, 0, 2, 0));
    }

    @Test
    void negativeToPositiveMultiCellSpanIsDeterministicAndImmutable() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(-1.0F, 0.0F, 0.0F),
                vertex(3.0F, 0.0F, 0.0F),
                vertex(3.0F, 1.0F, 0.0F),
                vertex(-1.0F, 1.0F, 0.0F)
        );
        List<SpiralStairCtmGeometry.Fragment> first =
                SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);
        List<SpiralStairCtmGeometry.Fragment> second =
                SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);

        assertEquals(List.of(-1, 0, 1, 2), first.stream()
                .map(SpiralStairCtmGeometry.Fragment::cellS).toList());
        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> first.get(0).vertices().add(first.get(0).vertices().get(0)));
    }

    @Test
    void everyFaceMapsSignedMinusOneThroughPlusTwoToExpectedRepeatTiles() {
        int[] cells = {-1, 0, 1, 2};
        for (Direction face : Direction.values()) {
            for (int cell : cells) {
                int x = -7 + cell;
                int y = 64 + cell;
                int z = -11 + cell;
                int expected = switch (face) {
                    case DOWN -> Math.floorMod(-z - 1, 6) * 6 + Math.floorMod(x, 6);
                    case UP -> Math.floorMod(z, 6) * 6 + Math.floorMod(x, 6);
                    case NORTH -> Math.floorMod(-y, 6) * 6 + Math.floorMod(-x - 1, 6);
                    case SOUTH -> Math.floorMod(-y, 6) * 6 + Math.floorMod(x, 6);
                    case WEST -> Math.floorMod(-y, 6) * 6 + Math.floorMod(z, 6);
                    case EAST -> Math.floorMod(-y, 6) * 6 + Math.floorMod(-z - 1, 6);
                };
                assertEquals(expected, ErydonCtmService.repeatTileIndex(x, y, z, face));
            }
        }
    }

    @Test
    void realGeorgianCorniceFaceUsesTheSameCellContractAndCanCrossEightToTwentyFour() {
        JsonObject model = resourceJson(
                "/assets/erydon/models/block/cornice/georgian/cornice_georgian_straight.json")
                .getAsJsonObject();
        JsonObject firstElement = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        JsonArray from = firstElement.getAsJsonArray("from");
        JsonArray to = firstElement.getAsJsonArray("to");
        assertTrue(firstElement.getAsJsonObject("faces").has("south"));

        float x0 = from.get(0).getAsFloat() / 16.0F;
        float y0 = from.get(1).getAsFloat() / 16.0F;
        float x1 = to.get(0).getAsFloat() / 16.0F;
        float y1 = to.get(1).getAsFloat() / 16.0F;
        float z = to.get(2).getAsFloat() / 16.0F;
        List<SpiralStairCtmGeometry.Fragment> fragments = SpiralStairCtmGeometry.split(
                Direction.SOUTH,
                List.of(vertex(x0, y0, z), vertex(x1, y0, z),
                        vertex(x1, y1, z), vertex(x0, y1, z))
        );

        assertEquals(1, fragments.size());
        assertEquals(0, fragments.get(0).cellS());
        assertEquals(0, fragments.get(0).cellT());
        assertNear((x1 - x0) * (y1 - y0), area(fragments.get(0)));

        float width = x1 - x0;
        List<SpiralStairCtmGeometry.Fragment> shifted = SpiralStairCtmGeometry.split(
                Direction.SOUTH,
                List.of(vertex(x0 + 0.5F, y0, z), vertex(x1 + 0.5F, y0, z),
                        vertex(x1 + 0.5F, y1, z), vertex(x0 + 0.5F, y1, z))
        );
        assertEquals(List.of(0, 1), shifted.stream()
                .map(SpiralStairCtmGeometry.Fragment::cellS).toList());
        assertNear(width * (y1 - y0), shifted.stream().mapToDouble(SynapheiaPrototypeTest::area).sum());
    }

    @Test
    void immutableLookupAdvancesGenerationAndCoversTheDeclaredBlocks() {
        List<Identifier> tiles = IntStream.range(0, 36)
                .mapToObj(index -> new Identifier("minecraft", "optifine/ctm/aganite/" + index))
                .toList();
        Identifier block = new Identifier("erydon", "aganite_block");
        SynapheiaManifest.Rule rule = new SynapheiaManifest.Rule(
                new Identifier("minecraft", "optifine/ctm/aganite/a_aganite_base.properties"),
                "test", SynapheiaManifest.Method.REPEAT, tiles,
                Set.of(Direction.values()), Set.of(block), Set.of(), true, 10);
        SynapheiaManifest.Prepared prepared = new SynapheiaManifest.Prepared(
                List.of(rule), "test", 1, 1, 0, 1L);

        SynapheiaService.Snapshot first = SynapheiaService.publish(prepared);
        SynapheiaService.Snapshot second = SynapheiaService.publish(prepared);
        assertTrue(first.active());
        assertTrue(second.active());
        assertNotEquals(first.generation(), second.generation());
        assertEquals(Set.of(block), second.rulesByBlock().keySet());
        assertEquals(rule, second.repeatRuleFor(block, Direction.UP,
                new Identifier("erydon", "block/aganite_block")));
        assertThrows(UnsupportedOperationException.class, () -> second.rulesByBlock().put(
                new Identifier("minecraft", "stone"), List.of(rule)));
        assertThrows(UnsupportedOperationException.class,
                () -> second.rulesByBlock().get(block).add(rule));
    }

    @Test
    void projectedSpiralGeometryCannotBeMistakenForAnAlreadyProcessedCtmTile() {
        List<Identifier> tiles = IntStream.range(0, 36)
                .mapToObj(index -> new Identifier("minecraft", "optifine/ctm/aganite/" + index))
                .toList();
        Identifier spiral = new Identifier("erydon", "aganite_stairs_spiral_large");
        SynapheiaManifest.Rule rule = new SynapheiaManifest.Rule(
                new Identifier("minecraft", "optifine/ctm/aganite/a_aganite_base.properties"),
                "test", SynapheiaManifest.Method.REPEAT, tiles,
                Set.of(Direction.values()), Set.of(spiral), Set.of(), true, 10);
        SynapheiaService.Snapshot snapshot = SynapheiaService.publish(
                new SynapheiaManifest.Prepared(List.of(rule), "test", 1, 1, 0, 1L));

        // The normal route deliberately rejects a CTM source tile to avoid double processing.
        assertNull(snapshot.repeatRuleFor(spiral, Direction.UP, tiles.get(17)));
        // Spiral geometry is rebuilt from world positions, so this apparent tile may be atlas bleed.
        assertEquals(rule, snapshot.repeatRuleForProjectedGeometry(spiral, Direction.UP));
        assertTrue(SynapheiaRepeatBakedModel.usesProjectedRepeatGeometry(spiral));
        assertTrue(SynapheiaRepeatBakedModel.usesProjectedRepeatGeometry(
                new Identifier("erydon", "aganite_stairs_spiral_large_aged")));
        assertFalse(SynapheiaRepeatBakedModel.usesProjectedRepeatGeometry(
                new Identifier("erydon", "aganite_block")));
    }

    @Test
    void legacyAgedIdsAlsoIndexTheirCanonicalBlocks() {
        List<Identifier> tiles = IntStream.range(0, 36)
                .mapToObj(index -> new Identifier("minecraft", "optifine/ctm/aganite_aged/" + index))
                .toList();
        Identifier legacy = new Identifier("erydon", "aganite_block_aged");
        Identifier canonical = new Identifier("erydon", "aganite_aged_block");
        SynapheiaManifest.Rule rule = new SynapheiaManifest.Rule(
                new Identifier("minecraft", "optifine/ctm/aganite_aged/base.properties"),
                "test", SynapheiaManifest.Method.REPEAT, tiles,
                Set.of(Direction.values()), Set.of(legacy), Set.of(), true, 10);

        SynapheiaService.Snapshot snapshot = SynapheiaService.publish(
                new SynapheiaManifest.Prepared(List.of(rule), "test", 1, 1, 0, 1L));

        assertEquals(List.of(rule), snapshot.rulesFor(legacy));
        assertEquals(List.of(rule), snapshot.rulesFor(canonical));
    }

    @Test
    void propertyParserAcceptsProductionRepeatAndOverlayContracts() {
        Identifier block = new Identifier("erydon", "aganite_block");
        Properties repeat = new Properties();
        repeat.setProperty("method", "repeat");
        repeat.setProperty("width", "6");
        repeat.setProperty("height", "6");
        repeat.setProperty("tiles", IntStream.range(0, 36)
                .mapToObj(index -> "textures/optifine/ctm/aganite/" + index)
                .collect(java.util.stream.Collectors.joining(" ")));
        repeat.setProperty("faces", "all");
        repeat.setProperty("connect", "block");
        repeat.setProperty("innerSeams", "true");
        repeat.setProperty("priority", "10");
        SynapheiaManifest.Rule repeatRule = SynapheiaManifest.parseRule(
                new Identifier("minecraft", "optifine/ctm/aganite/base.properties"),
                "test", repeat, Set.of(block));
        assertEquals(SynapheiaManifest.Method.REPEAT, repeatRule.method());
        assertEquals(36, repeatRule.tiles().size());

        Properties overlay = new Properties();
        overlay.setProperty("method", "overlay_ctm");
        overlay.setProperty("tiles", IntStream.range(0, 47)
                .mapToObj(index -> "textures/optifine/ctm/overlay/trim/silver/" + index)
                .collect(java.util.stream.Collectors.joining(" ")));
        overlay.setProperty("matchTiles", "erydon:block/aganite_block_silvertrim");
        overlay.setProperty("faces", "top bottom north south east west");
        overlay.setProperty("connect", "block");
        overlay.setProperty("innerSeams", "true");
        overlay.setProperty("priority", "20");
        overlay.setProperty("layer", "translucent");
        SynapheiaManifest.Rule overlayRule = SynapheiaManifest.parseRule(
                new Identifier("minecraft", "optifine/ctm/aganite/overlay.properties"),
                "test", overlay, Set.of(block));
        assertEquals(SynapheiaManifest.Method.OVERLAY_CTM, overlayRule.method());
        assertEquals(47, overlayRule.tiles().size());
    }

    @Test
    void connectedTextureMapCoversAllFortySevenCanonicalMasks() {
        Set<Integer> tileIndices = IntStream.range(0, 256)
                .filter(SynapheiaPrototypeTest::isCanonicalConnectionMask)
                .map(SynapheiaRepeatBakedModel::connectedTileIndex)
                .boxed().collect(java.util.stream.Collectors.toSet());
        assertEquals(47, tileIndices.size());
        assertEquals(IntStream.range(0, 47).boxed().collect(java.util.stream.Collectors.toSet()), tileIndices);
    }

    @Test
    void everyProductionErydonRuleUsesTheSupportedSharedEngines() throws Exception {
        Path namespaceRoot = Path.of("src/main/resources/assets/minecraft");
        int repeatRules = 0;
        int overlayRules = 0;
        try (var paths = Files.walk(namespaceRoot.resolve("optifine/ctm"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".properties")).toList()) {
                Properties properties = new Properties();
                try (InputStream input = Files.newInputStream(path)) {
                    properties.load(input);
                }
                Set<Identifier> blocks = SynapheiaManifest.parseBlocks(properties.getProperty("matchBlocks"));
                if (blocks.isEmpty()) {
                    continue;
                }
                String relative = namespaceRoot.relativize(path).toString().replace('\\', '/');
                SynapheiaManifest.Rule rule = SynapheiaManifest.parseRule(
                        new Identifier("minecraft", relative), "production-test", properties, blocks);
                if (rule.method() == SynapheiaManifest.Method.REPEAT) {
                    repeatRules++;
                } else {
                    overlayRules++;
                }
            }
        }
        assertEquals(1025, repeatRules);
        assertEquals(192, overlayRules);
    }

    private static boolean isCanonicalConnectionMask(int mask) {
        for (int corner = 0; corner < 4; corner++) {
            int cornerBit = 1 << (corner * 2 + 1);
            int firstEdge = 1 << (corner * 2);
            int secondEdge = 1 << (((corner + 1) & 3) * 2);
            if ((mask & cornerBit) != 0
                    && ((mask & firstEdge) == 0 || (mask & secondEdge) == 0)) {
                return false;
            }
        }
        return true;
    }

    private static JsonElementView[] elementViews(JsonArray array) {
        JsonElementView[] result = new JsonElementView[array.size()];
        for (int index = 0; index < array.size(); index++) {
            result[index] = new JsonElementView(array.get(index).getAsJsonObject());
        }
        return result;
    }

    private static com.google.gson.JsonElement resourceJson(String path) {
        try (var stream = SynapheiaPrototypeTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read test resource " + path, exception);
        }
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x, float y, float z) {
        return vertex(x, y, z, 0.0F, 0.0F, -1, 0, 0.0F, 0.0F, 1.0F);
    }

    private static SpiralStairCtmGeometry.CellVertex cellVertex(float localS, float localT) {
        return new SpiralStairCtmGeometry.CellVertex(
                vertex(localS, localT, 0.5F), localS, localT);
    }

    private static float model(float coordinate) {
        return coordinate / 16.0F;
    }

    private static List<SpiralStairCtmGeometry.Vertex> rectangle(Direction face,
                                                                  float minS,
                                                                  float maxS,
                                                                  float minT,
                                                                  float maxT) {
        return List.of(
                projectedVertex(face, minS, minT),
                projectedVertex(face, maxS, minT),
                projectedVertex(face, maxS, maxT),
                projectedVertex(face, minS, maxT)
        );
    }

    private static SpiralStairCtmGeometry.Vertex projectedVertex(Direction face, float s, float t) {
        return switch (face) {
            case UP, DOWN -> vertex(s, 0.25F, t);
            case EAST, WEST -> vertex(0.25F, t, s);
            case NORTH, SOUTH -> vertex(s, t, 0.25F);
        };
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x,
                                                        float y,
                                                        float z,
                                                        float sourceU,
                                                        float sourceV,
                                                        int color,
                                                        int lightmap,
                                                        float normalX,
                                                        float normalY,
                                                        float normalZ) {
        return new SpiralStairCtmGeometry.Vertex(
                x, y, z, color, lightmap, true, normalX, normalY, normalZ, sourceU, sourceV);
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

    private static double cellArea(List<SpiralStairCtmGeometry.CellVertex> vertices) {
        double twiceArea = 0.0D;
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex current = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex next = vertices.get((index + 1) % vertices.size());
            twiceArea += (double) current.localS() * next.localT()
                    - (double) next.localS() * current.localT();
        }
        return twiceArea * 0.5D;
    }

    private static void assertNear(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) <= EPSILON,
                () -> "Expected " + expected + ", found " + actual);
    }

    private static void assertValidTangentTriangle(List<TangentVertex> vertices,
                                                   int firstIndex,
                                                   int secondIndex,
                                                   int thirdIndex) {
        TangentVertex first = vertices.get(firstIndex);
        TangentVertex second = vertices.get(secondIndex);
        TangentVertex third = vertices.get(thirdIndex);
        float firstEdgeX = second.x - first.x;
        float firstEdgeY = second.y - first.y;
        float firstEdgeZ = second.z - first.z;
        float secondEdgeX = third.x - first.x;
        float secondEdgeY = third.y - first.y;
        float secondEdgeZ = third.z - first.z;
        float crossX = firstEdgeY * secondEdgeZ - firstEdgeZ * secondEdgeY;
        float crossY = firstEdgeZ * secondEdgeX - firstEdgeX * secondEdgeZ;
        float crossZ = firstEdgeX * secondEdgeY - firstEdgeY * secondEdgeX;
        float crossLengthSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
        float uvDeterminant = (second.u - first.u) * (third.v - first.v)
                - (second.v - first.v) * (third.u - first.u);

        assertTrue(crossLengthSquared > 0.000000001F);
        assertTrue(Math.abs(uvDeterminant) > 0.000000001F);
    }

    private record JsonElementView(JsonObject object) {
    }

    private record TangentVertex(float x, float y, float z, float u, float v) {
    }

}
