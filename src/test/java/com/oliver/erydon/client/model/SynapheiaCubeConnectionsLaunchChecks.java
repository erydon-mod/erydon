package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SlopeBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

/** Exercises the real cube mask after registry bootstrap, without client model access. */
public final class SynapheiaCubeConnectionsLaunchChecks {
    private static final BlockPos ORIGIN = new BlockPos(20, 64, -30);

    private SynapheiaCubeConnectionsLaunchChecks() { }

    public static void run() {
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        Identifier stoneId = Registries.BLOCK.getId(Blocks.STONE);
        List<Identifier> tiles = IntStream.range(0, 47)
                .mapToObj(index -> new Identifier("minecraft", "block/cube_connection_probe/" + index)).toList();
        int checked = 0;
        for (Direction face : Direction.values()) {
            Offset[] cardinals = classicDirections(face);
            for (int pattern = 0; pattern < 256; pattern++) {
                // A different permutation supplies every seam pattern once, independently
                // of the cardinal/diagonal placement order and the production cache.
                int seamPattern = (pattern * 73 + 151) & 255;
                for (boolean innerSeams : new boolean[]{false, true}) {
                    BlockState[] states = new BlockState[27];
                    Arrays.fill(states, air);
                    states[index(0, 0, 0)] = stone;
                    for (int direction = 0; direction < 4; direction++) {
                        Offset cardinal = cardinals[direction];
                        Offset diagonal = cardinal.plus(cardinals[(direction + 1) & 3]);
                        put(states, stone, cardinal, face, pattern, seamPattern, 2 * direction);
                        put(states, stone, diagonal, face, pattern, seamPattern, 2 * direction + 1);
                    }
                    var rule = new SynapheiaManifest.Rule(
                            new Identifier("minecraft", "cube_connection_probe"), "probe",
                            SynapheiaManifest.Method.OVERLAY_CTM, tiles, Set.of(Direction.values()),
                            Set.of(stoneId), Set.of(), SynapheiaManifest.OverlayShape.SOURCE,
                            SynapheiaManifest.OverlayConnection.RULE, innerSeams, 20);
                    int[] reads = new int[27];
                    SynapheiaNeighbourCache cache = new SynapheiaNeighbourCache(pos -> {
                        int slot = index(pos.getX() - ORIGIN.getX(), pos.getY() - ORIGIN.getY(),
                                pos.getZ() - ORIGIN.getZ());
                        require(++reads[slot] == 1, "Production mask read a cached cell more than once");
                        return states[slot];
                    }, ORIGIN);
                    int expected = classicMask(states, stone, cardinals, face, innerSeams);
                    int actual = SynapheiaRepeatBakedModel.connectionMask(cache, stone, face, rule);
                    String context = face + ", pattern=" + pattern + ", seamPattern=" + seamPattern
                            + ", innerSeams=" + innerSeams;
                    require(actual == expected, "Production cube mask differs from classic CTM: " + context
                            + ", expected=" + expected + ", actual=" + actual);
                    int firstReads = Arrays.stream(reads).sum();
                    require(firstReads <= 27, "Production cube mask exceeded its bounded neighbourhood");
                    require(SynapheiaRepeatBakedModel.connectionMask(cache, stone, face, rule) == expected,
                            "Cached production replay changed the cube mask: " + context);
                    require(Arrays.stream(reads).sum() == firstReads,
                            "Repeated production mask performed another block-state read: " + context);
                    checked++;
                }
            }
        }
        require(checked == 3072, "Cube mask matrix was incomplete");
        System.out.println("ERYDON_CUBE_CTM_PARITY_OK: " + checked
                + " production masks, all six faces, independent seam patterns; cached replays read each of at most 27 cells once");
        foldedMasks(tiles, stoneId);
    }

    /** Actual registry/state/material/visibility branches, with model geometry injected before client startup. */
    private static void foldedMasks(List<Identifier> tiles, Identifier stoneId) {
        Identifier slopeId = new Identifier("erydon", "glacium_slope");
        var block = Registries.BLOCK.get(slopeId);
        require(block instanceof SlopeBlock, "Missing real registered slope fixture");
        BlockState stone = Blocks.STONE.getDefaultState();
        int checked = 0;
        for (Direction facing : Direction.Type.HORIZONTAL) for (BlockHalf half : BlockHalf.values()) {
            BlockState slope = block.getDefaultState().with(SlopeBlock.FACING, facing)
                    .with(SlopeBlock.HALF, half).with(SlopeBlock.SHAPE, SlopeBlock.SlopeShape.STRAIGHT);
            int yDegrees = switch (facing) {
                case EAST -> 0; case SOUTH -> 90; case WEST -> 180; case NORTH -> 270;
                default -> throw new AssertionError("Non-horizontal slope facing");
            };
            FixedSlopeRotation rotation = FixedSlopeRotation.of(half == BlockHalf.TOP ? 180 : 0, yDegrees);
            // Canonical smoothStraightQuads hypotenuse, transformed exactly like the actual placed model.
            var ramp = new SynapheiaSlopeConnections.Surface(rotation.mapFace(Direction.UP),
                    point(rotation, 0, 1, 0), point(rotation, 0, 1, 1),
                    point(rotation, 1, 0, 1), point(rotation, 1, 0, 0));
            Direction highFace = rotation.mapFace(Direction.WEST);
            Direction lowFace = rotation.mapFace(Direction.EAST);
            int sign = half == BlockHalf.BOTTOM ? 1 : -1;
            Offset high = new Offset(0, sign, 0), low = new Offset(0, -sign, 0);
            int highBit = edgeBit(ramp.face, highFace), lowBit = edgeBit(ramp.face, lowFace);
            var rule = foldedRule(tiles, stoneId, slopeId, Set.of(Direction.values()),
                    SynapheiaManifest.OverlayConnection.RULE, true);
            BiFunction<BlockState, ErydonSlopeModelClassifier.Family,
                    List<SynapheiaSlopeConnections.Surface>> resolver = (state, family) -> {
                require(state == slope && family == ErydonSlopeModelClassifier.Family.STANDARD,
                        "Folded mask requested unexpected slope geometry");
                return List.of(ramp);
            };
            String context = facing + "/" + half;
            BlockState[] both = scene(slope);
            set(both, high, stone); set(both, low, stone);
            assertFolded(both, slope, ramp, rule, resolver, highBit | lowBit, context + " both ramp ends");

            BlockState[] highOnly = scene(slope);
            set(highOnly, high, stone);
            assertFolded(highOnly, slope, ramp, rule, resolver, highBit, context + " high end");
            BlockState[] lowOnly = scene(slope);
            set(lowOnly, low, stone);
            assertFolded(lowOnly, slope, ramp, rule, resolver, lowBit, context + " low end");

            BlockState[] highReverse = scene(stone);
            set(highReverse, low, slope);
            assertFolded(highReverse, stone, SynapheiaSlopeConnections.cubeFace(highFace), rule, resolver,
                    edgeBit(highFace, half == BlockHalf.BOTTOM ? Direction.DOWN : Direction.UP),
                    context + " reciprocal high cube");
            BlockState[] lowReverse = scene(stone);
            set(lowReverse, high, slope);
            assertFolded(lowReverse, stone, SynapheiaSlopeConnections.cubeFace(lowFace), rule, resolver,
                    edgeBit(lowFace, half == BlockHalf.BOTTOM ? Direction.UP : Direction.DOWN),
                    context + " reciprocal low cube");

            BlockState[] mismatched = both.clone();
            set(mismatched, high, Blocks.DIRT.getDefaultState());
            assertFolded(mismatched, slope, ramp, rule, resolver, lowBit, context + " unlisted material");
            var sameBlockOnly = foldedRule(tiles, stoneId, slopeId, Set.of(Direction.values()),
                    SynapheiaManifest.OverlayConnection.BLOCK, true);
            assertFolded(both, slope, ramp, sameBlockOnly, resolver, 0, context + " BLOCK material contract");
            assertFolded(highReverse, stone, SynapheiaSlopeConnections.cubeFace(highFace), sameBlockOnly,
                    resolver, 0, context + " reciprocal BLOCK material contract");

            // Isolate the actual vertical joining face. The adjacent cube's DOWN/UP
            // face must not accidentally make this visibility assertion pass.
            var actualFaceRule = foldedRule(tiles, stoneId, slopeId, Set.of(ramp.face, highFace),
                    SynapheiaManifest.OverlayConnection.RULE, true);
            assertFolded(highOnly, slope, ramp, actualFaceRule, resolver, highBit,
                    context + " actual joining face permitted");
            BlockState[] occluded = highOnly.clone();
            set(occluded, high.plus(Offset.of(highFace)), stone);
            assertFolded(occluded, slope, ramp, actualFaceRule, resolver, 0,
                    context + " actual " + highFace + " cube face occluded");
            var noInnerSeams = foldedRule(tiles, stoneId, slopeId, Set.of(ramp.face, highFace),
                    SynapheiaManifest.OverlayConnection.RULE, false);
            assertFolded(occluded, slope, ramp, noInnerSeams, resolver, highBit,
                    context + " innerSeams disabled");
            var projectionOnly = foldedRule(tiles, stoneId, slopeId, Set.of(ramp.face),
                    SynapheiaManifest.OverlayConnection.RULE, true);
            assertFolded(highOnly, slope, ramp, projectionOnly, resolver, 0,
                    context + " joining cube face excluded by rule.faces");
            var cubeFaceOnly = foldedRule(tiles, stoneId, slopeId, Set.of(highFace),
                    SynapheiaManifest.OverlayConnection.RULE, true);
            assertFolded(highReverse, stone, SynapheiaSlopeConnections.cubeFace(highFace), cubeFaceOnly,
                    resolver, 0, context + " joining ramp face excluded by rule.faces");

            // An actual slope neighbour may expose axis-aligned side quads too.
            // Parallel opposing quads touching the same plane are not a visible fold.
            BlockState[] opposite = scene(stone);
            set(opposite, Offset.of(highFace), slope);
            BiFunction<BlockState, ErydonSlopeModelClassifier.Family,
                    List<SynapheiaSlopeConnections.Surface>> oppositeResolver = (state, family) -> {
                require(state == slope && family == ErydonSlopeModelClassifier.Family.STANDARD,
                        "Opposite-face mask requested unexpected slope geometry");
                return List.of(SynapheiaSlopeConnections.cubeFace(highFace.getOpposite()));
            };
            assertFolded(opposite, stone, SynapheiaSlopeConnections.cubeFace(highFace), rule,
                    oppositeResolver, 0, context + " opposite parallel vertical faces");
            checked += 14;
        }
        require(checked == 112, "Folded production mask matrix was incomplete");
        System.out.println("ERYDON_FOLDED_CTM_MASKS_OK: " + checked
                + " real registered slope/cube masks, four facings and both halves; reciprocal edges, materials, rule faces, occlusion and bounded cache reads");
    }

    private static SynapheiaManifest.Rule foldedRule(List<Identifier> tiles, Identifier stoneId,
            Identifier slopeId, Set<Direction> faces, SynapheiaManifest.OverlayConnection connection,
            boolean innerSeams) {
        return new SynapheiaManifest.Rule(new Identifier("minecraft", "folded_connection_probe"), "probe",
                SynapheiaManifest.Method.OVERLAY_CTM, tiles, faces, Set.of(stoneId, slopeId), Set.of(),
                SynapheiaManifest.OverlayShape.SOURCE, connection, innerSeams, 20);
    }

    private static SynapheiaSlopeConnections.Point point(FixedSlopeRotation rotation, float x, float y, float z) {
        return new SynapheiaSlopeConnections.Point(rotation.positionX(x, y, z), rotation.positionY(x, y, z),
                rotation.positionZ(x, y, z));
    }

    private static BlockState[] scene(BlockState source) {
        BlockState[] states = new BlockState[27];
        Arrays.fill(states, Blocks.AIR.getDefaultState());
        states[index(0, 0, 0)] = source;
        return states;
    }

    private static void set(BlockState[] states, Offset offset, BlockState state) {
        states[index(offset.x, offset.y, offset.z)] = state;
    }

    private static void assertFolded(BlockState[] states, BlockState state,
            SynapheiaSlopeConnections.Surface source, SynapheiaManifest.Rule rule,
            BiFunction<BlockState, ErydonSlopeModelClassifier.Family,
                    List<SynapheiaSlopeConnections.Surface>> resolver, int expected, String context) {
        int[] reads = new int[27];
        SynapheiaNeighbourCache cache = new SynapheiaNeighbourCache(pos -> {
            int slot = index(pos.getX() - ORIGIN.getX(), pos.getY() - ORIGIN.getY(),
                    pos.getZ() - ORIGIN.getZ());
            require(++reads[slot] == 1, "Folded mask read a cached cell more than once: " + context);
            return states[slot];
        }, ORIGIN);
        int actual = SynapheiaRepeatBakedModel.surfaceConnectionMask(cache, state, source.face, rule, source, resolver);
        require(actual == expected, "Production folded mask incorrect: " + context
                + ", expected=" + expected + ", actual=" + actual);
        int firstReads = Arrays.stream(reads).sum();
        require(firstReads <= 27, "Folded mask exceeded its bounded neighbourhood: " + context);
        require(SynapheiaRepeatBakedModel.surfaceConnectionMask(cache, state, source.face, rule, source, resolver)
                == expected, "Folded cached replay changed mask: " + context);
        require(Arrays.stream(reads).sum() == firstReads, "Folded cached replay reread a block state: " + context);
    }

    /** Expected edge bits come from the independent classic basis, never the production mask helper. */
    private static int edgeBit(Direction projection, Direction edgeDirection) {
        Offset edge = Offset.of(edgeDirection);
        Offset[] directions = classicDirections(projection);
        for (int i = 0; i < directions.length; i++) if (directions[i].equals(edge)) return 1 << (2 * i);
        throw new AssertionError("Edge is not tangent to projection: " + projection + "/" + edgeDirection);
    }

    private static void put(BlockState[] states, BlockState stone, Offset target, Direction face,
                            int pattern, int seamPattern, int bit) {
        if ((pattern & (1 << bit)) != 0) states[index(target.x, target.y, target.z)] = stone;
        if ((seamPattern & (1 << bit)) != 0) {
            states[index(target.x + face.getOffsetX(), target.y + face.getOffsetY(),
                    target.z + face.getOffsetZ())] = stone;
        }
    }

    /** Classic cardinal checks followed by diagonals admitted only by both adjacent edges. */
    private static int classicMask(BlockState[] states, BlockState stone, Offset[] directions,
                                   Direction face, boolean innerSeams) {
        int mask = 0;
        for (int direction = 0; direction < 4; direction++) {
            if (classicConnects(states, stone, directions[direction], face, innerSeams)) {
                mask |= 1 << (2 * direction);
            }
        }
        for (int direction = 0; direction < 4; direction++) {
            int next = (direction + 1) & 3;
            if ((mask & (1 << (2 * direction))) != 0 && (mask & (1 << (2 * next))) != 0
                    && classicConnects(states, stone, directions[direction].plus(directions[next]), face, innerSeams)) {
                mask |= 1 << (2 * direction + 1);
            }
        }
        return mask;
    }

    private static boolean classicConnects(BlockState[] states, BlockState stone, Offset target,
                                           Direction face, boolean innerSeams) {
        return states[index(target.x, target.y, target.z)] == stone
                && (!innerSeams || states[index(target.x + face.getOffsetX(), target.y + face.getOffsetY(),
                target.z + face.getOffsetZ())] != stone);
    }

    /** Independent vanilla face basis; deliberately does not call production tangentOffset. */
    private static Offset[] classicDirections(Direction face) {
        Direction vertical = face == Direction.UP ? Direction.NORTH
                : face == Direction.DOWN ? Direction.SOUTH : Direction.UP;
        Direction horizontal = face.getDirection() == Direction.AxisDirection.NEGATIVE
                ? vertical.rotateClockwise(face.getAxis()) : vertical.rotateCounterclockwise(face.getAxis());
        return new Offset[]{Offset.of(horizontal), Offset.of(vertical.getOpposite()),
                Offset.of(horizontal.getOpposite()), Offset.of(vertical)};
    }

    private static int index(int x, int y, int z) {
        require(x >= -1 && x <= 1 && y >= -1 && y <= 1 && z >= -1 && z <= 1,
                "Production mask requested a cell outside 3x3x3: " + x + "," + y + "," + z);
        return (x + 1) * 9 + (y + 1) * 3 + z + 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Offset(int x, int y, int z) {
        static Offset of(Direction direction) {
            return new Offset(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        }
        Offset plus(Offset other) { return new Offset(x + other.x, y + other.y, z + other.z); }
    }
}
