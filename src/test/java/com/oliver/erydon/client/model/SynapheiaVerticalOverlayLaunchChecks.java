package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/** The actual vertical renderer and production overlay masks at a stacked cube fold. */
final class SynapheiaVerticalOverlayLaunchChecks {
    private SynapheiaVerticalOverlayLaunchChecks() { }

    static void run() {
        Block cubeBlock = fixture("striatus_trim_bronze_block", new Block(Block.Settings.copy(Blocks.STONE)));
        List<Block> blocks = List.of(
                fixture("striatus_trim_bronze_slope_vertical", new SlopeVerticalBlock(Block.Settings.copy(Blocks.STONE))),
                fixture("striatus_trim_bronze_slope_vertical_shallow_narrow", new SlopeVerticalShallowNarrowBlock(Block.Settings.copy(Blocks.STONE))),
                fixture("striatus_trim_bronze_slope_vertical_shallow_broad", new SlopeVerticalShallowBroadBlock(Block.Settings.copy(Blocks.STONE))));
        int checked = 0;
        try (var contents = new SpriteContents(new Identifier("erydon", "vertical_overlay_probe"),
                new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new TestSprite(contents);
            BakedModel wrapped = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                    new Class<?>[]{BakedModel.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getParticleSprite" -> sprite;
                        case "getTransformation" -> ModelTransformation.NONE;
                        case "getOverrides" -> ModelOverrideList.EMPTY;
                        case "getQuads" -> List.of();
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            var model = new SlopeVerticalBakedModel(wrapped);
            for (int family = 0; family < blocks.size(); family++) {
                Block block = blocks.get(family);
                var rule = rule(Registries.BLOCK.getId(block), Registries.BLOCK.getId(cubeBlock));
                for (BlockState state : block.getStateManager().getStates()) {
                    Direction facing = state.get(Properties.HORIZONTAL_FACING);
                    int degrees = switch (facing) {
                        case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
                    };
                    var rotation = FixedSlopeRotation.of(0, degrees);
                    boolean left = family == 1
                            ? state.get(SlopeVerticalShallowNarrowBlock.HAND) == SlopeVerticalShallowNarrowBlock.Handedness.LEFT
                            : family == 2 && state.get(SlopeVerticalShallowBroadBlock.HAND) == SlopeVerticalShallowBroadBlock.Handedness.LEFT;
                    var surfaces = model.getQuads(state, null, Random.create(0)).stream().map(quad -> {
                        int[] data = quad.getVertexData(); int stride = data.length / 4;
                        var points = new SynapheiaSlopeConnections.Point[4];
                        for (int i = 0; i < 4; i++) points[i] = new SynapheiaSlopeConnections.Point(
                                Float.intBitsToFloat(data[i * stride]), Float.intBitsToFloat(data[i * stride + 1]),
                                Float.intBitsToFloat(data[i * stride + 2]));
                        return new SynapheiaSlopeConnections.Surface(quad.getFace(), points);
                    }).toList();
                    var walls = surfaces.stream().filter(SynapheiaSlopeConnections.Surface::sloped).toList();
                    require(walls.size() == 1, "Expected one real full-height diagonal: " + state);
                    var wall = walls.get(0);
                    Direction neighbourDirection = family == 0 ? Direction.SOUTH : family == 1 ? Direction.WEST : Direction.EAST;
                    Direction cubeFace = family == 0 ? Direction.WEST : family == 1 ? Direction.SOUTH : Direction.NORTH;
                    Direction wallEdge = family == 0 ? Direction.WEST : family == 1 ? Direction.SOUTH : Direction.NORTH;
                    Direction cubeEdge = family == 0 ? Direction.NORTH : family == 1 ? Direction.EAST : Direction.WEST;
                    if (left) {
                        neighbourDirection = reflectZ(neighbourDirection); cubeFace = reflectZ(cubeFace);
                        wallEdge = reflectZ(wallEdge); cubeEdge = reflectZ(cubeEdge);
                    }
                    neighbourDirection = rotation.mapFace(neighbourDirection);
                    cubeFace = rotation.mapFace(cubeFace); wallEdge = rotation.mapFace(wallEdge); cubeEdge = rotation.mapFace(cubeEdge);
                    BlockPos offset = BlockPos.ORIGIN.offset(neighbourDirection);
                    BlockState cube = cubeBlock.getDefaultState();
                    Map<BlockPos, BlockState> scene = column(state, cube, offset);
                    int expected = connectedMask(wall.face, wallEdge);
                    check(scene, state, wall, surfaces, rule, expected, "stacked vertical fold " + state);
                    scene.remove(offset.up());
                    check(scene, state, wall, surfaces, rule, expected & ~corner(wall.face, wallEdge, Direction.UP),
                            "missing upper cube " + state);
                    scene.put(offset.up(), Blocks.DIRT.getDefaultState());
                    check(scene, state, wall, surfaces, rule, expected & ~corner(wall.face, wallEdge, Direction.UP),
                            "unmatched upper cube " + state);
                    scene.remove(offset); scene.remove(offset.down());
                    check(scene, state, wall, surfaces, rule, edge(wall.face, Direction.UP) | edge(wall.face, Direction.DOWN),
                            "no matching physical cube border " + state);
                    scene = column(cube, state, offset.multiply(-1));
                    var cubeSurface = SynapheiaSlopeConnections.cubeFace(cubeFace);
                    check(scene, cube, cubeSurface, surfaces, rule, connectedMask(cubeFace, cubeEdge),
                            "reciprocal cube fold " + state);
                    scene.remove(offset.multiply(-1).up());
                    check(scene, cube, cubeSurface, surfaces, rule,
                            connectedMask(cubeFace, cubeEdge) & ~corner(cubeFace, cubeEdge, Direction.UP),
                            "reciprocal missing upper slope " + state);
                    checked += 6;
                }
            }
        }
        require(checked == 240, "Vertical overlay matrix incomplete: " + checked);
        System.out.println("ERYDON_VERTICAL_OVERLAY_CORNERS_OK: " + checked
                + " production masks from actual baked vertical meshes; all three families, facings, hands and water states; reciprocal cube joins and missing/material corner controls");
    }

    private static void check(Map<BlockPos, BlockState> scene, BlockState source,
            SynapheiaSlopeConnections.Surface surface, List<SynapheiaSlopeConnections.Surface> slopes,
            SynapheiaManifest.Rule rule, int expected, String context) {
        Set<BlockPos> reads = new java.util.HashSet<>();
        var cache = new SynapheiaNeighbourCache(pos -> {
            require(reads.add(pos.toImmutable()), "Repeated world read: " + context);
            return scene.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }, BlockPos.ORIGIN);
        int actual = SynapheiaRepeatBakedModel.surfaceConnectionMask(cache, source, surface.face, rule, surface,
                (state, family) -> slopes);
        require(actual == expected, context + ": expected " + expected + ", got " + actual);
        require(reads.size() <= 27, "Unbounded neighbour cache: " + context);
    }

    private static Map<BlockPos, BlockState> column(BlockState source, BlockState neighbour, BlockPos offset) {
        Map<BlockPos, BlockState> scene = new HashMap<>();
        for (int y = -1; y <= 1; y++) {
            scene.put(new BlockPos(0, y, 0), source); scene.put(offset.add(0, y, 0), neighbour);
        }
        return scene;
    }

    private static SynapheiaManifest.Rule rule(Identifier slope, Identifier cube) {
        return new SynapheiaManifest.Rule(new Identifier("erydon", "vertical_overlay_probe"), "fixture",
                SynapheiaManifest.Method.OVERLAY_CTM, IntStream.range(0, 47).mapToObj(i ->
                new Identifier("minecraft", "optifine/ctm/overlay/trim/bronze/" + i)).toList(),
                Set.of(Direction.values()), Set.of(slope, cube), Set.of(), SynapheiaManifest.OverlayShape.SOURCE,
                SynapheiaManifest.OverlayConnection.RULE, true, 20);
    }

    private static Block fixture(String path, Block candidate) {
        Identifier id = new Identifier("erydon", path);
        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) block = Registry.register(Registries.BLOCK, id, candidate);
        require(block.getClass() == candidate.getClass(), "Unexpected fixture class: " + id);
        block.getStateManager().getStates().forEach(BlockState::initShapeCache);
        return block;
    }

    private static int connectedMask(Direction face, Direction side) {
        return edge(face, side) | edge(face, Direction.UP) | edge(face, Direction.DOWN)
                | corner(face, side, Direction.UP) | corner(face, side, Direction.DOWN);
    }

    private static int edge(Direction face, Direction target) {
        Direction vertical = Direction.UP;
        Direction horizontal = face.getDirection() == Direction.AxisDirection.NEGATIVE
                ? vertical.rotateClockwise(face.getAxis()) : vertical.rotateCounterclockwise(face.getAxis());
        Direction[] directions = {horizontal, Direction.DOWN, horizontal.getOpposite(), Direction.UP};
        for (int i = 0; i < 4; i++) if (directions[i] == target) return 1 << (2 * i);
        throw new AssertionError("Not a projected edge: " + face + "/" + target);
    }

    private static int corner(Direction face, Direction a, Direction b) {
        int pair = edge(face, a) | edge(face, b);
        for (int i = 0; i < 4; i++) if (pair == ((1 << (2 * i)) | (1 << (2 * ((i + 1) & 3))))) return 1 << (2 * i + 1);
        throw new AssertionError("Not adjacent edges");
    }

    private static Direction reflectZ(Direction face) { return face.getAxis() == Direction.Axis.Z ? face.getOpposite() : face; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "vertical_overlay_atlas"), contents, 32, 32, 0, 0); }
    }
}
