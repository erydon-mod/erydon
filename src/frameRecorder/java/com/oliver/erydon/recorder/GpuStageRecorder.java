package com.oliver.erydon.recorder;

import com.oliver.erydon.recorder.mixin.GpuStageMixinPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/** Opt-in, render-thread-only observation. Timestamp queries never change render state. */
public final class GpuStageRecorder {
    static final long SAMPLE_PERIOD_NANOS = 1_000_000_000L;
    static final int SLOT_COUNT = 4, SPANS_PER_FRAME = 16, MAX_SAMPLES = 128;
    private static final int QUERIES_PER_SLOT = 2 + 2 * SPANS_PER_FRAME;
    private static final List<String> STAGES = List.of(
            "shadow", "terrain", "deferred_depth_copy", "composite_final", "color_space");
    private static GpuStageRecorder active;
    private final Backend backend;
    private final LongSupplier clock;
    private final Sample[] slots = new Sample[SLOT_COUNT];
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private Thread owner;
    private int[] queries;
    private Sample recording;
    private long frames, lastSampleNanos;
    private boolean sampled;
    private int ringDrops, spanDrops, invalidSamples, unresolved, shadowTerrainSkipped;
    private String failure;
    private Map<String, Object> finalSnapshot;

    public GpuStageRecorder() { this(new OpenGlBackend(), System::nanoTime); }

    GpuStageRecorder(Backend backend, LongSupplier clock) {
        this.backend = backend;
        this.clock = clock;
    }

    public static void beginFrame(GpuStageRecorder sampler) {
        if (sampler == null || sampler.finalSnapshot != null || sampler.failure != null) return;
        try {
            if (active != null) active.closeFrame();
            active = null;
            sampler.openFrame();
            if (sampler.failure == null) active = sampler;
        } catch (RuntimeException | LinkageError error) { sampler.disable(error); }
    }

    /** Called every frame; without an opted-in sampler this does no GL work. */
    public static void endFrame() {
        GpuStageRecorder sampler = active;
        active = null;
        if (sampler == null) return;
        try { sampler.closeFrame(); }
        catch (RuntimeException | LinkageError error) { sampler.disable(error); }
    }

    public static void begin(String stage) {
        GpuStageRecorder sampler = active;
        if (sampler == null || sampler.recording == null) return;
        try { sampler.beginSpan(stage); }
        catch (RuntimeException | LinkageError error) { sampler.disable(error); }
    }

    public static void end(String stage) {
        GpuStageRecorder sampler = active;
        if (sampler == null || sampler.recording == null) return;
        try { sampler.endSpan(stage); }
        catch (RuntimeException | LinkageError error) { sampler.disable(error); }
    }

    private void openFrame() {
        if (owner == null) owner = Thread.currentThread();
        if (owner != Thread.currentThread()) throw new IllegalStateException("Wrong render thread");
        if (queries == null) {
            if (!backend.supported()) {
                failure = String.valueOf(backend.details().getOrDefault("reason", "Unsupported renderer versions or GL timestamp queries"));
                return;
            }
            queries = backend.createQueries(SLOT_COUNT * QUERIES_PER_SLOT);
        }
        frames++;
        drain();
        long now = clock.getAsLong();
        if (sampled && now - lastSampleNanos < SAMPLE_PERIOD_NANOS) return;
        sampled = true;
        lastSampleNanos = now;
        if (samples.size() >= MAX_SAMPLES) { ringDrops++; return; }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) continue;
            recording = new Sample(i, frames, now);
            slots[i] = recording;
            backend.timestamp(query(recording, 0));
            return;
        }
        ringDrops++;
    }

    private void beginSpan(String stage) {
        Sample sample = recording;
        if (sample.invalid) return;
        if (stage.equals("terrain") && sample.shadowDepth > 0) {
            sample.ignoredTerrain++;
            shadowTerrainSkipped++;
            return;
        }
        if (!STAGES.contains(stage) || sample.count == SPANS_PER_FRAME) {
            sample.invalid = true;
            spanDrops++;
            return;
        }
        int index = sample.count++;
        sample.spans[index] = new Span(stage);
        sample.stack[sample.depth++] = index;
        if (stage.equals("shadow")) sample.shadowDepth++;
        backend.timestamp(query(sample, 2 + 2 * index));
    }

    private void endSpan(String stage) {
        Sample sample = recording;
        if (sample.invalid) return;
        if (stage.equals("terrain") && sample.ignoredTerrain > 0) { sample.ignoredTerrain--; return; }
        if (sample.depth == 0) { sample.invalid = true; return; }
        int index = sample.stack[--sample.depth];
        if (!sample.spans[index].stage.equals(stage)) { sample.invalid = true; return; }
        if (stage.equals("shadow")) sample.shadowDepth--;
        backend.timestamp(query(sample, 3 + 2 * index));
    }

    private void closeFrame() {
        Sample sample = recording;
        recording = null;
        if (sample == null) return;
        sample.invalid |= sample.depth != 0 || sample.ignoredTerrain != 0 || sample.count == 0;
        backend.timestamp(query(sample, 1));
        sample.submitted = true;
    }

    private int query(Sample sample, int local) { return queries[sample.slot * QUERIES_PER_SLOT + local]; }

    private void drain() {
        for (int slot = 0; slot < slots.length; slot++) {
            Sample sample = slots[slot];
            // Poll the last timestamp first; confirm every result's availability before reading any.
            if (sample == null || !sample.submitted || !backend.available(query(sample, 1))) continue;
            if (sample.invalid) { invalidSamples++; slots[slot] = null; continue; }
            boolean ready = backend.available(query(sample, 0));
            for (int i = 0; ready && i < sample.count; i++) {
                ready = backend.available(query(sample, 2 + 2 * i))
                        && backend.available(query(sample, 3 + 2 * i));
            }
            if (!ready) continue;
            long start = backend.result(query(sample, 0)), end = backend.result(query(sample, 1));
            Map<String, Long> totals = new LinkedHashMap<>();
            List<Map<String, Object>> spans = new ArrayList<>();
            for (int i = 0; i < sample.count; i++) {
                long duration = backend.result(query(sample, 3 + 2 * i)) - backend.result(query(sample, 2 + 2 * i));
                if (duration < 0 || end < start) { sample.invalid = true; break; }
                String stage = sample.spans[i].stage;
                totals.merge(stage, duration, Long::sum);
                spans.add(Map.of("stage", stage, "elapsedNanos", duration));
            }
            if (sample.invalid) invalidSamples++;
            else samples.add(Map.of("frame", sample.frame, "cpuSampleNanos", sample.cpuNanos,
                    "frameCommandTimelineNanos", end - start, "stageNanos", Map.copyOf(totals),
                    "spans", List.copyOf(spans)));
            slots[slot] = null;
        }
    }

    /** Render-thread cleanup is nonblocking and idempotent; unresolved frames are explicitly dropped. */
    public Map<String, Object> finish() {
        if (finalSnapshot != null) return finalSnapshot;
        if (active == this) active = null;
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("GPU query cleanup requires its render thread");
        }
        try { closeFrame(); if (queries != null && failure == null) drain(); }
        catch (RuntimeException | LinkageError error) { disable(error); }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) { unresolved++; slots[i] = null; }
        }
        deleteQueries();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", failure == null && !samples.isEmpty());
        result.put("status", failure != null ? failure : samples.isEmpty() ? "No resolved stage samples" : "OK");
        result.put("support", Map.copyOf(backend.details()));
        result.put("measurement", "Asynchronous GL timestamp command-timeline elapsed, not GPU busy time");
        result.put("samplePeriodNanos", SAMPLE_PERIOD_NANOS);
        result.put("ringCapacity", SLOT_COUNT);
        result.put("maximumSpansPerFrame", SPANS_PER_FRAME);
        result.put("samples", List.copyOf(samples));
        result.put("ringOrSampleLimitDrops", ringDrops);
        result.put("spanLimitDrops", spanDrops);
        result.put("invalidSamples", invalidSamples);
        result.put("unresolvedAtFinish", unresolved);
        result.put("shadowTerrainSpansExcluded", shadowTerrainSkipped);
        result.put("scope", List.of(
                "Elapsed timestamps can include GPU idle gaps while the CPU submits commands; they are not GPU utilization.",
                "Shadow includes shadow terrain, entities, mipmaps and shadow composite; nested terrain is excluded.",
                "Terrain sums actual Sodium SOLID, CUTOUT and TRANSLUCENT submissions outside shadows.",
                "Deferred includes pre-translucent depth copy. Composite includes Iris final pass.",
                "Color space is Iris final game conversion. Iris prepare, other entities, sky, particles and HUD are not individually timed.",
                "Frame timeline brackets Minecraft render; sampled spans are not a complete or additive frame breakdown."));
        finalSnapshot = Map.copyOf(result);
        return finalSnapshot;
    }

    private void disable(Throwable error) {
        failure = "GPU observation disabled: " + error.getClass().getSimpleName();
        recording = null;
        if (active == this) active = null;
        deleteQueries();
    }

    private void deleteQueries() {
        if (queries == null || owner != Thread.currentThread()) return;
        int[] retired = queries;
        queries = null;
        try { backend.delete(retired); }
        catch (RuntimeException | LinkageError error) {
            failure = "GPU query cleanup unavailable: " + error.getClass().getSimpleName();
        }
    }

    interface Backend {
        boolean supported();
        default Map<String, Object> details() { return Map.of(); }
        int[] createQueries(int count);
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
        void delete(int[] queries);
    }

    private static final class OpenGlBackend implements Backend {
        private Map<String, Object> details = Map.of();
        @Override public boolean supported() {
            if (!GpuStageMixinPlugin.supportedVersions()) {
                details = Map.of("rendererVersionsSupported", false, "reason", "Unsupported Iris/Sodium versions");
                return false;
            }
            var caps = GL.getCapabilities();
            boolean entryPoints = caps.glQueryCounter != 0 && caps.glGetQueryObjectui64v != 0
                    && caps.glGetQueryObjectiv != 0 && caps.glGetQueryiv != 0
                    && caps.glGenQueries != 0 && caps.glDeleteQueries != 0;
            boolean api = timerQueryApiAvailable(caps.OpenGL33, caps.GL_ARB_timer_query, entryPoints);
            int bits = api ? GL15.glGetQueryi(ARBTimerQuery.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) : 0;
            String reason = api ? bits > 0 ? "OK" : "GL timestamp counter has zero bits"
                    : entryPoints ? "GL 3.3 and ARB_timer_query unavailable" : "Timer-query API entry points unavailable";
            details = Map.of("rendererVersionsSupported", true,
                    "glVersion", String.valueOf(GL11.glGetString(GL11.GL_VERSION)),
                    "openGl33", caps.OpenGL33, "arbTimerQuery", caps.GL_ARB_timer_query,
                    "timerQueryEntryPoints", entryPoints, "timestampCounterBits", bits, "reason", reason);
            return api && bits > 0;
        }
        @Override public Map<String, Object> details() { return details; }
        @Override public int[] createQueries(int count) { int[] ids = new int[count]; GL15.glGenQueries(ids); return ids; }
        // LWJGL's ARB wrappers delegate to the same GL33C entry points, independently of core-version flags.
        @Override public void timestamp(int query) { ARBTimerQuery.glQueryCounter(query, ARBTimerQuery.GL_TIMESTAMP); }
        @Override public boolean available(int query) { return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        @Override public long result(int query) { return ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); }
        @Override public void delete(int[] queries) { GL15.glDeleteQueries(queries); }
    }

    static boolean timerQueryApiAvailable(boolean core33, boolean arbTimerQuery, boolean entryPoints) {
        return (core33 || arbTimerQuery) && entryPoints;
    }

    private static final class Sample {
        final int slot;
        final long frame, cpuNanos;
        final Span[] spans = new Span[SPANS_PER_FRAME];
        final int[] stack = new int[SPANS_PER_FRAME];
        int count, depth, shadowDepth, ignoredTerrain;
        boolean submitted, invalid;
        Sample(int slot, long frame, long cpuNanos) { this.slot = slot; this.frame = frame; this.cpuNanos = cpuNanos; }
    }

    private static final class Span {
        final String stage;
        Span(String stage) { this.stage = stage; }
    }
}
