package com.oliver.erydon.block;

final class GeorgianWallConnectionPolicy {
    private static final int NORTH_OR_NORTH_EAST = 1;
    private static final int EAST_OR_SOUTH_EAST = 1 << 1;
    private static final int SOUTH_OR_SOUTH_WEST = 1 << 2;
    private static final int WEST_OR_NORTH_WEST = 1 << 3;

    private GeorgianWallConnectionPolicy() {
    }

    static boolean allowsDiagonal(boolean firstCardinal, boolean secondCardinal) {
        return !firstCardinal && !secondCardinal;
    }

    static boolean hasConnectionTurn(int cardinalMask, int diagonalMask) {
        int cardinals = cardinalMask & 15;
        int diagonals = diagonalMask & 15;
        if (Integer.bitCount(cardinals) + Integer.bitCount(diagonals) < 2) {
            return false;
        }

        boolean straightCardinal = diagonals == 0
                && (cardinals == (NORTH_OR_NORTH_EAST | SOUTH_OR_SOUTH_WEST)
                || cardinals == (EAST_OR_SOUTH_EAST | WEST_OR_NORTH_WEST));
        boolean straightDiagonal = cardinals == 0
                && (diagonals == (NORTH_OR_NORTH_EAST | SOUTH_OR_SOUTH_WEST)
                || diagonals == (EAST_OR_SOUTH_EAST | WEST_OR_NORTH_WEST));
        return !straightCardinal && !straightDiagonal;
    }

    static boolean isStraightRunSection(int cardinalMask, int diagonalMask) {
        int cardinals = cardinalMask & 15;
        if ((diagonalMask & 15) != 0) {
            return false;
        }
        return Integer.bitCount(cardinals) == 1
                || cardinals == (NORTH_OR_NORTH_EAST | SOUTH_OR_SOUTH_WEST)
                || cardinals == (EAST_OR_SOUTH_EAST | WEST_OR_NORTH_WEST);
    }

    static boolean isPeriodicPierSection(int cardinalMask, int diagonalMask,
                                         int tallCardinalMask, boolean up) {
        int cardinals = cardinalMask & 15;
        return !up
                && isStraightRunSection(cardinals, diagonalMask)
                && (tallCardinalMask & 15) == cardinals;
    }

    static boolean shouldUsePeriodicPier(int zeroBasedStraightIndex, int interval,
                                         boolean adjacentToActualPier) {
        return zeroBasedStraightIndex >= 0
                && interval > 0
                && !adjacentToActualPier
                && (zeroBasedStraightIndex + 1) % interval == 0;
    }

    static int anchoredRunIndex(int zeroBasedDistance, int runLength,
                                boolean beginsAtPier, boolean endsAtPier) {
        if (zeroBasedDistance < 0 || zeroBasedDistance >= runLength) {
            return -1;
        }
        return !beginsAtPier && endsAtPier
                ? runLength - 1 - zeroBasedDistance
                : zeroBasedDistance;
    }
}
