package com.oliver.erydon.block;

import net.minecraft.util.math.Direction;

/** Shared face selection for plain and inlaid multiface layers. */
final class MultifacePlacement {
    private static final double FACE_EPS = 1.0e-3;
    private MultifacePlacement() { }

    static Direction touchingFace(Direction clickedSide, double x, double y, double z) {
        // At an edge/corner several coordinates are on boundaries. Only the axis
        // of the actual hit can identify the supporting surface unambiguously.
        double coordinate = switch (clickedSide.getAxis()) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        double boundary = clickedSide.getDirection() == Direction.AxisDirection.POSITIVE ? 0.0 : 1.0;
        return Math.abs(coordinate - boundary) <= FACE_EPS ? clickedSide.getOpposite() : null;
    }

}
