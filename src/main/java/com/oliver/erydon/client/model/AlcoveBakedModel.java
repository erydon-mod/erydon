package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.AlcoveBlock;
import com.oliver.erydon.util.ErydonIdNaming;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public final class AlcoveBakedModel implements BakedModel, FabricBakedModel {
    private static final String GEORGIAN_MODEL_ROOT = "block/alcove/georgian/";
    private static final String GOTHIC_MODEL_ROOT = "block/alcove/gothic/";
    private static final String MODERN_MODEL_ROOT = "block/alcove/modern/";
    private static final List<String> BASE_MODEL_SUFFIXES = List.of(
            "back",
            "sides",
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
    private static final List<String> GEORGIAN_BODY_MODEL_SUFFIXES = List.of(
            "base_detail_left",
            "base_detail_right",
            "middle_detail_left",
            "middle_detail_right",
            "double_base_detail_left",
            "double_base_detail_right",
            "double_middle_detail_left",
            "double_middle_detail_right",
            "triple_base_detail_left",
            "triple_base_detail_right",
            "triple_middle_detail_left",
            "triple_middle_detail_right");
    private static final int SPAN_COUNT = AlcoveBlock.AlcoveSpan.values().length;
    private static final List<List<String>> GEORGIAN_BODY_SUFFIXES_BY_STATE = bodyDetailSuffixTable();
    private static final List<List<String>> GEORGIAN_SUFFIXES_BY_STATE = componentSuffixTable(true);
    private static final List<List<String>> OTHER_SUFFIXES_BY_STATE = componentSuffixTable(false);

    private final BakedModel wrapped;
    private final Sprite particle;
    private volatile ResolvedComponents resolvedComponents;

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
        ComponentModels components = componentsFor(state.getBlock());
        if (components == null) {
            return;
        }
        for (BakedModel component : components.forState(state.get(AlcoveBlock.PART), state.get(AlcoveBlock.SPAN))) {
            emit(context, component, degrees);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
            if (blockId != null && Erydon.MOD_ID.equals(blockId.getNamespace())
                    && ErydonModelFamilyIndex.isAlcoveBlock(blockId.getPath())) {
                ComponentModels components = componentsFor(blockItem.getBlock());
                BakedModel icon = components == null ? null : components.icon;
                if (icon != null) {
                    SharedGeometryChildModel.emit(context, icon);
                    return;
                }
            }
        }
        context.fallbackConsumer().accept(wrapped);
    }

    static List<String> componentSuffixes(AlcoveBlock.AlcovePart part, AlcoveBlock.AlcoveSpan span,
                                          boolean georgian) {
        return (georgian ? GEORGIAN_SUFFIXES_BY_STATE : OTHER_SUFFIXES_BY_STATE)
                .get(part.ordinal() * SPAN_COUNT + span.ordinal());
    }

    private static List<List<String>> componentSuffixTable(boolean georgian) {
        List<List<String>> table = new ArrayList<>(AlcoveBlock.AlcovePart.values().length * SPAN_COUNT);
        for (var part : AlcoveBlock.AlcovePart.values()) {
            boolean crown = part == AlcoveBlock.AlcovePart.SINGLE || part == AlcoveBlock.AlcovePart.TOP;
            for (var span : AlcoveBlock.AlcoveSpan.values()) {
                List<String> suffixes = new ArrayList<>(4);
                suffixes.add("back");
                if (!georgian || !crown) {
                    suffixes.add(sideSuffix(span));
                }
                if (georgian) {
                    suffixes.addAll(bodyDetailSuffixes(part, span));
                }
                if (crown) {
                    suffixes.add(topSuffix(span));
                }
                table.add(List.copyOf(suffixes));
            }
        }
        return List.copyOf(table);
    }

    static List<String> bodyDetailSuffixes(AlcoveBlock.AlcovePart part, AlcoveBlock.AlcoveSpan span) {
        return GEORGIAN_BODY_SUFFIXES_BY_STATE.get(part.ordinal() * SPAN_COUNT + span.ordinal());
    }

    private static List<List<String>> bodyDetailSuffixTable() {
        List<List<String>> table = new ArrayList<>(AlcoveBlock.AlcovePart.values().length * SPAN_COUNT);
        for (var part : AlcoveBlock.AlcovePart.values()) {
            for (var span : AlcoveBlock.AlcoveSpan.values()) {
                if (part != AlcoveBlock.AlcovePart.BASE && part != AlcoveBlock.AlcovePart.MIDDLE) {
                    table.add(List.of());
                    continue;
                }
                String prefix = part == AlcoveBlock.AlcovePart.BASE ? "base_detail_" : "middle_detail_";
                table.add(switch (span) {
                    case SINGLE -> List.of(prefix + "left", prefix + "right");
                    case LEFT -> List.of("double_" + prefix + "left");
                    case RIGHT -> List.of("double_" + prefix + "right");
                    case TRIPLE_LEFT -> List.of("triple_" + prefix + "left");
                    case TRIPLE_RIGHT -> List.of("triple_" + prefix + "right");
                    case TRIPLE_CENTER -> List.of();
                });
            }
        }
        return List.copyOf(table);
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

    /** Family wrappers are recreated by the model-loading plugin on every resource reload. */
    private ComponentModels componentsFor(Block block) {
        ResolvedComponents cached = resolvedComponents;
        if (cached != null && cached.block == block) {
            return cached.models;
        }
        synchronized (this) {
            cached = resolvedComponents;
            if (cached != null && cached.block == block) {
                return cached.models;
            }
            Identifier blockId = Registries.BLOCK.getId(block);
            if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
                return null;
            }
            var manager = MinecraftClient.getInstance().getBakedModelManager();
            ComponentModels models = new ComponentModels(blockId.getPath(), manager::getModel);
            resolvedComponents = new ResolvedComponents(block, models);
            return models;
        }
    }

    private record ResolvedComponents(Block block, ComponentModels models) { }

    static final class ComponentModels {
        private final List<List<BakedModel>> selections;
        private final BakedModel icon;

        ComponentModels(String blockPath, Function<Identifier, BakedModel> resolver) {
            boolean georgian = ErydonModelFamilyIndex.isGeorgianAlcoveBlock(blockPath);
            Map<String, BakedModel> children = new HashMap<>();
            for (String suffix : modelSuffixes(blockPath)) {
                children.put(suffix, resolver.apply(modelId(blockPath, suffix)));
            }
            icon = children.get("icon");
            List<List<BakedModel>> states = new ArrayList<>(AlcoveBlock.AlcovePart.values().length * SPAN_COUNT);
            for (var part : AlcoveBlock.AlcovePart.values()) {
                for (var span : AlcoveBlock.AlcoveSpan.values()) {
                    List<BakedModel> selected = new ArrayList<>(4);
                    for (String suffix : componentSuffixes(part, span, georgian)) {
                        BakedModel child = children.get(suffix);
                        if (child != null) {
                            selected.add(child);
                        }
                    }
                    states.add(List.copyOf(selected));
                }
            }
            selections = List.copyOf(states);
        }

        List<BakedModel> forState(AlcoveBlock.AlcovePart part, AlcoveBlock.AlcoveSpan span) {
            return selections.get(part.ordinal() * SPAN_COUNT + span.ordinal());
        }
    }

    public static Identifier modelId(String blockPath, String suffix) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = ErydonIdNaming.isAged(resourcePath);
        String basePath = ErydonIdNaming.withoutAged(resourcePath);
        return new Identifier(Erydon.MOD_ID, modelRoot(basePath) + basePath + "_" + suffix + (aged ? "_aged" : ""));
    }

    private static String modelRoot(String blockPath) {
        if (ErydonModelFamilyIndex.isModernAlcoveBlock(blockPath)) {
            return MODERN_MODEL_ROOT;
        }
        return ErydonModelFamilyIndex.isGothicAlcoveBlock(blockPath)
                ? GOTHIC_MODEL_ROOT
                : GEORGIAN_MODEL_ROOT;
    }

    public static List<String> modelSuffixes(String blockPath) {
        List<String> suffixes = new ArrayList<>(BASE_MODEL_SUFFIXES.size() + TRIPLE_MODEL_SUFFIXES.size());
        suffixes.addAll(BASE_MODEL_SUFFIXES);
        suffixes.addAll(TRIPLE_MODEL_SUFFIXES);
        if (ErydonModelFamilyIndex.isGeorgianAlcoveBlock(blockPath)) {
            suffixes.addAll(GEORGIAN_BODY_MODEL_SUFFIXES);
        }
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
        ComponentModels components = componentsFor(state.getBlock());
        if (components == null) {
            return quads;
        }
        for (BakedModel component : components.forState(state.get(AlcoveBlock.PART), state.get(AlcoveBlock.SPAN))) {
            AxiomFallbackQuads.add(quads, component, degrees, face, random, true);
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
