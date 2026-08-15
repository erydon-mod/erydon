package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
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

public final class WindowFrenchGeorgianBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_PATH = "block/window/french_georgian/";
    private static final Object MATERIAL_LOCK = new Object();
    private static RenderMaterial solidMaterial;
    private static RenderMaterial translucentMaterial;

    public static final String[] MODEL_SUFFIXES = {
            "closed_upper_single",
            "closed_lower_single",
            "closed_upper_multi_lh",
            "closed_upper_multi_mid",
            "closed_upper_multi_rh",
            "closed_lower_multi_lh",
            "closed_lower_multi_mid",
            "closed_lower_multi_rh",
            "open_upper_single_lh",
            "open_upper_single_rh",
            "open_lower_single_lh",
            "open_lower_single_rh",
            "open_upper_multi_lh",
            "open_upper_multi_mid",
            "open_upper_multi_rh",
            "open_lower_multi_lh",
            "open_lower_multi_rh",
            "sill",
            "sill_lh",
            "sill_rh"
    };

    private final BakedModel wrapped;
    private final Sprite particle;

    public WindowFrenchGeorgianBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof WindowFrenchGeorgianBlock)
                || !state.contains(WindowFrenchGeorgianBlock.FACING)
                || !state.contains(WindowFrenchGeorgianBlock.OPEN)
                || !state.contains(WindowFrenchGeorgianBlock.HINGE)
                || !state.contains(WindowFrenchGeorgianBlock.PIECE)
                || !state.contains(WindowFrenchGeorgianBlock.SILL)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        boolean splitLayers = pushSplitLayerTransform(context);
        try {
            int rotation = rotationForFacing(state.get(WindowFrenchGeorgianBlock.FACING));
            String mainSuffix = mainSuffix(state);
            if (mainSuffix != null) {
                emit(state, context, mainSuffix, rotation);
            }

            if (state.get(WindowFrenchGeorgianBlock.SILL)) {
                emit(state, context, "sill", rotation);

                String sideSillSuffix = sideSillSuffix(state);
                if (sideSillSuffix != null) {
                    emit(state, context, sideSillSuffix, rotation);
                }
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

    private static String mainSuffix(BlockState state) {
        WindowFrenchGeorgianBlock.Piece piece = state.get(WindowFrenchGeorgianBlock.PIECE);
        if (!state.get(WindowFrenchGeorgianBlock.OPEN)) {
            return "closed_" + piece.asString();
        }

        return switch (piece) {
            case UPPER_SINGLE -> "open_upper_single_" + hingeSuffix(state.get(WindowFrenchGeorgianBlock.HINGE));
            case LOWER_SINGLE -> "open_lower_single_" + hingeSuffix(state.get(WindowFrenchGeorgianBlock.HINGE));
            case UPPER_MULTI_LH -> "open_upper_multi_lh";
            case UPPER_MULTI_MID -> "open_upper_multi_mid";
            case UPPER_MULTI_RH -> "open_upper_multi_rh";
            case LOWER_MULTI_LH -> "open_lower_multi_lh";
            case LOWER_MULTI_MID -> null;
            case LOWER_MULTI_RH -> "open_lower_multi_rh";
        };
    }

    private static String sideSillSuffix(BlockState state) {
        if (!state.get(WindowFrenchGeorgianBlock.OPEN)) {
            return null;
        }

        return switch (state.get(WindowFrenchGeorgianBlock.PIECE)) {
            case LOWER_SINGLE -> "sill_" + hingeSuffix(state.get(WindowFrenchGeorgianBlock.HINGE));
            case LOWER_MULTI_LH -> "sill_lh";
            case LOWER_MULTI_RH -> "sill_rh";
            default -> null;
        };
    }

    private static String hingeSuffix(DoorHinge hinge) {
        return hinge == DoorHinge.LEFT ? "lh" : "rh";
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
                && state.getBlock() instanceof WindowFrenchGeorgianBlock
                && state.contains(WindowFrenchGeorgianBlock.FACING)
                && state.contains(WindowFrenchGeorgianBlock.OPEN)
                && state.contains(WindowFrenchGeorgianBlock.HINGE)
                && state.contains(WindowFrenchGeorgianBlock.PIECE)
                && state.contains(WindowFrenchGeorgianBlock.SILL))) {
            return wrapped.getQuads(state, face, random);
        }

        int rotation = rotationForFacing(state.get(WindowFrenchGeorgianBlock.FACING));
        List<BakedQuad> quads = new ArrayList<>();
        String mainSuffix = mainSuffix(state);
        if (mainSuffix != null) {
            addQuads(quads, state, mainSuffix, rotation, face, random);
        }

        if (state.get(WindowFrenchGeorgianBlock.SILL)) {
            addQuads(quads, state, "sill", rotation, face, random);

            String sideSillSuffix = sideSillSuffix(state);
            if (sideSillSuffix != null) {
                addQuads(quads, state, sideSillSuffix, rotation, face, random);
            }
        }
        return quads;
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
