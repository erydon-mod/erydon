package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.CeilingBlock;
import com.oliver.erydon.migration.ErydonIdMigration;
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

public final class CeilingCofferedBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_ROOT = "block/ceiling/coffered/";
    private static final String CORNICE_ROOT = "block/cornice/";

    private static final int NORTH_WALL_MASK = 1 << 0;
    private static final int EAST_WALL_MASK = 1 << 1;
    private static final int SOUTH_WALL_MASK = 1 << 2;
    private static final int WEST_WALL_MASK = 1 << 3;
    private static final int OUTER_NE_MASK = 1 << 4;
    private static final int OUTER_SE_MASK = 1 << 5;
    private static final int OUTER_SW_MASK = 1 << 6;
    private static final int OUTER_NW_MASK = 1 << 7;

    private final BakedModel wrapped;
    private final Sprite particle;

    public CeilingCofferedBakedModel(BakedModel wrapped) {
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
        if (!(state.getBlock() instanceof CeilingBlock)
                || !state.contains(CeilingBlock.FINISH)
                || !state.contains(CeilingBlock.UNUSED)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        Style style = styleForBlockPath(blockId.getPath());
        if (style == null) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        String finish = state.get(CeilingBlock.FINISH).asString();
        int mask = state.get(CeilingBlock.UNUSED).mask();

        emitModel(context, panelModelId(blockId.getPath(), finish), 0);
        emitIf(context, mask, NORTH_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 0);
        emitIf(context, mask, EAST_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 90);
        emitIf(context, mask, SOUTH_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 180);
        emitIf(context, mask, WEST_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 270);

        emitIf(context, mask, OUTER_NE_MASK, stemModelId(blockId.getPath(), finish, true), 0);
        emitIf(context, mask, OUTER_SE_MASK, stemModelId(blockId.getPath(), finish, true), 90);
        emitIf(context, mask, OUTER_SW_MASK, stemModelId(blockId.getPath(), finish, true), 180);
        emitIf(context, mask, OUTER_NW_MASK, stemModelId(blockId.getPath(), finish, true), 270);

        Identifier straightCornice = corniceModelId(blockId.getPath(), false);
        Identifier outerCornice = corniceModelId(blockId.getPath(), true);
        if (straightCornice != null && outerCornice != null) {
            emitCorniceIf(context, mask, NORTH_WALL_MASK, straightCornice, 0);
            emitCorniceIf(context, mask, EAST_WALL_MASK, straightCornice, 90);
            emitCorniceIf(context, mask, SOUTH_WALL_MASK, straightCornice, 180);
            emitCorniceIf(context, mask, WEST_WALL_MASK, straightCornice, 270);

            emitCorniceIf(context, mask, OUTER_NE_MASK, outerCornice, style.outerCornerRotation(0));
            emitCorniceIf(context, mask, OUTER_SE_MASK, outerCornice, style.outerCornerRotation(90));
            emitCorniceIf(context, mask, OUTER_SW_MASK, outerCornice, style.outerCornerRotation(180));
            emitCorniceIf(context, mask, OUTER_NW_MASK, outerCornice, style.outerCornerRotation(270));
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(wrapped);
    }

    private static void emitIf(RenderContext context, int mask, int bit, Identifier modelId, int degrees) {
        if ((mask & bit) != 0) {
            emitModel(context, modelId, degrees);
        }
    }

    private static void emitCorniceIf(RenderContext context, int mask, int bit, Identifier modelId, int degrees) {
        if ((mask & bit) != 0) {
            BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId);
            WorldAlignedYRotation.emit(context, model, degrees);
        }
    }

    private static void emitModel(RenderContext context, Identifier modelId, int degrees) {
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId);
        if (model != null) {
            emitTransformed(context, model, degrees);
        }
    }

    public static List<Identifier> modelIdsForBlock(String blockPath) {
        List<Identifier> ids = new ArrayList<>();
        Style style = styleForBlockPath(blockPath);
        if (style == null) {
            return ids;
        }

        for (String finish : new String[]{"matte", "gloss"}) {
            ids.add(panelModelId(blockPath, finish));
            ids.add(stemModelId(blockPath, finish, false));
            ids.add(stemModelId(blockPath, finish, true));
        }

        Identifier straightCornice = corniceModelId(blockPath, false);
        Identifier outerCornice = corniceModelId(blockPath, true);
        if (straightCornice != null && outerCornice != null) {
            ids.add(straightCornice);
            ids.add(outerCornice);
        }

        return ids;
    }

    public static Identifier panelModelId(String blockPath, String finish) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        Style style = styleForBlockPath(resourcePath);
        return new Identifier(Erydon.MOD_ID, MODEL_ROOT + style.path + "/" + resourcePath + "_" + finish);
    }

    private static Identifier stemModelId(String blockPath, String finish, boolean outer) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        Style style = styleForBlockPath(resourcePath);
        String genericPath = genericCeilingPath(resourcePath);
        return new Identifier(Erydon.MOD_ID,
                MODEL_ROOT + style.path + "/" + genericPath + "_" + finish + (outer ? "_stem_outer" : "_stem"));
    }

    private static Identifier corniceModelId(String blockPath, boolean outer) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        Style style = styleForBlockPath(resourcePath);
        String prefix = materialPrefix(resourcePath);
        if (style == null || prefix.isEmpty()) {
            return null;
        }

        return new Identifier(Erydon.MOD_ID,
                CORNICE_ROOT + style.path + "/" + prefix + "_cornice_" + style.path + "_" + style.corniceSuffix(outer));
    }

    private static Style styleForBlockPath(String blockPath) {
        blockPath = ErydonIdMigration.legacyResourcePath(blockPath);
        if (blockPath.contains("ceiling_coffered_georgian_")) {
            return Style.GEORGIAN;
        }
        if (blockPath.contains("ceiling_coffered_guilloche_")) {
            return Style.GUILLOCHE;
        }
        if (blockPath.contains("ceiling_coffered_modern_")) {
            return Style.MODERN;
        }
        return null;
    }

    private static String genericCeilingPath(String blockPath) {
        int index = blockPath.indexOf("ceiling_coffered_");
        return index >= 0 ? blockPath.substring(index) : blockPath;
    }

    private static String materialPrefix(String blockPath) {
        int index = blockPath.indexOf("ceiling_coffered_");
        if (index <= 0) {
            return "";
        }

        String prefix = blockPath.substring(0, index);
        return prefix.endsWith("_") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static void emitTransformed(RenderContext context, BakedModel model, int degrees) {
        int turns = Math.floorMod(degrees / 90, 4);
        if (turns == 0) {
            SharedGeometryChildModel.emit(context, model);
            return;
        }

        context.pushTransform(new YRotationTransform(turns));
        try {
            SharedGeometryChildModel.emit(context, model);
        } finally {
            context.popTransform();
        }
    }

    private enum Style {
        GEORGIAN("georgian", "outer_corner", 0),
        GUILLOCHE("guilloche", "outer_corner", 0),
        MODERN("modern", "outer", 90);

        private final String path;
        private final String outerSuffix;
        private final int outerRotationOffset;

        Style(String path, String outerSuffix, int outerRotationOffset) {
            this.path = path;
            this.outerSuffix = outerSuffix;
            this.outerRotationOffset = outerRotationOffset;
        }

        private String corniceSuffix(boolean outer) {
            return outer ? outerSuffix : "straight";
        }

        private int outerCornerRotation(int baseRotation) {
            return Math.floorMod(baseRotation + outerRotationOffset, 360);
        }
    }

    private static final class YRotationTransform implements RenderContext.QuadTransform {
        private final int turns;

        private YRotationTransform(int turns) {
            this.turns = turns;
        }

        @Override
        public boolean transform(MutableQuadView quad) {
            for (int vertex = 0; vertex < 4; vertex++) {
                float x = quad.x(vertex);
                float y = quad.y(vertex);
                float z = quad.z(vertex);
                quad.pos(vertex, rotateX(x, z), y, rotateZ(x, z));

                if (quad.hasNormal(vertex)) {
                    float normalX = quad.normalX(vertex);
                    float normalY = quad.normalY(vertex);
                    float normalZ = quad.normalZ(vertex);
                    quad.normal(vertex, rotateNormalX(normalX, normalZ), normalY, rotateNormalZ(normalX, normalZ));
                }
            }

            quad.cullFace(rotateFace(quad.cullFace()));
            quad.nominalFace(rotateFace(quad.nominalFace()));
            return true;
        }

        private float rotateX(float x, float z) {
            return switch (turns) {
                case 1 -> 1.0F - z;
                case 2 -> 1.0F - x;
                case 3 -> z;
                default -> x;
            };
        }

        private float rotateZ(float x, float z) {
            return switch (turns) {
                case 1 -> x;
                case 2 -> 1.0F - z;
                case 3 -> 1.0F - x;
                default -> z;
            };
        }

        private float rotateNormalX(float x, float z) {
            return switch (turns) {
                case 1 -> -z;
                case 2 -> -x;
                case 3 -> z;
                default -> x;
            };
        }

        private float rotateNormalZ(float x, float z) {
            return switch (turns) {
                case 1 -> x;
                case 2 -> -z;
                case 3 -> -x;
                default -> z;
            };
        }

        private Direction rotateFace(Direction face) {
            if (face == null || face.getAxis() == Direction.Axis.Y) {
                return face;
            }

            Direction rotated = face;
            for (int i = 0; i < turns; i++) {
                rotated = rotated.rotateYClockwise();
            }
            return rotated;
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (!(state != null
                && state.getBlock() instanceof CeilingBlock
                && state.contains(CeilingBlock.FINISH)
                && state.contains(CeilingBlock.UNUSED))) {
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

        String finish = state.get(CeilingBlock.FINISH).asString();
        int mask = state.get(CeilingBlock.UNUSED).mask();
        List<BakedQuad> quads = new ArrayList<>();

        addModelQuads(quads, panelModelId(blockId.getPath(), finish), 0, face, random);
        addIfQuads(quads, mask, NORTH_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 0, face, random);
        addIfQuads(quads, mask, EAST_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 90, face, random);
        addIfQuads(quads, mask, SOUTH_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 180, face, random);
        addIfQuads(quads, mask, WEST_WALL_MASK, stemModelId(blockId.getPath(), finish, false), 270, face, random);

        addIfQuads(quads, mask, OUTER_NE_MASK, stemModelId(blockId.getPath(), finish, true), 0, face, random);
        addIfQuads(quads, mask, OUTER_SE_MASK, stemModelId(blockId.getPath(), finish, true), 90, face, random);
        addIfQuads(quads, mask, OUTER_SW_MASK, stemModelId(blockId.getPath(), finish, true), 180, face, random);
        addIfQuads(quads, mask, OUTER_NW_MASK, stemModelId(blockId.getPath(), finish, true), 270, face, random);

        Identifier straightCornice = corniceModelId(blockId.getPath(), false);
        Identifier outerCornice = corniceModelId(blockId.getPath(), true);
        if (straightCornice != null && outerCornice != null) {
            addCorniceIfQuads(quads, mask, NORTH_WALL_MASK, straightCornice, 0, face, random);
            addCorniceIfQuads(quads, mask, EAST_WALL_MASK, straightCornice, 90, face, random);
            addCorniceIfQuads(quads, mask, SOUTH_WALL_MASK, straightCornice, 180, face, random);
            addCorniceIfQuads(quads, mask, WEST_WALL_MASK, straightCornice, 270, face, random);

            addCorniceIfQuads(quads, mask, OUTER_NE_MASK, outerCornice, style.outerCornerRotation(0), face, random);
            addCorniceIfQuads(quads, mask, OUTER_SE_MASK, outerCornice, style.outerCornerRotation(90), face, random);
            addCorniceIfQuads(quads, mask, OUTER_SW_MASK, outerCornice, style.outerCornerRotation(180), face, random);
            addCorniceIfQuads(quads, mask, OUTER_NW_MASK, outerCornice, style.outerCornerRotation(270), face, random);
        }

        return quads;
    }

    private static void addIfQuads(List<BakedQuad> quads,
                                   int mask,
                                   int bit,
                                   Identifier modelId,
                                   int degrees,
                                   Direction face,
                                   Random random) {
        if ((mask & bit) != 0) {
            addModelQuads(quads, modelId, degrees, face, random);
        }
    }

    private static void addCorniceIfQuads(List<BakedQuad> quads,
                                          int mask,
                                          int bit,
                                          Identifier modelId,
                                          int degrees,
                                          Direction face,
                                          Random random) {
        if ((mask & bit) != 0) {
            BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId);
            AxiomFallbackQuads.add(quads, model, degrees, face, random, true);
        }
    }

    private static void addModelQuads(List<BakedQuad> quads,
                                      Identifier modelId,
                                      int degrees,
                                      Direction face,
                                      Random random) {
        BakedModel model = MinecraftClient.getInstance().getBakedModelManager().getModel(modelId);
        AxiomFallbackQuads.add(quads, model, degrees, face, random);
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
