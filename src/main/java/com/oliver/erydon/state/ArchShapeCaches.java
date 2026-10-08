package com.oliver.erydon.state;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.ArchModernBlock;
import com.oliver.erydon.block.ArchGothicBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minecraft's immutable collision/light metadata can be shared by identical arch geometry. */
public final class ArchShapeCaches {
    private static final Map<Key, Object> CACHE = new ConcurrentHashMap<>();
    private static final MethodHandle CONSTRUCTOR = constructor();

    private ArchShapeCaches() { }

    // These exact classes inherit vanilla opacity/transparency: outline, fluid and opaque
    // fully determine the light fields. Do not recompute those geometric queries per state.
    private record Key(Class<?> family, VoxelShape collision, VoxelShape sides, VoxelShape culling,
                       VoxelShape outline, FluidState fluid, boolean opaque, boolean modelOffset) { }

    public static Object create(BlockState state) {
        Block block = state.getBlock();
        Class<?> family = block.getClass();
        if (family != ArchRomanesqueBlock.class && family != ArchModernBlock.class && family != ArchGothicBlock.class)
            return construct(state);
        var view = EmptyBlockView.INSTANCE;
        var pos = BlockPos.ORIGIN;
        Key key = new Key(family, block.getCollisionShape(state, view, pos, net.minecraft.block.ShapeContext.absent()),
                state.getSidesShape(view, pos), block.getCullingShape(state, view, pos),
                state.getOutlineShape(view, pos), state.getFluidState(), state.isOpaque(),
                state.hasModelOffset());
        return CACHE.computeIfAbsent(key, ignored -> construct(state));
    }

    public static int size() { return CACHE.size(); }

    private static Object construct(BlockState state) {
        try { return CONSTRUCTOR.invokeExact(state); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot construct block shape cache", failure); }
    }

    private static MethodHandle constructor() {
        // Resolve by signature, avoiding hard-coded private names that differ between dev and production.
        for (Class<?> inner : AbstractBlock.AbstractBlockState.class.getDeclaredClasses()) {
            try {
                var constructor = inner.getDeclaredConstructor(BlockState.class);
                constructor.setAccessible(true);
                return MethodHandles.lookup().unreflectConstructor(constructor)
                        .asType(MethodType.methodType(Object.class, BlockState.class));
            } catch (NoSuchMethodException ignored) {
                // Other inner classes do not represent shape caches.
            } catch (IllegalAccessException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
        throw new ExceptionInInitializerError("Block shape cache constructor not found");
    }
}
