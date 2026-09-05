package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ColumnOrientationRenderingTest {
    @Test
    void diagonalComposesWithTiltedGeometryWithoutChangingHeightUvsOrSourceData() {
        BakedQuad source = fixture();
        int[] original = source.getVertexData().clone();
        BakedQuad rotated = AxiomFallbackQuads.rotateColumnDiagonal(source);
        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            double x = Float.intBitsToFloat(original[offset]) - 0.5;
            double z = Float.intBitsToFloat(original[offset + 2]) - 0.5;
            assertEquals(0.5 + (x - z) / Math.sqrt(2), value(rotated, i, 0), 0.000001);
            assertEquals(0.5 + (x + z) / Math.sqrt(2), value(rotated, i, 2), 0.000001);
            for (int preserved : new int[]{1, 3, 4, 5, 6}) {
                assertEquals(original[offset + preserved], rotated.getVertexData()[offset + preserved]);
            }
            int normal = rotated.getVertexData()[offset + 7];
            assertEquals(-72, (byte) normal);
            assertEquals(76, (byte) (normal >> 8));
            assertEquals(-72, (byte) (normal >> 16));
        }
        assertArrayEquals(original, source.getVertexData());
        assertEquals(source.getColorIndex(), rotated.getColorIndex());
        assertEquals(source.hasShade(), rotated.hasShade());
    }

    @Test
    void diagonalAxiomGeometryAppearsOnceAndIsNeverNeighbourCulled() {
        BakedModel model = model(fixture());
        Random random = Random.create(123);
        assertEquals(7, AxiomFallbackQuads.collectColumn(model, 45, null, random).size());
        for (Direction face : Direction.values()) {
            assertTrue(AxiomFallbackQuads.collectColumn(model, 45, face, random).isEmpty());
            assertEquals(1, AxiomFallbackQuads.collectColumn(model, 0, face, random).size());
        }
    }

    @Test
    void fabricRotationMatchesAxiomAndClearsDiagonalFaceHints() {
        BakedQuad source = fixture();
        BakedQuad expected = AxiomFallbackQuads.rotateColumnDiagonal(source);
        float[][] positions = new float[4][3];
        float[][] normals = new float[4][3];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) positions[i][j] = value(source, i, j);
            normals[i] = new float[]{-0.8F, 0.6F, 0};
        }
        AtomicReference<Direction> cull = new AtomicReference<>(Direction.WEST);
        AtomicReference<Direction> nominal = new AtomicReference<>(Direction.WEST);
        MutableQuadView quad = (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MutableQuadView.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "x" -> positions[(int) args[0]][0];
                        case "y" -> positions[(int) args[0]][1];
                        case "z" -> positions[(int) args[0]][2];
                        case "pos" -> { positions[(int) args[0]] = new float[]{(float) args[1], (float) args[2], (float) args[3]}; yield proxy; }
                        case "hasNormal" -> true;
                        case "normalX" -> normals[(int) args[0]][0];
                        case "normalY" -> normals[(int) args[0]][1];
                        case "normalZ" -> normals[(int) args[0]][2];
                        case "normal" -> { normals[(int) args[0]] = new float[]{(float) args[1], (float) args[2], (float) args[3]}; yield proxy; }
                        case "lightFace" -> Direction.WEST;
                        case "cullFace" -> { if (args == null) yield cull.get(); cull.set((Direction) args[0]); yield proxy; }
                        case "nominalFace" -> { if (args == null) yield nominal.get(); nominal.set((Direction) args[0]); yield proxy; }
                        default -> throw new AssertionError(method.getName());
                    };
                });
        AtomicReference<RenderContext.QuadTransform> transform = new AtomicReference<>();
        AtomicInteger popped = new AtomicInteger();
        RenderContext context = (RenderContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RenderContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "pushTransform" -> transform.set((RenderContext.QuadTransform) args[0]);
                        case "popTransform" -> popped.incrementAndGet();
                        case "fallbackConsumer" -> { return (Consumer<BakedModel>) model -> assertTrue(transform.get().transform(quad)); }
                        default -> throw new AssertionError(method.getName());
                    }
                    return null;
                });
        WorldAlignedYRotation.emit(context, model(source), 45);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) assertEquals(value(expected, i, j), positions[i][j], 0.000001);
            assertEquals(-0.8 / Math.sqrt(2), normals[i][0], 0.000001);
            assertEquals(0.6, normals[i][1], 0.000001);
            assertEquals(-0.8 / Math.sqrt(2), normals[i][2], 0.000001);
        }
        assertNull(cull.get());
        assertNull(nominal.get());
        assertEquals(1, popped.get());
    }

    private static float value(BakedQuad quad, int vertex, int component) {
        return Float.intBitsToFloat(quad.getVertexData()[vertex * 8 + component]);
    }

    private static BakedQuad fixture() {
        int[] data = new int[32];
        // A tilted side (as on a detailed capital), with an overhanging corner.
        float[][] positions = {{0.1F, 0, 0}, {0.85F, 1, 0}, {0.85F, 1, 1}, {0.1F, 0, 1}};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) data[i * 8 + j] = Float.floatToRawIntBits(positions[i][j]);
            data[i * 8 + 3] = -1;
            data[i * 8 + 4] = Float.floatToRawIntBits(i / 4.0F);
            data[i * 8 + 5] = Float.floatToRawIntBits(1 - i / 4.0F);
            data[i * 8 + 6] = 0x00F000F0;
            data[i * 8 + 7] = (-102 & 255) | (76 << 8);
        }
        return new BakedQuad(data, 2, Direction.WEST, null, true);
    }

    private static BakedModel model(BakedQuad quad) {
        return new BakedModel() {
            @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) { return List.of(quad); }
            @Override public boolean useAmbientOcclusion() { return true; }
            @Override public boolean hasDepth() { return true; }
            @Override public boolean isSideLit() { return true; }
            @Override public boolean isBuiltin() { return false; }
            @Override public Sprite getParticleSprite() { return null; }
            @Override public ModelTransformation getTransformation() { return null; }
            @Override public ModelOverrideList getOverrides() { return null; }
        };
    }
}
