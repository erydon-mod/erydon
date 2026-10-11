package com.oliver.erydon.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** All methods run on the IO worker. Output locations are fixed children of the game directory. */
final class ResultStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    final Path game, root, requests, results;

    ResultStore(Path gameDirectory) throws IOException {
        game = gameDirectory.toRealPath();
        root = directory(game.resolve("erydon-frame-recorder"));
        requests = directory(root.resolve("requests"));
        results = directory(root.resolve("results"));
    }

    private static Path directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("Recorder directories must not be redirected links");
        return path;
    }

    List<Path> pending() throws IOException {
        try (var paths = Files.list(requests)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> {
                        String id = path.getFileName().toString().replaceFirst("\\.json$", "");
                        return CaptureRequest.safeId(id) && !Files.exists(results.resolve(id), LinkOption.NOFOLLOW_LINKS);
                    })
                    .sorted().limit(16).toList();
        }
    }

    Path reserve(Path request) throws IOException {
        String filename = request.getFileName().toString();
        String id = filename.substring(0, filename.length() - 5);
        if (!CaptureRequest.safeId(id) || !request.getParent().equals(requests))
            throw new IOException("Unsafe request filename");
        Path output = results.resolve(id);
        Files.createDirectory(output); // Atomic claim. Never overwrite a previous result or follow a link.
        try {
            Files.move(request, output.resolve("request.json"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            // Keep the reservation; a failed claim must not be retried as a second run.
            atomicJson(output.resolve("status.json"), Map.of("id", id, "state", "INVALID", "reason", "Request claim failed"));
            throw error;
        }
        return output;
    }

    CaptureRequest readRequest(Path output) throws IOException {
        Path input = output.resolve("request.json");
        if (Files.size(input) > 4096) throw new IOException("Request exceeds 4096 bytes");
        return CaptureRequest.parse(Files.readString(input), output.getFileName() + ".json");
    }

    static void atomicJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(temporary, JSON.toJson(value), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    static void frames(Path output, long[] intervals) throws IOException {
        Path temporary = output.resolve("frames.csv.pending");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("frame_index,frame_ms\n");
            for (int i = 0; i < intervals.length; i++) {
                if (intervals[i] <= 0) throw new IOException("Invalid non-positive frame interval");
                writer.write(Integer.toString(i));
                writer.write(',');
                writer.write(String.format(Locale.ROOT, "%.6f", intervals[i] / 1_000_000.0));
                writer.write('\n');
            }
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        Files.move(temporary, output.resolve("frames.csv"), StandardCopyOption.ATOMIC_MOVE);
    }

    Map<String, Object> files(List<String> enabledPacks) throws Exception {
        var inventory = new TreeMap<String, Object>();
        addFile(inventory, game.resolve("options.txt"));
        Path mods = game.resolve("mods");
        if (Files.isDirectory(mods)) try (var list = Files.list(mods)) {
            for (Path path : list.filter(item -> item.getFileName().toString().endsWith(".jar")).sorted().toList()) addFile(inventory, path);
        }
        Path config = game.resolve("config");
        for (String name : List.of("iris.properties", "sodium-options.json", "sodium-mixins.properties", "indium-renderer.properties",
                "continuity.json", "entity_model_features.json", "entity_texture_features.json", "entityculling.json", "erydon-client.properties"))
            addFile(inventory, config.resolve(name));
        for (String pack : enabledPacks) {
            if (pack.startsWith("file/")) addTree(inventory, child(game.resolve("resourcepacks"), pack.substring(5)));
        }
        Path iris = config.resolve("iris.properties");
        if (Files.isRegularFile(iris)) {
            var properties = new java.util.Properties();
            try (var input = Files.newInputStream(iris)) { properties.load(input); }
            String shader = properties.getProperty("shaderPack", "");
            if (!shader.isBlank()) {
                Path pack = child(game.resolve("shaderpacks"), shader);
                addTree(inventory, pack);
                addFile(inventory, pack.resolveSibling(pack.getFileName() + ".txt"));
            }
        }
        return inventory;
    }

    private static Path child(Path folder, String name) throws IOException {
        Path result = folder.resolve(name).normalize();
        if (!result.startsWith(folder) || result.equals(folder)) throw new IOException("Input path escapes its directory");
        return result;
    }

    private void addTree(Map<String, Object> inventory, Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Enabled resource or shader pack is missing");
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) { addFile(inventory, root); return; }
        if (Files.isSymbolicLink(root)) throw new IOException("Redirected packs are not supported by the recorder");
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) addFile(inventory, path);
        }
    }

    private void addFile(Map<String, Object> inventory, Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported input file type");
        long size = Files.size(file), modified = Files.getLastModifiedTime(file).toMillis();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[131072];
            for (int read; (read = input.read(buffer)) >= 0;) if (read != 0) digest.update(buffer, 0, read);
        }
        if (Files.size(file) != size || Files.getLastModifiedTime(file).toMillis() != modified)
            throw new IOException("Runtime input changed while hashing");
        inventory.put(game.relativize(file).toString().replace('\\', '/'),
                Map.of("sha256", HexFormat.of().formatHex(digest.digest()), "bytes", size, "modifiedMillis", modified));
    }
}
