package com.oliver.erydon.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.item.ErydonBlockCategories;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FullBlockSearchTest {
    @Test
    void standardFinishTermsCoverPlainFormsAndExcludeAlternateFinishes() {
        for (String path : List.of("glacium_block", "glacium_slab", "glacium_arch_gothic",
                "glacium_slope_steep_upper", "glacium_coping_georgian", "glacium_column_circular_double",
                "glacium_cornice_byzantine", "glacium_cornice_guilloche", "latmion_block",
                "psamatheon_window_arch", "kelastrion_light_wall")) {
            assertTrue(ErydonBlockCategories.isStandardFinish(path), path);
            assertTrue(ErydonBlockCategories.searchTerms(path).containsAll(List.of("polished", "honed", "mirror")), path);
        }
        for (String path : List.of("glacium_aged_block", "glacium_block_aged", "glacium_hewn_slab",
                "glacium_ashlar_slope", "glacium_rusticated_coping_georgian", "glacium_rock_arch_gothic",
                "glacium_nerium_weave_bronze_block", "glacium_herringbone_grout_wall",
                "glacium_trim_bronze_block", "glacium_block_bronzetrim", "glacium_guilloche_silver_block",
                "glacium_quatrefoil_bronze_stairs", "glacium_rosette_silver_layer_multiface",
                "aganite_diaphanes_block", "glazing_clear", "cover_white")) {
            assertFalse(ErydonBlockCategories.isStandardFinish(path), path);
            for (String term : List.of("polished", "honed", "mirror")) {
                assertFalse(ErydonBlockCategories.searchTerms(path).contains(term), path + ": " + term);
            }
        }
    }

    @Test
    void standardFinishTagsAndSearchAliasesAgreeAcrossTheCompleteCatalogue() throws Exception {
        Set<String> expected = new HashSet<>();
        for (var entry : language("en_us").entrySet()) {
            if (!entry.getKey().startsWith("block.erydon.")) continue;
            String path = entry.getKey().substring("block.erydon.".length());
            boolean standard = ErydonBlockCategories.isStandardFinish(path);
            if (standard) expected.add(path);
            for (String term : List.of("polished", "honed", "mirror")) {
                assertEquals(standard, ErydonBlockCategories.searchTerms(path).contains(term), path + ": " + term);
            }
        }
        assertEquals(1413, expected.size());
        for (String kind : List.of("blocks", "items")) {
            for (String finish : List.of("polished", "honed", "mirror")) {
                assertEquals(expected, tagMembers(kind, finish), kind + ": " + finish);
            }
        }
        for (String locale : List.of("en_us", "de_de", "es_es")) {
            assertTrue(language(locale).has("search.erydon.standard_finish"), locale);
            assertFalse(language(locale).has("block.erydon.chalstrom_calacattum_chrysonyx_weave_bronze_slab"), locale);
        }
    }

    @Test
    void recognisesFullCubesIncludingPublishedAliasesAndInlays() {
        for (String path : List.of("glacium_block", "glacium_aged_block", "glacium_block_aged",
                "glacium_rock_block", "glacium_hewn_block", "glacium_ashlar_block",
                "glacium_rusticated_block", "glacium_quatrefoil_bronze_block",
                "glacium_block_bronzequatrefoil", "glacium_guilloche_silver_block",
                "glacium_nerium_weave_silver_block")) {
            assertTrue(ErydonBlockCategories.isFullBlock(path), path);
        }
        for (String path : List.of("glacium_slab", "glacium_stairs", "glacium_slope",
                "glacium_aged_slope_shallow_upper", "glacium_layer", "glacium_slice_vertical",
                "glacium_post", "glacium_arch_gothic", "glacium_column_square",
                "glacium_chimney_circular", "glacium_guilloche_silver_stairs",
                "glacium_rusticated_alcove_georgian", "glazing_clear")) {
            assertFalse(ErydonBlockCategories.isFullBlock(path), path);
        }
    }

    @Test
    void allTranslatedNamesAndBrowserAliasesReserveBlockForFullCubes() throws Exception {
        for (String locale : List.of("en_us", "de_de", "es_es")) {
            JsonObject language = language(locale);
            int checked = 0;
            for (var entry : language.entrySet()) {
                if (!entry.getKey().startsWith("block.erydon.")) continue;
                String path = entry.getKey().substring("block.erydon.".length());
                List<String> terms = ErydonBlockCategories.searchTerms(path);
                if (ErydonBlockCategories.isFullBlock(path)) {
                    assertTrue(terms.contains("block"), path);
                    assertTrue(terms.contains("blocks"), path);
                    assertTrue(hasBlockKeyword(language.get("search.erydon.full_block").getAsString()));
                } else {
                    assertFalse(hasBlockKeyword(entry.getValue().getAsString()), locale + ": " + path);
                    for (String term : terms) {
                        assertFalse(hasBlockKeyword(term), path + ": " + term);
                    }
                }
                checked++;
            }
            assertTrue(checked > 9000, locale + " must cover the complete catalogue");
        }
    }

    @Test
    void shapeInstructionsDoNotAddIncidentalBlockSearchMatches() throws Exception {
        for (String locale : List.of("en_us", "de_de", "es_es")) {
            for (var entry : language(locale).entrySet()) {
                if (entry.getKey().startsWith("tooltip.erydon.")) {
                    assertFalse(hasBlockKeyword(entry.getValue().getAsString()),
                            locale + ": " + entry.getKey());
                }
            }
        }
    }

    @Test
    void glaciumBlockSearchIncludesFullInlaysAndExcludesEveryOtherShape() throws Exception {
        Set<String> matches = new HashSet<>();
        for (var entry : language("en_us").entrySet()) {
            if (!entry.getKey().startsWith("block.erydon.")) continue;
            String path = entry.getKey().substring("block.erydon.".length());
            String indexedText = (entry.getValue().getAsString() + " "
                    + String.join(" ", ErydonBlockCategories.searchTerms(path))).toLowerCase(Locale.ROOT);
            if (indexedText.contains("glacium") && indexedText.contains("block")) {
                assertTrue(ErydonBlockCategories.isFullBlock(path), path);
                matches.add(path);
            }
        }
        assertTrue(matches.containsAll(List.of("glacium_block", "glacium_aged_block",
                "glacium_rock_block", "glacium_guilloche_silver_block",
                "glacium_quatrefoil_bronze_block")));
    }

    private static boolean hasBlockKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("block") || lower.contains("blöck") || lower.contains("bloque");
    }

    private static JsonObject language(String locale) throws Exception {
        return resource("/assets/erydon/lang/" + locale + ".json");
    }

    private static Set<String> tagMembers(String kind, String name) throws Exception {
        Set<String> members = new HashSet<>();
        for (var value : resource("/data/erydon/tags/" + kind + "/" + name + ".json").getAsJsonArray("values")) {
            String entry = value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
            if (entry.startsWith("#erydon:")) {
                members.addAll(tagMembers(kind, entry.substring("#erydon:".length())));
            } else {
                assertTrue(entry.startsWith("erydon:"), entry);
                members.add(ErydonIdMigration.canonicalPath(entry.substring("erydon:".length())));
            }
        }
        return members;
    }

    private static JsonObject resource(String path) throws Exception {
        try (var stream = FullBlockSearchTest.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
