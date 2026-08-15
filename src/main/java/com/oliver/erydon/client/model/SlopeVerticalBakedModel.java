package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
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
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class SlopeVerticalBakedModel implements BakedModel, FabricBakedModel {
    private static final String MODEL_PATH = "block/slope/vertical/";
    public static final String[] SHALLOW_SUFFIXES = {"_lh", "_rh"};
    private final BakedModel wrapped;
    private final Sprite particle;
    private final ModelTransformation transformation;

    public SlopeVerticalBakedModel(BakedModel wrapped) {
        this(wrapped, ErydonSlopeModelClassifier.Family.VERTICAL);
    }

    public SlopeVerticalBakedModel(BakedModel wrapped, ErydonSlopeModelClassifier.Family family) {
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
        ShapeChoice choice = shapeChoice(state);
        if (choice == null) {
            context.fallbackConsumer().accept(wrapped);
            return;
        }

        FixedSlopeRotation transform = FixedSlopeRotation.of(0, rotationForFacing(choice.facing));
        FaceSprites sprites = repeatCtmSprites(state, pos, particle);
        emitPrism(context, sprites, transform, choice.points);
    }

    @Override
    @SuppressWarnings("removal")
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        Point[] points = itemPoints(itemId);
        emitPrism(context, face -> particle, FixedSlopeRotation.of(0, 0), points);
    }

    private static void emitPrism(RenderContext context, FaceSprites sprites, FixedSlopeRotation transform, Point[] points) {
        QuadEmitter emitter = context.getEmitter();
        emitCap(emitter, sprites, transform, Direction.UP, points, 1.0F);
        emitCap(emitter, sprites, transform, Direction.DOWN, reverse(points), 0.0F);

        for (int i = 0; i < points.length; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % points.length];
            Direction face = sideFace(a, b);
            float uMax = Math.min(16.0F, (float) Math.hypot(b.x - a.x, b.z - a.z) * 16.0F);
            emitQuad(emitter, sprites, transform, face,
                    a.x, 0.0F, a.z,
                    b.x, 0.0F, b.z,
                    b.x, 1.0F, b.z,
                    a.x, 1.0F, a.z,
                    0.0F, 16.0F,
                    uMax, 16.0F,
                    uMax, 0.0F,
                    0.0F, 0.0F);
        }
    }

    private static void emitCap(QuadEmitter emitter,
                                FaceSprites sprites,
                                FixedSlopeRotation transform,
                                Direction face,
                                Point[] points,
                                float y) {
        Point first = points[0];
        for (int i = 1; i < points.length - 1; i++) {
            Point second = points[i];
            Point third = points[i + 1];
            emitQuad(emitter, sprites, transform, face,
                    first.x, y, first.z,
                    second.x, y, second.z,
                    third.x, y, third.z,
                    third.x, y, third.z,
                    first.x * 16.0F, first.z * 16.0F,
                    second.x * 16.0F, second.z * 16.0F,
                    third.x * 16.0F, third.z * 16.0F,
                    third.x * 16.0F, third.z * 16.0F);
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
        Direction finalFace = transform.mapFace(nominalFace);
        transform.writePosition(emitter, 0, x0, y0, z0);
        transform.writePosition(emitter, 1, x1, y1, z1);
        transform.writePosition(emitter, 2, x2, y2, z2);
        transform.writePosition(emitter, 3, x3, y3, z3);
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
        Direction cullFace = SlopeQuadUtil.boundaryCullFace(nominalFace,
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3);
        emitter.cullFace(cullFace == null ? null : transform.mapFace(cullFace));
        emitter.nominalFace(finalFace);
        emitter.spriteBake(sprites.sprite(finalFace), MutableQuadView.BAKE_ROTATE_NONE);
        emitter.emit();
    }

    private static ShapeChoice shapeChoice(BlockState state) {
        if (state.getBlock() instanceof SlopeVerticalBlock && state.contains(SlopeVerticalBlock.FACING)) {
            return new ShapeChoice(state.get(SlopeVerticalBlock.FACING), vertical());
        }

        if (state.getBlock() instanceof SlopeVerticalShallowBroadBlock
                && state.contains(SlopeVerticalShallowBroadBlock.FACING)
                && state.contains(SlopeVerticalShallowBroadBlock.HAND)) {
            Point[] points = state.get(SlopeVerticalShallowBroadBlock.HAND) == SlopeVerticalShallowBroadBlock.Handedness.LEFT
                    ? mirrorZ(broadRight()) : broadRight();
            return new ShapeChoice(state.get(SlopeVerticalShallowBroadBlock.FACING), points);
        }

        if (state.getBlock() instanceof SlopeVerticalShallowNarrowBlock
                && state.contains(SlopeVerticalShallowNarrowBlock.FACING)
                && state.contains(SlopeVerticalShallowNarrowBlock.HAND)) {
            Point[] points = state.get(SlopeVerticalShallowNarrowBlock.HAND) == SlopeVerticalShallowNarrowBlock.Handedness.LEFT
                    ? mirrorZ(narrowRight()) : narrowRight();
            return new ShapeChoice(state.get(SlopeVerticalShallowNarrowBlock.FACING), points);
        }

        return null;
    }

    private static Point[] itemPoints(Identifier itemId) {
        return switch (ErydonSlopeModelClassifier.familyForId(itemId)) {
            case VERTICAL_SHALLOW_BROAD -> broadRight();
            case VERTICAL_SHALLOW_NARROW -> narrowRight();
            default -> vertical();
        };
    }

    private static Point[] vertical() {
        return new Point[] {
                new Point(0.0F, 0.0F),
                new Point(0.0F, 1.0F),
                new Point(1.0F, 0.0F)
        };
    }

    private static Point[] broadRight() {
        return new Point[] {
                new Point(0.0F, 0.0F),
                new Point(0.0F, 1.0F),
                new Point(0.5F, 1.0F),
                new Point(1.0F, 0.0F)
        };
    }

    private static Point[] narrowRight() {
        return new Point[] {
                new Point(0.0F, 0.0F),
                new Point(0.0F, 1.0F),
                new Point(0.5F, 0.0F)
        };
    }

    private static Point[] mirrorZ(Point[] points) {
        Point[] mirrored = new Point[points.length];
        for (int i = 0; i < points.length; i++) {
            Point point = points[points.length - 1 - i];
            mirrored[i] = new Point(point.x, 1.0F - point.z);
        }
        return mirrored;
    }

    private static Point[] reverse(Point[] points) {
        Point[] reversed = new Point[points.length];
        for (int i = 0; i < points.length; i++) {
            reversed[i] = points[points.length - 1 - i];
        }
        return reversed;
    }

    private static Direction sideFace(Point a, Point b) {
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float nx = -dz;
        float nz = dx;
        if (Math.abs(nx) > Math.abs(nz)) {
            return nx > 0.0F ? Direction.EAST : Direction.WEST;
        }
        return nz > 0.0F ? Direction.SOUTH : Direction.NORTH;
    }

    private static int rotationForFacing(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private static FaceSprites repeatCtmSprites(BlockState state, BlockPos pos, Sprite fallback) {
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
        int tileX;
        int tileY;

        switch (face) {
            case DOWN -> {
                tileX = pos.getX();
                tileY = -pos.getZ() - 1;
            }
            case UP -> {
                tileX = pos.getX();
                tileY = pos.getZ();
            }
            case NORTH -> {
                tileX = -pos.getX() - 1;
                tileY = -pos.getY();
            }
            case SOUTH -> {
                tileX = pos.getX();
                tileY = -pos.getY();
            }
            case WEST -> {
                tileX = pos.getZ();
                tileY = -pos.getY();
            }
            case EAST -> {
                tileX = -pos.getZ() - 1;
                tileY = -pos.getY();
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

    private record Point(float x, float z) {
    }

    private record ShapeChoice(Direction facing, Point[] points) {
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        if (state == null) {
            return Collections.emptyList();
        }

        ShapeChoice choice = shapeChoice(state);
        if (choice == null) {
            return Collections.emptyList();
        }

        List<BakedQuad> quads = new ArrayList<>();
        addBakedPrism(quads, particle, rotationForFacing(choice.facing), choice.points);
        return AxiomFallbackQuads.filterByFace(quads, face);
    }

    private static void addBakedPrism(List<BakedQuad> quads, Sprite sprite, int yDegrees, Point[] points) {
        addBakedCap(quads, sprite, yDegrees, Direction.UP, points, 1.0F);
        addBakedCap(quads, sprite, yDegrees, Direction.DOWN, reverse(points), 0.0F);

        for (int i = 0; i < points.length; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % points.length];
            Direction face = sideFace(a, b);
            float uMax = Math.min(16.0F, (float) Math.hypot(b.x - a.x, b.z - a.z) * 16.0F);
            addBakedQuad(quads, sprite, yDegrees, face,
                    a.x, 0.0F, a.z,
                    b.x, 0.0F, b.z,
                    b.x, 1.0F, b.z,
                    a.x, 1.0F, a.z,
                    0.0F, 16.0F,
                    uMax, 16.0F,
                    uMax, 0.0F,
                    0.0F, 0.0F);
        }
    }

    private static void addBakedCap(List<BakedQuad> quads,
                                    Sprite sprite,
                                    int yDegrees,
                                    Direction face,
                                    Point[] points,
                                    float y) {
        Point first = points[0];
        for (int i = 1; i < points.length - 1; i++) {
            Point second = points[i];
            Point third = points[i + 1];
            addBakedQuad(quads, sprite, yDegrees, face,
                    first.x, y, first.z,
                    second.x, y, second.z,
                    third.x, y, third.z,
                    third.x, y, third.z,
                    first.x * 16.0F, first.z * 16.0F,
                    second.x * 16.0F, second.z * 16.0F,
                    third.x * 16.0F, third.z * 16.0F,
                    third.x * 16.0F, third.z * 16.0F);
        }
    }

    private static void addBakedQuad(List<BakedQuad> quads,
                                     Sprite sprite,
                                     int yDegrees,
                                     Direction nominalFace,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3,
                                     float u0, float v0,
                                     float u1, float v1,
                                     float u2, float v2,
                                     float u3, float v3) {
        int[] data = new int[32];
        FixedSlopeRotation transform = FixedSlopeRotation.of(0, yDegrees);
        Direction finalFace = transform.mapFace(nominalFace);

        packBakedVertex(data, 0, sprite, transform, x0, y0, z0,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x0, y0, z0, u0, v0),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x0, y0, z0, u0, v0));
        packBakedVertex(data, 1, sprite, transform, x1, y1, z1,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x1, y1, z1, u1, v1),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x1, y1, z1, u1, v1));
        packBakedVertex(data, 2, sprite, transform, x2, y2, z2,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x2, y2, z2, u2, v2),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x2, y2, z2, u2, v2));
        packBakedVertex(data, 3, sprite, transform, x3, y3, z3,
                SlopeWorldUv.projectedU(transform, nominalFace, finalFace, x3, y3, z3, u3, v3),
                SlopeWorldUv.projectedV(transform, nominalFace, finalFace, x3, y3, z3, u3, v3));

        quads.add(new BakedQuad(data, -1, finalFace, sprite, true));
    }

    private static void packBakedVertex(int[] data,
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

