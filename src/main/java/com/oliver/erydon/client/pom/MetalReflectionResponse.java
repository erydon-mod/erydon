package com.oliver.erydon.client.pom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coverage-weighted conductor reflections, separate from the selected stone finish. */
public final class MetalReflectionResponse {
    private static final String MARKER = "// ERYDON conductor reflection response";
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern MATERIAL_FETCH = Pattern.compile(
            "vec([34])\\s+texture6\\s*=\\s*texelFetch\\(colortex6,\\s*texelCoord,\\s*0\\)\\s*(?:\\.(?:rgb|rgba))?\\s*;");
    private static final Pattern FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*fresnelM\\s*\\*\\s*sqrt1\\(smoothnessD\\)\\s*-\\s*dither\\s*\\*\\s*0\\.01\\s*;");
    private static final Pattern TINT = Pattern.compile("reflection\\.rgb\\s*\\*=\\s*reflectColor\\s*;");
    private static final Pattern FILTER_SIGNATURE = Pattern.compile(
            "vec4\\s+sampleBlurFilteredReflection\\(vec4\\s+centerCol,\\s*vec3\\s+nViewPos,\\s*float\\s+dither,\\s*float\\s+z0\\)\\s*\\{");
    private static final Pattern FILTER_RETURN = Pattern.compile("return\\s+sum\\s*/\\s*weightSum\\s*;");
    private static final Pattern FILTER_CALL = Pattern.compile(
            "compositeReflection\\s*=\\s*sampleBlurFilteredReflection\\(compositeReflection,\\s*nViewPos,\\s*dither,\\s*z0\\)\\s*;");
    private static final Pattern PRESERVATION = Pattern.compile("const\\s+float\\s+texturePreservation\\s*=\\s*0\\.7\\s*;");
    private static final Pattern WATER_FRESNEL = Pattern.compile("fresnelM\\s*=\\s*\\(fresnelM\\s*\\*\\s*0\\.85\\s*\\+\\s*0\\.15\\)\\s*\\*\\s*reflectMult\\s*;");
    private static final Pattern WATER_BLEND = Pattern.compile("color\\.rgb\\s*=\\s*mix\\(color\\.rgb,\\s*reflection\\.rgb,\\s*fresnelM\\)\\s*;");
    private static final Pattern WATER_NO_REFLECTION = Pattern.compile("vec4\\s+reflection\\s*=\\s*vec4\\(0\\.0\\)\\s*;");

    public record Result(String text, boolean changed, String status) { }

    public static Result adapt(String program, String source) {
        if (source == null) return new Result(null, false, "NOT_APPLICABLE");
        if (!"deferred1".equals(program) && !"composite".equals(program)
                && !"composite1".equals(program) && !"gbuffers_water".equals(program))
            return new Result(source, false, "OTHER_PROGRAM");
        if (source.contains(MARKER)) return unchanged(source, "ALREADY_TRANSFORMED");
        return switch (program) {
            case "deferred1" -> deferred(source);
            case "composite" -> composite(source);
            case "composite1" -> blend(source);
            default -> water(source);
        };
    }

    private static Result deferred(String source) {
        if (!unique(FRESNEL, source)) return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = mainMaterialRgba(source);
        if (result == null) return unchanged(source, "UNSUPPORTED_SOURCE");
        String insertion = """

                    // ERYDON conductor reflection response
                    if (materialMaskInt == 243 || materialMaskInt == 244) {
                        float erydonMaterialByte = floor(clamp(texture6.a, 0.0, 1.0) * 255.0 + 0.5);
                        float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                        float erydonBaseFinish = mod(erydonMaterialByte, 4.0);
                        float erydonBaseSmoothness = erydonBaseFinish < 0.5 ? smoothnessD
                                : (erydonBaseFinish < 1.5 ? (175.0 / 255.0) * (175.0 / 255.0) : 1.0);
                        float erydonBaseReflection = (fresnelM + dither * 0.01)
                                * sqrt(clamp(erydonBaseSmoothness, 0.0, 1.0) / max(smoothnessD, 0.000001)) - dither * 0.01;
                        if (erydonBaseFinish > 2.5) {
                            erydonBaseReflection = (pow3(fresnel) * 0.5 + 0.5) * erydonBaseSmoothness;
                        }
                        float erydonMetalF0Max = materialMaskInt == 243 ? 0.72 : 0.95;
                        float erydonGrazing = clamp(fresnel, 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        float erydonMetalReflection = (erydonMetalF0Max + (1.0 - erydonMetalF0Max) * erydonGrazingFifth)
                                * (0.75 + 0.25 * clamp(smoothnessD, 0.0, 1.0));
                        fresnelM = clamp(mix(clamp(erydonBaseReflection, 0.0, 1.0), erydonMetalReflection,
                                             erydonCoverage), 0.0, 0.9999);
                    }
                """;
        return transformed(after(FRESNEL, result, insertion));
    }

    private static Result composite(String source) {
        if (!unique(TINT, source)) return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = mainMaterialRgba(source);
        if (result == null) return unchanged(source, "UNSUPPORTED_SOURCE");
        String insertion = """
                    // ERYDON conductor reflection response
                    if (materialMaskInt == 243 || materialMaskInt == 244) {
                        float erydonMaterialByte = floor(clamp(texture6.a, 0.0, 1.0) * 255.0 + 0.5);
                        float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                        vec3 erydonF0 = materialMaskInt == 243 ? vec3(0.72, 0.42, 0.16) : vec3(0.95, 0.93, 0.88);
                        float erydonGrazing = clamp(fresnel, 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        vec3 erydonFresnel = erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth;
                        float erydonMetalReflection = max(erydonFresnel.r, max(erydonFresnel.g, erydonFresnel.b));
                        float erydonMetalShare = clamp(erydonCoverage * erydonMetalReflection
                                * (0.75 + 0.25 * clamp(smoothnessD, 0.0, 1.0))
                                / max(fresnelM * fresnelM, 0.000001), 0.0, 1.0);
                        // Weight tint by reflected energy, not coverage a second time.
                        // Stored strength includes fog; the bounded ratio remains safe
                        // when the surface reflection fades out behind fog/clouds.
                        reflectColor = mix(vec3(1.0), erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare);
                    }
                """;
        result = TINT.matcher(result).replaceFirst(Matcher.quoteReplacement(insertion) + "$0");
        return transformed(result);
    }

    private static Result blend(String source) {
        if (!unique(FILTER_SIGNATURE, source) || !unique(FILTER_RETURN, source)
                || !unique(FILTER_CALL, source) || !unique(PRESERVATION, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        Matcher start = FILTER_SIGNATURE.matcher(source), end = FILTER_RETURN.matcher(source);
        start.find();
        end.find();
        if (end.start() <= start.end()) return unchanged(source, "UNSUPPORTED_SOURCE");
        String body = source.substring(start.end(), end.start());
        boolean lowSampler = !MATERIAL_FETCH.matcher(body).find();
        if (!lowSampler && !unique(MATERIAL_FETCH, body) || lowSampler && body.contains("texture6"))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        if (!lowSampler) {
            body = replace(MATERIAL_FETCH, body, "vec4 texture6 = texelFetch(colortex6, texelCoord, 0);");
            body = after(MATERIAL_FETCH, body, """

                        int erydonBlendMask = int(texture6.g * 255.1);
                        if (erydonBlendMask == 243 || erydonBlendMask == 244) {
                            float erydonMaterialByte = floor(clamp(texture6.a, 0.0, 1.0) * 255.0 + 0.5);
                            float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                            float erydonMetalPreservation = 0.35 + 0.4 * (1.0 - sqrt(clamp(texture6.r, 0.0, 1.0)));
                            erydonMetalTexturePreservation = mix(0.7, erydonMetalPreservation, erydonCoverage);
                        }
                    """);
        }
        String signature = """
                vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0,
                                                  out float erydonMetalTexturePreservation) {
                    // ERYDON conductor reflection response
                    erydonMetalTexturePreservation = 0.7;
                """;
        String result = source.substring(0, start.start()) + signature + body + source.substring(end.start());
        result = replace(FILTER_CALL, result, """
                float erydonMetalTexturePreservation;
                compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0,
                                                                   erydonMetalTexturePreservation);
                """);
        result = replace(PRESERVATION, result, "float texturePreservation = erydonMetalTexturePreservation;");
        return new Result(result, true, lowSampler ? "TRANSFORMED_LOW_SAMPLER" : "TRANSFORMED");
    }

    private static Result water(String source) {
        boolean fresnel = WATER_FRESNEL.matcher(source).find(), mix = WATER_BLEND.matcher(source).find();
        if (!fresnel && !mix && unique(WATER_NO_REFLECTION, source))
            return unchanged(source, "REFLECTIONS_NOT_COMPILED");
        if (!unique(WATER_FRESNEL, source) || !unique(WATER_BLEND, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = after(WATER_FRESNEL, source, """

                    // ERYDON conductor reflection response
                    vec3 erydonWaterMetalTint = vec3(1.0);
                    if (erydonMetalCoverage > 0.0) {
                        float erydonCoverage = clamp(erydonMetalCoverage, 0.0, 1.0);
                        float erydonGrazing = clamp(1.0 + dot(normalM, nViewPos), 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        vec3 erydonF0 = clamp(erydonMetalF0, vec3(0.0), vec3(1.0));
                        vec3 erydonFresnel = erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth;
                        float erydonMetalReflection = max(erydonFresnel.r, max(erydonFresnel.g, erydonFresnel.b));
                        float erydonMetalStrength = erydonMetalReflection * (0.75 + 0.25 * clamp(reflectMult, 0.0, 1.0));
                        float erydonCombined = mix(clamp(fresnelM, 0.0, 1.0), erydonMetalStrength, erydonCoverage);
                        float erydonMetalShare = clamp(erydonCoverage * erydonMetalStrength / max(erydonCombined, 0.000001), 0.0, 1.0);
                        erydonWaterMetalTint = mix(vec3(1.0), erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare);
                        fresnelM = clamp(erydonCombined, 0.0, 0.9999);
                    }
                """);
        result = WATER_BLEND.matcher(result).replaceFirst("reflection.rgb *= erydonWaterMetalTint;\n$0");
        return transformed(result);
    }

    /** Leave deferred1's separate Voxy helper fetch alone. Only main owns these materials. */
    private static String mainMaterialRgba(String source) {
        if (!unique(MAIN, source)) return null;
        Matcher main = MAIN.matcher(source);
        main.find();
        String body = source.substring(main.end());
        if (!unique(MATERIAL_FETCH, body)) return null;
        return source.substring(0, main.end())
                + replace(MATERIAL_FETCH, body, "vec4 texture6 = texelFetch(colortex6, texelCoord, 0);");
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

    private MetalReflectionResponse() { }
}
