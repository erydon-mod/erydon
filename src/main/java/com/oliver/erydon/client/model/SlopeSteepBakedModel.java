package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.SlopeSteepBlock;
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

public final class SlopeSteepBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_PATH = "block/slope/steep/";
    public static final String[] MODEL_SUFFIXES = {"", "_inner", "_outer"};
    private static final float MIN_SIDE_HEIGHT = 0.001F;

    private final BakedModel wrapped;
    private final Sprite particle;
    private final ModelTransformation transformation;
    private final Map<BlockState, List<BakedQuad>> quadCache = new ConcurrentHashMap<>();

    public SlopeSteepBakedModel(BakedModel wrapped) {
        this(wrapped, ErydonSlopeModelClassifier.Family.STEEP_LOWER);
    }

    public SlopeSteepBakedModel(BakedModel wrapped, ErydonSlopeModelClassifier.Family family) {
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
        if (!(state.getBlock() instanceof SlopeSteepBlock)
                || !state.contains(SlopeSteepBlock.FACING)
                || !state.contains(SlopeSteepBlock.SHAPE)
                || !state.contains(SlopeSteepBlock.HALF)) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        int xDegrees = state.get(SlopeSteepBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = state.get(SlopeSteepBlock.SHAPE) == SlopeSteepBlock.SlopeShape.STRAIGHT
                ? rotationForFacing(state.get(SlopeSteepBlock.FACING))
                : normalRotationForState(state);
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        FaceSprites sprites = repeatCtmSprites(state, pos, particle);
        boolean upperVariant = isUpperVariant(state);
        if (state.get(SlopeSteepBlock.SHAPE) != SlopeSteepBlock.SlopeShape.STRAIGHT) {
            emitNativeCornerSlope(state, context, sprites, transform, upperVariant);
            return;
        }

        emitNativeStraightSlope(context, sprites, transform, upperVariant);
    }

    private static void emitNativeCornerSlope(BlockState state, RenderContext context, FaceSprites sprites, FixedSlopeRotation transform, boolean upperVariant) {
        if (isOuterCorner(state.get(SlopeSteepBlock.SHAPE))) {
            emitNativeOuterCorner(context, sprites, transform, upperVariant);
        } else {
            emitNativeInnerCorner(context, sprites, transform, upperVariant);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        boolean upperVariant = ErydonSlopeModelClassifier.familyForId(itemId)
                == ErydonSlopeModelClassifier.Family.STEEP_UPPER;
        emitNativeStraightSlope(context, face -> particle, FixedSlopeRotation.of(0, 0), upperVariant);
    }

    private static void emitNativeStraightSlope(RenderContext context, FaceSprites sprites, FixedSlopeRotation transform, boolean upperVariant) {
        QuadEmitter emitter = context.getEmitter();
        emitStraightGeometry((nominalFace,
                              x0, y0, z0,
                              x1, y1, z1,
                              x2, y2, z2,
                              x3, y3, z3,
                              u0, v0,
                              u1, v1,
                              u2, v2,
                              u3, v3) -> emitQuad(emitter, sprites, transform, nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant);
    }

    private static void emitStraightGeometry(CornerQuadSink sink, boolean upperVariant) {
        if (upperVariant) {
            sink.quad(Direction.UP,
                    0.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 1.0F, 1.0F,
                    0.5F, 1.0F, 0.0F,
                    0.0F, 0.0F,
                    0.0F, 16.0F,
                    8.0F, 16.0F,
                    8.0F, 0.0F);
            sink.quad(Direction.UP,
                    0.5F, 1.0F, 0.0F,
                    0.5F, 1.0F, 1.0F,
                    1.0F, 0.0F, 1.0F,
                    1.0F, 0.0F, 0.0F,
                    8.0F, 0.0F,
                    8.0F, 16.0F,
                    16.0F, 16.0F,
                    16.0F, 0.0F);
        } else {
            sink.quad(Direction.UP,
                    0.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 0.0F, 1.0F,
                    0.5F, 0.0F, 0.0F,
                    0.0F, 0.0F,
                    0.0F, 16.0F,
                    8.0F, 16.0F,
                    8.0F, 0.0F);
        }

        sink.quad(Direction.WEST,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 16.0F,
                16.0F, 16.0F,
                16.0F, 0.0F,
                0.0F, 0.0F);
        float bottomMaxX = upperVariant ? 1.0F : 0.5F;
        sink.quad(Direction.DOWN,
                0.0F, 0.0F, 0.0F,
                bottomMaxX, 0.0F, 0.0F,
                bottomMaxX, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F,
                0.0F, 0.0F,
                bottomMaxX * 16.0F, 0.0F,
                bottomMaxX * 16.0F, 16.0F,
                0.0F, 16.0F);

        emitSideStrips(sink, Direction.NORTH, 0.0F, upperVariant);
        emitSideStrips(sink, Direction.SOUTH, 1.0F, upperVariant);
    }

    private static void emitNativeOuterCorner(RenderContext context,
                                              FaceSprites sprites,
                                              FixedSlopeRotation transform,
                                              boolean upperVariant) {
        QuadEmitter emitter = context.getEmitter();
        emitCornerHeightField(emitter, sprites, transform, upperVariant, true);
    }

    private static void emitNativeInnerCorner(RenderContext context,
                                              FaceSprites sprites,
                                              FixedSlopeRotation transform,
                                              boolean upperVariant) {
        QuadEmitter emitter = context.getEmitter();
        emitCornerHeightField(emitter, sprites, transform, upperVariant, false);
    }

    private static void emitCornerHeightField(QuadEmitter emitter,
                                              FaceSprites sprites,
                                              FixedSlopeRotation transform,
                                              boolean upperVariant,
                                              boolean outer) {
        emitCornerGeometry((nominalFace,
                            x0, y0, z0,
                            x1, y1, z1,
                            x2, y2, z2,
                            x3, y3, z3,
                            u0, v0,
                            u1, v1,
                            u2, v2,
                            u3, v3) -> emitQuad(emitter, sprites, transform, nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant, outer);
    }

    private static void emitCornerGeometry(CornerQuadSink sink, boolean upperVariant, boolean outer) {
        emitCornerBottomCap(sink, upperVariant, outer);
        emitExactCornerTop(sink, upperVariant, outer);
        emitCornerBoundarySides(sink, upperVariant, outer);
    }

    private static void emitCornerBottomCap(CornerQuadSink sink, boolean upperVariant, boolean outer) {
        if (upperVariant) {
            emitBottomCapQuad(sink, 0.0F, 0.0F, 1.0F, 1.0F);
            return;
        }

        if (outer) {
            emitBottomCapQuad(sink, 0.0F, 0.5F, 0.5F, 1.0F);
            return;
        }

        emitBottomCapQuad(sink, 0.0F, 0.0F, 0.5F, 1.0F);
        emitBottomCapQuad(sink, 0.5F, 0.5F, 1.0F, 1.0F);
    }

    private static void emitBottomCapQuad(CornerQuadSink sink,
                                          float minX,
                                          float minZ,
                                          float maxX,
                                          float maxZ) {
        sink.quad(Direction.DOWN,
                minX, 0.0F, minZ,
                maxX, 0.0F, minZ,
                maxX, 0.0F, maxZ,
                minX, 0.0F, maxZ,
                minX * 16.0F, minZ * 16.0F,
                maxX * 16.0F, minZ * 16.0F,
                maxX * 16.0F, maxZ * 16.0F,
                minX * 16.0F, maxZ * 16.0F);
    }

    private static void emitExactCornerTop(CornerQuadSink sink, boolean upperVariant, boolean outer) {
        if (!upperVariant && outer) {
            emitTopTriangle(sink, upperVariant, outer,
                    0.0F, 0.0F, 0.5F,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 0.0F, 0.5F);
            emitTopTriangle(sink, upperVariant, outer,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 0.0F, 1.0F,
                    0.5F, 0.0F, 0.5F);
            return;
        }

        if (!upperVariant) {
            // Split each trapezoidal wing into one rectangular quad and one
            // right triangle. Iris can then derive one stable POM sampling
            // rectangle for every primitive without changing the visible UVs.
            emitTopQuad(sink, upperVariant, outer,
                    0.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 0.5F,
                    0.5F, 0.0F, 0.5F,
                    0.5F, 0.0F, 0.0F);
            emitTopTriangle(sink, upperVariant, outer,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 0.0F, 0.5F,
                    0.0F, 1.0F, 0.5F);
            emitTopQuad(sink, upperVariant, outer,
                    0.5F, 1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F,
                    1.0F, 0.0F, 0.5F,
                    0.5F, 0.0F, 0.5F);
            emitTopTriangle(sink, upperVariant, outer,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 1.0F, 1.0F,
                    0.5F, 0.0F, 0.5F);
            return;
        }

        if (outer) {
            emitTopQuad(sink, upperVariant, outer,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.5F,
                    0.5F, 1.0F, 0.5F,
                    0.5F, 0.0F, 0.0F);
            emitTopTriangle(sink, upperVariant, outer,
                    0.5F, 0.0F, 0.0F,
                    0.5F, 1.0F, 0.5F,
                    1.0F, 0.0F, 0.0F);
            emitTopQuad(sink, upperVariant, outer,
                    0.0F, 1.0F, 0.5F,
                    0.0F, 1.0F, 1.0F,
                    0.5F, 1.0F, 1.0F,
                    0.5F, 1.0F, 0.5F);
            emitTopQuad(sink, upperVariant, outer,
                    0.5F, 1.0F, 0.5F,
                    0.5F, 1.0F, 1.0F,
                    1.0F, 0.0F, 1.0F,
                    1.0F, 0.0F, 0.5F);
            emitTopTriangle(sink, upperVariant, outer,
                    0.5F, 1.0F, 0.5F,
                    1.0F, 0.0F, 0.5F,
                    1.0F, 0.0F, 0.0F);
            return;
        }

        emitTopQuad(sink, upperVariant, outer,
                0.0F, 1.0F, 0.0F,
                0.0F, 1.0F, 1.0F,
                0.5F, 1.0F, 1.0F,
                0.5F, 1.0F, 0.0F);
        emitTopQuad(sink, upperVariant, outer,
                0.5F, 1.0F, 0.5F,
                0.5F, 1.0F, 1.0F,
                1.0F, 1.0F, 1.0F,
                1.0F, 1.0F, 0.5F);
        emitTopTriangle(sink, upperVariant, outer,
                0.5F, 1.0F, 0.0F,
                0.5F, 1.0F, 0.5F,
                1.0F, 0.0F, 0.0F);
        emitTopTriangle(sink, upperVariant, outer,
                0.5F, 1.0F, 0.5F,
                1.0F, 1.0F, 0.5F,
                1.0F, 0.0F, 0.0F);
    }

    private static void emitTopQuad(CornerQuadSink sink,
                                    boolean upperVariant,
                                    boolean outer,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2,
                                    float x3, float y3, float z3) {
        sink.quad(Direction.UP,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                cornerTopU(x0), cornerTopV(z0),
                cornerTopU(x1), cornerTopV(z1),
                cornerTopU(x2), cornerTopV(z2),
                cornerTopU(x3), cornerTopV(z3));
    }

    private static void emitTopTriangle(CornerQuadSink sink,
                                        boolean upperVariant,
                                        boolean outer,
                                        float x0, float y0, float z0,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2) {
        emitTopQuad(sink, upperVariant, outer,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x2, y2, z2);
    }

    private static float cornerTopU(float x) {
        return x * 16.0F;
    }

    private static float cornerTopV(float z) {
        return z * 16.0F;
    }

    private static void emitCornerBoundarySides(CornerQuadSink sink,
                                                boolean upperVariant,
                                                boolean outer) {
        emitBoundaryProfile(sink, Direction.NORTH, upperVariant, outer);
        emitBoundaryProfile(sink, Direction.SOUTH, upperVariant, outer);
        emitBoundaryProfile(sink, Direction.WEST, upperVariant, outer);
        emitBoundaryProfile(sink, Direction.EAST, upperVariant, outer);
    }

    private static void emitBoundaryProfile(CornerQuadSink sink,
                                            Direction face,
                                            boolean upperVariant,
                                            boolean outer) {
        float h0 = boundaryHeight(face, upperVariant, outer, 0.0F);
        float hMid = boundaryHeight(face, upperVariant, outer, 0.5F);
        float h1 = boundaryHeight(face, upperVariant, outer, 1.0F);

        if (linearHeight(h0, hMid, h1)) {
            emitBoundarySegment(sink, face, 0.0F, 1.0F, h0, h1);
            return;
        }

        emitBoundarySegment(sink, face, 0.0F, 0.5F, h0, hMid);
        emitBoundarySegment(sink, face, 0.5F, 1.0F, hMid, h1);
    }

    private static float boundaryHeight(Direction face, boolean upperVariant, boolean outer, float t) {
        return switch (face) {
            case NORTH -> cornerHeight(upperVariant, outer, t, 0.0F);
            case SOUTH -> cornerHeight(upperVariant, outer, t, 1.0F);
            case WEST -> cornerHeight(upperVariant, outer, 0.0F, t);
            case EAST -> cornerHeight(upperVariant, outer, 1.0F, t);
            default -> 0.0F;
        };
    }

    private static boolean linearHeight(float h0, float hMid, float h1) {
        return Math.abs(hMid - ((h0 + h1) * 0.5F)) <= 0.0001F;
    }

    private static void emitBoundarySegment(CornerQuadSink sink,
                                            Direction face,
                                            float t0,
                                            float t1,
                                            float h0,
                                            float h1) {
        switch (face) {
            case NORTH -> emitBoundarySide(sink, face, t0, 0.0F, t1, 0.0F, h0, h1);
            case SOUTH -> emitBoundarySide(sink, face, t0, 1.0F, t1, 1.0F, h0, h1);
            case WEST -> emitBoundarySide(sink, face, 0.0F, t0, 0.0F, t1, h0, h1);
            case EAST -> emitBoundarySide(sink, face, 1.0F, t0, 1.0F, t1, h0, h1);
            default -> {
            }
        }
    }

    private static void emitBoundarySide(CornerQuadSink sink,
                                         Direction face,
                                         float x0,
                                         float z0,
                                         float x1,
                                         float z1,
                                         float h0,
                                         float h1) {
        if (h0 <= 0.0F && h1 <= 0.0F) {
            return;
        }

        float u0 = sideU(face, x0, z0);
        float u1 = sideU(face, x1, z1);
        if (face == Direction.NORTH || face == Direction.EAST) {
            if (h0 <= 0.0F) {
                sink.quad(face,
                        x0, 0.0F, z0,
                        x1, h1, z1,
                        x1, 0.0F, z1,
                        x1, 0.0F, z1,
                        u0, 16.0F,
                        u1, (1.0F - h1) * 16.0F,
                        u1, 16.0F,
                        u1, 16.0F);
                return;
            }
            sink.quad(face,
                    x0, 0.0F, z0,
                    x0, h0, z0,
                    x1, h1, z1,
                    x1, 0.0F, z1,
                    u0, 16.0F,
                    u0, (1.0F - h0) * 16.0F,
                    u1, (1.0F - h1) * 16.0F,
                    u1, 16.0F);
        } else {
            if (h0 <= 0.0F) {
                sink.quad(face,
                        x0, 0.0F, z0,
                        x1, 0.0F, z1,
                        x1, h1, z1,
                        x1, h1, z1,
                        u0, 16.0F,
                        u1, 16.0F,
                        u1, (1.0F - h1) * 16.0F,
                        u1, (1.0F - h1) * 16.0F);
                return;
            }
            if (h1 <= 0.0F) {
                sink.quad(face,
                        x0, 0.0F, z0,
                        x1, 0.0F, z1,
                        x0, h0, z0,
                        x0, h0, z0,
                        u0, 16.0F,
                        u1, 16.0F,
                        u0, (1.0F - h0) * 16.0F,
                        u0, (1.0F - h0) * 16.0F);
                return;
            }
            sink.quad(face,
                    x0, 0.0F, z0,
                    x1, 0.0F, z1,
                    x1, h1, z1,
                    x0, h0, z0,
                    u0, 16.0F,
                    u1, 16.0F,
                    u1, (1.0F - h1) * 16.0F,
                    u0, (1.0F - h0) * 16.0F);
        }
    }

    private static float sideU(Direction face, float x, float z) {
        return switch (face) {
            case NORTH, SOUTH -> x * 16.0F;
            case EAST, WEST -> z * 16.0F;
            default -> 0.0F;
        };
    }

    @FunctionalInterface
    private interface CornerQuadSink {
        void quad(Direction nominalFace,
                  float x0, float y0, float z0,
                  float x1, float y1, float z1,
                  float x2, float y2, float z2,
                  float x3, float y3, float z3,
                  float u0, float v0,
                  float u1, float v1,
                  float u2, float v2,
                  float u3, float v3);
    }

    static List<CornerDebugQuad> debugLogicalCornerGeometry(boolean upperVariant,
                                                           boolean outer,
                                                           int xDegrees,
                                                           int yDegrees) {
        return debugCornerGeometry(upperVariant, outer, xDegrees, yDegrees, false);
    }

    static List<CornerDebugQuad> debugLiveCornerGeometry(boolean upperVariant,
                                                        boolean outer,
                                                        int xDegrees,
                                                        int yDegrees) {
        return debugCornerGeometry(upperVariant, outer, xDegrees, yDegrees, true);
    }

    static List<CornerDebugQuad> debugBakedCornerGeometry(boolean upperVariant,
                                                         boolean outer,
                                                         int xDegrees,
                                                         int yDegrees) {
        return debugCornerGeometry(upperVariant, outer, xDegrees, yDegrees, true);
    }

    static List<CornerDebugQuad> debugLogicalStraightGeometry(boolean upperVariant, int xDegrees, int yDegrees) {
        return debugStraightGeometry(upperVariant, xDegrees, yDegrees, false);
    }

    static List<CornerDebugQuad> debugLiveStraightGeometry(boolean upperVariant, int xDegrees, int yDegrees) {
        return debugStraightGeometry(upperVariant, xDegrees, yDegrees, true);
    }

    static List<CornerDebugQuad> debugBakedStraightGeometry(boolean upperVariant, int xDegrees, int yDegrees) {
        return debugStraightGeometry(upperVariant, xDegrees, yDegrees, true);
    }

    private static List<CornerDebugQuad> debugCornerGeometry(boolean upperVariant,
                                                            boolean outer,
                                                            int xDegrees,
                                                            int yDegrees,
                                                            boolean applyEmitAdjustment) {
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        List<CornerDebugQuad> quads = new ArrayList<>();
        emitCornerGeometry((nominalFace,
                            x0, y0, z0,
                            x1, y1, z1,
                            x2, y2, z2,
                            x3, y3, z3,
                            u0, v0,
                            u1, v1,
                            u2, v2,
                            u3, v3) -> addDebugQuad(quads, transform, nominalFace, applyEmitAdjustment,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant, outer);
        return List.copyOf(quads);
    }

    private static List<CornerDebugQuad> debugStraightGeometry(boolean upperVariant,
                                                              int xDegrees,
                                                              int yDegrees,
                                                              boolean applyEmitAdjustment) {
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        List<CornerDebugQuad> quads = new ArrayList<>();
        emitStraightGeometry((nominalFace,
                              x0, y0, z0,
                              x1, y1, z1,
                              x2, y2, z2,
                              x3, y3, z3,
                              u0, v0,
                              u1, v1,
                              u2, v2,
                              u3, v3) -> addDebugQuad(quads, transform, nominalFace, applyEmitAdjustment,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant);
        return List.copyOf(quads);
    }

    private static void addDebugQuad(List<CornerDebugQuad> quads,
                                     FixedSlopeRotation transform,
                                     Direction nominalFace,
                                     boolean applyEmitAdjustment,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3,
                                     float u0, float v0,
                                     float u1, float v1,
                                     float u2, float v2,
                                     float u3, float v3) {
        float sourceY0 = y0;
        float sourceY1 = y1;
        float sourceY2 = y2;
        float sourceY3 = y3;
        if (applyEmitAdjustment) {
            Direction cullFace = SlopeQuadUtil.boundaryCullFace(nominalFace,
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2,
                    x3, y3, z3);
            y0 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, sourceY0, sourceY1, sourceY2, sourceY3, sourceY0);
            y1 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, sourceY0, sourceY1, sourceY2, sourceY3, sourceY1);
            y2 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, sourceY0, sourceY1, sourceY2, sourceY3, sourceY2);
            y3 = SlopeQuadUtil.partialHorizontalBoundaryOutsetY(cullFace, nominalFace, sourceY0, sourceY1, sourceY2, sourceY3, sourceY3);
        }

        Direction finalFace = transform.mapFace(nominalFace);
        TextureProjection projection = textureProjection(transform, nominalFace,
                x0, sourceY0, z0,
                x1, sourceY1, z1,
                x2, sourceY2, z2,
                x3, sourceY3, z3);
        float[] projectedUvs = projectedUvs(transform, projection,
                x0, sourceY0, z0,
                x1, sourceY1, z1,
                x2, sourceY2, z2,
                x3, sourceY3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3);
        quads.add(new CornerDebugQuad(nominalFace, finalFace,
                projection.sourceFace(), projection.finalFace(), projection.spriteFace(),
                debugVertex(transform, x0, y0, sourceY0, z0, u0, v0, projectedUvs[0], projectedUvs[1]),
                debugVertex(transform, x1, y1, sourceY1, z1, u1, v1, projectedUvs[2], projectedUvs[3]),
                debugVertex(transform, x2, y2, sourceY2, z2, u2, v2, projectedUvs[4], projectedUvs[5]),
                debugVertex(transform, x3, y3, sourceY3, z3, u3, v3, projectedUvs[6], projectedUvs[7])));
    }

    private static CornerDebugVertex debugVertex(FixedSlopeRotation transform,
                                                 float x,
                                                 float adjustedY,
                                                 float sourceY,
                                                 float z,
                                                 float authoredU,
                                                 float authoredV,
                                                 float projectedU,
                                                 float projectedV) {
        return new CornerDebugVertex(
                transform.positionX(x, adjustedY, z),
                transform.positionY(x, adjustedY, z),
                transform.positionZ(x, adjustedY, z),
                projectedU,
                projectedV,
                x,
                sourceY,
                z,
                authoredU,
                authoredV);
    }

    record CornerDebugQuad(Direction nominalFace,
                           Direction face,
                           Direction projectionFace,
                           Direction textureFace,
                           Direction spriteFace,
                           CornerDebugVertex v0,
                           CornerDebugVertex v1,
                           CornerDebugVertex v2,
                           CornerDebugVertex v3) {
        CornerDebugVertex vertex(int index) {
            return switch (index) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> throw new IllegalArgumentException("vertex index must be 0-3");
            };
        }
    }

    record CornerDebugVertex(float x,
                             float y,
                             float z,
                             float u,
                             float v,
                             float sourceX,
                             float sourceY,
                             float sourceZ,
                             float authoredU,
                             float authoredV) {
    }

    private static void emitSideStrips(CornerQuadSink sink, Direction face, float z, boolean upperVariant) {
        if (upperVariant) {
            emitSideQuad(sink, face, z, 0.0F, 0.5F, 1.0F, 1.0F, upperVariant);
            emitSideQuad(sink, face, z, 0.5F, 1.0F, 1.0F, MIN_SIDE_HEIGHT, upperVariant);
        } else {
            emitSideQuad(sink, face, z, 0.0F, 0.5F, 1.0F, MIN_SIDE_HEIGHT, upperVariant);
        }
    }

    private static void emitSideQuad(CornerQuadSink sink,
                                     Direction face,
                                     float z,
                                     float x0,
                                     float x1,
                                     float h0,
                                     float h1,
                                     boolean upperVariant) {
        float u0 = steepSideU(upperVariant, x0, h0, h1);
        float u1 = steepSideU(upperVariant, x1, h0, h1);
        if (face == Direction.NORTH) {
            sink.quad(face,
                    x0, 0.0F, z,
                    x0, h0, z,
                    x1, h1, z,
                    x1, 0.0F, z,
                    u0, 16.0F,
                    u0, (1.0F - h0) * 16.0F,
                    u1, (1.0F - h1) * 16.0F,
                    u1, 16.0F);
        } else {
            sink.quad(face,
                    x0, 0.0F, z,
                    x1, 0.0F, z,
                    x1, h1, z,
                    x0, h0, z,
                    u0, 16.0F,
                    u1, 16.0F,
                    u1, (1.0F - h1) * 16.0F,
                    u0, (1.0F - h0) * 16.0F);
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
        TextureProjection projection = textureProjection(transform, nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);

        float[] projectedUvs = projectedUvs(transform, projection,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3);

        transform.writePosition(emitter, 0, x0, ay0, z0);
        transform.writePosition(emitter, 1, x1, ay1, z1);
        transform.writePosition(emitter, 2, x2, ay2, z2);
        transform.writePosition(emitter, 3, x3, ay3, z3);
        emitter.uv(0, projectedUvs[0], projectedUvs[1]);
        emitter.uv(1, projectedUvs[2], projectedUvs[3]);
        emitter.uv(2, projectedUvs[4], projectedUvs[5]);
        emitter.uv(3, projectedUvs[6], projectedUvs[7]);
        emitter.color(-1, -1, -1, -1);
        if (nominalFace == Direction.UP) {
            writeQuadNormal(emitter, transform,
                    x0, ay0, z0,
                    x1, ay1, z1,
                    x2, ay2, z2,
                    x3, ay3, z3);
        }
        emitter.cullFace(cullFace == null ? null : transform.mapFace(cullFace));
        emitter.nominalFace(finalFace);
        Sprite sprite = sprites.sprite(projection.spriteFace());
        emitter.spriteBake(sprite, MutableQuadView.BAKE_ROTATE_NONE);
        emitter.emit();
    }

    private static float projectedU(FixedSlopeRotation transform,
                                    Direction projectionFace,
                                    Direction textureFace,
                                    float x,
                                    float y,
                                    float z,
                                    float authoredU,
                                    float authoredV) {
        return SlopeWorldUv.projectedU(transform, projectionFace, textureFace,
                x, y, z, authoredU, authoredV);
    }

    private static float projectedV(FixedSlopeRotation transform,
                                    Direction projectionFace,
                                    Direction textureFace,
                                    float x,
                                    float y,
                                    float z,
                                    float authoredU,
                                    float authoredV) {
        return SlopeWorldUv.projectedV(transform, projectionFace, textureFace,
                x, y, z, authoredU, authoredV);
    }

    /**
     * Iris derives a quad's POM bounds from the average of all four UVs. A
     * triangle is represented by a quad with a duplicated final position, but
     * duplicating its UV biases that average and makes the shader sample a
     * changing atlas region. The unused UV can instead complete the rectangle:
     * its geometric triangle remains degenerate while the three visible
     * vertices receive stable POM bounds.
     */
    private static float[] projectedUvs(FixedSlopeRotation transform,
                                        TextureProjection projection,
                                        float x0, float y0, float z0,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2,
                                        float x3, float y3, float z3,
                                        float u0, float v0,
                                        float u1, float v1,
                                        float u2, float v2,
                                        float u3, float v3) {
        float[] result = {
                projectedU(transform, projection.sourceFace(), projection.finalFace(), x0, y0, z0, u0, v0),
                projectedV(transform, projection.sourceFace(), projection.finalFace(), x0, y0, z0, u0, v0),
                projectedU(transform, projection.sourceFace(), projection.finalFace(), x1, y1, z1, u1, v1),
                projectedV(transform, projection.sourceFace(), projection.finalFace(), x1, y1, z1, u1, v1),
                projectedU(transform, projection.sourceFace(), projection.finalFace(), x2, y2, z2, u2, v2),
                projectedV(transform, projection.sourceFace(), projection.finalFace(), x2, y2, z2, u2, v2),
                projectedU(transform, projection.sourceFace(), projection.finalFace(), x3, y3, z3, u3, v3),
                projectedV(transform, projection.sourceFace(), projection.finalFace(), x3, y3, z3, u3, v3)
        };

        float firstAreaSquared = triangleAreaSquared(
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2);
        float secondAreaSquared = triangleAreaSquared(
                x0, y0, z0,
                x2, y2, z2,
                x3, y3, z3);
        int ghostVertex = -1;
        int first = 0;
        int second = 0;
        int third = 0;
        if (firstAreaSquared <= 0.000000000001F && secondAreaSquared > 0.000000000001F) {
            ghostVertex = 1;
            first = 0;
            second = 4;
            third = 6;
        } else if (secondAreaSquared <= 0.000000000001F && firstAreaSquared > 0.000000000001F) {
            ghostVertex = 3;
            first = 0;
            second = 2;
            third = 4;
        }
        if (ghostVertex >= 0) {
            float minU = Math.min(result[first], Math.min(result[second], result[third]));
            float maxU = Math.max(result[first], Math.max(result[second], result[third]));
            float minV = Math.min(result[first + 1], Math.min(result[second + 1], result[third + 1]));
            float maxV = Math.max(result[first + 1], Math.max(result[second + 1], result[third + 1]));
            int ghost = ghostVertex * 2;
            result[ghost] = 2.0F * (minU + maxU)
                    - result[first] - result[second] - result[third];
            result[ghost + 1] = 2.0F * (minV + maxV)
                    - result[first + 1] - result[second + 1] - result[third + 1];
        }
        return result;
    }

    private static float triangleAreaSquared(float x0, float y0, float z0,
                                             float x1, float y1, float z1,
                                             float x2, float y2, float z2) {
        float ax = x1 - x0;
        float ay = y1 - y0;
        float az = z1 - z0;
        float bx = x2 - x0;
        float by = y2 - y0;
        float bz = z2 - z0;
        float crossX = ay * bz - az * by;
        float crossY = az * bx - ax * bz;
        float crossZ = ax * by - ay * bx;
        return crossX * crossX + crossY * crossY + crossZ * crossZ;
    }

    private static void writeQuadNormal(QuadEmitter emitter,
                                        FixedSlopeRotation transform,
                                        float x0, float y0, float z0,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2,
                                        float x3, float y3, float z3) {
        SourceNormal normal = sourceNormal(
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);
        float transformedX = transform.directionX(normal.x(), normal.y(), normal.z());
        float transformedY = transform.directionY(normal.x(), normal.y(), normal.z());
        float transformedZ = transform.directionZ(normal.x(), normal.y(), normal.z());
        emitter.normal(0, transformedX, transformedY, transformedZ);
        emitter.normal(1, transformedX, transformedY, transformedZ);
        emitter.normal(2, transformedX, transformedY, transformedZ);
        emitter.normal(3, transformedX, transformedY, transformedZ);
    }

    private static TextureProjection textureProjection(FixedSlopeRotation transform,
                                                       Direction nominalFace,
                                                       float x0, float y0, float z0,
                                                       float x1, float y1, float z1,
                                                       float x2, float y2, float z2,
                                                       float x3, float y3, float z3) {
        Direction sourceFace = nominalFace;
        Direction spriteSourceFace = nominalFace;
        if (nominalFace == Direction.UP && sourceHeightVaries(y0, y1, y2, y3)) {
            SourceNormal normal = sourceNormal(
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2,
                    x3, y3, z3);
            sourceFace = Math.abs(normal.x()) >= Math.abs(normal.z())
                    ? Direction.EAST
                    : Direction.NORTH;
            spriteSourceFace = sourceFace;
        }
        return new TextureProjection(
                sourceFace,
                transform.mapFace(sourceFace),
                transform.mapFace(spriteSourceFace));
    }

    private static boolean sourceHeightVaries(float y0, float y1, float y2, float y3) {
        return Math.abs(y0 - y1) > 0.000001F
                || Math.abs(y0 - y2) > 0.000001F
                || Math.abs(y0 - y3) > 0.000001F;
    }

    private static SourceNormal sourceNormal(float x0, float y0, float z0,
                                             float x1, float y1, float z1,
                                             float x2, float y2, float z2,
                                             float x3, float y3, float z3) {
        float ax = x1 - x0;
        float ay = y1 - y0;
        float az = z1 - z0;
        float bx = x2 - x0;
        float by = y2 - y0;
        float bz = z2 - z0;
        float normalX = ay * bz - az * by;
        float normalY = az * bx - ax * bz;
        float normalZ = ax * by - ay * bx;
        float lengthSquared = normalX * normalX + (normalY * normalY + normalZ * normalZ);
        if (lengthSquared <= 0.000001F) {
            ax = x2 - x0;
            ay = y2 - y0;
            az = z2 - z0;
            bx = x3 - x0;
            by = y3 - y0;
            bz = z3 - z0;
            normalX = ay * bz - az * by;
            normalY = az * bx - ax * bz;
            normalZ = ax * by - ay * bx;
            lengthSquared = normalX * normalX + (normalY * normalY + normalZ * normalZ);
        }
        float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
        return new SourceNormal(
                normalX * inverseLength,
                normalY * inverseLength,
                normalZ * inverseLength);
    }

    private record TextureProjection(Direction sourceFace,
                                     Direction finalFace,
                                     Direction spriteFace) {
    }

    private record SourceNormal(float x, float y, float z) {
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

    private static boolean isOuterCorner(SlopeSteepBlock.SlopeShape shape) {
        return shape == SlopeSteepBlock.SlopeShape.OUTER_LEFT || shape == SlopeSteepBlock.SlopeShape.OUTER_RIGHT;
    }

    private static boolean isRightCorner(SlopeSteepBlock.SlopeShape shape) {
        return shape == SlopeSteepBlock.SlopeShape.INNER_RIGHT
                || shape == SlopeSteepBlock.SlopeShape.OUTER_RIGHT;
    }

    private static boolean isUpperVariant(BlockState state) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        return ErydonSlopeModelClassifier.familyForId(blockId)
                == ErydonSlopeModelClassifier.Family.STEEP_UPPER;
    }

    private static float heightAt(boolean upperVariant, float x) {
        if (upperVariant && x <= 0.5F) {
            return 1.0F;
        }
        return upperVariant ? Math.max(2.0F * (1.0F - x), 0.0F) : Math.max(1.0F - (2.0F * x), 0.0F);
    }

    private static float heightFromZ(boolean upperVariant, float z) {
        if (upperVariant) {
            return z >= 0.5F ? 1.0F : Math.max(2.0F * z, 0.0F);
        }
        return z <= 0.5F ? 0.0F : Math.max((2.0F * z) - 1.0F, 0.0F);
    }

    private static float cornerHeight(boolean upperVariant, boolean outer, float x, float z) {
        float xHeight = heightAt(upperVariant, x);
        float zHeight = heightFromZ(upperVariant, z);
        return outer ? Math.min(xHeight, zHeight) : Math.max(xHeight, zHeight);
    }

    private static float steepSideU(boolean upperVariant, float x, float h0, float h1) {
        return x * 16.0F;
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

    @FunctionalInterface
    private interface FaceSprites {
        Sprite sprite(Direction face);
    }

    private static int normalRotationForState(BlockState state) {
        int rotation = rotationForFacing(state.get(SlopeSteepBlock.FACING));
        SlopeSteepBlock.SlopeShape shape = state.get(SlopeSteepBlock.SHAPE);
        boolean top = state.get(SlopeSteepBlock.HALF) == BlockHalf.TOP;

        if (shape == SlopeSteepBlock.SlopeShape.STRAIGHT) {
            return rotation;
        }

        boolean right = isRightCorner(shape);
        if (top && !right) {
            return rotation - 90;
        }
        if (!top && right) {
            return rotation + 90;
        }
        return rotation;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (state == null || !(state.getBlock() instanceof SlopeSteepBlock)) {
            return Collections.emptyList();
        }

        return AxiomFallbackQuads.filterByFace(quadCache.computeIfAbsent(state, this::smoothQuads), face);
    }

    private List<BakedQuad> smoothQuads(BlockState state) {
        if (state.get(SlopeSteepBlock.SHAPE) == SlopeSteepBlock.SlopeShape.STRAIGHT) {
            return List.copyOf(smoothStraightQuads(state));
        }

        return List.copyOf(smoothCornerQuads(state));
    }

    private List<BakedQuad> smoothStraightQuads(BlockState state) {
        int xDegrees = state.get(SlopeSteepBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = rotationForFacing(state.get(SlopeSteepBlock.FACING));
        boolean upperVariant = isUpperVariant(state);
        List<BakedQuad> quads = new ArrayList<>();

        emitStraightGeometry((nominalFace,
                              x0, y0, z0,
                              x1, y1, z1,
                              x2, y2, z2,
                              x3, y3, z3,
                              u0, v0,
                              u1, v1,
                              u2, v2,
                              u3, v3) -> addBakedQuad(quads, particle, nominalFace, xDegrees, yDegrees,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant);
        return quads;
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

        TextureProjection projection = textureProjection(transform, nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);
        float[] projectedUvs = projectedUvs(transform, projection,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3);

        packVertex(data, 0, sprite, transform, x0, ay0, z0,
                projectedUvs[0], projectedUvs[1]);
        packVertex(data, 1, sprite, transform, x1, ay1, z1,
                projectedUvs[2], projectedUvs[3]);
        packVertex(data, 2, sprite, transform, x2, ay2, z2,
                projectedUvs[4], projectedUvs[5]);
        packVertex(data, 3, sprite, transform, x3, ay3, z3,
                projectedUvs[6], projectedUvs[7]);

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
        int xDegrees = state.get(SlopeSteepBlock.HALF) == BlockHalf.TOP ? 180 : 0;
        int yDegrees = normalRotationForState(state);
        boolean upperVariant = isUpperVariant(state);
        boolean outer = isOuterCorner(state.get(SlopeSteepBlock.SHAPE));
        List<BakedQuad> quads = new ArrayList<>();

        addBakedCornerHeightField(quads, particle, xDegrees, yDegrees, upperVariant, outer);
        return quads;
    }

    private static void addBakedCornerHeightField(List<BakedQuad> quads,
                                                  Sprite sprite,
                                                  int xDegrees,
                                                  int yDegrees,
                                                  boolean upperVariant,
                                                  boolean outer) {
        emitCornerGeometry((nominalFace,
                            x0, y0, z0,
                            x1, y1, z1,
                            x2, y2, z2,
                            x3, y3, z3,
                            u0, v0,
                            u1, v1,
                            u2, v2,
                            u3, v3) -> addBakedQuad(quads, sprite, nominalFace, xDegrees, yDegrees,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3,
                u0, v0,
                u1, v1,
                u2, v2,
                u3, v3), upperVariant, outer);
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



