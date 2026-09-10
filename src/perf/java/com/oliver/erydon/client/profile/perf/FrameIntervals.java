package com.oliver.erydon.client.profile.perf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Single-render-thread recorder. Allocate before the timed run; write only after it. */
public final class FrameIntervals {
    private final long[] deltas;
    private int count;
    private long previous;
    private boolean hasPrevious;
    private long dropped;
    private boolean running;
    public FrameIntervals(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity < 2");
        deltas = new long[capacity];
    }
    public void start() { count=0; previous=0; hasPrevious=false; dropped=0; running=true; }
    public void sample(long nowNanos) {
        if (!running) return;
        if (hasPrevious) {
            long delta=nowNanos-previous;
            if (delta<=0) throw new IllegalArgumentException("Nonmonotonic clock");
            if (count<deltas.length) deltas[count++]=delta; else dropped++;
        }
        previous=nowNanos; hasPrevious=true;
    }
    public void stop() { running=false; }
    public int count() { return count; }
    public long dropped() { return dropped; }
    public void writeCsv(Path path) throws IOException {
        if (running) throw new IllegalStateException("Stop before writing");
        if (dropped!=0) throw new IllegalStateException("Capture overflowed; discard and repeat with larger buffer");
        if (count==0) throw new IllegalStateException("No frame intervals");
        Path parent=path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        try (BufferedWriter out=Files.newBufferedWriter(path,StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            out.write("frame_ms\n");
            for (int i=0;i<count;i++) {out.write(Double.toString(deltas[i]/1_000_000.0));out.newLine();}
        }
    }
}
