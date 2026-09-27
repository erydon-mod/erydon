package com.oliver.erydon.client.pom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Material boundaries for CU's existing reflection passes; adds no reflection rays. */
public final class MetalReflectionFilter {
    private static final String FORMAT_MARKER = "// ERYDON metal buffer formats";
    private static final String HISTORY_MARKER = "// ERYDON metal reflection history";
    private static final String SPATIAL_MARKER = "// ERYDON metal reflection filter";
    private static final Pattern HISTORY_FORMAT = Pattern.compile("const\\s+int\\s+colortex1Format\\s*=\\s*RGB8_SNORM\\s*;");
    private static final Pattern MATERIAL_FORMAT = Pattern.compile("const\\s+int\\s+colortex6Format\\s*=\\s*RGB8\\s*;");
    private static final Pattern OUTPUT_INIT = Pattern.compile("vec4\\s+reflectOutput\\s*=\\s*vec4\\(0\\.0\\)\\s*;");
    private static final Pattern MATERIAL = Pattern.compile("int\\s+materialMaskInt\\s*=\\s*int\\(texture6\\.g\\s*\\*\\s*255\\.1\\)\\s*;");
    private static final Pattern PREVIOUS_NORMAL = Pattern.compile("vec3\\s+prevNormalM\\s*=\\s*mat3\\(gbufferModelView\\)\\s*\\*\\s*texture2D\\(colortex1,\\s*virtualPrevRefPos\\.xy\\)\\.rgb\\s*;");
    private static final Pattern HISTORY_BLEND = Pattern.compile("reflectOutput\\.rgb\\s*=\\s*mix\\(prevRef\\.rgb,\\s*reflectOutput\\.rgb,\\s*min1\\(minBlendFactor\\s*/\\s*prevValid\\)\\)\\s*;");
    private static final Pattern HISTORY_OUTPUT = Pattern.compile("gl_FragData\\[1\\]\\s*=\\s*vec4\\(texture4\\.rgb,\\s*1\\.0\\)\\s*;");
    private static final Pattern FILTER_START = Pattern.compile("vec4\\s+sampleBlurFilteredReflection\\([^)]*\\)\\s*\\{");
    private static final Pattern FILTER_RETURN = Pattern.compile("return\\s+sum\\s*/\\s*weightSum\\s*;");
    private static final Pattern CENTER_MATERIAL = Pattern.compile("vec[34]\\s+texture6\\s*=\\s*texelFetch\\(colortex6,\\s*texelCoord,\\s*0\\)\\s*(?:\\.rgb)?\\s*;");
    private static final Pattern SAMPLE_NORMAL = Pattern.compile("vec4\\s+texture1Sample\\s*=\\s*texture2D\\(colortex1,\\s*sampleCoord\\)\\s*;");

    public record Result(String text, boolean changed, String status) { }

    /** Sources have already passed Iris's preprocessor. Do not insert directives here. */
    public static Result adapt(String program, String source) {
        if (source == null) return new Result(null, false, "NOT_APPLICABLE");
        return switch (program) {
            case "shadow", "composite6", "final" -> adaptFormats(source);
            case "composite" -> adaptHistory(source);
            case "composite1" -> adaptSpatial(source);
            default -> new Result(source, false, "OTHER_PROGRAM");
        };
    }

    /** Both buffers already exist. Iris reads these format directives even inside pack comments. */
    public static Result adaptFormats(String source) {
        if (source == null) return new Result(null, false, "NOT_APPLICABLE");
        if (source.contains(FORMAT_MARKER)) return unchanged(source, "ALREADY_TRANSFORMED");
        if (!unique(HISTORY_FORMAT, source) || !unique(MATERIAL_FORMAT, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = replace(HISTORY_FORMAT, source, FORMAT_MARKER + "\nconst int colortex1Format = RGBA8_SNORM;");
        result = replace(MATERIAL_FORMAT, result, "const int colortex6Format = RGBA8;");
        return transformed(result);
    }

    private static Result adaptHistory(String source) {
        if (source.contains(HISTORY_MARKER)) return unchanged(source, "ALREADY_TRANSFORMED");
        if (!unique(OUTPUT_INIT, source) || !unique(MATERIAL, source)
                || !unique(PREVIOUS_NORMAL, source) || !unique(HISTORY_BLEND, source)
                || !unique(HISTORY_OUTPUT, source)) return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = after(OUTPUT_INIT, source, "\n" + HISTORY_MARKER + "\nfloat erydonReflectionMaterial = 0.0;\n");
        result = after(MATERIAL, result, "\nerydonReflectionMaterial = materialMaskInt == 243 ? 0.5 : (materialMaskInt == 244 ? 1.0 : 0.0);\n");
        result = replace(PREVIOUS_NORMAL, result, """
                vec4 erydonPreviousNormal = texture2D(colortex1, virtualPrevRefPos.xy);
                vec3 prevNormalM = mat3(gbufferModelView) * erydonPreviousNormal.rgb;
                bool erydonPreviousMaterialMatches = true;
                if (erydonReflectionMaterial > 0.01 || erydonPreviousNormal.a > 0.01) {
                    // A filtered scalar tag can accidentally resemble a third material.
                    // Check the nearest tag as well, and reject interpolated boundaries.
                    ivec2 erydonHistorySize = textureSize(colortex1, 0);
                    ivec2 erydonHistoryPixel = clamp(ivec2(virtualPrevRefPos.xy * vec2(erydonHistorySize)),
                                                     ivec2(0), erydonHistorySize - ivec2(1));
                    float erydonPreviousMaterial = texelFetch(colortex1, erydonHistoryPixel, 0).a;
                    erydonPreviousMaterialMatches = abs(erydonPreviousMaterial - erydonReflectionMaterial) < 0.02
                            && abs(erydonPreviousNormal.a - erydonPreviousMaterial) < 0.02;
                }
                """);
        result = replace(HISTORY_BLEND, result, """
                if (erydonPreviousMaterialMatches) {
                    reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / max(prevValid, 0.000001)));
                }
                """);
        result = replace(HISTORY_OUTPUT, result, "gl_FragData[1] = vec4(texture4.rgb, erydonReflectionMaterial);");
        return transformed(result);
    }

    private static Result adaptSpatial(String source) {
        if (source.contains(SPATIAL_MARKER)) return unchanged(source, "ALREADY_TRANSFORMED");
        if (!unique(FILTER_START, source) || !unique(FILTER_RETURN, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        Matcher start = FILTER_START.matcher(source), end = FILTER_RETURN.matcher(source);
        start.find();
        end.find();
        if (end.start() <= start.end()) return unchanged(source, "UNSUPPORTED_SOURCE");
        String body = source.substring(start.end(), end.start());
        boolean lowSampler = !CENTER_MATERIAL.matcher(body).find();
        if (!unique(SAMPLE_NORMAL, body) || !lowSampler && !unique(CENTER_MATERIAL, body)
                || lowSampler && body.contains("texture6"))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        if (!lowSampler) {
            body = after(CENTER_MATERIAL, body, """

                        int erydonFilterMask = int(texture6.g * 255.1);
                        float erydonFilterMaterial = erydonFilterMask == 243 ? 0.5 : (erydonFilterMask == 244 ? 1.0 : 0.0);
                    """);
            body = after(SAMPLE_NORMAL, body, """

                        if (erydonFilterMaterial > 0.01 || texture1Sample.a > 0.01) {
                            ivec2 erydonSampleSize = textureSize(colortex1, 0);
                            ivec2 erydonSamplePixel = clamp(ivec2(sampleCoord * vec2(erydonSampleSize)),
                                                            ivec2(0), erydonSampleSize - ivec2(1));
                            float erydonSampleMaterial = texelFetch(colortex1, erydonSamplePixel, 0).a;
                            if (abs(erydonSampleMaterial - erydonFilterMaterial) >= 0.02
                                    || abs(texture1Sample.a - erydonSampleMaterial) >= 0.02) continue;
                        }
                    """);
        }
        // A thin isolated inlay may reject every blurred sample. Keep its own trace.
        String result = source.substring(0, start.end()) + "\n" + SPATIAL_MARKER + body
                + "return weightSum > 0.000001 ? sum / weightSum : centerCol;" + source.substring(end.end());
        return new Result(result, true, lowSampler ? "TRANSFORMED_LOW_SAMPLER" : "TRANSFORMED");
    }

    private static boolean unique(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() && !matcher.find();
    }

    private static String replace(Pattern pattern, String source, String replacement) {
        return pattern.matcher(source).replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String after(Pattern pattern, String source, String insertion) {
        return pattern.matcher(source).replaceFirst("$0" + Matcher.quoteReplacement(insertion));
    }

    private static Result transformed(String source) { return new Result(source, true, "TRANSFORMED"); }
    private static Result unchanged(String source, String status) { return new Result(source, false, status); }

    private MetalReflectionFilter() { }
}
