package com.oliver.erydon.client.pom;

import net.minecraft.util.math.Direction;

/** World-projected UV gradients paired with the exact signed-byte shader normal. */
final class ColumnMeshTangentFrame {
    private ColumnMeshTangentFrame() { }

    /** Zero means this normal is singular for the stored texture plane; preserve the encoder's tangent. */
    static int pack(Direction plane,int normal) {
        if(plane==null)return 0;
        int nx=(byte)normal,ny=(byte)(normal>>>8),nz=(byte)(normal>>>16);
        int ux=0,uz=0,vy=0,vz=0;
        switch(plane) {
            case NORTH -> {ux=-1;vy=-1;}
            case SOUTH -> {ux=1;vy=-1;}
            case WEST -> {uz=1;vy=-1;}
            case EAST -> {uz=-1;vy=-1;}
            case UP -> {ux=1;vz=1;}
            case DOWN -> {ux=1;vz=-1;}
        }
        // Constant V on the surface requires T perpendicular to both grad(V) and N.
        int tx=vy*nz-vz*ny,ty=vz*nx,tz=-vy*nx;
        int alignment=ux*tx+uz*tz;
        if(alignment==0)return 0;
        double length=Math.sqrt((double)tx*tx+(double)ty*ty+(double)tz*tz);
        double scale=(alignment>0?127.0:-127.0)/length;
        // Iris1.7.6 encodes sign(dot(B,cross(T,N))), rather than cross(N,T).
        int handedness=alignment<0?127:-127;
        return ((int)(tx*scale)&255)|(((int)(ty*scale)&255)<<8)|(((int)(tz*scale)&255)<<16)|((handedness&255)<<24);
    }
}
