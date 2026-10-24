package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.block.CorniceRun;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorniceCroppedGeometryTest {
    private static final CorniceRun.Cut START=new CorniceRun.Cut(0,0), END=new CorniceRun.Cut(0,1);

    @Test void halfProfileKeepsAnOrnamentsNativeWidthAndDiscardsTheOtherHalf() {
        BakedQuad first=top(.125F,.25F,.25F),second=top(.625F,.75F,.25F);
        int[] before=first.getVertexData().clone();
        var half=new CorniceConnections.Segment(new CorniceRun(0,0,.5,0),START,END,false,false,true);
        var result=CorniceAngledGeometry.fit(List.of(first,second),half);
        assertEquals(1,result.size());
        assertEquals(.125,min(result,0),1.e-7);assertEquals(.25,max(result,0),1.e-7);
        assertEquals(.125,max(result,0)-min(result,0),1.e-7,"The ornament was compressed rather than cropped");
        for(BakedQuad q:result)for(int i=0;i<4;i++)assertEquals(value(q,i,0),value(q,i,4),1.e-7,"Cropping changed source UV scale");
        var old=new CorniceConnections.Segment(half.run(),START,END,false,false);
        var compressed=CorniceAngledGeometry.fit(List.of(first),old);
        assertEquals(.0625,max(compressed,0)-min(compressed,0),1.e-7,"Negative control no longer models affine compression");
        assertNotEquals(max(compressed,0)-min(compressed,0),max(result,0)-min(result,0));
        assertArrayEquals(before,first.getVertexData());
    }

    @Test void adjacentHalfRunsUseTheCorrectHalfOfOneAuthoredUnitProfile() {
        var half=new CorniceConnections.Segment(new CorniceRun(.5,0,1,0),START,END,false,false,true);
        var result=CorniceAngledGeometry.fit(List.of(top(.125F,.25F,.25F),top(.625F,.75F,.25F)),half);
        assertEquals(.5,half.sourcePhase(),1.e-12);assertEquals(1,result.size());
        assertEquals(.625,min(result,0),1.e-7);assertEquals(.75,max(result,0),1.e-7);
        for(BakedQuad q:result)for(int i=0;i<4;i++)assertEquals(value(q,i,0),value(q,i,4),1.e-7);
    }

    @Test void allLegacyHypotenusesRemainAffineWhenTheOtherJoinedArmIsHalfWidth() {
        List<CorniceRun> published=List.of(new CorniceRun(0,1,1,0),
                new CorniceRun(.5,1,1,0),new CorniceRun(1,1,.5,0),
                new CorniceRun(0,1,.5,0),new CorniceRun(.5,1,0,0));
        List<BakedQuad> source=List.of(top(.125F,.25F,.25F),top(.625F,.75F,.25F));
        int cases=0;
        for(CorniceRun original:published)for(Direction facing:Direction.Type.HORIZONTAL) {
            CorniceRun sourceRun=original.rotate(facing);
            double dx=original.tangentX()>0?.5:-.5;
            CorniceRun halfRun=new CorniceRun(original.b().x(),original.b().z(),original.b().x()+dx,original.b().z()).rotate(facing);
            var join=CorniceRun.join(sourceRun,1,halfRun,-1);assertNotNull(join);
            var legacy=new CorniceConnections.Segment(sourceRun,START,join.sourceCut(),false,true);
            // Discovery propagates the half-support marker to its reciprocal peer.
            var marked=new CorniceConnections.Segment(sourceRun,START,join.sourceCut(),false,true,true);
            var half=new CorniceConnections.Segment(halfRun,join.targetCut(),END,true,false,true);
            assertTrue(marked.cropProfile());assertFalse(marked.usesProfileCrop(),"A half peer changed a published hypotenuse to rigid cropping");
            assertTrue(half.usesProfileCrop(),"The actual short cardinal arm stopped preserving ornament scale");
            var expected=CorniceAngledGeometry.fit(source,legacy);var actual=CorniceAngledGeometry.fit(source,marked);
            assertEquals(expected.size(),actual.size());
            for(int i=0;i<expected.size();i++) {
                assertArrayEquals(expected.get(i).getVertexData(),actual.get(i).getVertexData(),"A joined old hypotenuse changed geometry, UVs or normals");
                assertEquals(expected.get(i).getFace(),actual.get(i).getFace());
            }
            for(int i=0;i<=64;i++) {
                double depth=i/128.;var a=marked.point(1,depth);var b=half.point(0,depth);
                assertEquals(a.x(),b.x(),1.e-12);assertEquals(a.z(),b.z(),1.e-12);
            }
            cases++;
        }
        assertEquals(20,cases);
    }

    @Test void exteriorHalfArmsMeetAtEveryDepthWithoutStretchingOrDuplicateTopCoverage() {
        CorniceRun a=new CorniceRun(0,0,.5,0),b=new CorniceRun(.5,0,.5,-.5);
        var join=CorniceRun.join(a,1,b,-1);assertNotNull(join);
        var first=new CorniceConnections.Segment(a,START,join.sourceCut(),false,true,true);
        var second=new CorniceConnections.Segment(b,join.targetCut(),END,true,false,true);
        for(int i=0;i<=128;i++) {
            double depth=i/256.;
            assertEquals(first.point(1,depth),second.point(0,depth));
        }
        var qa=CorniceAngledGeometry.fit(List.of(top(0,1,.5F)),first);
        var qb=CorniceAngledGeometry.fit(List.of(top(0,1,.5F)),second);
        assertEquals(.375,area(qa),1.e-7);assertEquals(.375,area(qb),1.e-7);
        for(BakedQuad q:qa)for(int i=0;i<4;i++)assertTrue(value(q,i,0)<=.5+value(q,i,2)+1.e-7);
        for(BakedQuad q:qb)for(int i=0;i<4;i++)assertTrue(value(q,i,2)<=value(q,i,0)-.5+1.e-7);
        assertNotEquals(.5,area(qa)+area(qb),"Negative control omitted the exterior corner square");
    }

    @Test void realSliceContoursCloseBothUnjoinedCutEndsAndKeepSafeSourceUvs() {
        List<BakedQuad> source=box(0,.5F,0,1,1,.25F);
        int[][] original=source.stream().map(q->q.getVertexData().clone()).toArray(int[][]::new);
        var segment=new CorniceConnections.Segment(new CorniceRun(0,0,.5,0),START,END,false,false,true);
        var result=CorniceAngledGeometry.fit(source,segment);
        assertEquals(.125,faceArea(result,Direction.WEST),1.e-7);
        assertEquals(.125,faceArea(result,Direction.EAST),1.e-7);
        for(BakedQuad q:result)for(int i=0;i<4;i++) {
            assertTrue(value(q,i,4)>=0&&value(q,i,4)<=1);assertTrue(value(q,i,5)>=0&&value(q,i,5)<=1);
            assertEquals(0xFF234567,q.getVertexData()[i*8+3]);assertEquals(0x01230045,q.getVertexData()[i*8+6]);
        }
        for(int i=0;i<source.size();i++)assertArrayEquals(original[i],source.get(i).getVertexData());
    }

    @Test void overlappingSliceVolumesAreUnionedAndARealHollowCentreRemainsOpen() {
        List<BakedQuad> overlapping=new ArrayList<>(box(0,.5F,0,1,1,.25F));
        overlapping.addAll(box(0,.625F,.125F,1,1,.375F));
        var segment=new CorniceConnections.Segment(new CorniceRun(0,0,.5,0),START,END,false,false,true);
        var result=CorniceAngledGeometry.fit(overlapping,overlapping,segment);
        assertEquals(.171875,faceArea(result,Direction.WEST),1.e-7,"Overlapping authored volumes produced duplicate cap area");
        assertEquals(.171875,faceArea(result,Direction.EAST),1.e-7);
        assertNotEquals(.21875,faceArea(result,Direction.WEST),"Negative control summed overlapping contours");
        List<BakedQuad> hollow=new ArrayList<>(box(0,.5F,0,1,.625F,.5F));
        hollow.addAll(box(0,.875F,0,1,1,.5F));
        hollow.addAll(box(0,.625F,0,1,.875F,.125F));
        hollow.addAll(box(0,.625F,.375F,1,.875F,.5F));
        result=CorniceAngledGeometry.fit(hollow,hollow,segment);
        assertEquals(.1875,faceArea(result,Direction.WEST),1.e-7,"The actual hollow slice was filled by a cap");
        assertEquals(.1875,faceArea(result,Direction.EAST),1.e-7);
        assertNotEquals(.25,faceArea(result,Direction.WEST),"Negative control capped the whole outer contour");
    }

    private static BakedQuad top(float min,float max,float depth) {return quad(Direction.UP,new float[][]{{min,1,0},{min,1,depth},{max,1,depth},{max,1,0}});}
    private static List<BakedQuad> box(float x0,float y0,float z0,float x1,float y1,float z1) {
        List<BakedQuad> q=new ArrayList<>();
        q.add(quad(Direction.UP,new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}}));
        q.add(quad(Direction.DOWN,new float[][]{{x0,y0,z1},{x0,y0,z0},{x1,y0,z0},{x1,y0,z1}}));
        q.add(quad(Direction.NORTH,new float[][]{{x1,y1,z0},{x1,y0,z0},{x0,y0,z0},{x0,y1,z0}}));
        q.add(quad(Direction.SOUTH,new float[][]{{x0,y1,z1},{x0,y0,z1},{x1,y0,z1},{x1,y1,z1}}));
        q.add(quad(Direction.WEST,new float[][]{{x0,y1,z0},{x0,y0,z0},{x0,y0,z1},{x0,y1,z1}}));
        q.add(quad(Direction.EAST,new float[][]{{x1,y1,z1},{x1,y0,z1},{x1,y0,z0},{x1,y1,z0}}));
        return q;
    }
    private static BakedQuad quad(Direction face,float[][] points) {
        int[] data=new int[32];
        for(int i=0;i<4;i++) {
            for(int j=0;j<3;j++)data[i*8+j]=Float.floatToRawIntBits(points[i][j]);
            data[i*8+3]=0xFF234567;data[i*8+6]=0x01230045;
            data[i*8+4]=Float.floatToRawIntBits(points[i][0]);data[i*8+5]=Float.floatToRawIntBits(points[i][2]);
        }
        return new BakedQuad(data,6,face,null,false);
    }
    private static double min(List<BakedQuad> qs,int axis){return qs.stream().flatMapToDouble(q->java.util.stream.IntStream.range(0,4).mapToDouble(i->value(q,i,axis))).min().orElseThrow();}
    private static double max(List<BakedQuad> qs,int axis){return qs.stream().flatMapToDouble(q->java.util.stream.IntStream.range(0,4).mapToDouble(i->value(q,i,axis))).max().orElseThrow();}
    private static double faceArea(List<BakedQuad> qs,Direction face){return area(qs.stream().filter(q->q.getFace()==face).toList());}
    private static double area(List<BakedQuad> qs){return qs.stream().mapToDouble(q->triangle(q,0,1,2)+triangle(q,0,2,3)).sum();}
    private static double triangle(BakedQuad q,int a,int b,int c){double[] u=new double[3],v=new double[3];for(int i=0;i<3;i++){u[i]=value(q,b,i)-value(q,a,i);v[i]=value(q,c,i)-value(q,a,i);}double x=u[1]*v[2]-u[2]*v[1],y=u[2]*v[0]-u[0]*v[2],z=u[0]*v[1]-u[1]*v[0];return Math.sqrt(x*x+y*y+z*z)/2;}
    private static float value(BakedQuad q,int i,int slot){return Float.intBitsToFloat(q.getVertexData()[i*8+slot]);}
}
