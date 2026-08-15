package com.oliver.erydon.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prevents incompatible ERYDON-family compatibility generations from loading
 * together while leaving each product's semantic version independent.
 */
public final class FamilyReleaseCompatibility {
    public static final String COMPATIBILITY_GENERATION_KEY =
            "erydon:family_compatibility_generation";

    private static final List<String> FAMILY_MOD_IDS = List.of(
            "erydon",
            "themelios",
            "erydon_themelios",
            "daedalon"
    );

    private FamilyReleaseCompatibility() {
    }

    public static void enforce(String ownerModId) {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer owner = loader.getModContainer(ownerModId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing ERYDON-family mod container: " + ownerModId
                ));
        int expectedGeneration = readRequiredGeneration(owner.getMetadata());
        Map<String, String> installedVersions = new LinkedHashMap<>();
        Map<String, Integer> installedGenerations = new LinkedHashMap<>();
        for (String modId : FAMILY_MOD_IDS) {
            loader.getModContainer(modId).ifPresent(container -> {
                ModMetadata metadata = container.getMetadata();
                installedVersions.put(
                        modId,
                        metadata.getVersion().getFriendlyString()
                );
                Integer generation = readGeneration(metadata);
                if (generation != null) {
                    installedGenerations.put(modId, generation);
                }
            });
        }

        List<String> mismatches =
                findGenerationMismatches(
                        expectedGeneration,
                        installedVersions,
                        installedGenerations
                );
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(
                    "Incompatible ERYDON-family compatibility generations. "
                            + owner.getMetadata().getName() + " "
                            + owner.getMetadata().getVersion().getFriendlyString()
                            + " uses compatibility generation " + expectedGeneration
                            + " and requires every installed ERYDON-family mod to use "
                            + "that generation; found "
                            + String.join(", ", mismatches)
                            + ". Install ERYDON, ERYDON Themelios and Daedalon builds "
                            + "with the same compat number."
            );
        }
    }

    public static int currentGeneration(String ownerModId) {
        ModMetadata metadata = FabricLoader.getInstance()
                .getModContainer(ownerModId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing ERYDON-family mod container: " + ownerModId
                ))
                .getMetadata();
        return readRequiredGeneration(metadata);
    }

    static List<String> findGenerationMismatches(
            int expectedGeneration,
            Map<String, String> installedVersions,
            Map<String, Integer> installedGenerations
    ) {
        List<String> mismatches = new ArrayList<>();
        installedVersions.forEach((modId, version) -> {
            Integer generation = installedGenerations.get(modId);
            if (generation == null) {
                mismatches.add(
                        modId + " " + version
                                + " (missing compatibility generation)"
                );
            } else if (generation != expectedGeneration) {
                mismatches.add(
                        modId + " " + version
                                + " (compatibility generation " + generation + ")"
                );
            }
        });
        return List.copyOf(mismatches);
    }

    private static int readRequiredGeneration(ModMetadata metadata) {
        Integer generation = readGeneration(metadata);
        if (generation == null) {
            throw new IllegalStateException(
                    metadata.getName() + " "
                            + metadata.getVersion().getFriendlyString()
                            + " is missing a valid "
                            + COMPATIBILITY_GENERATION_KEY + " value."
            );
        }
        return generation;
    }

    private static Integer readGeneration(ModMetadata metadata) {
        if (!metadata.containsCustomValue(COMPATIBILITY_GENERATION_KEY)) {
            return null;
        }
        CustomValue value = metadata.getCustomValue(COMPATIBILITY_GENERATION_KEY);
        if (value == null || value.getType() != CustomValue.CvType.NUMBER) {
            return null;
        }
        double raw = value.getAsNumber().doubleValue();
        int generation = value.getAsNumber().intValue();
        return generation > 0 && raw == generation ? generation : null;
    }
}
