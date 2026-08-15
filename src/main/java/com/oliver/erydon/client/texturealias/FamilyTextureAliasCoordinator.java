package com.oliver.erydon.client.texturealias;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.Comparator;
import java.util.Map;

/**
 * Elects one resolver when several Erydon-family mods are installed together.
 * Every capable family mod contains the same resolver, so each also works alone.
 */
public final class FamilyTextureAliasCoordinator {
    private static final String OWN_MOD_ID = "erydon";
    private static final String CAPABILITY_KEY = "erydon:texture_alias_resolver";
    private static final Map<String, Integer> LEADER_PRIORITY = Map.of(
            "erydon", 0,
            "erydon_themelios", 1,
            "themelios", 1,
            "daedalon", 2
    );

    private FamilyTextureAliasCoordinator() {
    }

    public static boolean isLeader() {
        return FabricLoader.getInstance()
                .getAllMods()
                .stream()
                .map(ModContainer::getMetadata)
                .filter(FamilyTextureAliasCoordinator::isCapable)
                .min(Comparator
                        .comparingInt(FamilyTextureAliasCoordinator::priority)
                        .thenComparing(ModMetadata::getId))
                .map(metadata -> OWN_MOD_ID.equals(metadata.getId()))
                .orElse(false);
    }

    private static boolean isCapable(ModMetadata metadata) {
        if (!metadata.containsCustomValue(CAPABILITY_KEY)) {
            return false;
        }
        CustomValue value = metadata.getCustomValue(CAPABILITY_KEY);
        return value != null
                && value.getType() == CustomValue.CvType.NUMBER
                && value.getAsNumber().intValue() >= 1;
    }

    private static int priority(ModMetadata metadata) {
        return LEADER_PRIORITY.getOrDefault(metadata.getId(), Integer.MAX_VALUE);
    }
}
