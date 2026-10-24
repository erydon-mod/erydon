package com.oliver.erydon.client.pom;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMeshTangentFrameTest {
    @Test void sixFlatWorldTexturePlanesKeepTheirKnownIrisAxisAndHandedness() {
        for(Direction face:Direction.values()) {
            int normal=ColumnMeshNormalTransport.pack(face.getOffsetX(),face.getOffsetY(),face.getOffsetZ());
            int tangent=ColumnMeshTangentFrame.pack(face,normal);
            int[] expected=switch(face) {
                case NORTH->new int[]{-127,0,0};case SOUTH,UP,DOWN->new int[]{127,0,0};
                case WEST->new int[]{0,0,127};case EAST->new int[]{0,0,-127};
            };
            assertEquals(expected[0],(byte)tangent);assertEquals(expected[1],(byte)(tangent>>>8));
            assertEquals(expected[2],(byte)(tangent>>>16));assertEquals(127,(byte)(tangent>>>24));
        }
    }

    @Test void packedCornerNormalsHaveUnitOrthogonalStableFrames() {
        double bound=Math.sqrt(3)/(127-Math.sqrt(3));
        int checked=0;
        for(Direction face:Direction.values())for(int i=-8;i<=8;i++)for(int j=-8;j<=8;j++) {
            double x=face.getOffsetX(),y=face.getOffsetY(),z=face.getOffsetZ();
            if(face.getAxis()==Direction.Axis.Y){x+=i/12.;z+=j/12.;}
            else if(face.getAxis()==Direction.Axis.X){y+=i/12.;z+=j/12.;}
            else{x+=i/12.;y+=j/12.;}
            double length=Math.sqrt(x*x+y*y+z*z);
            int normal=ColumnMeshNormalTransport.pack((float)(x/length),(float)(y/length),(float)(z/length));
            int tangent=ColumnMeshTangentFrame.pack(face,normal);
            assertNotEquals(0,tangent);assertEquals(tangent,ColumnMeshTangentFrame.pack(face,normal));
            assertTrue(length(tangent)>=127-Math.sqrt(3));assertTrue(length(tangent)<=127.000001);
            assertTrue(Math.abs(dot(normal,tangent))/(length(normal)*length(tangent))<=bound+1.e-6);
            assertEquals(127,(byte)(tangent>>>24));checked++;
        }
        assertEquals(1734,checked);
    }

    @Test void missingOrSingularProjectionRetainsTheOriginalEncoderFrame() {
        assertEquals(0,ColumnMeshTangentFrame.pack(null,127));
        assertEquals(0,ColumnMeshTangentFrame.pack(Direction.UP,127));
        assertEquals(0,ColumnMeshTangentFrame.pack(Direction.NORTH,127<<8));
    }

    private static double dot(int a,int b){return(byte)a*(double)(byte)b+(byte)(a>>>8)*(double)(byte)(b>>>8)+(byte)(a>>>16)*(double)(byte)(b>>>16);}
    private static double length(int v){return Math.sqrt(dot(v,v));}
}
