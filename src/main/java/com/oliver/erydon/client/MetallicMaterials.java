package com.oliver.erydon.client;

import com.oliver.erydon.HighPolishSettings;
import java.util.List;
import java.util.Set;

/** Reload-only sprite selection. A matching name is still required to have authored metal texels. */
public final class MetallicMaterials {
    public static final int OVERLAY = 1;
    public static final int EMBEDDED = 2;
    public static final int STANDALONE = 3;
    /** Exact nonmetal finish marker for shared cover/coffered-ceiling inset sprites. */
    public static final int GLOSS_COVER = 4;
    public static final int AUTHORED = 0;
    public static final int BRONZE = 1;
    public static final int SILVER = 2;
    private static final Set<String> NAMESPACES = Set.of("erydon", "themelios", "daedalon");
    private static final Set<String> STONES = Set.copyOf(HighPolishSettings.MATERIALS);
    private static final Set<String> MOTIFS = Set.of("trim", "guilloche", "quatrefoil", "rose", "rosette");

    public record Candidate(int kind, int alloy, boolean pureMetalFallback) { }

    private MetallicMaterials() { }

    public static Candidate classify(String namespace, String path) {
        if (path.endsWith("_n") || path.endsWith("_s") || path.contains("window_arch_mirror")
                || path.contains("two_way") || path.contains("glazing_")) return null;
        if ("erydon".equals(namespace)
                && (path.equals("block/cover_white_gloss") || path.equals("block/cover_black_gloss"))) {
            return new Candidate(GLOSS_COVER, AUTHORED, false);
        }
        if (NAMESPACES.contains(namespace)) {
            boolean matte = path.equals("block/cover_bronze_matte") || path.equals("block/cover_silver_matte");
            int kind = path.contains("herringbone") || path.contains("weave")
                    || path.matches(".*_block_(bronze|silver)(trim|guilloche|quatrefoil|rose)$")
                    ? EMBEDDED : STANDALONE;
            return new Candidate(kind, alloy(path), matte);
        }
        if (!"minecraft".equals(namespace)) return null;
        for (String root : List.of("optifine/ctm/", "mcpatcher/ctm/")) {
            if (!path.startsWith(root)) continue;
            String relative = path.substring(root.length());
            String[] parts = relative.split("/");
            if (parts.length == 4 && parts[0].equals("overlay") && MOTIFS.contains(parts[1])
                    && (parts[2].equals("bronze") || parts[2].equals("silver"))) {
                return new Candidate(OVERLAY, alloy(relative), false);
            }
            if (parts.length >= 2 && STONES.contains(parts[0].split("_")[0])) {
                return new Candidate(EMBEDDED, alloy(relative), false);
            }
        }
        return null;
    }

    private static int alloy(String path) {
        boolean bronze = path.contains("bronze");
        boolean silver = path.contains("silver");
        return bronze == silver ? AUTHORED : bronze ? BRONZE : SILVER;
    }

    /** Original level-zero metal category, multiplied by albedo coverage rather than specular alpha. */
    public static byte[] coverage(int[] albedoAbgr, int[] specularAbgr, boolean pureMetalFallback) {
        if (specularAbgr != null && specularAbgr.length != albedoAbgr.length) {
            throw new IllegalArgumentException("Albedo and specular dimensions differ");
        }
        byte[] coverage = new byte[albedoAbgr.length];
        for (int i = 0; i < coverage.length; i++) {
            boolean metal = specularAbgr != null ? ((specularAbgr[i] >>> 8) & 255) >= 230 : pureMetalFallback;
            if (metal) coverage[i] = (byte) (albedoAbgr[i] >>> 24);
        }
        return coverage;
    }

    /** Native 16x patterns predate their metal PBR masks; their explicit grout counterpart supplies the shape. */
    public static String nativeBronzeCounterpart(String path) {
        if (path.contains("_herringbone_bronze")) return path.replace("_herringbone_bronze", "_herringbone_grout");
        if (path.contains("_weave_bronze")) return path.replace("_weave_bronze", "_weave_grout");
        return null;
    }

    public static boolean nativeBronzePlaceholder(int width, int height, int[] specularAbgr) {
        if (width != 16 || height != 16 || specularAbgr == null || specularAbgr.length != 256) return false;
        for (int value : specularAbgr) if (value != 0xff0000ff) return false;
        return true;
    }

    /** Exact authored pair differences, guarded against recoloured stone or independently replaced artwork. */
    public static byte[] nativeBronzeCoverage(int[] albedoAbgr, int[] groutAbgr) {
        if (albedoAbgr.length != 256 || groutAbgr.length != 256) return null;
        byte[] coverage = new byte[256];
        int changes = 0;
        for (int i = 0; i < coverage.length; i++) {
            if (albedoAbgr[i] == groutAbgr[i]) continue;
            // These are the two literal bronze paint values in every native herringbone/weave pair.
            if ((albedoAbgr[i] != 0xff1da0e5 && albedoAbgr[i] != 0xff22a2e5)
                    || (groutAbgr[i] >>> 24) != 255) return null;
            coverage[i] = (byte) 255;
            changes++;
        }
        return changes > 0 && changes < coverage.length ? coverage : null;
    }

    /** Perceptual roughness from the authored metal smoothness, with a distinct matte fallback. */
    public static int roughness(byte[] coverage, int[] specularAbgr, boolean pureMetalFallback) {
        if (specularAbgr == null) return pureMetalFallback ? 166 : 0;
        double sum = 0.0;
        long weight = 0;
        for (int i = 0; i < coverage.length; i++) {
            int alpha = coverage[i] & 255;
            sum += (255 - (specularAbgr[i] & 255)) * (double) alpha;
            weight += alpha;
        }
        return weight == 0 ? 0 : (int) Math.round(sum / weight);
    }

    /** Mean of the metal contribution only; coverage already contains alpha, so do not multiply it twice. */
    public static int meanMetalAlbedo(byte[] coverage, int[] albedoAbgr) {
        if (coverage.length != albedoAbgr.length) throw new IllegalArgumentException("Albedo and coverage dimensions differ");
        long red = 0, green = 0, blue = 0, weight = 0;
        for (int i = 0; i < coverage.length; i++) {
            int alpha = coverage[i] & 255;
            int color = albedoAbgr[i];
            red += (color & 255) * (long) alpha;
            green += ((color >>> 8) & 255) * (long) alpha;
            blue += ((color >>> 16) & 255) * (long) alpha;
            weight += alpha;
        }
        if (weight == 0) return 0xff000000;
        return 0xff000000 | (int) ((red + weight / 2) / weight)
                | ((int) ((green + weight / 2) / weight) << 8)
                | ((int) ((blue + weight / 2) / weight) << 16);
    }
}
