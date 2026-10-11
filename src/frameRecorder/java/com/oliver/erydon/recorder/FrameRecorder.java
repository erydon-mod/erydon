package com.oliver.erydon.recorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional companion: observes frames and consumes data-only requests; never controls the game. */
public final class FrameRecorder implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("erydon-frame-recorder");
    private static volatile FrameRecorder instance;
    private static volatile long completedFrames;
    // Render-thread owner ensures an IO timeout cannot strand pending OpenGL query objects.
    private static Work gpuOwner;
    private volatile Work active;
    private ResultStore store;
    private boolean failed;

    public static void frameStart() {
        FrameRecorder recorder = instance;
        Work work = recorder == null ? null : recorder.active;
        if (gpuOwner != null && (gpuOwner != work || gpuOwner.session.finished())) {
            finishGpu(gpuOwner);
            gpuOwner = null;
        }
        if (work == null || !work.request.gpuStages()) return;
        synchronized (work) {
            if (work.session.state() != CaptureSession.State.CAPTURING || work.gpuFailure != null) return;
            try {
                if (work.gpu == null) work.gpu = new GpuStageRecorder();
                gpuOwner = work;
                GpuStageRecorder.beginFrame(work.gpu);
            } catch (Exception | LinkageError error) {
                work.gpuFailure = "GPU stage observation unavailable: " + error.getClass().getSimpleName();
            }
        }
    }

    private static void finishGpu(Work work) {
        try { work.gpuSnapshot = work.gpu.finish(); }
        catch (Exception | LinkageError error) {
            work.gpuFailure = "GPU stage export unavailable: " + error.getClass().getSimpleName();
        }
    }

    @Override public void onInitializeClient() {
        instance = this;
        var worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ERYDON frame recorder IO");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    public static void frame(MinecraftClient client) {
        GpuStageRecorder.endFrame();
        completedFrames++;
        FrameRecorder recorder = instance;
        Work work = recorder == null ? null : recorder.active;
        if (work == null) return;
        try {
            synchronized (work) { work.frame(client); }
        } catch (Exception | LinkageError error) {
            synchronized (work) {
                work.session.invalidate("Recorder observation failed: " + error.getClass().getSimpleName());
                work.endReady = true;
            }
        }
    }

    /** Latched transitions prevent a brief menu/focus interruption between two samples being overlooked. */
    public static void interrupted() {
        FrameRecorder recorder = instance;
        Work work = recorder == null ? null : recorder.active;
        if (work == null) return;
        synchronized (work) {
            if (work.session.state() == CaptureSession.State.CAPTURING)
                work.session.invalidate("Menu, overlay or window focus changed during capture");
        }
    }

    private void poll() {
        if (failed) return;
        try {
            if (store == null) store = new ResultStore(FabricLoader.getInstance().getGameDir());
            Work work = active;
            if (work == null) {
                heartbeat(null);
                acceptNext();
                return;
            }
            work.session.timeout(System.nanoTime());
            CaptureSession.State state = work.session.state();
            // No filesystem polling, hashing, status or heartbeat writes while timed sampling is active.
            if (state == CaptureSession.State.CAPTURING) return;
            if (state == CaptureSession.State.WAITING && work.startReady && !work.filesReady) {
                var fingerprints = store.files(work.packs);
                synchronized (work) {
                    work.filesBefore = fingerprints;
                    work.filesReady = true;
                }
            }
            if (work.session.finished()) {
                synchronized (work) {
                    if (work.session.state() == CaptureSession.State.COMPLETE && !work.endReady) return;
                }
                finish(work);
                active = null;
                heartbeat(null);
            } else {
                synchronized (work) {
                    if (work.session.state() == CaptureSession.State.CAPTURING || work.armed) return;
                    if (work.session.readyToStart()) {
                        writeStatus(work, "CAPTURING", "Armed; sampling begins at the next eligible completed frame");
                        heartbeat(work);
                        work.armed = true;
                        work.session.permitStart();
                    } else {
                        writeStatus(work, "WAITING", work.session.status().reason());
                        heartbeat(work);
                    }
                }
            }
        } catch (Exception | LinkageError error) {
            Work work = active;
            if (work != null) {
                work.session.invalidate("Recorder IO failed: " + error.getClass().getSimpleName());
                try {
                    Files.deleteIfExists(work.output.resolve("frames.csv"));
                    writeStatus(work, "INVALID", work.session.status().reason());
                } catch (Exception ignored) { /* A missing COMPLETE result must never be accepted. */ }
                active = null;
            }
            failed = true;
            LOG.warn("Frame recorder disabled for this launch after an IO error", error);
        }
    }

    private void acceptNext() throws Exception {
        for (Path requestPath : store.pending()) {
            String filename = requestPath.getFileName().toString();
            String id = filename.substring(0, filename.length() - 5);
            if (!CaptureRequest.safeId(id)) continue;
            Path output;
            try { output = store.reserve(requestPath); }
            catch (FileAlreadyExistsException duplicate) { continue; }
            try {
                CaptureRequest request = store.readRequest(output);
                Work work = new Work(request, output);
                writeStatus(work, "WAITING", "Waiting for a loaded, focused and stable view");
                active = work;
            } catch (Exception invalid) {
                ResultStore.atomicJson(output.resolve("status.json"), Map.of("id", id, "state", "INVALID",
                        "reason", "Invalid request: " + invalid.getClass().getSimpleName(), "intervals", 0));
            }
            return;
        }
    }

    private void finish(Work work) throws Exception {
        if (work.before != null) ResultStore.atomicJson(work.output.resolve("runtime-before.json"),
                Map.of("configuration", work.before, "scene", work.startScene.asMap()));
        if (work.after != null) ResultStore.atomicJson(work.output.resolve("runtime-after.json"),
                Map.of("configuration", work.after, "scene", work.endScene.asMap()));
        if (work.filesBefore != null) ResultStore.atomicJson(work.output.resolve("files-before.json"), work.filesBefore);
        if (work.session.state() == CaptureSession.State.COMPLETE) {
            writeStatus(work, "WAITING", "Frame sampling complete; verifying runtime files and configuration");
            Map<String, Object> filesAfter = store.files(work.packs);
            ResultStore.atomicJson(work.output.resolve("files-after.json"), filesAfter);
            if (work.before == null || !work.before.equals(work.after) || !work.filesBefore.equals(filesAfter))
                work.session.invalidate("Runtime settings, loaded mods or render input files changed");
        }
        if (work.session.state() == CaptureSession.State.COMPLETE) {
            ResultStore.frames(work.output, work.session.completedIntervals());
            if (work.request.gpuStages()) ResultStore.atomicJson(work.output.resolve("gpu-stages.json"),
                    work.gpuSnapshot == null ? Map.of("available", false, "reason",
                            work.gpuFailure == null ? "No eligible GPU samples" : work.gpuFailure) : work.gpuSnapshot);
            writeStatus(work, "COMPLETE", "Valid completed frame intervals; all recorded runtime inputs match");
        } else {
            writeStatus(work, work.session.state().name(), work.session.status().reason());
        }
    }

    private void writeStatus(Work work, String state, String reason) throws Exception {
        var sample = work.session.status();
        var value = new LinkedHashMap<String, Object>();
        value.put("id", work.request.id()); value.put("state", state); value.put("reason", reason);
        value.put("version", "1.1.1"); value.put("pid", ProcessHandle.current().pid());
        value.put("intervals", sample.intervals()); value.put("elapsedSeconds", sample.elapsedNanos() / 1_000_000_000.0);
        value.put("longestFrameMs", sample.longestFrameNanos() / 1_000_000.0);
        value.put("longFramesRetained", true);
        value.put("gpuTimingAvailable", work.gpuSnapshot != null && Boolean.TRUE.equals(work.gpuSnapshot.get("available")));
        value.put("wallTimeMillis", System.currentTimeMillis());
        value.put("request", work.request);
        ResultStore.atomicJson(work.output.resolve("status.json"), value);
    }

    private void heartbeat(Work work) throws Exception {
        ResultStore.atomicJson(store.root.resolve("heartbeat.json"), Map.of("version", "1.1.1",
                "pid", ProcessHandle.current().pid(), "completedFrames", completedFrames,
                "activeId", work == null ? "" : work.request.id(),
                "state", work == null ? "IDLE" : work.session.state().name(), "wallTimeMillis", System.currentTimeMillis()));
    }

    private static final class Work {
        final CaptureRequest request;
        final Path output;
        final CaptureSession session;
        final Scene anchor = new Scene(), startScene = new Scene(), endScene = new Scene();
        volatile boolean startReady, filesReady, endReady;
        boolean armed;
        Map<String, Object> before, after, filesBefore;
        GpuStageRecorder gpu;
        Map<String, Object> gpuSnapshot;
        String gpuFailure;
        List<String> packs;
        Object metadataWorld;
        int viewDistance, simulationDistance, maxFps, fov;
        boolean vsync, hudHidden, debugEnabled;

        Work(CaptureRequest request, Path output) {
            this.request = request;
            this.output = output;
            session = new CaptureSession(request, System.nanoTime(), 240_000);
            session.requireStartPermission();
        }

        void frame(MinecraftClient client) throws Exception {
            if (session.finished()) { captureEnd(client); return; }
            String unavailable = unavailable(client);
            if (!startReady && unavailable == null) {
                before = RecorderInventory.configuration(client);
                packs = List.copyOf(client.getResourcePackManager().getEnabledNames());
                metadataWorld = client.world;
                viewDistance = client.options.getViewDistance().getValue();
                simulationDistance = client.options.getSimulationDistance().getValue();
                maxFps = client.options.getMaxFps().getValue(); fov = client.options.getFov().getValue();
                vsync = client.options.getEnableVsync().getValue();
                hudHidden = client.options.hudHidden; debugEnabled = client.options.debugEnabled;
                anchor.dimension = client.world.getRegistryKey().getValue().toString();
                startReady = true;
            }
            if (!filesReady) unavailable = "Waiting for initial runtime fingerprints";
            if (startReady && (metadataWorld != client.world || !sameSettings(client)))
                unavailable = "Runtime scene or settings changed after initial inventory";
            CaptureSession.State prior = session.state();
            boolean same = anchor.matches(client);
            if (prior == CaptureSession.State.WAITING && client.world != null) anchor.read(client);
            long now = System.nanoTime();
            if (armed && prior == CaptureSession.State.WAITING && (unavailable != null || !same)) {
                session.invalidate("View or readiness changed after capture was armed");
                captureEnd(client);
                return;
            }
            session.frame(now, unavailable, same);
            if (prior != CaptureSession.State.CAPTURING && session.state() == CaptureSession.State.CAPTURING)
                startScene.copy(anchor);
            if (session.finished()) captureEnd(client);
        }

        private boolean sameSettings(MinecraftClient client) {
            return viewDistance == client.options.getViewDistance().getValue()
                    && simulationDistance == client.options.getSimulationDistance().getValue()
                    && maxFps == client.options.getMaxFps().getValue() && fov == client.options.getFov().getValue()
                    && vsync == client.options.getEnableVsync().getValue()
                    && hudHidden == client.options.hudHidden && debugEnabled == client.options.debugEnabled;
        }

        private void captureEnd(MinecraftClient client) throws Exception {
            if (endReady) return;
            if (gpu != null) {
                finishGpu(this);
                if (gpuOwner == this) gpuOwner = null;
            }
            after = RecorderInventory.configuration(client);
            if (client.world != null) { endScene.dimension = client.world.getRegistryKey().getValue().toString(); endScene.read(client); }
            endReady = true;
        }

        private static String unavailable(MinecraftClient client) {
            if (client.world == null || client.player == null) return "World or player not loaded";
            if (client.currentScreen != null) return "A menu or screen is open";
            if (client.getOverlay() != null) return "A loading overlay is open";
            if (client.isPaused()) return "Game is paused";
            if (!client.isWindowFocused()) return "Minecraft does not have window focus";
            if (client.skipGameRender) return "World rendering is disabled";
            if (!client.worldRenderer.isTerrainRenderComplete()) return "Terrain rendering is incomplete";
            return TerrainGuard.unavailableReason(client);
        }
    }

    /** Mutable only under Work's lock; copied without allocation at capture start/end. */
    private static final class Scene {
        Object world, cameraEntity;
        String dimension = "unavailable";
        double x, y, z;
        float yaw, pitch, rain, thunder;
        int width, height;
        long time, timeOfDay;
        boolean focused;
        boolean matches(MinecraftClient client) {
            var camera = client.gameRenderer.getCamera(); var position = camera.getPos();
            return world == client.world && cameraEntity == client.getCameraEntity()
                    && x == position.x && y == position.y && z == position.z && yaw == camera.getYaw() && pitch == camera.getPitch()
                    && width == client.getWindow().getFramebufferWidth() && height == client.getWindow().getFramebufferHeight();
        }
        void read(MinecraftClient client) {
            var camera = client.gameRenderer.getCamera(); var position = camera.getPos();
            world = client.world; cameraEntity = client.getCameraEntity();
            x = position.x; y = position.y; z = position.z; yaw = camera.getYaw(); pitch = camera.getPitch();
            width = client.getWindow().getFramebufferWidth(); height = client.getWindow().getFramebufferHeight();
            time = client.world.getTime(); timeOfDay = client.world.getTimeOfDay();
            rain = client.world.getRainGradient(1); thunder = client.world.getThunderGradient(1); focused = client.isWindowFocused();
        }
        void copy(Scene other) {
            world = other.world; cameraEntity = other.cameraEntity; dimension = other.dimension;
            x = other.x; y = other.y; z = other.z; yaw = other.yaw; pitch = other.pitch;
            width = other.width; height = other.height; time = other.time; timeOfDay = other.timeOfDay;
            rain = other.rain; thunder = other.thunder; focused = other.focused;
        }
        Map<String, Object> asMap() {
            return Map.of("dimension", dimension, "camera", List.of(x, y, z, yaw, pitch),
                    "framebuffer", List.of(width, height), "worldTime", time, "timeOfDay", timeOfDay,
                    "rain", rain, "thunder", thunder, "focused", focused, "worldLightingFrozen", false);
        }
    }
}
