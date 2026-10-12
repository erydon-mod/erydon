package com.oliver.erydon.block;

import net.minecraft.util.math.Direction;

/** Rear wall edge and outward profile depth, shared by placed meshes and interaction shapes. */
public record CorniceRun(Point a, Point b) {
    private static final double EPS = 1.0e-7;

    public CorniceRun {
        if (a == null || b == null || Math.hypot(b.x - a.x, b.z - a.z) <= EPS) {
            throw new IllegalArgumentException("Cornice rear endpoints must form a finite, nonzero edge");
        }
    }

    public CorniceRun(double ax, double az, double bx, double bz) {
        this(new Point(ax, az), new Point(bx, bz));
    }

    public record Point(double x, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Cornice coordinates must be finite");
            }
        }
    }

    /** Authored longitudinal coordinate at the mitre, varying with outward profile depth. */
    public record Cut(double slope, double intercept) {
        public Cut {
            if (!Double.isFinite(slope) || !Double.isFinite(intercept)) {
                throw new IllegalArgumentException("Cornice end cuts must be finite");
            }
        }

        public double at(double depth) {
            return slope * depth + intercept;
        }
    }

    public record Join(Cut sourceCut, Cut targetCut) { }

    public double length() {
        return Math.hypot(b.x - a.x, b.z - a.z);
    }

    public double tangentX() { return (b.x - a.x) / length(); }
    public double tangentZ() { return (b.z - a.z) / length(); }
    public double normalX() { return -tangentZ(); }
    public double normalZ() { return tangentX(); }

    /** X stretches to the rear edge; the profile retains its authored outward thickness. */
    public Point point(double u, double depth) {
        return new Point(a.x + (b.x - a.x) * u + normalX() * depth,
                a.z + (b.z - a.z) * u + normalZ() * depth);
    }

    /** Inverse-transpose of the stretched run, before normalizing a full 3-D normal. */
    public Point normal(double sourceX, double sourceZ) {
        double longitudinal = sourceX / length();
        return new Point(tangentX() * longitudinal + normalX() * sourceZ,
                tangentZ() * longitudinal + normalZ() * sourceZ);
    }

    public CorniceRun translated(double x, double z) {
        return new CorniceRun(a.x + x, a.z + z, b.x + x, b.z + z);
    }

    /** Rotate a NORTH-authored edge clockwise around the owner's cell centre. */
    public CorniceRun rotate(Direction facing) {
        int turns = switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Cornice facing must be horizontal");
        };
        return new CorniceRun(rotate(a, turns), rotate(b, turns));
    }

    private static Point rotate(Point source, int turns) {
        double x = source.x, z = source.z;
        for (int turn = 0; turn < turns; turn++) {
            double next = 1 - z;
            z = x;
            x = next;
        }
        return new Point(x, z);
    }

    /**
     * Both rear endpoints and outward profile strips must meet. End signs are
     * -1 at a and +1 at b; an oriented wall chain joins opposite end signs.
     * Every shared depth then lies on the intersection of both offset wall lines.
     */
    public static Join join(CorniceRun source, int sourceEnd, CorniceRun target, int targetEnd) {
        if (Math.abs(sourceEnd) != 1 || Math.abs(targetEnd) != 1) {
            throw new IllegalArgumentException("Cornice end signs must be -1 or +1");
        }
        if (sourceEnd == targetEnd) return null;
        Point sourceRear = sourceEnd < 0 ? source.a : source.b;
        Point targetRear = targetEnd < 0 ? target.a : target.b;
        if (Math.hypot(sourceRear.x - targetRear.x, sourceRear.z - targetRear.z) > EPS) return null;

        double sx = source.tangentX(), sz = source.tangentZ();
        double tx = target.tangentX(), tz = target.tangentZ();
        double dot = sx * tx + sz * tz;
        // Reject a reversed exterior or near hairpin before its offset rays
        // create an unbounded mitre. All registered slope angles are well outside this tolerance.
        if (dot <= -1 + EPS) return null;
        double determinant = cross(sx, sz, tx, tz);
        double sourceIntercept = sourceEnd < 0 ? 0 : 1;
        double targetIntercept = targetEnd < 0 ? 0 : 1;
        if (Math.abs(determinant) <= EPS) {
            return dot > 0 ? new Join(new Cut(0, sourceIntercept), new Cut(0, targetIntercept)) : null;
        }
        double dx = target.normalX() - source.normalX();
        double dz = target.normalZ() - source.normalZ();
        return new Join(new Cut(cross(dx, dz, tx, tz) / determinant / source.length(), sourceIntercept),
                new Cut(cross(dx, dz, sx, sz) / determinant / target.length(), targetIntercept));
    }

    private static double cross(double ax, double az, double bx, double bz) {
        return ax * bz - az * bx;
    }
}
