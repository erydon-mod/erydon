package com.oliver.erydon.util;

import com.oliver.erydon.block.ClusterRebuildableBlock;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.BlockView;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Safety boundary used only by explicit admin recalculation scans.
 * Normal placement and neighbour-update paths run without an active context.
 */
public final class ClusterRecalcSafety {
    public static final int MAX_CLUSTER_BLOCKS = 512;
    public static final int MAX_LAYOUT_CELLS = 16_384;
    public static final int MAX_PENDANT_BLOCKS = 64;
    public static final int MAX_SPIRAL_LAYERS = 64;

    private static final int READ_HALO = 2;
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private ClusterRecalcSafety() {
    }

    public static <T> T run(ServerWorld world, Supplier<T> action) {
        Context previous = ACTIVE.get();
        Context context = new Context(world);
        ACTIVE.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    /** Reads a state only if its chunk is already loaded while a recalc scan is active. */
    public static BlockState getBlockState(BlockView world, BlockPos pos) {
        Context context = ACTIVE.get();
        if (context != null && world == context.world && !context.world.isChunkLoaded(pos)) {
            context.fail(ClusterRebuildableBlock.RecalcStatus.UNLOADED_EDGE);
            return Blocks.VOID_AIR.getDefaultState();
        }
        return world.getBlockState(pos);
    }

    /** Counts a discovered member and fails before a pathological component can grow further. */
    public static boolean claim(BlockPos pos) {
        Context context = ACTIVE.get();
        if (context == null) {
            return true;
        }
        if (context.claimed.contains(pos)) {
            return true;
        }
        if (context.claimed.size() >= MAX_CLUSTER_BLOCKS) {
            context.fail(ClusterRebuildableBlock.RecalcStatus.TOO_LARGE);
            return false;
        }
        context.claimed.add(pos.toImmutable());
        return true;
    }

    public static void markTooLarge() {
        Context context = ACTIVE.get();
        if (context != null) {
            context.fail(ClusterRebuildableBlock.RecalcStatus.TOO_LARGE);
        }
    }

    public static void requireLayoutArea(long cells) {
        if (cells > MAX_LAYOUT_CELLS) {
            markTooLarge();
        }
    }

    /**
     * Completes loaded-neighbour preflight after discovery. Returning non-null means
     * the caller must return it immediately, before changing any blocks.
     */
    public static ClusterRebuildableBlock.ClusterRecalcResult unsafeResult(Set<BlockPos> positions) {
        Context context = ACTIVE.get();
        if (context == null) {
            return null;
        }

        if (context.failure == null) {
            LongOpenHashSet requiredChunks = new LongOpenHashSet();
            for (BlockPos pos : positions) {
                int minChunkX = Math.floorDiv(pos.getX() - READ_HALO, 16);
                int maxChunkX = Math.floorDiv(pos.getX() + READ_HALO, 16);
                int minChunkZ = Math.floorDiv(pos.getZ() - READ_HALO, 16);
                int maxChunkZ = Math.floorDiv(pos.getZ() + READ_HALO, 16);
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        requiredChunks.add(ChunkPos.toLong(chunkX, chunkZ));
                    }
                }
            }
            LongIterator chunks = requiredChunks.iterator();
            while (chunks.hasNext()) {
                long chunkKey = chunks.nextLong();
                BlockPos probe = new BlockPos(
                        ChunkPos.getPackedX(chunkKey) << 4,
                        context.world.getBottomY(),
                        ChunkPos.getPackedZ(chunkKey) << 4
                );
                if (!context.world.isChunkLoaded(probe)) {
                    context.fail(ClusterRebuildableBlock.RecalcStatus.UNLOADED_EDGE);
                    break;
                }
            }
        }

        if (context.failure == null) {
            return null;
        }

        Set<BlockPos> reported = new LinkedHashSet<>(context.claimed);
        reported.addAll(positions);
        return new ClusterRebuildableBlock.ClusterRecalcResult(reported, context.failure);
    }

    /** Suppress neighbour callbacks during a scan; all family members are rebuilt explicitly. */
    public static int updateFlags(int normalFlags) {
        return isActive() ? Block.NOTIFY_LISTENERS : normalFlags;
    }

    private static final class Context {
        private final ServerWorld world;
        private final Set<BlockPos> claimed = new LinkedHashSet<>();
        private ClusterRebuildableBlock.RecalcStatus failure;

        private Context(ServerWorld world) {
            this.world = world;
        }

        private void fail(ClusterRebuildableBlock.RecalcStatus reason) {
            if (failure == null) {
                failure = reason;
            }
        }
    }
}
