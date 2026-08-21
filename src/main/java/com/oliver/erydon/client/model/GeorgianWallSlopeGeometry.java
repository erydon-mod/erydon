package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.texture.Sprite;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GeorgianWallSlopeGeometry {
    private static final float EPSILON = 0.00001F;
    private static final float FLAT_SIDE_CENTRE_POST_START = 5.0F;

    private final List<Surface> surfaces;

    private GeorgianWallSlopeGeometry(List<Surface> surfaces) {
        this.surfaces = List.copyOf(surfaces);
    }

    static GeorgianWallSlopeGeometry load(ResourceManager resourceManager, Identifier id) throws IOException {
        return load(resourceManager, id, false);
    }

    static GeorgianWallSlopeGeometry loadFlatSideArm(ResourceManager resourceManager,
                                                      Identifier id) throws IOException {
        return load(resourceManager, id, true);
    }

    private static GeorgianWallSlopeGeometry load(ResourceManager resourceManager,
                                                  Identifier id,
                                                  boolean flatSideArmOnly) throws IOException {
        Resource resource = resourceManager.getResource(id)
                .orElseThrow(() -> new IOException("Missing Georgian wall slope model " + id));
        try (Reader reader = resource.getReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IOException("Georgian wall slope model must be a JSON object: " + id);
            }
            return flatSideArmOnly
                    ? parseFlatSideArm(root.getAsJsonObject(), id)
                    : parse(root.getAsJsonObject(), id);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Georgian wall slope model " + id, exception);
        }
    }

    int surfaceCount() {
        return surfaces.size();
    }

    int surfaceCount(String elementName) {
        return (int) surfaces.stream()
                .filter(surface -> elementName.equals(surface.elementName))
                .count();
    }

    void emit(RenderContext context,
              Direction uphill,
              Sprite sourceSprite) {
        QuadEmitter emitter = context.getEmitter();
        for (Surface surface : surfaces) {
            Vector3f[] rotated = rotate(surface.vertices, uphill);
            Direction face = closestDirection(rotated);
            emitSourceQuad(emitter, rotated, face, sourceSprite);
        }
    }

    static GeorgianWallSlopeGeometry parse(JsonObject model, Identifier id) throws IOException {
        return parse(model, id, false);
    }

    static GeorgianWallSlopeGeometry parseFlatSideArm(JsonObject model,
                                                       Identifier id) throws IOException {
        return parse(model, id, true);
    }

    private static GeorgianWallSlopeGeometry parse(JsonObject model,
                                                    Identifier id,
                                                    boolean flatSideArmOnly) throws IOException {
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null || elements.isEmpty()) {
            throw new IOException("Georgian wall slope model has no elements: " + id);
        }

        List<Surface> surfaces = new ArrayList<>();
        for (JsonElement elementValue : elements) {
            if (!elementValue.isJsonObject()) {
                continue;
            }
            JsonObject element = elementValue.getAsJsonObject();
            float[] from = vector3(element.get("from"), "from");
            float[] to = vector3(element.get("to"), "to");
            // The multipart flat side contains its own centre post. The slope
            // model already supplies that post, so retain only geometry which
            // reaches into the outward (authored north) half of the block.
            if (flatSideArmOnly
                    && Math.min(from[2], to[2]) >= FLAT_SIDE_CENTRE_POST_START) {
                continue;
            }
            Rotation rotation = Rotation.parse(element.get("rotation"));
            String elementName = element.has("name")
                    ? element.get("name").getAsString()
                    : "";
            JsonObject faces = element.getAsJsonObject("faces");
            if (faces == null) {
                continue;
            }

            for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
                Direction direction = Direction.byName(entry.getKey());
                if (direction == null) {
                    continue;
                }
                Vector3f[] vertices = faceVertices(from, to, direction);
                for (int index = 0; index < vertices.length; index++) {
                    vertices[index] = rotation.transform(vertices[index]).div(16.0F);
                }
                surfaces.add(new Surface(vertices, elementName));
            }
        }
        return new GeorgianWallSlopeGeometry(surfaces);
    }

    private static Vector3f[] faceVertices(float[] from, float[] to, Direction face) {
        float x1 = from[0];
        float y1 = from[1];
        float z1 = from[2];
        float x2 = to[0];
        float y2 = to[1];
        float z2 = to[2];
        return switch (face) {
            case NORTH -> new Vector3f[]{
                    new Vector3f(x2, y1, z1), new Vector3f(x1, y1, z1),
                    new Vector3f(x1, y2, z1), new Vector3f(x2, y2, z1)
            };
            case SOUTH -> new Vector3f[]{
                    new Vector3f(x1, y1, z2), new Vector3f(x2, y1, z2),
                    new Vector3f(x2, y2, z2), new Vector3f(x1, y2, z2)
            };
            case WEST -> new Vector3f[]{
                    new Vector3f(x1, y1, z1), new Vector3f(x1, y1, z2),
                    new Vector3f(x1, y2, z2), new Vector3f(x1, y2, z1)
            };
            case EAST -> new Vector3f[]{
                    new Vector3f(x2, y1, z2), new Vector3f(x2, y1, z1),
                    new Vector3f(x2, y2, z1), new Vector3f(x2, y2, z2)
            };
            case UP -> new Vector3f[]{
                    new Vector3f(x1, y2, z1), new Vector3f(x1, y2, z2),
                    new Vector3f(x2, y2, z2), new Vector3f(x2, y2, z1)
            };
            case DOWN -> new Vector3f[]{
                    new Vector3f(x1, y1, z2), new Vector3f(x1, y1, z1),
                    new Vector3f(x2, y1, z1), new Vector3f(x2, y1, z2)
            };
        };
    }

    private static Vector3f[] rotate(Vector3f[] source, Direction uphill) {
        int turns = switch (uphill) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        Vector3f[] result = new Vector3f[source.length];
        for (int index = 0; index < source.length; index++) {
            Vector3f vertex = source[index];
            result[index] = switch (turns) {
                case 1 -> new Vector3f(1.0F - vertex.z, vertex.y, vertex.x);
                case 2 -> new Vector3f(1.0F - vertex.x, vertex.y, 1.0F - vertex.z);
                case 3 -> new Vector3f(vertex.z, vertex.y, 1.0F - vertex.x);
                default -> new Vector3f(vertex);
            };
        }
        return result;
    }

    private static Direction closestDirection(Vector3f[] vertices) {
        Vector3f first = new Vector3f(vertices[1]).sub(vertices[0]);
        Vector3f second = new Vector3f(vertices[2]).sub(vertices[0]);
        Vector3f normal = first.cross(second);
        if (normal.lengthSquared() <= EPSILON) {
            return Direction.UP;
        }
        return Direction.getFacing(normal.x, normal.y, normal.z);
    }

    private static void emitSourceQuad(QuadEmitter emitter,
                                       Vector3f[] vertices,
                                       Direction face,
                                       Sprite sourceSprite) {
        for (int index = 0; index < vertices.length; index++) {
            Vector3f vertex = vertices[index];
            emitter.pos(index, vertex.x, vertex.y, vertex.z);
        }
        // Keep the source coordinates safely inside the base sprite so the outer
        // Synapheia pass can identify the material. Synapheia then derives the
        // repeat UVs from the real vertex positions and performs the only split.
        emitter.uv(0, 0.0F, 1.0F);
        emitter.uv(1, 1.0F, 1.0F);
        emitter.uv(2, 1.0F, 0.0F);
        emitter.uv(3, 0.0F, 0.0F);
        emitter.color(-1, -1, -1, -1);
        emitter.cullFace(null);
        emitter.nominalFace(face);
        emitter.spriteBake(sourceSprite, MutableQuadView.BAKE_NORMALIZED);
        emitter.emit();
    }

    private static float[] vector3(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be a three-number array");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException(name + " must contain exactly three numbers");
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private record Surface(Vector3f[] vertices, String elementName) {
    }


    private record Rotation(float[] origin, float angleRadians, Direction.Axis axis) {
        private static Rotation parse(JsonElement value) {
            if (value == null || !value.isJsonObject()) {
                return new Rotation(new float[]{8.0F, 8.0F, 8.0F}, 0.0F, Direction.Axis.Y);
            }
            JsonObject rotation = value.getAsJsonObject();
            float[] origin = rotation.has("origin")
                    ? vector3(rotation.get("origin"), "rotation origin")
                    : new float[]{8.0F, 8.0F, 8.0F};
            float angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0.0F;
            Direction.Axis axis = switch (rotation.has("axis") ? rotation.get("axis").getAsString() : "y") {
                case "x" -> Direction.Axis.X;
                case "z" -> Direction.Axis.Z;
                default -> Direction.Axis.Y;
            };
            return new Rotation(origin, (float) Math.toRadians(angle), axis);
        }

        private Vector3f transform(Vector3f source) {
            if (Math.abs(angleRadians) <= EPSILON) {
                return source;
            }
            Vector3f result = new Vector3f(source).sub(origin[0], origin[1], origin[2]);
            switch (axis) {
                case X -> result.rotateX(angleRadians);
                case Y -> result.rotateY(angleRadians);
                case Z -> result.rotateZ(angleRadians);
            }
            return result.add(origin[0], origin[1], origin[2]);
        }
    }
}
