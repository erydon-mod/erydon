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

    @Test void standardSlopeJoinsCubeTopsAtBothEndsAndInBothDirections() {
        Surface slope = ramp(1, 0);
        Surface top = cubeFace(Direction.UP);
        assertEquals(bit(Direction.UP, -1, 0, 0), maskFor(slope, Direction.UP, Map.of("-1,0,0", top)));
        assertEquals(bit(Direction.UP, 1, 0, 0), maskFor(slope, Direction.UP, Map.of("1,-1,0", top)));
        assertEquals(bit(Direction.UP, 1, 0, 0), maskFor(top, Direction.UP, Map.of("1,0,0", slope)));
        assertEquals(bit(Direction.UP, -1, 0, 0), maskFor(top, Direction.UP, Map.of("-1,1,0", slope)));
    }

    @Test void shallowSlopesJoinCubesOnlyAtTheMatchingHeight() {
        Surface top = cubeFace(Direction.UP);
        assertEquals(bit(Direction.UP, -1, 0, 0), maskFor(ramp(1, .5F), Direction.UP,
                Map.of("-1,0,0", top)));
        assertEquals(bit(Direction.UP, 1, 0, 0), maskFor(ramp(.5F, 0), Direction.UP,
                Map.of("1,-1,0", top)));
        assertEquals(0, maskFor(ramp(1, .5F), Direction.UP, Map.of("1,0,0", top)));
        assertEquals(0, maskFor(ramp(.5F, 0), Direction.UP, Map.of("-1,0,0", top)));
        assertEquals(0, maskFor(top, Direction.UP, Map.of("1,0,0", ramp(.5F, 0))));
        assertEquals(0, maskFor(top, Direction.UP, Map.of("-1,1,0", ramp(1, .5F))));
    }

    @Test void invertedSlopeJoinsCubeUndersidesInBothDirections() {
        Surface slope = ramp(0, 1);
        Surface bottom = cubeFace(Direction.DOWN);
        assertEquals(bit(Direction.DOWN, -1, 0, 0), maskFor(slope, Direction.DOWN,
                Map.of("-1,0,0", bottom)));
        assertEquals(bit(Direction.DOWN, 1, 0, 0), maskFor(slope, Direction.DOWN,
                Map.of("1,1,0", bottom)));
        assertEquals(bit(Direction.DOWN, 1, 0, 0), maskFor(bottom, Direction.DOWN,
                Map.of("1,0,0", slope)));
        assertEquals(bit(Direction.DOWN, -1, 0, 0), maskFor(bottom, Direction.DOWN,
                Map.of("-1,-1,0", slope)));
    }

    @Test void steepAndVerticalSlopesJoinCubeSidesReciprocally() {
        Surface steep = new Surface(new Point(0, 1, 0), new Point(0, 1, 1),
                new Point(.5F, 0, 1), new Point(.5F, 0, 0));
        Surface west = cubeFace(Direction.WEST);
        assertEquals(bit(Direction.WEST, 0, 1, 0), maskFor(steep, Direction.WEST,
                Map.of("0,1,0", west)));
        assertEquals(bit(Direction.WEST, 0, -1, 0), maskFor(west, Direction.WEST,
                Map.of("0,-1,0", steep)));
        Surface vertical = new Surface(new Point(0, 0, 1), new Point(0, 1, 1),
                new Point(1, 1, 0), new Point(1, 0, 0));
        Surface north = cubeFace(Direction.NORTH);
        assertEquals(bit(Direction.NORTH, 1, 0, 0), maskFor(vertical, Direction.NORTH,
                Map.of("1,0,0", north)));
        assertEquals(bit(Direction.NORTH, -1, 0, 0), maskFor(north, Direction.NORTH,
                Map.of("-1,0,0", vertical)));
    }

    @Test void cubeJoinsWorkAcrossEveryFaceWithoutBridgingGaps() {
        for (Direction face : Direction.values()) {
            Surface flat = cubeFace(face);
            for (int i = 0; i < 4; i++) {
                var offset = SynapheiaRepeatBakedModel.tangentOffset(face, i);
                String neighbour = offset.x() + "," + offset.y() + "," + offset.z();
                assertEquals(1 << (2 * i), maskFor(flat, face, Map.of(neighbour, flat)));
                assertEquals(0, maskFor(flat, face, Map.of(neighbour, cubeFace(face.getOpposite()))));
            }
        }
    }

    @Test void slopeCubeCornerNeedsBothPhysicalEdgesAndTheDiagonal() {
        Surface slope = ramp(1, 0);
        Surface top = cubeFace(Direction.UP);
        int edges = bit(Direction.UP, -1, 0, 0) | bit(Direction.UP, 0, 0, -1);
        assertEquals(edges, maskFor(slope, Direction.UP,
                Map.of("-1,0,0", top, "0,0,-1", slope)));
        assertEquals(3, Integer.bitCount(maskFor(slope, Direction.UP,
                Map.of("-1,0,0", top, "0,0,-1", slope, "-1,0,-1", top))));
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
        assertEquals(8, offsets.size(), "Include the cubes directly above and below the ramp endpoints");
    }

    @Test void foldedStripJoinsSameColumnCubesOnEveryFacingAndHalf() {
        for (int turns = 0; turns < 4; turns++) for (boolean inverted : new boolean[]{false, true}) {
            Direction projection = mapped(Direction.UP, turns, inverted);
            Surface slope = transformed(ramp(1, 0), projection, turns, inverted);
            assertFold(slope, projection, Direction.WEST, 0, 1, 0, -1, 0, 0, turns, inverted);
            assertFold(slope, projection, Direction.EAST, 0, -1, 0, 1, 0, 0, turns, inverted);
        }
    }

    @Test void shallowGradientPairJoinsFoldedCubesOnlyAtItsRealEndpoints() {
        for (int turns = 0; turns < 4; turns++) for (boolean inverted : new boolean[]{false, true}) {
            Direction projection = mapped(Direction.UP, turns, inverted);
            Surface upper = transformed(ramp(1, .5F), projection, turns, inverted);
            Surface lower = transformed(ramp(.5F, 0), projection, turns, inverted);
            assertFold(upper, projection, Direction.WEST, 0, 1, 0, -1, 0, 0, turns, inverted);
            assertFold(lower, projection, Direction.EAST, 0, -1, 0, 1, 0, 0, turns, inverted);

            int[] below = mappedOffset(0, -1, 0, turns, inverted);
            int[] above = mappedOffset(0, 1, 0, turns, inverted);
            assertEquals(0, cubeMask(upper, projection, mapped(Direction.EAST, turns, inverted), below));
            assertEquals(0, cubeMask(lower, projection, mapped(Direction.WEST, turns, inverted), above));

            int[] across = mappedOffset(1, 0, 0, turns, inverted);
            assertEquals(projectedBit(projection, 1, 0, 0, turns, inverted),
                    joinedMask(upper, projection, lower, across));
            assertEquals(projectedBit(projection, -1, 0, 0, turns, inverted),
                    joinedMask(lower, projection, upper, mappedOffset(-1, 0, 0, turns, inverted)));
        }
    }

    @Test void raisedAndLoweredCubeFoldsRemainReciprocal() {
        for (int turns = 0; turns < 4; turns++) for (boolean inverted : new boolean[]{false, true}) {
            Direction projection = mapped(Direction.UP, turns, inverted);
            Surface slope = transformed(ramp(1, 0), projection, turns, inverted);
            assertFold(slope, projection, Direction.EAST, -1, 1, 0, -1, 0, 0, turns, inverted);
            assertFold(slope, projection, Direction.WEST, 1, -1, 0, 1, 0, 0, turns, inverted);
        }
    }

    @Test void foldedMatchesRejectGapsCornerTouchesAndBackToBackPlanes() {
        Surface slope = ramp(1, 0);
        Edge top = slope.boundary(new SynapheiaRepeatBakedModel.Offset(-1, 0, 0));
        assertTrue((cubeJoinFaces(Direction.UP, slope, top, 0, 1, 0) & (1 << Direction.WEST.ordinal())) != 0,
                "The visible upper fold is obtuse and must not be rejected by a positive normal-dot test");
        assertEquals(0, cubeJoinFaces(Direction.UP, slope, top, 0, 0, 1), "A point contact is not a joining edge");
        assertFalse(surfacesJoin(Direction.UP, slope, ramp(1, .5F), top, -1, 1, 0), "There is a height gap");
        Surface upsideDown = new Surface(Direction.DOWN, slope.points);
        assertFalse(surfacesJoin(Direction.UP, slope, upsideDown, top, 0, 0, 0));

        Surface cube = cubeFace(Direction.EAST);
        Edge edge = cube.boundary(new SynapheiaRepeatBakedModel.Offset(0, 1, 0));
        assertEquals(0, cubeJoinFaces(Direction.EAST, cube, edge, 1, 0, 0),
                "Neighbour cube's opposite face cannot erase a visible border");
        assertFalse(surfacesJoin(Direction.EAST, cube, cubeFace(Direction.UP), edge, 0, 0, 0),
                "Ordinary cube faces must not start folding around their own edges");
    }

    @Test void matchingCubeFacesKeepVisibilityQueriesWithinTheExistingCache() {
        for (float[] heights : new float[][]{{1, 0}, {1, .5F}, {.5F, 0}}) {
            for (int turns = 0; turns < 4; turns++) for (boolean inverted : new boolean[]{false, true}) {
                Direction projection = mapped(Direction.UP, turns, inverted);
                Surface slope = transformed(ramp(heights[0], heights[1]), projection, turns, inverted);
                mask(projection, slope, (x, y, z, edge) -> {
                    assertTrue(Math.abs(x) <= 1 && Math.abs(y) <= 1 && Math.abs(z) <= 1);
                    int faces = cubeJoinFaces(projection, slope, edge, x, y, z);
                    for (Direction face : Direction.values()) if ((faces & (1 << face.ordinal())) != 0)
                        assertTrue(faceVisibilityInCache(x, y, z, face), "Use the actual joining face's occlusion cell");
                    return false;
                });
            }
        }
    }

    @Test void duplicatedTriangleVertexRetainsItsRealPlaneAndProjectionOrientation() {
        Surface duplicated = new Surface(Direction.UP, new Point(0, 1, 0), new Point(0, 1, 0),
                new Point(0, 1, 1), new Point(1, 0, 1));
        assertTrue(duplicated.sloped());
        assertTrue(duplicated.normal(Direction.UP).y() > 0);
        for (Direction face : Direction.values()) {
            Point normal = cubeFace(face).normal(face);
            assertTrue(normal.x() * face.getOffsetX() + normal.y() * face.getOffsetY()
                    + normal.z() * face.getOffsetZ() > 0, "Cube and hull winding must use their projection hint");
        }
    }

    @Test void narrowValidRampFragmentsStillJoinAtTheirSharedEdge() {
        Surface source = new Surface(Direction.UP, new Point(0, 1, 0), new Point(0, 1, .005F),
                new Point(1, 0, .005F), new Point(1, 0, 0));
        Surface target = new Surface(Direction.UP, source.points);
        Edge edge = source.boundary(new SynapheiaRepeatBakedModel.Offset(-1, 0, 0));
        assertTrue(surfacesJoin(Direction.UP, source, target, edge, -1, 1, 0),
                "Two individually valid POM fragments must not fail a product-of-areas cutoff");
        Surface collapsed = new Surface(Direction.UP, new Point(0, 1, 0), new Point(0, 1, 0),
                new Point(1, 0, 0), new Point(1, 0, 0));
        assertFalse(surfacesJoin(Direction.UP, source, collapsed, edge, -1, 1, 0),
                "A degenerate zero-area face still cannot establish a join");
    }

    @Test void duplicatedTargetVertexCannotTurnOneCornerContactIntoAnEdgeJoin() {
        Surface slope = ramp(1, 0);
        Edge edge = slope.boundary(new SynapheiaRepeatBakedModel.Offset(-1, 0, 0));
        Surface triangle = new Surface(Direction.UP, new Point(0, 1, 0), new Point(0, 1, 0),
                new Point(1, 0, 0), new Point(1, 0, 1));
        assertTrue(triangle.sloped(), "This is a valid triangle, not a zero-area target face");
        assertFalse(triangle.meets(edge, 0, 0, 1));
        assertFalse(surfacesJoin(Direction.UP, slope, triangle, edge, 0, 0, 1));
        Point sharedCorner = new Point(0, 1, 1);
        assertTrue(triangle.meets(new Edge(sharedCorner, sharedCorner), 0, 0, 1),
                "Intentional CTM diagonal-point queries still recognize the shared vertex");
    }

    private static void assertFold(Surface slope, Direction projection, Direction cubeDirection,
                                   int x, int y, int z, int edgeX, int edgeY, int edgeZ,
                                   int turns, boolean inverted) {
        Direction cubeDirectionMapped = mapped(cubeDirection, turns, inverted);
        int[] offset = mappedOffset(x, y, z, turns, inverted);
        assertEquals(projectedBit(projection, edgeX, edgeY, edgeZ, turns, inverted),
                cubeMask(slope, projection, cubeDirectionMapped, offset));
        int[] reverse = new int[]{-offset[0], -offset[1], -offset[2]};
        Surface cube = cubeFace(cubeDirectionMapped);
        // The cube's shared edge is determined by its own plane, independently of ramp UV projection.
        int expected = 0;
        for (int i = 0; i < 4; i++) {
            Edge edge = cube.boundary(SynapheiaRepeatBakedModel.tangentOffset(cubeDirectionMapped, i));
            if (surfacesJoin(cubeDirectionMapped, cube, slope, edge, reverse[0], reverse[1], reverse[2]))
                expected |= 1 << (2 * i);
        }
        assertEquals(1, Integer.bitCount(expected), "One actual cube edge joins this ramp");
        assertEquals(expected, joinedMask(cube, cubeDirectionMapped, slope, reverse));
    }

    private static int cubeMask(Surface slope, Direction projection, Direction cubeDirection, int[] offset) {
        return mask(projection, slope, (x, y, z, edge) -> x == offset[0] && y == offset[1] && z == offset[2]
                && (cubeJoinFaces(projection, slope, edge, x, y, z) & (1 << cubeDirection.ordinal())) != 0);
    }

    private static int joinedMask(Surface source, Direction projection, Surface target, int[] offset) {
        return mask(projection, source, (x, y, z, edge) -> x == offset[0] && y == offset[1] && z == offset[2]
                && surfacesJoin(projection, source, target, edge, x, y, z));
    }

    private static int projectedBit(Direction projection, int x, int y, int z, int turns, boolean inverted) {
        int[] direction = mappedOffset(x, y, z, turns, inverted);
        return bit(projection, direction[0], direction[1], direction[2]);
    }

    private static Direction mapped(Direction direction, int turns, boolean inverted) {
        int[] vector = mappedOffset(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ(), turns, inverted);
        return Direction.fromVector(vector[0], vector[1], vector[2]);
    }

    private static int[] mappedOffset(int x, int y, int z, int turns, boolean inverted) {
        for (int i = 0; i < turns; i++) { int previousX = x; x = -z; z = previousX; }
        return new int[]{x, inverted ? -y : y, z};
    }

    private static Surface transformed(Surface source, Direction projection, int turns, boolean inverted) {
        Point[] points = new Point[source.points.length];
        for (int i = 0; i < points.length; i++) {
            Point point = source.points[i];
            float x = point.x(), z = point.z();
            for (int turn = 0; turn < turns; turn++) { float previousX = x; x = 1 - z; z = previousX; }
            points[i] = new Point(x, inverted ? 1 - point.y() : point.y(), z);
        }
        return new Surface(projection, points);
    }

    @Test void triangularSidesConnectAcrossRealPartialEdgesReciprocally() {
        Surface triangle=new Surface(new Point(0,0,0),new Point(1,0,0),new Point(0,.5F,0));
        Surface cube=cubeFace(Direction.NORTH);
        assertEquals(bit(Direction.NORTH,-1,0,0),maskFor(triangle,Direction.NORTH,Map.of("-1,0,0",cube)));
        assertEquals(bit(Direction.NORTH,1,0,0),maskFor(cube,Direction.NORTH,Map.of("1,0,0",triangle)));
        assertEquals(bit(Direction.NORTH,0,-1,0),maskFor(triangle,Direction.NORTH,Map.of("0,-1,0",cube)));
        assertEquals(0,maskFor(triangle,Direction.NORTH,Map.of("1,0,0",cube)),"A lone corner is not a shared edge");
        Surface gap=new Surface(new Point(0,.6F,0),new Point(0,1,0),new Point(1,1,0));
        assertEquals(0,maskFor(triangle,Direction.NORTH,Map.of("-1,0,0",gap)));
    }

    @Test void sideStripsReassembleTheWholeTriangleWithoutInternalBorders() {
        java.util.List<Point> points=new java.util.ArrayList<>();
        for(int i=0;i<16;i++) {
            float x0=i/16F,x1=(i+1)/16F;
            points.addAll(java.util.List.of(new Point(x0,0,0),new Point(x1,0,0),
                    new Point(x1,.5F*(1-x1),0),new Point(x0,.5F*(1-x0),0)));
        }
        Surface side=hull(Direction.NORTH,points);
        assertEquals(3,side.points.length);
        assertEquals(bit(Direction.NORTH,-1,0,0)|bit(Direction.NORTH,0,-1,0),
                maskFor(side,Direction.NORTH,Map.of("-1,0,0",cubeFace(Direction.NORTH),"0,-1,0",cubeFace(Direction.NORTH))));
    }
}
