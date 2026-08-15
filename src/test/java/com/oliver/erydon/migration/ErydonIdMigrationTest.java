package com.oliver.erydon.migration;

import com.oliver.erydon.item.ErydonBlockCategories;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErydonIdMigrationTest {
    @Test
    void loadsTheCompleteApprovedMapIncludingQuatrefoil() {
        assertEquals(1775, ErydonIdMigration.entries().size());
        assertEquals(1586, ErydonIdMigration.aliases().size());
        assertEquals(189, ErydonIdMigration.directRenames().size());
        assertEquals(48, ErydonIdMigration.aliases().stream()
                .filter(entry -> entry.oldId().getPath().contains("quatrefoil"))
                .count());
    }

    @Test
    void canonicalisesRepresentativePublishedIds() {
        assertEquals("aganite_aged_block",
                ErydonIdMigration.canonicalPath("aganite_block_aged"));
        assertEquals("aganite_quatrefoil_bronze_block",
                ErydonIdMigration.canonicalPath("aganite_block_bronzequatrefoil"));
        assertEquals("aganite_guilloche_silver_block",
                ErydonIdMigration.canonicalPath("aganite_block_silverguilloche"));
        assertEquals("aganite_aged_cornice_byzantine",
                ErydonIdMigration.canonicalPath("aganite_cornice_guilloche_aged"));
        assertEquals("aganite_aged_surround_modern",
                ErydonIdMigration.canonicalPath("aganite_surround_modern_aged"));
    }

    @Test
    void derivesCanonicalSliceAndPostIdsFromCanonicalOrPublishedSlabIds() {
        assertEquals("aganite_aged_slice_horizontal",
                ErydonIdMigration.canonicalSlitherPath("aganite_aged_slab", "slice_horizontal"));
        assertEquals("aganite_aged_slice_vertical",
                ErydonIdMigration.canonicalSlitherPath("aganite_slab_aged", "slice_vertical"));
        assertEquals("aganite_hewn_post",
                ErydonIdMigration.canonicalSlitherPath("aganite_hewn_slab", "post"));
        assertEquals("aganite_rock_slice_horizontal",
                ErydonIdMigration.canonicalSlitherPath("aganite_rock_slab", "slice_horizontal"));
    }

    @Test
    void keepsPublishedInternalResourcesStable() {
        assertEquals("aganite_block_aged",
                ErydonIdMigration.legacyResourcePath("aganite_aged_block"));
        assertEquals("aganite_block_bronzequatrefoil",
                ErydonIdMigration.legacyResourcePath("aganite_quatrefoil_bronze_block"));
        assertEquals("aganite_cornice_guilloche_aged",
                ErydonIdMigration.legacyResourcePath("aganite_aged_cornice_byzantine"));

        // This family was unpublished and its resources were renamed directly.
        assertEquals("aganite_aged_arch_gothic",
                ErydonIdMigration.legacyResourcePath("aganite_aged_arch_gothic"));
    }

    @Test
    void exposesOldAndNewSearchVocabularyWithoutAliasingUnpublishedIds() {
        List<String> quatrefoil = ErydonBlockCategories.searchTerms("aganite_quatrefoil_bronze_block");
        assertTrue(quatrefoil.contains("erydon:aganite_block_bronzequatrefoil"));
        assertTrue(quatrefoil.contains("erydon:aganite_quatrefoil_bronze_block"));
        assertTrue(quatrefoil.contains("quatrefoil motif"));

        List<String> byzantine = ErydonBlockCategories.searchTerms("aganite_cornice_byzantine");
        assertTrue(byzantine.contains("byzantine"));
        assertTrue(byzantine.contains("guilloche"));

        List<String> motif = ErydonBlockCategories.searchTerms("aganite_guilloche_silver_block");
        assertTrue(motif.contains("guilloche"));
        assertTrue(motif.contains("byzantine"));

        List<String> direct = ErydonBlockCategories.searchTerms("aganite_aged_arch_gothic");
        assertFalse(direct.contains("erydon:aganite_arch_gothic_aged"));
    }

    @Test
    void recognisesCanonicalSlicesDecorationsAndColumnCapitalVocabulary() {
        assertTrue(ErydonBlockCategories.isSliceOrPost("aganite_ashlar_slice_horizontal"));
        assertTrue(ErydonBlockCategories.isSliceOrPost("aganite_aged_slice_vertical"));
        assertTrue(ErydonBlockCategories.isDecoratedBlock("aganite_quatrefoil_silver_block"));

        List<String> column = ErydonBlockCategories.searchTerms("aganite_column_circular");
        assertTrue(column.contains("column capital"));
        assertTrue(column.contains("guilloche capital"));
        assertTrue(column.contains("byzantine capital"));
    }
}
