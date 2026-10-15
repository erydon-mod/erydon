package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.ArchGeorgianBlock;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.GeorgianArchLayout;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.Block;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Pre-derived Georgian cells retain fixed detailing through every cluster width. */
public final class ArchGeorgianBakedModel implements BakedModel, FabricBakedModel, ArchQuadSources.Complete {
    private static final int ARRANGEMENT_COUNT = ArchRomanesqueBlock.Arrangement.values().length;
    private final BakedModel wrapped;
    // Each wrapper and its child references belong to one resource reload.
    private final Map<Block, ComponentModels> children = new ConcurrentHashMap<>();

    public ArchGeorgianBakedModel(BakedModel wrapped) { this.wrapped = wrapped; }

    public static List<String> modelSuffixes(String blockPath) { return GeorgianArchLayout.modelSuffixes(); }

    public static Identifier modelId(String blockPath, String suffix) {
        return new Identifier(Erydon.MOD_ID, "block/arch/georgian/" + blockPath + "_" + suffix);
    }

    @Override public boolean isVanillaAdapter() { return false; }

    @Override @SuppressWarnings("removal")
    public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos,
                               Supplier<Random> randomSupplier, RenderContext context) {
        if (!supports(state)) { context.fallbackConsumer().accept(wrapped); return; }
        ComponentModels models = components(state.getBlock());
        if (models == null) return;
        int rotation = rotation(state.get(ArchRomanesqueBlock.FACING));
        for (BakedModel child : models.forState(state.get(ArchRomanesqueBlock.ARRANGEMENT),
                state.get(ArchRomanesqueBlock.WIDTH))) WorldAlignedYRotation.emit(context, child, rotation);
    }

    @Override @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ArchGeorgianBlock) {
            var models = components(item.getBlock());
            if (models != null && models.icon != null) { SharedGeometryChildModel.emit(context, models.icon); return; }
        }
        context.fallbackConsumer().accept(wrapped);
    }

    @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!supports(state)) return wrapped.getQuads(state, face, random);
        var result = new ArrayList<BakedQuad>();
        var models = components(state.getBlock());
        if (models == null) return result;
        int rotation = rotation(state.get(ArchRomanesqueBlock.FACING));
        for (BakedModel child : models.forState(state.get(ArchRomanesqueBlock.ARRANGEMENT),
                state.get(ArchRomanesqueBlock.WIDTH))) {
            AxiomFallbackQuads.add(result, child, rotation, face, random, true);
        }
        return result;
    }

    private static boolean supports(BlockState state) { return state != null && state.getBlock() instanceof ArchGeorgianBlock; }

    private static int rotation(Direction facing) {
        return switch (facing) { case NORTH -> 180; case EAST -> 270; case WEST -> 90; default -> 0; };
    }

    private ComponentModels components(Block block) {
        return children.computeIfAbsent(block, candidate -> {
            Identifier id = Registries.BLOCK.getId(candidate);
            if (id == null || !Erydon.MOD_ID.equals(id.getNamespace())) return null;
            var manager = MinecraftClient.getInstance().getBakedModelManager();
            return new ComponentModels(id.getPath(), manager::getModel);
        });
    }

    static final class ComponentModels {
        private final BakedModel icon;
        private final List<List<BakedModel>> selections;

        ComponentModels(String blockPath, Function<Identifier, BakedModel> resolver) {
            var resolved = new HashMap<String, BakedModel>();
            for (String suffix : modelSuffixes(blockPath)) resolved.put(suffix, resolver.apply(modelId(blockPath, suffix)));
            var models = Collections.unmodifiableMap(resolved);
            icon = models.get("icon");
            var states = new ArrayList<List<BakedModel>>();
            for (int width = 1; width <= 6; width++) for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) {
                var selection = new ArrayList<BakedModel>(2);
                for (String suffix : GeorgianArchLayout.components(arrangement, width)) {
                    var child = models.get(suffix);
                    if (child != null) selection.add(child);
                }
                states.add(List.copyOf(selection));
            }
            selections = List.copyOf(states);
        }

        List<BakedModel> forState(ArchRomanesqueBlock.Arrangement arrangement, int width) {
            return selections.get((Math.max(1, Math.min(6, width)) - 1) * ARRANGEMENT_COUNT
                    + arrangement.ordinal());
        }
    }

    @Override public boolean useAmbientOcclusion() { return wrapped.useAmbientOcclusion(); }
    @Override public boolean hasDepth() { return wrapped.hasDepth(); }
    @Override public boolean isSideLit() { return wrapped.isSideLit(); }
    @Override public boolean isBuiltin() { return wrapped.isBuiltin(); }
    @Override public Sprite getParticleSprite() { return wrapped.getParticleSprite(); }
    @Override public ModelTransformation getTransformation() { return wrapped.getTransformation(); }
    @Override public ModelOverrideList getOverrides() { return wrapped.getOverrides(); }
}
