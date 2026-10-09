package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import com.oliver.erydon.client.MetallicMaterials;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MetallicLookupLayoutTest {
    @Test void glossInsetMetadataSurvivesZeroMetalCoverageWithoutAllocatingAPyramid() {
        for (int size : new int[]{16, 32, 64}) {
            var marker = MetallicLookupLayout.glossCover(0, 0, size, size);
            assertArrayEquals(new byte[size * size], marker.coverage());
            byte[] uniform = new byte[size * size];
            Arrays.fill(uniform, (byte) 255);
            var metal = sprite(size, 0, size, size, uniform, MetallicMaterials.SILVER, false);
            var emptyMetal = sprite(size * 2, 0, size, size, new byte[size * size], MetallicMaterials.SILVER, false);
            var encoded = MetallicLookupLayout.encode(size * 3, size, List.of(marker, metal, emptyMetal));
            assertEquals(2, encoded.recordCount());
            assertEquals(0, encoded.uniqueMasks());
            byte[] bytes = encoded.rgba();
            int markerRecord = MetallicLookupLayout.getU32(bytes, MetallicLookupLayout.HEADER_TEXELS);
            assertEquals(0, MetallicLookupLayout.getU32(bytes, markerRecord));
            assertEquals(size | (size << 16), MetallicLookupLayout.getU32(bytes, markerRecord + 1));
            assertEquals(MetallicMaterials.GLOSS_COVER, MetallicLookupLayout.getU32(bytes, markerRecord + 2),
                    "Kind4 must carry alloy0, roughness0 and no full-metal/fallback flags");
            assertEquals(0, MetallicLookupLayout.getU32(bytes, markerRecord + 3));
            assertEquals(0xff000000, MetallicLookupLayout.getU32(bytes, markerRecord + 4));
            int metalRecord = MetallicLookupLayout.getU32(bytes, MetallicLookupLayout.HEADER_TEXELS + size / 16);
            assertEquals(MetallicLookupLayout.UNIFORM, bytes[(metalRecord + 2) * 4 + 3]);
            assertEquals(0, MetallicLookupLayout.getU32(bytes, MetallicLookupLayout.HEADER_TEXELS + size * 2 / 16),
                    "Ordinary zero-metal sprites remain absent");
        }
    }

    @Test void glossMarkerLeavesExistingMetalCoverageBytesAndUnknownAlloyMetadataUnchanged() {
        byte[] mask = new byte[256];
        mask[30] = (byte) 255;
        var metal = sprite(16, 0, 16, 16, mask, MetallicMaterials.AUTHORED, false);
        var original = MetallicLookupLayout.encode(32, 16, List.of(metal));
        var withMarker = MetallicLookupLayout.encode(32, 16,
                List.of(MetallicLookupLayout.glossCover(0, 0, 16, 16), metal));
        assertEquals(original.uniqueMasks(), withMarker.uniqueMasks());
        byte[] a = original.rgba(), b = withMarker.rgba();
        int oldRecord = MetallicLookupLayout.getU32(a, 5), newRecord = MetallicLookupLayout.getU32(b, 5);
        assertEquals(MetallicLookupLayout.getU32(a, oldRecord + 2), MetallicLookupLayout.getU32(b, newRecord + 2));
        assertEquals(0, b[(newRecord + 2) * 4 + 1], "Unknown alloy remains zero rather than becoming silver");
        int oldStart = MetallicLookupLayout.getU32(a, oldRecord + 3);
        int newStart = MetallicLookupLayout.getU32(b, newRecord + 3);
        byte[] pyramid = MetallicLookupLayout.pyramid(mask, 16, 16);
        assertArrayEquals(pyramid, Arrays.copyOfRange(a, oldStart, oldStart + pyramid.length));
        assertArrayEquals(pyramid, Arrays.copyOfRange(b, newStart, newStart + pyramid.length));
    }

    @Test void nonmetalGlossMarkersRejectMetalCoverageAlloysAndFallbackFlags() {
        byte[] zero = new byte[256], metal = new byte[256];
        metal[0] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> new MetallicLookupLayout.SpriteData(
                0, 0, 16, 16, MetallicMaterials.GLOSS_COVER, MetallicMaterials.SILVER, 0, false, zero));
        assertThrows(IllegalArgumentException.class, () -> new MetallicLookupLayout.SpriteData(
                0, 0, 16, 16, MetallicMaterials.GLOSS_COVER, MetallicMaterials.AUTHORED, 1, false, zero));
        assertThrows(IllegalArgumentException.class, () -> new MetallicLookupLayout.SpriteData(
                0, 0, 16, 16, MetallicMaterials.GLOSS_COVER, MetallicMaterials.AUTHORED, 0, true, zero));
        assertThrows(IllegalArgumentException.class, () -> new MetallicLookupLayout.SpriteData(
                0, 0, 16, 16, MetallicMaterials.GLOSS_COVER, MetallicMaterials.AUTHORED, 0, false, metal));
    }

    @Test void singlePixelMetalLineRetainsItsFractionThroughEveryMipAtEverySupportedPackResolution() {
        for (int size : new int[]{16, 32, 64}) {
            byte[] source = new byte[size * size];
            for (int y = 0; y < size; y++) source[y * size + size / 2] = (byte) 255;
            byte[] pyramid = MetallicLookupLayout.pyramid(source, size, size);
            int start = 0;
            for (int dimension = size; dimension >= 1; dimension /= 2) {
                long sum = 0;
                for (int i = start; i < start + dimension * dimension; i++) sum += pyramid[i] & 255;
                assertEquals(255.0 / size, sum / (double) (dimension * dimension), 0.51,
                        size + "px texture, " + dimension + "px mip");
                start += dimension * dimension;
            }
            assertEquals(pyramid.length, start);
            assertTrue((pyramid[pyramid.length - 1] & 255) > 0);
        }
    }

    @Test void rectangularMaskLevelsContinueUntilBothDimensionsReachOne() {
        byte[] source = new byte[32 * 16];
        for (int i = 0; i < source.length; i++) source[i] = i % 2 == 0 ? (byte) 255 : 0;
        byte[] result = MetallicLookupLayout.pyramid(source, 32, 16);
        assertEquals(512 + 128 + 32 + 8 + 2 + 1, result.length);
        assertEquals(128, result[result.length - 1] & 255);
    }

    @Test void lookupRecordsProvideExactBoundsMetadataAndByteOffsets() {
        byte[] mask = new byte[256];
        mask[0] = (byte) 255;
        var encoded = MetallicLookupLayout.encode(64, 32, List.of(sprite(16, 0, 16, 16, mask, 1, false)));
        byte[] bytes = encoded.rgba();
        assertEquals(64 | (32 << 16), MetallicLookupLayout.getU32(bytes, 0));
        assertEquals(4 | (2 << 16), MetallicLookupLayout.getU32(bytes, 1));
        assertArrayEquals(new byte[]{77, 69, 84, 1}, Arrays.copyOfRange(bytes, 12, 16));
        int record = MetallicLookupLayout.getU32(bytes, 5);
        assertEquals(MetallicLookupLayout.getU32(bytes, 2), record);
        assertEquals(16, MetallicLookupLayout.getU32(bytes, record));
        assertEquals(16 | (16 << 16), MetallicLookupLayout.getU32(bytes, record + 1));
        assertEquals(2 | (1 << 8) | (37 << 16), MetallicLookupLayout.getU32(bytes, record + 2));
        int start = MetallicLookupLayout.getU32(bytes, record + 3);
        assertEquals(255, bytes[start] & 255);
        assertEquals(0, bytes[start + 1]);
        assertEquals(0, MetallicLookupLayout.getU32(bytes, 4));
        assertEquals(0, MetallicLookupLayout.getU32(bytes, 6));
    }

    @Test void samePatternInDifferentAlloysSharesTheSameCoveragePyramid() {
        byte[] mask = new byte[256];
        mask[30] = (byte) 255;
        var encoded = MetallicLookupLayout.encode(32, 16, List.of(
                sprite(0, 0, 16, 16, mask, 1, false), sprite(16, 0, 16, 16, mask, 2, false)));
        assertEquals(2, encoded.recordCount());
        assertEquals(1, encoded.uniqueMasks());
        byte[] bytes = encoded.rgba();
        int a = MetallicLookupLayout.getU32(bytes, 4), b = MetallicLookupLayout.getU32(bytes, 5);
        assertNotEquals(a, b);
        assertEquals(MetallicLookupLayout.getU32(bytes, a + 3), MetallicLookupLayout.getU32(bytes, b + 3));
    }

    @Test void fifthRecordTexelStoresActualMetalMeanWithoutChangingMaskAddressing() {
        byte[] mask = new byte[256]; mask[0] = (byte) 255; mask[1] = 85;
        int[] albedo = new int[256]; Arrays.fill(albedo, 0xffffffff);
        albedo[0] = 0xff1464c8; albedo[1] = 0x55b4dc50;
        int mean = MetallicMaterials.meanMetalAlbedo(mask, albedo);
        var data = new MetallicLookupLayout.SpriteData(0, 0, 16, 16, 2, 1, 37, false, mask, mean);
        byte[] bytes = MetallicLookupLayout.encode(16, 16, List.of(data)).rgba();
        int record = MetallicLookupLayout.getU32(bytes, 4);
        assertEquals(5, MetallicLookupLayout.RECORD_TEXELS);
        assertArrayEquals(new byte[]{(byte) 170, (byte) 130, 60, (byte) 255},
                Arrays.copyOfRange(bytes, (record + 4) * 4, (record + 5) * 4));
        int start = MetallicLookupLayout.getU32(bytes, record + 3);
        assertEquals((record + 5) * 4, start);
        assertEquals(255, bytes[start] & 255);
        assertEquals(85, bytes[start + 1] & 255);
    }

    @Test void uniformMetalOmitsItsPyramidAndEmptyCoverageOmitsItsRecord() {
        byte[] uniform = new byte[256];
        Arrays.fill(uniform, (byte) 255);
        var encoded = MetallicLookupLayout.encode(32, 16, List.of(
                sprite(0, 0, 16, 16, uniform, 1, true), sprite(16, 0, 16, 16, new byte[256], 1, false)));
        assertEquals(1, encoded.recordCount());
        assertEquals(0, encoded.uniqueMasks());
        byte[] bytes = encoded.rgba();
        int record = MetallicLookupLayout.getU32(bytes, 4);
        assertEquals(3, bytes[(record + 2) * 4 + 3]);
        assertEquals(0, MetallicLookupLayout.getU32(bytes, record + 3));
        assertEquals(0, MetallicLookupLayout.getU32(bytes, 5));
    }

    @Test void collisionsOutOfBoundsAndUnsupportedAlignmentFailClosed() {
        byte[] full = new byte[256];
        Arrays.fill(full, (byte) 255);
        var first = sprite(0, 0, 16, 16, full, 1, false);
        assertThrows(IllegalArgumentException.class, () -> MetallicLookupLayout.encode(16, 16, List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> MetallicLookupLayout.encode(16, 16,
                List.of(sprite(16, 0, 16, 16, full, 1, false))));
        assertThrows(IllegalArgumentException.class, () -> sprite(1, 0, 16, 16, full, 1, false));
        assertThrows(IllegalArgumentException.class, () -> sprite(0, 0, 8, 32, full, 1, false));
        assertThrows(IllegalArgumentException.class, () -> sprite(0, 0, 48, 16, new byte[48 * 16], 1, false));
        assertThrows(IllegalArgumentException.class, () -> MetallicLookupLayout.encode(32768, 16, List.of()));
    }

    @Test void largestAtlasOccupancyFitsItsBoundedBudgetAndArraysAreDefensive() {
        byte[] mask = new byte[256]; mask[0] = (byte) 255;
        var data = sprite(16368, 16368, 16, 16, mask, 1, false);
        mask[0] = 0;
        var encoded = MetallicLookupLayout.encode(16384, 16384, List.of(data));
        assertEquals(1, encoded.recordCount());
        assertTrue(encoded.rgba().length < 5 * 1024 * 1024);
        assertTrue(encoded.rgba().length < MetallicLookupLayout.MAX_BYTES);
        byte[] first = encoded.rgba(); first[0] = 0;
        assertEquals(16384 | (16384 << 16), MetallicLookupLayout.getU32(encoded.rgba(), 0));
    }

    private static MetallicLookupLayout.SpriteData sprite(int x, int y, int width, int height,
                                                          byte[] mask, int alloy, boolean fallback) {
        return new MetallicLookupLayout.SpriteData(x, y, width, height, 2, alloy, 37, fallback, mask);
    }
}
