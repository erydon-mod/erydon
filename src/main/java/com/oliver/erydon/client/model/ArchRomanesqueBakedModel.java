package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.ArchRomanesqueBlock;
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
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ArchRomanesqueBakedModel implements BakedModel, FabricBakedModel {
    private static final String ROMANESQUE_MODEL_PATH = "block/arch/romanesque/";
    private static final String MODERN_MODEL_PATH = "block/arch/modern/";
    private static final String GOTHIC_MODEL_PATH = "block/arch/gothic/";

    public static final String[] MODEL_SUFFIXES = {
            "corner_small",
            "corner_medium",
            "corner_large_upper",
            "corner_large_lower",
            "side_small",
            "side_medium",
            "side_large",
            "side_medium_upper",
            "side_large_upper",
            "side_column",
            "plinth",
            "top_large"
    };
    private static final String[] MODERN_MODEL_SUFFIXES = {
            "corner_small",
            "corner_medium",
            "corner_large_upper",
            "corner_large_lower",
            "side_small",
            "side_medium",
            "side_large",
            "side_medium_upper",
            "side_large_upper",
            "top_large"
    };
    private static final String[] GOTHIC_MODEL_SUFFIXES = {
            "corner_small",
            "corner_medium",
            "corner_large_upper",
            "corner_large_lower",
            "side_small",
            "side_medium",
            "side_large",
            "top_large",
            "icon"
    };

    private final BakedModel wrapped;
    private final Sprite particle;

    public ArchRomanesqueBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof ArchRomanesqueBlock)
                || !state.contains(ArchRomanesqueBlock.ARRANGEMENT)
                || !state.contains(ArchRomanesqueBlock.FACING)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        ArchRomanesqueBlock.Arrangement arrangement = state.get(ArchRomanesqueBlock.ARRANGEMENT);
        Direction facing = state.get(ArchRomanesqueBlock.FACING);

        emitCorner(state, context, arrangement.corner(), arrangement.cornerFlip(), facing);
        emitSide(state, context, arrangement.sideL(), false, facing);
        emitSide(state, context, arrangement.sideR(), true, facing);
        emitUpper(state, context, arrangement.upperL(), false, facing);
        emitUpper(state, context, arrangement.upperR(), true, facing);
        emitIf(state, context, arrangement.columnL(), "side_column", leftRotation(facing));
        emitIf(state, context, arrangement.columnR(), "side_column", rightRotation(facing));
        emitIf(state, context, arrangement.plinthL(), "plinth", leftRotation(facing));
        emitIf(state, context, arrangement.plinthR(), "plinth", rightRotation(facing));
        emitIf(state, context, arrangement.hasTopLarge(), "top_large", rightRotation(facing));
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId != null && Erydon.MOD_ID.equals(blockId.getNamespace())
                    && isGothicArch(blockId.getPath())) {
                BakedModel icon = MinecraftClient.getInstance().getBakedModelManager()
                        .getModel(modelId(blockId.getPath(), "icon"));
                if (icon != null) {
                    SharedGeometryChildModel.emit(context, icon);
                    return;
                }
            }
        }
        context.fallbackConsumer().accept(wrapped);
    }

    private static void emitCorner(BlockState state,
                                   RenderContext context,
                                   ArchRomanesqueBlock.Corner corner,
                                   boolean flipped,
                                   Direction facing) {
        String suffix = switch (corner) {
            case SMALL -> "corner_small";
            case MEDIUM -> "corner_medium";
            case LARGE_UPPER -> "corner_large_upper";
            case LARGE_LOWER -> "corner_large_lower";
            case NONE -> null;
        };
        if (suffix != null) {
            emit(state, context, suffix, flipped ? rightRotation(facing) : leftRotation(facing));
        }
    }

    private static void emitSide(BlockState state,
                                 RenderContext context,
                                 ArchRomanesqueBlock.Side side,
                                 boolean right,
                                 Direction facing) {
        String suffix = switch (side) {
            case SMALL -> "side_small";
            case MEDIUM -> "side_medium";
            case LARGE -> "side_large";
            case NONE -> null;
        };
        if (suffix != null) {
            emit(state, context, suffix, right ? rightRotation(facing) : leftRotation(facing));
        }
    }

    private static void emitUpper(BlockState state,
                                  RenderContext context,
                                  ArchRomanesqueBlock.Upper upper,
                                  boolean right,
                                  Direction facing) {
        String suffix = switch (upper) {
            case MEDIUM -> "side_medium_upper";
            case LARGE -> "side_large_upper";
            case NONE -> null;
        };
        if (suffix != null) {
            emit(state, context, suffix, right ? rightRotation(facing) : leftRotation(facing));
        }
    }

    private static void emitIf(BlockState state, RenderContext context, boolean condition, String suffix, int degrees) {
        if (condition) {
            emit(state, context, suffix, degrees);
        }
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
        boolean aged = ErydonIdNaming.isAged(resourcePath);
        String basePath = ErydonIdNaming.withoutAged(resourcePath);
        return new Identifier(Erydon.MOD_ID, modelPath(resourcePath) + basePath + "_" + suffix + (aged ? "_aged" : ""));
    }

    public static String[] modelSuffixes(String blockPath) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        if (isGothicArch(resourcePath)) {
            return GOTHIC_MODEL_SUFFIXES;
        }
        return isModernArch(resourcePath) ? MODERN_MODEL_SUFFIXES : MODEL_SUFFIXES;
    }

    private static String modelPath(String blockPath) {
        if (isGothicArch(blockPath)) {
            return GOTHIC_MODEL_PATH;
        }
        return isModernArch(blockPath) ? MODERN_MODEL_PATH : ROMANESQUE_MODEL_PATH;
    }

    private static boolean isModernArch(String blockPath) {
        return blockPath.contains("_arch_modern");
    }

    private static boolean isGothicArch(String blockPath) {
        return blockPath.contains("_arch_gothic");
    }

    private static int leftRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> 180;
            case EAST -> 270;
            case SOUTH -> 0;
            case WEST -> 90;
            default -> 0;
        };
    }

    private static int rightRotation(Direction facing) {
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
        if (!(state != null
                && state.getBlock() instanceof ArchRomanesqueBlock
                && state.contains(ArchRomanesqueBlock.ARRANGEMENT)
                && state.contains(ArchRomanesqueBlock.FACING))) {
            return wrapped.getQuads(state, face, random);
        }

        ArchRomanesqueBlock.Arrangement arrangement = state.get(ArchRomanesqueBlock.ARRANGEMENT);
        Direction facing = state.get(ArchRomanesqueBlock.FACING);
        List<BakedQuad> quads = new ArrayList<>();

        addCornerQuads(quads, state, arrangement.corner(), arrangement.cornerFlip(), facing, face, random);
        addSideQuads(quads, state, arrangement.sideL(), false, facing, face, random);
        addSideQuads(quads, state, arrangement.sideR(), true, facing, face, random);
        addUpperQuads(quads, state, arrangement.upperL(), false, facing, face, random);
        addUpperQuads(quads, state, arrangement.upperR(), true, facing, face, random);
        addIfQuads(quads, state, arrangement.columnL(), "side_column", leftRotation(facing), face, random);
        addIfQuads(quads, state, arrangement.columnR(), "side_column", rightRotation(facing), face, random);
        addIfQuads(quads, state, arrangement.plinthL(), "plinth", leftRotation(facing), face, random);
        addIfQuads(quads, state, arrangement.plinthR(), "plinth", rightRotation(facing), face, random);
        addIfQuads(quads, state, arrangement.hasTopLarge(), "top_large", rightRotation(facing), face, random);
        return quads;
    }

    private static void addCornerQuads(List<BakedQuad> quads,
                                       BlockState state,
                                       ArchRomanesqueBlock.Corner corner,
                                       boolean flipped,
                                       Direction facing,
                                       Direction face,
                                       Random random) {
        String suffix = switch (corner) {
            case SMALL -> "corner_small";
            case MEDIUM -> "corner_medium";
            case LARGE_UPPER -> "corner_large_upper";
            case LARGE_LOWER -> "corner_large_lower";
            case NONE -> null;
        };
        if (suffix != null) {
            addQuads(quads, state, suffix, flipped ? rightRotation(facing) : leftRotation(facing), face, random);
        }
    }

    private static void addSideQuads(List<BakedQuad> quads,
                                     BlockState state,
                                     ArchRomanesqueBlock.Side side,
                                     boolean right,
                                     Direction facing,
                                     Direction face,
                                     Random random) {
        String suffix = switch (side) {
            case SMALL -> "side_small";
            case MEDIUM -> "side_medium";
            case LARGE -> "side_large";
            case NONE -> null;
        };
        if (suffix != null) {
            addQuads(quads, state, suffix, right ? rightRotation(facing) : leftRotation(facing), face, random);
        }
    }

    private static void addUpperQuads(List<BakedQuad> quads,
                                      BlockState state,
                                      ArchRomanesqueBlock.Upper upper,
                                      boolean right,
                                      Direction facing,
                                      Direction face,
                                      Random random) {
        String suffix = switch (upper) {
            case MEDIUM -> "side_medium_upper";
            case LARGE -> "side_large_upper";
            case NONE -> null;
        };
        if (suffix != null) {
            addQuads(quads, state, suffix, right ? rightRotation(facing) : leftRotation(facing), face, random);
        }
    }

    private static void addIfQuads(List<BakedQuad> quads,
                                   BlockState state,
                                   boolean condition,
                                   String suffix,
                                   int degrees,
                                   Direction face,
                                   Random random) {
        if (condition) {
            addQuads(quads, state, suffix, degrees, face, random);
        }
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
