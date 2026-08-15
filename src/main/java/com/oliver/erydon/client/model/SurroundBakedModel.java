package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.SurroundBlock;
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

public final class SurroundBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_ROOT = "block/surround/";

    private static final String[] GEORGIAN_MODEL_SUFFIXES = {
            "corbel",
            "mantel",
            "mantel_stub_lh",
            "mantel_stub_rh",
            "plinth",
            "shaft",
            "short_jamb",
            "short_mantel",
            "short_mantel_stub_lh",
            "short_mantel_stub_rh",
            "hearth",
            "hearth_stub_lh",
            "hearth_stub_rh"
    };

    private static final String[] HANDED_MODEL_SUFFIXES = {
            "corbel",
            "corbel_lh",
            "corbel_rh",
            "mantel",
            "mantel_stub_lh",
            "mantel_stub_rh",
            "plinth",
            "plinth_lh",
            "plinth_rh",
            "shaft",
            "shaft_lh",
            "shaft_rh",
            "short_jamb",
            "short_jamb_lh",
            "short_jamb_rh",
            "short_mantel",
            "short_mantel_stub_lh",
            "short_mantel_stub_rh",
            "hearth",
            "hearth_stub_lh",
            "hearth_stub_rh"
    };

    private final BakedModel wrapped;
    private final Sprite particle;

    public SurroundBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof SurroundBlock)
                || !state.contains(SurroundBlock.SECTION)
                || !state.contains(SurroundBlock.FACING)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        Style style = styleForBlockPath(blockId.getPath());
        if (style == null) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        SurroundBlock.Section section = state.get(SurroundBlock.SECTION);
        if (style == Style.GEORGIAN && isHanded(section.outerPiece)) {
            return;
        }

        int rotation = rotationForFacing(state.get(SurroundBlock.FACING));
        emitPiece(state, context, style, section.outerPiece, rotation);
        emitPiece(state, context, style, section.innerPiece, rotation);
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    private static void emitPiece(BlockState state,
                                  RenderContext context,
                                  Style style,
                                  SurroundBlock.PieceType piece,
                                  int rotation) {
        String suffix = suffixForPiece(style, piece);
        if (suffix != null) {
            WorldAlignedYRotation.emit(context, getModel(state, suffix), rotation);
        }
    }

    static boolean locksHorizontalUv(SurroundBlock.PieceType piece) {
        return piece != SurroundBlock.PieceType.NONE;
    }

    private static String suffixForPiece(Style style, SurroundBlock.PieceType piece) {
        if (piece == SurroundBlock.PieceType.NONE) {
            return null;
        }
        if (style != Style.GEORGIAN) {
            return piece.asString();
        }

        return switch (piece) {
            case CORBEL -> "corbel";
            case MANTEL -> "mantel";
            case MANTEL_STUB_LH -> "mantel_stub_lh";
            case MANTEL_STUB_RH -> "mantel_stub_rh";
            case PLINTH -> "plinth";
            case SHAFT -> "shaft";
            case SHORT_JAMB -> "short_jamb";
            case SHORT_MANTEL -> "short_mantel";
            case SHORT_MANTEL_STUB_LH -> "short_mantel_stub_lh";
            case SHORT_MANTEL_STUB_RH -> "short_mantel_stub_rh";
            case HEARTH -> "hearth";
            case HEARTH_STUB_LH -> "hearth_stub_lh";
            case HEARTH_STUB_RH -> "hearth_stub_rh";
            default -> null;
        };
    }

    private static boolean isHanded(SurroundBlock.PieceType piece) {
        return switch (piece) {
            case CORBEL_LH, CORBEL_RH,
                    PLINTH_LH, PLINTH_RH,
                    SHAFT_LH, SHAFT_RH,
                    SHORT_JAMB_LH, SHORT_JAMB_RH -> true;
            default -> false;
        };
    }

    private static BakedModel getModel(BlockState state, String suffix) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        return MinecraftClient.getInstance().getBakedModelManager().getModel(modelId(blockId.getPath(), suffix));
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
        if (blockPath.contains("_surround_georgian")) {
            return Style.GEORGIAN;
        }
        if (blockPath.contains("_surround_guilloche")) {
            return Style.GUILLOCHE;
        }
        if (blockPath.contains("_surround_gothic_ornate")) {
            return Style.GOTHIC_ORNATE;
        }
        if (blockPath.contains("_surround_modern")) {
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

    private enum Style {
        GEORGIAN("georgian", GEORGIAN_MODEL_SUFFIXES),
        GUILLOCHE("guilloche", HANDED_MODEL_SUFFIXES),
        GOTHIC_ORNATE("gothic_ornate", HANDED_MODEL_SUFFIXES),
        MODERN("modern", HANDED_MODEL_SUFFIXES);

        private final String path;
        private final String[] suffixes;

        Style(String path, String[] suffixes) {
            this.path = path;
            this.suffixes = suffixes;
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!(state != null
                && state.getBlock() instanceof SurroundBlock
                && state.contains(SurroundBlock.SECTION)
                && state.contains(SurroundBlock.FACING))) {
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

        SurroundBlock.Section section = state.get(SurroundBlock.SECTION);
        if (style == Style.GEORGIAN && isHanded(section.outerPiece)) {
            return List.of();
        }

        int rotation = rotationForFacing(state.get(SurroundBlock.FACING));
        List<BakedQuad> quads = new ArrayList<>();
        addPieceQuads(quads, state, style, section.outerPiece, rotation, face, random);
        addPieceQuads(quads, state, style, section.innerPiece, rotation, face, random);
        return quads;
    }

    private static void addPieceQuads(List<BakedQuad> quads,
                                      BlockState state,
                                      Style style,
                                      SurroundBlock.PieceType piece,
                                      int rotation,
                                      Direction face,
                                      Random random) {
        String suffix = suffixForPiece(style, piece);
        if (suffix != null) {
            AxiomFallbackQuads.add(quads, getModel(state, suffix), rotation, face, random,
                    locksHorizontalUv(piece));
        }
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
