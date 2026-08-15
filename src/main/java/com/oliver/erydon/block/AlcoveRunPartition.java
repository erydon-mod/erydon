package com.oliver.erydon.block;

import java.util.ArrayList;
import java.util.List;

/** Pure horizontal run partitioning shared by the Georgian and Gothic alcoves. */
public final class AlcoveRunPartition {
    private AlcoveRunPartition() {
    }

    public static List<Integer> widths(int runLength, int maxWidth) {
        if (runLength < 1) {
            throw new IllegalArgumentException("runLength must be positive");
        }
        if (maxWidth < 1 || maxWidth > 3) {
            throw new IllegalArgumentException("maxWidth must be 1, 2, or 3");
        }

        List<Integer> widths = new ArrayList<>();
        if (maxWidth == 1) {
            for (int index = 0; index < runLength; index++) {
                widths.add(1);
            }
            return List.copyOf(widths);
        }
        if (maxWidth == 2) {
            for (int remaining = runLength; remaining >= 2; remaining -= 2) {
                widths.add(2);
            }
            if ((runLength & 1) != 0) {
                widths.add(1);
            }
            return List.copyOf(widths);
        }

        int triples = runLength / 3;
        int remainder = runLength % 3;
        if (remainder == 1 && runLength > 1) {
            triples--;
        }
        for (int index = 0; index < triples; index++) {
            widths.add(3);
        }
        if (remainder == 1) {
            if (runLength == 1) {
                widths.add(1);
            } else {
                widths.add(2);
                widths.add(2);
            }
        } else if (remainder == 2) {
            widths.add(2);
        }
        return List.copyOf(widths);
    }

    public static Segment segmentAt(int runLength, int localLeftIndex, int maxWidth) {
        if (localLeftIndex < 0 || localLeftIndex >= runLength) {
            throw new IllegalArgumentException("localLeftIndex is outside the run");
        }

        int start = 0;
        for (int width : widths(runLength, maxWidth)) {
            int end = start + width;
            if (localLeftIndex < end) {
                return new Segment(width, localLeftIndex - start);
            }
            start = end;
        }
        throw new IllegalStateException("Run partition did not cover the requested position");
    }

    public record Segment(int width, int index) {
        public Segment {
            if (width < 1 || width > 3 || index < 0 || index >= width) {
                throw new IllegalArgumentException("Invalid alcove segment");
            }
        }
    }
}
