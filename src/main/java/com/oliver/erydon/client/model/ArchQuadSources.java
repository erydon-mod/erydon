package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Distinguishes assembled arch lists from ordinary, disjoint vanilla face buckets. */
final class ArchQuadSources {
    /** For supported arch states, getQuads(state, null, random) contains every source face once. */
    interface Complete {}

    /** Optional authored bucket provenance for the same complete, ordered stream. */
    interface CullMetadata extends Complete {
        Sources sourceQuads(BlockState state, Random random);
    }

    /** Used only while building a cached arch template; ordinary collectors stay unchanged. */
    static final class Sources extends ArrayList<BakedQuad> {
        private final List<Direction> cullFaces = new ArrayList<>();

        void add(BakedQuad quad, Direction cullFace) {
            super.add(quad);
            cullFaces.add(cullFace);
        }

        @Override public boolean add(BakedQuad quad) {
            add(quad, null);
            return true;
        }

        Direction cullFace(int index) { return cullFaces.get(index); }

        void reflectCullFace(int index, ArchHorizontalReflection reflection) {
            cullFaces.set(index, reflection.face(cullFaces.get(index)));
        }
    }

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

    static CullMetadata cullMetadata(BakedModel model) {
        for (int depth = 0; depth < 32 && model != null; depth++) {
            if (model instanceof CullMetadata metadata) return metadata;
            if (!(model instanceof ForwardingBakedModel forwarding)
                    || !TRANSPARENT.get(model.getClass())) return null;
            model = forwarding.getWrappedModel();
        }
        return null;
    }

    /** No intermediate or external transform may move the proven boundary. */
    static boolean canCullThroughSynapheia(BakedModel wrapped, boolean externalTransform) {
        return !externalTransform && wrapped instanceof ArchRepeatCtmRenderer;
    }
}
