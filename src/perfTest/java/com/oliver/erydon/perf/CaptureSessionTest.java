package com.oliver.erydon.perf;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CaptureSessionTest {
    @Test void disabledGate() {
        assertFalse(PerfMixinGate.enabled(true, null)); assertFalse(PerfMixinGate.enabled(true, "false"));
        assertFalse(PerfMixinGate.enabled(false, "true")); assertTrue(PerfMixinGate.enabled(true, "true"));
    }
    @Test void warmupResetsAndDurationUsesTime() {
        var s = new CaptureSession(10, 100, 10);
        s.frame(0, true, true); s.frame(9, true, true); assertEquals(0, s.frames.count());
        s.frame(10, false, true); s.frame(20, true, true); s.frame(29, true, true);
        assertEquals(CaptureSession.State.WARMUP, s.state);
        s.frame(30, true, true); s.frame(80, true, true); s.frame(130, true, true);
        assertEquals(CaptureSession.State.COMPLETE, s.state); assertEquals(2, s.frames.count());
    }
    @Test void interruptionsRejectInsteadOfDroppingSlowFrames() {
        for (boolean focus : new boolean[] {false, true}) {
            var s = new CaptureSession(0, 100, 10); s.frame(0, true, true);
            s.frame(200, focus, !focus); assertEquals(CaptureSession.State.INVALID, s.state);
        }
    }
    @Test void overflowRejected() {
        var s = new CaptureSession(0, 100, 2);
        for (int i = 0; i < 4; i++) s.frame(i, true, true);
        assertEquals(CaptureSession.State.INVALID, s.state); assertEquals(1, s.frames.dropped());
    }
    @Test void writesOnlyAfterCompletion(@TempDir Path root) throws Exception {
        var s = new CaptureSession(0, 100, 10); Path csv = root.resolve("frames.csv");
        s.frame(0, true, true); s.frame(50, true, true);
        assertThrows(IllegalStateException.class, () -> s.frames.writeCsv(csv)); assertFalse(Files.exists(csv));
        s.frame(100, true, true); s.frames.writeCsv(csv); assertEquals(3, Files.readAllLines(csv).size());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> s.frames.writeCsv(csv));
    }
    @Test void identityCannotBeInferredFromFilenames() {
        assertThrows(IllegalStateException.class, () -> RuntimeInventory.requireOneErydon(List.of()));
        assertThrows(IllegalStateException.class, () -> RuntimeInventory.requireOneErydon(List.of("erydon", "erydon")));
        assertDoesNotThrow(() -> RuntimeInventory.requireOneErydon(List.of("minecraft", "erydon")));
    }
}
