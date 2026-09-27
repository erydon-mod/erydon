package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.MetallicMaterials;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rebuilt once per block-atlas upload. ResourceManager reads also honour virtual texture aliases. */
public final class MetallicLookupTexture {
    public static final Identifier TEXTURE_ID = new Identifier(Erydon.MOD_ID, "metal_lookup");

    private MetallicLookupTexture() { }

    public static void registerPlaceholder() {
        install(createTexture(new byte[4], 1, 1));
    }

    public static void rebuildAfterBlockAtlasUpload(SpriteAtlasTexture atlas, SpriteLoader.StitchResult result) {
        if (!SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(atlas.getId())) return;
        try {
            ResourceManager resources = MinecraftClient.getInstance().getResourceManager();
            List<MetallicLookupLayout.SpriteData> records = new ArrayList<>();
            int skipped = 0;
            List<Sprite> sprites = result.regions().values().stream().distinct()
                    .sorted(Comparator.comparing(sprite -> sprite.getContents().getId().toString())).toList();
            for (Sprite sprite : sprites) {
                Identifier id = sprite.getContents().getId();
                var material = MetallicMaterials.classify(id.getNamespace(), id.getPath());
                // Unknown alloys retain native PBR. Do not decode every ordinary stone
                // sprite merely to discover that it is outside the supported alloy set.
                if (material == null || material.alloy() == MetallicMaterials.AUTHORED) continue;
                try {
                    MetallicLookupLayout.SpriteData record = readSprite(resources, sprite, material);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    skipped++;
                    Erydon.LOGGER.warn("[{}] Metal lookup kept native rendering for {}: {}",
                            Erydon.MOD_ID, id, exception.getMessage());
                }
            }
            MetallicLookupLayout.Encoded encoded = MetallicLookupLayout.encode(result.width(), result.height(), records);
            byte[] rgba = encoded.rgba();
            install(createTexture(rgba, encoded.width(), encoded.height()));
            Erydon.LOGGER.info("[{}] Metal lookup ready: sprites={}, masks={}, skipped={}, runtimeBytes={}; block IDs unchanged.",
                    Erydon.MOD_ID, encoded.recordCount(), encoded.uniqueMasks(), skipped, rgba.length);
        } catch (RuntimeException exception) {
            registerPlaceholder();
            Erydon.LOGGER.warn("[{}] Metal lookup unavailable; native materials retained: {}", Erydon.MOD_ID, exception.getMessage());
        }
    }

    private static MetallicLookupLayout.SpriteData readSprite(ResourceManager resources, Sprite sprite,
                                                               MetallicMaterials.Candidate material) throws IOException {
        Identifier id = sprite.getContents().getId();
        int width = sprite.getContents().getWidth(), height = sprite.getContents().getHeight();
        Identifier specularId = resourceId(id, "_s");
        int[] specular;
        boolean nativePair = false;
        try (NativeImage image = readImage(resources, specularId)) {
            if (image == null) {
                if (!material.pureMetalFallback()) return null;
                specular = null;
            } else {
                requireDimensions(image, width, height, specularId);
                specular = pixels(image);
                boolean anyMetal = false;
                for (int value : specular) if (((value >>> 8) & 255) >= 230) { anyMetal = true; break; }
                if (!anyMetal) {
                    nativePair = MetallicMaterials.nativeBronzeCounterpart(id.getPath()) != null
                            && MetallicMaterials.nativeBronzePlaceholder(width, height, specular);
                    if (!nativePair) return null; // Other explicit nonmetal pack overrides take precedence.
                }
            }
        }
        Identifier albedoId = resourceId(id, "");
        int[] albedo;
        try (NativeImage image = readImage(resources, albedoId)) {
            if (image == null) throw new IllegalArgumentException("Missing albedo resource " + albedoId);
            // Animated strip images differ from the stitched frame; keep their native path until animation is supported.
            requireDimensions(image, width, height, albedoId);
            albedo = pixels(image);
        }
        byte[] coverage = MetallicMaterials.coverage(albedo, specular, material.pureMetalFallback());
        if (nativePair) {
            Identifier counterpart = new Identifier(id.getNamespace(), MetallicMaterials.nativeBronzeCounterpart(id.getPath()));
            Identifier groutId = resourceId(counterpart, "");
            try (NativeImage image = readImage(resources, groutId)) {
                if (image == null) throw new IllegalArgumentException("Missing native bronze counterpart " + groutId);
                requireDimensions(image, width, height, groutId);
                coverage = MetallicMaterials.nativeBronzeCoverage(albedo, pixels(image));
                if (coverage == null) throw new IllegalArgumentException("Native bronze/grout pair changed; explicit PBR mask required");
            }
        }
        boolean anyVisibleMetal = false;
        for (byte value : coverage) if (value != 0) { anyVisibleMetal = true; break; }
        if (!anyVisibleMetal) return null;
        return new MetallicLookupLayout.SpriteData(sprite.getX(), sprite.getY(), width, height,
                material.kind(), material.alloy(), MetallicMaterials.roughness(coverage, specular, material.pureMetalFallback()),
                specular == null && material.pureMetalFallback(), coverage, MetallicMaterials.meanMetalAlbedo(coverage, albedo));
    }

    static Identifier resourceId(Identifier sprite, String suffix) {
        return new Identifier(sprite.getNamespace(), "textures/" + sprite.getPath() + suffix + ".png");
    }

    private static NativeImage readImage(ResourceManager resources, Identifier id) throws IOException {
        var resource = resources.getResource(id);
        if (resource.isEmpty()) return null;
        try (var stream = resource.get().getInputStream()) {
            return NativeImage.read(NativeImage.Format.RGBA, stream);
        }
    }

    private static void requireDimensions(NativeImage image, int width, int height, Identifier id) {
        if (image.getWidth() != width || image.getHeight() != height) {
            throw new IllegalArgumentException("Animated or mismatched image dimensions for " + id);
        }
    }

    private static int[] pixels(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) result[y * width + x] = image.getColor(x, y);
        }
        return result;
    }

    private static NativeImageBackedTexture createTexture(byte[] rgba, int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                image.setColor(x, y, (rgba[i] & 255) | ((rgba[i + 1] & 255) << 8)
                        | ((rgba[i + 2] & 255) << 16) | ((rgba[i + 3] & 255) << 24));
            }
        }
        NativeImageBackedTexture texture = new ClampedNearestTexture(image);
        texture.setFilter(false, false);
        return texture;
    }

    private static void install(NativeImageBackedTexture texture) {
        MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE_ID, texture);
    }

    private static final class ClampedNearestTexture extends NativeImageBackedTexture {
        private ClampedNearestTexture(NativeImage image) { super(image); }
        @Override public void upload() {
            NativeImage image = getImage();
            if (image == null) return;
            bindTexture();
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, true, false, false);
        }
    }
}
