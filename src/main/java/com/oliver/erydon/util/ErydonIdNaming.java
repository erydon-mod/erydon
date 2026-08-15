package com.oliver.erydon.util;

/**
 * Shared parsing for published suffix-aged IDs and canonical material-aged IDs.
 */
public final class ErydonIdNaming {
    private static final String AGED_TOKEN = "aged";
    private static final String AGED_PREFIX = "aged_";
    private static final String AGED_INFIX = "_aged_";
    private static final String AGED_SUFFIX = "_aged";

    private ErydonIdNaming() {
    }

    public static boolean isAged(String path) {
        return path != null
                && (path.equals(AGED_TOKEN)
                || path.startsWith(AGED_PREFIX)
                || path.contains(AGED_INFIX)
                || path.endsWith(AGED_SUFFIX));
    }

    /**
     * Returns the equivalent non-aged path while preserving every other token.
     */
    public static String withoutAged(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.equals(AGED_TOKEN)) {
            return "";
        }
        if (path.startsWith(AGED_PREFIX)) {
            return path.substring(AGED_PREFIX.length());
        }

        int infix = path.indexOf(AGED_INFIX);
        if (infix >= 0) {
            return path.substring(0, infix) + "_" + path.substring(infix + AGED_INFIX.length());
        }
        if (path.endsWith(AGED_SUFFIX)) {
            return path.substring(0, path.length() - AGED_SUFFIX.length());
        }
        return path;
    }

    public static boolean matchesForm(String path, String suffix) {
        String basePath = withoutAged(path);
        return basePath != null && basePath.endsWith(suffix);
    }
}
