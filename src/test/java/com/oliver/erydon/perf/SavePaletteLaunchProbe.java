package com.oliver.erydon.perf;

import com.sun.management.ThreadMXBean;
import com.mojang.serialization.Codec;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.world.chunk.PalettedContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.lang.management.ManagementFactory;
import java.util.HexFormat;
import java.util.Iterator;

/** Exercises the actual Minecraft palette codec with and without the save-only cap. */
public final class SavePaletteLaunchProbe implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            StringBuilder results = new StringBuilder();
            run(results, "block-uniform", 16, 1, PalettedContainer.PaletteProvider.BLOCK_STATE);
            run(results, "block-local", 16, 4, PalettedContainer.PaletteProvider.BLOCK_STATE);
            run(results, "block-global", 16, 257, PalettedContainer.PaletteProvider.BLOCK_STATE);
            run(results, "biome-uniform", 9, 1, PalettedContainer.PaletteProvider.BIOME);
            run(results, "biome-global", 9, 64, PalettedContainer.PaletteProvider.BIOME);
            // The baseline allocates the reported 24-bit temporary table here.
            run(results, "block-24bit-4096", 24, 4096, PalettedContainer.PaletteProvider.BLOCK_STATE);
            Files.writeString(Path.of("palette-report.txt"), results, StandardCharsets.UTF_8);
            System.out.println("ERYDON_SAVE_PALETTE_OK: cap="
                    + Boolean.getBoolean("erydon.perf.save_palette_cap") + "\n" + results);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(StringBuilder results, String name, int registryBits, int distinct,
                            PalettedContainer.PaletteProvider provider) throws Exception {
        var ids = new SyntheticIds(registryBits, distinct);
        int edge = provider.getContainerSize() == 4096 ? 16 : 4;
        var container = new PalettedContainer<>(ids, ids.get(0), provider);
        for (int index = 0; index < provider.getContainerSize(); index++) {
            container.set(index % edge, index / edge % edge, index / (edge * edge),
                    ids.get(index % distinct));
        }
        Codec<PalettedContainer<Integer>> codec = PalettedContainer.createPalettedContainerCodec(
                ids, Codec.INT, provider, ids.get(0));
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        long before = allocations.getThreadAllocatedBytes(Thread.currentThread().getId());
        NbtElement encoded = codec.encodeStart(NbtOps.INSTANCE, container)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        long allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        if (name.equals("block-24bit-4096")) {
            boolean cap = Boolean.getBoolean("erydon.perf.save_palette_cap");
            if (cap && allocated > 32L * 1024 * 1024 || !cap && allocated < 200L * 1024 * 1024) {
                throw new AssertionError("Save-palette allocation did not match cap=" + cap + ": " + allocated);
            }
            System.out.println("ERYDON_SAVE_PALETTE_ALLOCATION: cap=" + cap + " bytes=" + allocated);
        }
        PalettedContainer<Integer> decoded = codec.parse(NbtOps.INSTANCE, encoded)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        for (int index = 0; index < provider.getContainerSize(); index++) {
            int actual = decoded.get(index % edge, index / edge % edge, index / (edge * edge));
            if (actual != index % distinct) {
                throw new AssertionError(name + " changed value at " + index + ": " + actual);
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(encoded.toString().getBytes(StandardCharsets.UTF_8));
        results.append(name).append('=').append(HexFormat.of().formatHex(digest)).append('\n');
    }

    private static final class SyntheticIds implements IndexedIterable<Integer> {
        private final int size;
        private final Integer[] values;

        private SyntheticIds(int bits, int distinct) {
            size = 1 << bits;
            values = new Integer[distinct];
            for (int index = 0; index < distinct; index++) values[index] = index;
        }

        @Override public int getRawId(Integer value) { return value; }
        @Override public Integer get(int id) { return id < values.length ? values[id] : Integer.valueOf(id); }
        @Override public int size() { return size; }
        @Override public Iterator<Integer> iterator() {
            return new Iterator<>() {
                private int next;
                @Override public boolean hasNext() { return next < size; }
                @Override public Integer next() { return get(next++); }
            };
        }
    }
}
