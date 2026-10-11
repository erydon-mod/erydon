package com.oliver.erydon.client.pom;

import java.util.regex.Pattern;

/** Avoids an unused AF8 sample only after the recessed-inlay source contract is proved. */
final class InlayInitialAlbedoFilter {
    static final String MARKER = "// ERYDON skip initial inlay AF8 sample";
    static final String ACTIVE = "erydonInlayActive = erydonInlaySubstrateRecord >= 0 && erydonMetalInfo.x == 1\n"
            + "        && all(greaterThan(erydonInlayBaseBounds.zw, vec2(0.0)));";
    private static final String INITIAL = "vec4 color = textureAF(tex, texCoord);";
    private static final String SIGNATURE = "vec4 textureAF(sampler2D texSampler, vec2 uv) {";
    private static final String DERIVATIVES = "mat2 J = inverseM(mat2(dFdx(uv), dFdy(uv)));";
    private static final String GRADIENT_X = "vec2 dcdx = dFdx(texCoord.xy);";
    private static final String GRADIENT_Y = "vec2 dcdy = dFdy(texCoord.xy);";
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    static String adapt(String source) {
        if (source == null || source.contains(MARKER)) return source;
        String code = COMMENTS.matcher(source).replaceAll("");
        if (!once(source, ACTIVE) || !once(source, INITIAL) || !once(source, SIGNATURE)
                || !once(source, DERIVATIVES) || !once(source, GRADIENT_X) || !once(source, GRADIENT_Y)
                || !once(code, "float samplesDiv2 = 8 / 2.0;")
                || !once(code, "vec2 ADivSamples = A / 8;") || !once(code, "filteredColor.a /= 8;")
                || words(code, "textureAF") != 2 || words(code, "colorP") != 1) return source;

        int main = code.indexOf("void main()"), mainOpen = code.indexOf('{', main);
        int materials = code.indexOf("GetCustomMaterials(color,", mainOpen);
        if (main < 0 || mainOpen < 0 || materials < mainOpen) return source;
        String prefix = code.substring(mainOpen, materials + "GetCustomMaterials(color,".length());
        // Initial RGB has no surviving use: colorP is dead in this compiled LabPBR path,
        // the only early discard sees admitted inlays as opaque, and trace replaces RGBA.
        if (words(prefix, "color") != 6
                || !prefix.contains("float erydonOverlayAlpha = erydonInlayActive ? 1.0 : erydonMetalCutout(")
                || !prefix.contains("if (erydonOverlayAlpha >= 0.0) { if (erydonOverlayAlpha < 0.5) discard; color.a = 1.0; }")
                || !prefix.contains("if (color.a <= 0.00001) discard;")
                || !prefix.contains("vec3 colorP = color.rgb;") || !prefix.contains("color.rgb *= glColor.rgb;")) return source;

        int dx = code.indexOf(GRADIENT_X), dy = code.indexOf(GRADIENT_Y), af = code.indexOf(SIGNATURE);
        if (dx > af || dy > af || depth(code, dx) != 0 || depth(code, dy) != 0) return source;
        int trace = code.indexOf("void erydonInlayTrace("), traceOpen = code.indexOf('{', trace);
        int traceEnd = close(code, traceOpen);
        if (trace < 0 || traceOpen < 0 || traceEnd < 0) return source;
        String traceBody = code.substring(traceOpen, traceEnd);
        String overwrite = "color = vec4(baseColor.rgb * glColor.rgb, 1.0);";
        int assignment = traceBody.indexOf(overwrite);
        if (words(traceBody, "return") != 0 || words(traceBody, "color") != 1
                || !once(code, overwrite) || depth(traceBody, assignment) != 1) return source;
        int previous = assignment - 1;
        while (previous >= 0 && Character.isWhitespace(traceBody.charAt(previous))) previous--;
        // A naked if/else/loop body also has depth1. Its preceding token is
        // not a completed statement or brace, so it cannot prove an overwrite.
        if (previous < 0 || "{};".indexOf(traceBody.charAt(previous)) < 0) return source;
        if (!Pattern.compile("vec2\\s+texCoordM\\s*=\\s*texCoord\\s*;\\s*if\\s*\\(erydonInlayActive\\)\\s*\\{\\s*"
                + "erydonInlayTrace\\(texCoordM,\\s*color,\\s*lViewPos\\);").matcher(code).find()) return source;

        String result = source.replace(ACTIVE, "")
                .replace(SIGNATURE, "vec4 textureAF(sampler2D texSampler, vec2 uv, vec2 uvDx, vec2 uvDy) {")
                .replace(DERIVATIVES, "mat2 J = inverseM(mat2(uvDx, uvDy));");
        int function = result.indexOf("vec4 textureAF("), end = close(result, result.indexOf('{', function));
        if (end < 0) return source;
        // Keep the original two-argument entry point and arithmetic for native callers.
        result = result.substring(0, end + 1)
                + "\nvec4 textureAF(sampler2D texSampler, vec2 uv) { return textureAF(texSampler, uv, dFdx(uv), dFdy(uv)); }\n"
                + result.substring(end + 1);
        // dcdx/dcdy are the existing unconditional terrain gradients. Derivatives
        // inside the varying fallback branch would be undefined at primitive joins.
        return result.replace(INITIAL, MARKER + "\n" + ACTIVE
                + "\nvec4 color = vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "if (!erydonInlayActive) color = textureAF(tex, texCoord, dcdx, dcdy);");
    }

    private static boolean once(String source, String token) {
        int first = source.indexOf(token);
        return first >= 0 && source.indexOf(token, first + token.length()) < 0;
    }

    private static int words(String source, String word) {
        return (int) Pattern.compile("\\b" + word + "\\b").matcher(source).results().count();
    }

    private static int depth(String source, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return depth;
    }

    private static int close(String source, int open) {
        if (open < 0) return -1;
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private InlayInitialAlbedoFilter() { }
}
