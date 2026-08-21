package com.oliver.erydon.client.model;

import com.oliver.erydon.block.GeorgianWallSlopeResolver;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.List;
import java.util.function.Supplier;

final class GeorgianWallSlopeBakedModel implements BakedModel, FabricBakedModel {
    private final BakedModel wrapped;
    private final GeorgianWallSlopeModelLoadingPlugin.PreparedModels models;

    GeorgianWallSlopeBakedModel(BakedModel wrapped,
                                GeorgianWallSlopeModelLoadingPlugin.PreparedModels models) {
        this.wrapped = wrapped;
        this.models = models;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    @SuppressWarnings("removal")
    public void emitBlockQuads(BlockRenderView view,
                               BlockState state,
                               BlockPos pos,
                               Supplier<Random> randomSupplier,
                               RenderContext context) {
        GeorgianWallSlopeResolver.Mode mode = GeorgianWallSlopeResolver.resolve(view, state, pos);
        GeorgianWallSlopeGeometry geometry = models.forMode(mode);
        if (!mode.isSlope() || geometry == null) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        // The particle sprite comes from the active material wrapper, so all
        // 159 production variants and resource-pack overrides retain their
        // own surface instead of relying on a hard-coded material list.
        Sprite sprite = wrapped.getParticleSprite();
        geometry.emit(context, mode.uphill(), sprite);
        GeorgianWallSlopeGeometry flatSideArm = models.flatSideArm();
        if (flatSideArm != null) {
            for (Direction direction : GeorgianWallSlopeResolver.flatCornerDirections(
                    state,
                    mode.uphill()
            )) {
                flatSideArm.emit(context, direction, sprite);
            }
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack,
                              Supplier<Random> randomSupplier,
                              RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        return wrapped.getQuads(state, face, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return wrapped.useAmbientOcclusion();
    }

    @Override
    public boolean hasDepth() {
        return wrapped.hasDepth();
    }

    @Override
    public boolean isSideLit() {
        return wrapped.isSideLit();
    }

    @Override
    public boolean isBuiltin() {
        return wrapped.isBuiltin();
    }

    @Override
    public Sprite getParticleSprite() {
        return wrapped.getParticleSprite();
    }

    @Override
    public ModelTransformation getTransformation() {
        return wrapped.getTransformation();
    }

    @Override
    public ModelOverrideList getOverrides() {
        return wrapped.getOverrides();
    }
}
