package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Distinguishes assembled arch lists from ordinary, disjoint vanilla face buckets. */
final class ArchQuadSources {
    /** For supported arch states, getQuads(state, null, random) contains every source face once. */
    interface Complete {}

    // Resolve the Minecraft method by signature, so both named development and
    // intermediary production names work without reflective name remapping.
    private static final String QUADS_METHOD = Arrays.stream(BakedModel.class.getMethods())
            .filter(method -> method.getReturnType() == List.class
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{
                    BlockState.class, Direction.class, Random.class}))
            .map(Method::getName).findFirst().orElseThrow();
    private static final ClassValue<Boolean> TRANSPARENT = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod(QUADS_METHOD, BlockState.class, Direction.class, Random.class)
                        .getDeclaringClass() == ForwardingBakedModel.class
                        && type.getMethod("getWrappedModel").getDeclaringClass() == ForwardingBakedModel.class;
            } catch (ReflectiveOperationException | SecurityException unsupported) {
                return false;
            }
        }
    };

    private ArchQuadSources() {}

    /** Called once when the renderer is constructed, never for each emitted block. */
    static boolean hasCompleteNullBucket(BakedModel model) {
        for (int depth = 0; depth < 32 && model != null; depth++) {
            if (model instanceof Complete) return true;
            if (!(model instanceof ForwardingBakedModel forwarding)
                    || !TRANSPARENT.get(model.getClass())) return false;
            model = forwarding.getWrappedModel();
        }
        return false;
    }
}
