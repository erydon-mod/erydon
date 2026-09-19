package com.oliver.erydon.client.model;

import com.oliver.erydon.client.ErydonHighPolish;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Water and polished stone cannot safely share the shader's translucent terrain buffer. */
public final class HighPolishWaterModel extends ForwardingBakedModel {
    private static final Identifier PHASE = new Identifier("erydon", "polish_water");
    private static final Direction[] NEIGHBOURS = Direction.values();
    private static final ThreadLocal<BlockPos.Mutable> CURSOR = ThreadLocal.withInitial(BlockPos.Mutable::new);
    private static final WaterLookup<BlockRenderView> WATER = (view, pos) -> view.getFluidState(pos).isIn(FluidTags.WATER);
    private static final Map<RenderMaterial, RenderMaterial> OPAQUE = new ConcurrentHashMap<>();
    private static final Map<RenderMaterial, RenderMaterial> POLISHED = new ConcurrentHashMap<>();
    private final boolean window;

    private HighPolishWaterModel(BakedModel model, boolean window) {
        wrapped = model;
        this.window = window;
    }

    public static void register() {
        if (!ErydonHighPolish.activeSettings().enabled()) return;
        ModelLoadingPlugin.register(context -> {
            OPAQUE.clear();
            POLISHED.clear();
            context.modifyModelAfterBake().addPhaseOrdering(SynapheiaModelLoadingPlugin.CTM_PHASE, PHASE);
            context.modifyModelAfterBake().register(PHASE, (model, bake) -> {
                if (model == null || !(bake.id() instanceof ModelIdentifier id)
                        || "inventory".equals(id.getVariant())
                        || !ErydonHighPolish.usesHighPolish(id.getNamespace(), id.getPath())) return model;
                return new HighPolishWaterModel(model, id.getPath().contains("_window_"));
            });
        });
    }

    @Override public boolean isVanillaAdapter() { return false; }

    @Override public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos,
                                         Supplier<Random> random, RenderContext context) {
        // Only mesh rebuilds inspect water. No new geometry, frame callbacks or render passes.
        if (!touchesWater(view, state, pos)) {
            wrapped.emitBlockQuads(view, state, pos, random, context);
            return;
        }
        context.pushTransform(quad -> {
            // Normal window glazing is genuinely transparent; preserve it.
            if (!window || quad.colorIndex() != 0) quad.material(opaque(quad.material()));
            return true;
        });
        try { wrapped.emitBlockQuads(view, state, pos, random, context); }
        finally { context.popTransform(); }
    }

    static boolean touchesWater(BlockRenderView view, BlockState state, BlockPos pos) {
        return touchesWater(view, state.getFluidState().isIn(FluidTags.WATER), pos, WATER);
    }

    @FunctionalInterface
    interface WaterLookup<V> { boolean containsWater(V view, BlockPos pos); }

    static <V> boolean touchesWater(V view, boolean waterlogged, BlockPos pos, WaterLookup<V> water) {
        if (waterlogged) return true;
        BlockPos.Mutable cursor = CURSOR.get();
        for (Direction direction : NEIGHBOURS) {
            if (water.containsWater(view, cursor.set(pos).move(direction))) return true;
        }
        return false;
    }

    static RenderMaterial opaque(RenderMaterial source) {
        // CUTOUT preserves holes in metal overlays while writing stone into opaque depth.
        return OPAQUE.computeIfAbsent(source, material -> RendererAccess.INSTANCE.getRenderer()
                .materialFinder().copyFrom(material).blendMode(BlendMode.CUTOUT).find());
    }

    static RenderMaterial polished(RenderMaterial source) {
        if (source.blendMode() == BlendMode.TRANSLUCENT) return source;
        return POLISHED.computeIfAbsent(source, material -> RendererAccess.INSTANCE.getRenderer()
                .materialFinder().copyFrom(material).blendMode(BlendMode.TRANSLUCENT).find());
    }
}
