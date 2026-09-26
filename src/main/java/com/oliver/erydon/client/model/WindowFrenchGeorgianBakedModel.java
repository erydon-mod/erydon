package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import com.oliver.erydon.block.WindowArchBlock;
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
    private final boolean highPolish;
    private final boolean highPolishTwoWay;
    private volatile Sprite mirrorSprite;
    private final Sprite particle;

    public WindowFrenchGeorgianBakedModel(BakedModel wrapped) {
        this(wrapped, false);
    }

    public WindowFrenchGeorgianBakedModel(BakedModel wrapped, boolean highPolish) {
        this(wrapped, highPolish, false);
    }

    public WindowFrenchGeorgianBakedModel(BakedModel wrapped, boolean highPolish, boolean highPolishTwoWay) {
        this.highPolish = highPolish;
        this.highPolishTwoWay = highPolishTwoWay;
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
                || !state.contains(WindowFrenchGeorgianBlock.SILL)
                || !state.contains(WindowFrenchGeorgianBlock.CORNER)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        emitFace(state, context, state.get(WindowFrenchGeorgianBlock.FACING),
                state.get(WindowFrenchGeorgianBlock.PIECE), state.get(WindowFrenchGeorgianBlock.HINGE),
                state.get(WindowFrenchGeorgianBlock.SILL));
        Direction secondary = WindowFrenchGeorgianBlock.secondaryFacing(state);
        if (secondary != null) {
            emitFace(state, context, secondary, WindowFrenchGeorgianBlock.cornerPiece(state),
                    state.get(WindowFrenchGeorgianBlock.HINGE), state.get(WindowFrenchGeorgianBlock.SILL));
        }
    }

    private void emitFace(BlockState state, RenderContext context, Direction facing,
                          WindowFrenchGeorgianBlock.Piece piece, DoorHinge hinge, boolean sill) {
        boolean splitLayers = pushSplitLayerTransform(context, state, facing, piece, hinge);
        try {
            int rotation = rotationForFacing(facing);
            String mainSuffix = mainSuffix(piece, state.get(WindowFrenchGeorgianBlock.OPEN), hinge);
            if (mainSuffix != null) {
                emit(state, context, mainSuffix, rotation);
            }

            if (sill) {
                emit(state, context, "sill", rotation);

                String sideSillSuffix = sideSillSuffix(piece, state.get(WindowFrenchGeorgianBlock.OPEN), hinge);
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

    private static String mainSuffix(WindowFrenchGeorgianBlock.Piece piece, boolean open, DoorHinge hinge) {
        if (!open) {
            return "closed_" + piece.asString();
        }

        return switch (piece) {
            case UPPER_SINGLE -> "open_upper_single_" + hingeSuffix(hinge);
            case LOWER_SINGLE -> "open_lower_single_" + hingeSuffix(hinge);
            case UPPER_MULTI_LH -> "open_upper_multi_lh";
            case UPPER_MULTI_MID -> "open_upper_multi_mid";
            case UPPER_MULTI_RH -> "open_upper_multi_rh";
            case LOWER_MULTI_LH -> "open_lower_multi_lh";
            case LOWER_MULTI_MID -> null;
            case LOWER_MULTI_RH -> "open_lower_multi_rh";
        };
    }

    private static String sideSillSuffix(WindowFrenchGeorgianBlock.Piece piece, boolean open, DoorHinge hinge) {
        if (!open) {
            return null;
        }

        return switch (piece) {
            case LOWER_SINGLE -> "sill_" + hingeSuffix(hinge);
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

    private boolean pushSplitLayerTransform(RenderContext context, BlockState state, Direction facing,
                                            WindowFrenchGeorgianBlock.Piece piece, DoorHinge hinge) {
        if (!ensureMaterials()) {
            return false;
        }

        RenderMaterial stone = solidMaterial;
        WindowArchBlock.Glass glass = state.get(WindowFrenchGeorgianBlock.GLASS);
        boolean open = state.get(WindowFrenchGeorgianBlock.OPEN);
        boolean leftWing = leftWing(piece, hinge);
        Sprite mirror = glass == WindowArchBlock.Glass.TWO_WAY ? mirrorSprite() : null;
        RenderMaterial mirrorMaterial = highPolishTwoWay ? translucentMaterial : solidMaterial;
        context.pushTransform(quad -> {
            Direction outside = facing;
            if (glass == WindowArchBlock.Glass.TWO_WAY && open && quad.colorIndex() == 0) {
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < 4; i++) {
                    float depth = facing.getAxis() == Direction.Axis.X ? quad.x(i) : quad.z(i);
                    min = Math.min(min, depth);
                    max = Math.max(max, depth);
                }
                outside = outsideForPane(facing, leftWing, max - min);
            }
            WindowArchBakedModel.applyGlassFinish(quad, glass, outside, mirror,
                    solidMaterial, translucentMaterial, stone, mirrorMaterial);
            return true;
        });
        return true;
    }

    static boolean leftWing(WindowFrenchGeorgianBlock.Piece piece, DoorHinge hinge) {
        return switch (piece) {
            case UPPER_SINGLE, LOWER_SINGLE -> hinge == DoorHinge.LEFT;
            case UPPER_MULTI_LH, LOWER_MULTI_LH -> true;
            default -> false;
        };
    }

    static Direction outsideForPane(Direction facing, boolean leftWing, float depthSpan) {
        // Open windows retain a fixed fanlight. Only the deep, hinged pane turns its coating.
        return depthSpan > 0.25F
                ? (leftWing ? facing.rotateYCounterclockwise() : facing.rotateYClockwise()) : facing;
    }

    private Sprite mirrorSprite() {
        Sprite sprite = mirrorSprite;
        if (sprite == null) {
            sprite = MinecraftClient.getInstance().getBakedModelManager()
                    .getModel(WindowArchBakedModel.MIRROR_MATERIAL_MODEL).getParticleSprite();
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
                && state.getBlock() instanceof WindowFrenchGeorgianBlock
                && state.contains(WindowFrenchGeorgianBlock.FACING)
                && state.contains(WindowFrenchGeorgianBlock.OPEN)
                && state.contains(WindowFrenchGeorgianBlock.HINGE)
                && state.contains(WindowFrenchGeorgianBlock.PIECE)
                && state.contains(WindowFrenchGeorgianBlock.SILL)
                && state.contains(WindowFrenchGeorgianBlock.CORNER))) {
            return wrapped.getQuads(state, face, random);
        }

        List<BakedQuad> quads = new ArrayList<>();
        appendFaceQuads(quads, state, state.get(WindowFrenchGeorgianBlock.FACING),
                state.get(WindowFrenchGeorgianBlock.PIECE), state.get(WindowFrenchGeorgianBlock.HINGE),
                state.get(WindowFrenchGeorgianBlock.SILL), face, random);
        Direction secondary = WindowFrenchGeorgianBlock.secondaryFacing(state);
        if (secondary != null) {
            appendFaceQuads(quads, state, secondary, WindowFrenchGeorgianBlock.cornerPiece(state),
                    state.get(WindowFrenchGeorgianBlock.HINGE), state.get(WindowFrenchGeorgianBlock.SILL),
                    face, random);
        }
        return quads;
    }

    private void appendFaceQuads(List<BakedQuad> output, BlockState state, Direction facing,
                                 WindowFrenchGeorgianBlock.Piece piece, DoorHinge hinge, boolean sill,
                                 Direction face, Random random) {
        int rotation = rotationForFacing(facing);
        List<BakedQuad> quads = new ArrayList<>();
        String mainSuffix = mainSuffix(piece, state.get(WindowFrenchGeorgianBlock.OPEN), hinge);
        if (mainSuffix != null) {
            addQuads(quads, state, mainSuffix, rotation, face, random);
        }

        if (sill) {
            addQuads(quads, state, "sill", rotation, face, random);

            String sideSillSuffix = sideSillSuffix(piece, state.get(WindowFrenchGeorgianBlock.OPEN), hinge);
            if (sideSillSuffix != null) {
                addQuads(quads, state, sideSillSuffix, rotation, face, random);
            }
        }
        if (state.get(WindowFrenchGeorgianBlock.GLASS) == WindowArchBlock.Glass.TWO_WAY) {
            boolean open = state.get(WindowFrenchGeorgianBlock.OPEN);
            boolean leftWing = leftWing(piece, hinge);
            Sprite mirror = mirrorSprite();
            for (int i = 0; i < quads.size(); i++) {
                BakedQuad quad = quads.get(i);
                if (quad.getColorIndex() != 0) continue;
                Direction outside = facing;
                if (open) {
                    int axis = facing.getAxis() == Direction.Axis.X ? 0 : 2;
                    int[] data = quad.getVertexData();
                    float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float depth = Float.intBitsToFloat(data[vertex * 8 + axis]);
                        min = Math.min(min, depth);
                        max = Math.max(max, depth);
                    }
                    outside = outsideForPane(facing, leftWing, max - min);
                }
                if (quad.getFace() == outside) quads.set(i, WindowArchBakedModel.mirrorQuad(quad, mirror));
            }
        }
        output.addAll(quads);
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
