package com.oliver.erydon.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class MetallicMaterialsTest {
    @Test void onlyTheTwoExactErydonGlossInsetSpritesReceiveNonmetalFinishMarkers() {
        for (String colour : List.of("white", "black")) {
            String path = "block/cover_" + colour + "_gloss";
            var marker = MetallicMaterials.classify("erydon", path);
            assertEquals(MetallicMaterials.GLOSS_COVER, marker.kind());
            assertEquals(MetallicMaterials.AUTHORED, marker.alloy());
            assertFalse(marker.pureMetalFallback());
            for (String namespace : List.of("themelios", "daedalon")) {
                var unchanged = MetallicMaterials.classify(namespace, path);
                assertEquals(MetallicMaterials.STANDALONE, unchanged.kind());
                assertEquals(MetallicMaterials.AUTHORED, unchanged.alloy(),
                        "Unrecognised alloy sprites retain their existing native-PBR path");
            }
            assertNull(MetallicMaterials.classify("othermod", path));
            assertNull(MetallicMaterials.classify("erydon", path + "_s"));
            assertNull(MetallicMaterials.classify("erydon", path + "_n"));
            for (String unrelated : List.of(path + "_extra", path.replace("_gloss", "_matte"),
                    path.replace("block/", "block/decor/"), "block/aganite_block")) {
                assertNotEquals(MetallicMaterials.GLOSS_COVER,
                        MetallicMaterials.classify("erydon", unrelated).kind(), unrelated);
            }
        }
        for (String alloy : List.of("bronze", "silver")) {
            var metal = MetallicMaterials.classify("erydon", "block/cover_" + alloy + "_gloss");
            assertEquals(MetallicMaterials.STANDALONE, metal.kind());
            assertEquals(alloy.equals("bronze") ? MetallicMaterials.BRONZE : MetallicMaterials.SILVER, metal.alloy());
        }
    }

    @Test void commonOverlaysAndEmbeddedPatternsCoverBothAlloysAndEveryShapeThroughTheirSprites() {
        for (String root : List.of("optifine/ctm/", "mcpatcher/ctm/")) {
            for (String motif : List.of("trim", "guilloche", "quatrefoil", "rose", "rosette")) {
                for (String alloy : List.of("bronze", "silver")) {
                    var value = MetallicMaterials.classify("minecraft", root + "overlay/" + motif + "/" + alloy + "/17");
                    assertNotNull(value);
                    assertEquals(MetallicMaterials.OVERLAY, value.kind());
                    assertEquals(alloy.equals("bronze") ? MetallicMaterials.BRONZE : MetallicMaterials.SILVER, value.alloy());
                    assertFalse(value.pureMetalFallback());
                }
            }
        }
        assertEquals(MetallicMaterials.EMBEDDED,
                MetallicMaterials.classify("minecraft", "optifine/ctm/striatus_nerium_weave_bronze/0").kind());
        assertEquals(MetallicMaterials.EMBEDDED,
                MetallicMaterials.classify("erydon", "block/aganite_block_bronzerose").kind());
        for (String namespace : List.of("erydon", "themelios", "daedalon")) {
            assertNotNull(MetallicMaterials.classify(namespace, "block/bronze"));
        }
    }

    @Test void glazingCoatingAuxiliaryMapsAndUnrelatedModsAreExcluded() {
        for (String namespace : List.of("erydon", "themelios", "daedalon")) {
            for (String path : List.of("block/window_arch_mirror", "block/window_arch_mirror_s", "block/glazing_bronze",
                    "block/glazing_silver_edge", "block/two_way_glass", "block/bronze_s", "block/bronze_n")) {
                assertNull(MetallicMaterials.classify(namespace, path), namespace + ":" + path);
            }
        }
        assertNull(MetallicMaterials.classify("othermod", "block/bronze"));
        assertNull(MetallicMaterials.classify("minecraft", "block/gold_block"));
        assertNull(MetallicMaterials.classify("minecraft", "optifine/ctm/gold/0"));
        assertNull(MetallicMaterials.classify("minecraft", "optifine/ctm/overlay/random/bronze/0"));
    }

    @Test void onlyExactMatteCoversCanSupplyAnAbsentMetalMask() {
        for (String alloy : List.of("bronze", "silver")) {
            assertTrue(MetallicMaterials.classify("erydon", "block/cover_" + alloy + "_matte").pureMetalFallback());
            assertFalse(MetallicMaterials.classify("erydon", "block/cover_" + alloy + "_gloss").pureMetalFallback());
        }
        assertFalse(MetallicMaterials.classify("daedalon", "block/bronze").pureMetalFallback());
        assertArrayEquals(new byte[]{(byte) 255, 64, 0},
                MetallicMaterials.coverage(new int[]{0xff000000, 0x40000000, 0}, null, true));
        assertArrayEquals(new byte[]{0, 0, 0},
                MetallicMaterials.coverage(new int[]{-1, -1, -1}, null, false));
    }

    @Test void metalCoverageRetainsPartialAlphaAndNeverIncludesDielectricPixelsOrTransparentPadding() {
        int[] albedo = {-1, -1, 0x40000000, 0};
        int[] specular = {0xff00e5ff, 0xff00e6ff, 0x0000ffff, 0xff00ffff};
        assertArrayEquals(new byte[]{0, (byte) 255, 64, 0}, MetallicMaterials.coverage(albedo, specular, false));
        assertArrayEquals(new byte[]{0}, MetallicMaterials.coverage(new int[]{-1}, new int[]{0}, true),
                "An explicit dielectric pack override must win over the known matte fallback");
        assertThrows(IllegalArgumentException.class, () -> MetallicMaterials.coverage(new int[2], new int[1], false));
    }

    @Test void authoredMetalRoughnessAndTheMatteCoverFinishRemainDistinct() {
        assertEquals(0, MetallicMaterials.roughness(new byte[]{-1}, new int[]{0xff00ffff}, false));
        assertEquals(127, MetallicMaterials.roughness(new byte[]{-1}, new int[]{0xff00ff80}, false));
        assertEquals(166, MetallicMaterials.roughness(new byte[]{-1}, null, true));
        assertEquals(0, MetallicMaterials.roughness(new byte[]{0, -1}, new int[]{0, 0xff00ffff}, false));
    }

    @Test void metalAlbedoMeanExcludesStoneAndWeightsPartialAlphaExactlyOnce() {
        byte[] coverage = {(byte) 255, 85, 0, 0};
        int[] albedo = {0xff1464c8, 0x55b4dc50, 0xffffffff, 0x00ffffff};
        assertEquals(0xff3c82aa, MetallicMaterials.meanMetalAlbedo(coverage, albedo),
                "RGB (200,100,20) and (80,220,180) weighted 3:1 must average to (170,130,60)");
        assertEquals(0xff000000, MetallicMaterials.meanMetalAlbedo(new byte[]{0}, new int[]{-1}));
        assertThrows(IllegalArgumentException.class,
                () -> MetallicMaterials.meanMetalAlbedo(new byte[1], new int[2]));
    }

    @Test void missingNativeMetalMasksAreRecoveredFromExplicitPairsWithoutAColourThreshold() {
        int[] specular = new int[256]; Arrays.fill(specular, 0xff0000ff);
        assertTrue(MetallicMaterials.nativeBronzePlaceholder(16, 16, specular));
        assertFalse(MetallicMaterials.nativeBronzePlaceholder(32, 8, specular));
        specular[0] = 0xff000000;
        assertFalse(MetallicMaterials.nativeBronzePlaceholder(16, 16, specular));
        assertEquals("optifine/ctm/striatus_nerium_weave_grout/4",
                MetallicMaterials.nativeBronzeCounterpart("optifine/ctm/striatus_nerium_weave_bronze/4"));
        assertNull(MetallicMaterials.nativeBronzeCounterpart("block/cover_bronze_gloss"));
        int[] grout = new int[256]; Arrays.fill(grout, 0xffabcdef);
        int[] bronze = grout.clone(); bronze[4] = 0xff1da0e5; bronze[8] = 0xff22a2e5;
        byte[] mask = MetallicMaterials.nativeBronzeCoverage(bronze, grout);
        assertNotNull(mask); assertEquals(255, mask[4] & 255); assertEquals(255, mask[8] & 255); assertEquals(0, mask[7]);
        bronze[5] = 0xffabceef;
        assertNull(MetallicMaterials.nativeBronzeCoverage(bronze, grout), "Changed stone must reject the inferred mask");
        assertNull(MetallicMaterials.nativeBronzeCoverage(grout, grout));
    }

    @Test void everyActualNativeBronzeGroutPairHasOnlyTheVerifiedMetalPaintDifferences() throws Exception {
        Path root = Path.of("src/main/resources/assets");
        int matched = 0;
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(value -> value.toString().endsWith(".png")).toList()) {
                String source = path.toString().replace('\\', '/');
                if (!source.contains("/textures/") || source.endsWith("_n.png") || source.endsWith("_s.png")) continue;
                String counterpart = MetallicMaterials.nativeBronzeCounterpart(source);
                if (counterpart == null) continue;
                var bronze = ImageIO.read(path.toFile());
                var grout = ImageIO.read(Path.of(counterpart).toFile());
                assertEquals(16, bronze.getWidth(), source); assertEquals(16, bronze.getHeight(), source);
                int[] a = bronze.getRGB(0, 0, 16, 16, null, 0, 16);
                int[] b = grout.getRGB(0, 0, 16, 16, null, 0, 16);
                for (int i = 0; i < a.length; i++) { a[i] = argbToAbgr(a[i]); b[i] = argbToAbgr(b[i]); }
                byte[] coverage = MetallicMaterials.nativeBronzeCoverage(a, b);
                assertNotNull(coverage, source);
                for (int i = 0; i < coverage.length; i++) assertEquals(a[i] != b[i], coverage[i] != 0, source);
                matched++;
            }
        }
        assertEquals(1443, matched, "All 39 native families, 36 CTM tiles plus each base texture");
    }

    private static int argbToAbgr(int color) {
        return (color & 0xff00ff00) | ((color >>> 16) & 255) | ((color & 255) << 16);
    }

    @Test void actualCatalogueMetalTextureNamesHaveACandidateWithoutDependingOnBlockShapes() throws Exception {
        Path root = Path.of("src/main/resources/assets");
        int matched = 0;
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(value -> value.toString().endsWith("_s.png")).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.contains("bronze") && !relative.contains("silver")) continue;
                String[] parts = relative.split("/textures/", 2);
                if (parts.length != 2) continue;
                String sprite = parts[1].substring(0, parts[1].length() - 6);
                assertNotNull(MetallicMaterials.classify(parts[0], sprite), relative);
                matched++;
            }
        }
        assertTrue(matched > 1500, "Exercise the complete native bronze/silver PBR catalogue");
    }
}
