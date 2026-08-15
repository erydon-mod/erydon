package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.minecraft.util.Identifier;

public final class ErydonSlopeModelClassifier {
    private ErydonSlopeModelClassifier() {
    }

    public static Family familyForId(Identifier id) {
        if (id == null || !Erydon.MOD_ID.equals(id.getNamespace())) {
            return Family.NONE;
        }

        return familyForPath(id.getPath());
    }

    private static Family familyForPath(String path) {
        if (path == null || path.startsWith("block/slope/")) {
            return Family.NONE;
        }

        String blockPath = stripWrappedPrefix(path);
        if (isGlazingPath(blockPath)) {
            return Family.NONE;
        }

        if (blockPath.contains("_slope_vertical_shallow_broad")) {
            return Family.VERTICAL_SHALLOW_BROAD;
        }
        if (blockPath.contains("_slope_vertical_shallow_narrow")) {
            return Family.VERTICAL_SHALLOW_NARROW;
        }
        if (blockPath.contains("_slope_vertical")
                && !blockPath.contains("_slope_vertical_shallow_")) {
            return Family.VERTICAL;
        }
        if (blockPath.contains("_slope_shallow_lower")) {
            return Family.SHALLOW_LOWER;
        }
        if (blockPath.contains("_slope_shallow_upper")) {
            return Family.SHALLOW_UPPER;
        }
        if (blockPath.contains("_slope_steep_lower")) {
            return Family.STEEP_LOWER;
        }
        if (blockPath.contains("_slope_steep_upper")) {
            return Family.STEEP_UPPER;
        }
        if (blockPath.endsWith("_slope") || blockPath.endsWith("_slope_aged")) {
            return Family.STANDARD;
        }

        return Family.NONE;
    }

    public static boolean isHandledSlopeId(Identifier id) {
        return familyForId(id) != Family.NONE;
    }

    public static boolean isOldSteppedSlopeModelPath(String path) {
        return path != null && path.startsWith("block/slope/");
    }

    private static String stripWrappedPrefix(String path) {
        String prefix = "block/internal/wrapped/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    public static boolean isGlazingPath(String path) {
        return path != null && path.startsWith("glazing_");
    }

    public enum Family {
        NONE,
        STANDARD,
        SHALLOW_LOWER,
        SHALLOW_UPPER,
        STEEP_LOWER,
        STEEP_UPPER,
        VERTICAL,
        VERTICAL_SHALLOW_BROAD,
        VERTICAL_SHALLOW_NARROW
    }
}
