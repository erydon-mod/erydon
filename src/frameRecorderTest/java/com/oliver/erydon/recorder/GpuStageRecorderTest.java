package com.oliver.erydon.recorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuStageRecorderTest {
    @Test void minecraft32ContextCanUseArbTimerQueriesButRequiresRealEntryPoints() {
        assertTrue(GpuStageRecorder.timerQueryApiAvailable(false, true, true));
        assertTrue(GpuStageRecorder.timerQueryApiAvailable(true, false, true));
        assertFalse(GpuStageRecorder.timerQueryApiAvailable(false, true, false));
        assertFalse(GpuStageRecorder.timerQueryApiAvailable(false, false, true));
    }

    @Test void inactiveHooksAndConstructionDoNotTouchGl() {
        FakeGl gl = new FakeGl();
        var recorder = new GpuStageRecorder(gl, () -> 0L);
        GpuStageRecorder.beginFrame(null);
        GpuStageRecorder.begin("shadow");
        GpuStageRecorder.end("shadow");
        GpuStageRecorder.endFrame();
        assertFalse((boolean) recorder.finish().get("available"));
        assertEquals(0, gl.supportChecks);
        assertEquals(0, gl.created);
        assertEquals(0, gl.deleted);
    }

    @Test void readsOnlyAvailableResultsAndDoesNotSampleEveryFrame() {
        FakeGl gl = new FakeGl();
        AtomicLong clock = new AtomicLong();
        var recorder = new GpuStageRecorder(gl, clock::get);
        frame(recorder, "terrain");
        int issued = gl.timestamps;
        clock.set(10_000_000L);
        frame(recorder, "terrain");
        assertEquals(issued, gl.timestamps);
        assertEquals(0, gl.results);
        gl.ready = true;
        clock.set(20_000_000L);
        frame(recorder, "terrain");
        assertEquals(issued, gl.timestamps);
        Map<String, Object> result = recorder.finish();
        assertTrue((boolean) result.get("available"));
        assertEquals(1, samples(result).size());
        assertTrue(gl.results > 0);
        assertEquals(1, gl.deleted);
    }

    @Test void sumsTerrainPassesAndExcludesTerrainNestedInsideShadows() {
        FakeGl gl = new FakeGl();
        var recorder = new GpuStageRecorder(gl, () -> 0L);
        GpuStageRecorder.beginFrame(recorder);
        GpuStageRecorder.begin("shadow");
        GpuStageRecorder.begin("terrain"); GpuStageRecorder.end("terrain");
        GpuStageRecorder.end("shadow");
        GpuStageRecorder.begin("terrain"); GpuStageRecorder.end("terrain");
        GpuStageRecorder.begin("terrain"); GpuStageRecorder.end("terrain");
        GpuStageRecorder.endFrame();
        gl.ready = true;
        Map<String, Object> result = recorder.finish();
        var stages = (Map<?, ?>) samples(result).get(0).get("stageNanos");
        assertEquals(100L, stages.get("shadow"));
        assertEquals(200L, stages.get("terrain"));
        assertEquals(1, result.get("shadowTerrainSpansExcluded"));
        assertEquals(3, ((List<?>) samples(result).get(0).get("spans")).size());
        assertThrows(UnsupportedOperationException.class, () -> result.put("available", false));
    }

    @Test void pendingRingIsBoundedAndFinishDropsWithoutWaitingOrDoubleDeletion() {
        FakeGl gl = new FakeGl();
        AtomicLong clock = new AtomicLong();
        var recorder = new GpuStageRecorder(gl, clock::get);
        for (int i = 0; i < GpuStageRecorder.SLOT_COUNT + 2; i++) {
            clock.set(i * GpuStageRecorder.SAMPLE_PERIOD_NANOS);
            frame(recorder, "terrain");
        }
        assertEquals(GpuStageRecorder.SLOT_COUNT * 4, gl.timestamps);
        Map<String, Object> result = recorder.finish();
        assertEquals(2, result.get("ringOrSampleLimitDrops"));
        assertEquals(GpuStageRecorder.SLOT_COUNT, result.get("unresolvedAtFinish"));
        assertEquals(0, gl.results);
        assertEquals(1, gl.deleted);
        assertSame(result, recorder.finish());
        GpuStageRecorder.beginFrame(recorder); GpuStageRecorder.endFrame();
        assertEquals(1, gl.deleted);
    }

    @Test void unsupportedAndGlFailureDisableObservationWithoutEscapingRenderHooks() {
        FakeGl unsupported = new FakeGl(); unsupported.supported = false;
        var disabled = new GpuStageRecorder(unsupported, () -> 0L);
        frame(disabled, "terrain");
        assertEquals(0, unsupported.created);
        assertFalse((boolean) disabled.finish().get("available"));

        FakeGl failing = new FakeGl();
        var recorder = new GpuStageRecorder(failing, () -> 0L);
        GpuStageRecorder.beginFrame(recorder);
        failing.failTimestamp = true;
        assertDoesNotThrow(() -> GpuStageRecorder.endFrame());
        assertFalse((boolean) recorder.finish().get("available"));
        assertEquals(1, failing.deleted);
        assertEquals(0, failing.results);
    }

    @Test void unmatchedAndExcessSpansDiscardInsteadOfPublishingPartialTimings() {
        FakeGl gl = new FakeGl();
        var recorder = new GpuStageRecorder(gl, () -> 0L);
        GpuStageRecorder.beginFrame(recorder);
        for (int i = 0; i <= GpuStageRecorder.SPANS_PER_FRAME; i++) {
            GpuStageRecorder.begin("terrain"); GpuStageRecorder.end("terrain");
        }
        GpuStageRecorder.endFrame();
        gl.ready = true;
        Map<String, Object> result = recorder.finish();
        assertEquals(1, result.get("spanLimitDrops"));
        assertEquals(1, result.get("invalidSamples"));
        assertTrue(samples(result).isEmpty());
        assertEquals(0, gl.results);
    }

    private static void frame(GpuStageRecorder recorder, String stage) {
        GpuStageRecorder.beginFrame(recorder);
        GpuStageRecorder.begin(stage); GpuStageRecorder.end(stage);
        GpuStageRecorder.endFrame();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samples(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("samples");
    }

    private static final class FakeGl implements GpuStageRecorder.Backend {
        final Map<Integer, Long> values = new HashMap<>();
        boolean supported = true, ready, failTimestamp;
        int supportChecks, created, timestamps, results, deleted;
        @Override public boolean supported() { supportChecks++; return supported; }
        @Override public int[] createQueries(int count) {
            created++;
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) ids[i] = i + 1;
            return ids;
        }
        @Override public void timestamp(int query) {
            if (failTimestamp) throw new IllegalStateException("Simulated GL failure");
            values.put(query, ++timestamps * 100L);
        }
        @Override public boolean available(int query) { return ready; }
        @Override public long result(int query) {
            assertTrue(ready, "A query result must never be read while unavailable");
            results++;
            return values.get(query);
        }
        @Override public void delete(int[] queries) { deleted++; }
    }
}
