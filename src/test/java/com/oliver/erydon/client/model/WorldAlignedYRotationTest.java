package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.random.Random;
import java.util.List;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WorldAlignedYRotationTest {
    @Test
    void quarterTurnsKeepFaceHintsAlignedWithGeometry() {
        for (Direction face : Direction.Type.HORIZONTAL) {
            for (int turns = 0; turns < 4; turns++) {
                for (boolean culled : new boolean[]{false, true}) {
                    check(face, turns, culled, false);
                    check(face, turns, culled, true);
                }
            }
        }
    }

    private void check(Direction face, int turns, boolean culled, boolean clearCull) {
        AtomicReference<Direction> cull = new AtomicReference<>(culled ? face : null);
        AtomicReference<Direction> nominal = new AtomicReference<>(face);
        float[][] positions = {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}};
        MutableQuadView quad = (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MutableQuadView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "lightFace" -> face;
                    case "x" -> positions[(int) args[0]][0];
                    case "y" -> positions[(int) args[0]][1];
                    case "z" -> positions[(int) args[0]][2];
                    case "pos" -> { positions[(int) args[0]] = new float[]{(float) args[1], (float) args[2], (float) args[3]}; yield proxy; }
                    case "hasNormal" -> false;
                    case "cullFace" -> {
                        if (args == null) yield cull.get();
                        cull.set((Direction) args[0]);
                        // Fabric's setter also replaces the nominal face (including null).
                        nominal.set((Direction) args[0]);
                        yield proxy;
                    }
                    case "nominalFace" -> {
                        if (args == null) yield nominal.get();
                        nominal.set((Direction) args[0]); yield proxy;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        AtomicReference<RenderContext.QuadTransform> transform = new AtomicReference<>();
        RenderContext context = (RenderContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RenderContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "pushTransform" -> transform.set((RenderContext.QuadTransform) args[0]);
                        case "popTransform" -> transform.set(null);
                        case "fallbackConsumer" -> { return (Consumer<BakedModel>) model -> {
                            if (transform.get() != null) assertTrue(transform.get().transform(quad));
                        }; }
                        default -> throw new AssertionError(method.getName());
                    }
                    return null;
                });
        BakedModel model = new BakedModel() {
            @Override public List<BakedQuad> getQuads(BlockState state, Direction side, Random random) { return List.of(); }
            @Override public boolean useAmbientOcclusion() { return true; }
            @Override public boolean hasDepth() { return true; }
            @Override public boolean isSideLit() { return true; }
            @Override public boolean isBuiltin() { return false; }
            @Override public Sprite getParticleSprite() { return null; }
            @Override public ModelTransformation getTransformation() { return null; }
            @Override public ModelOverrideList getOverrides() { return null; }
        };
        WorldAlignedYRotation.emit(context, model, turns * 90, clearCull);
        Direction expected = face;
        for (int i = 0; i < turns; i++) expected = expected.rotateYClockwise();
        String label = face + " turns=" + turns + " culled=" + culled + " clear=" + clearCull;
        assertEquals(expected, nominal.get(), label);
        assertEquals(culled && !clearCull ? expected : null, cull.get(), label);
        assertNull(transform.get());
    }
}
