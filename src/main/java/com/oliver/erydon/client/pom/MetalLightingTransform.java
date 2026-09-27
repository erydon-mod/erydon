package com.oliver.erydon.client.pom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces only the metallic share of CU's direct-light highlight. */
public final class MetalLightingTransform {
    private static final String MARKER = "// ERYDON conductor direct lighting";
    private static final Pattern LIGHTING_FUNCTION = Pattern.compile("void\\s+DoLighting\\s*\\(");
    private static final Pattern NATIVE_HIGHLIGHT = Pattern.compile(
            "float\\s+specularHighlight\\s*=\\s*GGX\\(normalM,\\s*nViewPos,\\s*lightVec,\\s*NdotLmax0,\\s*smoothnessG\\)\\s*;");
    private static final Pattern HIGHLIGHT_MIX = Pattern.compile(
            "lightHighlight\\s*\\*=\\s*\\(subsurfaceHighlight\\s*\\+\\s*specularHighlight\\)\\s*\\*\\s*highlightColor\\s*;");
    private static final Pattern ZERO_HIGHLIGHT = Pattern.compile("vec3\\s+lightHighlight\\s*=\\s*vec3\\(0\\.0\\)\\s*;");
    private static final Pattern DIFFUSE_APPLICATION = Pattern.compile("color\\.rgb\\s*\\*=\\s*finalDiffuse\\s*;");
    private static final Pattern HIGHLIGHT_APPLICATION = Pattern.compile("color\\.rgb\\s*\\+=\\s*lightHighlight\\s*;");

    public record Result(String text, boolean changed, String status) { }

    /**
     * The caller declares and fills erydonMetalCoverage, erydonMetalF0 (linear
     * reflectance), and erydonMetalRoughness before this helper can be called.
     * Non-metal and two-way-glass fragments leave coverage zero.
     */
    public static Result adapt(String program, String source) {
        if (!"gbuffers_terrain".equals(program) && !"gbuffers_water".equals(program))
            return new Result(source, false, "OTHER_PROGRAM");
        if (source == null) return new Result(null, false, "NOT_APPLICABLE");
        if (source.contains(MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        if (!unique(LIGHTING_FUNCTION, source) || !unique(ZERO_HIGHLIGHT, source)
                || !unique(DIFFUSE_APPLICATION, source) || !unique(HIGHLIGHT_APPLICATION, source))
            return new Result(source, false, "UNSUPPORTED_SOURCE");
        boolean nativeHighlight = NATIVE_HIGHLIGHT.matcher(source).find();
        boolean highlightMix = HIGHLIGHT_MIX.matcher(source).find();
        if (nativeHighlight != highlightMix || nativeHighlight
                && (!unique(NATIVE_HIGHLIGHT, source) || !unique(HIGHLIGHT_MIX, source)))
            return new Result(source, false, "UNSUPPORTED_SOURCE");

        String diffuse = """
                // ERYDON conductor direct lighting
                color.rgb *= finalDiffuse;
                if (erydonMetalCoverage > 0.0 && emission <= 0.0) {
                    color.rgb = erydonMetalBroadLighting(color.rgb, erydonMetalDiffuseComponent,
                                                        finalDiffuse, erydonMetalRoughness);
                }
                """;
        String result = DIFFUSE_APPLICATION.matcher(source).replaceFirst(Matcher.quoteReplacement(diffuse));
        if (!nativeHighlight) return new Result(result, true, "TRANSFORMED");

        String helper = """
                // ERYDON conductor direct lighting
                vec3 ErydonConductorHighlight(vec3 shadingNormal, vec3 viewDirection,
                                             vec3 lightDirection, float nativeLightGate) {
                    float noV = dot(shadingNormal, -viewDirection);
                    float noL = dot(shadingNormal, lightDirection);
                    if (nativeLightGate <= 0.0 || noV <= 0.0 || noL <= 0.0) return vec3(0.0);
                    noV = max(noV, 0.0001);
                    noL = max(noL, 0.0001);

                    vec3 halfVector = lightDirection - viewDirection;
                    halfVector *= inversesqrt(max(dot(halfVector, halfVector), 0.00000001));
                    float noH = clamp(dot(shadingNormal, halfVector), 0.0, 1.0);
                    float voH = clamp(dot(-viewDirection, halfVector), 0.0, 1.0);

                    // GGX distribution and correlated Smith visibility. Keep a finite
                    // direct-light lobe for subpixel sun glints without blurring the
                    // separate authored environment reflection.
                    float roughness = clamp(erydonMetalRoughness, 0.12, 1.0);
                    float alpha = roughness * roughness;
                    float alphaSquared = alpha * alpha;
                    float distributionDenominator = noH * noH * (alphaSquared - 1.0) + 1.0;
                    float distribution = alphaSquared / max(3.14159265359
                            * distributionDenominator * distributionDenominator, 0.00000001);
                    float smithV = noL * sqrt(noV * noV * (1.0 - alphaSquared) + alphaSquared);
                    float smithL = noV * sqrt(noL * noL * (1.0 - alphaSquared) + alphaSquared);
                    float visibility = 0.5 / max(smithV + smithL, 0.000001);

                    float grazing = 1.0 - voH;
                    float grazingSquared = grazing * grazing;
                    float fresnelWeight = grazingSquared * grazingSquared * grazing;
                    vec3 f0 = clamp(erydonMetalF0, vec3(0.0), vec3(1.0));
                    vec3 fresnel = f0 + (vec3(1.0) - f0) * fresnelWeight;
                    // CU's shadowMult already carries its light-facing cosine.
                    // Reuse that gate once, without a second NdotL or shine boost.
                    // CU's working colour is gamma encoded here, just like highlightColor.
                    return pow(max(fresnel * distribution * visibility, vec3(0.0)), vec3(1.0 / 2.2));
                }

                """;
        result = LIGHTING_FUNCTION.matcher(result).replaceFirst(Matcher.quoteReplacement(helper) + "$0");
        String blend = """
                if (erydonMetalCoverage > 0.0) {
                    vec3 erydonConductor = ErydonConductorHighlight(normalM, nViewPos, lightVec, NdotLmax0);
                    vec3 erydonDirectHighlight = mix(vec3(specularHighlight), erydonConductor,
                                                    clamp(erydonMetalCoverage, 0.0, 1.0));
                    lightHighlight *= (vec3(subsurfaceHighlight) + erydonDirectHighlight) * highlightColor;
                } else {
                    lightHighlight *= (subsurfaceHighlight + specularHighlight) * highlightColor;
                }
                """;
        result = HIGHLIGHT_MIX.matcher(result).replaceFirst(Matcher.quoteReplacement(blend));
        return new Result(result, true, "TRANSFORMED");
    }

    private static boolean unique(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() && !matcher.find();
    }

    private MetalLightingTransform() { }
}
