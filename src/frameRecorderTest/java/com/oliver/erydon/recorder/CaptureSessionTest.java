package com.oliver.erydon.recorder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureSessionTest {
    private static long s(double seconds) { return Math.round(seconds * 1_000_000_000); }
    private CaptureSession session(int capacity) {
        return new CaptureSession(new CaptureRequest("test", 2, 2, 12), 0, capacity);
    }
    private void start(CaptureSession session) {
        session.frame(0, null, true); session.frame(s(2), null, true);
        assertEquals(CaptureSession.State.CAPTURING, session.state());
    }

    @Test void retainsLongFinalFrameWithoutClippingOrArtificialFirstInterval() {
        var session = session(10); start(session);
        session.frame(s(2.1), null, true); session.frame(s(4.2), null, true);
        assertEquals(CaptureSession.State.COMPLETE, session.state());
        assertArrayEquals(new long[]{s(.1), s(2.1)}, session.completedIntervals());
        assertEquals(s(2.2), session.status().elapsedNanos());
        assertEquals(s(2.1), session.status().longestFrameNanos());
    }

    @Test void interruptionAtCompletionBoundaryInvalidatesInsteadOfTrimming() {
        var session = session(10); start(session);
        session.frame(s(4), "Menu opened", true);
        assertEquals(CaptureSession.State.INVALID, session.state());
        assertThrows(IllegalStateException.class, session::completedIntervals);
    }

    @Test void cameraChangesResetWarmupButInvalidateActiveCapture() {
        var session = session(10);
        session.frame(0, null, true); session.frame(s(1.9), null, false);
        session.frame(s(2), null, true); session.frame(s(3.9), null, true);
        assertEquals(CaptureSession.State.WAITING, session.state());
        session.frame(s(4), null, true); session.frame(s(4.1), null, false);
        assertEquals(CaptureSession.State.INVALID, session.state());
    }

    @Test void stalledWindowTimesOutWithoutNeedingAnotherFrame() {
        var session = session(10); start(session); session.timeout(s(12));
        assertEquals(CaptureSession.State.TIMED_OUT, session.state());
        session.frame(s(13), null, true);
        assertEquals(CaptureSession.State.TIMED_OUT, session.state());
    }

    @Test void lateCompletedFrameCannotRacePastTheDeadline() {
        var session = session(10); start(session); session.frame(s(12), null, true);
        assertEquals(CaptureSession.State.TIMED_OUT, session.state());
    }

    @Test void zeroClockAndCapacityOverflowNeverProducePlausibleResults() {
        var clock = session(10); start(clock); clock.frame(s(2), null, true);
        assertEquals(CaptureSession.State.INVALID, clock.state());
        var full = session(1); start(full); full.frame(s(2.1), null, true); full.frame(s(2.2), null, true);
        assertEquals(CaptureSession.State.INVALID, full.state());
        assertEquals(1, full.status().intervals());
    }

    @Test void completedResultsAreCopiedAndStableAgainstLaterTimeout() {
        var session = session(10); start(session); session.frame(s(4), null, true);
        long[] copy = session.completedIntervals(); copy[0] = 42;
        session.timeout(s(99));
        assertArrayEquals(new long[]{s(2)}, session.completedIntervals());
    }

    @Test void captureWaitsForIoHandshakeThenSeedsAtTheNextFrame() {
        var session = session(10);
        session.requireStartPermission();
        session.frame(0, null, true); session.frame(s(2), null, true);
        assertTrue(session.readyToStart());
        assertEquals(CaptureSession.State.WAITING, session.state());
        session.permitStart(); session.frame(s(2.1), null, true);
        assertEquals(CaptureSession.State.CAPTURING, session.state());
        assertEquals(0, session.status().intervals());
        session.frame(s(4.1), null, true);
        assertArrayEquals(new long[]{s(2)}, session.completedIntervals());
    }
}
