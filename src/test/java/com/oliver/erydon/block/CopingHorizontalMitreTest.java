package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopingHorizontalMitreTest {
    private static final double EPS = 1.0e-8;
    private static final double[] WIDTHS = {1 / Math.sqrt(5), Math.sqrt(.5), 2 / Math.sqrt(5), 1, 3 / Math.sqrt(5)};
    private static final double[] TURNS = {Math.atan(.5), Math.PI / 4, Math.atan(2), Math.PI / 2, 2 * Math.atan(2)};

    @Test void straightRailsShareOneCutWithoutChangingTheirWidth() {
        var a = new CopingHorizontalMitre.Rail(.25, .25, -Math.PI / 4, Math.sqrt(2), Math.sqrt(.5));
        var b = new CopingHorizontalMitre.Rail(.75, -.25, -Math.PI / 4, Math.sqrt(2), Math.sqrt(.5));
        var join = assertNotNullJoin(a, 1, b, -1);
        assertTrue(join.reaches(0));
        assertEquals(Math.sqrt(.5) / 2, join.sourceDistance(), EPS);
        assertEquals(join.sourceDistance(), join.targetDistance(), EPS);
        assertEquals(0, join.plane().distance(.5, 0), EPS);
        assertEquals(join.plane(), join.plane(.125));
        assertReciprocal(a, 1, b, -1, join);
    }

    @Test void mixedWidthsMeetAtTheSameSharpCornersAcrossEveryAuthoredOverhang() {
        for (double turn : TURNS) for (int hand : new int[]{-1, 1})
            for (double aw : WIDTHS) for (double bw : WIDTHS) for (int rotation = 0; rotation < 4; rotation++) {
                double angle = turn * hand, quarter = rotation * Math.PI / 2;
                var a = rotate(new CopingHorizontalMitre.Rail(-1.75, 0, 0, 4, aw), quarter);
                var b = rotate(new CopingHorizontalMitre.Rail(1.75 * Math.cos(angle), 1.75 * Math.sin(angle), angle, 4, bw), quarter);
                var join = assertNotNullJoin(a, 1, b, -1);
                for (double overhang : new double[]{-.03125, 0, .03125, .125}) {
                    var plane = join.plane(overhang);
                    assertEquals(1, Math.hypot(plane.x(), plane.z()), EPS);
                    assertTrue(plane.distance(a.x(), a.z()) < 0);
                    assertTrue(plane.distance(b.x(), b.z()) > 0);
                    for (boolean left : new boolean[]{false, true}) {
                        var corner = join.sideCorner(left, overhang);
                        double side = left ? 1 : -1;
                        assertEquals(0, plane.distance(corner.x(), corner.z()), EPS);
                        assertEquals(side * (aw / 2 + overhang), lateral(a, corner), EPS);
                        assertEquals(side * (bw / 2 + overhang), lateral(b, corner), EPS);
                    }
                }
                assertReciprocal(a, 1, b, -1, join);
            }
    }

    @Test void unequalWidthsRecomputeTheBevelCutInsteadOfLeavingASplitOuterCorner() {
        var a = new CopingHorizontalMitre.Rail(-1, 0, 0, 3, 1);
        var b = new CopingHorizontalMitre.Rail(1, 1, Math.PI / 4, 3, Math.sqrt(.5));
        var join = assertNotNullJoin(a, 1, b, -1);
        var outer = join.sideCorner(true, .125);
        assertTrue(Math.abs(join.plane().distance(outer.x(), outer.z())) > .001,
                "A single core plane would leave the unequal-width authored overhangs misaligned");
        assertEquals(0, join.plane(.125).distance(outer.x(), outer.z()), EPS);
    }

    @Test void localEndCutMatchesItsWorldPlaneIncludingTranslatedCentres() {
        var a = new CopingHorizontalMitre.Rail(12.1, -7.2, -Math.atan(2), 1.5, .9);
        var b = new CopingHorizontalMitre.Rail(12.1 + Math.cos(a.yaw()) + 1, -7.2 + Math.sin(a.yaw()), 0, 2, 1);
        var join = assertNotNullJoin(a, 1, b, -1);
        for (double extra : new double[]{0, .125}) {
            var world = join.plane(extra);
            var local = world.local(a);
            for (double x : new double[]{-.125, 0, .5, 1, 1.125}) for (double z : new double[]{-.125, 0, .5, 1, 1.125}) {
                double wx = a.x() + (x - .5) * Math.cos(a.yaw()) - (z - .5) * Math.sin(a.yaw());
                double wz = a.z() + (x - .5) * Math.sin(a.yaw()) + (z - .5) * Math.cos(a.yaw());
                assertEquals(world.distance(wx, wz), local.distance(x, z), EPS);
            }
        }
    }

    @Test void piecewiseCutsShareTheWholeBevelCrossSectionForEitherNativeEnd() {
        for (double turn : TURNS) for (double aw : WIDTHS) for (double bw : WIDTHS)
            for (int ae : new int[]{-1, 1}) for (int be : new int[]{-1, 1}) {
                double aa = ae == 1 ? 0 : Math.PI, ba = turn + (be == -1 ? 0 : Math.PI);
                var a = new CopingHorizontalMitre.Rail(-1.75, 0, aa, 4, aw);
                var b = new CopingHorizontalMitre.Rail(1.75 * Math.cos(turn), 1.75 * Math.sin(turn), ba, 4, bw);
                var join = assertNotNullJoin(a, ae, b, be);
                assertEquals(3, join.sourceCuts().size()); assertEquals(3, join.targetCuts().size());
                for (double sign : new double[]{-1, 1}) {
                    double boundary = sign * aw / 2;
                    int outside = sign < 0 ? 0 : 2;
                    assertEquals(join.sourceCuts().get(1).at(boundary), join.sourceCuts().get(outside).at(boundary), EPS);
                }
                for (double p : new double[]{-aw / 2 - .125, -aw / 2 - .03125, -aw / 2, -aw / 4,
                        0, aw / 4, aw / 2, aw / 2 + .03125, aw / 2 + .125}) {
                    double sign = -ae * be;
                    double q = sign * (Math.abs(p) <= aw / 2 ? p * bw / aw
                            : Math.copySign(bw / 2 + Math.abs(p) - aw / 2, p));
                    var fromA = point(a, join.sourceCut(p), p);
                    var fromB = point(b, join.targetCut(q), q);
                    assertEquals(fromA.x(), fromB.x(), EPS);
                    assertEquals(fromA.z(), fromB.z(), EPS);
                }
            }
    }

    @Test void rejectsBackwardsParallelDisplacedWidthStepsAndDistantCrossings() {
        var a = new CopingHorizontalMitre.Rail(0, 0, 0, 1, 1);
        assertNull(CopingHorizontalMitre.solve(a, 1, new CopingHorizontalMitre.Rail(1, 0, 0, 1, 1), 1));
        assertNull(CopingHorizontalMitre.solve(a, 1, new CopingHorizontalMitre.Rail(-1, 0, 0, 1, 1), -1));
        assertNull(CopingHorizontalMitre.solve(a, 1, new CopingHorizontalMitre.Rail(1, .1, 0, 1, 1), -1));
        assertNull(CopingHorizontalMitre.solve(a, 1, new CopingHorizontalMitre.Rail(1, 0, 0, 1, .7), -1));
        var remote = assertNotNullJoin(a, 1, new CopingHorizontalMitre.Rail(10, 0, 0, 1, 1), -1);
        assertFalse(remote.reaches(.125));
        assertThrows(IllegalArgumentException.class, () -> remote.plane(-.5));
        assertThrows(IllegalArgumentException.class, () -> CopingHorizontalMitre.solve(a, 0, a, 1));
        assertThrows(IllegalArgumentException.class, () -> new CopingHorizontalMitre.Rail(0, 0, 0, 1, Double.NaN));
    }

    private static CopingHorizontalMitre.Mitre assertNotNullJoin(CopingHorizontalMitre.Rail a, int ae,
                                                                CopingHorizontalMitre.Rail b, int be) {
        var result = CopingHorizontalMitre.solve(a, ae, b, be);
        assertNotNull(result, () -> "Missing mitre for " + a + " -> " + b);
        return result;
    }

    private static void assertReciprocal(CopingHorizontalMitre.Rail a, int ae, CopingHorizontalMitre.Rail b, int be,
                                         CopingHorizontalMitre.Mitre join) {
        var reverse = assertNotNullJoin(b, be, a, ae);
        for (double extra : new double[]{0, .03125, .125}) {
            var p = join.plane(extra); var q = reverse.plane(extra);
            assertEquals(-p.x(), q.x(), EPS); assertEquals(-p.z(), q.z(), EPS);
            assertEquals(-p.boundary(), q.boundary(), EPS);
            var left = join.sideCorner(true, extra); var right = reverse.sideCorner(false, extra);
            assertEquals(left.x(), right.x(), EPS); assertEquals(left.z(), right.z(), EPS);
        }
    }

    private static double lateral(CopingHorizontalMitre.Rail rail, CopingHorizontalMitre.Point point) {
        return -(point.x() - rail.x()) * Math.sin(rail.yaw()) + (point.z() - rail.z()) * Math.cos(rail.yaw());
    }

    private static CopingHorizontalMitre.Point point(CopingHorizontalMitre.Rail rail, double run, double lateral) {
        return new CopingHorizontalMitre.Point(rail.x() + run * Math.cos(rail.yaw()) - lateral * Math.sin(rail.yaw()),
                rail.z() + run * Math.sin(rail.yaw()) + lateral * Math.cos(rail.yaw()));
    }

    private static CopingHorizontalMitre.Rail rotate(CopingHorizontalMitre.Rail rail, double angle) {
        return new CopingHorizontalMitre.Rail(rail.x() * Math.cos(angle) - rail.z() * Math.sin(angle),
                rail.x() * Math.sin(angle) + rail.z() * Math.cos(angle), rail.yaw() + angle, rail.length(), rail.width());
    }
}
