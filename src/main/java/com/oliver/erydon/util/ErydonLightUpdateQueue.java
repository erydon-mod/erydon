package com.oliver.erydon.util;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.block.BrazierBlock;
import com.oliver.erydon.block.CeilingBlock;
import com.oliver.erydon.block.LightBlock;
import com.oliver.erydon.block.LightPendantBlock;
import com.oliver.erydon.block.LightPendantHaloBlock;
import com.oliver.erydon.block.OilBurnerBlock;
import com.oliver.erydon.block.WallLightBlock;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rechecks dynamic ERYDON light sources without producing one long server tick.
 * Chunk palette filtering avoids visiting individual cells in sections that do
 * not contain a configured light family.
 */
public final class ErydonLightUpdateQueue {
    private static final int MODERN_LIGHTS = 1;
    private static final int WALL_LIGHTS = 1 << 1;
    private static final int PENDANT_LIGHTS = 1 << 2;
    private static final int BRAZIERS = 1 << 3;
    private static final int OIL_BURNERS = 1 << 4;
    private static final int COFFERED_CEILINGS = 1 << 5;
    private static final int ALL_LIGHT_FAMILIES = (1 << 6) - 1;
    private static final int CHUNKS_SCANNED_PER_TICK = 2;
    private static final int LIGHTS_CHECKED_PER_TICK = 512;
    private static final int STALE_CHUNKS_DISCARDED_PER_TICK = 64;
    private static final int STALE_LIGHTS_DISCARDED_PER_TICK = 2_048;
    private static final Map<ServerWorld, WorldWork> WORK_BY_WORLD = new HashMap<>();
    private static boolean registered;

    private ErydonLightUpdateQueue() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            WorldWork work = work(world);
            long chunkKey = chunk.getPos().toLong();
            work.loadedChunks.add(chunkKey);
            // Saved light arrays may reflect an older server-config value.
            // The palette-filtered scan is cheap for chunks without ERYDON
            // lights and guarantees newly loaded chunks converge.
            work.queueChunk(chunkKey, ALL_LIGHT_FAMILIES);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            WorldWork work = work(world);
            long chunkKey = chunk.getPos().toLong();
            work.loadedChunks.remove(chunkKey);
            work.queuedChunkMasks.remove(chunkKey);
        });
        ServerTickEvents.END_WORLD_TICK.register(ErydonLightUpdateQueue::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WORK_BY_WORLD.clear());
    }

    public static void queueChangedLoadedChunks(
            MinecraftServer server,
            ErydonConfig.ServerSnapshot previous,
            ErydonConfig.ServerSnapshot current
    ) {
        int familyMask = changedFamilyMask(previous, current);
        if (familyMask == 0) {
            return;
        }

        int queued = 0;
        for (ServerWorld world : server.getWorlds()) {
            WorldWork work = work(world);
            if (!work.loadedChunks.isEmpty()) {
                work.announceCompletion = true;
            }
            for (long chunkKey : work.loadedChunks) {
                if (work.queueChunk(chunkKey, familyMask)) {
                    queued++;
                }
            }
        }
        Erydon.LOGGER.info("[{}] Queued {} loaded chunk{} for staged light refresh.",
                Erydon.MOD_ID, queued, queued == 1 ? "" : "s");
    }

    private static void tick(ServerWorld world) {
        WorldWork work = work(world);
        int chunksScanned = 0;
        int staleChunks = 0;
        while (chunksScanned < CHUNKS_SCANNED_PER_TICK
                && staleChunks < STALE_CHUNKS_DISCARDED_PER_TICK
                && !work.pendingChunks.isEmpty()) {
            long chunkKey = work.pendingChunks.dequeueLong();
            Integer familyMask = work.queuedChunkMasks.remove(chunkKey);
            if (familyMask == null) {
                staleChunks++;
                continue;
            }

            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            WorldChunk chunk = loadedChunk(world, chunkX, chunkZ);
            if (chunk == null) {
                staleChunks++;
                continue;
            }
            chunksScanned++;
            chunk.forEachBlockMatchingPredicate(
                    state -> isConfiguredLight(state, familyMask),
                    (pos, state) -> work.pendingLights.enqueue(pos.asLong())
            );
        }

        int lightsChecked = 0;
        int staleLights = 0;
        while (lightsChecked < LIGHTS_CHECKED_PER_TICK
                && staleLights < STALE_LIGHTS_DISCARDED_PER_TICK
                && !work.pendingLights.isEmpty()) {
            BlockPos pos = BlockPos.fromLong(work.pendingLights.dequeueLong());
            if (world.isChunkLoaded(pos)) {
                world.getChunkManager().getLightingProvider().checkBlock(pos);
                lightsChecked++;
            } else {
                staleLights++;
            }
        }

        if (work.announceCompletion && work.pendingChunks.isEmpty() && work.pendingLights.isEmpty()) {
            work.announceCompletion = false;
            Erydon.LOGGER.info("[{}] Finished staged light refresh in {}.",
                    Erydon.MOD_ID, world.getRegistryKey().getValue());
        }
    }

    private static boolean isConfiguredLight(BlockState state, int familyMask) {
        Block block = state.getBlock();
        if (block instanceof WallLightBlock) {
            return (familyMask & WALL_LIGHTS) != 0 && state.get(LightBlock.LIT);
        }
        if (block instanceof LightBlock) {
            return (familyMask & MODERN_LIGHTS) != 0 && state.get(LightBlock.LIT);
        }
        if (block instanceof LightPendantBlock) {
            return (familyMask & PENDANT_LIGHTS) != 0 && state.get(LightPendantBlock.LIT);
        }
        if (block instanceof LightPendantHaloBlock) {
            return (familyMask & PENDANT_LIGHTS) != 0 && state.get(LightPendantHaloBlock.LIT);
        }
        if (block instanceof BrazierBlock) {
            return (familyMask & BRAZIERS) != 0;
        }
        if (block instanceof OilBurnerBlock) {
            return (familyMask & OIL_BURNERS) != 0;
        }
        return block instanceof CeilingBlock
                && (familyMask & COFFERED_CEILINGS) != 0
                && state.get(CeilingBlock.LIGHT) != CeilingBlock.CeilingLight.NONE;
    }

    private static int changedFamilyMask(
            ErydonConfig.ServerSnapshot previous,
            ErydonConfig.ServerSnapshot current
    ) {
        int mask = 0;
        if (previous.modernLightLevel() != current.modernLightLevel()) {
            mask |= MODERN_LIGHTS;
        }
        if (previous.wallLightLevel() != current.wallLightLevel()) {
            mask |= WALL_LIGHTS;
        }
        if (previous.pendantLightLevel() != current.pendantLightLevel()) {
            mask |= PENDANT_LIGHTS;
        }
        if (previous.brazierLightLevel() != current.brazierLightLevel()) {
            mask |= BRAZIERS;
        }
        if (previous.oilBurnerLightLevel() != current.oilBurnerLightLevel()) {
            mask |= OIL_BURNERS;
        }
        if (previous.cofferedCeilingLightLevel() != current.cofferedCeilingLightLevel()) {
            mask |= COFFERED_CEILINGS;
        }
        return mask;
    }

    private static WorldChunk loadedChunk(ServerWorld world, int chunkX, int chunkZ) {
        var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk instanceof WorldChunk worldChunk ? worldChunk : null;
    }

    private static WorldWork work(ServerWorld world) {
        return WORK_BY_WORLD.computeIfAbsent(world, ignored -> new WorldWork());
    }

    private static final class WorldWork {
        private final Set<Long> loadedChunks = new HashSet<>();
        private final Map<Long, Integer> queuedChunkMasks = new HashMap<>();
        private final LongArrayFIFOQueue pendingChunks = new LongArrayFIFOQueue();
        private final LongArrayFIFOQueue pendingLights = new LongArrayFIFOQueue();
        private boolean announceCompletion;

        private boolean queueChunk(long chunkKey, int familyMask) {
            Integer queuedMask = queuedChunkMasks.get(chunkKey);
            if (queuedMask != null) {
                queuedChunkMasks.put(chunkKey, queuedMask | familyMask);
                return false;
            }
            queuedChunkMasks.put(chunkKey, familyMask);
            pendingChunks.enqueue(chunkKey);
            return true;
        }
    }
}
