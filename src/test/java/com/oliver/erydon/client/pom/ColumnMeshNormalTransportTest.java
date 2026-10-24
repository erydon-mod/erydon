package com.oliver.erydon.client.pom;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.irisshaders.iris.vertices.NormI8;
import net.minecraft.util.math.Direction;
import com.oliver.erydon.mixin.client.compat.iris.InlaySubstrateVertexMixin;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMeshNormalTransportTest {
    private static final float[][] NORMALS = {{1, 0, 0}, {0, 1, 0}, {0, 0, -1}, {-.6f, 0, .8f}};

    @AfterEach void clear() { ColumnMeshNormalTransport.restore(0); }

    @Test void onlyTheBaseAndSixExactProjectionTagsAreOwned() {
        assertTrue(ColumnMeshNormalTransport.isColumn(ColumnMeshNormalTransport.tag()));
        assertNull(ColumnMeshNormalTransport.projectionFace(ColumnMeshNormalTransport.tag()));
        java.util.Set<Integer> owned = new java.util.HashSet<>(); owned.add(ColumnMeshNormalTransport.tag());
        for (Direction face : Direction.values()) {
            int tag = ColumnMeshNormalTransport.tag(face);
            assertTrue(owned.add(tag), "Source planes must have distinct tags");
            assertTrue(ColumnMeshNormalTransport.isColumn(tag));
            assertEquals(face, ColumnMeshNormalTransport.projectionFace(tag));
        }
        assertEquals(7, owned.size());
        for (int payload = 0; payload <= 65535; payload++) {
            int candidate = ColumnMeshNormalTransport.tag() | payload;
            assertEquals(owned.contains(candidate), ColumnMeshNormalTransport.isColumn(candidate));
            if (!owned.contains(candidate)) assertNull(ColumnMeshNormalTransport.projectionFace(candidate));
        }
        for (int tag : new int[]{0, -1, 0x0DAE0000, 0x0DAEFFFF, 0x0DAF0000,
                0x43504C00, 0x434E4300, ColumnMeshNormalTransport.tag() | 7,
                ColumnMeshNormalTransport.tag() | 8, ColumnMeshNormalTransport.tag() | 0x10000000}) {
            assertFalse(ColumnMeshNormalTransport.isColumn(tag));
            assertNull(ColumnMeshNormalTransport.projectionFace(tag));
        }
    }

    @Test void ordinaryFabricCanLoadTheMeshTagWithoutOptionalRendererClasses() throws Exception {
        String helper = ColumnMeshNormalTransport.class.getName();
        ClassLoader noOptionalRenderer = new ClassLoader(ColumnMeshNormalTransport.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(helper)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) try (var stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes(); loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    if (name.startsWith("net.irisshaders.") || name.startsWith("link.infra.indium.")
                            || name.startsWith("me.jellysquid.")) throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            }
        };
        Class<?> tagOwner = Class.forName(helper, true, noOptionalRenderer);
        int tag = (int) tagOwner.getMethod("tag").invoke(null);
        assertEquals(ColumnMeshNormalTransport.tag(), tag);
        assertEquals(Boolean.TRUE, tagOwner.getMethod("isColumn", int.class).invoke(null, tag));
        assertEquals(Boolean.FALSE, tagOwner.getMethod("isColumn", int.class).invoke(null, 0x0DAE0000));
    }

    @Test void packedComponentsMatchTheActualPinnedIrisCodec() {
        for (int angle = 0; angle < 360; angle++) {
            float x = (float) Math.cos(Math.toRadians(angle));
            float z = (float) Math.sin(Math.toRadians(angle));
            assertEquals(NormI8.pack(x, 0, z, 0), ColumnMeshNormalTransport.pack(x, 0, z));
        }
        for (float[] normal : NORMALS) assertEquals(NormI8.pack(normal[0], normal[1], normal[2], 0),
                ColumnMeshNormalTransport.pack(normal[0], normal[1], normal[2]));
    }

    @Test void actualStagingOrderAndArrayIdentityControlOnlyTheNormalBytes() {
        Object[] vertices = new Object[4];
        long memory = MemoryUtil.nmemAlloc(192);
        try {
            MemoryUtil.memSet(memory, 0x5A, 192);
            int previous = begin(quad(ColumnMeshNormalTransport.tag(), NORMALS, true), vertices, true, 1, 2, 3, 0);
            try {
                assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 176, new Object[4]));
                assertTrue(ColumnMeshNormalTransport.writeNormals(memory + 176, vertices));
                assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 176, vertices), "A scope may encode only its one push");
                for (int index = 0; index < 192; index++) {
                    int local = index - 16;
                    boolean normalByte = local >= 0 && local < 160 && local % 40 >= 28 && local % 40 < 32;
                    if (!normalByte) assertEquals((byte) 0x5A, MemoryUtil.memGetByte(memory + index),
                            "Position/UV/tangent/inlay +32/+34/luminance +39 or guard byte changed at " + index);
                }
                for (int vertex = 0; vertex < 4; vertex++) {
                    float[] expected = NORMALS[(vertex + 1) % 4];
                    assertEquals(NormI8.pack(expected[0], expected[1], expected[2], 0),
                            MemoryUtil.memGetInt(memory + 16 + vertex * 40L + 28));
                }
            } finally { ColumnMeshNormalTransport.restore(previous); }
            assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 176, vertices));
        } finally { MemoryUtil.nmemFree(memory); }
    }

    @Test void nestedOrdinaryAndForeignDrawsCannotBorrowAnOuterColumnPayload() {
        Object[] vertices = new Object[4];
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            int outer = begin(quad(ColumnMeshNormalTransport.tag(), NORMALS, true), vertices, true, 0, 1, 2, 3);
            for (int tag : new int[]{0, 0x0DAE0000, 0x0DAF0000, 0x434E4300}) {
                int inner = begin(quad(tag, NORMALS, true), vertices, true, 0, 1, 2, 3);
                try { assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 160, vertices)); }
                finally { ColumnMeshNormalTransport.restore(inner); }
            }
            assertTrue(ColumnMeshNormalTransport.writeNormals(memory + 160, vertices));
            ColumnMeshNormalTransport.restore(outer);
        } finally { MemoryUtil.nmemFree(memory); }
    }

    @Test void incompleteInvalidOrUnsupportedNormalsKeepTheExistingEncoderOutput() {
        Object[] vertices = new Object[4];
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            for (float[] invalid : new float[][]{{0, 0, 0}, {2, 0, 0}, {Float.NaN, 0, 1}, {0, Float.POSITIVE_INFINITY, 0}}) {
                float[][] values = NORMALS.clone(); values[2] = invalid;
                int previous = begin(quad(ColumnMeshNormalTransport.tag(), values, true), vertices, true, 0, 1, 2, 3);
                try { assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 160, vertices)); }
                finally { ColumnMeshNormalTransport.restore(previous); }
            }
            for (boolean supported : new boolean[]{true, false}) {
                int previous = begin(quad(ColumnMeshNormalTransport.tag(), NORMALS, !supported), vertices, supported, 0, 1, 2, 3);
                try { assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 160, vertices)); }
                finally { ColumnMeshNormalTransport.restore(previous); }
            }
        } finally { MemoryUtil.nmemFree(memory); }
    }

    @Test void exceptionCleanupAndWorkerIsolationDoNotLeakStagedArrays() {
        Object[] vertices = new Object[4];
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            assertThrows(IllegalStateException.class, () -> {
                int previous = begin(quad(ColumnMeshNormalTransport.tag(), NORMALS, true), vertices, true, 0, 1, 2, 3);
                try {
                    assertFalse(CompletableFuture.supplyAsync(() -> ColumnMeshNormalTransport.writeNormals(memory + 160, vertices)).join());
                    throw new IllegalStateException("encoder failed");
                } finally { ColumnMeshNormalTransport.restore(previous); }
            });
            assertFalse(ColumnMeshNormalTransport.writeNormals(memory + 160, vertices));
        } finally { MemoryUtil.nmemFree(memory); }
    }

    @Test void existingIrisReturnHookWritesColumnNormalsWithoutTouchingForeignPayloads() throws Exception {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[4];
        var hook = InlaySubstrateVertexMixin.class.getDeclaredMethod("erydon$writeInlaySubstrate",
                long.class, long.class, Material.class, ChunkVertexEncoder.Vertex[].class, int.class);
        hook.setAccessible(true);
        InlaySubstrateVertexMixin writer = new InlaySubstrateVertexMixin() { };
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            for (int tag : new int[]{ColumnMeshNormalTransport.tag(),
                    ColumnMeshNormalTransport.tag(Direction.DOWN), ColumnMeshNormalTransport.tag(Direction.UP),
                    ColumnMeshNormalTransport.tag(Direction.NORTH), ColumnMeshNormalTransport.tag(Direction.SOUTH),
                    ColumnMeshNormalTransport.tag(Direction.WEST), ColumnMeshNormalTransport.tag(Direction.EAST),
                    0, 0x0DAE0000, 0x0DAF0000, 0x434E4300, ColumnMeshNormalTransport.tag() | 7, ColumnMeshNormalTransport.tag() | 8}) {
                MemoryUtil.memSet(memory, 0x36, 160);
                int previous = begin(quad(tag, NORMALS, true), vertices, true, 0, 1, 2, 3);
                try { assertEquals(memory + 160, hook.invoke(writer, memory + 160, memory, null, vertices, 0)); }
                finally { ColumnMeshNormalTransport.restore(previous); }
                for (int vertex = 0; vertex < 4; vertex++) for (int offset = 0; offset < 40; offset++) {
                    if (ColumnMeshNormalTransport.isColumn(tag) && offset >= 28 && offset < 32) continue;
                    assertEquals((byte) 0x36, MemoryUtil.memGetByte(memory + vertex * 40L + offset),
                            "Actual return hook changed an unrelated field/tag at " + offset);
                }
                if (ColumnMeshNormalTransport.isColumn(tag)) for (int vertex = 0; vertex < 4; vertex++) {
                    float[] normal = NORMALS[vertex];
                    assertEquals(NormI8.pack(normal[0], normal[1], normal[2], 0), MemoryUtil.memGetInt(memory + vertex * 40L + 28));
                }
            }
        } finally { MemoryUtil.nmemFree(memory); }
    }

    @Test void aThrowingQuadCaptureAlsoRestoresThePriorScope() {
        Object[] vertices = new Object[4];
        QuadView broken = (QuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[]{QuadView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("tag")) return ColumnMeshNormalTransport.tag();
                    throw new IllegalStateException("broken mesh");
                });
        assertThrows(IllegalStateException.class, () -> begin(broken, vertices, true, 0, 1, 2, 3));
        assertFalse(ColumnMeshNormalTransport.hasScope());
    }

    @Test void projectionFramesWriteOnlyTheirGuardedTangentAndNormalFields() {
        long memory=MemoryUtil.nmemAlloc(160);Object[] vertices=new Object[4];
        try {
            for(Direction face:Direction.values())for(boolean tangentSupported:new boolean[]{false,true}) {
                float[][] normals=new float[4][3];
                for(float[] normal:normals){normal[0]=face.getOffsetX();normal[1]=face.getOffsetY();normal[2]=face.getOffsetZ();}
                MemoryUtil.memSet(memory,0xA7,160);
                int previous=ColumnMeshNormalTransport.push(quad(ColumnMeshNormalTransport.tag(face),normals,true),vertices,0,1,2,3,true,tangentSupported);
                try {assertTrue(ColumnMeshNormalTransport.writeNormals(memory+160,vertices));}
                finally {ColumnMeshNormalTransport.restore(previous);}
                int packed=NormI8.pack(normals[0][0],normals[0][1],normals[0][2],0);
                for(int i=0;i<4;i++) {
                    assertEquals(packed,MemoryUtil.memGetInt(memory+i*40L+28));
                    assertEquals(tangentSupported?ColumnMeshTangentFrame.pack(face,packed):0xA7A7A7A7,MemoryUtil.memGetInt(memory+i*40L+24));
                }
                for(int offset=0;offset<160;offset++) {
                    int field=offset%40;if(field>=28&&field<32||tangentSupported&&field>=24&&field<28)continue;
                    assertEquals((byte)0xA7,MemoryUtil.memGetByte(memory+offset),"Projection frame overwrote unrelated payload byte "+offset);
                }
            }
        } finally {MemoryUtil.nmemFree(memory);}
    }

    @Test void bareTagsForeignTagsAndSingularOrUnverifiedFramesKeepTheOriginalTangent() {
        long memory=MemoryUtil.nmemAlloc(160);Object[] vertices=new Object[4];
        float[][] north={{0,0,-1},{0,0,-1},{0,0,-1},{0,0,-1}};
        try {
            for(int tag:new int[]{ColumnMeshNormalTransport.tag(),0,0x0DAE0000,ColumnMeshNormalTransport.tag()|7}) {
                MemoryUtil.memSet(memory,0xA7,160);
                int previous=ColumnMeshNormalTransport.push(quad(tag,north,true),vertices,0,1,2,3,true,true);
                try {assertEquals(ColumnMeshNormalTransport.isColumn(tag),ColumnMeshNormalTransport.writeNormals(memory+160,vertices));}
                finally {ColumnMeshNormalTransport.restore(previous);}
                for(int i=0;i<4;i++)assertEquals(0xA7A7A7A7,MemoryUtil.memGetInt(memory+i*40L+24));
            }
            // One invalid corner rejects the entire tangent update while valid normal transport remains available.
            float[][] singular={{0,0,-1},{0,0,-1},{1,0,0},{0,0,-1}};
            MemoryUtil.memSet(memory,0xA7,160);
            int previous=ColumnMeshNormalTransport.push(quad(ColumnMeshNormalTransport.tag(Direction.NORTH),singular,true),vertices,0,1,2,3,true,true);
            try {assertTrue(ColumnMeshNormalTransport.writeNormals(memory+160,vertices));}
            finally {ColumnMeshNormalTransport.restore(previous);}
            for(int i=0;i<4;i++)assertEquals(0xA7A7A7A7,MemoryUtil.memGetInt(memory+i*40L+24));
        } finally {MemoryUtil.nmemFree(memory);}
    }

    private static int begin(QuadView quad, Object vertices, boolean supported, int a, int b, int c, int d) {
        return ColumnMeshNormalTransport.push(quad, vertices, a, b, c, d, supported);
    }

    private static QuadView quad(int tag, float[][] normals, boolean complete) {
        return (QuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[]{QuadView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tag" -> tag;
                    case "hasNormal" -> complete || (int) args[0] != 2;
                    case "normalX" -> normals[(int) args[0]][0];
                    case "normalY" -> normals[(int) args[0]][1];
                    case "normalZ" -> normals[(int) args[0]][2];
                    default -> throw new AssertionError("Unexpected quad access: " + method.getName());
                });
    }
}
