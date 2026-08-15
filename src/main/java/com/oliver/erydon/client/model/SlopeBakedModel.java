package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.SlopeBlock;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SlopeBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_PATH = "block/slope/slope/";
    public static final String[] MODEL_SUFFIXES = {"", "_inner", "_outer"};
    private static final int SIDE_STEPS = 16;
    private static final float MIN_SIDE_HEIGHT = 0.001F;

    private final BakedModel wrapped;
    private final Sprite particle;
    private final ModelTransformation transformation;
    private final Map<BlockState, List<BakedQuad>> quadCache = new ConcurrentHashMap<>();

    public SlopeBakedModel(BakedModel wrapped) {
        this(wrapped, ErydonSlopeModelClassifier.Family.STANDARD);
    }

    public SlopeBakedModel(BakedModel wrapped, ErydonSlopeModelClassifier.Family family) {
        this.wrapped = wrapped;
        this.particle = wrapped.getParticleSprite();
        this.transformation = ErydonSlopeItemTransforms.forFamily(family, wrapped.getTransformation());
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
        if (!(state.getBlock() instanceof SlopeBlock)
                || !state.contains(SlopeBlock.FACING)
                || !state.contains(SlopeBlock.SHAPE)
                || !state.contains(SlopeBlock.HALF)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        int xDegrees = state.get(SlopeBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = state.get(SlopeBlock.SHAPE) == SlopeBlock.SlopeShape.STRAIGHT
                ? rotationForFacing(state.get(SlopeBlock.FACING))
                : normalRotationForState(state);
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        FaceSprites sprites = repeatCtmSprites(state, pos, particle);
        if (state.get(SlopeBlock.SHAPE) != SlopeBlock.SlopeShape.STRAIGHT) {
            emitNativeCornerSlope(state, context, sprites, transform);
            return;
        }

        emitNativeStraightSlope(context, sprites, transform);
    }

    private static void emitNativeCornerSlope(BlockState state, RenderContext context, FaceSprites sprites, FixedSlopeRotation transform) {
        if (isOuterCorner(state.get(SlopeBlock.SHAPE))) {
            emitNativeOuterCorner(context, sprites, transform);
        } else {
            emitNativeInnerCorner(context, sprites, transform);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        emitNativeStraightSlope(context, face -> particle, FixedSlopeRotation.of(0, 0));
    }

    private static void emitNativeStraightSlope(RenderContext context, FaceSprites sprites, FixedSlopeRotation transform) {
        QuadEmitter emitter = context.getEmitter();

        emitQuad(emitter, sprites, transform, Direction.UP,
                0.0F, 1.0F, 0.0F,
                0.0F, 1.0F, 1.0F,
                1.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F);
        emitQuad(emitter, sprites, transform, Direction.WEST,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        emitQuad(emitter, sprites, transform, Direction.DOWN,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);

        emitSideStrips(emitter, sprites, transform, Direction.NORTH, 0.0F);
        emitSideStrips(emitter, sprites, transform, Direction.SOUTH, 1.0F);
    }

    private static void emitNativeOuterCorner(RenderContext context, FaceSprites sprites, FixedSlopeRotation transform) {
        QuadEmitter emitter = context.getEmitter();

        emitQuad(emitter, sprites, transform, Direction.DOWN,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);

        emitNorthOuterCorner(emitter, sprites, transform);
        emitWestOuterCorner(emitter, sprites, transform);
        emitOuterSlopeFaces(emitter, sprites, transform);
        emitOuterApexCover(emitter, sprites, transform);
    }

    private static void emitNativeInnerCorner(RenderContext context, FaceSprites sprites, FixedSlopeRotation transform) {
        QuadEmitter emitter = context.getEmitter();

        emitQuad(emitter, sprites, transform, Direction.DOWN,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);
        emitQuad(emitter, sprites, transform, Direction.WEST,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        emitQuad(emitter, sprites, transform, Direction.SOUTH,
                0.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 1.0F,
                1.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);

        emitNorthInnerCorner(emitter, sprites, transform);
        emitEastInnerCorner(emitter, sprites, transform);
        emitInnerSlopeFaces(emitter, sprites, transform);
    }

    private static void emitSideStrips(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform, Direction face, float z) {
        if (face == Direction.NORTH) {
            emitQuad(emitter, sprites, transform, face,
                    0.0F, 0.0F, z,
                    0.0F, 1.0F, z,
                    1.0F, MIN_SIDE_HEIGHT, z,
                    1.0F, 0.0F, z,
                    0.0F, 16.0F,
                    0.0F, 0.0F,
                    16.0F, (1.0F - MIN_SIDE_HEIGHT) * 16.0F,
                    16.0F, 16.0F);
        } else {
            emitQuad(emitter, sprites, transform, face,
                    0.0F, 0.0F, z,
                    1.0F, 0.0F, z,
                    1.0F, MIN_SIDE_HEIGHT, z,
                    0.0F, 1.0F, z,
                    0.0F, 16.0F,
                    16.0F, 16.0F,
                    16.0F, (1.0F - MIN_SIDE_HEIGHT) * 16.0F,
                    0.0F, 0.0F);
        }
    }

    private static void emitNorthOuterCorner(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float x0 = (float) i / SIDE_STEPS;
            float x1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - x0;
            float h1 = Math.max(1.0F - x1, 0.001F);
            emitQuad(emitter, sprites, transform, Direction.NORTH,
                    x0, 0.0F, 0.0F,
                    x0, h0, 0.0F,
                    x1, h1, 0.0F,
                    x1, 0.0F, 0.0F,
                    x0 * 16.0F, 16.0F,
                    x0 * 16.0F, (1.0F - h0) * 16.0F,
                    x1 * 16.0F, (1.0F - h1) * 16.0F,
                    x1 * 16.0F, 16.0F);
        }
    }

    private static void emitWestOuterCorner(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float z0 = (float) i / SIDE_STEPS;
            float z1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - z0;
            float h1 = Math.max(1.0F - z1, 0.001F);
            emitQuad(emitter, sprites, transform, Direction.WEST,
                    0.0F, 0.0F, z0,
                    0.0F, 0.0F, z1,
                    0.0F, h1, z1,
                    0.0F, h0, z0,
                    z0 * 16.0F, 16.0F,
                    z1 * 16.0F, 16.0F,
                    z1 * 16.0F, (1.0F - h1) * 16.0F,
                    z0 * 16.0F, (1.0F - h0) * 16.0F);
        }
    }

    private static void emitOuterSlopeFaces(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float t0 = (float) i / SIDE_STEPS;
            float t1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - t0;
            float h1 = Math.max(1.0F - t1, 0.001F);

            emitQuad(emitter, sprites, transform, Direction.UP,
                    t0, h0, 0.0F,
                    t0, h0, t0,
                    t1, h1, t1,
                    t1, h1, 0.0F,
                    t0 * 16.0F, 0.0F,
                    t0 * 16.0F, t0 * 16.0F,
                    t1 * 16.0F, t1 * 16.0F,
                    t1 * 16.0F, 0.0F);
            emitQuad(emitter, sprites, transform, Direction.UP,
                    0.0F, h0, t0,
                    0.0F, h1, t1,
                    t1, h1, t1,
                    t0, h0, t0,
                    0.0F, t0 * 16.0F,
                    0.0F, t1 * 16.0F,
                    t1 * 16.0F, t1 * 16.0F,
                    t0 * 16.0F, t0 * 16.0F);
        }
    }

    private static void emitOuterApexCover(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        float cap = 1.0F / SIDE_STEPS;
        float lift = 0.0005F;
        emitQuad(emitter, sprites, transform, Direction.UP,
                0.0F, 1.0F + lift, 0.0F,
                0.0F, 1.0F - cap + lift, cap,
                cap, 1.0F - cap + lift, cap,
                cap, 1.0F - cap + lift, 0.0F,
                0.0F, 0.0F,
                0.0F, cap * 16.0F,
                cap * 16.0F, cap * 16.0F,
                cap * 16.0F, 0.0F);
    }

    private static void emitNorthInnerCorner(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        emitNorthOuterCorner(emitter, sprites, transform);
    }

    private static void emitEastInnerCorner(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float z0 = (float) i / SIDE_STEPS;
            float z1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = z0;
            float h1 = Math.max(z1, 0.001F);
            emitQuad(emitter, sprites, transform, Direction.EAST,
                    1.0F, 0.0F, z0,
                    1.0F, h0, z0,
                    1.0F, h1, z1,
                    1.0F, 0.0F, z1,
                    z0 * 16.0F, 16.0F,
                    z0 * 16.0F, (1.0F - h0) * 16.0F,
                    z1 * 16.0F, (1.0F - h1) * 16.0F,
                    z1 * 16.0F, 16.0F);
        }
    }

    private static void emitInnerSlopeFaces(QuadEmitter emitter, FaceSprites sprites, FixedSlopeRotation transform) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float t0 = (float) i / SIDE_STEPS;
            float t1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - t0;
            float h1 = Math.max(1.0F - t1, 0.001F);

            emitQuad(emitter, sprites, transform, Direction.UP,
                    t0, h0, 0.0F,
                    t0, h0, h0,
                    t1, h1, h1,
                    t1, h1, 0.0F,
                    t0 * 16.0F, 0.0F,
                    t0 * 16.0F, h0 * 16.0F,
                    t1 * 16.0F, h1 * 16.0F,
                    t1 * 16.0F, 0.0F);
            emitQuad(emitter, sprites, transform, Direction.UP,
                    1.0F - h0, h0, h0,
                    1.0F, h0, h0,
                    1.0F, h1, h1,
                    1.0F - h1, h1, h1,
                    (1.0F - h0) * 16.0F, h0 * 16.0F,
                    16.0F, h0 * 16.0F,
                    16.0F, h1 * 16.0F,
                    (1.0F - h1) * 16.0F, h1 * 16.0F);
        }
    }

    private static void emitQuad(QuadEmitter emitter,
                                 FaceSprites sprites,
                                 FixedSlopeRotation transform,
                                 Direction nominalFace,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float u0, float v0,
                                 float u1, float v1,
                                 float u2, float v2,
                                 float u3, float v3) {
        Direction cullFace = SlopeQuadUtil.boundaryCullFace(nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);
        float ay0 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y0);
        float ay1 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y1);
        float ay2 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y2);
        float ay3 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y3);

        Direction finalFace = transform.mapFace(nominalFace);
        transform.writePosition(emitter, 0, x0, ay0, z0);
        transform.writePosition(emitter, 1, x1, ay1, z1);
        transform.writePosition(emitter, 2, x2, ay2, z2);
        transform.writePosition(emitter, 3, x3, ay3, z3);
        emitter.uv(0,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x0, y0, z0, u0, v0),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x0, y0, z0, u0, v0));
        emitter.uv(1,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x1, y1, z1, u1, v1),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x1, y1, z1, u1, v1));
        emitter.uv(2,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x2, y2, z2, u2, v2),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x2, y2, z2, u2, v2));
        emitter.uv(3,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x3, y3, z3, u3, v3),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x3, y3, z3, u3, v3));
        emitter.color(-1, -1, -1, -1);
        emitter.cullFace(cullFace == null ? null : transform.mapFace(cullFace));
        emitter.nominalFace(finalFace);
        Sprite sprite = sprites.sprite(finalFace);
        emitter.spriteBake(sprite, MutableQuadView.BAKE_ROTATE_NONE);
        emitter.emit();
    }

    private static int rotationForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 0;
            case SOUTH -> 90;
            case WEST -> 180;
            case NORTH -> 270;
            default -> 0;
        };
    }

    private static boolean isOuterCorner(SlopeBlock.SlopeShape shape) {
        return shape == SlopeBlock.SlopeShape.OUTER_LEFT || shape == SlopeBlock.SlopeShape.OUTER_RIGHT;
    }

    private static FaceSprites repeatCtmSprites(BlockState state,
                                                BlockPos pos,
                                                Sprite fallback) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        if (blockId == null) {
            return face -> fallback;
        }

        ErydonCtmService ctmService = ErydonCtmService.get(MinecraftClient.getInstance().getResourceManager());
        String ctmSet = ctmService.slopeCtmSetName(blockId.getPath());
        if (ctmSet == null) {
            return face -> fallback;
        }

        Sprite[] spriteCache = new Sprite[SlopeQuadUtil.DIRECTION_COUNT];
        return face -> {
            if (face == null) {
                return fallback;
            }
            Sprite cached = spriteCache[face.ordinal()];
            if (cached == null) {
                cached = ctmService.resolveSlopeRepeatSprite(state, pos, face, ctmSet);
                spriteCache[face.ordinal()] = cached;
            }
            return cached;
        };
    }


    public static Identifier modelId(String blockPath, String suffix) {
        String resourcePath = ErydonIdMigration.legacyResourcePath(blockPath);
        boolean aged = resourcePath.endsWith("_aged");
        String basePath = aged ? resourcePath.substring(0, resourcePath.length() - "_aged".length()) : resourcePath;
        return new Identifier(Erydon.MOD_ID, MODEL_PATH + basePath + suffix + (aged ? "_aged" : ""));
    }
    private static int repeatTileIndex(BlockPos pos, Direction face) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int tileX;
        int tileY;

        switch (face) {
            case DOWN -> {
                tileX = x;
                tileY = -z - 1;
            }
            case UP -> {
                tileX = x;
                tileY = z;
            }
            case NORTH -> {
                tileX = -x - 1;
                tileY = -y;
            }
            case SOUTH -> {
                tileX = x;
                tileY = -y;
            }
            case WEST -> {
                tileX = z;
                tileY = -y;
            }
            case EAST -> {
                tileX = -z - 1;
                tileY = -y;
            }
            default -> {
                tileX = 0;
                tileY = 0;
            }
        }

        return Math.floorMod(tileY, 6) * 6 + Math.floorMod(tileX, 6);
    }

    @FunctionalInterface
    private interface FaceSprites {
        Sprite sprite(Direction face);
    }

    private static int normalRotationForState(BlockState state) {
        int rotation = rotationForFacing(state.get(SlopeBlock.FACING));
        SlopeBlock.SlopeShape shape = state.get(SlopeBlock.SHAPE);
        boolean top = state.get(SlopeBlock.HALF) == BlockHalf.TOP;

        return switch (shape) {
            case STRAIGHT, INNER_RIGHT -> rotation;
            case INNER_LEFT -> top ? rotation - 90 : rotation;
            case OUTER_LEFT -> top ? rotation : rotation - 90;
            case OUTER_RIGHT -> top ? rotation + 90 : rotation;
        };
    }

    private static int cornerRotationForState(BlockState state) {
        Direction facing = state.get(SlopeBlock.FACING);
        BlockHalf half = state.get(SlopeBlock.HALF);
        SlopeBlock.SlopeShape shape = state.get(SlopeBlock.SHAPE);
        Direction adjustedFacing = facing;

        if (shape == SlopeBlock.SlopeShape.INNER_RIGHT || shape == SlopeBlock.SlopeShape.OUTER_RIGHT) {
            adjustedFacing = adjustedFacing.rotateYClockwise();
        }

        int yDegrees = rotationForFacing(adjustedFacing);

        if (shape == SlopeBlock.SlopeShape.OUTER_LEFT || shape == SlopeBlock.SlopeShape.OUTER_RIGHT) {
            yDegrees = (yDegrees + 270) % 360;
        }

        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            yDegrees = (yDegrees + 180) % 360;
        }

        return Math.floorMod(yDegrees + cornerStrikeExtraRotation(facing, half, shape), 360);
    }

    private static int cornerStrikeExtraRotation(Direction facing, BlockHalf half, SlopeBlock.SlopeShape shape) {
        if (half == BlockHalf.BOTTOM) {
            if (facing == Direction.EAST && shape == SlopeBlock.SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.NORTH && shape == SlopeBlock.SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.WEST && shape == SlopeBlock.SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.NORTH && shape == SlopeBlock.SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.SOUTH && shape == SlopeBlock.SlopeShape.OUTER_LEFT) return 180;
            if (facing == Direction.SOUTH && shape == SlopeBlock.SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.WEST && shape == SlopeBlock.SlopeShape.INNER_RIGHT) return 180;
            if (facing == Direction.EAST && shape == SlopeBlock.SlopeShape.OUTER_LEFT) return 180;
            return 0;
        }

        if (shape == SlopeBlock.SlopeShape.INNER_LEFT) return 90;
        if (shape == SlopeBlock.SlopeShape.INNER_RIGHT) return 270;
        if (shape == SlopeBlock.SlopeShape.OUTER_LEFT) return 90;
        if (shape == SlopeBlock.SlopeShape.OUTER_RIGHT) return 270;

        return 0;
    }

    private static void emitTransformed(RenderContext context, BakedModel model, int xDegrees, int yDegrees) {
        if (Math.floorMod(xDegrees, 360) == 0 && Math.floorMod(yDegrees, 360) == 0) {
            context.fallbackConsumer().accept(model);
            return;
        }

        context.pushTransform(FixedSlopeRotation.of(xDegrees, yDegrees));
        context.fallbackConsumer().accept(model);
        context.popTransform();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (state == null || !(state.getBlock() instanceof SlopeBlock)) {
            return Collections.emptyList();
        }

        return AxiomFallbackQuads.filterByFace(quadCache.computeIfAbsent(state, this::smoothQuads), face);
    }

    private List<BakedQuad> smoothQuads(BlockState state) {
        if (state.get(SlopeBlock.SHAPE) == SlopeBlock.SlopeShape.STRAIGHT) {
            return List.copyOf(smoothStraightQuads(state));
        }

        return List.copyOf(smoothCornerQuads(state));
    }

    private List<BakedQuad> smoothStraightQuads(BlockState state) {
        int xDegrees = state.get(SlopeBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = rotationForFacing(state.get(SlopeBlock.FACING));
        List<BakedQuad> quads = new ArrayList<>();

        addBakedQuad(quads, particle, Direction.UP, xDegrees, yDegrees,
                0.0F, 1.0F, 0.0F,
                0.0F, 1.0F, 1.0F,
                1.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F);
        addBakedQuad(quads, particle, Direction.WEST, xDegrees, yDegrees,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        addBakedQuad(quads, particle, Direction.DOWN, xDegrees, yDegrees,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);

        addBakedSideStrips(quads, particle, Direction.NORTH, 0.0F, xDegrees, yDegrees);
        addBakedSideStrips(quads, particle, Direction.SOUTH, 1.0F, xDegrees, yDegrees);
        return quads;
    }

    private static void addBakedSideStrips(List<BakedQuad> quads,
                                           Sprite sprite,
                                           Direction face,
                                           float z,
                                           int xDegrees,
                                           int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float x0 = (float) i / SIDE_STEPS;
            float x1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - x0;
            float h1 = Math.max(1.0F - x1, 0.001F);

            if (face == Direction.NORTH) {
                addBakedQuad(quads, sprite, face, xDegrees, yDegrees,
                        x0, 0.0F, z,
                        x0, h0, z,
                        x1, h1, z,
                        x1, 0.0F, z,
                        x0 * 16.0F, 16.0F,
                        x0 * 16.0F, (1.0F - h0) * 16.0F,
                        x1 * 16.0F, (1.0F - h1) * 16.0F,
                        x1 * 16.0F, 16.0F);
            } else {
                addBakedQuad(quads, sprite, face, xDegrees, yDegrees,
                        x0, 0.0F, z,
                        x1, 0.0F, z,
                        x1, h1, z,
                        x0, h0, z,
                        x0 * 16.0F, 16.0F,
                        x1 * 16.0F, 16.0F,
                        x1 * 16.0F, (1.0F - h1) * 16.0F,
                        x0 * 16.0F, (1.0F - h0) * 16.0F);
            }
        }
    }

    private static void addBakedQuad(List<BakedQuad> quads,
                                     Sprite sprite,
                                     Direction nominalFace,
                                     int xDegrees,
                                     int yDegrees,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3,
                                     float u0, float v0,
                                     float u1, float v1,
                                     float u2, float v2,
                                     float u3, float v3) {
        int[] data = new int[32];
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        Direction cullFace = SlopeQuadUtil.boundaryCullFace(nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);
        float ay0 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y0);
        float ay1 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y1);
        float ay2 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y2);
        float ay3 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, y0, y1, y2, y3, y3);

        Direction finalFace = transform.mapFace(nominalFace);
        packVertex(data, 0, sprite, transform, x0, ay0, z0,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x0, y0, z0, u0, v0),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x0, y0, z0, u0, v0));
        packVertex(data, 1, sprite, transform, x1, ay1, z1,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x1, y1, z1, u1, v1),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x1, y1, z1, u1, v1));
        packVertex(data, 2, sprite, transform, x2, ay2, z2,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x2, y2, z2, u2, v2),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x2, y2, z2, u2, v2));
        packVertex(data, 3, sprite, transform, x3, ay3, z3,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x3, y3, z3, u3, v3),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x3, y3, z3, u3, v3));

        quads.add(new BakedQuad(data, -1, finalFace, sprite, true));
    }

    private static void packVertex(int[] data,
                                   int vertex,
                                   Sprite sprite,
                                   FixedSlopeRotation transform,
                                   float x,
                                   float y,
                                   float z,
                                   float u,
                                   float v) {
        int offset = vertex * 8;
        transform.packPosition(data, vertex, x, y, z);
        data[offset + 3] = -1;
        data[offset + 4] = Float.floatToRawIntBits(sprite.getFrameU(u));
        data[offset + 5] = Float.floatToRawIntBits(sprite.getFrameV(v));
    }

    private List<BakedQuad> smoothCornerQuads(BlockState state) {
        int xDegrees = state.get(SlopeBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = normalRotationForState(state);
        List<BakedQuad> quads = new ArrayList<>();

        if (isOuterCorner(state.get(SlopeBlock.SHAPE))) {
            addBakedOuterCorner(quads, particle, xDegrees, yDegrees);
        } else {
            addBakedInnerCorner(quads, particle, xDegrees, yDegrees);
        }
        return quads;
    }

    private static void addBakedOuterCorner(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        addBakedQuad(quads, sprite, Direction.DOWN, xDegrees, yDegrees,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);
        addBakedNorthOuterCorner(quads, sprite, xDegrees, yDegrees);
        addBakedWestOuterCorner(quads, sprite, xDegrees, yDegrees);
        addBakedOuterSlopeFaces(quads, sprite, xDegrees, yDegrees);
    }

    private static void addBakedInnerCorner(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        addBakedQuad(quads, sprite, Direction.DOWN, xDegrees, yDegrees,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                16.0F, 0.0F,
                16.0F, 16.0F,
                0.0F, 16.0F);
        addBakedQuad(quads, sprite, Direction.WEST, xDegrees, yDegrees,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        addBakedQuad(quads, sprite, Direction.SOUTH, xDegrees, yDegrees,
                0.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 1.0F,
                1.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        addBakedNorthOuterCorner(quads, sprite, xDegrees, yDegrees);
        addBakedEastInnerCorner(quads, sprite, xDegrees, yDegrees);
        addBakedInnerSlopeFaces(quads, sprite, xDegrees, yDegrees);
    }

    private static void addBakedNorthOuterCorner(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float x0 = (float) i / SIDE_STEPS;
            float x1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - x0;
            float h1 = Math.max(1.0F - x1, 0.001F);
            addBakedQuad(quads, sprite, Direction.NORTH, xDegrees, yDegrees,
                    x0, 0.0F, 0.0F,
                    x0, h0, 0.0F,
                    x1, h1, 0.0F,
                    x1, 0.0F, 0.0F,
                    x0 * 16.0F, 16.0F,
                    x0 * 16.0F, (1.0F - h0) * 16.0F,
                    x1 * 16.0F, (1.0F - h1) * 16.0F,
                    x1 * 16.0F, 16.0F);
        }
    }

    private static void addBakedWestOuterCorner(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float z0 = (float) i / SIDE_STEPS;
            float z1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - z0;
            float h1 = Math.max(1.0F - z1, 0.001F);
            addBakedQuad(quads, sprite, Direction.WEST, xDegrees, yDegrees,
                    0.0F, 0.0F, z0,
                    0.0F, 0.0F, z1,
                    0.0F, h1, z1,
                    0.0F, h0, z0,
                    z0 * 16.0F, 16.0F,
                    z1 * 16.0F, 16.0F,
                    z1 * 16.0F, (1.0F - h1) * 16.0F,
                    z0 * 16.0F, (1.0F - h0) * 16.0F);
        }
    }

    private static void addBakedOuterSlopeFaces(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float t0 = (float) i / SIDE_STEPS;
            float t1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - t0;
            float h1 = Math.max(1.0F - t1, 0.001F);
            addBakedQuad(quads, sprite, Direction.UP, xDegrees, yDegrees,
                    t0, h0, 0.0F,
                    t0, h0, t0,
                    t1, h1, t1,
                    t1, h1, 0.0F,
                    t0 * 16.0F, 0.0F,
                    t0 * 16.0F, t0 * 16.0F,
                    t1 * 16.0F, t1 * 16.0F,
                    t1 * 16.0F, 0.0F);
            addBakedQuad(quads, sprite, Direction.UP, xDegrees, yDegrees,
                    0.0F, h0, t0,
                    0.0F, h1, t1,
                    t1, h1, t1,
                    t0, h0, t0,
                    0.0F, t0 * 16.0F,
                    0.0F, t1 * 16.0F,
                    t1 * 16.0F, t1 * 16.0F,
                    t0 * 16.0F, t0 * 16.0F);
        }
    }

    private static void addBakedEastInnerCorner(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float z0 = (float) i / SIDE_STEPS;
            float z1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = z0;
            float h1 = Math.max(z1, 0.001F);
            addBakedQuad(quads, sprite, Direction.EAST, xDegrees, yDegrees,
                    1.0F, 0.0F, z0,
                    1.0F, h0, z0,
                    1.0F, h1, z1,
                    1.0F, 0.0F, z1,
                    z0 * 16.0F, 16.0F,
                    z0 * 16.0F, (1.0F - h0) * 16.0F,
                    z1 * 16.0F, (1.0F - h1) * 16.0F,
                    z1 * 16.0F, 16.0F);
        }
    }

    private static void addBakedInnerSlopeFaces(List<BakedQuad> quads, Sprite sprite, int xDegrees, int yDegrees) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float t0 = (float) i / SIDE_STEPS;
            float t1 = (float) (i + 1) / SIDE_STEPS;
            float h0 = 1.0F - t0;
            float h1 = Math.max(1.0F - t1, 0.001F);
            addBakedQuad(quads, sprite, Direction.UP, xDegrees, yDegrees,
                    t0, h0, 0.0F,
                    t0, h0, h0,
                    t1, h1, h1,
                    t1, h1, 0.0F,
                    t0 * 16.0F, 0.0F,
                    t0 * 16.0F, h0 * 16.0F,
                    t1 * 16.0F, h1 * 16.0F,
                    t1 * 16.0F, 0.0F);
            addBakedQuad(quads, sprite, Direction.UP, xDegrees, yDegrees,
                    1.0F - h0, h0, h0,
                    1.0F, h0, h0,
                    1.0F, h1, h1,
                    1.0F - h1, h1, h1,
                    (1.0F - h0) * 16.0F, h0 * 16.0F,
                    16.0F, h0 * 16.0F,
                    16.0F, h1 * 16.0F,
                    (1.0F - h1) * 16.0F, h1 * 16.0F);
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
        return transformation;
    }

    @Override
    public ModelOverrideList getOverrides() {
        return wrapped.getOverrides();
    }
}

