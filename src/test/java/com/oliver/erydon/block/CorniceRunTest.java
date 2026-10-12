package com.oliver.erydon.block;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorniceRunTest {
    private static final double EPS = 1.0e-8;
    private static final CorniceRun[] SLOPES = {
            new CorniceRun(0, 1, 1, 0),
            new CorniceRun(.5, 1, 1, 0),
            new CorniceRun(1, 1, .5, 0),
            new CorniceRun(0, 1, .5, 0),
            new CorniceRun(.5, 1, 0, 0)
    };

    @Test void allSlopeHandsKeepTheRearFaceAndProfileThickness() {
        for (CorniceRun base : SLOPES) for (Direction facing : Direction.Type.HORIZONTAL) {
            CorniceRun run = base.rotate(facing).translated(-7, 11);
            for (double u : new double[]{0, .2, .5, .8, 1}) {
                CorniceRun.Point rear = run.point(u, 0);
                assertEquals(run.a().x() + (run.b().x() - run.a().x()) * u, rear.x(), EPS);
                assertEquals(run.a().z() + (run.b().z() - run.a().z()) * u, rear.z(), EPS);
                for (double depth : new double[]{1.0 / 16, 3.0 / 16, 5.5 / 16, .5}) {
                    CorniceRun.Point outer = run.point(u, depth);
                    assertEquals(depth, Math.hypot(outer.x() - rear.x(), outer.z() - rear.z()), EPS);
                    assertEquals(0, (outer.x() - rear.x()) * run.tangentX()
                            + (outer.z() - rear.z()) * run.tangentZ(), EPS);
                }
            }
        }
        assertEquals(Math.sqrt(2), SLOPES[0].length(), EPS);
        for (int index = 1; index < SLOPES.length; index++) {
            assertEquals(Math.sqrt(1.25), SLOPES[index].length(), EPS);
        }
    }

    @Test void cardinalMappingExactlyMatchesTheExistingStraightProfiles() {
        CorniceRun north = new CorniceRun(0, 0, 1, 0);
        CorniceRun.Point point = north.point(.25, .125);
        assertEquals(new CorniceRun.Point(.25, .125), point);
        assertEquals(new CorniceRun.Point(.875, .25), north.rotate(Direction.EAST).point(.25, .125));
        assertEquals(new CorniceRun.Point(.75, .875), north.rotate(Direction.SOUTH).point(.25, .125));
        assertEquals(new CorniceRun.Point(.125, .75), north.rotate(Direction.WEST).point(.25, .125));
    }

    @Test void diagonalAndCardinalMitresMeetAcrossEveryProfileDepthAndRotation() {
        for (CorniceRun slope : SLOPES) for (Direction facing : Direction.Type.HORIZONTAL) {
            for (int end : new int[]{-1, 1}) for (Direction cardinal : Direction.Type.HORIZONTAL) {
                CorniceRun target = cardinalAt(slope, end, cardinal);
                CorniceRun.Join join = CorniceRun.join(slope, end, target, -end);
                if (join == null) continue;
                assertSharedDepths(slope, target, join);
                CorniceRun sourceRotated = slope.rotate(facing).translated(17, -13);
                CorniceRun targetRotated = target.rotate(facing).translated(17, -13);
                CorniceRun.Join rotatedJoin = CorniceRun.join(sourceRotated, end, targetRotated, -end);
                assertNotNull(rotatedJoin);
                assertSharedDepths(sourceRotated, targetRotated, rotatedJoin);
                CorniceRun.Join reciprocal = CorniceRun.join(targetRotated, -end, sourceRotated, end);
                assertNotNull(reciprocal);
                assertEquals(rotatedJoin.sourceCut().slope(), reciprocal.targetCut().slope(), EPS);
                assertEquals(rotatedJoin.targetCut().slope(), reciprocal.sourceCut().slope(), EPS);
            }
        }
    }

    @Test void knownFortyFiveDegreeCornerHasTheExpectedIndependentBisector() {
        CorniceRun straight = new CorniceRun(-1, 0, 0, 0);
        CorniceRun angled = new CorniceRun(0, 0, 1, 1);
        CorniceRun.Join join = CorniceRun.join(straight, 1, angled, -1);
        assertNotNull(join);
        // Intersection of z=d and z-x=sqrt(2)*d.
        for (double depth : new double[]{0, 1.0 / 16, 5.5 / 16, .5}) {
            CorniceRun.Point seam = straight.point(join.sourceCut().at(depth), depth);
            assertEquals((1 - Math.sqrt(2)) * depth, seam.x(), EPS);
            assertEquals(depth, seam.z(), EPS);
            assertEquals(Math.sqrt(2) * depth, seam.z() - seam.x(), EPS);
        }
    }

    @Test void slopePairsMeetWithoutMovingTheirRearEndpoints() {
        for (CorniceRun source : SLOPES) for (CorniceRun baseTarget : SLOPES) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                CorniceRun target = baseTarget.rotate(facing);
                target = target.translated(source.b().x() - target.a().x(), source.b().z() - target.a().z());
                CorniceRun.Join join = CorniceRun.join(source, 1, target, -1);
                if (join != null) {
                    assertEquals(1, join.sourceCut().at(0), EPS);
                    assertEquals(0, join.targetCut().at(0), EPS);
                    assertSharedDepths(source, target, join);
                }
            }
        }
    }

    @Test void stretchedNormalsStayPerpendicularToTheTransformedProfile() {
        CorniceRun run = new CorniceRun(0, 1, 1, 0);
        CorniceRun.Point transformed = run.normal(1, 1);
        // Authored sloped surface tangent is (1,-1); map its longitudinal and depth steps independently.
        CorniceRun.Point a = run.point(0, 0), b = run.point(1, -1);
        assertEquals(0, transformed.x() * (b.x() - a.x()) + transformed.z() * (b.z() - a.z()), EPS);
        assertEquals(1, Math.hypot(run.normal(0, 1).x(), run.normal(0, 1).z()), EPS);
    }

    @Test void detachedDuplicateAndReversedExteriorEdgesNeverJoin() {
        CorniceRun straight = new CorniceRun(0, 0, 1, 0);
        assertNull(CorniceRun.join(straight, 1, new CorniceRun(1.1, 0, 2.1, 0), -1));
        assertNull(CorniceRun.join(straight, 1, new CorniceRun(1, .01, 2, .01), -1));
        assertNull(CorniceRun.join(straight, 1, new CorniceRun(0, 0, 1, 0), 1));
        assertNull(CorniceRun.join(straight, 1, new CorniceRun(1, 0, 0, 0), -1));
        assertNull(CorniceRun.join(straight, 1, new CorniceRun(1, 0, 0, .00001), -1));
        CorniceRun.Join continuation = CorniceRun.join(straight, 1, new CorniceRun(1, 0, 2, .00000001), -1);
        assertNotNull(continuation);
        assertTrue(Math.abs(continuation.sourceCut().slope()) < .000001);
    }

    @Test void invalidCoordinatesAndEndSignsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CorniceRun(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CorniceRun(Double.NaN, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CorniceRun.Cut(Double.POSITIVE_INFINITY, 0));
        CorniceRun run = new CorniceRun(0, 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> run.rotate(Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> CorniceRun.join(run, 0, run, 1));
    }

    private static CorniceRun cardinalAt(CorniceRun run, int end, Direction facing) {
        CorniceRun target = new CorniceRun(0, 0, 1, 0).rotate(facing);
        CorniceRun.Point sourceRear = end < 0 ? run.a() : run.b();
        CorniceRun.Point targetRear = end < 0 ? target.b() : target.a();
        return target.translated(sourceRear.x() - targetRear.x(), sourceRear.z() - targetRear.z());
    }

    private static void assertSharedDepths(CorniceRun source, CorniceRun target, CorniceRun.Join join) {
        for (double depth : new double[]{0, .01, 1.0 / 16, 3.0 / 16, 5.5 / 16, .5}) {
            CorniceRun.Point one = source.point(join.sourceCut().at(depth), depth);
            CorniceRun.Point two = target.point(join.targetCut().at(depth), depth);
            assertEquals(one.x(), two.x(), EPS);
            assertEquals(one.z(), two.z(), EPS);
            assertEquals(depth, (one.x() - source.a().x()) * source.normalX()
                    + (one.z() - source.a().z()) * source.normalZ(), EPS);
            assertEquals(depth, (two.x() - target.a().x()) * target.normalX()
                    + (two.z() - target.a().z()) * target.normalZ(), EPS);
        }
    }
}
