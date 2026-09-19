package com.oliver.erydon.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolishedStoneMaterialsTest {
    @Test
    void overridesSelectActualVariantsAndWeavesFollowTheFirstNamedStone() {
        var settings = com.oliver.erydon.HighPolishSettings.defaults().withEnabled(true)
                .withStone("glacium", new com.oliver.erydon.HighPolishSettings.Stone(false,
                        com.oliver.erydon.HighPolishSettings.Choice.ON,
                        com.oliver.erydon.HighPolishSettings.Choice.INHERIT,
                        com.oliver.erydon.HighPolishSettings.Choice.ON));
        assertFalse(PolishedStoneMaterials.enabled(settings, "erydon", "glacium_block"));
        assertTrue(PolishedStoneMaterials.enabled(settings, "erydon", "glacium_herringbone_grout_stairs"));
        assertFalse(PolishedStoneMaterials.enabled(settings, "erydon", "glacium_nerium_weave_bronze_block"));
        assertTrue(PolishedStoneMaterials.enabled(settings, "erydon", "hesperion_glacium_weave_grout_block"));
        assertTrue(PolishedStoneMaterials.enabled(settings, "erydon", "glacium_trim_bronze_block"));
        assertFalse(PolishedStoneMaterials.enabled(settings, "erydon", "glacium_aged_trim_bronze_block"));
    }

    private static final List<String> STONES = List.of(
            "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
            "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
            "kelastrion", "kylorion", "latmion", "laurentium", "mielonyx", "nerium", "noxoplis", "porphyros",
            "portorium", "psamatheon", "rosinium", "sanguenite", "selenephos", "solistra", "striatus");

    @Test
    void allPolishedFormsAndInlaysAreIncluded() {
        for (String stone : STONES) {
            for (String form : List.of("block", "slab", "stairs", "wall_georgian", "arch_gothic",
                    "alcove_gothic", "alcove_georgian", "window_arch", "window_french_georgian",
                    "herringbone_grout_stairs", "herringbone_bronze_block", "trim_silver_block",
                    "guilloche_bronze_slope", "quatrefoil_silver_layer_multiface", "rosette_bronze_block")) {
                assertTrue(includes(stone + "_" + form), stone + "_" + form);
            }
        }
        for (String weave : List.of("calacattum_portorium", "chalstrom_calacattum", "chrysonyx_glacium",
                "gelastrum_etruscus", "glacium_nerium", "hesperion_glacium", "kylorion_glacium",
                "laurentium_calacattum", "mielonyx_imperium", "rosinium_sanguenite",
                "solistra_etruscus", "striatus_nerium")) {
            assertTrue(includes(weave + "_weave_bronze_block"));
            assertTrue(includes(weave + "_weave_grout_stairs"));
        }
    }

    @Test
    void agedRoughGlassAndOtherNamespacesStayExcluded() {
        for (String stone : STONES) {
            for (String finish : List.of("aged", "ashlar", "hewn", "rusticated", "rock", "diaphanes")) {
                assertFalse(includes(stone + "_" + finish + "_block"));
                assertFalse(includes(stone + "_block_" + finish));
            }
            assertFalse(PolishedStoneMaterials.includes("themelios", stone + "_block"));
        }
        for (String stone : List.of("kelastrion", "latmion", "psamatheon")) {
            for (String form : List.of("block", "alcove_gothic", "herringbone_bronze_block", "trim_silver_block")) {
                assertTrue(includes(stone + "_" + form));
            }
        }
        assertFalse(includes("glazing_white"));
        assertFalse(includes("window_arch_mirror"));
        assertFalse(includes("internal_glacium_block"));
        assertFalse(includes("glaciumx_block"));
    }

    @Test
    void everyAuthoredPolishedBlockstateIncludingPatternsHasCoverage() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/assets/erydon/blockstates"))) {
            var paths = files.map(p -> p.getFileName().toString().replace(".json", "")).toList();
            int matched = 0;
            for (String path : paths) {
                String material = path.split("_")[0];
                if (!STONES.contains(material)) continue;
                boolean excluded = path.matches(".*_(aged|ashlar|hewn|rusticated|rock|diaphanes)(_|$).*");
                assertEquals(!excluded, includes(path), path);
                if (includes(path)) matched++;
            }
            assertTrue(matched > 1000, "Expected broad coverage of the actual authored catalogue");
            for (String stone : STONES) assertTrue(paths.contains(stone + "_block"), stone);
        }
    }

    private static boolean includes(String path) {
        return PolishedStoneMaterials.includes("erydon", path);
    }
}
