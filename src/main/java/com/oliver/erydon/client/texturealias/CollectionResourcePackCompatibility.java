package com.oliver.erydon.client.texturealias;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.compat.FamilyReleaseCompatibility;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Distinguishes compatible ERYDON Collection packs from incompatible releases.
 *
 * <p>Minecraft's normal pack format only describes the Minecraft version, so
 * ERYDON's own asset generation needs a separate marker. Small third-party
 * override packs are deliberately left alone.</p>
 */
public final class CollectionResourcePackCompatibility {
    public static final Identifier COLLECTION_MARKER_ID =
            new Identifier("erydon", "erydon_collection_pack.json");

    private static final Logger LOGGER =
            LoggerFactory.getLogger("ERYDON/ResourcePackCompatibility");
    private static final String OWNER_MOD_ID = "erydon";
    private static final int CURRENT_FAMILY_ASSET_GENERATION = 2;
    private static final Pattern FULL_SEMANTIC_VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern RELEASE_NUMBER =
            Pattern.compile("(?<![0-9])(?:v)?[0-9]+\\.[0-9]+\\.[0-9]+(?![0-9])");

    private CollectionResourcePackCompatibility() {
    }

    public static boolean shouldMarkTooOld(String profileId, String description) {
        return shouldMarkTooOld(
                profileId,
                description,
                currentCompatibilityGeneration()
        );
    }

    static boolean shouldMarkTooOld(
            String profileId,
            String description,
            int expectedGeneration
    ) {
        return looksLikeOfficialCollectionPack(profileId, description)
                && !declaresCompatibilityGeneration(
                        profileId,
                        description,
                        expectedGeneration
                );
    }

    public static ResourcePack disableIfLegacy(
            String profileId,
            String description,
            ResourcePack pack
    ) {
        return disableIfLegacy(
                profileId,
                description,
                pack,
                currentCompatibilityGeneration()
        );
    }

    static ResourcePack disableIfLegacy(
            String profileId,
            String description,
            ResourcePack pack,
            int expectedGeneration
    ) {
        if (!looksLikeOfficialCollectionPack(profileId, description)) {
            return pack;
        }

        try {
            if (hasCurrentMarker(pack, expectedGeneration)) {
                return pack;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read the ERYDON Collection compatibility marker from '{}'.",
                    profileId, exception);
        }

        LOGGER.warn("Ignoring assets from incompatible ERYDON Collection pack '{}'.",
                profileId);
        return new DisabledIncompatibleCollectionPack(pack);
    }

    static boolean looksLikeOfficialCollectionPack(String profileId, String description) {
        String signal = (profileId + " " + description).toLowerCase(Locale.ROOT);
        if (!signal.contains("erydon")) {
            return false;
        }

        String compact = signal.replaceAll("[^a-z0-9]+", "");
        boolean knownFamilyName = signal.contains("erydon collection 32x")
                || signal.contains("erydon collection 64x")
                || signal.contains("erydon family") && (
                signal.contains("16x lite") || signal.contains("64x pbr"))
                || compact.contains("erydoncollection32x")
                || compact.contains("erydoncollection64x")
                || compact.contains("erydonrp16xlite")
                || compact.contains("erydonrp64xpbr");
        boolean versionedOfficialShape = signal.contains("erydon")
                && RELEASE_NUMBER.matcher(signal).find()
                && (signal.contains("16x lite")
                || signal.contains("32x")
                || signal.contains("64x pbr")
                || signal.contains("64x resource pack")
                || compact.contains("16xlite")
                || compact.contains("32x")
                || compact.contains("64xpbr")
                || compact.contains("64xrp"));
        return knownFamilyName || versionedOfficialShape;
    }

    private static boolean declaresCompatibilityGeneration(
            String profileId,
            String description,
            int expectedGeneration
    ) {
        String signal = (profileId + " " + description).toLowerCase(Locale.ROOT);
        Pattern expected = Pattern.compile(
                "(?<![a-z0-9])compat" + expectedGeneration + "(?![0-9])"
        );
        return expected.matcher(signal).find();
    }

    private static boolean hasCurrentMarker(
            ResourcePack pack,
            int expectedGeneration
    ) throws IOException {
        InputSupplier<InputStream> supplier =
                pack.open(ResourceType.CLIENT_RESOURCES, COLLECTION_MARKER_ID);
        if (supplier == null) {
            return false;
        }

        try (InputStream input = supplier.get();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonObject marker = JsonParser.parseReader(reader).getAsJsonObject();
            return marker.has("schema")
                    && marker.get("schema").getAsInt() == 1
                    && marker.has("family_asset_generation")
                    && marker.get("family_asset_generation").getAsInt()
                    == CURRENT_FAMILY_ASSET_GENERATION
                    && marker.has("family_compatibility_generation")
                    && marker.get("family_compatibility_generation").getAsInt()
                    == expectedGeneration
                    && marker.has("release")
                    && FULL_SEMANTIC_VERSION.matcher(
                            marker.get("release").getAsString()
                    ).matches()
                    && marker.has("resolution")
                    && (marker.get("resolution").getAsInt() == 32
                    || marker.get("resolution").getAsInt() == 64);
        }
    }

    private static int currentCompatibilityGeneration() {
        return FamilyReleaseCompatibility.currentGeneration(OWNER_MOD_ID);
    }

    private static final class DisabledIncompatibleCollectionPack implements ResourcePack {
        private final ResourcePack delegate;

        private DisabledIncompatibleCollectionPack(ResourcePack delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputSupplier<InputStream> openRoot(String... segments) {
            return delegate.openRoot(segments);
        }

        @Override
        public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
            return null;
        }

        @Override
        public void findResources(
                ResourceType type,
                String namespace,
                String prefix,
                ResultConsumer consumer
        ) {
        }

        @Override
        public java.util.Set<String> getNamespaces(ResourceType type) {
            return java.util.Set.of();
        }

        @Override
        public <T> T parseMetadata(
                net.minecraft.resource.metadata.ResourceMetadataReader<T> metaReader
        ) throws IOException {
            return delegate.parseMetadata(metaReader);
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public boolean isAlwaysStable() {
            return delegate.isAlwaysStable();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
