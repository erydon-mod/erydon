package com.oliver.erydon.client.profile;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Test-only helper for naming a copied development save used by the benchmark harness. */
public final class SharedGeometryBenchmarkWorldNameTool {
    private SharedGeometryBenchmarkWorldNameTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected <copied-world-directory> <benchmark-world-name>.");
        }
        Path worldDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        String benchmarkName = args[1];
        if (!Files.isDirectory(worldDirectory)) {
            throw new IllegalArgumentException("Copied benchmark world does not exist: " + worldDirectory);
        }
        if (!"Erydon Shared Geometry Benchmark".equals(benchmarkName)) {
            throw new IllegalArgumentException("Refusing unexpected benchmark world name: " + benchmarkName);
        }

        rewriteLevelName(worldDirectory.resolve("level.dat"), benchmarkName);
        Path oldLevel = worldDirectory.resolve("level.dat_old");
        if (Files.isRegularFile(oldLevel)) {
            rewriteLevelName(oldLevel, benchmarkName);
        }
        verifyLevelName(worldDirectory.resolve("level.dat"), benchmarkName);
        System.out.println("Prepared benchmark world: " + worldDirectory + " (LevelName=" + benchmarkName + ")");
    }

    private static void rewriteLevelName(Path levelFile, String benchmarkName) throws Exception {
        NbtCompound root;
        try (InputStream input = Files.newInputStream(levelFile)) {
            root = NbtIo.readCompressed(input);
        }
        NbtCompound data = root.getCompound("Data");
        data.putString("LevelName", benchmarkName);

        Path temporaryFile = levelFile.resolveSibling(levelFile.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporaryFile)) {
            NbtIo.writeCompressed(root, output);
        }
        Files.move(temporaryFile, levelFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void verifyLevelName(Path levelFile, String benchmarkName) throws Exception {
        NbtCompound root;
        try (InputStream input = Files.newInputStream(levelFile)) {
            root = NbtIo.readCompressed(input);
        }
        String actualName = root.getCompound("Data").getString("LevelName");
        if (!benchmarkName.equals(actualName)) {
            throw new IllegalStateException(
                    "Benchmark world name readback failed: expected '" + benchmarkName
                            + "', found '" + actualName + "'.");
        }
    }
}
