package com.oliver.erydon.client.pom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Encoder for ERYDON's runtime-only CTM-POM lookup protocol. */
public final class ErydonCuPomLookupLayout {
    public static final int PROTOCOL_VERSION = 3;
    public static final int DIMENSION = 256;
    public static final int RUNTIME_BYTES = DIMENSION * DIMENSION * 4;
    public static final int HASH_START = 16;
    // A prime slot count avoids clustering for atlas centres aligned to powers of two.
    public static final int HASH_SLOTS = 24571;
    public static final int CENTRE_START = HASH_START + HASH_SLOTS * 2;
    public static final int PHASES_PER_FAMILY = 36;
    public static final int MAX_PROBES = 16;
    private static final int TEXELS = DIMENSION * DIMENSION;
    static final int MAX_RECORDS = Math.min(TEXELS - CENTRE_START, HASH_SLOTS);
    static final int MAX_FAMILIES = MAX_RECORDS / PHASES_PER_FAMILY;

    public record SpriteCentre(int x, int y) {
        public SpriteCentre {
            if (x < 0 || x > 0xFFFF || y < 0 || y > 0xFFFF) {
                throw new IllegalArgumentException("Sprite centre exceeds unsigned 16-bit range");
            }
        }
    }

    public record Encoded(int atlasWidth, int atlasHeight, int familyCount, int recordCount, byte[] rgba) {
        public Encoded {
            rgba = rgba.clone();
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }
    }

    /** Every family must contain exactly 36 stitched centres in phase order 0..35. */
    public static Encoded encode(int atlasWidth, int atlasHeight, List<List<SpriteCentre>> families) {
        Objects.requireNonNull(families, "families");
        if (atlasWidth <= 0 || atlasWidth > 0xFFFF || atlasHeight <= 0 || atlasHeight > 0xFFFF) {
            throw new IllegalArgumentException("Invalid atlas dimensions");
        }

        List<SpriteCentre> flat = new ArrayList<>();
        Set<SpriteCentre> allCentres = new HashSet<>();
        for (int familyIndex = 0; familyIndex < families.size(); familyIndex++) {
            List<SpriteCentre> family = Objects.requireNonNull(families.get(familyIndex), "family " + familyIndex);
            if (family.size() != PHASES_PER_FAMILY) {
                throw new IllegalArgumentException(
                        "Family " + familyIndex + " has " + family.size() + " phases; expected 36");
            }
            if (new HashSet<>(family).size() != PHASES_PER_FAMILY) {
                throw new IllegalArgumentException(
                        "Family " + familyIndex + " contains duplicate stitched sprite centres");
            }
            for (SpriteCentre centre : family) {
                if (!allCentres.add(centre)) {
                    throw new IllegalArgumentException("A stitched centre belongs to multiple records: " + centre);
                }
                flat.add(centre);
            }
        }
        if (flat.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Too many CTM phase records: " + flat.size());
        }

        byte[] rgba = new byte[RUNTIME_BYTES];
        putBytes(rgba, 0, 69, 67, 80, PROTOCOL_VERSION); // ECP protocol
        putU16Pair(rgba, 1, atlasWidth, atlasHeight);
        putU16Pair(rgba, 2, flat.size(), families.size());

        for (int record = 0; record < flat.size(); record++) {
            SpriteCentre centre = flat.get(record);
            int first = hash(centre.x(), centre.y());
            boolean inserted = false;
            for (int probe = 0; probe < MAX_PROBES; probe++) {
                int slot = (first + probe) % HASH_SLOTS;
                int keyTexel = HASH_START + slot * 2;
                int payloadTexel = keyTexel + 1;
                if (u8(rgba[payloadTexel * 4 + 2]) == 0) {
                    putU16Pair(rgba, keyTexel, centre.x(), centre.y());
                    putBytes(rgba, payloadTexel, record & 255, (record >>> 8) & 255, 255, 0);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                throw new IllegalStateException("Hash table exceeded " + MAX_PROBES + " probes at record " + record);
            }
            putU16Pair(rgba, CENTRE_START + record, centre.x(), centre.y());
        }
        return new Encoded(atlasWidth, atlasHeight, families.size(), flat.size(), rgba);
    }

    static int findRecord(byte[] rgba, int centreX, int centreY) {
        int first = hash(centreX, centreY);
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int slot = (first + probe) % HASH_SLOTS;
            int keyTexel = HASH_START + slot * 2;
            int payloadTexel = keyTexel + 1;
            if (u8(rgba[payloadTexel * 4 + 2]) == 0) {
                return -1;
            }
            if (readU16(rgba, keyTexel, 0) == centreX && readU16(rgba, keyTexel, 2) == centreY) {
                int record = readU16(rgba, payloadTexel, 0);
                return record < readU16(rgba, 2, 0) ? record : -1;
            }
        }
        return -1;
    }

    static int targetRecord(int currentRecord, int deltaX, int deltaY) {
        int familyBase = Math.floorDiv(currentRecord, PHASES_PER_FAMILY) * PHASES_PER_FAMILY;
        int phase = currentRecord - familyBase;
        int targetX = Math.floorMod(phase % 6 + deltaX, 6);
        int targetY = Math.floorMod(phase / 6 + deltaY, 6);
        return familyBase + targetY * 6 + targetX;
    }

    private static int hash(int x, int y) {
        return Math.floorMod(x * 73 + y * 151, HASH_SLOTS);
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
