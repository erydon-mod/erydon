package com.oliver.erydon.client.pom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Encoder for ERYDON's runtime-only CTM-POM lookup protocol. */
public final class ErydonCuPomLookupLayout {
    public static final int PROTOCOL_VERSION = 4;
    public static final int LOOKUP_WIDTH = 1024;
    public static final int LOOKUP_HEIGHT = 1057;
    public static final int RUNTIME_BYTES = LOOKUP_WIDTH * LOOKUP_HEIGHT * 4;
    public static final int ATLAS_QUANTUM = 16;
    public static final int MAX_ATLAS_DIMENSION = LOOKUP_WIDTH * ATLAS_QUANTUM;
    public static final int HEADER_TEXELS = LOOKUP_WIDTH;
    public static final int OCCUPANCY_START = HEADER_TEXELS;
    public static final int OCCUPANCY_WIDTH = 1024;
    public static final int OCCUPANCY_HEIGHT = 1024;
    public static final int RECORD_START = OCCUPANCY_START + OCCUPANCY_WIDTH * OCCUPANCY_HEIGHT;
    public static final int RECORD_TEXELS = 2;
    public static final int PHASES_PER_FAMILY = 36;
    private static final int TEXELS = LOOKUP_WIDTH * LOOKUP_HEIGHT;
    static final int MAX_RECORDS = (TEXELS - RECORD_START) / RECORD_TEXELS;
    static final int MAX_FAMILIES = MAX_RECORDS / PHASES_PER_FAMILY;

    public record SpriteBounds(int x, int y, int width, int height) {
        public SpriteBounds {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Sprite bounds must be positive");
            }
            if (!isQuantumAligned(x) || !isQuantumAligned(y)
                    || !isQuantumAligned(width) || !isQuantumAligned(height)) {
                throw new IllegalArgumentException("Sprite bounds must be aligned to 16 atlas pixels");
            }
            if ((long) x + width > MAX_ATLAS_DIMENSION
                    || (long) y + height > MAX_ATLAS_DIMENSION) {
                throw new IllegalArgumentException("Sprite bounds exceed the lookup atlas coverage");
            }
        }
    }

    public record Encoded(
            int atlasWidth,
            int atlasHeight,
            int lookupWidth,
            int lookupHeight,
            int familyCount,
            int recordCount,
            byte[] rgba
    ) {
        public Encoded {
            rgba = rgba.clone();
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }
    }

    /** Every family must contain exactly 36 stitched bounds in phase order 0..35. */
    public static Encoded encode(int atlasWidth, int atlasHeight, List<List<SpriteBounds>> families) {
        Objects.requireNonNull(families, "families");
        if (atlasWidth <= 0 || atlasWidth > MAX_ATLAS_DIMENSION
                || atlasHeight <= 0 || atlasHeight > MAX_ATLAS_DIMENSION) {
            throw new IllegalArgumentException("Invalid atlas dimensions");
        }

        List<SpriteBounds> flat = new ArrayList<>();
        Set<SpriteBounds> allBounds = new HashSet<>();
        for (int familyIndex = 0; familyIndex < families.size(); familyIndex++) {
            List<SpriteBounds> family = Objects.requireNonNull(families.get(familyIndex), "family " + familyIndex);
            if (family.size() != PHASES_PER_FAMILY) {
                throw new IllegalArgumentException(
                        "Family " + familyIndex + " has " + family.size() + " phases; expected 36");
            }
            SpriteBounds familyReference = Objects.requireNonNull(family.get(0), "family phase 0");
            for (int phase = 0; phase < family.size(); phase++) {
                SpriteBounds bounds = Objects.requireNonNull(family.get(phase), "family phase " + phase);
                if (bounds.width() != familyReference.width() || bounds.height() != familyReference.height()) {
                    throw new IllegalArgumentException(
                            "Family " + familyIndex + " phase " + phase + " has a different sprite size");
                }
                requireInsideAtlas(bounds, atlasWidth, atlasHeight);
                if (!allBounds.add(bounds)) {
                    throw new IllegalArgumentException("A stitched sprite belongs to multiple records: " + bounds);
                }
                flat.add(bounds);
            }
        }
        if (flat.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Too many CTM phase records: " + flat.size());
        }

        byte[] rgba = new byte[RUNTIME_BYTES];
        putBytes(rgba, 0, 69, 67, 80, PROTOCOL_VERSION); // ECP protocol
        putU16Pair(rgba, 1, atlasWidth, atlasHeight);
        putU16Pair(rgba, 2, flat.size(), families.size());
        putU16Pair(rgba, 3, LOOKUP_WIDTH, LOOKUP_HEIGHT);
        putU16Pair(rgba, 4, ATLAS_QUANTUM, PHASES_PER_FAMILY);

        for (int record = 0; record < flat.size(); record++) {
            SpriteBounds bounds = flat.get(record);
            occupy(rgba, bounds, record);
            int recordTexel = RECORD_START + record * RECORD_TEXELS;
            putU16Pair(rgba, recordTexel, bounds.x(), bounds.y());
            putU16Pair(rgba, recordTexel + 1, bounds.width(), bounds.height());
        }
        return new Encoded(
                atlasWidth,
                atlasHeight,
                LOOKUP_WIDTH,
                LOOKUP_HEIGHT,
                families.size(),
                flat.size(),
                rgba);
    }

    static int findRecord(byte[] rgba, int atlasX, int atlasY) {
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != RUNTIME_BYTES
                || atlasX < 0 || atlasX >= MAX_ATLAS_DIMENSION
                || atlasY < 0 || atlasY >= MAX_ATLAS_DIMENSION) {
            return -1;
        }
        int cellX = atlasX / ATLAS_QUANTUM;
        int cellY = atlasY / ATLAS_QUANTUM;
        int occupancyTexel = OCCUPANCY_START + cellY * OCCUPANCY_WIDTH + cellX;
        int encodedRecord = readU16(rgba, occupancyTexel, 0);
        int record = encodedRecord - 1;
        return encodedRecord > 0 && record < readU16(rgba, 2, 0) ? record : -1;
    }

    static SpriteBounds readBounds(byte[] rgba, int record) {
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != RUNTIME_BYTES || record < 0 || record >= readU16(rgba, 2, 0)) {
            throw new IllegalArgumentException("Invalid CTM phase record: " + record);
        }
        int recordTexel = RECORD_START + record * RECORD_TEXELS;
        return new SpriteBounds(
                readU16(rgba, recordTexel, 0),
                readU16(rgba, recordTexel, 2),
                readU16(rgba, recordTexel + 1, 0),
                readU16(rgba, recordTexel + 1, 2));
    }

    static int targetRecord(int currentRecord, int deltaX, int deltaY) {
        int familyBase = Math.floorDiv(currentRecord, PHASES_PER_FAMILY) * PHASES_PER_FAMILY;
        int phase = currentRecord - familyBase;
        int targetX = Math.floorMod(phase % 6 + deltaX, 6);
        int targetY = Math.floorMod(phase / 6 + deltaY, 6);
        return familyBase + targetY * 6 + targetX;
    }

    private static void requireInsideAtlas(SpriteBounds bounds, int atlasWidth, int atlasHeight) {
        if ((long) bounds.x() + bounds.width() > atlasWidth
                || (long) bounds.y() + bounds.height() > atlasHeight) {
            throw new IllegalArgumentException("Sprite bounds exceed the active atlas: " + bounds);
        }
    }

    private static void occupy(byte[] rgba, SpriteBounds bounds, int record) {
        int minCellX = bounds.x() / ATLAS_QUANTUM;
        int minCellY = bounds.y() / ATLAS_QUANTUM;
        int cellWidth = bounds.width() / ATLAS_QUANTUM;
        int cellHeight = bounds.height() / ATLAS_QUANTUM;
        for (int cellY = minCellY; cellY < minCellY + cellHeight; cellY++) {
            for (int cellX = minCellX; cellX < minCellX + cellWidth; cellX++) {
                int occupancyTexel = OCCUPANCY_START + cellY * OCCUPANCY_WIDTH + cellX;
                if (readU16(rgba, occupancyTexel, 0) != 0) {
                    throw new IllegalArgumentException("Stitched sprite bounds overlap at atlas cell "
                            + cellX + "," + cellY);
                }
                putU16Pair(rgba, occupancyTexel, record + 1, 0);
            }
        }
    }

    private static boolean isQuantumAligned(int value) {
        return value % ATLAS_QUANTUM == 0;
    }

    private static void putU16Pair(byte[] rgba, int texel, int a, int b) {
        putBytes(rgba, texel, a & 255, (a >>> 8) & 255, b & 255, (b >>> 8) & 255);
    }

    private static void putBytes(byte[] rgba, int texel, int r, int g, int b, int a) {
        int offset = texel * 4;
        rgba[offset] = (byte) r;
        rgba[offset + 1] = (byte) g;
        rgba[offset + 2] = (byte) b;
        rgba[offset + 3] = (byte) a;
    }

    private static int readU16(byte[] rgba, int texel, int component) {
        int offset = texel * 4 + component;
        return u8(rgba[offset]) | (u8(rgba[offset + 1]) << 8);
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    private ErydonCuPomLookupLayout() {
    }
}
