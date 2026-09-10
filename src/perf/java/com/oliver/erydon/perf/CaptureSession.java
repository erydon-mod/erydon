package com.oliver.erydon.perf;

import com.oliver.erydon.client.profile.perf.FrameIntervals;

/** Single-render-thread, allocation-free warm-up/capture state machine. */
final class CaptureSession {
    enum State { WARMUP, CAPTURING, COMPLETE, INVALID }
    final FrameIntervals frames;
    private final long warmup, duration;
    private long stableSince = -1, captureSince;
    State state = State.WARMUP;
    String reason;
    CaptureSession(long warmup, long duration, int capacity) {
        if (warmup < 0 || duration <= 0) throw new IllegalArgumentException("Invalid duration");
        this.warmup = warmup;
        this.duration = duration;
        frames = new FrameIntervals(capacity);
    }
    void frame(long now, boolean eligible, boolean sameScene) {
        if (finished()) return;
        if (state == State.WARMUP) {
            if (!eligible || !sameScene) { stableSince = -1; return; }
            if (stableSince < 0) stableSince = now;
            if (now - stableSince < warmup) return;
            captureSince = now;
            frames.start();
            state = State.CAPTURING;
        } else if (!eligible || !sameScene) { invalidate("Focus, menu, terrain, camera or scene changed"); return; }
        frames.sample(now);
        if (frames.dropped() != 0) { invalidate("Frame buffer overflow"); return; }
        if (now - captureSince >= duration) {
            frames.stop();
            state = frames.count() > 0 ? State.COMPLETE : State.INVALID;
            if (state == State.INVALID) reason = "Empty capture";
        }
    }
    void invalidate(String reason) { frames.stop(); state = State.INVALID; this.reason = reason; }
    boolean finished() { return state == State.COMPLETE || state == State.INVALID; }
}
