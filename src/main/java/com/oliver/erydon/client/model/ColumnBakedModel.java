package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.ColumnBlock;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.util.ErydonIdNaming;
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

import java.util.List;
import java.util.function.Supplier;

public final class ColumnBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_ROOT = "block/column/";

    public static final String[] MODEL_SUFFIXES = {
            "plinth",
            "base",
            "base_narrow",
            "pillar",
            "capital",
            "capital_guilloche",
            "capital_narrow"
    };

    private static final List<String> GOTHIC_MODEL_SUFFIXES = List.of(
            "plinth",
            "base",
            "pillar",
            "capital"
    );

    private final BakedModel wrapped;
    private final Sprite particle;

    public ColumnBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof ColumnBlock)
                || !state.contains(ColumnBlock.PART)
                || !state.contains(ColumnBlock.BASE)
                || !state.contains(ColumnBlock.CAPITAL)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        String suffix = suffixForState(state);
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(state, suffix));
        if (model != null) {
            SharedGeometryChildModel.emit(context, model);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    private static String suffixForState(BlockState state) {
        if (isGothicColumn(state)) {
            return switch (state.get(ColumnBlock.PART)) {
                case PLINTH -> "plinth";
                case PILLAR -> "pillar";
                case BASE -> "base";
                case CAPITAL -> "capital";
            };
        }

        return switch (state.get(ColumnBlock.PART)) {
            case PLINTH -> "plinth";
            case PILLAR -> "pillar";
            case BASE -> state.get(ColumnBlock.BASE) == ColumnBlock.BaseStyle.NARROW ? "base_narrow" : "base";
            case CAPITAL -> capitalSuffix(state.get(ColumnBlock.CAPITAL), isCircularColumn(state));
        };
    }

    static String capitalSuffix(ColumnBlock.CapitalStyle style, boolean circular) {
        return switch (style) {
            case GUILLOCHE -> "capital_guilloche";
            case NARROW -> circular ? "capital_narrow" : "capital";
            case GEORGIAN -> "capital";
            case NONE -> "pillar";
        };
    }

    private static boolean isCircularColumn(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath().contains("column_circular");
    }

    private static boolean isGothicColumn(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath().contains("column_gothic");
    }

    private static Identifier modelId(BlockState state, String suffix) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        return modelId(blockId.getPath(), suffix);
    }

    public static Identifier modelId(String blockPath, String suffix) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = ErydonIdNaming.isAged(resourcePath);
        String basePath = ErydonIdNaming.withoutAged(resourcePath);
        String stylePath = stylePath(basePath);
        return new Identifier(Erydon.MOD_ID,
                MODEL_ROOT + stylePath + "/" + basePath + "_" + suffix + (aged ? "_aged" : ""));
    }

    public static List<String> modelSuffixes(String blockPath) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        if (resourcePath.contains("column_gothic")) {
            return GOTHIC_MODEL_SUFFIXES;
        }
        if (resourcePath.contains("column_circular")) {
            return List.of(MODEL_SUFFIXES);
        }

        return List.of(
                "plinth",
                "base",
                "base_narrow",
                "pillar",
                "capital",
                "capital_guilloche"
        );
    }

    private static String stylePath(String basePath) {
        if (basePath.contains("column_gothic")) {
            return "gothic";
        }
        if (basePath.contains("column_circular")) {
            return "circular";
        }
        return "square";
    }


    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!(state != null
                && state.getBlock() instanceof ColumnBlock
                && state.contains(ColumnBlock.PART)
                && state.contains(ColumnBlock.BASE)
                && state.contains(ColumnBlock.CAPITAL))) {
            return wrapped.getQuads(state, face, random);
        }

        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(state, suffixForState(state)));
        return AxiomFallbackQuads.collect(model, face, random);
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
