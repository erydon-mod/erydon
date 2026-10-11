package com.oliver.erydon.recorder;

/** Main-thread sampling, with synchronized worker timeout/status access. No IO or allocations per sample. */
public final class CaptureSession {
    public enum State { WAITING, CAPTURING, COMPLETE, INVALID, TIMED_OUT }
    public record Status(State state, String reason, int intervals, long elapsedNanos, long longestFrameNanos) {}
    private final CaptureRequest request;
    private final long accepted;
    private final long[] intervals;
    private State state = State.WAITING;
    private String reason = "Waiting for loaded, focused, stable view";
    private long stableSince = Long.MIN_VALUE, previousFrame, captureStart, elapsed, longest;
    private int count;
    private boolean startPermitted = true, readyToStart;

    public CaptureSession(CaptureRequest request, long now, int capacity) {
        this.request = request;
        accepted = now;
        if (capacity < 1) throw new IllegalArgumentException("Frame capacity must be positive");
        intervals = new long[capacity];
    }

    public synchronized void frame(long now, String ineligible, boolean sameScene) {
        if (finished()) return;
        if (now - accepted >= seconds(request.timeoutSeconds())) { timeout(now); return; }
        if (state == State.CAPTURING) {
            if (ineligible != null || !sameScene) {
                invalidate(ineligible != null ? ineligible : "Camera, world or framebuffer changed");
                return;
            }
            long interval = now - previousFrame;
            if (interval <= 0) { invalidate("Non-increasing frame clock"); return; }
            if (count == intervals.length) { invalidate("Frame buffer capacity exceeded; no samples were dropped"); return; }
            intervals[count++] = interval;
            previousFrame = now;
            elapsed = now - captureStart;
            longest = Math.max(longest, interval);
            // Keep slow frames in full. A last long frame may legitimately cross the requested duration.
            if (elapsed >= seconds(request.captureSeconds())) {
                state = State.COMPLETE;
                reason = "Completed frame intervals; configuration verification pending";
            }
            return;
        }
        if (ineligible != null || !sameScene) {
            stableSince = Long.MIN_VALUE;
            readyToStart = false;
            reason = ineligible != null ? ineligible : "Waiting for camera stability";
            return;
        }
        if (stableSince == Long.MIN_VALUE) stableSince = now;
        if (now - stableSince >= seconds(request.warmupSeconds())) {
            readyToStart = true;
            if (!startPermitted) { reason = "Stable view ready; waiting for recorder IO handshake"; return; }
            state = State.CAPTURING;
            reason = "Recording completed frame intervals";
            captureStart = previousFrame = now;
        }
    }

    public synchronized void timeout(long now) {
        if (finished()) return;
        if (now - accepted >= seconds(request.timeoutSeconds())) {
            state = State.TIMED_OUT;
            reason = "Request deadline exceeded before a valid capture completed";
        }
    }

    public synchronized void invalidate(String message) {
        state = State.INVALID;
        reason = message;
    }

    public synchronized boolean finished() {
        return state == State.COMPLETE || state == State.INVALID || state == State.TIMED_OUT;
    }

    public synchronized Status status() { return new Status(state, reason, count, elapsed, longest); }
    public synchronized State state() { return state; }
    public synchronized void requireStartPermission() { startPermitted = false; }
    public synchronized boolean readyToStart() { return readyToStart; }
    public synchronized void permitStart() { startPermitted = true; }
    public synchronized long[] completedIntervals() {
        if (state != State.COMPLETE) throw new IllegalStateException("Only a valid completed capture can export frames");
        return java.util.Arrays.copyOf(intervals, count);
    }
    public static long seconds(int value) { return value * 1_000_000_000L; }
}
