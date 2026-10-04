package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CopingHorizontalFit;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.util.math.Direction;

/** Cached, world-independent yaw and translation of the editable flat coping. */
final class CopingHorizontalTransform implements RenderContext.QuadTransform {
    private final double cosine,sine,x,z;
    CopingHorizontalTransform(CopingHorizontalFit.Pose pose) {
        cosine=Math.cos(pose.yawRadians()); sine=Math.sin(pose.yawRadians());
        x=pose.centreX(); z=pose.centreZ();
    }
    float x(float a,float b) { return (float)(x+(a-.5)*cosine-(b-.5)*sine); }
    float z(float a,float b) { return (float)(z+(a-.5)*sine+(b-.5)*cosine); }
    Direction direction(Direction face) {
        if(face==null || face.getAxis()==Direction.Axis.Y) return face;
        return Direction.getFacing((float)(face.getOffsetX()*cosine-face.getOffsetZ()*sine),0,
                (float)(face.getOffsetX()*sine+face.getOffsetZ()*cosine));
    }
    int normal(int packed) {
        int a=(byte)packed,b=(byte)(packed>>>16);
        int nextX=(int)Math.round(a*cosine-b*sine),nextZ=(int)Math.round(a*sine+b*cosine);
        return (packed&0xFF00FF00)|(nextX&255)|((nextZ&255)<<16);
    }
    @Override public boolean transform(MutableQuadView quad) {
        Direction plane=CopingTexturePlane.face(quad.tag());
        Direction nominal=quad.nominalFace();
        for(int i=0;i<4;i++) {
            float a=quad.x(i),b=quad.z(i);
            quad.pos(i,x(a,b),quad.y(i),z(a,b));
            if(quad.hasNormal(i)) {
                float nx=quad.normalX(i),nz=quad.normalZ(i);
                quad.normal(i,(float)(nx*cosine-nz*sine),quad.normalY(i),(float)(nx*sine+nz*cosine));
            }
        }
        quad.cullFace(null).nominalFace(direction(nominal));
        if(plane!=null) quad.tag(CopingTexturePlane.tag(direction(plane)));
        // Synapheia projects from the transformed world positions and authored
        // plane tag, including any parts that cross a texture-cell boundary.
        return true;
    }
}
