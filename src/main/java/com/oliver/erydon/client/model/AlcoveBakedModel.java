package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.AlcoveBlock;
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
import net.minecraft.item.BlockItem;
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

public final class AlcoveBakedModel implements BakedModel, FabricBakedModel {
    private static final String GEORGIAN_MODEL_ROOT = "block/alcove/georgian/";
    private static final String GOTHIC_MODEL_ROOT = "block/alcove/gothic/";
    private static final List<String> BASE_MODEL_SUFFIXES = List.of(
            "back",
            "sides",
            "base",
            "top",
            "icon",
            "double_side_left",
            "double_side_right",
            "double_top_left",
            "double_top_right");
    private static final List<String> TRIPLE_MODEL_SUFFIXES = List.of(
            "triple_side_left",
            "triple_side_center",
            "triple_side_right",
            "triple_top_left",
            "triple_top_center",
            "triple_top_right");

    private final BakedModel wrapped;
    private final Sprite particle;

    public AlcoveBakedModel(BakedModel wrapped) {
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
        if (!isAlcoveState(state)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        int degrees = yRotation(state.get(AlcoveBlock.FACING));
        for (String suffix : suffixesForState(state)) {
            emit(context, getModel(state, suffix), degrees);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId != null && Erydon.MOD_ID.equals(blockId.getNamespace())
                    && ErydonModelFamilyIndex.isAlcoveBlock(blockId.getPath())) {
                BakedModel icon = MinecraftClient.getInstance().getBakedModelManager()
                        .getModel(modelId(blockId.getPath(), "icon"));
                if (icon != null) {
                    context.fallbackConsumer().accept(icon);
                    return;
                }
            }
        }
        context.fallbackConsumer().accept(wrapped);
    }

    private static List<String> suffixesForState(BlockState state) {
        List<String> suffixes = new ArrayList<>(4);
        suffixes.add("back");

        AlcoveBlock.AlcoveSpan span = state.get(AlcoveBlock.SPAN);
        suffixes.add(sideSuffix(span));

        AlcoveBlock.AlcovePart part = state.get(AlcoveBlock.PART);
        if (part == AlcoveBlock.AlcovePart.SINGLE || part == AlcoveBlock.AlcovePart.BASE) {
            suffixes.add("base");
        }
        if (part == AlcoveBlock.AlcovePart.SINGLE || part == AlcoveBlock.AlcovePart.TOP) {
            suffixes.add(topSuffix(span));
        }
        return suffixes;
    }

    private static String sideSuffix(AlcoveBlock.AlcoveSpan span) {
        return switch (span) {
            case LEFT -> "double_side_left";
            case RIGHT -> "double_side_right";
            case TRIPLE_LEFT -> "triple_side_left";
            case TRIPLE_CENTER -> "triple_side_center";
            case TRIPLE_RIGHT -> "triple_side_right";
            case SINGLE -> "sides";
        };
    }

    private static String topSuffix(AlcoveBlock.AlcoveSpan span) {
        return switch (span) {
            case LEFT -> "double_top_left";
            case RIGHT -> "double_top_right";
            case TRIPLE_LEFT -> "triple_top_left";
            case TRIPLE_CENTER -> "triple_top_center";
            case TRIPLE_RIGHT -> "triple_top_right";
            case SINGLE -> "top";
        };
    }

    private static boolean isAlcoveState(BlockState state) {
        return state != null
                && state.getBlock() instanceof AlcoveBlock
                && state.contains(AlcoveBlock.FACING)
                && state.contains(AlcoveBlock.PART)
                && state.contains(AlcoveBlock.SPAN);
    }

    private static void emit(RenderContext context, BakedModel model, int degrees) {
        WorldAlignedYRotation.emit(context, model, degrees);
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
        boolean aged = ErydonIdNaming.isAged(resourcePath);
        String basePath = ErydonIdNaming.withoutAged(resourcePath);
        return new Identifier(Erydon.MOD_ID, modelRoot(basePath) + basePath + "_" + suffix + (aged ? "_aged" : ""));
    }

    private static String modelRoot(String blockPath) {
        return ErydonModelFamilyIndex.isGothicAlcoveBlock(blockPath)
                ? GOTHIC_MODEL_ROOT
                : GEORGIAN_MODEL_ROOT;
    }

    public static List<String> modelSuffixes(String blockPath) {
        List<String> suffixes = new ArrayList<>(BASE_MODEL_SUFFIXES.size() + TRIPLE_MODEL_SUFFIXES.size());
        suffixes.addAll(BASE_MODEL_SUFFIXES);
        suffixes.addAll(TRIPLE_MODEL_SUFFIXES);
        return List.copyOf(suffixes);
    }

    private static int yRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> 180;
            case EAST -> 270;
            case WEST -> 90;
            default -> 0;
        };
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!isAlcoveState(state)) {
            return wrapped.getQuads(state, face, random);
        }

        List<BakedQuad> quads = new ArrayList<>();
        int degrees = yRotation(state.get(AlcoveBlock.FACING));
        for (String suffix : suffixesForState(state)) {
            AxiomFallbackQuads.add(quads, getModel(state, suffix), degrees, face, random, true);
        }
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
