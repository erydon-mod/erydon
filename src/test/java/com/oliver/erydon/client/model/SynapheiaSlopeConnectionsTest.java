package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.*;
import static org.junit.jupiter.api.Assertions.*;

class SynapheiaSlopeConnectionsTest {
    private static Surface ramp(float high, float low) {
        return new Surface(new Point(0, high, 0), new Point(0, high, 1),
                new Point(1, low, 1), new Point(1, low, 0));
    }

    private static int bit(Direction face, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            var offset = SynapheiaRepeatBakedModel.tangentOffset(face, i);
            if (offset.x() == x && offset.y() == y && offset.z() == z) return 1 << (2 * i);
        }
        throw new AssertionError("No projected edge");
    }

    private static int maskFor(Surface source, Direction face, Map<String, Surface> neighbours) {
        return mask(face, source, (x, y, z, edge) -> {
            Surface neighbour = neighbours.get(x + "," + y + "," + z);
            return neighbour != null && neighbour.meets(edge, x, y, z);
        });
    }

    @Test void shallowUpperAndLowerJoinAtTheirActualSharedHeight() {
        assertEquals(bit(Direction.UP, 1, 0, 0), maskFor(ramp(1, .5F), Direction.UP,
                Map.of("1,0,0", ramp(.5F, 0))));
        assertEquals(0, maskFor(ramp(1, .5F), Direction.UP, Map.of("1,0,0", ramp(1, .5F))),
                "Matching material alone must not bridge a height gap");
    }

    @Test void diagonalRampContinuationAndDifferentGradientsConnect() {
        assertEquals(bit(Direction.UP, -1, 0, 0), maskFor(ramp(1, 0), Direction.UP,
                Map.of("-1,1,0", ramp(.5F, 0))));
        assertEquals(bit(Direction.UP, 1, 0, 0), maskFor(ramp(1, 0), Direction.UP,
                Map.of("1,-1,0", ramp(1, .5F))));
        assertEquals(0, maskFor(ramp(1, 0), Direction.UP, Map.of("1,0,0", ramp(1, .5F))));
    }

    @Test void steepAndVerticalFacesUseTheirOwnProjection() {
        Surface steep = new Surface(new Point(0, 1, 0), new Point(0, 1, 1),
                new Point(.5F, 0, 1), new Point(.5F, 0, 0));
        assertEquals(bit(Direction.WEST, 0, 1, 0), maskFor(steep, Direction.WEST,
                Map.of("-1,1,0", ramp(1, 0))));
        Surface vertical = new Surface(new Point(0, 0, 1), new Point(0, 1, 1),
                new Point(1, 1, 0), new Point(1, 0, 0));
        assertEquals(bit(Direction.NORTH, 1, 0, 0), maskFor(vertical, Direction.NORTH,
                Map.of("1,0,-1", vertical)));
    }

    @Test void cornerTilesRequireBothEdgesAndThePhysicalDiagonal() {
        Surface slope = ramp(1, 0);
        int twoEdges = bit(Direction.UP, -1, 0, 0) | bit(Direction.UP, 0, 0, -1);
        assertEquals(twoEdges, maskFor(slope, Direction.UP,
                Map.of("-1,1,0", slope, "0,0,-1", slope)));
        assertEquals(3, Integer.bitCount(maskFor(slope, Direction.UP,
                Map.of("-1,1,0", slope, "0,0,-1", slope, "-1,1,-1", slope))));
    }

    @Test void neighbourReadsStayInsideTheExistingBoundedCache() {
        Set<String> offsets = new HashSet<>();
        mask(Direction.UP, ramp(1, 0), (x, y, z, edge) -> {
            assertTrue(Math.abs(x) <= 1 && Math.abs(y) <= 1 && Math.abs(z) <= 1);
            assertFalse(x == 0 && y == 0 && z == 0);
            offsets.add(x + "," + y + "," + z);
            return false;
        });
        assertEquals(6, offsets.size());
    }
}
