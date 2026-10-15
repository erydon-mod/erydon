package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SlopeBlock;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

/** Exercises actual overlay selection and its skipped work without a window or an atlas. */
final class SynapheiaOverlaySelectionLaunchChecks {
    private static final Identifier TRIM = new Identifier("erydon", "glacium_trim_bronze_slope");
    private static final Identifier MOTIF = new Identifier("erydon", "glacium_guilloche_bronze_slope");

    private SynapheiaOverlaySelectionLaunchChecks() { }

    static void run() {
        try {
            if (RendererAccess.INSTANCE.getRenderer() == null)
                RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
            BlockState trimState = fixture(TRIM);
            BlockState motifState = fixture(MOTIF);
            Method select = method("selectOverlay", SynapheiaNeighbourCache.class, Map.class,
                    BlockState.class, Direction.class, SynapheiaManifest.Rule.class, List.class);
            Method physical = method("physicalTrimSurface", SynapheiaNeighbourCache.class,
                    BlockState.class, Direction.class, SynapheiaManifest.Rule.class, List.class);
            Field current = SynapheiaService.class.getDeclaredField("current");
            current.setAccessible(true);
            Object previous = current.get(null);
            Field shapeField = SynapheiaSlopeConnections.class.getDeclaredField("SHAPES");
            shapeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var shapes = (AtomicReferenceArray<List<SynapheiaSlopeConnections.Surface>>) shapeField.get(null);
            int shapeIndex = shapeIndex(trimState);
            var previousShape = shapes.get(shapeIndex);
            int checked = 0;
            int bypassed = 0;
            try (var contents = new SpriteContents(new Identifier("erydon", "trim_selection_probe"),
                    new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
                Sprite sprite = new TestSprite(contents);
                BakedModel wrapped = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                        new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                            if (method.getName().equals("getParticleSprite")) return sprite;
                            throw new AssertionError("Unexpected wrapped-model access: " + method);
                        });
                for (Direction face : Direction.values()) {
                    var whole = SynapheiaSlopeConnections.cubeFace(face);
                    shapes.set(shapeIndex, List.of(whole));
                    for (int variant = 0; variant < 4; variant++) {
                        Identifier id = variant == 1 ? MOTIF : TRIM;
                        BlockState state = variant == 1 ? motifState : trimState;
                        var rule = rule(id, variant == 2 ? "replacement" : "trim");
                        var plan = SynapheiaBlockPlan.compile(id, List.of(rule));
                        var handles = new ReloadSpriteHandles.Builder<SynapheiaManifest.Rule, List<Sprite>>()
                                .bind(rule, rule.tiles(), () -> java.util.Collections.nCopies(47, sprite)).build();
                        var snapshot = new SynapheiaService.Snapshot(1L, Map.of(id, List.of(rule)),
                                Map.of(id, plan), 0, 1, "fixture", handles);
                        current.set(null, snapshot);
                        var renderer = new SynapheiaRepeatBakedModel(wrapped,
                                new net.minecraft.client.util.ModelIdentifier(id, ""), plan, snapshot);
                        // The fourth case is an inset face sharing the same light direction.
                        // It must not borrow the boundary's cached physical Trim target.
                        var source = vertices(whole, variant == 3 ? 0.25F : 0.0F);
                        var sourceSurface = SynapheiaSlopeConnections.surface(source);
                        var connectionSurface = SynapheiaSlopeConnections.wholeFace(state,
                                ErydonSlopeModelClassifier.Family.STANDARD, face, sourceSurface);
                        int[] oracleReads = {0};
                        var oracleCache = cache(state, oracleReads);
                        int expectedMask = SynapheiaRepeatBakedModel.surfaceConnectionMask(
                                oracleCache, state, face, rule, connectionSurface, SynapheiaSlopeConnections::surfaces);
                        Object expectedTrim = physical.invoke(renderer, oracleCache, state, face, rule, source);
                        if (expectedTrim != null) expectedMask = 255;

                        int[] reads = {0};
                        var cache = cache(state, reads);
                        Map<Object, Object> selected = new LinkedHashMap<>();
                        Object actual = select.invoke(renderer, cache, selected, state, face, rule, source);
                        require((int) value(actual, "connectionMask") == expectedMask,
                                "Overlay mask changed: " + face + "/" + variant);
                        require((int) value(actual, "tileIndex") == SynapheiaRepeatBakedModel.connectedTileIndex(expectedMask),
                                "Overlay tile changed: " + face + "/" + variant);
                        require(value(actual, "sprite") == sprite, "Selected sprite identity changed");
                        require(value(actual, "trimSurface") == expectedTrim, "Physical Trim target changed");
                        int firstReads = reads[0];
                        require(select.invoke(renderer, cache, selected, state, face, rule, source) == actual,
                                "Repeated overlay selection was not reused");
                        require(reads[0] == firstReads, "Repeated selection reread neighbours");
                        if (expectedTrim != null) {
                            require(reads[0] == 0 && oracleReads[0] > 0,
                                    "Physical Trim evaluated its discarded connection mask");
                            require((int) value(actual, "tileIndex") == 26, "Physical Trim must use interior tile26");
                            bypassed++;
                            // Another strip on the same boundary maps to the same whole face;
                            // an inset with the same normal must retain its own ordinary mask.
                            var strip = new ArrayList<>(source);
                            var first = source.get(0);
                            var second = source.get(1);
                            strip.set(0, new SpiralStairCtmGeometry.Vertex(
                                    (first.x() + second.x()) * .5F, (first.y() + second.y()) * .5F,
                                    (first.z() + second.z()) * .5F, -1, 0, false, 0, 0, 0));
                            require(select.invoke(renderer, cache, selected, state, face, rule, strip) == actual,
                                    "Boundary strips lost shared whole-face selection");
                            Object inset = select.invoke(renderer, cache, selected, state, face, rule,
                                    vertices(whole, .25F));
                            require(inset != actual && value(inset, "trimSurface") == null,
                                    "Grouped boundary selection leaked into an inset surface");
                        }
                        checked++;
                    }
                }
                cubeJoin(select, physical, current, shapes, shapeIndex, trimState, sprite, wrapped);
            } finally {
                shapes.set(shapeIndex, previousShape);
                current.set(null, previous);
            }
            require(checked == 24 && bypassed == 4, "Overlay selection matrix incomplete");
            System.out.println("ERYDON_OVERLAY_SELECTION_OK: " + checked
                    + " real selection cases; four physical Trim faces skip all neighbour reads;"
                    + " cube/ramp joins, motif/replacement/inset/vertical fallback and grouped-strip isolation preserved");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to exercise actual Synapheia overlay selection", failure);
        }
    }

    private static void cubeJoin(Method select, Method physical, Field current,
                                 AtomicReferenceArray<List<SynapheiaSlopeConnections.Surface>> shapes,
                                 int shapeIndex, BlockState slope, Sprite sprite, BakedModel wrapped)
            throws ReflectiveOperationException {
        Identifier id = new Identifier("erydon", "glacium_trim_bronze_block");
        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR)
            block = Registry.register(Registries.BLOCK, id, new Block(Block.Settings.copy(Blocks.STONE)));
        BlockState state = block.getDefaultState();
        state.initShapeCache();
        var base = rule(id, "trim");
        var rule = new SynapheiaManifest.Rule(base.resourceId(), base.sourcePack(), base.method(), base.tiles(),
                base.faces(), Set.of(id, TRIM), base.matchTiles(), base.overlayShape(),
                base.overlayConnection(), base.innerSeams(), base.priority());
        var plan = SynapheiaBlockPlan.compile(id, List.of(rule));
        var handles = new ReloadSpriteHandles.Builder<SynapheiaManifest.Rule, List<Sprite>>()
                .bind(rule, rule.tiles(), () -> java.util.Collections.nCopies(47, sprite)).build();
        var snapshot = new SynapheiaService.Snapshot(1L, Map.of(id, List.of(rule)),
                Map.of(id, plan), 0, 1, "fixture", handles);
        current.set(null, snapshot);
        var renderer = new SynapheiaRepeatBakedModel(wrapped,
                new net.minecraft.client.util.ModelIdentifier(id, ""), plan, snapshot);
        var side = new SynapheiaSlopeConnections.Surface(Direction.NORTH,
                new SynapheiaSlopeConnections.Point(0, 0, 0), new SynapheiaSlopeConnections.Point(1, 0, 0),
                new SynapheiaSlopeConnections.Point(0, 1, 0));
        shapes.set(shapeIndex, List.of(side));
        for (boolean joined : new boolean[]{false, true}) {
            var cache = new SynapheiaNeighbourCache(pos -> BlockPos.ORIGIN.equals(pos) ? state
                    : joined && pos.equals(BlockPos.ORIGIN.up()) ? slope : Blocks.AIR.getDefaultState(), BlockPos.ORIGIN);
            int expectedMask = SynapheiaRepeatBakedModel.connectionMask(cache, state, Direction.NORTH, rule);
            Object expectedTrim = physical.invoke(renderer, cache, state, Direction.NORTH, rule, null);
            require((expectedTrim != null) == joined, "Cube/ramp physical Trim fixture was not representative");
            if (expectedTrim != null) expectedMask = 255;
            Map<Object, Object> selected = new LinkedHashMap<>();
            Object actual = select.invoke(renderer, cache, selected, state, Direction.NORTH, rule, null);
            require((int) value(actual, "connectionMask") == expectedMask
                            && value(actual, "trimSurface") == expectedTrim,
                    "Cube/ramp overlay selection changed");
            require(select.invoke(renderer, cache, selected, state, Direction.NORTH, rule, null) == actual,
                    "Cube overlay selection was not reused");
        }
    }

    private static BlockState fixture(Identifier id) {
        Block block = Registries.BLOCK.get(id);
        if (!(block instanceof SlopeBlock)) {
            block = Registry.register(Registries.BLOCK, id,
                    new SlopeBlock(Block.Settings.copy(Blocks.STONE)));
            block.getStateManager().getStates().forEach(BlockState::initShapeCache);
        }
        return block.getDefaultState();
    }

    private static int shapeIndex(BlockState state) {
        return ((((ErydonSlopeModelClassifier.Family.STANDARD.ordinal() * 4
                + state.get(SlopeBlock.FACING).getHorizontal()) * 5
                + state.get(SlopeBlock.SHAPE).ordinal()) * 2 + state.get(SlopeBlock.HALF).ordinal()) * 2);
    }

    private static SynapheiaNeighbourCache cache(BlockState state, int[] reads) {
        return new SynapheiaNeighbourCache(pos -> {
            reads[0]++;
            return BlockPos.ORIGIN.equals(pos) ? state : Blocks.AIR.getDefaultState();
        }, BlockPos.ORIGIN);
    }

    private static List<SpiralStairCtmGeometry.Vertex> vertices(SynapheiaSlopeConnections.Surface surface,
                                                               float inset) {
        return java.util.Arrays.stream(surface.points).map(point -> new SpiralStairCtmGeometry.Vertex(
                point.x() - surface.face.getOffsetX() * inset,
                point.y() - surface.face.getOffsetY() * inset,
                point.z() - surface.face.getOffsetZ() * inset, -1, 0, false, 0, 0, 0)).toList();
    }

    private static SynapheiaManifest.Rule rule(Identifier block, String tileFamily) {
        var tiles = IntStream.range(0, 47).mapToObj(index -> new Identifier("minecraft",
                "optifine/ctm/overlay/" + tileFamily + "/bronze/" + index)).toList();
        return new SynapheiaManifest.Rule(new Identifier("erydon", "trim_selection_probe"), "fixture",
                SynapheiaManifest.Method.OVERLAY_CTM, tiles, Set.of(Direction.values()), Set.of(block), Set.of(),
                SynapheiaManifest.OverlayShape.SOURCE, SynapheiaManifest.OverlayConnection.RULE, false, 20);
    }

    private static Method method(String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = SynapheiaRepeatBakedModel.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object value(Object record, String name) throws ReflectiveOperationException {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) {
            super(new Identifier("erydon", "trim_selection_probe_atlas"), contents, 32, 32, 0, 0);
        }
    }
}
