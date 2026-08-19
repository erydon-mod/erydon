package com.oliver.erydon.client.model;

import com.google.gson.Gson;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes deterministic ordinary-repeat oracle/prototype events for the supplied comparator. */
public final class SynapheiaParityDump {
    private static final String PREFIX = "ERYDON_SYNAPHEIA_METRIC ";
    private static final Gson GSON = new Gson();
    private static final List<int[]> OWNER_POSITIONS = List.of(
            new int[]{0, 64, 0},
            new int[]{-7, 64, -11},
            new int[]{15, 31, 15},
            new int[]{-1, 32, -1}
    );

    private SynapheiaParityDump() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected baseline and prototype JSONL paths.");
        }
        Path baseline = Path.of(args[0]).toAbsolutePath().normalize();
        Path prototype = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(baseline.getParent());
        Files.createDirectories(prototype.getParent());
        Files.writeString(baseline, dump(false), StandardCharsets.UTF_8);
        Files.writeString(prototype, dump(true), StandardCharsets.UTF_8);
    }

    private static String dump(boolean prototype) {
        StringBuilder output = new StringBuilder();
        for (int run = 1; run <= 2; run++) {
            String engine = prototype ? "synapheia" : "continuity";
            String runId = engine + "-ordinary-" + run;
            int sequence = 0;
            int surfaceIndex = 0;
            for (int ownerIndex = 0; ownerIndex < OWNER_POSITIONS.size(); ownerIndex++) {
                int[] owner = OWNER_POSITIONS.get(ownerIndex);
                for (Direction face : Direction.values()) {
                    String sourceId = "ordinary-" + ownerIndex + "-" + face.getName();
                    output.append(line(stage(runId, ++sequence, engine, owner, sourceId)));
                    Map<String, Object> surface = prototype
                            ? prototypeSurface(runId, ++sequence, run, owner, face, sourceId, surfaceIndex)
                            : oracleSurface(runId, ++sequence, run, owner, face, sourceId, surfaceIndex);
                    output.append(line(surface));
                    surfaceIndex++;
                }
            }
        }
        return output.toString();
    }

    private static Map<String, Object> stage(String runId,
                                             int sequence,
                                             String engine,
                                             int[] owner,
                                             String sourceId) {
        return fields(
                "event", "stage_invoked",
                "run_id", runId,
                "sequence", sequence,
                "scene", "ordinary",
                "engine", engine,
                "stage", "repeat",
                "model_identifier", "erydon:aganite_block#",
                "source_surface_id", sourceId,
                "block_pos", position(owner)
        );
    }

    private static Map<String, Object> oracleSurface(String runId,
                                                     int sequence,
                                                     int run,
                                                     int[] owner,
                                                     Direction face,
                                                     String sourceId,
                                                     int surfaceIndex) {
        List<SpiralStairCtmGeometry.Vertex> source = faceVertices(face);
        List<Map<String, Object>> vertices = new ArrayList<>(4);
        for (SpiralStairCtmGeometry.Vertex vertex : source) {
            float s = projectedS(face, vertex);
            float t = projectedT(face, vertex);
            vertices.add(vertexRecord(vertex, expectedU(face, s), expectedV(face, t)));
        }
        int tileIndex = referenceTileIndex(owner[0], owner[1], owner[2], face);
        return surface(runId, sequence, run, "continuity", owner, face, sourceId,
                surfaceIndex, 0, 0, vertices, tileIndex);
    }

    private static Map<String, Object> prototypeSurface(String runId,
                                                        int sequence,
                                                        int run,
                                                        int[] owner,
                                                        Direction face,
                                                        String sourceId,
                                                        int surfaceIndex) {
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(face, faceVertices(face));
        if (fragments.size() != 1) {
            throw new IllegalStateException("Ordinary face did not produce exactly one fragment: " + face);
        }
        SpiralStairCtmGeometry.Fragment fragment = fragments.get(0);
        List<Map<String, Object>> vertices = new ArrayList<>(4);
        for (SpiralStairCtmGeometry.CellVertex vertex : fragment.vertices()) {
            vertices.add(vertexRecord(vertex.vertex(),
                    SpiralStairCtmGeometry.u(face, vertex),
                    SpiralStairCtmGeometry.v(face, vertex)));
        }
        int x = owner[0] + SpiralStairCtmGeometry.offsetX(face, fragment);
        int y = owner[1] + SpiralStairCtmGeometry.offsetY(face, fragment);
        int z = owner[2] + SpiralStairCtmGeometry.offsetZ(face, fragment);
        int tileIndex = ErydonCtmService.repeatTileIndex(x, y, z, face);
        return surface(runId, sequence, run, "synapheia", owner, face, sourceId,
                surfaceIndex, fragment.cellS(), fragment.cellT(), vertices, tileIndex);
    }

    private static Map<String, Object> surface(String runId,
                                               int sequence,
                                               int run,
                                               String engine,
                                               int[] owner,
                                               Direction face,
                                               String sourceId,
                                               int surfaceIndex,
                                               int cellS,
                                               int cellT,
                                               List<Map<String, Object>> vertices,
                                               int tileIndex) {
        int offsetX = switch (face) {
            case UP, DOWN, NORTH, SOUTH -> cellS;
            default -> 0;
        };
        int offsetY = face.getAxis().isHorizontal() ? cellT : 0;
        int offsetZ = switch (face) {
            case UP, DOWN -> cellT;
            case EAST, WEST -> cellS;
            default -> 0;
        };
        String tile = "minecraft:optifine/ctm/aganite/" + tileIndex;
        return fields(
                "event", "surface_emitted",
                "run_id", runId,
                "sequence", sequence,
                "scene", "ordinary",
                "engine", engine,
                "mode", engine,
                "reload_generation", run,
                "block_id", "erydon:aganite_block",
                "blockstate", "",
                "block_pos", position(owner),
                "model_identifier", "erydon:aganite_block#",
                "source_surface_id", sourceId,
                "fragment_id", sourceId + "|cell-0,0,0|primitive-0",
                "surface_index", surfaceIndex,
                "surface_key", sourceId,
                "geometric_cell_offset", List.of(offsetX, offsetY, offsetZ),
                "expected_cell_offset", List.of(0, 0, 0),
                "geometric_cell_position", List.of(
                        owner[0] + offsetX, owner[1] + offsetY, owner[2] + offsetZ),
                "tile_index", tileIndex,
                "expected_tile_index", referenceTileIndex(owner[0], owner[1], owner[2], face),
                "tile_identifier", tile,
                "sprite_identifier", tile,
                "face_direction", face.getName(),
                "cull_face", face.getName(),
                "tint_index", -1,
                "material_binding", "default",
                "render_material", "default",
                "render_layer", "solid",
                "ao_intent", true,
                "diffuse_intent", true,
                "winding", List.of(0, 1, 2, 3),
                "cache_backing_object_id", engine + "-generation-" + run,
                "vertices", List.copyOf(vertices)
        );
    }

    private static List<SpiralStairCtmGeometry.Vertex> faceVertices(Direction face) {
        return List.of(
                vertex(face, 0.0F, 0.0F),
                vertex(face, 1.0F, 0.0F),
                vertex(face, 1.0F, 1.0F),
                vertex(face, 0.0F, 1.0F)
        );
    }

    private static SpiralStairCtmGeometry.Vertex vertex(Direction face, float s, float t) {
        float x;
        float y;
        float z;
        switch (face) {
            case UP -> { x = s; y = 1.0F; z = t; }
            case DOWN -> { x = s; y = 0.0F; z = t; }
            case EAST -> { x = 1.0F; y = t; z = s; }
            case WEST -> { x = 0.0F; y = t; z = s; }
            case SOUTH -> { x = s; y = t; z = 1.0F; }
            case NORTH -> { x = s; y = t; z = 0.0F; }
            default -> throw new IllegalStateException("Unexpected face " + face);
        }
        return new SpiralStairCtmGeometry.Vertex(
                x, y, z, -1, 0, true,
                face.getOffsetX(), face.getOffsetY(), face.getOffsetZ(),
                expectedU(face, s), expectedV(face, t));
    }

    private static Map<String, Object> vertexRecord(SpiralStairCtmGeometry.Vertex vertex,
                                                     float finalU,
                                                     float finalV) {
        return fields(
                "position", List.of(vertex.x(), vertex.y(), vertex.z()),
                "source_uv", List.of(vertex.sourceU(), vertex.sourceV()),
                "final_uv", List.of(finalU, finalV),
                "normal", List.of(vertex.normalX(), vertex.normalY(), vertex.normalZ()),
                "color", List.of(255, 255, 255, 255),
                "lightmap", List.of(0, 0)
        );
    }

    private static float projectedS(Direction face, SpiralStairCtmGeometry.Vertex vertex) {
        return face == Direction.EAST || face == Direction.WEST ? vertex.z() : vertex.x();
    }

    private static float projectedT(Direction face, SpiralStairCtmGeometry.Vertex vertex) {
        return face == Direction.UP || face == Direction.DOWN ? vertex.z() : vertex.y();
    }

    private static float expectedU(Direction face, float s) {
        return face == Direction.NORTH || face == Direction.EAST ? 1.0F - s : s;
    }

    private static float expectedV(Direction face, float t) {
        return face == Direction.UP ? t : 1.0F - t;
    }

    private static int referenceTileIndex(int x, int y, int z, Direction face) {
        int column;
        int row;
        switch (face) {
            case DOWN -> { column = Math.floorMod(x, 6); row = Math.floorMod(-z - 1, 6); }
            case UP -> { column = Math.floorMod(x, 6); row = Math.floorMod(z, 6); }
            case NORTH -> { column = Math.floorMod(-x - 1, 6); row = Math.floorMod(-y, 6); }
            case SOUTH -> { column = Math.floorMod(x, 6); row = Math.floorMod(-y, 6); }
            case WEST -> { column = Math.floorMod(z, 6); row = Math.floorMod(-y, 6); }
            case EAST -> { column = Math.floorMod(-z - 1, 6); row = Math.floorMod(-y, 6); }
            default -> throw new IllegalStateException("Unexpected face " + face);
        }
        return row * 6 + column;
    }

    private static List<Integer> position(int[] values) {
        return List.of(values[0], values[1], values[2]);
    }

    private static String line(Map<String, Object> value) {
        return PREFIX + GSON.toJson(value) + System.lineSeparator();
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], entries[index + 1]);
        }
        return fields;
    }
}
