package com.oliver.erydon.client.pom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Omits identity self-shadow taps only in the recognized normalized-normal atlas path. */
final class PomShadowCeilingFilter {
    static final String MARKER = "// ERYDON skip POM shadow taps above normalized height ceiling";
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern DEPTH = Pattern.compile("parallaxdir\\.xy\\*=1\\.0\\*[0-9]+(?:\\.[0-9]+)?;");
    private static final Pattern NATIVE_TAP = Pattern.compile("(?m)^([ \\t]*)vec2 parallaxCoord = erydonCtmPomAtlasUv\\(coord \\+ parallaxdir\\.xy \\* stepLC\\);\\s*"
            + "float offsetHeight = textureGrad\\(normals, parallaxCoord, dcdx, dcdy\\)\\.a;\\s*"
            + "parallaxshadow \\*= clamp\\(1\\.0 - \\(offsetHeight - currentHeight\\) \\* 4\\.0, 0\\.0, 1\\.0\\);");
    private static final Pattern INLAY_TAP = Pattern.compile("(?m)^([ \\t]*)float offsetHeight = erydonInlaySubstrateHeight\\(erydonInlayShadowLocal \\+ direction\\.xy / erydonInlayRibbonScale \\* stepLC\\);\\s*"
            + "shadow \\*= clamp\\(1\\.0 - \\(offsetHeight - currentHeight\\) \\* 4\\.0, 0\\.0, 1\\.0\\);");
    private static final String NATIVE = compact("""
            float GetParallaxShadow(float parallaxFade, float dither, float height, vec2 coord, vec3 lightVec, mat3 tbn) {
                float parallaxshadow = 1.0;
                vec3 parallaxdir = tbn * lightVec;
                parallaxdir.xy *= 1.0 * DEPTH;
                for (int i = 0; i < 4 && parallaxshadow >= 0.01; i++) {
                    float stepLC = 0.025 * (i + dither);
                    float currentHeight = height + parallaxdir.z * stepLC;
                    vec2 parallaxCoord = erydonCtmPomAtlasUv(coord + parallaxdir.xy * stepLC);
                    float offsetHeight = textureGrad(normals, parallaxCoord, dcdx, dcdy).a;
                    parallaxshadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
                }
                return mix(parallaxshadow, 1.0, parallaxFade);
            }
            """);
    private static final String INLAY = compact("""
            float erydonInlayShadow(vec3 lightTangent, float dither) {
                float shadow = 1.0;
                if (erydonInlayShadowBaseFade > 0.0) {
                    vec3 direction = lightTangent;
                    direction.xy *= ERYDON_INLAY_POM_DEPTH;
                    for (int i = 0; i < 4 && shadow >= 0.01; i++) {
                        float stepLC = 0.025 * (float(i) + dither);
                        float currentHeight = erydonInlayShadowBaseHeight + direction.z * stepLC;
                        float offsetHeight = erydonInlaySubstrateHeight(erydonInlayShadowLocal + direction.xy / erydonInlayRibbonScale * stepLC);
                        shadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
                    }
                    shadow = mix(1.0, shadow, erydonInlayShadowBaseFade);
                }
                if (erydonInlayMetalCoverage > 0.999 && erydonInlayCavityDepth > 0.0) {
                    if (lightTangent.z <= 0.0) return 0.0;
                    vec2 opening;
                    vec3 wall;
                    vec2 ray = lightTangent.xy / erydonInlayRibbonScale / max(lightTangent.z, 0.05)
                            * erydonInlayCavityDepth * vec2(erydonMetalBounds.zw);
                    shadow *= erydonInlayTraceGroove(erydonInlayShadowLocal, ray, opening, wall);
                }
                return shadow;
            }
            """);
    private static final String HEIGHT = compact("""
            float erydonInlaySubstrateHeight(vec2 local) {
                vec2 scale;
                vec2 uv = erydonInlaySubstrateUv(local, scale);
                return textureGrad(normals, uv, erydonInlayGradient(dcdx, scale), erydonInlayGradient(dcdy, scale)).a;
            }
            """);

    static String adapt(String source) {
        if (source == null || source.contains(MARKER)) return source;
        // Preserve offsets so a lookalike tap outside its proved function can
        // never substitute for the original function's differently spaced tap.
        String code = COMMENTS.matcher(source).replaceAll(match -> " ".repeat(match.group().length()));
        Function nativeFunction = function(code, "GetParallaxShadow");
        Function inlayFunction = function(code, "erydonInlayShadow");
        Function heightFunction = function(code, "erydonInlaySubstrateHeight");
        if (nativeFunction == null) return source;
        String nativeCode = nativeFunction.code();
        Matcher depth = DEPTH.matcher(nativeCode);
        if (!depth.find() || depth.find()
                || !NATIVE.equals(DEPTH.matcher(nativeCode).replaceFirst("parallaxdir.xy*=1.0*DEPTH;"))
                || inlayFunction == null || !INLAY.equals(inlayFunction.code())
                || heightFunction == null || !HEIGHT.equals(heightFunction.code())
                || !uniqueTapInside(source, NATIVE_TAP, nativeFunction)
                || !uniqueTapInside(source, INLAY_TAP, inlayFunction)) return source;

        // Called only after the supported properties fingerprint and metallic
        // source preflight. Iris supplies the ordinary normalized normal atlas:
        // alpha <= 1, so this clamp is exactly 1 whenever currentHeight >= 1.
        // Keep every loop/fade/dither operation and every original multiply.
        // Negated >= also retains the original path for a NaN currentHeight.
        return wrap(wrap(source, NATIVE_TAP), INLAY_TAP);
    }

    private static String wrap(String source, Pattern pattern) {
        Matcher match = pattern.matcher(source);
        if (!match.find()) return source;
        String original = match.group(), indent = match.group(1);
        int multiply = original.indexOf(pattern == NATIVE_TAP ? "parallaxshadow *=" : "shadow *=");
        String sample = original.substring(0, multiply).stripTrailing().replace("float offsetHeight =", "offsetHeight =");
        // One is a valid normalized sample and leaves the original clamp as
        // exactly one above the ceiling, including an infinite currentHeight.
        String replacement = indent + MARKER + "\n" + indent + "float offsetHeight = 1.0;\n"
                + indent + "if (!(currentHeight >= 1.0)) {\n" + sample + "\n" + indent + "}\n"
                + indent + original.substring(multiply);
        return match.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static boolean uniqueTapInside(String source, Pattern pattern, Function function) {
        Matcher match = pattern.matcher(source);
        return match.find() && match.start() >= function.start() && match.end() <= function.end() && !match.find();
    }

    private static Function function(String code, String name) {
        Matcher match = Pattern.compile("\\bfloat\\s+" + name + "\\s*\\(").matcher(code);
        if (!match.find()) return null;
        int start = match.start(), open = code.indexOf('{', match.end());
        if (open < 0 || match.find()) return null;
        int depth = 1;
        for (int i = open + 1; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            if (code.charAt(i) == '}' && --depth == 0) return new Function(start, i + 1, compact(code.substring(start, i + 1)));
        }
        return null;
    }

    private static String compact(String code) {
        return code == null ? "" : code.replaceAll("\\s+", "");
    }

    private record Function(int start, int end, String code) { }

    private PomShadowCeilingFilter() { }
}
