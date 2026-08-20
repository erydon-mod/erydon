package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Owns the invalid placeholder and the post-atlas ERYDON repeat-CTM lookup texture. */
public final class ErydonCuPomLookupTexture {
    public static final Identifier TEXTURE_ID = new Identifier(Erydon.MOD_ID, "ctm_pom_lookup");

    public static void registerPlaceholder() {
        install(createInvalidTexture());
    }

    public static void rebuildAfterBlockAtlasUpload(SpriteAtlasTexture atlas) {
        if (!SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(atlas.getId())) {
            return;
        }
        try {
            List<ErydonCuPomFamilyDiscovery.Family> activeFamilies =
                    ErydonCuPomFamilyDiscovery.discover(MinecraftClient.getInstance().getResourceManager());
            List<ErydonCuPomLookupPlan.SpriteFamily> stitchedFamilies = new ArrayList<>(activeFamilies.size());
            for (ErydonCuPomFamilyDiscovery.Family family : activeFamilies) {
                stitchedFamilies.add(new ErydonCuPomLookupPlan.SpriteFamily(
                        family.name(), readPhases(atlas, family)));
            }
            ErydonCuPomLookupLayout.Encoded encoded =
                    ErydonCuPomLookupPlan.buildFamilies(stitchedFamilies);
            install(createTexture(encoded.rgba()));
            Erydon.LOGGER.info(
                    "[{}] CTM-POM lookup ready: families={}, phases={}, atlas={}x{}, runtimeBytes={}.",
                    Erydon.MOD_ID,
                    encoded.familyCount(),
                    encoded.recordCount(),
                    encoded.atlasWidth(),
                    encoded.atlasHeight(),
                    ErydonCuPomLookupLayout.RUNTIME_BYTES);
        } catch (RuntimeException exception) {
            install(createInvalidTexture());
            Erydon.LOGGER.warn(
                    "[{}] CTM-POM lookup validation failed; the invalid placeholder remains active: {}",
                    Erydon.MOD_ID,
                    exception.getMessage());
        }
    }

    private static List<ErydonCuPomLookupPlan.SpritePhase> readPhases(
            SpriteAtlasTexture atlas,
            ErydonCuPomFamilyDiscovery.Family family
    ) {
        List<ErydonCuPomLookupPlan.SpritePhase> phases =
                new ArrayList<>(ErydonCuPomLookupLayout.PHASES_PER_FAMILY);
        for (int phase = 0; phase < ErydonCuPomLookupLayout.PHASES_PER_FAMILY; phase++) {
            Identifier spriteId = family.phases().get(phase);
            Sprite sprite = atlas.getSprite(spriteId);
            if (sprite == null || !spriteId.equals(sprite.getContents().getId())) {
                throw new IllegalArgumentException(
                        "Missing stitched sprite " + spriteId + " for " + family.name());
            }
            int width = sprite.getContents().getWidth();
            int height = sprite.getContents().getHeight();
            int atlasWidth = inferAtlasDimension(
                    width, sprite.getMaxU() - sprite.getMinU(), "width", spriteId);
            int atlasHeight = inferAtlasDimension(
                    height, sprite.getMaxV() - sprite.getMinV(), "height", spriteId);
            int centreX = Math.round(sprite.getX() + width * 0.5F);
            int centreY = Math.round(sprite.getY() + height * 0.5F);
            phases.add(new ErydonCuPomLookupPlan.SpritePhase(
                    atlasWidth, atlasHeight, width, height, centreX, centreY));
        }
        return phases;
    }

    private static int inferAtlasDimension(int spriteSize, float uvSpan, String axis, Identifier spriteId) {
        if (!(uvSpan > 0.0F) || !Float.isFinite(uvSpan)) {
            throw new IllegalArgumentException("Invalid " + axis + " UV span for " + spriteId);
        }
        double exact = spriteSize / (double) uvSpan;
        int rounded = (int) Math.round(exact);
        if (rounded <= 0 || rounded > 0xFFFF || Math.abs(exact - rounded) > 0.25D) {
            throw new IllegalArgumentException("Cannot resolve atlas " + axis + " for " + spriteId);
        }
        return rounded;
    }

    private static NativeImageBackedTexture createInvalidTexture() {
        return createTexture(new byte[4]);
    }

    private static NativeImageBackedTexture createTexture(byte[] rgba) {
        int dimension = rgba.length == 4 ? 1 : ErydonCuPomLookupLayout.DIMENSION;
        if (rgba.length != dimension * dimension * 4) {
            throw new IllegalArgumentException("Unexpected lookup byte count: " + rgba.length);
        }
        NativeImage image = new NativeImage(dimension, dimension, false);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                int offset = (y * dimension + x) * 4;
                int r = rgba[offset] & 0xFF;
                int g = rgba[offset + 1] & 0xFF;
                int b = rgba[offset + 2] & 0xFF;
                int a = rgba[offset + 3] & 0xFF;
                image.setColor(x, y, r | (g << 8) | (b << 16) | (a << 24));
            }
        }
        NativeImageBackedTexture texture = new ClampedNearestTexture(image);
        texture.setFilter(false, false);
        return texture;
    }

    private static void install(NativeImageBackedTexture texture) {
        MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE_ID, texture);
        // TextureManager closes and releases the previous texture registered at this identifier.
    }

    private static final class ClampedNearestTexture extends NativeImageBackedTexture {
        private ClampedNearestTexture(NativeImage image) {
            super(image);
        }

        @Override
        public void upload() {
            NativeImage image = getImage();
            if (image == null) {
                return;
            }
            bindTexture();
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, true, false, false);
        }
    }

    private ErydonCuPomLookupTexture() {
    }
}
