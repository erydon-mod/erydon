package com.oliver.erydon.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErydonIdNamingTest {
    @Test
    void recognisesPublishedAndCanonicalAgedForms() {
        assertTrue(ErydonIdNaming.isAged("aganite_arch_romanesque_aged"));
        assertTrue(ErydonIdNaming.isAged("aganite_aged_arch_modern"));
        assertTrue(ErydonIdNaming.isAged("aged_arch_modern"));
        assertTrue(ErydonIdNaming.isAged("aged"));
        assertFalse(ErydonIdNaming.isAged("aganite_arch_modern"));
    }

    @Test
    void removesOnlyTheAgedToken() {
        assertEquals("aganite_arch_romanesque",
                ErydonIdNaming.withoutAged("aganite_arch_romanesque_aged"));
        assertEquals("aganite_arch_modern",
                ErydonIdNaming.withoutAged("aganite_aged_arch_modern"));
        assertEquals("arch_modern", ErydonIdNaming.withoutAged("aged_arch_modern"));
        assertEquals("", ErydonIdNaming.withoutAged("aged"));
        assertEquals("aganite_arch_modern",
                ErydonIdNaming.withoutAged("aganite_arch_modern"));
    }

    @Test
    void matchesFormsAcrossBothAgedLayouts() {
        assertTrue(ErydonIdNaming.matchesForm("aganite_arch_romanesque_aged", "_arch_romanesque"));
        assertTrue(ErydonIdNaming.matchesForm("aganite_aged_arch_gothic", "_arch_gothic"));
        assertFalse(ErydonIdNaming.matchesForm("aganite_aged_column_gothic", "_arch_gothic"));
    }
}
