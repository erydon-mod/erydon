package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnBlockTest {
    @Test
    void squareColumnsCycleThroughNoCapitalWithoutNarrow() {
        assertEquals(ColumnBlock.CapitalStyle.GUILLOCHE,
                ColumnBlock.CapitalStyle.GEORGIAN.next(false));
        assertEquals(ColumnBlock.CapitalStyle.NONE,
                ColumnBlock.CapitalStyle.GUILLOCHE.next(false));
        assertEquals(ColumnBlock.CapitalStyle.GEORGIAN,
                ColumnBlock.CapitalStyle.NONE.next(false));
    }

    @Test
    void circularColumnsCycleThroughNarrowAndNoCapital() {
        assertEquals(ColumnBlock.CapitalStyle.GUILLOCHE,
                ColumnBlock.CapitalStyle.GEORGIAN.next(true));
        assertEquals(ColumnBlock.CapitalStyle.NARROW,
                ColumnBlock.CapitalStyle.GUILLOCHE.next(true));
        assertEquals(ColumnBlock.CapitalStyle.NONE,
                ColumnBlock.CapitalStyle.NARROW.next(true));
        assertEquals(ColumnBlock.CapitalStyle.GEORGIAN,
                ColumnBlock.CapitalStyle.NONE.next(true));
    }

    @Test
    void noneIsAStableCapitalPropertyValue() {
        assertEquals("none", ColumnBlock.CapitalStyle.NONE.asString());
        assertFalse(ColumnBlock.CapitalStyle.NONE.hasCapital());
        assertTrue(ColumnBlock.CapitalStyle.GEORGIAN.hasCapital());
        assertTrue(ColumnBlock.CapitalStyle.GUILLOCHE.hasCapital());
        assertTrue(ColumnBlock.CapitalStyle.NARROW.hasCapital());
    }

    @Test
    void recalcPreservesNoCapitalAndTheSelectedBase() {
        ColumnBlock.RecalcSelection recalculated = ColumnBlock.RecalcSelection.preservingOptions(
                ColumnBlock.CapitalStyle.NONE,
                ColumnBlock.BaseStyle.NARROW,
                true,
                false
        );

        assertEquals(ColumnBlock.ColumnPart.CAPITAL, recalculated.part());
        assertEquals(ColumnBlock.CapitalStyle.NONE, recalculated.capital());
        assertEquals(ColumnBlock.BaseStyle.NARROW, recalculated.base());
    }
}
