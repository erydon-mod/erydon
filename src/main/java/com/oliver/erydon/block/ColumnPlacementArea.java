package com.oliver.erydon.block;

import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/** Checks every cell that a large circular column placement would occupy. */
final class ColumnPlacementArea {
    private ColumnPlacementArea() { }

    static boolean isClear(BlockPos anchor, int height, Predicate<BlockPos> isFree) {
        for (int y = 0; y < height; y++) for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            if (!isFree.test(anchor.add(dx, y, dz))) return false;
        }
        return true;
    }
}
