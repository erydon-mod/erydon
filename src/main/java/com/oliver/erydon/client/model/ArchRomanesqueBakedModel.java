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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.Function;

public final class ArchRomanesqueBakedModel implements BakedModel, FabricBakedModel, ArchQuadSources.Complete {
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
            "side_large_lower",
            "side_medium_upper",
            "side_large_upper",
            "side_column",
            "side_column_upper",
            "plinth",
            "top_large",
            "icon",
            "non_column_side_small_crown",
            "non_column_side_medium_crown",
            "non_column_side_small",
            "non_column_side_small_base",
            "non_column_side_medium",
            "non_column_side_medium_base",
            "non_column_side_large",
            "non_column_side_large_base",
            "non_column_side_large_lower",
            "non_column_side_large_crown",
            "non_column_corner_small",
            "non_column_corner_medium",
            "non_column_corner_large_lower",
            "non_column_corner_large_upper"
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
    private final Map<net.minecraft.block.Block, ChildModels> children = new ConcurrentHashMap<>();
    private final Map<Direction, ArchHorizontalReflection> depthMirrors = new ConcurrentHashMap<>();
    private record WideKey(net.minecraft.block.Block block, ArchRomanesqueBlock.Arrangement arrangement,
                           int width, Direction facing, boolean reflected) { }
    private final Map<WideKey, List<BakedQuad>> wideQuads = new ConcurrentHashMap<>();

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
        if (arrangement.isWide()) {
            var renderer = net.fabricmc.fabric.api.renderer.v1.RendererAccess.INSTANCE.getRenderer();
            if (renderer != null) {
                var material = renderer.materialFinder().find();
                for (BakedQuad quad : wideQuads(state)) context.getEmitter().fromVanilla(quad, material, null).emit();
            }
            return;
        }
        Direction facing = state.get(ArchRomanesqueBlock.FACING);
        boolean reflected = state.get(ArchRomanesqueBlock.REFLECTED)
                && !childrenFor(state.getBlock()).romanesque;
        if (reflected) {
            context.pushTransform(ArchHorizontalReflection.forState(facing, arrangement.hasTopLarge()));
        }
        try {
            emitCorner(state, context, arrangement.corner(), arrangement.cornerFlip(), facing);
            emitSide(state, context, arrangement.sideL(), false, facing);
            emitSide(state, context, arrangement.sideR(), true, facing);
            emitUpper(state, context, arrangement.upperL(), false, facing);
            emitUpper(state, context, arrangement.upperR(), true, facing);
            emitIf(state, context, arrangement.columnL(), columnSuffix(state, arrangement.upperL()), leftRotation(facing));
            emitIf(state, context, arrangement.columnR(), columnSuffix(state, arrangement.upperR()), rightRotation(facing), true);
            emitIf(state, context, arrangement.plinthL(), "plinth", leftRotation(facing));
            emitIf(state, context, arrangement.plinthR(), "plinth", rightRotation(facing), true);
            emitIf(state, context, arrangement.hasTopLarge(), "top_large", rightRotation(facing));
        } finally {
            if (reflected) {
                context.popTransform();
            }
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId != null && Erydon.MOD_ID.equals(blockId.getNamespace())
                    && (isGothicArch(blockId.getPath()) || isRomanesqueArch(blockId.getPath()))) {
                BakedModel icon = childrenFor(blockItem.getBlock()).get("icon");
                if (icon != null) {
                    SharedGeometryChildModel.emit(context, icon);
                    return;
                }
            }
        }
        context.fallbackConsumer().accept(wrapped);
    }

    private void emitCorner(BlockState state,
                                   RenderContext context,
                                   ArchRomanesqueBlock.Corner corner,
                                   boolean flipped,
                                   Direction facing) {
        String suffix = cornerSuffix(state, corner);
        if (suffix != null) {
            emit(state, context, suffix, flipped ? rightRotation(facing) : leftRotation(facing), flipped);
        }
    }

    private void emitSide(BlockState state,
                                 RenderContext context,
                                 ArchRomanesqueBlock.Side side,
                                 boolean right,
                                 Direction facing) {
        String suffix = sideSuffix(state, side);
        if (suffix != null) {
            emit(state, context, suffix, right ? rightRotation(facing) : leftRotation(facing), right);
        }
    }

    private void emitUpper(BlockState state,
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
            emit(state, context, suffix, right ? rightRotation(facing) : leftRotation(facing), right);
        }
    }

    private void emitIf(BlockState state, RenderContext context, boolean condition, String suffix, int degrees) {
        if (condition) {
            emit(state, context, suffix, degrees);
        }
    }

    private void emitIf(BlockState state, RenderContext context, boolean condition, String suffix,
                        int degrees, boolean right) {
        if (condition) emit(state, context, suffix, degrees, right);
    }

    private void emit(BlockState state, RenderContext context, String suffix, int degrees) {
        emit(state, context, suffix, degrees, false);
    }

    private void emit(BlockState state, RenderContext context, String suffix, int degrees, boolean right) {
        BakedModel model = getModel(state, suffix);
        boolean mirror = right && childrenFor(state.getBlock()).romanesque;
        if (mirror) context.pushTransform(depthMirror(state));
        try {
            if (model != null) emitTransformed(context, model, degrees);
        } finally {
            if (mirror) context.popTransform();
        }
    }

    private ArchHorizontalReflection depthMirror(BlockState state) {
        return depthMirrors.computeIfAbsent(state.get(ArchRomanesqueBlock.FACING),
                ArchHorizontalReflection::forDepthMirror);
    }

    private BakedModel getModel(BlockState state, String suffix) {
        return childrenFor(state.getBlock()).get(suffix);
    }

    /** Family wrappers and their child caches are rebuilt on resource reload. */
    private ChildModels childrenFor(net.minecraft.block.Block block) {
        return children.computeIfAbsent(block, owner -> {
            Identifier id = Registries.BLOCK.getId(owner);
            if (id == null || !Erydon.MOD_ID.equals(id.getNamespace())) {
                return new ChildModels(null, ignored -> null);
            }
            var manager = MinecraftClient.getInstance().getBakedModelManager();
            return new ChildModels(id.getPath(), manager::getModel);
        });
    }

    static final class ChildModels {
        private final Map<String, BakedModel> resolved;
        private final boolean romanesque;

        ChildModels(String blockPath, Function<Identifier, BakedModel> resolver) {
            romanesque = blockPath != null && isRomanesqueArch(blockPath);
            Map<String, BakedModel> models = new HashMap<>();
            if (blockPath != null) {
                for (String suffix : modelSuffixes(blockPath)) {
                    models.put(suffix, resolver.apply(modelId(blockPath, suffix)));
                }
            }
            resolved = Collections.unmodifiableMap(models);
        }

        BakedModel get(String suffix) { return resolved.get(suffix); }
    }

    static String sideSuffix(ArchRomanesqueBlock.Side side, ArchRomanesqueBlock.Corner corner,
                             boolean romanesque) {
        return switch (side) {
            case SMALL -> "side_small";
            case MEDIUM -> "side_medium";
            case LARGE -> romanesque && corner == ArchRomanesqueBlock.Corner.LARGE_LOWER
                    ? "side_large_lower" : "side_large";
            case NONE -> null;
        };
    }

    private String sideSuffix(BlockState state, ArchRomanesqueBlock.Side side) {
        if (childrenFor(state.getBlock()).romanesque) {
            return state.get(ArchRomanesqueBlock.ARRANGEMENT).authoredSideSuffix(side);
        }
        return sideSuffix(side, state.get(ArchRomanesqueBlock.ARRANGEMENT).corner(),
                false);
    }

    private String cornerSuffix(BlockState state, ArchRomanesqueBlock.Corner corner) {
        if (childrenFor(state.getBlock()).romanesque) {
            return state.get(ArchRomanesqueBlock.ARRANGEMENT).authoredCornerSuffix();
        }
        return switch (corner) {
            case SMALL -> "corner_small";
            case MEDIUM -> "corner_medium";
            case LARGE_UPPER -> "corner_large_upper";
            case LARGE_LOWER -> "corner_large_lower";
            case NONE -> null;
        };
    }

    static String columnSuffix(ArchRomanesqueBlock.Upper upper, boolean romanesque) {
        return romanesque && upper != ArchRomanesqueBlock.Upper.NONE ? "side_column_upper" : "side_column";
    }

    private String columnSuffix(BlockState state, ArchRomanesqueBlock.Upper upper) {
        return columnSuffix(upper, childrenFor(state.getBlock()).romanesque);
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

    private static boolean isRomanesqueArch(String blockPath) {
        return blockPath.contains("_arch_romanesque");
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

    private List<BakedQuad> wideQuads(BlockState state) {
        WideKey key = new WideKey(state.getBlock(), state.get(ArchRomanesqueBlock.ARRANGEMENT),
                state.get(ArchRomanesqueBlock.WIDTH), state.get(ArchRomanesqueBlock.FACING),
                state.get(ArchRomanesqueBlock.REFLECTED));
        return wideQuads.computeIfAbsent(key, ignored -> WideArchQuads.create(state,
                child -> getQuads(child, null, Random.create(0))));
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
        if (arrangement.isWide()) return face == null ? wideQuads(state) : List.of();
        Direction facing = state.get(ArchRomanesqueBlock.FACING);
        ArchHorizontalReflection reflection = state.get(ArchRomanesqueBlock.REFLECTED)
                && !childrenFor(state.getBlock()).romanesque ? ArchHorizontalReflection.forState(facing, arrangement.hasTopLarge()) : null;
        Direction sourceFace = reflection == null ? face : reflection.face(face);
        List<BakedQuad> quads = new ArrayList<>();

        addCornerQuads(quads, state, arrangement.corner(), arrangement.cornerFlip(), facing, sourceFace, random);
        addSideQuads(quads, state, arrangement.sideL(), false, facing, sourceFace, random);
        addSideQuads(quads, state, arrangement.sideR(), true, facing, sourceFace, random);
        addUpperQuads(quads, state, arrangement.upperL(), false, facing, sourceFace, random);
        addUpperQuads(quads, state, arrangement.upperR(), true, facing, sourceFace, random);
        addIfQuads(quads, state, arrangement.columnL(), columnSuffix(state, arrangement.upperL()), leftRotation(facing), sourceFace, random);
        addIfQuads(quads, state, arrangement.columnR(), columnSuffix(state, arrangement.upperR()), rightRotation(facing), sourceFace, random, true);
        addIfQuads(quads, state, arrangement.plinthL(), "plinth", leftRotation(facing), sourceFace, random);
        addIfQuads(quads, state, arrangement.plinthR(), "plinth", rightRotation(facing), sourceFace, random, true);
        addIfQuads(quads, state, arrangement.hasTopLarge(), "top_large", rightRotation(facing), sourceFace, random);
        if (reflection != null) {
            quads.replaceAll(reflection::reflect);
        }
        return quads;
    }

    private void addCornerQuads(List<BakedQuad> quads,
                                       BlockState state,
                                       ArchRomanesqueBlock.Corner corner,
                                       boolean flipped,
                                       Direction facing,
                                       Direction face,
                                       Random random) {
        String suffix = cornerSuffix(state, corner);
        if (suffix != null) {
            addQuads(quads, state, suffix, flipped ? rightRotation(facing) : leftRotation(facing), face, random, flipped);
        }
    }

    private void addSideQuads(List<BakedQuad> quads,
                                     BlockState state,
                                     ArchRomanesqueBlock.Side side,
                                     boolean right,
                                     Direction facing,
                                     Direction face,
                                     Random random) {
        String suffix = sideSuffix(state, side);
        if (suffix != null) {
            addQuads(quads, state, suffix, right ? rightRotation(facing) : leftRotation(facing), face, random, right);
        }
    }

    private void addUpperQuads(List<BakedQuad> quads,
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
            addQuads(quads, state, suffix, right ? rightRotation(facing) : leftRotation(facing), face, random, right);
        }
    }

    private void addIfQuads(List<BakedQuad> quads,
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

    private void addIfQuads(List<BakedQuad> quads, BlockState state, boolean condition,
                            String suffix, int degrees, Direction face, Random random, boolean right) {
        if (condition) addQuads(quads, state, suffix, degrees, face, random, right);
    }

    private void addQuads(List<BakedQuad> quads, BlockState state, String suffix,
                          int degrees, Direction face, Random random) {
        addQuads(quads, state, suffix, degrees, face, random, false);
    }

    private void addQuads(List<BakedQuad> quads, BlockState state, String suffix,
                          int degrees, Direction face, Random random, boolean right) {
        boolean mirror = right && childrenFor(state.getBlock()).romanesque;
        ArchHorizontalReflection reflection = mirror ? depthMirror(state) : null;
        int start = quads.size();
        AxiomFallbackQuads.add(quads, getModel(state, suffix), degrees,
                mirror ? reflection.face(face) : face, random, true);
        if (mirror) {
            for (int index = start; index < quads.size(); index++) {
                quads.set(index, reflection.reflect(quads.get(index)));
            }
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
