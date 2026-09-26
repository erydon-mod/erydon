package com.oliver.erydon.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolishedStoneMaterialsTest {
    @Test
    void polishedColumnsShareTheLowCostReflectionProxy() {
        for (String stone : STONES) {
            for (String shape : List.of("circular", "gothic", "square")) {
                assertTrue(PolishedStoneMaterials.isReflectionColumn(stone + "_column_" + shape));
            }
            assertFalse(PolishedStoneMaterials.isReflectionColumn(stone + "_column_square_aged"));
            assertFalse(PolishedStoneMaterials.isReflectionColumn(stone + "_aged_column_gothic"));
            assertFalse(PolishedStoneMaterials.isReflectionColumn(stone + "_block"));
        }
    }

    @Test
    void overridesSelectActualVariantsAndWeavesFollowTheFirstNamedStone() {
        var settings = com.oliver.erydon.HighPolishSettings.defaults().withEnabled(true)
                .withStone("glacium", new com.oliver.erydon.HighPolishSettings.Stone(com.oliver.erydon.HighPolishSettings.Level.HONED,
                        com.oliver.erydon.HighPolishSettings.Choice.POLISHED,
                        com.oliver.erydon.HighPolishSettings.Choice.INHERIT,
                        com.oliver.erydon.HighPolishSettings.Choice.MIRROR));
        for (String namespace : List.of("erydon", "themelios", "daedalon")) {
            assertFalse(PolishedStoneMaterials.enabled(settings, namespace, "glacium_block"));
            assertTrue(PolishedStoneMaterials.enabled(settings, namespace, "glacium_herringbone_grout_stairs"));
            assertFalse(PolishedStoneMaterials.enabled(settings, namespace, "glacium_nerium_weave_bronze_block"));
            assertTrue(PolishedStoneMaterials.enabled(settings, namespace, "hesperion_glacium_weave_grout_block"));
            assertTrue(PolishedStoneMaterials.enabled(settings, namespace, "glacium_trim_bronze_block"));
            assertFalse(PolishedStoneMaterials.enabled(settings, namespace, "glacium_aged_trim_bronze_block"));
        }
        assertEquals(com.oliver.erydon.HighPolishSettings.Level.POLISHED,
                PolishedStoneMaterials.level(settings, "glacium_herringbone_grout_stairs"));
        assertEquals(com.oliver.erydon.HighPolishSettings.Level.MIRROR,
                PolishedStoneMaterials.level(settings, "glacium_trim_silver_slope"));
    }

    private static final List<String> STONES = List.of(
            "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
            "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
            "kelastrion", "kylorion", "latmion", "laurentium", "mielonyx", "nerium", "noxoplis", "porphyros",
            "portorium", "psamatheon", "rosinium", "sanguenite", "selenephos", "solistra", "striatus");

    @Test
    void sharedThemeliosStoneFamiliesUseTheSameControls() {
        var settings = com.oliver.erydon.HighPolishSettings.defaults().withEnabled(true);
        for (String stone : STONES) {
            for (String form : List.of("block", "slab", "stairs", "layer", "layer_vertical",
                    "slice_horizontal", "slice_vertical", "post", "cylinder_large", "cornice_small",
                    "herringbone_grout_block", "trim_bronze_block", "rosette_silver_block")) {
                String path = stone + "_" + form;
                assertTrue(PolishedStoneMaterials.includes("themelios", path), path);
                assertTrue(PolishedStoneMaterials.enabled(settings, "themelios", path), path);
                assertFalse(PolishedStoneMaterials.enabled(settings.withEnabled(false), "themelios", path), path);
            }
        }
    }

    @Test
    void daedalonDecorFollowsEachStonesFinishWithoutIncludingAgedOrBronzeSculptures() {
        var settings = com.oliver.erydon.HighPolishSettings.defaults().withEnabled(true);
        for (String stone : STONES) {
            for (String form : List.of("aphrodite_statue", "zeus_bust", "amphora_urn", "exedra",
                    "gothic_capital", "corinthian_frieze", "gothic_wall_panel", "kion_plinth",
                    "monopteros_dome", "gothic_fountain_basin", "anthophoros_planter")) {
                String path = stone + "_" + form;
                assertTrue(PolishedStoneMaterials.enabled(settings, "daedalon", path), path);
                var honed = settings.withStone(stone, new com.oliver.erydon.HighPolishSettings.Stone(
                        com.oliver.erydon.HighPolishSettings.Level.HONED,
                        com.oliver.erydon.HighPolishSettings.Choice.INHERIT,
                        com.oliver.erydon.HighPolishSettings.Choice.INHERIT,
                        com.oliver.erydon.HighPolishSettings.Choice.INHERIT));
                assertFalse(PolishedStoneMaterials.enabled(honed, "daedalon", path), path);
                assertFalse(PolishedStoneMaterials.includes("daedalon", stone + "_aged_" + form));
                assertFalse(PolishedStoneMaterials.includes("daedalon", "bronze_" + form));
            }
        }
    }

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
                assertFalse(PolishedStoneMaterials.includes("themelios", stone + "_" + finish + "_block"));
                assertFalse(PolishedStoneMaterials.includes("themelios", stone + "_block_" + finish));
                assertFalse(PolishedStoneMaterials.includes("daedalon", stone + "_" + finish + "_block"));
                assertFalse(PolishedStoneMaterials.includes("daedalon", stone + "_block_" + finish));
            }
            for (String namespace : List.of("minecraft", "unrelated", "erydon_addon")) {
                assertFalse(PolishedStoneMaterials.includes(namespace, stone + "_block"));
            }
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
