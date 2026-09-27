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

    public static HighPolishSettings.Level level(HighPolishSettings settings, String path) {
        return settings.level(path.substring(0, path.indexOf('_')), finish(path));
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
        // Companion mods share these stone textures and the same per-material finish choices.
        if (!"erydon".equals(namespace) && !"themelios".equals(namespace) && !"daedalon".equals(namespace)) return false;
        String[] parts = path.split("_");
        if (parts.length < 2 || !MATERIALS.contains(parts[0])) return false;
        for (String part : parts) {
            if (EXCLUDED_FINISHES.contains(part)) return false;
        }
        return true;
    }

    /** The shader's inexpensive square proxy for circular, Gothic and square columns. */
    public static boolean isReflectionColumn(String path) {
        if (!path.contains("_column_")) return false;
        if (!includes("erydon", path)) return false;
        return path.endsWith("_column_circular") || path.endsWith("_column_circular_double")
                || path.endsWith("_column_gothic")
                || path.endsWith("_column_square");
    }
}
