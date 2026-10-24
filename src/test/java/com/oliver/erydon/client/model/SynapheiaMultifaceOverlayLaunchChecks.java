package com.oliver.erydon.client.model;

import com.oliver.erydon.block.LayerMultifaceBlock;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
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
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.oliver.erydon.client.model.SpiralStairCtmGeometry.Vertex;
import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.Surface;

/** Actual block states, production selection masks, and independent veneer/coverage oracles. */
final class SynapheiaMultifaceOverlayLaunchChecks {
    private static final float EPS = 0.0001F;
    private static int checked;

    private SynapheiaMultifaceOverlayLaunchChecks() { }

    static void run() {
        checked = 0;
        Block layer = fixture("striatus_trim_bronze_layer_multiface", true);
        Block other = fixture("glacium_trim_silver_layer_multiface", true);
        Block cube = fixture("striatus_trim_bronze_block", false);
        geometryMatrix(layer, other);
        faceMatrix(layer, cube);
        partialCubeEdges(layer, cube);
        partialSeams(layer, cube);
        unionShell(layer, cube);
        ruleIndependence(layer, cube);
        emittedInsetFaces(layer, cube);
        System.out.println("ERYDON_MULTIFACE_OVERLAY_OK: " + checked
                + " geometry/production-mask cases; 512 shared union shapes, all depths/faces/water states,"
                + " reciprocal and segmented cube edges, partial seam occlusion, inset/gap/material/motif controls;"
                + " each render cache reads at most 27 world cells once");
    }

    /** Replay the real overlay emitter, including its per-face selection cache and sprite baking. */
    private static void emittedInsetFaces(Block layer, Block cube) {
        BlockState state = state(layer, 2, bit(Direction.WEST) | bit(Direction.SOUTH), false);
        var rule = rule(Set.of(id(layer), id(cube)), true, "trim", "bronze");
        Map<BlockPos, BlockState> scene = new HashMap<>();
        scene.put(BlockPos.ORIGIN, state);
        scene.put(BlockPos.ORIGIN.up(), state);
        scene.put(BlockPos.ORIGIN.up().east(), state);
        try (var contents = new SpriteContents(new Identifier("erydon", "multiface_emission_probe"),
                new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            if (RendererAccess.INSTANCE.getRenderer() == null)
                RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
            List<Sprite> sprites = IntStream.range(0, 47).mapToObj(i -> (Sprite) new ProbeSprite(contents, i)).toList();
            var handles = new ReloadSpriteHandles.Builder<SynapheiaManifest.Rule, List<Sprite>>()
                    .bind(rule, rule.tiles(), () -> sprites).build();
            var plan = SynapheiaBlockPlan.compile(id(layer), List.of(rule));
            var snapshot = new SynapheiaService.Snapshot(SynapheiaService.current().generation(),
                    Map.of(id(layer), List.of(rule)),
                    Map.of(id(layer), plan), 0, 1, "fixture", handles);
            BakedModel wrapped = (BakedModel) java.lang.reflect.Proxy.newProxyInstance(
                    BakedModel.class.getClassLoader(), new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getParticleSprite")) return sprites.get(0);
                        throw new AssertionError("Unexpected wrapped model access: " + method);
                    });
            var renderer = new SynapheiaRepeatBakedModel(wrapped,
                    new ModelIdentifier(id(layer), ""), plan, snapshot);
            Class<?> capturedType = Class.forName(SynapheiaRepeatBakedModel.class.getName() + "$CapturedQuad");
            var captured = capturedType.getDeclaredConstructor(Direction.class, Direction.class, Direction.class,
                    RenderMaterial.class, int.class, int.class, List.class, SynapheiaManifest.Rule.class);
            captured.setAccessible(true);
            Class<?> sourceType = Class.forName(SynapheiaRepeatBakedModel.class.getName() + "$SourceOverlay");
            var overlay = sourceType.getDeclaredConstructor(capturedType, SynapheiaCellGeometry.Cell.class,
                    SynapheiaManifest.Rule.class, int.class);
            overlay.setAccessible(true);
            var emit = SynapheiaRepeatBakedModel.class.getDeclaredMethod("emitSourceOverlay", QuadEmitter.class,
                    SynapheiaNeighbourCache.class, Map.class, BlockState.class, BlockPos.class, sourceType);
            emit.setAccessible(true);
            var builder = RendererAccess.INSTANCE.getRenderer().meshBuilder();
            Map<Object, Object> selected = new LinkedHashMap<>();
            var cache = cache(scene);
            for (List<Vertex> vertices : List.of(rectangle(Direction.EAST, .25F, 0, .75F, 0, 1),
                    rectangle(Direction.EAST, 1, .75F, 1, 0, 1))) {
                Object quad = captured.newInstance(Direction.EAST, Direction.EAST, null, null, -1, 0, vertices, null);
                Object source = overlay.newInstance(quad, new SynapheiaCellGeometry.Cell(0, 0), rule, -1);
                require((int) emit.invoke(renderer, builder.getEmitter(), cache, selected, state,
                        BlockPos.ORIGIN, source) == 1, "Unexpected emitted multiface count");
            }
            List<Integer> tiles = new ArrayList<>();
            builder.build().forEach(quad -> {
                float meanU = 0;
                for (int v = 0; v < 4; v++) meanU += quad.u(v) / 4;
                tiles.add((int) Math.floor(meanU * 47));
            });
            require(tiles.equals(List.of(SynapheiaRepeatBakedModel.connectedTileIndex(64),
                            SynapheiaRepeatBakedModel.connectedTileIndex(0))),
                    "Real emission reused a boundary mask on an inset face: " + tiles);
            require(selected.size() == 2, "Different same-facing planes shared an overlay selection");
            require(SynapheiaRepeatBakedModel.connectionMask(cache(scene), state, Direction.EAST, rule) == 0,
                    "Counterfactual no-geometry path no longer reproduces the horizontal-band defect");
            checked += 3;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to exercise actual multiface emission", failure);
        }
    }

    private static final class ProbeSprite extends Sprite {
        ProbeSprite(SpriteContents contents, int index) {
            super(new Identifier("erydon", "multiface_emission_probe_atlas"), contents, 16 * 47, 16, index * 16, 0);
        }
    }

    private static void geometryMatrix(Block layer, Block other) {
        for (int depth = 1; depth <= 8; depth++) for (int mask = 0; mask < 64; mask++) {
            BlockState state = state(layer, depth, mask, false);
            List<Surface> surfaces = SynapheiaMultifaceConnections.surfaces(state);
            require(surfaces == SynapheiaMultifaceConnections.surfaces(state(other, depth, mask, true)),
                    "Geometry cache must be independent of material/water: " + depth + "/" + mask);
            double actualArea = 0;
            for (Surface surface : surfaces) {
                var a = surface.points[0]; var b = surface.points[1]; var d = surface.points[3];
                var cross = b.minus(a).cross(d.minus(a));
                actualArea += Math.sqrt(cross.dot(cross));
                float x = 0, y = 0, z = 0;
                for (var p : surface.points) { x += p.x() / 4; y += p.y() / 4; z += p.z() / 4; }
                Direction n = surface.face;
                require(occupied(mask, depth, x - n.getOffsetX() * EPS * 4,
                                y - n.getOffsetY() * EPS * 4, z - n.getOffsetZ() * EPS * 4),
                        "Union surface has no solid interior: " + depth + "/" + mask);
                require(!occupied(mask, depth, x + n.getOffsetX() * EPS * 4,
                                y + n.getOffsetY() * EPS * 4, z + n.getOffsetZ() * EPS * 4),
                        "Union contains a hidden internal face: " + depth + "/" + mask);
            }
            // Independently count exposed unit-voxel faces on the authored eighth-block grid.
            int expectedFaces = 0;
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) {
                if (!occupied(mask, depth, (x + .5F) / 8, (y + .5F) / 8, (z + .5F) / 8)) continue;
                for (Direction n : Direction.values()) if (!occupied(mask, depth,
                        (x + .5F + n.getOffsetX()) / 8, (y + .5F + n.getOffsetY()) / 8,
                        (z + .5F + n.getOffsetZ()) / 8)) expectedFaces++;
            }
            require(Math.abs(actualArea - expectedFaces / 64.0) < EPS,
                    "Union area/overlap differs from voxel oracle: " + depth + "/" + mask);
            require(mask != 0 || surfaces.isEmpty(), "Raycast targeting post leaked into empty geometry");
            checked++;
        }
    }

    private static void faceMatrix(Block layer, Block cube) {
        var rule = rule(Set.of(id(layer), id(cube)), true, "trim", "bronze");
        for (int depth = 1; depth <= 8; depth++) for (Direction covered : Direction.values()) {
            Direction face = covered.getOpposite();
            float plane = covered.getDirection() == Direction.AxisDirection.NEGATIVE ? depth / 8.0F : 1 - depth / 8.0F;
            List<Vertex> source = rectangle(face, plane, 0, 1, 0, 1);
            for (boolean water : new boolean[]{false, true}) {
                BlockState state = state(layer, depth, bit(covered), water);
                Map<BlockPos, BlockState> scene = plane(state, face);
                require(mask(scene, state, face, rule, source) == 255, "Continuous inset veneer restarted: " + state + "/" + face);
                var cache = cache(scene);
                var pieces = SynapheiaMultifaceConnections.fragments(cache, state, face, rule, source);
                require(pieces != null && pieces.size() == 1 && pieces.get(0) == source,
                        "Continuous single veneer was unnecessarily split");
                checked++;
            }
            // Change the depth/covered face of the upper/right tangent neighbour only.
            var offset = SynapheiaRepeatBakedModel.tangentOffset(face, 0);
            BlockState state = state(layer, depth, bit(covered), false);
            Map<BlockPos, BlockState> scene = new HashMap<>();
            scene.put(BlockPos.ORIGIN, state);
            scene.put(pos(offset), state(layer, depth == 8 ? 1 : depth + 1, bit(covered), false));
            require(mask(scene, state, face, rule, source) == 0, "Different inset planes falsely connected");
            scene.put(pos(offset), state(layer, depth, bit(covered.getOpposite()), false));
            require(mask(scene, state, face, rule, source) == (depth == 8 ? 1 : 0),
                    "Different covered faces falsely connected (except genuine depth-eight cubes)");
            scene.put(pos(offset), Blocks.DIRT.getDefaultState());
            require(mask(scene, state, face, rule, source) == 0, "Wrong material connected");
            scene.remove(pos(offset));
            require(mask(scene, state, face, rule, source) == 0, "Gap connected");
            checked += 4;
        }
    }

    private static void partialCubeEdges(Block layer, Block cube) {
        BlockState solid = cube.getDefaultState();
        var rule = rule(Set.of(id(layer), id(cube)), true, "trim", "bronze");
        for (Direction face : Direction.values()) for (int depth = 1; depth <= 8; depth++) {
            var offset = SynapheiaRepeatBakedModel.tangentOffset(face, 0);
            Direction.Axis span = remainingAxis(face.getAxis(), axis(offset));
            BlockState veneer = state(layer, depth, bit(negative(span)), false);
            List<Vertex> source = rectangle(face, boundary(face), 0, 1, 0, 1);
            Map<BlockPos, BlockState> scene = new HashMap<>();
            scene.put(BlockPos.ORIGIN, solid); scene.put(pos(offset), veneer);
            var renderCache = cache(scene);
            var pieces = SynapheiaMultifaceConnections.fragments(renderCache, solid, face, rule, source);
            require(pieces != null && pieces.size() == (depth == 8 ? 1 : 2), "Partial cube edge was not segmented");
            require(mask(scene, solid, face, rule, source) == (depth == 8 ? 1 : 0),
                    "An overlap alone erased the cube's whole edge");
            double area = 0;
            for (List<Vertex> piece : pieces) {
                int expected = center(piece, span) < depth / 8.0F ? 1 : 0;
                require(mask(renderCache, solid, face, rule, piece) == expected,
                        "Segmented cube edge lost its covered/exposed distinction: " + face + "/" + depth);
                area += area(piece); attributes(piece, face);
            }
            require(Math.abs(area - 1) < EPS, "Fragmented cube changed area");
            // The layer's shorter end edge is wholly covered by the neighbouring cube.
            List<Vertex> reverseSource = endFace(face, depth, span);
            Map<BlockPos, BlockState> reverse = new HashMap<>();
            reverse.put(BlockPos.ORIGIN, veneer); reverse.put(pos(offset).multiply(-1), solid);
            require(mask(reverse, veneer, face, rule, reverseSource) == 16, "Cube/layer connection is not reciprocal");
            checked += 3;
        }
    }

    private static void partialSeams(Block layer, Block cube) {
        BlockState solid = cube.getDefaultState();
        var rule = rule(Set.of(id(layer), id(cube)), true, "trim", "bronze");
        for (Direction face : Direction.values()) for (int depth = 1; depth <= 8; depth++) {
            var offset = SynapheiaRepeatBakedModel.tangentOffset(face, 0);
            Direction.Axis span = remainingAxis(face.getAxis(), axis(offset));
            Map<BlockPos, BlockState> scene = new HashMap<>();
            scene.put(BlockPos.ORIGIN, solid); scene.put(pos(offset), solid);
            BlockPos seam = pos(offset).offset(face);
            scene.put(seam, state(layer, depth, bit(negative(span)), false));
            List<Vertex> source = rectangle(face, boundary(face), 0, 1, 0, 1);
            var renderCache = cache(scene);
            var pieces = SynapheiaMultifaceConnections.fragments(renderCache, solid, face, rule, source);
            require(pieces != null && pieces.size() == (depth == 8 ? 1 : 2), "Partial front occluder did not cut the join");
            for (List<Vertex> piece : pieces) {
                int expected = center(piece, span) < depth / 8.0F ? 0 : 1;
                require(mask(renderCache, solid, face, rule, piece) == expected,
                        "Partial front cell hid an uncovered part of the join: " + face + "/" + depth);
            }
            require(mask(scene, solid, face, rule(Set.of(id(layer), id(cube)), false, "trim", "bronze"), source) == 1,
                    "innerSeams=false unexpectedly occluded the join");
            if (depth < 8) {
                // Same rule and cell, but this veneer is on the far side of that front cell.
                scene.put(seam, state(layer, depth, bit(face), false));
                require(mask(scene, solid, face, rule, source) == 1, "Non-touching partial front cell hid the join");
            }
            checked += 3;
        }
    }

    private static void unionShell(Block layer, Block cube) {
        var rule = rule(Set.of(id(layer), id(cube)), true, "trim", "bronze");
        BlockState corner = state(layer, 2, bit(Direction.NORTH) | bit(Direction.WEST), false);
        List<Vertex> source = rectangle(Direction.EAST, .25F, 0, 1, 0, 1);
        var pieces = SynapheiaMultifaceConnections.fragments(cache(Map.of(BlockPos.ORIGIN, corner)),
                corner, Direction.EAST, rule, source);
        require(pieces != null && Math.abs(pieces.stream().mapToDouble(SynapheiaMultifaceOverlayLaunchChecks::area).sum() - .75) < EPS,
                "Overlapping veneers retained an internal overlay plane");
        for (List<Vertex> piece : pieces) require(center(piece, Direction.Axis.Z) > .25F, "Internal corner surface survived");
        BlockState empty = state(layer, 2, 0, false);
        require(SynapheiaMultifaceConnections.fragments(cache(Map.of(BlockPos.ORIGIN, empty)), empty,
                Direction.EAST, rule, source).isEmpty(), "Empty multiface targeting post emitted an overlay");
        List<Vertex> cubeFace = rectangle(Direction.NORTH, 0, 0, 1, 0, 1);
        require(SynapheiaMultifaceConnections.fragments(cache(Map.of(BlockPos.ORIGIN, cube.getDefaultState())),
                cube.getDefaultState(), Direction.NORTH, rule, cubeFace) == null, "Ordinary cube took the fragment path");
        checked += 3;
    }

    private static void ruleIndependence(Block original, Block cube) {
        List<Surface> shared = SynapheiaMultifaceConnections.surfaces(state(original, 4, bit(Direction.WEST), false));
        for (String motif : List.of("trim", "guilloche", "quatrefoil", "rose")) for (String metal : List.of("bronze", "silver")) {
            Block layer = fixture("striatus_" + motif + "_" + metal + "_layer_multiface", true);
            BlockState state = state(layer, 4, bit(Direction.WEST), true);
            require(SynapheiaMultifaceConnections.surfaces(state) == shared, "Motif/metal duplicated cached geometry");
            var rule = rule(Set.of(id(layer), id(cube)), true, motif, metal);
            List<Vertex> source = rectangle(Direction.EAST, .5F, 0, 1, 0, 1);
            require(mask(plane(state, Direction.EAST), state, Direction.EAST, rule, source) == 255,
                    "Motif/metal changed physical continuity: " + motif + "/" + metal);
            var offset = SynapheiaRepeatBakedModel.tangentOffset(Direction.EAST, 0);
            var scene = new HashMap<BlockPos, BlockState>(); scene.put(BlockPos.ORIGIN, state);
            scene.put(pos(offset), state(fixture("glacium_trim_silver_layer_multiface", true), 4, bit(Direction.WEST), false));
            require(mask(scene, state, Direction.EAST, rule, source) == 0, "Unlisted material/motif borrowed a connection");
            checked += 3;
        }
    }

    private static int mask(Map<BlockPos, BlockState> scene, BlockState state, Direction face,
                            SynapheiaManifest.Rule rule, List<Vertex> source) {
        return mask(cache(scene), state, face, rule, source);
    }

    private static int mask(SynapheiaNeighbourCache cache, BlockState state, Direction face,
                            SynapheiaManifest.Rule rule, List<Vertex> source) {
        return SynapheiaRepeatBakedModel.surfaceConnectionMask(cache, state, face, rule,
                SynapheiaSlopeConnections.surface(source), (unexpected, family) -> {
                    throw new AssertionError("Multiface proof must not load a client model: " + unexpected);
                });
    }

    private static SynapheiaNeighbourCache cache(Map<BlockPos, BlockState> scene) {
        Set<BlockPos> reads = new HashSet<>();
        return new SynapheiaNeighbourCache(pos -> {
            require(reads.add(pos.toImmutable()), "World cell read twice");
            require(reads.size() <= 27, "Multiface lookup exceeded its 3x3x3 budget");
            return scene.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }, BlockPos.ORIGIN);
    }

    private static Map<BlockPos, BlockState> plane(BlockState state, Direction face) {
        Map<BlockPos, BlockState> scene = new HashMap<>();
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) scene.put(switch (face.getAxis()) {
            case X -> new BlockPos(0, a, b); case Y -> new BlockPos(a, 0, b); case Z -> new BlockPos(a, b, 0);
        }, state);
        return scene;
    }

    private static List<Vertex> rectangle(Direction face, float plane, float minS, float maxS, float minT, float maxT) {
        return List.of(vertex(face, plane, minS, maxT), vertex(face, plane, minS, minT),
                vertex(face, plane, maxS, minT), vertex(face, plane, maxS, maxT));
    }

    private static List<Vertex> endFace(Direction face, int depth, Direction.Axis span) {
        boolean spanS = face.getAxis() == Direction.Axis.X ? span == Direction.Axis.Z : span == Direction.Axis.X;
        return rectangle(face, boundary(face), 0, spanS ? depth / 8.0F : 1, 0, spanS ? 1 : depth / 8.0F);
    }

    private static Vertex vertex(Direction face, float plane, float s, float t) {
        float x = face.getAxis() == Direction.Axis.X ? plane : s;
        float y = face.getAxis() == Direction.Axis.Y ? plane : t;
        float z = face.getAxis() == Direction.Axis.X ? s : face.getAxis() == Direction.Axis.Y ? t : plane;
        return new Vertex(x, y, z, 0xFF000033 | Math.round(s * 255) << 16 | Math.round(t * 255) << 8,
                Math.round(s * 240) | Math.round(t * 240) << 16, true,
                face.getOffsetX(), face.getOffsetY(), face.getOffsetZ(), 2 * s + 1, 3 * t - 2);
    }

    private static void attributes(List<Vertex> piece, Direction face) {
        for (Vertex vertex : piece) {
            float s = SpiralStairCtmGeometry.textureS(face, vertex.x(), vertex.y(), vertex.z());
            float t = SpiralStairCtmGeometry.textureT(face, vertex.x(), vertex.y(), vertex.z());
            require(Math.abs(vertex.sourceU() - (2 * s + 1)) < EPS && Math.abs(vertex.sourceV() - (3 * t - 2)) < EPS,
                    "Fragment attributes/UV phase were stretched");
            require(Math.abs(((vertex.color() >>> 16) & 255) - Math.round(s * 255)) <= 1
                            && Math.abs(((vertex.color() >>> 8) & 255) - Math.round(t * 255)) <= 1,
                    "Fragment color was not interpolated");
            require(Math.abs((vertex.lightmap() & 65535) - Math.round(s * 240)) <= 1
                            && Math.abs(((vertex.lightmap() >>> 16) & 65535) - Math.round(t * 240)) <= 1,
                    "Fragment lightmap was not interpolated");
            require(vertex.hasNormal() && vertex.normalX() == face.getOffsetX()
                    && vertex.normalY() == face.getOffsetY() && vertex.normalZ() == face.getOffsetZ(), "Fragment lost its normal");
        }
    }

    private static SynapheiaManifest.Rule rule(Set<Identifier> blocks, boolean seams, String motif, String metal) {
        return new SynapheiaManifest.Rule(new Identifier("erydon", "multiface_probe_" + motif + "_" + metal), "fixture",
                SynapheiaManifest.Method.OVERLAY_CTM, IntStream.range(0, 47).mapToObj(i ->
                new Identifier("minecraft", "optifine/ctm/overlay/" + motif + "/" + metal + "/" + i)).toList(),
                Set.of(Direction.values()), blocks, Set.of(), SynapheiaManifest.OverlayShape.SOURCE,
                SynapheiaManifest.OverlayConnection.RULE, seams, 20);
    }

    private static Block fixture(String path, boolean multiface) {
        Identifier id = new Identifier("erydon", path); Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) block = Registry.register(Registries.BLOCK, id, multiface
                ? new LayerMultifaceBlock(Block.Settings.copy(Blocks.STONE)) : new Block(Block.Settings.copy(Blocks.STONE)));
        require(multiface == (block instanceof LayerMultifaceBlock), "Wrong fixture class: " + id);
        block.getStateManager().getStates().forEach(BlockState::initShapeCache);
        return block;
    }

    private static BlockState state(Block block, int depth, int mask, boolean water) {
        BlockState state = block.getDefaultState().with(LayerMultifaceBlock.LAYERS, depth).with(LayerMultifaceBlock.WATERLOGGED, water);
        for (Direction face : Direction.values()) state = state.with(property(face), (mask & bit(face)) != 0);
        return state;
    }

    private static BooleanProperty property(Direction face) { return switch (face) { case NORTH -> LayerMultifaceBlock.NORTH; case SOUTH -> LayerMultifaceBlock.SOUTH; case EAST -> LayerMultifaceBlock.EAST; case WEST -> LayerMultifaceBlock.WEST; case UP -> LayerMultifaceBlock.UP; case DOWN -> LayerMultifaceBlock.DOWN; }; }
    private static int bit(Direction face) { return switch (face) { case NORTH -> 1; case SOUTH -> 2; case EAST -> 4; case WEST -> 8; case UP -> 16; case DOWN -> 32; }; }
    // Slabs include their boundary: opposite half-slabs meet at 0.5 without an air plane.
    private static boolean occupied(int mask, int depth, float x, float y, float z) { float d = depth / 8.0F; return x >= 0 && y >= 0 && z >= 0 && x <= 1 && y <= 1 && z <= 1 && (((mask & 1) != 0 && z <= d) || ((mask & 2) != 0 && z >= 1 - d) || ((mask & 4) != 0 && x >= 1 - d) || ((mask & 8) != 0 && x <= d) || ((mask & 16) != 0 && y >= 1 - d) || ((mask & 32) != 0 && y <= d)); }
    private static Identifier id(Block block) { return Registries.BLOCK.getId(block); }
    private static BlockPos pos(SynapheiaRepeatBakedModel.Offset o) { return new BlockPos(o.x(), o.y(), o.z()); }
    private static Direction.Axis axis(SynapheiaRepeatBakedModel.Offset o) { return o.x() != 0 ? Direction.Axis.X : o.y() != 0 ? Direction.Axis.Y : Direction.Axis.Z; }
    private static Direction.Axis remainingAxis(Direction.Axis a, Direction.Axis b) { for (Direction.Axis c : Direction.Axis.values()) if (c != a && c != b) return c; throw new AssertionError(); }
    private static Direction negative(Direction.Axis axis) { return switch (axis) { case X -> Direction.WEST; case Y -> Direction.DOWN; case Z -> Direction.NORTH; }; }
    private static float boundary(Direction face) { return face.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0; }
    private static float center(List<Vertex> vertices, Direction.Axis axis) { float sum = 0; for (Vertex v : vertices) sum += switch (axis) { case X -> v.x(); case Y -> v.y(); case Z -> v.z(); }; return sum / vertices.size(); }
    private static double area(List<Vertex> v) { var a = new org.joml.Vector3f(v.get(1).x() - v.get(0).x(), v.get(1).y() - v.get(0).y(), v.get(1).z() - v.get(0).z()); var b = new org.joml.Vector3f(v.get(3).x() - v.get(0).x(), v.get(3).y() - v.get(0).y(), v.get(3).z() - v.get(0).z()); return a.cross(b).length(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
