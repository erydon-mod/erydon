package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

final class SlopeQuadUtil {
    static final int DIRECTION_COUNT = Direction.values().length;
    private static final float EPSILON = 0.0001F;
    private static final float PARTIAL_BOUNDARY_OUTSET = 0.0005F;

    private SlopeQuadUtil() {
    }

    static Direction boundaryCullFace(Direction nominalFace,
                                      float x0, float y0, float z0,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3) {
        return switch (nominalFace) {
            case DOWN -> allNear(0.0F, y0, y1, y2, y3)
                    && coversFullUnitSquare(x0, z0, x1, z1, x2, z2, x3, z3) ? Direction.DOWN : null;
            case UP -> allNear(1.0F, y0, y1, y2, y3)
                    && coversFullUnitSquare(x0, z0, x1, z1, x2, z2, x3, z3) ? Direction.UP : null;
            case NORTH -> allNear(0.0F, z0, z1, z2, z3)
                    && coversFullUnitSquare(x0, y0, x1, y1, x2, y2, x3, y3) ? Direction.NORTH : null;
            case SOUTH -> allNear(1.0F, z0, z1, z2, z3)
                    && coversFullUnitSquare(x0, y0, x1, y1, x2, y2, x3, y3) ? Direction.SOUTH : null;
            case WEST -> allNear(0.0F, x0, x1, x2, x3)
                    && coversFullUnitSquare(z0, y0, z1, y1, z2, y2, z3, y3) ? Direction.WEST : null;
            case EAST -> allNear(1.0F, x0, x1, x2, x3)
                    && coversFullUnitSquare(z0, y0, z1, y1, z2, y2, z3, y3) ? Direction.EAST : null;
        };
    }

    static float partialHorizontalBoundaryOutsetY(Direction cullFace,
                                                  Direction nominalFace,
                                                  float y0,
                                                  float y1,
                                                  float y2,
                                                  float y3,
                                                  float y) {
        if (cullFace != null) {
            return y;
        }
        if (nominalFace == Direction.UP && allNear(1.0F, y0, y1, y2, y3)) {
            return y + PARTIAL_BOUNDARY_OUTSET;
        }
        if (nominalFace == Direction.DOWN && allNear(0.0F, y0, y1, y2, y3)) {
            return y - PARTIAL_BOUNDARY_OUTSET;
        }
        return y;
    }

    private static boolean allNear(float expected, float a, float b, float c, float d) {
        return near(expected, a) && near(expected, b) && near(expected, c) && near(expected, d);
    }

    private static boolean coversFullUnitSquare(float a0, float b0,
                                                float a1, float b1,
                                                float a2, float b2,
                                                float a3, float b3) {
        return hasCorner(0.0F, 0.0F, a0, b0, a1, b1, a2, b2, a3, b3)
                && hasCorner(0.0F, 1.0F, a0, b0, a1, b1, a2, b2, a3, b3)
                && hasCorner(1.0F, 0.0F, a0, b0, a1, b1, a2, b2, a3, b3)
                && hasCorner(1.0F, 1.0F, a0, b0, a1, b1, a2, b2, a3, b3);
    }

    private static boolean hasCorner(float expectedA, float expectedB,
                                     float a0, float b0,
                                     float a1, float b1,
                                     float a2, float b2,
                                     float a3, float b3) {
        return near(expectedA, a0) && near(expectedB, b0)
                || near(expectedA, a1) && near(expectedB, b1)
                || near(expectedA, a2) && near(expectedB, b2)
                || near(expectedA, a3) && near(expectedB, b3);
    }

    private static boolean near(float expected, float value) {
        return Math.abs(value - expected) <= EPSILON;
    }
}
