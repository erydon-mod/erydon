package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.client.pom.ColumnMeshNormalFormat;
import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex;
import net.irisshaders.iris.vertices.NormI8;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Actual Iris terrain bytes for the clipped mesh, including both Sodium orders and a large atlas. */
public final class ColumnMeshTangentLaunchChecks {
    private ColumnMeshTangentLaunchChecks() { }
    private record Frame(int tangent,BlockPos owner) { }

    public static void run() throws Exception {
        require(ColumnMeshNormalFormat.supported()&&ColumnMeshNormalFormat.supportsTangents(),"Actual Iris normal/tangent fixture needs the pinned native layout");
        XHFPTerrainVertex encoder=new XHFPTerrainVertex();
        BlockContextHolder holder=new BlockContextHolder(new Object2IntOpenHashMap<BlockState>());
        encoder.iris$setContextHolder(holder);
        Material material=new Material(null,AlphaCutoffParameter.ZERO,true);
        long memory=MemoryUtil.nmemAlloc(160);
        long[] totals={0,0,0,0,0,0,0,0};double[] worst={0,0,0,0};
        try {
            for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
                DoubleColumnMeshGeometry.Template template;
                try(var reader=new InputStreamReader(ColumnMeshTangentLaunchChecks.class.getResourceAsStream(
                        "/assets/erydon/meshes/large_columns/"+suffix+".json"),StandardCharsets.UTF_8)) {
                    template=DoubleColumnMeshGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject());
                }
                boolean shaft=suffix.equals("pillar");
                for(int atlas:new int[]{256,16384})for(int offset:new int[]{0,15}) {
                    Map<String,Frame> seen=new HashMap<>(),rawSeen=new HashMap<>();
                    for(int y=0;y<(shaft?3:2);y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                        BlockPos owner=new BlockPos(offset+x,offset+y,offset+z);
                        template.cell(IndigoRenderer.INSTANCE,shaft,shaft?0:y,x,z).mesh().forEach(quad->{
                            var plane=SynapheiaRepeatBakedModel.repeatProjectionFace(new net.minecraft.util.Identifier("erydon","aganite_column_circular_double"),
                                    quad.lightFace(),quad.nominalFace(),quad.tag());
                            var repeat=atlas==16384?SynapheiaCellGeometry.singleCell(plane,quad):null;
                            require(atlas!=16384||repeat!=null,"Actual cell mesh no longer fits the Synapheia single-cell path");
                            for(ModelQuadOrientation order:ModelQuadOrientation.values()) {
                                ChunkVertexEncoder.Vertex[] vertices=new ChunkVertexEncoder.Vertex[4];
                                for(int i=0;i<4;i++) {
                                    int source=order.getVertexIndex(i);var vertex=vertices[i]=new ChunkVertexEncoder.Vertex();
                                    vertex.x=(owner.getX()&15)+quad.x(source);vertex.y=(owner.getY()&15)+quad.y(source);vertex.z=(owner.getZ()&15)+quad.z(source);
                                    float u=repeat==null?quad.u(source):SynapheiaCellGeometry.u(plane,quad,source,repeat);
                                    float v=repeat==null?quad.v(source):SynapheiaCellGeometry.v(plane,quad,source,repeat);
                                    vertex.u=.75F+u*64F/atlas;vertex.v=.5F+v*64F/atlas;
                                    vertex.color=-1;vertex.light=0x00F00000;
                                }
                                holder.localPosX=owner.getX()&15;holder.localPosY=owner.getY()&15;holder.localPosZ=owner.getZ()&15;
                                require(!ColumnMeshNormalTransport.hasScope(),"A previous encoded frame leaked its scope");
                                require(encoder.write(memory,material,vertices,0)==memory+160,"Raw tangent negative control changed stride");
                                int[] rawTangents=new int[4];for(int i=0;i<4;i++)rawTangents[i]=MemoryUtil.memGetInt(memory+i*40L+24);
                                byte[] originalBytes=new byte[160];MemoryUtil.memByteBuffer(memory,160).get(originalBytes);
                                int previous=ColumnMeshNormalTransport.push(quad,vertices,order.getVertexIndex(0),order.getVertexIndex(1),order.getVertexIndex(2),order.getVertexIndex(3));
                                try {
                                    require(encoder.write(memory,material,vertices,0)==memory+160,"Actual Iris vertex stride changed");
                                    for(int offsetByte=0;offsetByte<160;offsetByte++) {
                                        int field=offsetByte%40;if(field>=24&&field<32)continue;
                                        require(MemoryUtil.memGetByte(memory+offsetByte)==originalBytes[offsetByte],"Projection frame changed a non-frame Iris payload byte");
                                    }
                                    for(int i=0;i<4;i++) {
                                        int source=order.getVertexIndex(i);
                                        int normal=MemoryUtil.memGetInt(memory+i*40L+28),tangent=MemoryUtil.memGetInt(memory+i*40L+24);
                                        require(normal==NormI8.pack(quad.normalX(source),quad.normalY(source),quad.normalZ(source),0),"Actual mesh lost its shared shader normal");
                                        double normalLength=length(normal),tangentLength=length(tangent);
                                        totals[0]++;
                                        if(tangentLength<.5){totals[1]++;continue;}
                                        require(tangentLength>=127-Math.sqrt(3)&&tangentLength<=127.000001,"Projected shader tangent is not unit within signed-byte precision");
                                        require(Math.abs((byte)(tangent>>>24))==127,"Projected tangent lost its handedness");
                                        worst[0]=Math.max(worst[0],Math.abs(dot(normal,tangent))/(normalLength*tangentLength));
                                        double wx=owner.getX()+quad.x(source),wy=owner.getY()+quad.y(source),wz=owner.getZ()+quad.z(source);
                                        String key=Math.round(wx*1000000)+":"+Math.round(wy*1000000)+":"+Math.round(wz*1000000)+":"+normal+":"+quad.tag();
                                        int raw=rawTangents[i];double rawLength=length(raw);
                                        if(rawLength<.5)totals[4]++;
                                        else {
                                            double rawDot=Math.abs(dot(normal,raw))/(normalLength*rawLength);worst[2]=Math.max(worst[2],rawDot);
                                            if(rawDot>.05)totals[7]++;
                                            Frame old=rawSeen.putIfAbsent(key,new Frame(raw,owner));
                                            if(old!=null&&!old.owner.equals(owner)) {
                                                totals[5]++;double rawCosine=dot(old.tangent,raw)/(length(old.tangent)*rawLength);
                                                double rawAngle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,rawCosine))));
                                                worst[3]=Math.max(worst[3],rawAngle);if(rawAngle>1)totals[6]++;
                                            }
                                        }
                                        Frame before=seen.putIfAbsent(key,new Frame(tangent,owner));
                                        if(before!=null&&!before.owner.equals(owner)) {
                                            totals[2]++;double cosine=dot(before.tangent,tangent)/(length(before.tangent)*tangentLength);
                                            double angle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,cosine))));
                                            worst[1]=Math.max(worst[1],angle);if(before.tangent!=tangent)totals[3]++;
                                        }
                                    }
                                } finally {ColumnMeshNormalTransport.restore(previous);}
                            }
                        });
                    }
                }
            }
        } finally {MemoryUtil.nmemFree(memory);}
        System.out.println("ERYDON_COLUMN_TANGENT_FRAME_OK vertices="+totals[0]+" zeroTangents="+totals[1]+" shared="+totals[2]
                +" sharedFrameDifferences="+totals[3]+" maxAbsNormalTangentDot="+worst[0]+" maxSharedTangentAngle="+worst[1]
                +" rawZeroTangents="+totals[4]+" rawSharedAngleOver1Degree="+totals[6]+" rawMaxAbsNormalTangentDot="+worst[2]+" rawMaxSharedAngle="+worst[3]
                +" actualIrisEncoder=true orders=NORMAL/FLIP atlas=256-native/16384-Synapheia chunkOffsets=0/15");
        require(totals[0]==297504&&totals[2]>100,"Actual mesh tangent controls did not exercise the full frozen mesh matrix");
        require(totals[1]==0&&totals[3]==0,"Actual projected column tangents contain zero vectors or shared-frame flips");
        require(worst[0]<=Math.sqrt(3)/(127-Math.sqrt(3))+1.e-6,"Encoded tangent/normal is not orthogonal within signed-byte quantization");
        require(totals[4]>0&&totals[6]>0&&worst[2]>.05,"Original per-triangle tangent negative controls stopped exposing the defect");
    }

    private static double length(int packed){return Math.sqrt(dot(packed,packed));}
    private static double dot(int a,int b){return(byte)a*(double)(byte)b+(byte)(a>>>8)*(double)(byte)(b>>>8)+(byte)(a>>>16)*(double)(byte)(b>>>16);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
