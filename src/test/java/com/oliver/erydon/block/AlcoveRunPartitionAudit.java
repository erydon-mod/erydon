package com.oliver.erydon.block;

import java.util.List;

public final class AlcoveRunPartitionAudit {
    private static final List<List<Integer>> TRIPLE_WIDTH_EXPECTED = List.of(
            List.of(1),
            List.of(2),
            List.of(3),
            List.of(2, 2),
            List.of(3, 2),
            List.of(3, 3),
            List.of(3, 2, 2),
            List.of(3, 3, 2),
            List.of(3, 3, 3),
            List.of(3, 3, 2, 2),
            List.of(3, 3, 3, 2),
            List.of(3, 3, 3, 3));

    private AlcoveRunPartitionAudit() {
    }

    public static void main(String[] args) {
        for (int length = 1; length <= TRIPLE_WIDTH_EXPECTED.size(); length++) {
            List<Integer> forcedSingles = AlcoveRunPartition.widths(length, 1);
            expectEquals(length, forcedSingles.size(), "Forced-single segment count " + length);
            expect(forcedSingles.stream().allMatch(width -> width == 1),
                    "Forced-single mode used a wider segment at " + length);

            expectEquals(
                    TRIPLE_WIDTH_EXPECTED.get(length - 1),
                    AlcoveRunPartition.widths(length, 3),
                    "Alcove width " + length);

            for (int index = 0; index < length; index++) {
                AlcoveRunPartition.Segment segment = AlcoveRunPartition.segmentAt(length, index, 3);
                expect(segment.index() >= 0 && segment.index() < segment.width(),
                        "Invalid alcove segment at " + length + ":" + index);
            }
        }

        expectEquals(List.of(3), AlcoveRunPartition.widths(3, 3), "single to double to triple");
        expectEquals(List.of(2), AlcoveRunPartition.widths(2, 3), "triple minus an end");
        expectEquals(List.of(1), AlcoveRunPartition.widths(1, 3), "triple minus its center, left run");
        expectEquals(List.of(1), AlcoveRunPartition.widths(1, 3), "triple minus its center, right run");
        expectEquals(List.of(2, 2), AlcoveRunPartition.widths(4, 3), "four blocks");
        expectEquals(List.of(3, 2), AlcoveRunPartition.widths(5, 3), "five blocks");

        expectEquals(AlcoveBlock.AlcoveClusterWidth.SINGLE,
                AlcoveBlock.AlcoveClusterWidth.AUTO.next(false, 3),
                "Alcove width cycle starts at one");
        expectEquals(AlcoveBlock.AlcoveClusterWidth.AUTO,
                AlcoveBlock.AlcoveClusterWidth.TRIPLE.next(false, 3),
                "Alcove width cycle returns to automatic");
        expectEquals(AlcoveBlock.AlcoveClusterWidth.TRIPLE,
                AlcoveBlock.AlcoveClusterWidth.AUTO.next(true, 3),
                "Alcove reverse width cycle reaches three");
        expectEquals(AlcoveBlock.AlcoveClusterWidth.TRIPLE,
                AlcoveBlock.AlcoveClusterWidth.DOUBLE.next(false, 3),
                "Georgian width cycle exposes triple mode");
        expectEquals(3,
                AlcoveBlock.AlcoveClusterWidth.TRIPLE.effectiveMaxWidth(3),
                "Triple mode remains three for both alcove styles");

        System.out.println("Alcove run partition and manual-width audit passed for lengths 1..12.");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void expectEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + ": expected " + expected + ", got " + actual);
        }
    }
}
