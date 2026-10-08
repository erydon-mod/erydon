package com.oliver.erydon.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.item.ErydonBlockCategories;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlazingVerticalDiagonalMigrationTest {
    @Test void preservesPublishedIdsAndSearchTermsForOnlyTheUprightFamily() {
        for (String finish : List.of("tinted", "silver", "crystal", "bronze")) {
            String old = "glazing_framed_" + finish + "_slope_vertical", canonical = "glazing_framed_" + finish + "_vertical_diagonal";
            var entry = ErydonIdMigration.findByCanonicalId(new Identifier("erydon", canonical));
            assertNotNull(entry);
            assertTrue(entry.permanentAlias());
            assertEquals(0, entry.sourceRow());
            assertEquals(canonical, ErydonIdMigration.canonicalPath(old));
            assertEquals(old, ErydonIdMigration.legacyResourcePath(canonical));
            assertTrue(ErydonBlockCategories.isSlope(canonical), "Keep the previously searchable slope category");
            assertFalse(ErydonBlockCategories.isFullBlock(canonical));
            assertTrue(ErydonBlockCategories.searchTerms(canonical).containsAll(List.of("vertical diagonal", "vertical slope", old,
                    entry.oldDisplayName(), entry.canonicalDisplayName(), "ramp", "wedge", "roof")));
            for (String untouched : List.of("_slope", "_shallow_slope_lower", "_shallow_slope_upper")) {
                String path = "glazing_framed_" + finish + untouched;
                assertEquals(path, ErydonIdMigration.canonicalPath(path));
            }
            for (String language : List.of("en_us", "de_de", "es_es")) {
                JsonObject lang = resource("lang/" + language + ".json");
                assertTrue(lang.has("block.erydon." + canonical));
                assertFalse(lang.has("block.erydon." + old));
                assertTrue(lang.has("search.erydon." + canonical));
            }
        }
    }

    @Test void usesThePublishedPaneWithAVisibleIsometricInventoryView() {
        JsonObject parent = resource("models/block/glazing/vertical/glazing_framed_slope_vertical.json");
        JsonObject pane = parent.getAsJsonArray("elements").get(0).getAsJsonObject();
        double nativeAngle = pane.getAsJsonObject("rotation").get("angle").getAsDouble();
        assertEquals("y", pane.getAsJsonObject("rotation").get("axis").getAsString());
        assertEquals(45, nativeAngle);
        for (String finish : List.of("tinted", "silver", "crystal", "bronze")) {
            JsonObject item = resource("models/item/glazing_framed_" + finish + "_vertical_diagonal.json");
            assertEquals("erydon:block/glazing/vertical/glazing_framed_" + finish + "_slope_vertical", item.get("parent").getAsString());
            JsonObject gui = item.getAsJsonObject("display").getAsJsonObject("gui");
            double yaw = gui.getAsJsonArray("rotation").get(1).getAsDouble();
            double pitch = gui.getAsJsonArray("rotation").get(0).getAsDouble();
            Vector3f nativeNormal = new Quaternionf().rotationY((float) Math.toRadians(nativeAngle)).transform(new Vector3f(0, 0, 1));
            Vector3f viewedNormal = new Quaternionf().rotationXYZ((float) Math.toRadians(pitch), (float) Math.toRadians(yaw), 0)
                    .transform(new Vector3f(nativeNormal));
            Vector3f previousNormal = new Quaternionf().rotationXYZ((float) Math.toRadians(20), (float) Math.toRadians(45), 0)
                    .transform(new Vector3f(nativeNormal));
            // Projected surface area is proportional to the view-normal component.
            assertTrue(Math.abs(previousNormal.z) < .00001, "Fixture must reproduce the former edge-on icon");
            assertTrue(Math.abs(viewedNormal.z) > .6, "Inventory must show a substantial pane surface");
            double paneWidth = pane.getAsJsonArray("to").get(0).getAsDouble() - pane.getAsJsonArray("from").get(0).getAsDouble();
            Vector3f paneEdge = new Quaternionf().rotationY((float) Math.toRadians(nativeAngle))
                    .transform(new Vector3f((float) paneWidth, 0, 0));
            Vector3f previousEdge = new Quaternionf().rotationXYZ((float) Math.toRadians(20), (float) Math.toRadians(45), 0)
                    .transform(new Vector3f(paneEdge));
            Vector3f visibleEdge = new Quaternionf().rotationXYZ((float) Math.toRadians(pitch), (float) Math.toRadians(yaw), 0)
                    .transform(new Vector3f(paneEdge));
            assertTrue(Math.abs(previousEdge.x) < .00001, "Old GUI pose must collapse actual pane width");
            assertTrue(Math.abs(visibleEdge.x) > 14.5, "Actual inventory pane width must remain visible");
            assertEquals(20, pitch); assertEquals(0, yaw);
            assertEquals(.6, gui.getAsJsonArray("scale").get(0).getAsDouble());
        }
    }

    private static JsonObject resource(String path) {
        var stream = GlazingVerticalDiagonalMigrationTest.class.getResourceAsStream("/assets/erydon/" + path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
