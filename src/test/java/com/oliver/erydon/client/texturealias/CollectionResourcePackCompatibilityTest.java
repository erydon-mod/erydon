package com.oliver.erydon.client.texturealias;

import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionResourcePackCompatibilityTest {
    private static final String CURRENT_MARKER =
            "{\"schema\":1,\"family_asset_generation\":2,"
                    + "\"family_compatibility_generation\":2,"
                    + "\"release\":\"2.0.0\",\"resolution\":32}";
    private static final String FUTURE_MARKER =
            "{\"schema\":1,\"family_asset_generation\":3,"
                    + "\"family_compatibility_generation\":3,"
                    + "\"release\":\"3.0.0\",\"resolution\":64}";

    @Test
    void marksKnownOfficialOnePointFiveTwelvePackTooOld() {
        assertTrue(CollectionResourcePackCompatibility.shouldMarkTooOld(
                "file/ERYDON-Collection-32x-v1.5.12.zip",
                "ERYDON Collection 32x v1.5.12",
                2
        ));
    }

    @Test
    void acceptsMatchingCompatibilityDenominatorInPackProfile() {
        assertFalse(CollectionResourcePackCompatibility.shouldMarkTooOld(
                "file/ERYDON-Collection-64x-compat2-v2.0.0.zip",
                "ERYDON Collection 64x",
                2
        ));
    }

    @Test
    void recognizesTheActualLegacyOnePointFiveTwoPack() {
        assertTrue(CollectionResourcePackCompatibility.shouldMarkTooOld(
                "file/Erydon_1.5.2_64xPBR.zip",
                "Erydon 1.5.2 - 64x Resource Pack (CTM)",
                2
        ));
    }

    @Test
    void differentCompatibilityDenominatorIsRejected() {
        assertTrue(CollectionResourcePackCompatibility.shouldMarkTooOld(
                "file/ERYDON-Collection-64x-compat1-v2.0.0.zip",
                "ERYDON Collection 64x",
                2
        ));
    }

    @Test
    void disablesKnownOfficialPackWithoutGenerationMarker() {
        TextureAliasTestSupport.FakePack oldPack =
                new TextureAliasTestSupport.FakePack("old-collection");
        ResourcePack disabled = CollectionResourcePackCompatibility.disableIfLegacy(
                "file/ERYDON-Collection-32x-v1.5.12.zip",
                "ERYDON Collection 32x v1.5.12",
                oldPack,
                2
        );

        assertNotSame(oldPack, disabled);
        assertTrue(disabled.getNamespaces(ResourceType.CLIENT_RESOURCES).isEmpty());
        disabled.close();
    }

    @Test
    void acceptsMatchingGenerationMarkerAndLeavesPhysicalOverridesAlone() {
        TextureAliasTestSupport.FakePack currentPack =
                new TextureAliasTestSupport.FakePack("current-collection");
        currentPack.put(
                CollectionResourcePackCompatibility.COLLECTION_MARKER_ID,
                CURRENT_MARKER
        );
        assertSame(currentPack, CollectionResourcePackCompatibility.disableIfLegacy(
                "file/ERYDON-Collection-32x-compat2-v2.0.0.zip",
                "ERYDON Collection 32x",
                currentPack,
                2
        ));

        TextureAliasTestSupport.FakePack physicalOverride =
                new TextureAliasTestSupport.FakePack("physical-override");
        assertSame(physicalOverride, CollectionResourcePackCompatibility.disableIfLegacy(
                "file/my-erydon-block-fix.zip",
                "One ERYDON block override",
                physicalOverride,
                2
        ));
    }

    @Test
    void packReleaseIsIndependentFromModVersionButGenerationMustMatch() {
        TextureAliasTestSupport.FakePack oldPack =
                new TextureAliasTestSupport.FakePack("future-generation-pack");
        oldPack.put(
                CollectionResourcePackCompatibility.COLLECTION_MARKER_ID,
                FUTURE_MARKER
        );
        ResourcePack disabled = CollectionResourcePackCompatibility.disableIfLegacy(
                "file/ERYDON-Collection-64x-compat3-v3.0.0.zip",
                "ERYDON Collection 64x",
                oldPack,
                2
        );
        assertNotSame(oldPack, disabled);
        assertTrue(disabled.getNamespaces(ResourceType.CLIENT_RESOURCES).isEmpty());
        disabled.close();

        TextureAliasTestSupport.FakePack currentPack =
                new TextureAliasTestSupport.FakePack("current-generation-pack");
        currentPack.put(
                CollectionResourcePackCompatibility.COLLECTION_MARKER_ID,
                CURRENT_MARKER
        );
        assertSame(currentPack, CollectionResourcePackCompatibility.disableIfLegacy(
                "file/ERYDON-Collection-32x-compat2-v2.0.0.zip",
                "ERYDON Collection 32x",
                currentPack,
                2
        ));
    }
}
