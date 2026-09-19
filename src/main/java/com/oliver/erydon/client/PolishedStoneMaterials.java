package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import com.oliver.erydon.HighPolishSettings.Finish;
import java.util.Set;

/** Material selection only: used during client setup/model baking, never per quad. */
public final class PolishedStoneMaterials {
    private static final Set<String> MATERIALS = Set.copyOf(HighPolishSettings.MATERIALS);
    private static final Set<String> INLAYS = Set.of("_trim_", "_guilloche_", "_quatrefoil_", "_rosette_");
    private static final Set<String> EXCLUDED_FINISHES = Set.of(
            "aged", "ashlar", "hewn", "rusticated", "rock", "diaphanes");

    private PolishedStoneMaterials() { }

    public static boolean enabled(HighPolishSettings settings, String namespace, String path) {
        if (!settings.enabled() || !includes(namespace, path)) return false;
        String material = path.substring(0, path.indexOf('_'));
        return settings.enables(material, finish(path));
    }

    static Finish finish(String path) {
        if (path.contains("_herringbone_")) return Finish.HERRINGBONE;
        if (path.contains("_weave_")) return Finish.WEAVE;
        for (String motif : INLAYS) {
            if (path.contains(motif)) return Finish.INLAYS;
        }
        return Finish.PLAIN;
    }

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
