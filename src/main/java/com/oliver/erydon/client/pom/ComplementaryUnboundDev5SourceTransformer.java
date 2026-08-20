package com.oliver.erydon.client.pom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact, fail-closed in-memory adapter for the installed CU r5.9 dev5 source shape. */
public final class ComplementaryUnboundDev5SourceTransformer {
    public static final String EXPECTED_PROPERTIES_SHA256 =
            "a4c4e2156ad5aeb66c0ad495a2721ea092952f2cdebfaaf89a20dfa3409ef2e8";
    public static final String TEXTURE_DIRECTIVE =
            "texture.erydonCtmPomLookup=erydon:ctm_pom_lookup";
    public static final String HELPER_SENTINEL = "uniform sampler2D erydonCtmPomLookup;";

    public enum Mode {
        OFF,
        AUTO,
        FORCE
    }

    public record Result(
            String text,
            boolean changed,
            boolean eligible,
            String status,
            Map<String, Integer> counts
    ) {
    }

    private static final Pattern INSERT_MARKER = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)vec4\\s+ReadNormal\\s*\\(\\s*vec2\\s+coord\\s*\\)\\s*\\{[ \\t]*$");
    private static final Pattern READ_NORMAL = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)coord\\s*=\\s*fract\\s*\\(\\s*coord\\s*\\)"
                    + "\\s*\\*\\s*vTexCoordAM\\.pq\\s*\\+\\s*vTexCoordAM\\.st\\s*;[ \\t]*$");
    private static final Pattern FINAL_COORD = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)localCoord\\s*=\\s*fract\\s*\\(\\s*vTexCoord\\.st"
                    + "\\s*\\+\\s*pI\\s*\\*\\s*interval\\s*\\)\\s*;[ \\t]*\\R"
                    + "\\k<indent>newCoord\\s*=\\s*localCoord\\s*\\*\\s*vTexCoordAM\\.pq"
                    + "\\s*\\+\\s*vTexCoordAM\\.st\\s*;[ \\t]*\\R"
                    + "\\k<indent>return\\s+localCoord\\s*;[ \\t]*$");
    private static final Pattern SHADOW_COORD = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)vec2\\s+parallaxCoord\\s*=\\s*fract\\s*\\(\\s*coord"
                    + "\\s*\\+\\s*parallaxdir\\.xy\\s*\\*\\s*stepLC\\s*\\)"
                    + "\\s*\\*\\s*vTexCoordAM\\.pq\\s*\\+\\s*vTexCoordAM\\.st\\s*;[ \\t]*$");
    private static final Pattern SLOPE_COORD = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)vec2\\s+atlasCoord\\s*=\\s*fract\\s*\\(\\s*texCoord\\s*\\)"
                    + "\\s*\\*\\s*vTexCoordAM\\.pq\\s*\\+\\s*vTexCoordAM\\.st\\s*;[ \\t]*$");

    public static Result adaptProperties(String contents, Mode mode) {
        return adaptProperties(contents, mode, EXPECTED_PROPERTIES_SHA256);
    }

    static Result adaptProperties(String contents, Mode mode, String expectedHash) {
        boolean exact = sha256(contents).equals(expectedHash);
        boolean eligible = mode == Mode.FORCE || (mode == Mode.AUTO && exact);
        if (mode == Mode.OFF) {
            return new Result(contents, false, false, "OFF", Map.of());
        }
        if (!eligible) {
            return new Result(contents, false, false, "UNSUPPORTED_SHADER_PROPERTIES",
                    Map.of("properties_sha_match", exact ? 1 : 0));
        }
        if (contents.contains(TEXTURE_DIRECTIVE)) {
            return new Result(contents, false, true, "DIRECTIVE_ALREADY_PRESENT",
                    Map.of("properties_sha_match", exact ? 1 : 0));
        }
        String suffix = contents.endsWith("\n") ? "" : "\n";
        String adapted = contents + suffix + "\n# ERYDON CTM-POM bridge (in-memory only)\n"
                + TEXTURE_DIRECTIVE + "\n";
        return new Result(adapted, true, true, "ELIGIBLE",
                Map.of("properties_sha_match", exact ? 1 : 0, "directives_added", 1));
    }

    public static Result transformFragment(
            String programName,
            String source,
            String helperSource,
            boolean eligible
    ) {
        if (source == null) {
            return new Result("", false, eligible, "NO_SOURCE", Map.of());
        }
        if (!eligible) {
            return new Result(source, false, false, "NOT_ELIGIBLE", Map.of());
        }
        if (!"gbuffers_terrain".equals(programName)) {
            return new Result(source, false, true, "OTHER_PROGRAM", Map.of());
        }
        if (source.contains(HELPER_SENTINEL)) {
            return new Result(source, false, true, "ALREADY_TRANSFORMED", Map.of());
        }
        if (count(INSERT_MARKER, source) == 0) {
            return new Result(source, false, true, "POM_NOT_COMPILED", Map.of());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("insert_marker", count(INSERT_MARKER, source));
        counts.put("read_normal", count(READ_NORMAL, source));
        counts.put("final_coord", count(FINAL_COORD, source));
        counts.put("shadow_coord", count(SHADOW_COORD, source));
        counts.put("slope_coord", count(SLOPE_COORD, source));
        if (counts.values().stream().anyMatch(value -> value != 1)) {
            return new Result(source, false, true, "ANCHOR_MISMATCH_NO_CHANGE", Map.copyOf(counts));
        }

        String candidate = replaceFirst(INSERT_MARKER, source,
                match -> helperSource.stripTrailing() + "\n\n" + match.group());
        candidate = replaceFirst(READ_NORMAL, candidate,
                match -> match.group("indent") + "coord = erydonCtmPomAtlasUv(coord);");
        candidate = replaceFirst(FINAL_COORD, candidate,
                match -> match.group("indent") + "localCoord = vTexCoord.st + pI * interval;\n"
                        + match.group("indent") + "newCoord = erydonCtmPomAtlasUv(localCoord);\n"
                        + match.group("indent") + "return localCoord;");
        candidate = replaceFirst(SHADOW_COORD, candidate,
                match -> match.group("indent")
                        + "vec2 parallaxCoord = erydonCtmPomAtlasUv(coord + parallaxdir.xy * stepLC);");
        candidate = replaceFirst(SLOPE_COORD, candidate,
                match -> match.group("indent") + "vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);");

        Map<String, Integer> post = Map.of(
                "post_helper", occurrences(candidate, HELPER_SENTINEL),
                "post_read_normal", occurrences(candidate, "coord = erydonCtmPomAtlasUv(coord);"),
                "post_final_coord", occurrences(candidate, "newCoord = erydonCtmPomAtlasUv(localCoord);"),
                "post_shadow_coord", occurrences(candidate, "vec2 parallaxCoord = erydonCtmPomAtlasUv("),
                "post_slope_coord", occurrences(candidate, "vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);")
        );
        if (post.values().stream().anyMatch(value -> value != 1)) {
            counts.putAll(post);
            return new Result(source, false, true, "POSTCONDITION_FAILED_NO_CHANGE", Map.copyOf(counts));
        }
        counts.putAll(post);
        return new Result(candidate, true, true, "TRANSFORMED", Map.copyOf(counts));
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                output.append(String.format("%02x", value & 0xFF));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String replaceFirst(Pattern pattern, String source, Replacer replacer) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        return source.substring(0, matcher.start()) + replacer.replace(matcher) + source.substring(matcher.end());
    }

    private static int count(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int start = 0;
        while ((start = source.indexOf(value, start)) >= 0) {
            count++;
            start += value.length();
        }
        return count;
    }

    private interface Replacer {
        String replace(Matcher matcher);
    }

    private ComplementaryUnboundDev5SourceTransformer() {
    }
}
