package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ShallowSlopeBlock.SlopeShape;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShallowSlopePreviewTest {
    @Test
    void previewsMatchPlacedGeometryForBothVariantsAllShapesAndRotations() throws Exception {
        try (SpriteContents contents = contents()) {
            Sprite sprite = new TestSprite(contents);
            Class<?> spritesType = Class.forName(ShallowSlopeBakedModel.class.getName() + "$FaceSprites");
            Object sprites = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{spritesType}, (proxy, method, args) -> null);
            int configurations = 0;
            for (boolean upper : new boolean[]{false, true}) {
                for (SlopeShape shape : SlopeShape.values()) {
                    String methodName = shape == SlopeShape.STRAIGHT ? "emitNativeStraightSlope"
                            : isOuter(shape) ? "emitNativeOuterCorner" : "emitNativeInnerCorner";
                    Method emit = ShallowSlopeBakedModel.class.getDeclaredMethod(methodName,
                            RenderContext.class, spritesType, FixedSlopeRotation.class, boolean.class);
                    emit.setAccessible(true);
                    for (int x : new int[]{0, 180}) {
                        for (int y : new int[]{0, 90, 180, 270}) {
                            Capture capture = new Capture();
                            RenderContext context = (RenderContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{RenderContext.class}, (proxy, method, args) -> capture.emitter);
                            emit.invoke(null, context, sprites, FixedSlopeRotation.of(x, y), upper);
                            List<BakedQuad> preview = ShallowSlopeBakedModel.previewQuads(sprite, upper, shape, x, y);
                            String label = upper + " " + shape + " " + x + "/" + y;
                            assertEquals(capture.sources.size(), preview.size(), label);
                            for (int q = 0; q < preview.size(); q++) {
                                for (int v = 0; v < 4; v++) {
                                    for (int axis = 0; axis < 3; axis++) {
                                        assertEquals(capture.sources.get(q)[v][axis], value(preview.get(q), v, axis),
                                                0.000001, label);
                                    }
                                    assertEquals(sprite.getFrameU(capture.sources.get(q)[v][3]),
                                            value(preview.get(q), v, 4), 0.000001, label);
                                    assertEquals(sprite.getFrameV(capture.sources.get(q)[v][4]),
                                            value(preview.get(q), v, 5), 0.000001, label);
                                }
                            }
                            configurations++;
                        }
                    }
                }
            }
            assertEquals(80, configurations);
        }
    }

    @Test
    void shallowSurfacesHaveCorrectHeightsAndUpperVariantsHaveClosingEnds() {
        try (SpriteContents contents = contents()) {
            Sprite sprite = new TestSprite(contents);
            for (boolean upper : new boolean[]{false, true}) {
                float base = upper ? 0.5F : 0;
                for (SlopeShape shape : SlopeShape.values()) {
                    List<BakedQuad> quads = ShallowSlopeBakedModel.previewQuads(sprite, upper, shape, 0, 0);
                    for (BakedQuad quad : quads) {
                        if (quad.getFace() != Direction.UP) continue;
                        for (int v = 0; v < 4; v++) {
                            float x = value(quad, v, 0), z = value(quad, v, 2);
                            float height = shape == SlopeShape.STRAIGHT ? 1 - x
                                    : isOuter(shape) ? 1 - Math.max(x, z) : Math.max(1 - x, z);
                            assertEquals(base + 0.5F * height, value(quad, v, 1), 0.0006,
                                    upper + " " + shape);
                        }
                    }
                    if (upper && (shape == SlopeShape.STRAIGHT || isOuter(shape))) {
                        assertTrue(quads.stream().anyMatch(q -> q.getFace() == Direction.EAST
                                && maximumY(q) == 0.5F), "Upper slope must close its half-height east end");
                        if (isOuter(shape)) {
                            assertTrue(quads.stream().anyMatch(q -> q.getFace() == Direction.SOUTH
                                    && maximumY(q) == 0.5F), "Upper outer corner must close its south end");
                        }
                    }
                }
            }
        }
    }

    private static boolean isOuter(SlopeShape shape) {
        return shape == SlopeShape.OUTER_LEFT || shape == SlopeShape.OUTER_RIGHT;
    }

    private static float value(BakedQuad quad, int vertex, int component) {
        return Float.intBitsToFloat(quad.getVertexData()[vertex * 8 + component]);
    }

    private static float maximumY(BakedQuad quad) {
        float max = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < 4; v++) max = Math.max(max, value(quad, v, 1));
        return max;
    }

    private static SpriteContents contents() {
        return new SpriteContents(new Identifier("erydon", "preview_fixture"), new SpriteDimensions(16, 16),
                new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY);
    }

    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) {
            super(new Identifier("erydon", "fixture_atlas"), contents, 64, 64, 16, 32);
        }
    }

    // Capture the placed vertices before POM subdivides them; subdivision changes
    // quad count but preserves the visible surface that the hologram must match.
    private static final class Capture {
        float[][] data = new float[4][8];
        boolean[] normals = new boolean[4];
        final List<float[][]> sources = new ArrayList<>();
        final Map<String, Object> properties = new HashMap<>();
        final QuadEmitter emitter = (QuadEmitter) Proxy.newProxyInstance(
                QuadEmitter.class.getClassLoader(), new Class<?>[]{QuadEmitter.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    int count = args == null ? 0 : args.length;
                    if (name.equals("emit")) {
                        data = new float[4][8];
                        normals = new boolean[4];
                        properties.clear();
                    } else if (name.equals("pos") || name.equals("uv") || name.equals("normal")) {
                        int offset = name.equals("pos") ? 0 : name.equals("uv") ? 3 : 5;
                        for (int i = 1; i < count; i++) data[(int) args[0]][offset + i - 1] = (float) args[i];
                        if (name.equals("normal")) normals[(int) args[0]] = true;
                    } else if (List.of("x", "y", "z", "u", "v", "normalX", "normalY", "normalZ").contains(name)) {
                        return data[(int) args[0]][List.of("x", "y", "z", "u", "v", "normalX", "normalY", "normalZ").indexOf(name)];
                    } else if (name.equals("hasNormal")) return normals[(int) args[0]];
                    else if (List.of("material", "nominalFace", "cullFace", "colorIndex", "tag").contains(name)) {
                        if (count == 1) properties.put(name, args[0]);
                        else return properties.getOrDefault(name, method.getReturnType() == int.class ? 0 : null);
                    } else if (name.equals("color") && count == 4) {
                        sources.add(Arrays.stream(data).map(float[]::clone).toArray(float[][]::new));
                    } else if (name.equals("color") && count == 1) return -1;
                    else if (name.equals("lightmap") && count == 1) return 0;
                    return proxy;
                });
    }
}
