package com.oliver.erydon.client;

import java.util.Set;

/** Material selection only: used during client setup/model baking, never per quad. */
public final class PolishedStoneMaterials {
    private static final Set<String> MATERIALS = Set.of(
            "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
            "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
            "kylorion", "laurentium", "mielonyx", "nerium", "noxoplis", "porphyros",
            "portorium", "rosinium", "sanguenite", "selenephos", "solistra", "striatus");
    private static final Set<String> EXCLUDED_FINISHES = Set.of(
            "aged", "ashlar", "hewn", "rusticated", "rock", "diaphanes");

    private PolishedStoneMaterials() { }

    public static boolean includes(String namespace, String path) {
        if (!"erydon".equals(namespace)) return false;
        String[] parts = path.split("_");
        if (parts.length < 2 || !MATERIALS.contains(parts[0])) return false;
        for (String part : parts) {
            if (EXCLUDED_FINISHES.contains(part)) return false;
        }
        return true;
    }
}
