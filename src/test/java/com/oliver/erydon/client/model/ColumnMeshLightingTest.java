package com.oliver.erydon.client.model;

import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMeshLightingTest {
    @AfterEach void clear() { ColumnMeshLighting.restore(0); }

    @Test void noWorldAndForeignOrNonFlatQuadsNeverQueryTheWorld() {
        assertEquals(-1, ColumnMeshLighting.push(null, BlockPos.ORIGIN));
        assertEquals(-1, ColumnMeshLighting.next());
        assertFalse(ColumnMeshLighting.hasScope());
        int depth = ColumnMeshLighting.push(noQueries(), BlockPos.ORIGIN);
        try {
            for (int tag : new int[]{0, 0x0DAE0000, ColumnMeshNormalTransport.tag() | 7}) {
                QuadView quad = quad(tag, -1, 0);
                ColumnMeshLighting.beginQuad(quad, true);
                for (int vertex = 0; vertex < 4; vertex++) assertEquals(-1, ColumnMeshLighting.next());
                ColumnMeshLighting.endQuad(quad);
            }
            QuadView tagged = quad(ColumnMeshNormalTransport.tag(), -1, 0);
            ColumnMeshLighting.beginQuad(tagged, false);
            assertEquals(-1, ColumnMeshLighting.next());
            ColumnMeshLighting.endQuad(tagged);
            assertEquals(0, ColumnMeshLighting.sampleCount());
        } finally { ColumnMeshLighting.restore(depth); }
    }

    @Test void invalidLastCornerRejectsTheWholeQuadBeforeItsFirstVertex() {
        int depth = ColumnMeshLighting.push(noQueries(), BlockPos.ORIGIN);
        try {
            for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -.5001f, 1.5f}) {
                QuadView quad = quad(ColumnMeshNormalTransport.tag(), 3, bad);
                ColumnMeshLighting.beginQuad(quad, true);
                for (int vertex = 0; vertex < 4; vertex++) assertEquals(-1, ColumnMeshLighting.next());
                ColumnMeshLighting.endQuad(quad);
            }
            assertEquals(0, ColumnMeshLighting.sampleCount());
        } finally { ColumnMeshLighting.restore(depth); }
    }

    @Test void nestedNullWorldAndOtherThreadsCannotBorrowTheOuterEmission() throws Exception {
        int outer = ColumnMeshLighting.push(noQueries(), new BlockPos(-17, 2, 15));
        try {
            assertTrue(ColumnMeshLighting.hasScope());
            assertFalse(CompletableFuture.supplyAsync(ColumnMeshLighting::hasScope).get());
            int inner = ColumnMeshLighting.push(null, BlockPos.ORIGIN);
            try {
                assertFalse(ColumnMeshLighting.hasScope());
                assertEquals(-1, ColumnMeshLighting.next());
            } finally { ColumnMeshLighting.restore(inner); }
            assertTrue(ColumnMeshLighting.hasScope());
            assertEquals(0, ColumnMeshLighting.sampleCount());
        } finally { ColumnMeshLighting.restore(outer); }
        assertFalse(ColumnMeshLighting.hasScope());
    }

    @Test void helperLoadsWithoutAnyOptionalRenderer() throws Exception {
        String helper = ColumnMeshLighting.class.getName();
        ClassLoader isolated = new ClassLoader(ColumnMeshLighting.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(helper) || name.startsWith(helper + "$")) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) try (var stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    if (name.startsWith("net.irisshaders.") || name.startsWith("link.infra.indium.")
                            || name.startsWith("me.jellysquid.") || name.startsWith("net.fabricmc.fabric.impl.client.indigo."))
                        throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            }
        };
        Class<?> loaded = Class.forName(helper, true, isolated);
        int depth = (int) loaded.getMethod("push", BlockRenderView.class, BlockPos.class).invoke(null, noQueries(), BlockPos.ORIGIN);
        try { assertEquals(Boolean.TRUE, loaded.getMethod("hasScope").invoke(null)); }
        finally { loaded.getMethod("restore", int.class).invoke(null, depth); }
        assertEquals(Boolean.FALSE, loaded.getMethod("hasScope").invoke(null));
    }

    private static BlockRenderView noQueries() {
        // A dynamic proxy eagerly initializes every reflected return/argument type, including
        // BlockEntityType's global registries. These guards deliberately need no Minecraft bootstrap.
        return new BlockRenderView() {
            @Override public float getBrightness(Direction direction, boolean shaded) { throw query("getBrightness"); }
            @Override public LightingProvider getLightingProvider() { throw query("getLightingProvider"); }
            @Override public int getColor(BlockPos pos, ColorResolver resolver) { throw query("getColor"); }
            @Override public int getLightLevel(LightType type, BlockPos pos) { throw query("getLightLevel"); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { throw query("getBlockEntity"); }
            @Override public BlockState getBlockState(BlockPos pos) { throw query("getBlockState"); }
            @Override public FluidState getFluidState(BlockPos pos) { throw query("getFluidState"); }
            @Override public int getHeight() { throw query("getHeight"); }
            @Override public int getBottomY() { throw query("getBottomY"); }
        };
    }

    private static AssertionError query(String method) { return new AssertionError("Unexpected world query " + method); }

    private static QuadView quad(int tag, int badVertex, float bad) {
        return (QuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[]{QuadView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tag" -> tag;
                    case "x", "y", "z" -> (int) args[0] == badVertex ? bad : .25f;
                    default -> throw new AssertionError("Unexpected quad query " + method.getName());
                });
    }
}
