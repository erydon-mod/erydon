package com.oliver.erydon.client.model;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynapheiaNeighbourCacheTest {
    private static final int CONNECTED = 1;
    private static final int DISCONNECTED = 0;
    private static final BlockPos ORIGIN = new BlockPos(20, 64, -30);

    @Test
    void cachedAndLegacyMasksMatchForEveryFace() {
        for (Direction face : Direction.values()) {
            Offset[] candidates = candidateOffsets(face);
            for (int pattern = 0; pattern < 256; pattern++) {
                int[] states = neighbourhood();
                int seamPattern = Integer.rotateLeft(pattern, 3) & 0xFF;
                for (int bit = 0; bit < candidates.length; bit++) {
                    Offset target = candidates[bit];
                    if ((pattern & (1 << bit)) != 0) {
                        states[index(target.x(), target.y(), target.z())] = CONNECTED;
                    }
                    if ((seamPattern & (1 << bit)) != 0) {
                        states[index(target.x() + face.getOffsetX(),
                                target.y() + face.getOffsetY(),
                                target.z() + face.getOffsetZ())] = CONNECTED;
                    }
                }

                for (boolean innerSeams : new boolean[]{false, true}) {
                    int expectedMask = legacyConnectionMask(states, face, innerSeams);
                    int actualMask = cachedConnectionMask(states, face, innerSeams);
                    assertEquals(expectedMask, actualMask,
                            "mask parity for " + face + ", pattern " + pattern
                                    + ", innerSeams=" + innerSeams);
                    assertEquals(
                            SynapheiaRepeatBakedModel.connectedTileIndex(expectedMask),
                            SynapheiaRepeatBakedModel.connectedTileIndex(actualMask));
                }
            }
        }
    }

    @Test
    void eachRelativeSlotIsReadAtMostOnce() {
        SyntheticView synthetic = new SyntheticView();
        SynapheiaNeighbourCache cache = new SynapheiaNeighbourCache(
                synthetic.stateGetter(), ORIGIN);

        for (int pass = 0; pass < 2; pass++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        cache.get(dx, dy, dz);
                    }
                }
            }
        }

        assertEquals(27, Arrays.stream(synthetic.readCounts()).sum());
        assertTrue(Arrays.stream(synthetic.readCounts()).allMatch(count -> count == 1));
    }

    @Test
    void repeatOnlyPlansDoNotCreateTheCache() {
        assertNull(SynapheiaRepeatBakedModel.createNeighbourCache(false, null, null));
    }

    private static int legacyConnectionMask(int[] states,
                                            Direction face,
                                            boolean innerSeams) {
        Direction[] directions = legacyFaceDirections(face);
        int mask = 0;
        for (int direction = 0; direction < 4; direction++) {
            Offset target = Offset.from(directions[direction]);
            if (connects(states, target, face, innerSeams)) {
                mask |= 1 << (direction * 2);
            }
        }
        for (int direction = 0; direction < 4; direction++) {
            int next = (direction + 1) & 3;
            int firstBit = 1 << (direction * 2);
            int nextBit = 1 << (next * 2);
            Offset target = Offset.from(directions[direction]).plus(
                    Offset.from(directions[next]));
            if ((mask & firstBit) != 0 && (mask & nextBit) != 0
                    && connects(states, target, face, innerSeams)) {
                mask |= 1 << (direction * 2 + 1);
            }
        }
        return mask;
    }

    private static int cachedConnectionMask(int[] states,
                                            Direction face,
                                            boolean innerSeams) {
        int mask = 0;
        for (int direction = 0; direction < 4; direction++) {
            Offset target = Offset.from(
                    SynapheiaRepeatBakedModel.tangentOffset(face, direction));
            if (connects(states, target, face, innerSeams)) {
                mask |= 1 << (direction * 2);
            }
        }
        for (int direction = 0; direction < 4; direction++) {
            int next = (direction + 1) & 3;
            int firstBit = 1 << (direction * 2);
            int nextBit = 1 << (next * 2);
            Offset target = Offset.from(
                    SynapheiaRepeatBakedModel.tangentOffset(face, direction)).plus(
                    Offset.from(SynapheiaRepeatBakedModel.tangentOffset(face, next)));
            if ((mask & firstBit) != 0 && (mask & nextBit) != 0
                    && connects(states, target, face, innerSeams)) {
                mask |= 1 << (direction * 2 + 1);
            }
        }
        return mask;
    }

    private static boolean connects(int[] states,
                                    Offset target,
                                    Direction face,
                                    boolean innerSeams) {
        if (state(states, target.x(), target.y(), target.z()) != CONNECTED) {
            return false;
        }
        return !innerSeams || state(states,
                target.x() + face.getOffsetX(),
                target.y() + face.getOffsetY(),
                target.z() + face.getOffsetZ()) != CONNECTED;
    }

    private static Offset[] candidateOffsets(Direction face) {
        Direction[] directions = legacyFaceDirections(face);
        Offset[] offsets = new Offset[8];
        for (int direction = 0; direction < 4; direction++) {
            Offset side = Offset.from(directions[direction]);
            Offset next = Offset.from(directions[(direction + 1) & 3]);
            offsets[direction * 2] = side;
            offsets[direction * 2 + 1] = side.plus(next);
        }
        return offsets;
    }

    private static Direction[] legacyFaceDirections(Direction face) {
        Direction vertical = face == Direction.UP ? Direction.NORTH
                : face == Direction.DOWN ? Direction.SOUTH : Direction.UP;
        Direction horizontal = face.getDirection() == Direction.AxisDirection.NEGATIVE
                ? vertical.rotateClockwise(face.getAxis())
                : vertical.rotateCounterclockwise(face.getAxis());
        return new Direction[]{
                horizontal, vertical.getOpposite(), horizontal.getOpposite(), vertical
        };
    }

    private static int[] neighbourhood() {
        int[] states = new int[27];
        Arrays.fill(states, DISCONNECTED);
        states[index(0, 0, 0)] = CONNECTED;
        return states;
    }

    private static int state(int[] states, int dx, int dy, int dz) {
        return states[index(dx, dy, dz)];
    }

    private static int index(int dx, int dy, int dz) {
        return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
    }

    private record Offset(int x, int y, int z) {
        private static Offset from(Direction direction) {
            return new Offset(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        }

        private static Offset from(SynapheiaRepeatBakedModel.Offset offset) {
            return new Offset(offset.x(), offset.y(), offset.z());
        }

        private Offset plus(Offset other) {
            return new Offset(x + other.x, y + other.y, z + other.z);
        }
    }

    private static final class SyntheticView {
        private final int[] readCounts = new int[27];
        private final SynapheiaNeighbourCache.StateGetter stateGetter = pos -> {
            int slot = index(pos.getX() - ORIGIN.getX(),
                    pos.getY() - ORIGIN.getY(), pos.getZ() - ORIGIN.getZ());
            readCounts[slot]++;
            return null;
        };

        private SynapheiaNeighbourCache.StateGetter stateGetter() {
            return stateGetter;
        }

        private int[] readCounts() {
            return readCounts;
        }
    }
}
