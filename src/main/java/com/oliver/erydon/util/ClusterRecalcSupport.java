package com.oliver.erydon.util;

import com.oliver.erydon.block.ClusterRebuildableBlock;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

public final class ClusterRecalcSupport {

    private ClusterRecalcSupport() {
    }

    public static ScanOutcome scanBox(ServerWorld world, Box box) {
        return scanBoxes(world, Set.of(box));
    }

    public static ScanOutcome scanChunk(ServerWorld world, ChunkPos chunkPos) {
        WorldChunk chunk = loadedChunk(world, chunkPos.x, chunkPos.z);
        return chunk == null ? ScanOutcome.oneUnloadedChunk() : scanChunk(world, chunk);
    }

    public static ScanOutcome scanChunk(ServerWorld world, WorldChunk chunk) {
        Set<BlockPos> processed = new HashSet<>();
        MutableOutcome total = new MutableOutcome();
        scanLoadedChunk(world, chunk, null, processed, total);
        return total.toOutcome();
    }

    public static ScanOutcome scanBoxes(ServerWorld world, Iterable<Box> boxes) {
        Set<BlockPos> processed = new HashSet<>();
        ScanOutcome total = ScanOutcome.EMPTY;

        for (Box box : boxes) {
            total = total.merge(scanBox(world, box, processed));
        }

        return total;
    }

    public static StagedScan stagedBox(ServerWorld world, Box box) {
        return new StagedScan(world, box.clampY(world));
    }

    public static ScanOutcome recalcAt(ServerWorld world, BlockPos pos, Set<BlockPos> processed) {
        if (processed.contains(pos)) {
            return ScanOutcome.EMPTY;
        }

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ClusterRebuildableBlock rebuildable)) {
            return ScanOutcome.EMPTY;
        }

        ClusterRebuildableBlock.ClusterRecalcResult result = ClusterRecalcSafety.run(
                world,
                () -> rebuildable.recalcCluster(world, pos)
        );
        if (result.positions().isEmpty()) {
            return ScanOutcome.EMPTY;
        }

        processed.addAll(result.positions());
        int rebuilt = result.status() == ClusterRebuildableBlock.RecalcStatus.RECALCULATED ? 1 : 0;
        int manualLocked = result.status() == ClusterRebuildableBlock.RecalcStatus.MANUAL_LOCKED ? 1 : 0;
        int unloaded = result.status() == ClusterRebuildableBlock.RecalcStatus.UNLOADED_EDGE ? 1 : 0;
        int oversized = result.status() == ClusterRebuildableBlock.RecalcStatus.TOO_LARGE ? 1 : 0;
        return new ScanOutcome(
                1,
                rebuilt,
                manualLocked,
                unloaded,
                oversized,
                0,
                result.positions().size()
        );
    }

    private static ScanOutcome scanBox(ServerWorld world, Box requestedBox, Set<BlockPos> processed) {
        Box box = requestedBox.clampY(world);
        if (box.isEmpty()) {
            return ScanOutcome.EMPTY;
        }

        MutableOutcome total = new MutableOutcome();
        int minChunkX = Math.floorDiv(box.minX(), 16);
        int maxChunkX = Math.floorDiv(box.maxX(), 16);
        int minChunkZ = Math.floorDiv(box.minZ(), 16);
        int maxChunkZ = Math.floorDiv(box.maxZ(), 16);

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                WorldChunk chunk = loadedChunk(world, chunkX, chunkZ);
                if (chunk != null) {
                    scanLoadedChunk(world, chunk, box, processed, total);
                } else {
                    total.skipUnloadedChunk();
                }
            }
        }
        return total.toOutcome();
    }

    private static void scanLoadedChunk(ServerWorld world, WorldChunk chunk, Box bounds,
                                        Set<BlockPos> processed, MutableOutcome total) {
        // WorldChunk performs a section palette pre-check before visiting cells,
        // which makes large mostly-vanilla areas cheap to reject.
        chunk.forEachBlockMatchingPredicate(
                state -> state.getBlock() instanceof ClusterRebuildableBlock,
                (pos, state) -> {
                    if ((bounds == null || bounds.contains(pos)) && !processed.contains(pos)) {
                        total.add(recalcAt(world, pos.toImmutable(), processed));
                    }
                }
        );
    }

    private static WorldChunk loadedChunk(ServerWorld world, int chunkX, int chunkZ) {
        var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk instanceof WorldChunk worldChunk ? worldChunk : null;
    }

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public Box expand(int radius) {
            return new Box(
                    minX - radius,
                    minY - radius,
                    minZ - radius,
                    maxX + radius,
                    maxY + radius,
                    maxZ + radius
            );
        }

        public Box clampY(ServerWorld world) {
            return new Box(
                    minX,
                    Math.max(minY, world.getBottomY()),
                    minZ,
                    maxX,
                    Math.min(maxY, world.getTopY() - 1),
                    maxZ
            );
        }

        public boolean isEmpty() {
            return minX > maxX || minY > maxY || minZ > maxZ;
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    public record ScanOutcome(int seenClusters, int rebuiltClusters, int manualLockedClusters,
                              int unloadedEdgeClusters, int oversizedClusters,
                              int unloadedChunks, int touchedBlocks) {
        public static final ScanOutcome EMPTY = new ScanOutcome(0, 0, 0, 0, 0, 0, 0);

        public static ScanOutcome oneUnloadedChunk() {
            return new ScanOutcome(0, 0, 0, 0, 0, 1, 0);
        }

        public int skippedClusters() {
            return manualLockedClusters + unloadedEdgeClusters + oversizedClusters;
        }

        public ScanOutcome merge(ScanOutcome other) {
            return new ScanOutcome(
                    seenClusters + other.seenClusters,
                    rebuiltClusters + other.rebuiltClusters,
                    manualLockedClusters + other.manualLockedClusters,
                    unloadedEdgeClusters + other.unloadedEdgeClusters,
                    oversizedClusters + other.oversizedClusters,
                    unloadedChunks + other.unloadedChunks,
                    touchedBlocks + other.touchedBlocks
            );
        }
    }

    private static final class MutableOutcome {
        private int seenClusters;
        private int rebuiltClusters;
        private int manualLockedClusters;
        private int unloadedEdgeClusters;
        private int oversizedClusters;
        private int unloadedChunks;
        private int touchedBlocks;

        private void add(ScanOutcome outcome) {
            seenClusters += outcome.seenClusters();
            rebuiltClusters += outcome.rebuiltClusters();
            manualLockedClusters += outcome.manualLockedClusters();
            unloadedEdgeClusters += outcome.unloadedEdgeClusters();
            oversizedClusters += outcome.oversizedClusters();
            unloadedChunks += outcome.unloadedChunks();
            touchedBlocks += outcome.touchedBlocks();
        }

        private void skipUnloadedChunk() {
            unloadedChunks++;
        }

        private ScanOutcome toOutcome() {
            return new ScanOutcome(seenClusters, rebuiltClusters, manualLockedClusters,
                    unloadedEdgeClusters, oversizedClusters, unloadedChunks, touchedBlocks);
        }
    }

    /** Incremental form used by commands so a large configured radius cannot monopolise one tick. */
    public static final class StagedScan {
        private final ServerWorld world;
        private final Box bounds;
        private final Set<BlockPos> processed = new HashSet<>();
        private final LongArrayFIFOQueue pendingChunks = new LongArrayFIFOQueue();
        private final LongArrayFIFOQueue candidates = new LongArrayFIFOQueue();
        private final MutableOutcome total = new MutableOutcome();
        private final int totalChunks;
        private int scannedChunks;

        private StagedScan(ServerWorld world, Box bounds) {
            this.world = world;
            this.bounds = bounds;
            if (bounds.isEmpty()) {
                totalChunks = 0;
                return;
            }

            int minChunkX = Math.floorDiv(bounds.minX(), 16);
            int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
            int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
            int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    pendingChunks.enqueue(ChunkPos.toLong(chunkX, chunkZ));
                }
            }
            totalChunks = pendingChunks.size();
        }

        public void advance(int chunkBudget, int candidateBudget) {
            int chunks = 0;
            while (candidates.isEmpty() && chunks < chunkBudget && !pendingChunks.isEmpty()) {
                long chunkKey = pendingChunks.dequeueLong();
                scannedChunks++;
                chunks++;
                WorldChunk chunk = loadedChunk(world, ChunkPos.getPackedX(chunkKey), ChunkPos.getPackedZ(chunkKey));
                if (chunk == null) {
                    total.skipUnloadedChunk();
                    continue;
                }
                chunk.forEachBlockMatchingPredicate(
                        state -> state.getBlock() instanceof ClusterRebuildableBlock,
                        (pos, state) -> {
                            if (bounds.contains(pos) && !processed.contains(pos)) {
                                candidates.enqueue(pos.asLong());
                            }
                        }
                );
            }

            int handled = 0;
            int clusters = 0;
            while (handled < candidateBudget && !candidates.isEmpty()) {
                BlockPos pos = BlockPos.fromLong(candidates.dequeueLong());
                if (!processed.contains(pos) && world.isChunkLoaded(pos)) {
                    ScanOutcome outcome = recalcAt(world, pos, processed);
                    total.add(outcome);
                    if (outcome.seenClusters() > 0 && ++clusters >= 1) {
                        break;
                    }
                }
                handled++;
            }
        }

        public boolean isComplete() {
            return pendingChunks.isEmpty() && candidates.isEmpty();
        }

        public int scannedChunks() {
            return scannedChunks;
        }

        public int totalChunks() {
            return totalChunks;
        }

        public int pendingCandidates() {
            return candidates.size();
        }

        public ScanOutcome outcome() {
            return total.toOutcome();
        }
    }
}
