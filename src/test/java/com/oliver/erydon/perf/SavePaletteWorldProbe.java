package com.oliver.erydon.perf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saves and reloads a disposable real chunk with a global block-state palette. */
public final class SavePaletteWorldProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(SavePaletteWorldProbe::run);
    }

    private static void run(MinecraftServer server) {
        try {
            int registrySize = Block.STATE_IDS.size();
            if (registrySize <= 4096) throw new AssertionError("Block registry did not exceed section capacity");
            WorldChunk chunk = server.getOverworld().getChunk(0, 0);
            ChunkSection section = chunk.getSection(chunk.getSectionIndex(80));
            boolean verify = Boolean.getBoolean("erydon.perf.save_palette_world_verify");
            for (int index = 0; index < 257; index++) {
                int x = index % 16;
                int y = (80 + index / 256) & 15;
                int z = index / 16 % 16;
                BlockState expected = Block.STATE_IDS.get(index);
                if (expected == null) throw new AssertionError("Missing block state " + index);
                if (verify) {
                    BlockState actual = section.getBlockState(x, y, z);
                    if (actual != expected) {
                        throw new AssertionError("Saved block state " + index + " reloaded as " + actual);
                    }
                } else {
                    section.setBlockState(x, y, z, expected);
                }
            }
            if (!verify) {
                chunk.setNeedsSaving(true);
                server.saveAll(false, true, true);
            }
            Files.writeString(Path.of(verify ? "verified.txt" : "saved.txt"),
                    "states=257 registry=" + registrySize + "\n");
            System.out.println("ERYDON_SAVE_PALETTE_WORLD_OK: verify=" + verify
                    + " states=257 registry=" + registrySize);
            server.stop(false);
        } catch (Throwable failure) {
            failure.printStackTrace();
            server.stop(false);
            throw new AssertionError("Disposable world save/reload failed", failure);
        }
    }
}
