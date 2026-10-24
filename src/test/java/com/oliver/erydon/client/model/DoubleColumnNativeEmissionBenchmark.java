package com.oliver.erydon.client.model;

import com.sun.management.ThreadMXBean;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.minecraft.client.texture.Sprite;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Measures native mesh replay only: excludes CTM, world lighting, terrain encoding and GPU draws. */
final class DoubleColumnNativeEmissionBenchmark {
    private static volatile long blackhole;
    private DoubleColumnNativeEmissionBenchmark() { }

    static void run(Map<String,DoubleColumnMeshGeometry.Template> templates, Sprite sprite) {
        var renderer=RendererAccess.INSTANCE.getRenderer();
        long allAtlasPayload=0;
        for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
            List<Mesh> shared=new ArrayList<>(),atlas=new ArrayList<>();
            var geometry=templates.get(suffix);
            // Include both the ordinary one-high shaft and its two-high NONE-capital use.
            for(boolean shaft:new boolean[]{false,true}) {
                if(shaft&&!suffix.equals("pillar"))continue;
                for(int layer=0;layer<(shaft?1:2);layer++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                    Mesh original=geometry.cell(renderer,shaft,layer,x,z).mesh();
                    var builder=renderer.meshBuilder();QuadEmitter out=builder.getEmitter();
                    original.forEach(quad->{out.copyFrom(quad).spriteBake(sprite,MutableQuadView.BAKE_NORMALIZED).emit();});
                    Mesh textured=builder.build();
                    shared.add(original);atlas.add(textured);
                }
            }
            ReplayEmitter sourceReceiver=new ReplayEmitter(sprite,true),atlasReceiver=new ReplayEmitter(sprite,false);
            replay(shared,sourceReceiver,1);replay(atlas,atlasReceiver,1);
            if(sourceReceiver.checksum!=atlasReceiver.checksum||sourceReceiver.quads!=atlasReceiver.quads)
                throw new AssertionError("Native atlas-cache candidate changed encoded quad replay: "+suffix);
            long payload=sourceReceiver.quads*EncodingFormat.TOTAL_STRIDE*Integer.BYTES;
            allAtlasPayload+=payload;
            var joinedBuilder=renderer.meshBuilder();QuadEmitter joinedOut=joinedBuilder.getEmitter();
            for(Mesh cell:atlas)cell.forEach(quad->joinedOut.copyFrom(quad).emit());
            List<Mesh> joined=List.of(joinedBuilder.build());
            ReplayEmitter joinedReceiver=new ReplayEmitter(sprite,false);
            replay(joined,joinedReceiver,1);
            if(joinedReceiver.checksum!=atlasReceiver.checksum||joinedReceiver.quads!=atlasReceiver.quads)
                throw new AssertionError("Joined replay control changed encoded quads: "+suffix);
            for(int i=0;i<4;i++){replay(shared,sourceReceiver,100);replay(atlas,atlasReceiver,100);}
            for(int i=0;i<4;i++)replay(joined,joinedReceiver,100);
            long[] sourceNs=new long[7],atlasNs=new long[7],joinedNs=new long[7];
            long[] sourceBytes=new long[7],atlasBytes=new long[7],joinedBytes=new long[7];
            ThreadMXBean allocation=allocationBean();
            for(int trial=0;trial<7;trial++) {
                // Alternate order to avoid a consistent warmup/clock advantage.
                if((trial&1)==0) {
                    measure(shared,sourceReceiver,allocation,sourceNs,sourceBytes,trial);
                    measure(atlas,atlasReceiver,allocation,atlasNs,atlasBytes,trial);
                    measure(joined,joinedReceiver,allocation,joinedNs,joinedBytes,trial);
                } else {
                    measure(joined,joinedReceiver,allocation,joinedNs,joinedBytes,trial);
                    measure(atlas,atlasReceiver,allocation,atlasNs,atlasBytes,trial);
                    measure(shared,sourceReceiver,allocation,sourceNs,sourceBytes,trial);
                }
            }
            System.out.println("ERYDON_LARGE_COLUMN_NATIVE_REPLAY style="+suffix
                    +" sharedSpriteBakeNs="+median(sourceNs)+" atlasMeshNs="+median(atlasNs)
                    +" sharedAllocatedBytes="+median(sourceBytes)+" atlasAllocatedBytes="+median(atlasBytes)
                    +" joinedMeshNs="+median(joinedNs)+" joinedAllocatedBytes="+median(joinedBytes)
                    +" cachedCells="+shared.size()+" atlasPayloadBytesPerMaterial="+payload
                    +" nativeReplayOnly=true ctmLightingEncodingMeasured=false frameRateMeasured=false");
        }
        System.out.println("ERYDON_LARGE_COLUMN_ATLAS_CACHE_COST bytesPerMaterial="+allAtlasPayload
                +" bytesFor54Materials="+(allAtlasPayload*54)+" encodingIntStride="+EncodingFormat.TOTAL_STRIDE
                +" objectHeadersAndRendererCachesExcluded=true");
    }

    private static void replay(List<Mesh> meshes,ReplayEmitter receiver,int count) {
        for(int i=0;i<count;i++)for(Mesh mesh:meshes)mesh.outputTo(receiver);
        blackhole=receiver.checksum;
    }
    private static void measure(List<Mesh> meshes,ReplayEmitter receiver,ThreadMXBean allocation,
                                long[] ns,long[] bytes,int trial) {
        long thread=Thread.currentThread().getId();
        long allocated=allocation==null?0:allocation.getThreadAllocatedBytes(thread);
        long started=System.nanoTime();
        replay(meshes,receiver,100);
        ns[trial]=(System.nanoTime()-started)/100;
        bytes[trial]=allocation==null?-1:(allocation.getThreadAllocatedBytes(thread)-allocated)/100;
    }
    private static long median(long[] values){Arrays.sort(values);return values[values.length/2];}
    private static ThreadMXBean allocationBean() {
        if(!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)||!bean.isThreadAllocatedMemorySupported())return null;
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
    /** Real Indigo mutable emitter; reuses its buffer and never allocates an output mesh per replay. */
    private static final class ReplayEmitter extends MutableQuadViewImpl {
        private final Sprite sprite;
        private final boolean bakeSprite;
        private long checksum,quads;
        ReplayEmitter(Sprite sprite,boolean bakeSprite) {
            this.sprite=sprite;this.bakeSprite=bakeSprite;
            data=new int[EncodingFormat.TOTAL_STRIDE];clear();
        }
        @Override public void emitDirectly() {
            if(bakeSprite)spriteBake(sprite,MutableQuadView.BAKE_NORMALIZED);
            // Read the complete encoded quad, including positions, UVs, normals, tags and material.
            for(int value:data)checksum=checksum*31+value;
            quads++;
        }
    }
}
