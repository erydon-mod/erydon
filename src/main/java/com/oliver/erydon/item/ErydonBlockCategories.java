package com.oliver.erydon.item;

import com.oliver.erydon.util.ErydonIdNaming;
import com.oliver.erydon.migration.ErydonIdMigration;

import java.util.LinkedHashSet;
import java.util.List;

public final class ErydonBlockCategories {
    private ErydonBlockCategories() {
    }

    public static boolean belongsToMaterial(String path, String material) {
        return path.equals(material)
                || path.startsWith(material + "_");
    }

    public static boolean isSlab(String path) {
        return matchesSuffix(path, "_slab");
    }

    public static boolean isStairs(String path) {
        return matchesSuffix(path, "_stairs")
                || matchesSuffix(path, "_stairs_shallow_bottom")
                || matchesSuffix(path, "_stairs_shallow_top")
                || matchesSuffix(path, "_stairs_spiral_large");
    }

    public static boolean isWall(String path) {
        return (matchesSuffix(path, "_wall") || matchesSuffix(path, "_wall_georgian")) && !isLight(path);
    }

    public static boolean isLayer(String path) {
        return matchesSuffix(path, "_layer")
                || matchesSuffix(path, "_layer_multiface")
                || matchesSuffix(path, "_layer_vertical");
    }

    public static boolean isSliceOrPost(String path) {
        return matchesSuffix(path, "_vertical_slice")
                || matchesSuffix(path, "_horizontal_slice")
                || matchesSuffix(path, "_post");
    }

    public static boolean isSlope(String path) {
        return path.contains("_slope");
    }

    public static boolean isWindow(String path) {
        return path.contains("_window_");
    }

    public static boolean isLight(String path) {
        return matchesSuffix(path, "_light_modern")
                || matchesSuffix(path, "_light_wall")
                || matchesSuffix(path, "_light_pendant");
    }

    public static boolean isColumn(String path) {
        return path.contains("_column_");
    }

    public static boolean isArch(String path) {
        return path.contains("_arch_") && !isWindow(path);
    }

    public static boolean isCornice(String path) {
        return path.contains("_cornice_");
    }

    public static boolean isChimney(String path) {
        return path.contains("_chimney_");
    }

    public static boolean isSurround(String path) {
        return path.contains("_surround_");
    }

    public static boolean isCover(String path) {
        return path.startsWith("cover_");
    }

    public static boolean isCeiling(String path) {
        return path.contains("ceiling_coffered");
    }

    public static boolean isGlass(String path) {
        return path.startsWith("glazing_") || path.contains("_diaphanes");
    }

    public static boolean isWeave(String path) {
        return path.contains("_weave_");
    }

    public static boolean isDecoratedBlock(String path) {
        path = ErydonIdMigration.legacyResourcePath(path);
        return path.contains("_block_bronzetrim")
                || path.contains("_block_silvertrim")
                || path.contains("_block_bronzeguilloche")
                || path.contains("_block_silverguilloche")
                || path.contains("_block_bronzequatrefoil")
                || path.contains("_block_silverquatrefoil")
                || path.contains("_block_bronzerose")
                || path.contains("_block_silverrose");
    }

    public static boolean isAged(String path) {
        return ErydonIdNaming.isAged(path);
    }

    public static boolean isArchitecture(String path) {
        return isArch(path)
                || isColumn(path)
                || isChimney(path)
                || isCornice(path)
                || isSurround(path)
                || isWindow(path);
    }

    public static List<String> searchTerms(String path) {
        LinkedHashSet<String> terms = new LinkedHashSet<>(ErydonMaterialSources.findSearchTerms(path));
        terms.addAll(ErydonIdMigration.searchTermsForCanonicalPath(path));

        if (isStairs(path)) {
            addAll(terms, "steps", "staircase");
            if (path.contains("shallow")) {
                terms.add("shallow steps");
            }
            if (path.contains("spiral")) {
                terms.add("spiral staircase");
            }
        }
        if (isLayer(path)) {
            addAll(terms, "thin", "cladding", "panel");
        }
        if (isSliceOrPost(path)) {
            addAll(terms, "thin", "slice", "eighth");
            if (ErydonIdNaming.withoutAged(path).endsWith("_post")) {
                addAll(terms, "beam", "rod");
            }
        }
        if (isSlope(path)) {
            addAll(terms, "ramp", "wedge", "roof");
        }
        if (isWall(path)) {
            addAll(terms, "fence", "barrier");
        }
        if (isGlass(path)) {
            addAll(terms, "glass", "pane", "transparent");
        }
        if (isWindow(path)) {
            addAll(terms, "window", "frame");
        }
        if (isLight(path)) {
            addAll(terms, "lamp", "glow");
        }
        if (isColumn(path)) {
            addAll(terms, "pillar", "support", "capital", "column capital",
                    "guilloche", "guilloche capital", "byzantine", "byzantine capital");
        }
        if (isArch(path)) {
            addAll(terms, "archway", "doorway");
        }
        if (isCornice(path) || isSurround(path) || isDecoratedBlock(path)) {
            addAll(terms, "trim", "decorative");
        }
        if (isCover(path)) {
            addAll(terms, "cover", "panel");
        }
        if (isCeiling(path)) {
            addAll(terms, "ceiling", "coffered");
        }
        if (isWeave(path)) {
            addAll(terms, "mixed", "stone weave");
        }
        if (isAged(path)) {
            addAll(terms, "aged", "weathered");
        }
        if (path.contains("_ashlar")) {
            terms.add("masonry");
        }
        if (path.contains("_herringbone_")) {
            terms.add("zigzag");
        }

        return List.copyOf(terms);
    }

    private static boolean matchesSuffix(String path, String suffix) {
        return ErydonIdNaming.matchesForm(ErydonIdMigration.legacyResourcePath(path), suffix);
    }

    private static void addAll(LinkedHashSet<String> terms, String... values) {
        terms.addAll(List.of(values));
    }
}
