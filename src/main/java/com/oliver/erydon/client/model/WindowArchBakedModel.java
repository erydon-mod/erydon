package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.WindowArchBlock;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
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

public final class WindowArchBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_PATH = "block/window/arch/";
    private static final Object MATERIAL_LOCK = new Object();
    private static RenderMaterial solidMaterial;
    private static RenderMaterial translucentMaterial;

    public static final String[] MODEL_SUFFIXES = {
            "single_upper",
            "multi_upper",
            "mid_upper",
            "wall",
            "glass_lower",
            "void",
            "sill"
    };

    private final BakedModel wrapped;
    private final Sprite particle;

    public WindowArchBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof WindowArchBlock)
                || !state.contains(WindowArchBlock.FACING)
                || !state.contains(WindowArchBlock.PIECE)
                || !state.contains(WindowArchBlock.OPEN)
                || !state.contains(WindowArchBlock.SILL)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        boolean splitLayers = pushSplitLayerTransform(context);
        try {
            int rotation = rotationForFacing(state.get(WindowArchBlock.FACING));
            WindowArchBlock.Piece piece = state.get(WindowArchBlock.PIECE);

            switch (piece) {
                case UPPER_SINGLE -> emit(state, context, "single_upper", rotation);
                case UPPER_LEFT -> emit(state, context, "multi_upper", rotation);
                case UPPER_MID -> emit(state, context, "mid_upper", rotation);
                case UPPER_RIGHT -> emit(state, context, "multi_upper", rotation + 180);
                case LOWER_SINGLE -> emitLowerSingle(state, context, rotation);
                case LOWER_LEFT -> emitLowerLeft(state, context, rotation);
                case LOWER_RIGHT -> emitLowerRight(state, context, rotation);
                case LOWER_GLASS -> emit(state, context, state.get(WindowArchBlock.OPEN) ? "void" : "glass_lower", rotation);
            }

            if (state.get(WindowArchBlock.SILL)) {
                emit(state, context, "sill", rotation);
            }
        } finally {
            if (splitLayers) {
                context.popTransform();
            }
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    private static void emitLowerSingle(BlockState state, RenderContext context, int rotation) {
        emit(state, context, "wall", rotation);
        if (!state.get(WindowArchBlock.OPEN)) {
            emit(state, context, "glass_lower", rotation);
        }
        emit(state, context, "wall", rotation + 180);
    }

    private static void emitLowerLeft(BlockState state, RenderContext context, int rotation) {
        emit(state, context, "wall", rotation);
        if (!state.get(WindowArchBlock.OPEN)) {
            emit(state, context, "glass_lower", rotation);
        }
    }

    private static void emitLowerRight(BlockState state, RenderContext context, int rotation) {
        if (!state.get(WindowArchBlock.OPEN)) {
            emit(state, context, "glass_lower", rotation);
        }
        emit(state, context, "wall", rotation + 180);
    }

    private static void emit(BlockState state, RenderContext context, String suffix, int degrees) {
        BakedModel model = getModel(state, suffix);
        if (model != null) {
            emitTransformed(context, model, degrees);
        }
    }

    private static BakedModel getModel(BlockState state, String suffix) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return null;
        }

        return MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockId.getPath(), suffix));
    }

    public static Identifier modelId(String blockPath, String suffix) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = resourcePath.endsWith("_aged");
        String basePath = aged ? resourcePath.substring(0, resourcePath.length() - "_aged".length()) : resourcePath;
        return new Identifier(Erydon.MOD_ID, MODEL_PATH + basePath + "_" + suffix + (aged ? "_aged" : ""));
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
        WorldAlignedYRotation.emit(context, model, degrees, true);
    }

    private static boolean pushSplitLayerTransform(RenderContext context) {
        if (!ensureMaterials()) {
            return false;
        }

        context.pushTransform(quad -> {
            quad.material(quad.colorIndex() == 0 ? translucentMaterial : solidMaterial);
            return true;
        });
        return true;
    }

    private static boolean ensureMaterials() {
        if (solidMaterial != null && translucentMaterial != null) {
            return true;
        }

        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            return false;
        }

        synchronized (MATERIAL_LOCK) {
            if (solidMaterial == null || translucentMaterial == null) {
                MaterialFinder finder = renderer.materialFinder();
                solidMaterial = finder.clear().blendMode(BlendMode.SOLID).find();
                translucentMaterial = finder.clear().blendMode(BlendMode.TRANSLUCENT).find();
            }
        }

        return true;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!(state != null
                && state.getBlock() instanceof WindowArchBlock
                && state.contains(WindowArchBlock.FACING)
                && state.contains(WindowArchBlock.PIECE)
                && state.contains(WindowArchBlock.OPEN)
                && state.contains(WindowArchBlock.SILL))) {
            return wrapped.getQuads(state, face, random);
        }

        int rotation = rotationForFacing(state.get(WindowArchBlock.FACING));
        WindowArchBlock.Piece piece = state.get(WindowArchBlock.PIECE);
        List<BakedQuad> quads = new ArrayList<>();

        switch (piece) {
            case UPPER_SINGLE -> addQuads(quads, state, "single_upper", rotation, face, random);
            case UPPER_LEFT -> addQuads(quads, state, "multi_upper", rotation, face, random);
            case UPPER_MID -> addQuads(quads, state, "mid_upper", rotation, face, random);
            case UPPER_RIGHT -> addQuads(quads, state, "multi_upper", rotation + 180, face, random);
            case LOWER_SINGLE -> addLowerSingleQuads(quads, state, rotation, face, random);
            case LOWER_LEFT -> addLowerLeftQuads(quads, state, rotation, face, random);
            case LOWER_RIGHT -> addLowerRightQuads(quads, state, rotation, face, random);
            case LOWER_GLASS -> addQuads(quads, state, state.get(WindowArchBlock.OPEN) ? "void" : "glass_lower", rotation, face, random);
        }

        if (state.get(WindowArchBlock.SILL)) {
            addQuads(quads, state, "sill", rotation, face, random);
        }
        return quads;
    }

    private static void addLowerSingleQuads(List<BakedQuad> quads,
                                            BlockState state,
                                            int rotation,
                                            Direction face,
                                            Random random) {
        addQuads(quads, state, "wall", rotation, face, random);
        if (!state.get(WindowArchBlock.OPEN)) {
            addQuads(quads, state, "glass_lower", rotation, face, random);
        }
        addQuads(quads, state, "wall", rotation + 180, face, random);
    }

    private static void addLowerLeftQuads(List<BakedQuad> quads,
                                          BlockState state,
                                          int rotation,
                                          Direction face,
                                          Random random) {
        addQuads(quads, state, "wall", rotation, face, random);
        if (!state.get(WindowArchBlock.OPEN)) {
            addQuads(quads, state, "glass_lower", rotation, face, random);
        }
    }

    private static void addLowerRightQuads(List<BakedQuad> quads,
                                           BlockState state,
                                           int rotation,
                                           Direction face,
                                           Random random) {
        if (!state.get(WindowArchBlock.OPEN)) {
            addQuads(quads, state, "glass_lower", rotation, face, random);
        }
        addQuads(quads, state, "wall", rotation + 180, face, random);
    }

    private static void addQuads(List<BakedQuad> quads,
                                 BlockState state,
                                 String suffix,
                                 int degrees,
                                 Direction face,
                                 Random random) {
        AxiomFallbackQuads.add(quads, getModel(state, suffix), degrees, face, random, true);
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
