package com.oliver.erydon.client.model;

import com.oliver.erydon.block.DoubleCircularColumnBlock;
import com.oliver.erydon.block.ColumnBlock;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Renders each part of the shared circular model in its own 2x2 cell. */
public final class DoubleCircularColumnBakedModel implements BakedModel, FabricBakedModel {
    private final BakedModel wrapped;
    public DoubleCircularColumnBakedModel(BakedModel wrapped) { this.wrapped = wrapped; }

    private static String suffix(BlockState state) {
        if (!(state.getBlock() instanceof DoubleCircularColumnBlock)) return null;
        return switch (state.get(DoubleCircularColumnBlock.SECTION)) {
            case BASE_LOWER, BASE_UPPER -> state.get(DoubleCircularColumnBlock.BASE) == ColumnBlock.BaseStyle.NARROW
                    ? "base_narrow" : "base";
            case SHAFT -> "pillar";
            case CAPITAL_LOWER, CAPITAL_UPPER -> ColumnBakedModel.capitalSuffix(
                    state.get(DoubleCircularColumnBlock.CAPITAL), true);
        };
    }

    private static BakedModel child(BlockState state, String suffix) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        String sourcePath = path.substring(0, path.length() - "_double".length());
        return MinecraftClient.getInstance().getBakedModelManager()
                .getModel(ColumnBakedModel.modelId(sourcePath, suffix));
    }

    private static int layer(BlockState state) {
        return switch (state.get(DoubleCircularColumnBlock.SECTION)) {
            case BASE_UPPER, CAPITAL_UPPER -> 1;
            default -> 0;
        };
    }

    @Override public boolean isVanillaAdapter() { return false; }

    @Override public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos,
                                         Supplier<Random> randomSupplier, RenderContext context) {
        String suffix = suffix(state);
        if (suffix == null) return;
        BakedModel model = child(state, suffix);
        if (model == null) return;
        boolean shaft = state.get(DoubleCircularColumnBlock.SECTION) == DoubleCircularColumnBlock.Section.SHAFT;
        int partX = state.get(DoubleCircularColumnBlock.X);
        int partZ = state.get(DoubleCircularColumnBlock.Z);
        SpriteFinder sprites = SpriteFinder.get(MinecraftClient.getInstance().getBakedModelManager()
                .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
        int[] passCount = {1};
        for (int pass = 0; pass < passCount[0]; pass++) {
            int currentPass = pass;
            context.pushTransform(quad -> DoubleColumnSlice.transform(
                    quad, layer(state), partX, partZ, shaft,
                    currentPass, passCount, sprites.find(quad)));
            try {
                SharedGeometryChildModel.emit(context, model);
            } finally {
                context.popTransform();
            }
        }
    }

    @Override public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (state == null) return wrapped.getQuads(null, face, random);
        String suffix = suffix(state);
        if (suffix == null || face != null) return List.of();
        BakedModel model = child(state, suffix);
        if (model == null) return List.of();
        boolean shaft = state.get(DoubleCircularColumnBlock.SECTION) == DoubleCircularColumnBlock.Section.SHAFT;
        int partX = state.get(DoubleCircularColumnBlock.X);
        int partZ = state.get(DoubleCircularColumnBlock.Z);
        List<BakedQuad> result = new ArrayList<>();
        for (Direction sourceFace : new Direction[]{null, Direction.DOWN, Direction.UP,
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            for (BakedQuad quad : model.getQuads(null, sourceFace, random)) {
                result.addAll(DoubleColumnSlice.bake(quad, layer(state), partX, partZ, shaft));
            }
        }
        return result;
    }

    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean hasDepth() { return wrapped.hasDepth(); }
    @Override public boolean isSideLit() { return wrapped.isSideLit(); }
    @Override public boolean isBuiltin() { return wrapped.isBuiltin(); }
    @Override public Sprite getParticleSprite() { return wrapped.getParticleSprite(); }
    @Override public ModelTransformation getTransformation() { return wrapped.getTransformation(); }
    @Override public ModelOverrideList getOverrides() { return wrapped.getOverrides(); }
}
