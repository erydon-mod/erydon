package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.CorniceBlock;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class CorniceBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_ROOT = "block/cornice/";

    private final BakedModel wrapped;
    private final Sprite particle;

    public CorniceBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof CorniceBlock)
                || !state.contains(CorniceBlock.FACING)
                || !state.contains(CorniceBlock.SHAPE)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Style style = styleForBlockPath(blockId.getPath());
        if (style == null) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        int rotation = rotationForFacing(state.get(CorniceBlock.FACING));
        switch (state.get(CorniceBlock.SHAPE)) {
            case STRAIGHT -> emit(context, blockId.getPath(), "straight", rotation);
            case INNER_CORNER -> emitInnerCorner(context, blockId.getPath(), style, rotation);
            case OUTER_CORNER -> emit(context, blockId.getPath(), style.outerSuffix, style.outerRotation(rotation));
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    private static void emitInnerCorner(RenderContext context, String blockPath, Style style, int rotation) {
        if (style == Style.MODERN) {
            emit(context, blockPath, "inner", rotation);
            return;
        }

        emit(context, blockPath, "straight", rotation);
        emit(context, blockPath, "straight", rotation - 90);
    }

    private static void emit(RenderContext context, String blockPath, String suffix, int degrees) {
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockPath, suffix));
        if (model != null) {
            emitTransformed(context, model, degrees);
        }
    }

    public static String[] modelSuffixes(String blockPath) {
        Style style = styleForBlockPath(ErydonIdMigration.legacyResourcePath(blockPath));
        if (style == null) {
            return new String[0];
        }
        return style.suffixes;
    }

    public static Identifier modelId(String blockPath, String suffix) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = ErydonIdNaming.isAged(resourcePath);
        String basePath = ErydonIdNaming.withoutAged(resourcePath);
        Style style = styleForBlockPath(basePath);
        if (style == null) {
            return new Identifier(Erydon.MOD_ID, MODEL_ROOT + basePath + "_" + suffix + (aged ? "_aged" : ""));
        }

        return new Identifier(Erydon.MOD_ID,
                MODEL_ROOT + style.path + "/" + basePath + "_" + suffix + (aged ? "_aged" : ""));
    }

    private static Style styleForBlockPath(String blockPath) {
        blockPath = ErydonIdMigration.legacyResourcePath(blockPath);
        if (blockPath.contains("cornice_gothic")) {
            return Style.GOTHIC;
        }
        if (blockPath.contains("cornice_georgian")) {
            return Style.GEORGIAN;
        }
        if (blockPath.contains("cornice_guilloche")) {
            return Style.GUILLOCHE;
        }
        if (blockPath.contains("cornice_modern")) {
            return Style.MODERN;
        }
        return null;
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

    private enum Style {
        GOTHIC("gothic", "outer_corner", -90, new String[]{"straight", "outer_corner"}),
        GEORGIAN("georgian", "outer_corner", -90, new String[]{"straight", "outer_corner"}),
        GUILLOCHE("guilloche", "outer_corner", -90, new String[]{"straight", "outer_corner"}),
        MODERN("modern", "outer", 0, new String[]{"straight", "inner", "outer"});

        private final String path;
        private final String outerSuffix;
        private final int outerRotationOffset;
        private final String[] suffixes;

        Style(String path, String outerSuffix, int outerRotationOffset, String[] suffixes) {
            this.path = path;
            this.outerSuffix = outerSuffix;
            this.outerRotationOffset = outerRotationOffset;
            this.suffixes = suffixes;
        }

        private int outerRotation(int baseRotation) {
            return baseRotation + outerRotationOffset;
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!(state != null
                && state.getBlock() instanceof CorniceBlock
                && state.contains(CorniceBlock.FACING)
                && state.contains(CorniceBlock.SHAPE))) {
            return wrapped.getQuads(state, face, random);
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return wrapped.getQuads(state, face, random);
        }

        Style style = styleForBlockPath(blockId.getPath());
        if (style == null) {
            return wrapped.getQuads(state, face, random);
        }

        int rotation = rotationForFacing(state.get(CorniceBlock.FACING));
        List<BakedQuad> quads = new ArrayList<>();
        switch (state.get(CorniceBlock.SHAPE)) {
            case STRAIGHT -> addModelQuads(quads, blockId.getPath(), "straight", rotation, face, random);
            case INNER_CORNER -> addInnerCornerQuads(quads, blockId.getPath(), style, rotation, face, random);
            case OUTER_CORNER -> addModelQuads(quads, blockId.getPath(), style.outerSuffix, style.outerRotation(rotation), face, random);
        }
        return quads;
    }

    private static void addInnerCornerQuads(List<BakedQuad> quads,
                                            String blockPath,
                                            Style style,
                                            int rotation,
                                            Direction face,
                                            Random random) {
        if (style == Style.MODERN) {
            addModelQuads(quads, blockPath, "inner", rotation, face, random);
            return;
        }

        addModelQuads(quads, blockPath, "straight", rotation, face, random);
        addModelQuads(quads, blockPath, "straight", rotation - 90, face, random);
    }

    private static void addModelQuads(List<BakedQuad> quads,
                                      String blockPath,
                                      String suffix,
                                      int degrees,
                                      Direction face,
                                      Random random) {
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockPath, suffix));
        AxiomFallbackQuads.add(quads, model, degrees, face, random, true);
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
