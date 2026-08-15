package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.LayerVerticalBlock;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class LayerVerticalBakedModel implements BakedModel, FabricBakedModel {
    private static final String SOLID_MODEL_ROOT = "block/layer/layer/";
    private static final String GLAZING_MODEL_ROOT = "block/glazing/layer/";

    private final BakedModel wrapped;
    private final Sprite particle;

    public LayerVerticalBakedModel(BakedModel wrapped) {
        this.wrapped = wrapped;
        this.particle = wrapped.getParticleSprite();
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
        if (!state.contains(LayerVerticalBlock.LAYERS) || !state.contains(LayerVerticalBlock.FACING)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace()) || !isLayerVerticalBlock(blockId.getPath())) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        int layers = state.get(LayerVerticalBlock.LAYERS);
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockId.getPath(), layers));
        if (model != null) {
            emitTransformed(context, model, rotationForFacing(state.get(LayerVerticalBlock.FACING)));
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    public static List<Identifier> modelIdsForBlock(String blockPath) {
        List<Identifier> ids = new ArrayList<>();
        for (int layers = 1; layers <= 8; layers++) {
            ids.add(modelId(blockPath, layers));
        }
        return ids;
    }

    public static Identifier modelId(String blockPath, int layers) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = resourcePath.endsWith("_aged");
        String basePath = aged ? resourcePath.substring(0, resourcePath.length() - "_aged".length()) : resourcePath;
        String suffix = "_depth" + (layers * 2) + (aged ? "_aged" : "");
        return new Identifier(Erydon.MOD_ID, modelRoot(basePath) + basePath + suffix);
    }

    public static boolean isLayerVerticalBlock(String path) {
        return path.contains("layer_vertical") && !path.contains("slope");
    }

    private static String modelRoot(String basePath) {
        return basePath.startsWith("glazing_") || basePath.contains("_diaphanes_") ? GLAZING_MODEL_ROOT : SOLID_MODEL_ROOT;
    }

    private static int rotationForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private static void emitTransformed(RenderContext context, BakedModel model, int degrees) {
        WorldAlignedYRotation.emit(context, model, degrees);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (state == null || !state.contains(LayerVerticalBlock.LAYERS) || !state.contains(LayerVerticalBlock.FACING)) {
            return wrapped.getQuads(state, face, random);
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace()) || !isLayerVerticalBlock(blockId.getPath())) {
            return wrapped.getQuads(state, face, random);
        }

        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockId.getPath(), state.get(LayerVerticalBlock.LAYERS)));
        List<BakedQuad> quads = new ArrayList<>();
        AxiomFallbackQuads.add(quads, model, rotationForFacing(state.get(LayerVerticalBlock.FACING)), face, random, true);
        return quads;
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
        return particle;
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
