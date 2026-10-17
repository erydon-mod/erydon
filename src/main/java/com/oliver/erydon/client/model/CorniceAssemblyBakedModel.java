package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.client.pom.CornicePartMaterialTransport;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Each saved part retains its own baked model, CTM rules, textures and shader identity. */
public final class CorniceAssemblyBakedModel extends ForwardingBakedModel {
    private final Function<BlockState, BakedModel> models;
    private final BooleanSupplier globalAo;

    public CorniceAssemblyBakedModel(BakedModel wrapped) {
        this(wrapped, state -> MinecraftClient.getInstance().getBlockRenderManager().getModel(state));
    }

    CorniceAssemblyBakedModel(BakedModel wrapped, Function<BlockState, BakedModel> models) {
        this(wrapped, models, MinecraftClient::isAmbientOcclusionEnabled);
    }

    CorniceAssemblyBakedModel(BakedModel wrapped, Function<BlockState, BakedModel> models, BooleanSupplier globalAo) {
        this.wrapped = wrapped;
        this.models = models;
        this.globalAo = globalAo;
    }

    @Override public boolean isVanillaAdapter() { return false; }
    @Override public boolean useAmbientOcclusion() { return true; }

    @Override
    public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos,
                               Supplier<Random> random, RenderContext context) {
        CorniceAssembly.Contents contents = CorniceAssembly.contents(view, pos);
        boolean ambientOcclusion = globalAo.getAsBoolean();
        for (BlockState cornice : contents.cornices()) emitPart(view, cornice, pos, random, context, ambientOcclusion);
        if (contents.cover() != null) emitPart(view, contents.cover(), pos, random, context, ambientOcclusion);
    }

    private void emitPart(BlockRenderView view, BlockState part, BlockPos pos,
                          Supplier<Random> random, RenderContext context, boolean globalAo) {
        BakedModel model = models.apply(part);
        if (model == null || model == this) return;
        PartView partView = new PartView(view, pos.toImmutable(), part);
        // A lit cover changes the carrier's luminance. Explicit per-part AO
        // prevents that from disabling ambient occlusion on an unlit cornice.
        PartAo transform = new PartAo(globalAo, model.useAmbientOcclusion() && part.getLuminance() == 0);
        boolean materialTransport = CornicePartMaterialTransport.supportsContext(context);
        BlockState previous = materialTransport ? CornicePartMaterialTransport.push(part) : null;
        context.pushTransform(transform);
        try {
            model.emitBlockQuads(partView, part, pos, random, new PartContext(context, part));
        } finally {
            context.popTransform();
            if (materialTransport) CornicePartMaterialTransport.restore(previous);
        }
    }

    /** State-only previews cannot infer saved parts; the internal carrier has an authored empty fallback. */
    @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) { return List.of(); }

    /** Vanilla multipart fallbacks must select with the original part state, not the carrier state. */
    @SuppressWarnings("removal")
    private record PartContext(RenderContext source, BlockState part)
            implements RenderContext, CornicePartMaterialTransport.SourceContext {
        @Override public RenderContext originalContext() { return source; }
        @Override public net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter getEmitter() { return source.getEmitter(); }
        @Override public boolean hasTransform() { return source.hasTransform(); }
        @Override public void pushTransform(QuadTransform transform) { source.pushTransform(transform); }
        @Override public void popTransform() { source.popTransform(); }
        @Override public boolean isFaceCulled(Direction face) { return false; }
        @Override public BakedModelConsumer bakedModelConsumer() {
            return new BakedModelConsumer() {
                @Override public void accept(BakedModel model) { source.bakedModelConsumer().accept(model, part); }
                @Override public void accept(BakedModel model, BlockState explicit) { source.bakedModelConsumer().accept(model, explicit); }
            };
        }
    }

    private static final class PartAo implements RenderContext.QuadTransform {
        private final TriState ao;
        private final boolean globalAo;
        private final MaterialFinder finder = RendererAccess.INSTANCE.getRenderer().materialFinder();
        private RenderMaterial source;
        private RenderMaterial replacement;

        private PartAo(boolean globalAo, boolean ambientOcclusion) {
            this.globalAo = globalAo;
            ao = globalAo && ambientOcclusion ? TriState.TRUE : TriState.FALSE;
        }

        @Override public boolean transform(MutableQuadView quad) {
            RenderMaterial material = quad.material();
            if (material.ambientOcclusion() != TriState.DEFAULT && globalAo) return true;
            if (material != source) {
                source = material;
                replacement = finder.copyFrom(material).ambientOcclusion(ao).find();
            }
            quad.material(replacement);
            return true;
        }
    }

    /** Only the current model sees its original state; saved assembly attachments remain available to join discovery. */
    private record PartView(BlockRenderView delegate, BlockPos owner, BlockState part) implements RenderAttachedBlockView {
        @Override public BlockState getBlockState(BlockPos pos) { return owner.equals(pos) ? part : delegate.getBlockState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return delegate.getBlockEntity(pos); }
        @Override public Object getBlockEntityRenderAttachment(BlockPos pos) {
            return delegate instanceof RenderAttachedBlockView attached
                    ? attached.getBlockEntityRenderAttachment(pos) : RenderAttachedBlockView.super.getBlockEntityRenderAttachment(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return delegate.getFluidState(pos); }
        @Override public float getBrightness(Direction direction, boolean shaded) { return delegate.getBrightness(direction, shaded); }
        @Override public LightingProvider getLightingProvider() { return delegate.getLightingProvider(); }
        @Override public int getColor(BlockPos pos, ColorResolver resolver) { return delegate.getColor(pos, resolver); }
        @Override public int getHeight() { return delegate.getHeight(); }
        @Override public int getBottomY() { return delegate.getBottomY(); }
    }
}
