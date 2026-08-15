package com.oliver.erydon.state;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ClusterMigrationState extends PersistentState {

    private static final String DATA_KEY = "erydon_cluster_migration";
    private static final String VERSION_TAG = "version";
    private static final String CHUNKS_TAG = "processed_chunks";

    private int version;
    private final Map<String, Set<Long>> processedChunksByDimension = new HashMap<>();

    public static ClusterMigrationState get(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        return overworld.getPersistentStateManager().getOrCreate(
                ClusterMigrationState::fromNbt,
                ClusterMigrationState::new,
                DATA_KEY
        );
    }

    public static ClusterMigrationState fromNbt(NbtCompound nbt) {
        ClusterMigrationState state = new ClusterMigrationState();
        state.version = nbt.getInt(VERSION_TAG);

        NbtCompound processedChunks = nbt.getCompound(CHUNKS_TAG);
        for (String dimension : processedChunks.getKeys()) {
            long[] encodedChunks = processedChunks.getLongArray(dimension);
            if (encodedChunks.length == 0) {
                continue;
            }

            Set<Long> chunks = new HashSet<>(encodedChunks.length);
            for (long encodedChunk : encodedChunks) {
                chunks.add(encodedChunk);
            }
            state.processedChunksByDimension.put(dimension, chunks);
        }

        return state;
    }

    public boolean ensureVersion(int targetVersion) {
        if (version == targetVersion) {
            return false;
        }

        version = targetVersion;
        processedChunksByDimension.clear();
        markDirty();
        return true;
    }

    public boolean isChunkProcessed(ServerWorld world, ChunkPos chunkPos) {
        Set<Long> processedChunks = processedChunksByDimension.get(dimensionKey(world));
        return processedChunks != null && processedChunks.contains(chunkPos.toLong());
    }

    public void markChunkProcessed(ServerWorld world, ChunkPos chunkPos) {
        Set<Long> processedChunks = processedChunksByDimension.computeIfAbsent(
                dimensionKey(world),
                ignored -> new HashSet<>()
        );

        if (processedChunks.add(chunkPos.toLong())) {
            markDirty();
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt(VERSION_TAG, version);

        NbtCompound processedChunks = new NbtCompound();
        for (Map.Entry<String, Set<Long>> entry : new TreeMap<>(processedChunksByDimension).entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }

            long[] encodedChunks = entry.getValue().stream()
                    .mapToLong(Long::longValue)
                    .sorted()
                    .toArray();
            processedChunks.putLongArray(entry.getKey(), encodedChunks);
        }

        nbt.put(CHUNKS_TAG, processedChunks);
        return nbt;
    }

    private static String dimensionKey(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }
}
