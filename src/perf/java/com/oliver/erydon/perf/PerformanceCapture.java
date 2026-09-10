package com.oliver.erydon.perf;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.lwjgl.glfw.GLFW;

/** Runs exclusively in the isolated, explicitly enabled development module. */
public final class PerformanceCapture {
    private static PerformanceCapture instance;
    private final CaptureSession session;
    private final Path output;
    private Map<String, Object> before;
    private final long started = System.nanoTime();
    private Object world;
    private double x, y, z;
    private float yaw, pitch;
    private int width, height;
    private boolean written;
    private boolean sceneRequested;
    private volatile boolean sceneReady;
    private final boolean automaticPauseTest = Boolean.getBoolean("erydon.perf.pause_self_test");
    private final boolean originalPauseOnLostFocus;
    private boolean pauseTestArmed;

    private PerformanceCapture(MinecraftClient client) throws Exception {
        output = Path.of(System.getProperty("erydon.perf.output"));
        if (Files.exists(output)) throw new IllegalStateException("Capture output already exists");
        Files.createDirectories(output);
        session = new CaptureSession(seconds("warmupSeconds", 5), seconds("captureSeconds", 60), 200_000);
        originalPauseOnLostFocus = client.options.pauseOnLostFocus;
        if (automaticPauseTest) {
            if (!Files.isRegularFile(client.runDirectory.toPath().resolve("ERYDON_PERF_DISPOSABLE"))) throw new IllegalStateException("Disposable instance marker missing");
            // A hidden lifecycle test, never a performance measurement. Restore on exit.
            client.options.pauseOnLostFocus = false;
            GLFW.glfwHideWindow(client.getWindow().getHandle());
        }
    }
    private static long seconds(String key, int fallback) {
        int value = Integer.parseInt(System.getProperty("erydon.perf." + key, "" + fallback));
        if (value < 1 || value > 300) throw new IllegalArgumentException("Invalid " + key);
        return value * 1_000_000_000L;
    }
    public static void frame(MinecraftClient client) {
        try {
            if (instance == null) instance = new PerformanceCapture(client);
            instance.sample(client);
        } catch (Exception failure) {
            // A broken measurement setup must not continue producing plausible results.
            throw new IllegalStateException("ERYDON development measurement failed", failure);
        }
    }
    private void sample(MinecraftClient client) throws Exception {
        if (written) return;
        long now = System.nanoTime();
        var camera = client.gameRenderer.getCamera();
        var position = camera.getPos();
        var window = client.getWindow();
        boolean same = world == client.world && x == position.x && y == position.y && z == position.z
                && yaw == camera.getYaw() && pitch == camera.getPitch()
                && width == window.getFramebufferWidth() && height == window.getFramebufferHeight();
        boolean eligible = prepareScene(client) && client.world != null && client.player != null && client.getServer() != null
                && client.getServer().getSaveProperties().getLevelName().equals(System.getProperty("erydon.perf.world"))
                && client.currentScreen == null && client.getOverlay() == null && !client.isPaused()
                && (automaticPauseTest || client.isWindowFocused()) && client.worldRenderer.isTerrainRenderComplete()
                && !client.options.getEnableVsync().getValue() && client.options.getMaxFps().getValue() >= 260;
        if (session.state == CaptureSession.State.WARMUP) {
            world = client.world; x = position.x; y = position.y; z = position.z;
            yaw = camera.getYaw(); pitch = camera.getPitch();
            width = window.getFramebufferWidth(); height = window.getFramebufferHeight();
        }
        if (before == null && eligible && same) {
            // Inventory only the loaded world configuration, then allow fresh warm-up.
            before = RuntimeInventory.collect(client);
            write("runtime-before.json", before);
            return;
        }
        eligible &= before != null;
        if (automaticPauseTest && eligible && same && session.state == CaptureSession.State.CAPTURING
                && session.frames.count() >= 10 && !pauseTestArmed) {
            // Open the actual Minecraft pause menu only after healthy live samples.
            client.setScreen(new GameMenuScreen(true));
            pauseTestArmed = client.currentScreen instanceof GameMenuScreen;
            eligible &= client.currentScreen == null;
        }
        session.frame(now, eligible, same);
        if (!session.finished() && now - started > 300_000_000_000L) session.invalidate("Timed out waiting for a stable disposable scene");
        if (!session.finished()) return;
        boolean pauseRejected = pauseTestArmed && session.state == CaptureSession.State.INVALID
                && session.frames.count() >= 10 && session.frames.dropped() == 0
                && "Focus, menu, terrain, camera or scene changed".equals(session.reason);
        if (automaticPauseTest && session.state == CaptureSession.State.COMPLETE) session.invalidate("Pause self-test did not interrupt capture");
        // All expensive collection and writing occurs after the buffer is stopped.
        var after = before == null ? Map.<String, Object>of() : RuntimeInventory.collect(client);
        boolean configurationMatched = before != null && RuntimeInventory.comparable(before, after);
        if (!configurationMatched) session.invalidate("Runtime configuration incomplete or changed");
        if (!automaticPauseTest && session.state == CaptureSession.State.COMPLETE) session.frames.writeCsv(output.resolve("frames.csv"));
        write("runtime-after.json", after);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", session.state.name()); status.put("reason", session.reason);
        status.put("intervals", session.frames.count()); status.put("dropped", session.frames.dropped());
        status.put("performance_measurement", !automaticPauseTest);
        if (automaticPauseTest) {
            status.put("pause_menu_test_passed", pauseRejected && configurationMatched);
            status.put("focus_requirement_bypassed_for_control_test", true);
        }
        status.put("visual_approval", false); status.put("optimisation_gain", null);
        write("capture-status.json", status);
        written = true;
        if (automaticPauseTest) client.options.pauseOnLostFocus = originalPauseOnLostFocus;
        client.scheduleStop();
    }
    private boolean prepareScene(MinecraftClient client) {
        var server = client.getServer();
        if (server == null || client.player == null || !server.getSaveProperties().getLevelName().equals(System.getProperty("erydon.perf.world"))) return false;
        if (!sceneRequested) {
            if (!Files.isRegularFile(client.runDirectory.toPath().resolve("ERYDON_PERF_DISPOSABLE"))) throw new IllegalStateException("Disposable instance marker missing");
            sceneRequested = true;
            server.execute(() -> {
                String[] commands = {"gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                    "gamerule doMobSpawning false", "gamerule randomTickSpeed 0", "time set 6000", "weather clear",
                    "gamemode spectator @a", "fill 0 80 0 15 85 15 air", "fill 0 79 0 15 79 15 erydon:aganite_block",
                    "tp @a 8 83 -12 0 15"};
                for (String command : commands) server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
                sceneReady = true;
            });
        }
        return sceneReady;
    }
    private void write(String name, Object value) throws Exception {
        Files.writeString(output.resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardOpenOption.CREATE_NEW);
    }
}
