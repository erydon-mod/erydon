package com.oliver.erydon.command;

import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Changes only the metal of a recognized stone inlay, never solid-metal blocks. */
enum ErydonSwapOverlay {
    BRONZE,
    SILVER;

    private static final List<String> MOTIFS = List.of("trim", "guilloche", "quatrefoil", "rosette");

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<ErydonSwapOverlay> parse(String value) {
        for (ErydonSwapOverlay metal : values()) {
            if (metal.key().equalsIgnoreCase(value)) {
                return Optional.of(metal);
            }
        }
        return Optional.empty();
    }

    Optional<Identifier> targetId(Identifier sourceId, ErydonSwapOverlay target) {
        return ErydonSwapFamilyDatabase.match(sourceId).flatMap(match -> {
            String sourceKey = match.family().canonicalKey();
            if (MOTIFS.stream().noneMatch(motif -> sourceKey.endsWith("_" + motif + "_" + key()))) {
                return Optional.empty();
            }
            String targetKey = sourceKey.substring(0, sourceKey.length() - key().length()) + target.key();
            return ErydonSwapFamilyDatabase.findFamily(targetKey).map(match::targetId);
        });
    }
}
