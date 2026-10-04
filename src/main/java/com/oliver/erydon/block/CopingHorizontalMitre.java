package com.oliver.erydon.block;

import java.util.List;

/** Shared end cuts for two horizontal coping rails, including unequal widths. */
public final class CopingHorizontalMitre {
    private static final double EPS = 1.0e-7;
    private CopingHorizontalMitre() { }

    /** World centre, longitudinal angle, core length and core width in block units. */
    public record Rail(double x, double z, double yaw, double length, double width) {
        public Rail {
            if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(yaw)
                    || !Double.isFinite(length) || !Double.isFinite(width) || length <= 0 || width <= 0)
                throw new IllegalArgumentException("Coping rail dimensions must be finite and positive");
        }
    }

    public record Point(double x, double z) { }

    /** Native longitudinal coordinate measured from the rail centre. */
    public record Cut(double slope, double intercept) {
        public double at(double lateral) { return slope * lateral + intercept; }
    }

    /** Unit normal; the source retains distance <= 0, and the target retains >= 0. */
    public record Plane(double x, double z, double boundary) {
        public double distance(double px, double pz) { return x * px + z * pz - boundary; }

        /** Express a world cut in a rail's unrotated, centre-at-(.5,.5) model coordinates. */
        public Plane local(Rail rail) {
            double cosine = Math.cos(rail.yaw), sine = Math.sin(rail.yaw);
            double nx = x * cosine + z * sine, nz = -x * sine + z * cosine;
            return new Plane(nx, nz, boundary - x * rail.x - z * rail.z + .5 * (nx + nz));
        }
    }

    public static final class Mitre {
        private final Rail source, target;
        private final Point a, b;
        private final boolean straight;
        private final Plane core;
        private final List<Cut> sourceCuts, targetCuts;

        private Mitre(Rail source, int sourceEnd, Rail target, int targetEnd, Point a, Point b, boolean straight) {
            this.source = source; this.target = target; this.a = a; this.b = b; this.straight = straight;
            core = calculate(0);
            sourceCuts = cuts(source, sourceEnd, target, targetEnd, straight);
            targetCuts = cuts(target, targetEnd, source, sourceEnd, straight);
        }

        public Plane plane() { return core; }
        /** Negative overhang, proportional core, positive overhang, respectively. */
        public List<Cut> sourceCuts() { return sourceCuts; }
        public List<Cut> targetCuts() { return targetCuts; }
        public double sourceCut(double lateral) { return cut(sourceCuts, source.width, lateral); }
        public double targetCut(double lateral) { return cut(targetCuts, target.width, lateral); }

        private static double cut(List<Cut> cuts, double width, double lateral) {
            if (!Double.isFinite(lateral)) throw new IllegalArgumentException("Coping lateral coordinate must be finite");
            return cuts.get(lateral < -width / 2 ? 0 : lateral > width / 2 ? 2 : 1).at(lateral);
        }

        /**
         * Authored side bevels can overhang the core by different distances at each height.
         * Re-solving their two perimeter intersections gives both neighbours the same sharp
         * corner at that height, even when the underlying wall widths differ.
         */
        public Plane plane(double perimeterOffset) {
            if (!Double.isFinite(perimeterOffset)
                    || source.width / 2 + perimeterOffset <= EPS
                    || target.width / 2 + perimeterOffset <= EPS)
                throw new IllegalArgumentException("Coping perimeter must have a positive width");
            return perimeterOffset == 0 ? core : calculate(perimeterOffset);
        }

        public double sourceDistance() { return -core.distance(source.x, source.z) / dot(core.x, core.z, a.x, a.z); }
        public double targetDistance() { return -core.distance(target.x, target.z) / dot(core.x, core.z, b.x, b.z); }

        /** Discovery uses this to avoid joining a distant crossing rather than adjacent ends. */
        public boolean reaches(double extension) {
            if (!Double.isFinite(extension) || extension < 0) throw new IllegalArgumentException("Invalid coping end extension");
            return sourceDistance() <= source.length / 2 + extension + EPS
                    && targetDistance() <= target.length / 2 + extension + EPS;
        }

        public Point sideCorner(boolean left, double perimeterOffset) {
            if (straight) {
                double sign = left ? 1 : -1;
                double width = source.width / 2 + perimeterOffset;
                Plane plane = plane(perimeterOffset);
                double sx = source.x - sign * a.z * width, sz = source.z + sign * a.x * width;
                double distance = -plane.distance(sx, sz) / dot(plane.x, plane.z, a.x, a.z);
                return new Point(sx + distance * a.x, sz + distance * a.z);
            }
            return intersection(left ? 1 : -1, perimeterOffset);
        }

        private Plane calculate(double extra) {
            if (straight) return new Plane(a.x, a.z,
                    a.x * (source.x + target.x) / 2 + a.z * (source.z + target.z) / 2);
            Point left = intersection(1, extra), right = intersection(-1, extra);
            double nx = -(right.z - left.z), nz = right.x - left.x;
            double length = Math.hypot(nx, nz);
            nx /= length; nz /= length;
            if (dot(nx, nz, a.x, a.z) < 0) { nx = -nx; nz = -nz; }
            return new Plane(nx, nz, nx * left.x + nz * left.z);
        }

        private Point intersection(int side, double extra) {
            double aw = side * (source.width / 2 + extra), bw = side * (target.width / 2 + extra);
            double px = source.x - a.z * aw, pz = source.z + a.x * aw;
            // Opposite outward directions reverse the target's left/right edge.
            double qx = target.x + b.z * bw, qz = target.z - b.x * bw;
            double t = cross(qx - px, qz - pz, b.x, b.z) / cross(a.x, a.z, b.x, b.z);
            return new Point(px + t * a.x, pz + t * a.z);
        }
    }

    /** End signs are -1 for the local west end, +1 for the local east end. */
    public static Mitre solve(Rail source, int sourceEnd, Rail target, int targetEnd) {
        if (Math.abs(sourceEnd) != 1 || Math.abs(targetEnd) != 1)
            throw new IllegalArgumentException("Coping end signs must be -1 or +1");
        Point a = new Point(sourceEnd * Math.cos(source.yaw), sourceEnd * Math.sin(source.yaw));
        Point b = new Point(targetEnd * Math.cos(target.yaw), targetEnd * Math.sin(target.yaw));
        double determinant = cross(a.x, a.z, b.x, b.z);
        boolean straight = Math.abs(determinant) < EPS;
        if (straight && (dot(a.x, a.z, b.x, b.z) > -1 + EPS
                || Math.abs(cross(target.x - source.x, target.z - source.z, a.x, a.z)) > EPS
                || Math.abs(source.width - target.width) > EPS)) return null;
        Mitre mitre = new Mitre(source, sourceEnd, target, targetEnd, a, b, straight);
        Plane plane = mitre.core;
        if (!Double.isFinite(plane.boundary) || dot(plane.x, plane.z, a.x, a.z) <= EPS
                || dot(plane.x, plane.z, b.x, b.z) >= -EPS
                || plane.distance(source.x, source.z) >= -EPS
                || plane.distance(target.x, target.z) <= EPS) return null;
        return mitre;
    }

    private static List<Cut> cuts(Rail source, int sourceEnd, Rail target, int targetEnd, boolean straight) {
        double ax = Math.cos(source.yaw), az = Math.sin(source.yaw);
        if (straight) {
            Cut cut = new Cut(0, dot(target.x - source.x, target.z - source.z, ax, az) / 2);
            return List.of(cut, cut, cut);
        }
        double bx = Math.cos(target.yaw), bz = Math.sin(target.yaw);
        double determinant = cross(ax, az, bx, bz);
        double side = -sourceEnd * targetEnd;
        double intercept = cross(target.x - source.x, target.z - source.z, bx, bz) / determinant;
        double coreRatio = side * target.width / source.width;
        double coreSlope = cross(-bz * coreRatio + az, bx * coreRatio - ax, bx, bz) / determinant;
        double outerSlope = cross(-bz * side + az, bx * side - ax, bx, bz) / determinant;
        double outerShift = cross(-bz * side * (target.width - source.width) / 2,
                bx * side * (target.width - source.width) / 2, bx, bz) / determinant;
        return List.of(new Cut(outerSlope, intercept - outerShift), new Cut(coreSlope, intercept),
                new Cut(outerSlope, intercept + outerShift));
    }

    private static double dot(double ax, double az, double bx, double bz) { return ax * bx + az * bz; }
    private static double cross(double ax, double az, double bx, double bz) { return ax * bz - az * bx; }
}
