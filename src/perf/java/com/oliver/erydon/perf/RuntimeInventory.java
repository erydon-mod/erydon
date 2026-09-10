package com.oliver.erydon.perf;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;

final class RuntimeInventory {
    static final List<String> FLAGS = List.of("erydon.perf.pause_self_test", "erydon.perf.frame_capture", "erydon.perf.alias_index",
            "erydon.perf.shared_stair_shapes", "erydon.perf.sprite_handles", "erydon.perf.clip_plans",
            "erydon.shared_geometry.mode", "erydon.shared_geometry.metrics", "erydon.synapheia.metrics");
    static void requireOneErydon(Collection<String> identities) {
        if (identities.stream().filter("erydon"::equals).count() != 1) throw new IllegalStateException("Expected exactly one loaded ERYDON identity");
    }
    static Map<String, Object> collect(MinecraftClient client) throws Exception {
        var loader = FabricLoader.getInstance();
        if (!loader.isDevelopmentEnvironment()) throw new IllegalStateException("Development runtime required");
        JsonObject expected = new Gson().fromJson(Files.readString(Path.of(System.getProperty("erydon.perf.expected"))), JsonObject.class);
        if (!expected.has("source_sha256") || !expected.has("files")) throw new IllegalStateException("Missing source/build provenance");
        List<String> identities = new ArrayList<>();
        List<Object> mods = new ArrayList<>();
        for (var mod : loader.getAllMods().stream().sorted(java.util.Comparator.comparing(m -> m.getMetadata().getId())).toList()) {
            identities.add(mod.getMetadata().getId()); identities.addAll(mod.getMetadata().getProvides());
            mods.add(Map.of("id", mod.getMetadata().getId(), "version", mod.getMetadata().getVersion().getFriendlyString(),
                    "provides", mod.getMetadata().getProvides(), "origins", mod.getRootPaths().stream().map(p -> p.toUri().toString()).toList()));
        }
        requireOneErydon(identities);
        var erydon = loader.getModContainer("erydon").orElseThrow();
        var roots = erydon.getRootPaths().stream().map(p -> p.toAbsolutePath().normalize().toString()).sorted().toList();
        List<String> expectedRoots = new ArrayList<>();
        expected.getAsJsonArray("erydon_roots").forEach(p -> expectedRoots.add(Path.of(p.getAsString()).toAbsolutePath().normalize().toString()));
        expectedRoots.sort(String::compareTo);
        if (!roots.equals(expectedRoots)) throw new IllegalStateException("Loaded ERYDON origins differ from compiled source-set outputs");
        var actualFiles = new java.util.TreeSet<String>();
        for (var root : expected.getAsJsonArray("classpath")) {
            Path path = Path.of(root.getAsString());
            if (Files.isRegularFile(path)) actualFiles.add(path.toRealPath().toString());
            else try (var walk = Files.walk(path)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) actualFiles.add(file.toRealPath().toString());
            }
        }
        if (!actualFiles.equals(expected.getAsJsonObject("files").keySet())) throw new IllegalStateException("Runtime input file set changed");
        for (var entry : expected.getAsJsonObject("files").entrySet()) {
            if (!sha256(Path.of(entry.getKey())).equals(entry.getValue().getAsString())) throw new IllegalStateException("Compiled/runtime input changed: " + entry.getKey());
        }
        var flags = new LinkedHashMap<String, String>();
        FLAGS.forEach(key -> flags.put(key, System.getProperty(key, "MISSING")));
        for (var entry : flags.entrySet()) {
            if (!entry.getValue().equals(expected.getAsJsonObject("flags").get(entry.getKey()).getAsString())) throw new IllegalStateException("Child JVM flag differs: " + entry.getKey());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1); result.put("launch_type", "fabric-development-classpath");
        result.put("source_sha256", expected.get("source_sha256").getAsString());
        result.put("loaded_mods", mods); result.put("effective_flags", flags);
        result.put("runtime_files_verified", expected.getAsJsonObject("files"));
        result.put("java_version", System.getProperty("java.runtime.version")); result.put("java_vendor", System.getProperty("java.vendor"));
        result.put("heap_max_bytes", Runtime.getRuntime().maxMemory());
        result.put("heap_initial_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit());
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("gpu", GL11.glGetString(GL11.GL_RENDERER)); result.put("driver", GL11.glGetString(GL11.GL_VERSION));
        result.put("window", List.of(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight()));
        result.put("enabled_packs", client.getResourcePackManager().getEnabledNames());
        result.put("render_distance", client.options.getViewDistance().getValue());
        result.put("simulation_distance", client.options.getSimulationDistance().getValue());
        result.put("vsync", client.options.getEnableVsync().getValue()); result.put("max_fps", client.options.getMaxFps().getValue());
        result.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "UNKNOWN"));
        result.put("power_mode", expected.get("power_mode"));
        result.put("settings", settings(client.runDirectory.toPath()));
        result.put("shader_in_use", loader.isModLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse());
        result.put("ram_bytes", ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize());
        return result;
    }
    static Map<String, String> settings(Path root) throws Exception {
        var result = new java.util.TreeMap<String, String>();
        if (Files.isRegularFile(root.resolve("options.txt"))) result.put("options.txt", sha256(root.resolve("options.txt")));
        for (String directory : List.of("config", "shaderpacks", "resourcepacks")) {
            Path path = root.resolve(directory);
            if (Files.exists(path)) try (var walk = Files.walk(path)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) result.put(root.relativize(file).toString(), sha256(file));
            }
        }
        return result;
    }
    static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static boolean comparable(Map<String, Object> before, Map<String, Object> after) { return before.equals(after); }
}
