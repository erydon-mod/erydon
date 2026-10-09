package com.oliver.erydon.client.pom;

import com.oliver.erydon.client.MetallicMaterials;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact RGBA8 lookup with categorical finish records and independently filtered metal coverage. */
public final class MetallicLookupLayout {
    public static final int WIDTH = 1024;
    public static final int MAX_HEIGHT = 8192;
    public static final int MAX_BYTES = WIDTH * MAX_HEIGHT * 4;
    public static final int QUANTUM = 16;
    public static final int MAX_ATLAS_SIZE = 16384;
    public static final int HEADER_TEXELS = 4;
    public static final int RECORD_TEXELS = 5;
    public static final int UNIFORM = 1;
    public static final int PURE_METAL_FALLBACK = 2;

    public record SpriteData(int x, int y, int width, int height, int kind, int alloy,
                             int roughness, boolean pureMetalFallback, byte[] coverage, int metalAlbedoAbgr) {
        public SpriteData(int x, int y, int width, int height, int kind, int alloy,
                          int roughness, boolean pureMetalFallback, byte[] coverage) {
            this(x, y, width, height, kind, alloy, roughness, pureMetalFallback, coverage,
                    alloy == 1 ? 0xff1da0e5 : 0xffe6e6e6);
        }
        public SpriteData {
            if (x < 0 || y < 0 || width < QUANTUM || height < QUANTUM || width > MAX_ATLAS_SIZE
                    || height > MAX_ATLAS_SIZE || x % QUANTUM != 0 || y % QUANTUM != 0
                    || width % QUANTUM != 0 || height % QUANTUM != 0
                    || Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1) {
                throw new IllegalArgumentException("Metal sprite must have power-of-two, 16-aligned atlas bounds");
            }
            if (coverage.length != (long) width * height || kind < 1 || kind > MetallicMaterials.GLOSS_COVER
                    || alloy < 0 || alloy > 2 || roughness < 0 || roughness > 255) {
                throw new IllegalArgumentException("Invalid metal sprite payload");
            }
            if (kind == MetallicMaterials.GLOSS_COVER
                    && (alloy != MetallicMaterials.AUTHORED || roughness != 0 || pureMetalFallback || anyCoverage(coverage))) {
                throw new IllegalArgumentException("Gloss cover markers must not contain metal");
            }
            coverage = coverage.clone();
            metalAlbedoAbgr |= 0xff000000;
        }
        @Override public byte[] coverage() { return coverage.clone(); }
    }

    public record Encoded(int width, int height, int recordCount, int uniqueMasks, byte[] rgba) {
        public Encoded { rgba = rgba.clone(); }
        @Override public byte[] rgba() { return rgba.clone(); }
    }

    private MetallicLookupLayout() { }

    /** A categorical inset marker carries no metal mask or authored metal colour. */
    public static SpriteData glossCover(int x, int y, int width, int height) {
        return new SpriteData(x, y, width, height, MetallicMaterials.GLOSS_COVER,
                MetallicMaterials.AUTHORED, 0, false, new byte[Math.multiplyExact(width, height)], 0xff000000);
    }

    public static Encoded encode(int atlasWidth, int atlasHeight, List<SpriteData> sprites) {
        if (atlasWidth <= 0 || atlasHeight <= 0 || atlasWidth > MAX_ATLAS_SIZE || atlasHeight > MAX_ATLAS_SIZE) {
            throw new IllegalArgumentException("Metal lookup atlas size exceeds 16384");
        }
        int columns = (atlasWidth + QUANTUM - 1) / QUANTUM;
        int rows = (atlasHeight + QUANTUM - 1) / QUANTUM;
        List<SpriteData> active = sprites.stream().filter(sprite -> sprite.kind == MetallicMaterials.GLOSS_COVER
                || anyCoverage(sprite.coverage)).toList();
        long recordsBase = HEADER_TEXELS + (long) columns * rows;
        long masksBase = (recordsBase + (long) active.size() * RECORD_TEXELS) * 4;
        requireCapacity(masksBase);
        Map<MaskKey, Integer> maskOffsets = new LinkedHashMap<>();
        ByteArrayOutputStream masks = new ByteArrayOutputStream();
        List<Integer> starts = new ArrayList<>();
        for (SpriteData sprite : active) {
            if ((long) sprite.x + sprite.width > atlasWidth || (long) sprite.y + sprite.height > atlasHeight) {
                throw new IllegalArgumentException("Metal sprite extends outside atlas");
            }
            if (sprite.kind == MetallicMaterials.GLOSS_COVER || uniform(sprite.coverage)) {
                starts.add(0);
                continue;
            }
            MaskKey key = new MaskKey(sprite.width, sprite.height, sprite.coverage);
            Integer offset = maskOffsets.get(key);
            if (offset == null) {
                requireCapacity(masksBase + masks.size() + pyramidSize(sprite.width, sprite.height));
                byte[] pyramid = pyramid(sprite.coverage, sprite.width, sprite.height);
                requireCapacity(masksBase + masks.size() + pyramid.length);
                offset = (int) masksBase + masks.size();
                maskOffsets.put(key, offset);
                masks.writeBytes(pyramid);
            }
            starts.add(offset);
        }
        int size = (int) masksBase + masks.size();
        int height = Math.max(1, (size + WIDTH * 4 - 1) / (WIDTH * 4));
        requireCapacity((long) WIDTH * height * 4);
        byte[] rgba = new byte[WIDTH * height * 4];
        putU16Pair(rgba, 0, atlasWidth, atlasHeight);
        putU16Pair(rgba, 1, columns, rows);
        putU32(rgba, 2, (int) recordsBase);
        rgba[12] = 77; rgba[13] = 69; rgba[14] = 84; rgba[15] = 1;
        for (int i = 0; i < active.size(); i++) {
            SpriteData sprite = active.get(i);
            int record = (int) recordsBase + i * RECORD_TEXELS;
            putU16Pair(rgba, record, sprite.x, sprite.y);
            putU16Pair(rgba, record + 1, sprite.width, sprite.height);
            int meta = (record + 2) * 4;
            rgba[meta] = (byte) sprite.kind;
            rgba[meta + 1] = (byte) sprite.alloy;
            rgba[meta + 2] = (byte) sprite.roughness;
            rgba[meta + 3] = (byte) ((starts.get(i) == 0 && sprite.kind != MetallicMaterials.GLOSS_COVER ? UNIFORM : 0)
                    | (sprite.pureMetalFallback ? PURE_METAL_FALLBACK : 0));
            putU32(rgba, record + 3, starts.get(i));
            putU32(rgba, record + 4, sprite.metalAlbedoAbgr);
            for (int y = sprite.y / QUANTUM; y < (sprite.y + sprite.height) / QUANTUM; y++) {
                for (int x = sprite.x / QUANTUM; x < (sprite.x + sprite.width) / QUANTUM; x++) {
                    int cell = HEADER_TEXELS + y * columns + x;
                    if (getU32(rgba, cell) != 0) throw new IllegalArgumentException("Metal sprites overlap an atlas cell");
                    putU32(rgba, cell, record);
                }
            }
        }
        System.arraycopy(masks.toByteArray(), 0, rgba, (int) masksBase, masks.size());
        return new Encoded(WIDTH, height, active.size(), maskOffsets.size(), rgba);
    }

    /** Quantize each level directly from original coverage to avoid accumulated rounding loss. */
    public static byte[] pyramid(byte[] original, int width, int height) {
        if (width <= 0 || height <= 0 || original.length != (long) width * height
                || Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1) {
            throw new IllegalArgumentException("Coverage pyramid requires power-of-two dimensions");
        }
        requireCapacity(pyramidSize(width, height));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(original);
        int w = width, h = height;
        while (w > 1 || h > 1) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            int area = (width / w) * (height / h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    long sum = 0;
                    for (int sy = y * height / h; sy < (y + 1) * height / h; sy++) {
                        for (int sx = x * width / w; sx < (x + 1) * width / w; sx++) {
                            sum += original[sy * width + sx] & 255;
                        }
                    }
                    output.write((int) ((sum + area / 2) / area));
                }
            }
        }
        return output.toByteArray();
    }

    private static long pyramidSize(int width, int height) {
        long bytes = 0;
        while (true) {
            bytes += (long) width * height;
            if (width == 1 && height == 1) return bytes;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
    }

    private static boolean anyCoverage(byte[] coverage) {
        for (byte value : coverage) if (value != 0) return true;
        return false;
    }
    private static boolean uniform(byte[] coverage) {
        for (byte value : coverage) if (value != (byte) 255) return false;
        return true;
    }
    private static void requireCapacity(long bytes) {
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Metal lookup exceeds its 32 MiB limit");
    }
    private static void putU16Pair(byte[] rgba, int texel, int first, int second) {
        putU32(rgba, texel, first | (second << 16));
    }
    private static void putU32(byte[] rgba, int texel, int value) {
        for (int i = 0; i < 4; i++) rgba[texel * 4 + i] = (byte) (value >>> (i * 8));
    }
    static int getU32(byte[] rgba, int texel) {
        int result = 0;
        for (int i = 0; i < 4; i++) result |= (rgba[texel * 4 + i] & 255) << (i * 8);
        return result;
    }
    private record MaskKey(int width, int height, byte[] coverage) {
        @Override public boolean equals(Object other) {
            return other instanceof MaskKey key && width == key.width && height == key.height
                    && Arrays.equals(coverage, key.coverage);
        }
        @Override public int hashCode() { return 31 * (31 * width + height) + Arrays.hashCode(coverage); }
    }
}
