package com.oliver.erydon.client.pom;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex;
import net.irisshaders.iris.vertices.NormI8;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Direction;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Actual Fabric-woven renderer hooks, with no Minecraft window or OpenGL context. */
public final class ColumnMeshNormalLaunchChecks {
    private static final float[][] NORMALS = {{-.6f, 0, -.8f}, {0, .6f, -.8f}, {.6f, 0, -.8f}, {0, -.6f, -.8f}};
    private ColumnMeshNormalLaunchChecks() { }

    public static void run() throws Exception {
        // Transformation must validate the real WrapOperation signature even though this check never renders terrain.
        Class.forName("link.infra.indium.renderer.render.TerrainRenderContext", false,
                ColumnMeshNormalLaunchChecks.class.getClassLoader());
        require(ColumnMeshNormalFormat.supported(), "Pinned normal layout guard rejected the actual renderer");
        require(ColumnMeshNormalFormat.supportsTangents(), "Pinned tangent layout guard rejected the actual renderer");
        require(InlaySubstrateTransport.rendererSupported(), "Pinned renderer versions must enable the woven hooks");
        BlockState part = Blocks.GLOWSTONE.getDefaultState();
        Object2IntOpenHashMap<BlockState> ids = new Object2IntOpenHashMap<>(); ids.put(part, 654);
        BlockContextHolder holder = new BlockContextHolder(ids);
        holder.blockId = 321; holder.renderType = -1; holder.lightValue = 37;
        XHFPTerrainVertex encoder = new XHFPTerrainVertex(); encoder.iris$setContextHolder(holder);
        Material material = new Material(null, AlphaCutoffParameter.ZERO, true);
        long memory = MemoryUtil.nmemAlloc(160);
        int cases = 0;
        try {
            flatProjectionCalibration(encoder, memory, material);
            for (ModelQuadOrientation order : ModelQuadOrientation.values()) {
                ChunkVertexEncoder.Vertex[] vertices = vertices(order);
                byte[] baseline = write(encoder, memory, material, vertices);
                require(MemoryUtil.memGetShort(memory + 32) == 321 && MemoryUtil.memGetShort(memory + 34) == -1
                        && MemoryUtil.memGetByte(memory + 39) == 37, "Iris context payload fixture is invalid");
                int previous;
                for (int tag : columnTags()) {
                    previous = push(quad(tag), vertices, order);
                    try {
                        byte[] actual = write(encoder, memory, material, vertices);
                        unchanged(baseline, actual, false, tangentFrame(tag));
                        normals(memory, order);
                        if (tangentFrame(tag)) for (int vertex=0;vertex<4;vertex++) {
                            float[] normal=NORMALS[order.getVertexIndex(vertex)];
                            require(MemoryUtil.memGetInt(memory+vertex*40L+24)==ColumnMeshTangentFrame.pack(
                                    ColumnMeshNormalTransport.projectionFace(tag),NormI8.pack(normal[0],normal[1],normal[2],0)),
                                    "Woven tangent order/packing differs from the projection frame");
                        }
                        require(!Arrays.equals(baseline, actual), "Actual woven Iris return hook did not override column tag " + tag);
                    } finally { ColumnMeshNormalTransport.restore(previous); }
                }
                require(!ColumnMeshNormalTransport.hasScope(), "Successful write leaked a normal scope");
                for (int tag : new int[]{0, 0x0DAE0000, 0x0DAEFFFF, 0x0DAF0000, 0x434E4300}) {
                    previous = push(quad(tag), vertices, order);
                    try { require(Arrays.equals(baseline, write(encoder, memory, material, vertices)), "Foreign draw changed: " + tag); }
                    finally { ColumnMeshNormalTransport.restore(previous); }
                }
                BlockState oldPart = CornicePartMaterialTransport.push(part);
                previous = push(quad(ColumnMeshNormalTransport.tag(Direction.EAST)), vertices, order);
                try {
                    unchanged(baseline, write(encoder, memory, material, vertices), true);
                    normals(memory, order);
                    for (int vertex = 0; vertex < 4; vertex++) require(MemoryUtil.memGetShort(memory + vertex * 40L + 32) == 654
                                    && MemoryUtil.memGetShort(memory + vertex * 40L + 34) == -1
                                    && MemoryUtil.memGetByte(memory + vertex * 40L + 39) == 15,
                            "Column normals must coexist with the existing cornice material/luminance payload");
                } finally {
                    ColumnMeshNormalTransport.restore(previous);
                    CornicePartMaterialTransport.restore(oldPart);
                }
                previous = push(quad(ColumnMeshNormalTransport.tag(Direction.WEST)), vertices, order);
                boolean failed = false;
                try { encoder.write(memory, null, vertices, 7); }
                catch (NullPointerException expected) { failed = true; }
                finally { ColumnMeshNormalTransport.restore(previous); }
                require(failed && !ColumnMeshNormalTransport.hasScope(), "Exceptional encoder exit leaked a payload");
                require(Arrays.equals(baseline, write(encoder, memory, material, vertices)), "Failed column draw contaminated the next ordinary draw");
                cases++;
            }
        } finally { MemoryUtil.nmemFree(memory); }
        System.out.println("ERYDON_COLUMN_NORMAL_WOVEN_OK orders=" + cases
                + " columnTags=7 nativeStride=40 tangentOffset=24 flatDirectionCalibrations=12 normalOffset=28 foreignTags=5 material32=preserved renderType34=preserved luminance39=preserved exceptionCleanup=pass");
    }

    private static boolean tangentFrame(int tag) {
        Direction plane=ColumnMeshNormalTransport.projectionFace(tag);
        if(plane==null)return false;
        for(float[] n:NORMALS)if(ColumnMeshTangentFrame.pack(plane,NormI8.pack(n[0],n[1],n[2],0))==0)return false;
        return true;
    }

    /** Byte-for-byte handedness calibration against the actual pinned Iris writer, not a duplicated sign formula. */
    private static void flatProjectionCalibration(XHFPTerrainVertex encoder,long memory,Material material) {
        for(Direction face:Direction.values())for(ModelQuadOrientation order:ModelQuadOrientation.values()) {
            float[][] points=switch(face) {
                case UP->new float[][]{{0,1,0},{0,1,1},{1,1,1},{1,1,0}};
                case DOWN->new float[][]{{0,0,1},{0,0,0},{1,0,0},{1,0,1}};
                case NORTH->new float[][]{{1,1,0},{1,0,0},{0,0,0},{0,1,0}};
                case SOUTH->new float[][]{{0,1,1},{0,0,1},{1,0,1},{1,1,1}};
                case WEST->new float[][]{{0,1,0},{0,0,0},{0,0,1},{0,1,1}};
                case EAST->new float[][]{{1,1,1},{1,0,1},{1,0,0},{1,1,0}};
            };
            float[][] normals=new float[4][3];ChunkVertexEncoder.Vertex[] vertices=new ChunkVertexEncoder.Vertex[4];
            for(int i=0;i<4;i++) {
                normals[i][0]=face.getOffsetX();normals[i][1]=face.getOffsetY();normals[i][2]=face.getOffsetZ();
                float[] p=points[order.getVertexIndex(i)];var v=vertices[i]=new ChunkVertexEncoder.Vertex();
                v.x=p[0];v.y=p[1];v.z=p[2];v.color=-1;v.light=0x00F000A0;
                v.u=switch(face){case NORTH->1-v.x;case SOUTH,UP,DOWN->v.x;case WEST->v.z;case EAST->1-v.z;};
                v.v=switch(face){case UP->v.z;case DOWN->1-v.z;default->1-v.y;};
            }
            byte[] original=write(encoder,memory,material,vertices);
            int previous=push(quad(ColumnMeshNormalTransport.tag(face),normals),vertices,order);
            try {require(Arrays.equals(original,write(encoder,memory,material,vertices)),
                    "Projection frame changed the actual flat Iris axis/handedness/payload: "+face+" "+order);}
            finally {ColumnMeshNormalTransport.restore(previous);}
        }
    }

    private static int[] columnTags() {
        int[] tags = new int[7]; tags[0] = ColumnMeshNormalTransport.tag();
        for (Direction face : Direction.values()) {
            int tag = ColumnMeshNormalTransport.tag(face);
            require(ColumnMeshNormalTransport.projectionFace(tag) == face, "Column source plane did not round-trip");
            tags[face.getId() + 1] = tag;
        }
        return tags;
    }

    private static byte[] write(XHFPTerrainVertex encoder, long memory, Material material, ChunkVertexEncoder.Vertex[] vertices) {
        MemoryUtil.memSet(memory, 0xA7, 160);
        require(encoder.write(memory, material, vertices, 7) == memory + 160, "Actual Iris writer changed stride");
        byte[] data = new byte[160]; MemoryUtil.memByteBuffer(memory, 160).get(data); return data;
    }

    private static void unchanged(byte[] before, byte[] after, boolean part) {
        unchanged(before, after, part, false);
    }

    private static void unchanged(byte[] before, byte[] after, boolean part, boolean tangent) {
        for (int offset = 0; offset < 160; offset++) {
            int field = offset % 40;
            if (field >= 28 && field < 32 || tangent && field >= 24 && field < 28
                    || part && (field == 32 || field == 33 || field == 39)) continue;
            require(before[offset] == after[offset], "Woven column hook changed unrelated byte " + offset);
        }
    }

    private static void normals(long memory, ModelQuadOrientation order) {
        for (int vertex = 0; vertex < 4; vertex++) {
            float[] n = NORMALS[order.getVertexIndex(vertex)];
            require(MemoryUtil.memGetInt(memory + vertex * 40L + 28) == NormI8.pack(n[0], n[1], n[2], 0),
                    "Woven normal order/packing differs at " + vertex + "/" + order);
        }
    }

    private static int push(QuadView quad, Object vertices, ModelQuadOrientation order) {
        return ColumnMeshNormalTransport.push(quad, vertices, order.getVertexIndex(0), order.getVertexIndex(1),
                order.getVertexIndex(2), order.getVertexIndex(3));
    }

    private static ChunkVertexEncoder.Vertex[] vertices(ModelQuadOrientation order) {
        float[][] corners = {{0, 0, 0, 0, 1}, {0, 1, 0, 0, 0}, {1, 1, 0, 1, 0}, {1, 0, 0, 1, 1}};
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[4];
        for (int i = 0; i < 4; i++) {
            float[] p = corners[order.getVertexIndex(i)];
            var vertex = vertices[i] = new ChunkVertexEncoder.Vertex();
            vertex.x = p[0]; vertex.y = p[1]; vertex.z = p[2]; vertex.u = p[3]; vertex.v = p[4];
            vertex.color = -1; vertex.light = 0x00F000A0;
        }
        return vertices;
    }

    private static QuadView quad(int tag) { return quad(tag, NORMALS); }

    private static QuadView quad(int tag, float[][] normals) {
        return (QuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[]{QuadView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tag" -> tag;
                    case "hasNormal" -> true;
                    case "normalX" -> normals[(int) args[0]][0];
                    case "normalY" -> normals[(int) args[0]][1];
                    case "normalZ" -> normals[(int) args[0]][2];
                    default -> throw new AssertionError("Unexpected normal fixture access: " + method.getName());
                });
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
