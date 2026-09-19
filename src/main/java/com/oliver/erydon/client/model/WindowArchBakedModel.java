package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.WindowArchBlock;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
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
    public static final Identifier MIRROR_MATERIAL_MODEL = new Identifier(Erydon.MOD_ID, MODEL_PATH + "mirror_material");
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
    private final boolean highPolish;
    private final Sprite particle;
    // A wrapper belongs to one model reload, so its sprite cannot outlive the atlas.
    private volatile Sprite mirrorSprite;

    public WindowArchBakedModel(BakedModel wrapped) {
        this(wrapped, false);
    }

    public WindowArchBakedModel(BakedModel wrapped, boolean highPolish) {
        this.highPolish = highPolish;
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

        boolean splitLayers = pushSplitLayerTransform(context, state);
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

    private boolean pushSplitLayerTransform(RenderContext context, BlockState state) {
        if (!ensureMaterials()) {
            return false;
        }

        WindowArchBlock.Glass glass = state.get(WindowArchBlock.GLASS);
        Direction outside = state.get(WindowArchBlock.FACING);
        Sprite mirror = glass == WindowArchBlock.Glass.TWO_WAY ? mirrorSprite() : null;
        RenderMaterial stone = highPolish ? translucentMaterial : solidMaterial;
        context.pushTransform(quad -> {
            // The child rotation runs before this transform, including the 180-degree right upper.
            applyGlassFinish(quad, glass, outside, mirror, solidMaterial, translucentMaterial, stone);
            return true;
        });
        return true;
    }

    static void applyGlassFinish(MutableQuadView quad, WindowArchBlock.Glass glass, Direction outside,
                                 Sprite mirror, RenderMaterial solid, RenderMaterial translucent) {
        applyGlassFinish(quad, glass, outside, mirror, solid, translucent, solid);
    }

    static void applyGlassFinish(MutableQuadView quad, WindowArchBlock.Glass glass, Direction outside,
                                 Sprite mirror, RenderMaterial solid, RenderMaterial translucent, RenderMaterial stone) {
        if (glass.mirrorsFace(outside, quad.lightFace(), quad.colorIndex())) {
            quad.spriteBake(mirror, MutableQuadView.BAKE_LOCK_UV);
            quad.colorIndex(-1);
            quad.material(solid);
        } else {
            quad.material(quad.colorIndex() == 0 ? translucent : quad.colorIndex() < 0 ? stone : solid);
        }
    }

    private Sprite mirrorSprite() {
        Sprite sprite = mirrorSprite;
        if (sprite == null) {
            sprite = MinecraftClient.getInstance().getBakedModelManager().getModel(MIRROR_MATERIAL_MODEL).getParticleSprite();
            mirrorSprite = sprite;
        }
        return sprite;
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
        if (state.get(WindowArchBlock.GLASS) == WindowArchBlock.Glass.TWO_WAY) {
            Sprite mirror = mirrorSprite();
            Direction outside = state.get(WindowArchBlock.FACING);
            for (int i = 0; i < quads.size(); i++) {
                BakedQuad quad = quads.get(i);
                if (WindowArchBlock.Glass.TWO_WAY.mirrorsFace(outside, quad.getFace(), quad.getColorIndex())) {
                    quads.set(i, mirrorQuad(quad, mirror));
                }
            }
        }
        return quads;
    }

    private static BakedQuad mirrorQuad(BakedQuad quad, Sprite mirror) {
        int[] data = quad.getVertexData().clone();
        Sprite original = quad.getSprite();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            float u = Float.intBitsToFloat(data[offset + 4]);
            float v = Float.intBitsToFloat(data[offset + 5]);
            data[offset + 4] = Float.floatToRawIntBits(mirror.getFrameU(
                    16 * (u - original.getMinU()) / (original.getMaxU() - original.getMinU())));
            data[offset + 5] = Float.floatToRawIntBits(mirror.getFrameV(
                    16 * (v - original.getMinV()) / (original.getMaxV() - original.getMinV())));
        }
        return new BakedQuad(data, -1, quad.getFace(), mirror, quad.hasShade());
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
