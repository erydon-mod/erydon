package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.block.CorniceRun;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorniceAngledGeometryTest {
    @Test void profileKeepsHeightAndDepthWhileItsRunStretches() {
        var segment = segment(new CorniceRun(0,1,1,0), new CorniceRun.Cut(0,0), new CorniceRun.Cut(0,1),false,false);
        BakedQuad source = quad(new float[][]{{0,.5F,.125F},{1,.5F,.125F},{1,1,.125F},{0,1,.125F}});
        var fitted = CorniceAngledGeometry.fit(List.of(source),segment);
        assertEquals(1,fitted.size());
        int[] data = fitted.get(0).getVertexData();
        for(int vertex=0;vertex<4;vertex++) {
            int offset=vertex*8;
            var expected=segment.point(vertex==1 || vertex==2 ? 1 : 0,.125);
            assertEquals(expected.x(),Float.intBitsToFloat(data[offset]),.000001);
            assertEquals(expected.z(),Float.intBitsToFloat(data[offset+2]),.000001);
            assertEquals(source.getVertexData()[offset+1],data[offset+1]);
            assertEquals(source.getVertexData()[offset+4],data[offset+4]);
            assertEquals(source.getVertexData()[offset+5],data[offset+5]);
            assertNotEquals(0,data[offset+7]&0xFFFFFF);
        }
    }

    @Test void nonplanarMitredFacesUseNondegenerateFirstThreeVerticesForEitherAoDiagonal() {
        var segment=segment(new CorniceRun(0,0,1,0),new CorniceRun.Cut(.3,0),new CorniceRun.Cut(-.2,1),true,true);
        BakedQuad source=quad(new float[][]{{0,.5F,0},{1,.7F,0},{1,.95F,.5F},{0,.75F,.5F}});
        var fitted=CorniceAngledGeometry.fit(List.of(source),segment);
        assertEquals(2,fitted.size());
        for(var triangle:fitted) {
            int[] data=triangle.getVertexData();
            for(int axis=0;axis<3;axis++) assertEquals(data[axis],data[24+axis]);
            assertFalse(samePosition(data,0,1));
            assertFalse(samePosition(data,1,2));
            assertFalse(samePosition(data,0,2));
        }
    }

    @Test void joinedEndsRemoveInternalCapsAndRetainExposedCaps() {
        BakedQuad end=quad(new float[][]{{0,.5F,0},{0,.5F,.25F},{0,1,.25F},{0,1,0}});
        var run=new CorniceRun(0,0,1,0);
        assertTrue(CorniceAngledGeometry.fit(List.of(end),segment(run,new CorniceRun.Cut(-.2,0),new CorniceRun.Cut(0,1),true,false)).isEmpty());
        assertEquals(1,CorniceAngledGeometry.fit(List.of(end),segment(run,new CorniceRun.Cut(0,0),new CorniceRun.Cut(0,1),false,false)).size());
    }

    @Test void cacheIsSharedByMaterialKeysAndRejectsEntriesOverItsSingleBudget() {
        var cache=new CorniceFittedCache();
        var key=List.of(segment(new CorniceRun(0,0,1,0),new CorniceRun.Cut(0,0),new CorniceRun.Cut(0,1),false,false));
        var quads=List.of(quad(new float[][]{{0,0,0},{1,0,0},{1,1,0},{0,1,0}}));
        cache.put("a",key,quads);
        assertSame(quads,cache.get("a",key));
        assertNull(cache.get("b",key));
        cache.put("oversized",key,Collections.nCopies(CorniceFittedCache.MAX_ESTIMATED_BYTES/224+1,quads.get(0)));
        assertNull(cache.get("oversized",key));
        assertNull(new CorniceFittedCache().get("a",key));
    }

    @Test void corniceProjectionMarkerSurvivesMeshFaceChangesWithoutChangingOtherFamilies() {
        int tag=CopingTexturePlane.corniceTag(Direction.UP);
        assertTrue(CopingTexturePlane.isCornice(tag));
        assertEquals(Direction.UP,SynapheiaRepeatBakedModel.repeatProjectionFace(new Identifier("erydon","glacium_cornice_georgian"),Direction.SOUTH,null,tag));
        assertEquals(Direction.SOUTH,SynapheiaRepeatBakedModel.repeatProjectionFace(new Identifier("minecraft","stone"),Direction.SOUTH,null,tag));
        assertFalse(CopingTexturePlane.isCornice(CopingTexturePlane.tag(Direction.UP)));
    }

    private static CorniceConnections.Segment segment(CorniceRun run,CorniceRun.Cut start,CorniceRun.Cut end,boolean joinedStart,boolean joinedEnd) {
        return new CorniceConnections.Segment(run,start,end,joinedStart,joinedEnd);
    }
    private static boolean samePosition(int[] data,int a,int b) {
        return data[a*8]==data[b*8] && data[a*8+1]==data[b*8+1] && data[a*8+2]==data[b*8+2];
    }
    private static BakedQuad quad(float[][] positions) {
        int[] data=new int[32];
        for(int vertex=0;vertex<4;vertex++) {
            int index=vertex*8;
            for(int axis=0;axis<3;axis++) data[index+axis]=Float.floatToRawIntBits(positions[vertex][axis]);
            data[index+3]=-1;
            data[index+4]=Float.floatToRawIntBits(vertex==1 || vertex==2 ? 1 : 0);
            data[index+5]=Float.floatToRawIntBits(vertex>=2 ? 1 : 0);
        }
        return new BakedQuad(data,-1,Direction.SOUTH,null,true);
    }
}
