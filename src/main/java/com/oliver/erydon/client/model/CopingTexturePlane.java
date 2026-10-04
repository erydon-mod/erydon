package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

/** Nominal faces are not serialized by Fabric meshes; tags preserve the authored CTM plane. */
final class CopingTexturePlane {
    private static final int MARKER=0x43504C00;
    private CopingTexturePlane() { }

    static int tag(Direction face) { return MARKER | face.getId(); }

    static Direction face(int tag) {
        return (tag & ~7)==MARKER && (tag & 7)<6 ? Direction.byId(tag & 7) : null;
    }

    static int rotate(int tag,int turns) {
        Direction face=face(tag);
        if(face==null || face.getAxis()==Direction.Axis.Y) return tag;
        for(int turn=0;turn<Math.floorMod(turns,4);turn++) face=face.rotateYClockwise();
        return tag(face);
    }
}
