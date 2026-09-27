package com.oliver.erydon.block;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleCircularColumnPlacementTest {
    private static final BlockPos ANCHOR = new BlockPos(10, 64, 20);

    @Test
    void newColumnNeedsAllSixteenCells() {
        Set<BlockPos> checked = new HashSet<>();
        assertTrue(ColumnPlacementArea.isClear(ANCHOR, 4, checked::add));
        assertEquals(16, checked.size());
        assertTrue(checked.contains(ANCHOR.add(1, 3, 1)));
        assertFalse(ColumnPlacementArea.isClear(ANCHOR, 4,
                cell -> !cell.equals(ANCHOR.add(1, 3, 1))));
    }

    @Test
    void extensionNeedsAllFourCells() {
        Set<BlockPos> checked = new HashSet<>();
        assertTrue(ColumnPlacementArea.isClear(ANCHOR, 1, checked::add));
        assertEquals(4, checked.size());
        assertFalse(ColumnPlacementArea.isClear(ANCHOR, 1,
                cell -> !cell.equals(ANCHOR.add(1, 0, 1))));
    }
}
