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
    private static final Pattern SSR_FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*max\\(fresnel\\s*-\\s*fresnelFactor,\\s*0\\.0\\)\\s*/\\s*\\(1\\.0\\s*-\\s*fresnelFactor\\)\\s*;");
    private static final Pattern WSR_FRESNEL = Pattern.compile("fresnelM\\s*=\\s*fresnel\\s*\\*\\s*0\\.7\\s*\\+\\s*0\\.3\\s*;");
    private static final Pattern TINT = Pattern.compile("reflection\\.rgb\\s*\\*=\\s*reflectColor\\s*;");
    private static final Pattern FILTER_SIGNATURE = Pattern.compile(
            "vec4\\s+sampleBlurFilteredReflection\\(vec4\\s+centerCol,\\s*vec3\\s+nViewPos,\\s*float\\s+dither,\\s*float\\s+z0\\)\\s*\\{");
    private static final Pattern FILTER_RETURN = Pattern.compile("return\\s+sum\\s*/\\s*weightSum\\s*;");
    private static final Pattern FILTER_CALL = Pattern.compile(
            "compositeReflection\\s*=\\s*sampleBlurFilteredReflection\\(compositeReflection,\\s*nViewPos,\\s*dither,\\s*z0\\)\\s*;");
    private static final Pattern PRESERVATION = Pattern.compile("const\\s+float\\s+texturePreservation\\s*=\\s*0\\.7\\s*;");
    private static final Pattern FINAL_BLEND = Pattern.compile("compositeReflection\\.rgb\\s*=\\s*mix\\(compositeReflection\\.rgb,\\s*max\\(color,\\s*compositeReflection\\.rgb\\),\\s*texturePreservation\\)\\s*;\\s*color\\s*=\\s*mix\\(color,\\s*compositeReflection\\.rgb,\\s*fresnelM\\)\\s*;");
    private static final Pattern WATER_WSR = Pattern.compile("compositeReflection\\.rgb\\s*\\*=\\s*fresnelM\\s*;");
    private static final Pattern WATER_REFLECTION_OUTPUT = Pattern.compile("reflection\\.rgb\\s*\\*\\s*fresnelM\\s*\\*\\s*color\\.a\\s*\\*\\s*fogAlpha");
    private static final Pattern WATER_BACKGROUND_HIGHLIGHT = Pattern.compile("skyReflection\\s*\\+=\\s*specularHighlight\\s*\\*\\s*highlightColor\\s*\\*\\s*shadowMult\\s*\\*\\s*highlightMult\\s*\\*\\s*invRainFactor\\s*;");
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
        boolean ssr = unique(SSR_FRESNEL, source), wsr = unique(WSR_FRESNEL, source);
        if (ssr == wsr) return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = mainMaterialRgba(source);
        if (result == null) return unchanged(source, "UNSUPPORTED_SOURCE");
        String insertion = """

                    // ERYDON conductor reflection response
                    if (materialMaskInt == 243 || materialMaskInt == 244) {
                        float erydonMaterialWord = floor(clamp(texture6.a, 0.0, 1.0) * 65535.0 + 0.5);
                        float erydonMaterialByte = floor(erydonMaterialWord / 256.0);
                        float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                        float erydonBaseFinish = mod(erydonMaterialByte, 4.0);
                        float erydonRoughness = mod(erydonMaterialWord, 256.0) / 255.0;
                        float erydonMetalSmoothness = (1.0 - erydonRoughness) * (1.0 - erydonRoughness);
                        // ERYDON recovered substrate reflection
                        float erydonBaseSmoothness = clamp((smoothnessD - erydonCoverage * erydonMetalSmoothness)
                                / max(1.0 - erydonCoverage, 0.000001), 0.0, 1.0);
                        %s
                        // Metal tagging replaces the original dielectric byte. Eight
                        // is the supported polished stone value; unknown low-F0 stone
                        // retains a bounded approximation only at fractional coverage.
                        erydonBaseReflection = mix(erydonBaseReflection * erydonBaseReflection,
                                erydonBaseReflection * 0.75 + 0.25, 8.0 / 240.0);
                        erydonBaseReflection = erydonBaseReflection * sqrt1(erydonBaseSmoothness) - dither * 0.01;
                        if (erydonBaseFinish > 2.5) {
                            erydonBaseReflection = (pow3(fresnel) * 0.5 + 0.5) * erydonBaseSmoothness;
                        }
                        float erydonMetalF0Max = materialMaskInt == 243 ? 0.92 : 0.95;
                        float erydonGrazing = clamp(fresnel, 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        float erydonMetalReflection = erydonMetalF0Max + (1.0 - erydonMetalF0Max) * erydonGrazingFifth;
                        // G4 is SNORM8: sqrt(.9999) rounds to 1, which CU treats as an error.
                        fresnelM = clamp(mix(clamp(erydonBaseReflection, 0.0, 1.0), erydonMetalReflection,
                                             erydonCoverage), 0.0, 0.98);
                    }
                """.formatted(ssr ? "float erydonBaseFactor = (1.0 - erydonBaseSmoothness) * 0.7;\n"
                        + "                        float erydonBaseReflection = max(fresnel - erydonBaseFactor, 0.0) / (1.0 - erydonBaseFactor);"
                        : "float erydonBaseReflection = fresnel * 0.7 + 0.3;");
        return transformed(after(FRESNEL, result, insertion));
    }

    private static Result composite(String source) {
        if (!unique(TINT, source)) return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = mainMaterialRgba(source);
        if (result == null) return unchanged(source, "UNSUPPORTED_SOURCE");
        String insertion = """
                    // ERYDON conductor reflection response
                    if (materialMaskInt == 243 || materialMaskInt == 244) {
                        float erydonMaterialWord = floor(clamp(texture6.a, 0.0, 1.0) * 65535.0 + 0.5);
                        float erydonMaterialByte = floor(erydonMaterialWord / 256.0);
                        float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                        // Same canonical bronze colour ratios as erydonConductorF0.
                        vec3 erydonF0 = materialMaskInt == 243 ? vec3(0.92, 0.418036, 0.00975945) : vec3(0.95, 0.93, 0.88);
                        float erydonGrazing = clamp(fresnel, 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        vec3 erydonFresnel = erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth;
                        float erydonMetalReflection = max(erydonFresnel.r, max(erydonFresnel.g, erydonFresnel.b));
                        // SNORM reflection strength may round upward. A fully metal
                        // pixel must not acquire a white substrate share from that error.
                        float erydonMetalShare = erydonCoverage > 0.999 ? 1.0 : clamp(erydonCoverage * erydonMetalReflection
                                / max(fresnelM * fresnelM, 0.000001), 0.0, 1.0);
                        float erydonRoughness = mod(erydonMaterialWord, 256.0) / 255.0;
                        float erydonBroadShare = max(erydonRoughness * erydonRoughness, 0.06);
                        // Match the broad illumination proxy used by surface lighting.
                        // CU carries encoded scene RGB until composite1: encode this
                        // linear reflectance coefficient too, so it is not gamma-squared.
                        vec3 erydonLinearTint = mix(vec3(1.0), (1.0 - erydonBroadShare)
                                * erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare);
                        reflectColor = pow(max(erydonLinearTint, vec3(0.0)), vec3(1.0 / 2.2));
                    }
                """;
        result = TINT.matcher(result).replaceFirst(Matcher.quoteReplacement(insertion) + "$0");
        return transformed(result);
    }

    private static Result blend(String source) {
        if (!unique(FILTER_SIGNATURE, source) || !unique(FILTER_RETURN, source)
                || !unique(FILTER_CALL, source) || !unique(PRESERVATION, source) || !unique(FINAL_BLEND, source))
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
                        // A negative sentinel carries only the opaque silver coating.
                        if (erydonBlendMask == 245) {
                            erydonMetalBlend = vec2(-1.0, 0.0);
                        } else if (erydonBlendMask == 243 || erydonBlendMask == 244) {
                            float erydonMaterialWord = floor(clamp(texture6.a, 0.0, 1.0) * 65535.0 + 0.5);
                            float erydonMaterialByte = floor(erydonMaterialWord / 256.0);
                            float erydonCoverage = floor(erydonMaterialByte / 4.0) / 63.0;
                            float erydonF0Max = erydonBlendMask == 243 ? 0.92 : 0.95;
                            float erydonGrazing = clamp(1.0 + dot(mat3(gbufferModelView) * texture4.rgb, nViewPos), 0.0, 1.0);
                            float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                            erydonMetalBlend = vec2(erydonCoverage, erydonF0Max + (1.0 - erydonF0Max) * erydonGrazingFifth);
                        }
                    """);
        }
        String signature = """
                vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0,
                                                  out vec2 erydonMetalBlend) {
                    // ERYDON conductor reflection response
                    erydonMetalBlend = vec2(0.0);
                """;
        String result = source.substring(0, start.start()) + signature + body + source.substring(end.start());
        result = replace(FILTER_CALL, result, """
                vec2 erydonMetalBlend;
                compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0,
                                                                   erydonMetalBlend);
                """);
        result = replace(FINAL_BLEND, result, """
                if (erydonMetalBlend.x < 0.0) {
                    // Match the two-way coating: reflection is not lifted by lit albedo.
                    color = mix(color, compositeReflection.rgb, fresnelM);
                } else if (erydonMetalBlend.x > 0.0) {
                    // Surface lighting has removed only metal diffuse, retaining direct
                    // highlights and the broad illumination approximation. Absorption is not
                    // replaced with a coat of lit albedo. At fractional coverage we use
                    // one bounded attenuation for the shared base; endpoints are exact.
                    float erydonStoneFresnelShare = clamp(fresnelM - erydonMetalBlend.x * erydonMetalBlend.y,
                                                         0.0, 1.0 - erydonMetalBlend.x);
                    vec3 erydonBaseLinear = pow(max(color, vec3(0.0)), vec3(2.2));
                    vec3 erydonReflectionLinear = pow(max(compositeReflection.rgb, vec3(0.0)), vec3(2.2));
                    color = pow(max(erydonBaseLinear * (1.0 - erydonStoneFresnelShare)
                            + erydonReflectionLinear * fresnelM, vec3(0.0)), vec3(1.0 / 2.2));
                } else {
                    compositeReflection.rgb = mix(compositeReflection.rgb, max(color, compositeReflection.rgb), texturePreservation);
                    color = mix(color, compositeReflection.rgb, fresnelM);
                }
                """);
        if (!lowSampler && WATER_WSR.matcher(result).find()) {
            if (!unique(WATER_WSR, result)) return unchanged(source, "UNSUPPORTED_SOURCE");
            result = replace(WATER_WSR, result, """
                    // This branch has no blur-filter fetch; reuse its existing sampler.
                    int erydonTranslucentMask = int(texelFetch(colortex6, texelCoord, 0).g * 255.1);
                    if (erydonTranslucentMask == 243 || erydonTranslucentMask == 244) {
                        vec3 erydonTranslucentBase = pow(max(color, vec3(0.0)), vec3(2.2));
                        vec3 erydonTranslucentReflection = pow(max(compositeReflection.rgb, vec3(0.0)), vec3(2.2)) * fresnelM;
                        // colortex8 holds the actual encoded-space delta; provide WSR
                        // in the same form for CU's existing SSR/WSR replacement blend.
                        compositeReflection.rgb = max(pow(erydonTranslucentBase + erydonTranslucentReflection,
                                vec3(1.0 / 2.2)) - color, vec3(0.0));
                    } else compositeReflection.rgb *= fresnelM;
                    """);
        }
        return new Result(result, true, lowSampler ? "TRANSFORMED_LOW_SAMPLER" : "TRANSFORMED");
    }

    private static Result water(String source) {
        boolean fresnel = WATER_FRESNEL.matcher(source).find(), mix = WATER_BLEND.matcher(source).find();
        if (!fresnel && !mix && unique(WATER_NO_REFLECTION, source))
            return unchanged(source, "REFLECTIONS_NOT_COMPILED");
        if (!unique(WATER_FRESNEL, source) || !unique(WATER_BLEND, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        if (WATER_BACKGROUND_HIGHLIGHT.matcher(source).find() && !unique(WATER_BACKGROUND_HIGHLIGHT, source))
            return unchanged(source, "UNSUPPORTED_SOURCE");
        String result = after(WATER_FRESNEL, source, """

                    // ERYDON conductor reflection response
                    vec3 erydonWaterMetalTint = vec3(1.0);
                    vec3 erydonWaterReflectionDelta = vec3(0.0);
                    float erydonWaterBaseAttenuation = 1.0;
                    if (erydonMetalCoverage > 0.0) {
                        float erydonCoverage = clamp(erydonMetalCoverage, 0.0, 1.0);
                        float erydonGrazing = clamp(1.0 + dot(normalM, nViewPos), 0.0, 1.0);
                        float erydonGrazingFifth = erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing * erydonGrazing;
                        vec3 erydonF0 = clamp(erydonMetalF0, vec3(0.0), vec3(1.0));
                        vec3 erydonFresnel = erydonF0 + (vec3(1.0) - erydonF0) * erydonGrazingFifth;
                        float erydonMetalReflection = max(erydonFresnel.r, max(erydonFresnel.g, erydonFresnel.b));
                        float erydonMetalStrength = erydonMetalReflection;
                        float erydonCombined = mix(clamp(fresnelM, 0.0, 1.0), erydonMetalStrength, erydonCoverage);
                        float erydonMetalShare = clamp(erydonCoverage * erydonMetalStrength / max(erydonCombined, 0.000001), 0.0, 1.0);
                        float erydonRoughness = clamp(erydonMetalRoughness, 2.0 / 255.0, 229.0 / 255.0);
                        float erydonBroadShare = max(erydonRoughness * erydonRoughness, 0.06);
                        vec3 erydonLinearTint = mix(vec3(1.0), (1.0 - erydonBroadShare)
                                * erydonFresnel / max(erydonMetalReflection, 0.000001), erydonMetalShare);
                        erydonWaterMetalTint = pow(max(erydonLinearTint, vec3(0.0)), vec3(1.0 / 2.2));
                        erydonWaterBaseAttenuation = 1.0 - (1.0 - erydonCoverage) * clamp(fresnelM, 0.0, 1.0);
                        fresnelM = clamp(erydonCombined, 0.0, 0.98);
                    }
                """);
        result = WATER_BLEND.matcher(result).replaceFirst(Matcher.quoteReplacement("""
                if (erydonMetalCoverage > 0.0) {
                    reflection.rgb *= erydonWaterMetalTint;
                    vec3 erydonWaterBaseLinear = pow(max(color.rgb, vec3(0.0)), vec3(2.2)) * erydonWaterBaseAttenuation;
                    vec3 erydonWaterReflectionLinear = pow(max(reflection.rgb, vec3(0.0)), vec3(2.2)) * fresnelM;
                    vec3 erydonWaterBaseEncoded = pow(erydonWaterBaseLinear, vec3(1.0 / 2.2));
                    color.rgb = pow(erydonWaterBaseLinear + erydonWaterReflectionLinear, vec3(1.0 / 2.2));
                    erydonWaterReflectionDelta = max(color.rgb - erydonWaterBaseEncoded, vec3(0.0));
                } else {
                    color.rgb = mix(color.rgb, reflection.rgb, fresnelM);
                }
                """));
        // WSR later subtracts/replaces this term from encoded scene colour. Its
        // value must match what the linear metal composition actually added.
        result = WATER_REFLECTION_OUTPUT.matcher(result).replaceAll(Matcher.quoteReplacement(
                "(erydonMetalCoverage > 0.0 ? erydonWaterReflectionDelta : reflection.rgb * fresnelM) * color.a * fogAlpha"));
        result = WATER_BACKGROUND_HIGHLIGHT.matcher(result).replaceFirst(Matcher.quoteReplacement("""
                // Metal sunlight is already included in DoLighting's conductor lobe.
                if (erydonMetalCoverage > 0.0) {
                    skyReflection += (1.0 - clamp(erydonMetalCoverage, 0.0, 1.0))
                            * specularHighlight * highlightColor * shadowMult * highlightMult * invRainFactor;
                } else {
                    skyReflection += specularHighlight * highlightColor * shadowMult * highlightMult * invRainFactor;
                }
                """));
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
