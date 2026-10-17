package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Real pendant source models baked by vanilla, without a window or a world. */
public final class PendantUvLaunchChecks {
    private static final String PREFIX = "block/light/pendant/";
    private static final float EPSILON = 0.000001F;
    private static final String[] MATERIALS = {"borealis", "gelastrum", "mielonyx", "selenephos"};
    private static final String[] FRAMES = {"bronze", "silver"};

    private PendantUvLaunchChecks() { }

    public static void run() {
        SourceModels sources = new SourceModels();
        int checkedQuads = 0;
        int wrappers = 0;
        for (String size : new String[]{"small", "large"}) {
            Identifier masterId = id("light_pendant_" + size);
            JsonUnbakedModel master = sources.resolve(masterId);
            master.setParents(sources::resolve);
            checkSourceCounts(master, size);
            for (String material : MATERIALS) for (String frame : FRAMES) {
                Identifier wrapperId = id(material + "_diaphanes_light_pendant_" + size + "_" + frame);
                JsonUnbakedModel wrapper = sources.resolve(wrapperId);
                wrapper.setParents(sources::resolve);
                require(wrapper.getElements().equals(master.getElements()),
                        wrapperId + " no longer inherits the shared pendant geometry");
                Map<String, Identifier> textures = Map.of(
                        "stone", new Identifier("erydon", "block/" + material + "_diaphanes_block"),
                        "metal", new Identifier("erydon", "block/cover_" + frame + "_gloss"),
                        "bulb", new Identifier("erydon", "block/glazing_silver"));
                for (var element : wrapper.getElements()) for (var face : element.faces.values()) {
                    require(face.textureId.startsWith("#"), wrapperId + " lost a material reference");
                    Identifier expected = textures.get(face.textureId.substring(1));
                    require(expected != null && expected.equals(wrapper.resolveSprite(face.textureId).getTextureId()),
                            wrapperId + " resolves the wrong material for " + face.textureId);
                }
                require(textures.get("stone").equals(wrapper.resolveSprite("particle").getTextureId()),
                        wrapperId + " resolves the wrong particle material");
                wrappers++;
            }
            for (int resolution : new int[]{16, 32, 64}) {
                try (var contents = new SpriteContents(new Identifier("erydon", "pendant_uv_" + resolution),
                        new SpriteDimensions(resolution, resolution), new NativeImage(resolution, resolution, false),
                        AnimationResourceMetadata.EMPTY)) {
                    Sprite sprite = new AtlasSprite(contents, resolution);
                    require(sprite.getMinU() > 0 && sprite.getMaxU() < 1
                                    && sprite.getMinV() > 0 && sprite.getMaxV() < 1,
                            "Pendant test sprite must occupy an interior atlas region");
                    require(sprite.getAnimationFrameDelta() > 0 && sprite.getAnimationFrameDelta() < 1,
                            "Pendant proof must use vanilla's natural UV shrink");
                    for (int yaw : new int[]{0, 90, 180, 270}) {
                        ModelRotation rotation = ModelRotation.get(0, yaw);
                        var factory = new BakedQuadFactory();
                        int elementIndex = 0;
                        for (var element : master.getElements()) {
                            for (var entry : element.faces.entrySet()) {
                                BakedQuad quad = factory.bake(element.from, element.to, entry.getValue(), sprite,
                                        entry.getKey(), rotation, element.rotation, element.shade, masterId);
                                require(insideSprite(quad, sprite), masterId + " element " + elementIndex
                                        + " face " + entry.getKey() + " escapes its " + resolution
                                        + "px atlas sprite after Y=" + yaw);
                                checkedQuads++;
                            }
                            elementIndex++;
                        }
                        checkNegativeControls(factory, sprite, rotation, resolution, yaw);
                    }
                }
            }
        }
        require(wrappers == 16, "Expected all material/frame wrappers for both pendant sizes");
        require(checkedQuads == (1159 + 4529) * 12, "Pendant baked face count changed");
        System.out.println("ERYDON_PENDANT_UVS_OK: " + checkedQuads
                + " vanilla quads; 16 inherited material/frame wrappers; 16/32/64px; four Y rotations; negative controls detected");
    }

    private static void checkSourceCounts(JsonUnbakedModel model, String size) {
        boolean large = size.equals("large");
        require(model.getElements().size() == (large ? 848 : 218), size + " pendant geometry count changed");
        Map<String, Integer> materials = new HashMap<>();
        Map<String, Integer> cullFaces = new HashMap<>();
        for (var element : model.getElements()) for (var face : element.faces.values()) {
            materials.merge(face.textureId, 1, Integer::sum);
            cullFaces.merge(face.cullFace == null ? "none" : face.cullFace.asString(), 1, Integer::sum);
        }
        require(materials.equals(large ? Map.of("#metal", 2833, "#stone", 1536, "#bulb", 160)
                        : Map.of("#metal", 735, "#stone", 384, "#bulb", 40)),
                size + " pendant material/face counts changed");
        require(cullFaces.equals(large ? Map.of("none", 131, "up", 8, "down", 88, "east", 1080,
                        "north", 1061, "west", 1429, "south", 732)
                        : Map.of("none", 1119, "up", 8, "down", 32)),
                size + " pendant cullface counts changed");
    }

    private static void checkNegativeControls(BakedQuadFactory factory, Sprite sprite,
                                               ModelRotation rotation, int resolution, int yaw) {
        // These are the two original failure classes: a chart crossing V=0 and one above V=16.
        JsonUnbakedModel negative = JsonUnbakedModel.deserialize("""
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                  "south":{"texture":"#stone","uv":[0,-1,16,3]},
                  "north":{"texture":"#bulb","uv":[2,18,2,19]}
                }}]}
                """);
        for (var element : negative.getElements()) for (var entry : element.faces.entrySet()) {
            BakedQuad quad = factory.bake(element.from, element.to, entry.getValue(), sprite, entry.getKey(),
                    rotation, element.rotation, element.shade, new Identifier("erydon", "pendant_uv_negative"));
            require(!insideSprite(quad, sprite), "Pendant UV negative control was missed for " + entry.getKey()
                    + ", " + resolution + "px, Y=" + yaw);
        }
    }

    private static boolean insideSprite(BakedQuad quad, Sprite sprite) {
        int[] vertices = quad.getVertexData();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            float u = Float.intBitsToFloat(vertices[vertex * stride + 4]);
            float v = Float.intBitsToFloat(vertices[vertex * stride + 5]);
            if (!Float.isFinite(u) || !Float.isFinite(v)
                    || u < sprite.getMinU() - EPSILON || u > sprite.getMaxU() + EPSILON
                    || v < sprite.getMinV() - EPSILON || v > sprite.getMaxV() + EPSILON) return false;
        }
        return true;
    }

    private static Identifier id(String name) { return new Identifier("erydon", PREFIX + name); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class SourceModels {
        private final Map<Identifier, JsonUnbakedModel> models = new HashMap<>();

        JsonUnbakedModel resolve(Identifier id) {
            require(id.getNamespace().equals("erydon"), "Unexpected pendant parent namespace " + id);
            return models.computeIfAbsent(id, key -> {
                String resource = "assets/" + key.getNamespace() + "/models/" + key.getPath() + ".json";
                var stream = PendantUvLaunchChecks.class.getClassLoader().getResourceAsStream(resource);
                require(stream != null, "Missing pendant source " + key);
                try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    JsonUnbakedModel model = JsonUnbakedModel.deserialize(reader);
                    model.id = key.toString();
                    return model;
                } catch (IOException exception) {
                    throw new UncheckedIOException("Could not read pendant source " + key, exception);
                }
            });
        }
    }

    private static final class AtlasSprite extends Sprite {
        AtlasSprite(SpriteContents contents, int resolution) {
            super(new Identifier("erydon", "pendant_uv_atlas"), contents,
                    resolution * 8, resolution * 8, resolution * 2, resolution * 3);
        }
    }
}
