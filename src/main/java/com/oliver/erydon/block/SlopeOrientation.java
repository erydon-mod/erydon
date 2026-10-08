package com.oliver.erydon.block;

import net.minecraft.block.enums.BlockHalf;
import net.minecraft.util.math.Direction;

/** Pure orientation tables shared by rendering and cached interaction shapes. */
public final class SlopeOrientation {
    private SlopeOrientation() {}
    public static int standard(Direction facing,BlockHalf half,SlopeBlock.SlopeShape shape) {
        int rotation=degrees(facing); boolean top=half==BlockHalf.TOP;
        return switch(shape) {
            case STRAIGHT -> rotation;
            case INNER_RIGHT -> top ? rotation : rotation+90;
            case INNER_LEFT -> top ? rotation-90 : rotation;
            case OUTER_LEFT -> top ? rotation : rotation-90;
            case OUTER_RIGHT -> top ? rotation+90 : rotation;
        };
    }
    public static int shallow(Direction facing,BlockHalf half,ShallowSlopeBlock.SlopeShape shape) {
        int rotation=degrees(facing); boolean top=half==BlockHalf.TOP;
        return switch(shape) {
            case STRAIGHT -> rotation;
            case INNER_RIGHT -> top ? rotation : rotation+90;
            case INNER_LEFT -> top ? rotation-90 : rotation;
            case OUTER_LEFT -> top ? rotation : rotation-90;
            case OUTER_RIGHT -> top ? rotation+90 : rotation;
        };
    }
    public static int steep(Direction facing,BlockHalf half,SlopeSteepBlock.SlopeShape shape) {
        int rotation=degrees(facing);
        if(shape==SlopeSteepBlock.SlopeShape.STRAIGHT) return rotation;
        boolean right=shape==SlopeSteepBlock.SlopeShape.INNER_RIGHT || shape==SlopeSteepBlock.SlopeShape.OUTER_RIGHT;
        if(half==BlockHalf.TOP && !right) return rotation-90;
        if(half==BlockHalf.BOTTOM && right) return rotation+90;
        return rotation;
    }
    private static int degrees(Direction facing) {
        return switch(facing) { case SOUTH -> 90; case WEST -> 180; case NORTH -> 270; default -> 0; };
    }
}
