package com.oliver.erydon.block;

/** 40 geometry keys: top/bottom, four facings, and five stair shapes. */
public final class StairShapeSlots {
    public static final int COUNT = 40;

    private StairShapeSlots() {}

    public static int index(boolean top, int horizontalFacing, int shapeOrdinal) {
        if (horizontalFacing < 0 || horizontalFacing >= 4 || shapeOrdinal < 0 || shapeOrdinal >= 5) {
            throw new IllegalArgumentException("Invalid stair geometry state");
        }
        return ((top ? 4 : 0) + horizontalFacing) * 5 + shapeOrdinal;
    }
}
