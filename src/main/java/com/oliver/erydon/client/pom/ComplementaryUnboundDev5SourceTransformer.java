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
    public static final int SPIRAL_MATERIAL_ID = 32120;

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

    public record ProgramResult(
            String vertexText,
            String fragmentText,
            boolean changed,
            boolean eligible,
            String status,
            Map<String, Integer> counts
    ) {
    }

    private static final Pattern VERTEX_INSERT_MARKER = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)void\\s+main\\s*\\(\\s*\\)\\s*\\{[ \\t]*$");
    private static final Pattern VERTEX_VTEX_DECLARATION = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)out\\s+vec4\\s+vTexCoordAM\\s*;[ \\t]*$");
    private static final Pattern FRAGMENT_VTEX_DECLARATION = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)in\\s+vec4\\s+vTexCoordAM\\s*;[ \\t]*$");
    private static final Pattern VERTEX_MATERIAL = Pattern.compile(
            "(?m)^[ \\t]*mat\\s*=\\s*int\\s*\\(\\s*mc_Entity\\.x\\s*\\+\\s*0\\.5\\s*\\)\\s*;[ \\t]*$");
    private static final Pattern VERTEX_BOUNDS = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)vTexCoordAM\\.zw\\s*=\\s*abs\\s*\\(\\s*texMinMidCoord\\s*\\)"
                    + "\\s*\\*\\s*2\\s*;[ \\t]*\\R"
                    + "\\k<indent>vTexCoordAM\\.xy\\s*=\\s*min\\s*\\(\\s*texCoord\\s*,"
                    + "\\s*midCoord\\s*-\\s*texMinMidCoord\\s*\\)\\s*;[ \\t]*$");

    private static final Pattern FRAGMENT_INSERT_MARKER = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)vec4\\s+ReadNormal\\s*\\(\\s*vec2\\s+coord\\s*\\)\\s*\\{[ \\t]*$");
    private static final Pattern POM_LOCAL_COORD = Pattern.compile(
            "(?m)^[ \\t]*vec2\\s+vTexCoord\\s*=\\s*signMidCoordPos\\s*\\*\\s*0\\.5"
                    + "\\s*\\+\\s*0\\.5\\s*;[ \\t]*$");
    private static final Pattern SKIP_POM = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)bool\\s+skipPom\\s*=\\s*false\\s*;[ \\t]*$");
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

    public static ProgramResult transformProgram(
            String programName,
            String vertexSource,
            String fragmentSource,
            String vertexHelperSource,
            String fragmentHelperSource,
            boolean eligible
    ) {
        if (vertexSource == null || fragmentSource == null) {
            return unchanged(vertexSource, fragmentSource, eligible, "NO_SOURCE", Map.of());
        }
        if (!eligible) {
            return unchanged(vertexSource, fragmentSource, false, "NOT_ELIGIBLE", Map.of());
        }
        if (!"gbuffers_terrain".equals(programName)) {
            return unchanged(vertexSource, fragmentSource, true, "OTHER_PROGRAM", Map.of());
        }

        boolean vertexTransformed = vertexSource.contains(HELPER_SENTINEL);
        boolean fragmentTransformed = fragmentSource.contains(HELPER_SENTINEL);
        if (vertexTransformed && fragmentTransformed) {
            return unchanged(vertexSource, fragmentSource, true, "ALREADY_TRANSFORMED", Map.of());
        }
        if (vertexTransformed || fragmentTransformed
                || vertexHelperSource == null || vertexHelperSource.isBlank()
                || fragmentHelperSource == null || fragmentHelperSource.isBlank()) {
            return unchanged(vertexSource, fragmentSource, true,
                    "INCOMPLETE_TRANSFORM_NO_CHANGE", Map.of());
        }

        int vertexPomCount = count(VERTEX_VTEX_DECLARATION, vertexSource);
        int fragmentPomCount = count(FRAGMENT_INSERT_MARKER, fragmentSource);
        if (vertexPomCount == 0 && fragmentPomCount == 0) {
            return unchanged(vertexSource, fragmentSource, true, "POM_NOT_COMPILED", Map.of());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("vertex_insert_marker", count(VERTEX_INSERT_MARKER, vertexSource));
        counts.put("vertex_vtex_declaration", vertexPomCount);
        counts.put("vertex_material", count(VERTEX_MATERIAL, vertexSource));
        counts.put("vertex_bounds", count(VERTEX_BOUNDS, vertexSource));
        counts.put("fragment_insert_marker", fragmentPomCount);
        counts.put("fragment_vtex_declaration", count(FRAGMENT_VTEX_DECLARATION, fragmentSource));
        counts.put("fragment_local_coord", count(POM_LOCAL_COORD, fragmentSource));
        counts.put("fragment_skip_pom", count(SKIP_POM, fragmentSource));
        counts.put("fragment_read_normal", count(READ_NORMAL, fragmentSource));
        counts.put("fragment_final_coord", count(FINAL_COORD, fragmentSource));
        counts.put("fragment_shadow_coord", count(SHADOW_COORD, fragmentSource));
        counts.put("fragment_slope_coord", count(SLOPE_COORD, fragmentSource));
        if (counts.values().stream().anyMatch(value -> value != 1)) {
            return unchanged(vertexSource, fragmentSource, true,
                    "ANCHOR_MISMATCH_NO_CHANGE", Map.copyOf(counts));
        }

        String vertexHelper = vertexHelperSource.stripTrailing();
        String fragmentHelper = fragmentHelperSource.stripTrailing();
        String vertexCandidate = replaceFirst(VERTEX_VTEX_DECLARATION, vertexSource,
                match -> match.group() + "\n" + match.group("indent") + "flat out int erydonPomRecord;");
        vertexCandidate = replaceFirst(VERTEX_INSERT_MARKER, vertexCandidate,
                match -> vertexHelper + "\n\n" + match.group());
        vertexCandidate = replaceFirst(VERTEX_BOUNDS, vertexCandidate,
                match -> match.group() + "\n"
                        + match.group("indent")
                        + "erydonPomRecord = -1;\n"
                        + match.group("indent") + "if (mat == " + SPIRAL_MATERIAL_ID + ") {\n"
                        + match.group("indent")
                        + "    erydonPomRecord = erydonCtmPomFindRecord(midCoord, vec2(atlasSize));\n"
                        + match.group("indent") + "    if (erydonPomRecord >= 0) {\n"
                        + match.group("indent")
                        + "        erydonCtmPomApplyExactBounds(texCoord, vec2(atlasSize), erydonPomRecord, "
                        + "vTexCoordAM, signMidCoordPos, absMidCoordPos, midCoord);\n"
                        + match.group("indent") + "    }\n"
                        + match.group("indent") + "}");

        String fragmentCandidate = replaceFirst(FRAGMENT_VTEX_DECLARATION, fragmentSource,
                match -> match.group() + "\n" + match.group("indent") + "flat in int erydonPomRecord;");
        fragmentCandidate = replaceFirst(FRAGMENT_INSERT_MARKER, fragmentCandidate,
                match -> fragmentHelper + "\n\n" + match.group());
        fragmentCandidate = replaceFirst(SKIP_POM, fragmentCandidate,
                match -> match.group("indent") + "bool skipPom = mat == " + SPIRAL_MATERIAL_ID
                        + " && erydonPomRecord < 0;");
        fragmentCandidate = replaceFirst(READ_NORMAL, fragmentCandidate,
                match -> match.group("indent") + "coord = erydonCtmPomAtlasUv(coord);");
        fragmentCandidate = replaceFirst(FINAL_COORD, fragmentCandidate,
                match -> match.group("indent") + "localCoord = vTexCoord.st + pI * interval;\n"
                        + match.group("indent") + "newCoord = erydonCtmPomAtlasUv(localCoord);\n"
                        + match.group("indent") + "return localCoord;");
        fragmentCandidate = replaceFirst(SHADOW_COORD, fragmentCandidate,
                match -> match.group("indent")
                        + "vec2 parallaxCoord = erydonCtmPomAtlasUv(coord + parallaxdir.xy * stepLC);");
        fragmentCandidate = replaceFirst(SLOPE_COORD, fragmentCandidate,
                match -> match.group("indent") + "vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);");

        Map<String, Integer> post = new LinkedHashMap<>();
        post.put("post_vertex_helper", occurrences(vertexCandidate, HELPER_SENTINEL));
        post.put("post_fragment_helper", occurrences(fragmentCandidate, HELPER_SENTINEL));
        post.put("post_vertex_record_declaration", occurrences(
                vertexCandidate, "flat out int erydonPomRecord;"));
        post.put("post_fragment_record_declaration", occurrences(
                fragmentCandidate, "flat in int erydonPomRecord;"));
        post.put("post_vertex_bounds", occurrences(vertexCandidate,
                "erydonCtmPomApplyExactBounds(texCoord, vec2(atlasSize), erydonPomRecord,"));
        post.put("post_vertex_material_gate", occurrences(vertexCandidate,
                "if (mat == " + SPIRAL_MATERIAL_ID + ")"));
        post.put("post_vertex_default_record", occurrences(vertexCandidate,
                "erydonPomRecord = -1;"));
        post.put("post_skip_pom", occurrences(fragmentCandidate,
                "bool skipPom = mat == " + SPIRAL_MATERIAL_ID + " && erydonPomRecord < 0;"));
        post.put("post_read_normal", occurrences(fragmentCandidate,
                "coord = erydonCtmPomAtlasUv(coord);"));
        post.put("post_final_coord", occurrences(fragmentCandidate,
                "newCoord = erydonCtmPomAtlasUv(localCoord);"));
        post.put("post_shadow_coord", occurrences(fragmentCandidate,
                "vec2 parallaxCoord = erydonCtmPomAtlasUv("));
        post.put("post_slope_coord", occurrences(fragmentCandidate,
                "vec2 atlasCoord = erydonCtmPomAtlasUv(texCoord);"));
        if (post.values().stream().anyMatch(value -> value != 1)) {
            counts.putAll(post);
            return unchanged(vertexSource, fragmentSource, true,
                    "POSTCONDITION_FAILED_NO_CHANGE", Map.copyOf(counts));
        }
        counts.putAll(post);
        return new ProgramResult(vertexCandidate, fragmentCandidate, true, true,
                "TRANSFORMED", Map.copyOf(counts));
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

    private static ProgramResult unchanged(
            String vertexSource,
            String fragmentSource,
            boolean eligible,
            String status,
            Map<String, Integer> counts
    ) {
        return new ProgramResult(vertexSource, fragmentSource, false, eligible, status, counts);
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
