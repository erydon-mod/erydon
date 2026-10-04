package com.oliver.erydon.client.pom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sprite-scoped conductor response, independent of the player's stone finish controls. */
public final class MetallicShaderAdapter {
    public static final List<String> PROGRAMS = List.of("gbuffers_terrain", "gbuffers_water",
            "deferred1", "composite", "composite1", "shadow", "composite6", "final");
    private static final String MARKER = "// ERYDON sprite metal response";
    private static final Pattern FIRST_FUNCTION = Pattern.compile("(?m)^(?:void|float|int|bool|[biu]?vec[234]|mat[234])\\s+\\w+\\s*\\([^;{}]*\\)\\s*\\{");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern SPECULAR = Pattern.compile("vec4\\s+specularMap\\s*=\\s*texture2D\\(specular,\\s*texCoordM\\)\\s*;");
    private static final Pattern TERRAIN_BUFFER = Pattern.compile("gl_FragData\\[1\\]\\s*=\\s*vec4\\(smoothnessD,\\s*materialMask,\\s*skyLightFactor,\\s*1\\.0\\)\\s*;");
    private static final Pattern WATER_BUFFER = Pattern.compile("vec4\\(1\\.0,\\s*materialMask,\\s*skyLightFactor,\\s*1\\.0\\)");
    private static final Pattern WATER_REFLECT = Pattern.compile("reflectMult\\s*=\\s*smoothnessD\\s*;");
    private static final String LOOKUP = resource("erydon_metal_lookup.glsl");
    private static final String FRAGMENT = resource("erydon_metal_fragment.glsl");
    private static final String RECESS = resource("erydon_inlay_recess.glsl");
    private static final String INLAY_PROJECTION = resource("erydon_inlay_projection.glsl");
    private static final String RECESS_MARKER = "// ERYDON recessed inlay substrate";
    private static final Pattern POM_DEPTH = Pattern.compile("parallaxdir\\.xy\\s*\\*=\\s*1\\.0\\s*\\*\\s*([0-9]+(?:\\.[0-9]+)?)\\s*;");
    private static final Pattern POM_DISTANCE = Pattern.compile("parallaxFade\\s*=\\s*pow2\\(lViewPos\\s*/\\s*([0-9]+(?:\\.[0-9]+)?)\\)\\s*;");
    private static final Pattern CUSTOM_MATERIALS = Pattern.compile("void\\s+GetCustomMaterials\\s*\\(");
    private static final Pattern MATERIAL_UV = Pattern.compile("vec2\\s+texCoordM\\s*=\\s*texCoord\\s*;");
    private static final Pattern SKIP_POM = Pattern.compile("bool\\s+skipPom\\s*=\\s*[^;]+;");
    private static final Pattern SKIPPED_NORMAL = Pattern.compile("else\\s+normalMap\\s*=\\s*texture2D\\(normals,\\s*texCoordM\\)\\s*;");
    private static final Pattern MATERIAL_NORMAL = Pattern.compile("normalM\\s*=\\s*clamp\\(normalize\\(normalM\\s*\\*\\s*tbnMatrix\\),\\s*vec3\\(-1\\.0\\),\\s*vec3\\(1\\.0\\)\\)\\s*;");

    public record Program(String vertex, String fragment, boolean changed, String status) { }

    public static boolean recessSupported(Program program) {
        return program != null && program.changed() && program.vertex() != null && program.fragment() != null
                && program.vertex().contains(RECESS_MARKER) && program.fragment().contains(RECESS_MARKER);
    }

    public static Program adapt(String program, String vertex, String fragment, boolean enabled) {
        if (!enabled || fragment == null) return new Program(vertex, fragment, false, "DISABLED");
        if (!PROGRAMS.contains(program)) return new Program(vertex, fragment, false, "OTHER_PROGRAM");
        if (fragment.contains(MARKER)) return new Program(vertex, fragment, false, "ALREADY_TRANSFORMED");
        String v = vertex, f = fragment;
        try {
            if (program.equals("gbuffers_terrain") || program.equals("gbuffers_water")) {
                require(vertex != null && vertex.contains("attribute vec4 mc_midTexCoord;"));
                // The metadata encodes labPBR categories. Other PBR formats remain native.
                require(Pattern.compile("highlightMult\\s*\\*=\\s*0\\.5\\s*\\+\\s*0\\.5\\s*\\*\\s*specularMap\\.g").matcher(fragment).find());
                require(unique(SPECULAR, f) && unique(MAIN, v));
                boolean recess = program.equals("gbuffers_terrain")
                        && v.contains(ComplementaryUnboundDev5SourceTransformer.HELPER_SENTINEL)
                        && f.contains("vec2 erydonCtmPomAtlasUv(")
                        && unique(POM_DEPTH, f) && unique(POM_DISTANCE, f)
                        && unique(CUSTOM_MATERIALS, f) && unique(MATERIAL_UV, f)
                        && unique(SKIP_POM, f) && unique(SKIPPED_NORMAL, f) && unique(MATERIAL_NORMAL, f);
                String declarations = "flat out ivec4 erydonMetalBounds;\nflat out ivec4 erydonMetalInfo;\nflat out ivec2 erydonMetalAtlas;\nflat out vec3 erydonMetalAlbedoMean;\n";
                if (recess) {
                    declarations += INLAY_PROJECTION + RECESS_MARKER + "\nflat out int erydonInlaySubstrateRecord;\nflat out vec4 erydonInlayBaseBounds;\n"
                            + "flat out int erydonInlayRibbon;\nout vec2 erydonInlayBaseLocal;\n";
                    if (!Pattern.compile("attribute\\s+vec[34]\\s+at_midBlock\\s*;").matcher(v).find()) {
                        declarations += "attribute vec3 at_midBlock;\n";
                    }
                }
                v = injectHelpers(v, MARKER + "\n" + LOOKUP + declarations);
                v = after(MAIN, v, """

                        erydonMetalBounds = ivec4(0);
                        erydonMetalInfo = ivec4(0);
                        erydonMetalAtlas = ivec2(0);
                        erydonMetalAlbedoMean = vec3(0.0);
                        // The invalid one-pixel placeholder has no header or records.
                        if (textureSize(erydonMetalLookup, 0).x == 1024) {
                            ivec4 magic = erydonMetalBytes(3);
                            if (all(equal(magic, ivec4(77, 69, 84, 1)))) {
                                ivec4 atlasHeader = erydonMetalBytes(0);
                                erydonMetalAtlas = ivec2(erydonMetalU16(atlasHeader.xy), erydonMetalU16(atlasHeader.zw));
                                erydonReadMetal((gl_TextureMatrix[0] * mc_midTexCoord).xy,
                                                erydonMetalAtlas, erydonMetalBounds, erydonMetalInfo, erydonMetalAlbedoMean);
                            }
                        }
                        """);
                if (recess) {
                    v = after(MAIN, v, """
                            erydonInlaySubstrateRecord = -1;
                            erydonInlayBaseBounds = vec4(0.0);
                            erydonInlayRibbon = 0;
                            erydonInlayBaseLocal = vec2(0.0);
                            if (mc_Entity.y <= -2.0 && erydonCtmPomHeaderValid(vec2(atlasSize))) {
                                int erydonRecord = erydonInlayDecodeRecord(mc_Entity.y, erydonInlayRibbon);
                                if (float(erydonRecord) < erydonCtmPomRecordCount()) {
                                    vec4 erydonBounds = erydonCtmPomReadBoundsPx(float(erydonRecord));
                                    if (all(greaterThan(erydonBounds.zw, vec2(0.0)))) {
                                        erydonInlaySubstrateRecord = erydonRecord;
                                        erydonInlayBaseBounds = erydonBounds;
                                        if (erydonInlayRibbon == 1) {
                                            // Mid-block identifies the integer cell only. Keep the
                                            // full position precision for UVs rather than its 1/64 packing.
                                            // The integer camera translation cancels in the cell
                                            // projection; omit it to preserve precision far from spawn.
                                            vec3 erydonWorld = (gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex).xyz + fract(cameraPosition);
                                            erydonInlayBaseLocal = erydonInlayProjectBase(erydonWorld, at_midBlock.xyz, gl_Normal);
                                        }
                                    }
                                }
                            }
                            """);
                }
                String recessDeclarations = recess ? RECESS_MARKER + """

                        flat in int erydonInlaySubstrateRecord;
                        flat in vec4 erydonInlayBaseBounds;
                        flat in int erydonInlayRibbon;
                        in vec2 erydonInlayBaseLocal;
                        bool erydonInlayActive = false;
                        float erydonInlayMetalCoverage = 0.0;
                        vec3 erydonInlayWallNormal = vec3(0.0);
                        vec4 erydonInlayNormalSample = vec4(0.5, 0.5, 1.0, 1.0);
                        vec4 erydonInlaySpecularSample = vec4(0.0);
                        """ : "";
                f = injectHelpers(f, MARKER + "\n" + LOOKUP + recessDeclarations + FRAGMENT);
                boolean temporalCoverage = Pattern.compile("TAAJitter\\(gl_Position\\.xy,\\s*gl_Position\\.w\\)").matcher(vertex).find();
                f = surface(f, program.equals("gbuffers_water"), temporalCoverage, recess);
                if (recess) f = recess(f, temporalCoverage);
                var light = MetalLightingTransform.adapt(program, f);
                require(accepted(light.status()));
                f = light.text();
            }
            var response = MetalReflectionResponse.adapt(program, f);
            require(accepted(response.status()));
            f = response.text();
            var filter = MetalReflectionFilter.adapt(program, f);
            require(accepted(filter.status()));
            f = filter.text();
            if (!f.contains(MARKER)) f += "\n" + MARKER + "\n";
            return new Program(v, f, true, "TRANSFORMED");
        } catch (UnsupportedSource exception) {
            return new Program(vertex, fragment, false, "UNSUPPORTED_SOURCE");
        }
    }

    private static String surface(String source, boolean water, boolean temporalCoverage, boolean recess) {
        // Resolve coverage before the finish adapter sees the mixed specular sample.
        // At a partly covered pixel it still computes the user's underlying stone finish.
        String s = after(SPECULAR, source, """

                    ivec4 erydonBounds = erydonMetalBounds;
                    ivec4 erydonInfo = erydonMetalInfo;
                    vec3 erydonAuthoredMetalColor = erydonMetalAlbedoMean;
                    vec2 erydonDx = dFdx(texCoord) * vec2(erydonMetalAtlas);
                    vec2 erydonDy = dFdy(texCoord) * vec2(erydonMetalAtlas);
                    float erydonFootprint = max(length(erydonDx), length(erydonDy));
                    float erydonAuthoredRoughness = 1.0 - specularMap.r;
                    bool erydonHasAuthoredMetal = specularMap.g >= 229.5 / 255.0;
                    if (erydonInfo.x > 0) {
                        // A CTM-aware POM ray may finish on another sprite in the atlas.
                        ivec2 erydonPixel = ivec2(floor(texCoordM * vec2(erydonMetalAtlas)));
                        if (any(lessThan(erydonPixel, erydonBounds.xy))
                                || any(greaterThanEqual(erydonPixel, erydonBounds.xy + erydonBounds.zw))) {
                            erydonReadMetal(texCoordM, erydonMetalAtlas, erydonBounds, erydonInfo, erydonAuthoredMetalColor);
                        }
                        if (erydonInfo.x > 0) {
                            // Overlay visibility already represents coverage: admitted samples are solid metal.
                            // Do not filter the same mask a second time for those admitted samples.
                            erydonMetalCoverage = erydonInfo.x == 1 ? 1.0
                                    : erydonMetalMask(texCoordM, erydonBounds, erydonInfo, erydonDx, erydonDy);
                            // Lighting, smoothness mixing and deferred reconstruction
                            // must use the same coverage represented in the material buffer.
                            erydonMetalCoverage = floor(clamp(erydonMetalCoverage, 0.0, 1.0) * 63.0 + 0.5) / 63.0;
                            if (erydonMetalCoverage >= 1.0 / 126.0) {
                                specularMap.g = erydonMetalCoverage > 0.999 ? 1.0 : min(specularMap.g, 10.0 / 255.0);
                            }
                        }
                    }
                    """);
        int body = s.indexOf('{', s.indexOf("void GetCustomMaterials("));
        require(body >= 0);
        int end = matchingBrace(s, body);
        String apply = """

                    if (erydonMetalCoverage >= 1.0 / 126.0 && erydonInfo.x > 0) {
                        erydonMetalF0 = erydonConductorF0(erydonInfo.y, color.rgb);
                        float erydonRoughness = erydonMetalCoverage > 0.95 && erydonHasAuthoredMetal ? erydonAuthoredRoughness : float(erydonInfo.z) / 255.0;
                        // Preserve authored polish: a .22 floor turns CU's reflection
                        // sampling into a rough, heavily blurred surface. Keep only a
                        // minimum of 2/255; direct sun highlights have their own finite width.
                        erydonMetalRoughness = clamp(floor(erydonRoughness * 255.0 + 0.5), 2.0, 229.0) / 255.0;
                        float erydonMetalSmoothness = (1.0 - erydonMetalRoughness) * (1.0 - erydonMetalRoughness);
                        smoothnessG = mix(smoothnessG, erydonMetalSmoothness, erydonMetalCoverage);
                        smoothnessD = mix(smoothnessD, erydonMetalSmoothness, erydonMetalCoverage);
                        // The existing lit base colour supplies CU's indirect illumination proxy.
                        color.rgb = erydonMetalAlbedo(color.rgb, erydonAuthoredMetalColor, erydonMetalF0,
                                                      erydonMetalCoverage, glColor.rgb);
                        // Stochastic overlay samples represent a fully covered conductor.
                        // Transparent mip padding must not darken its admitted samples.
                        if (erydonInfo.x == 1 && erydonFootprint >= 0.75) {
                            color.rgb = pow(erydonMetalF0, vec3(1.0 / 2.2)) * glColor.rgb;
                        }
                        // Keep an estimated metal-only contribution for the lighting stage.
                        // Fully covered pixels retain all authored wear; mixed pixels leave
                        // the stone contribution in the original sampled colour.
                        erydonMetalDiffuseComponent = erydonMetalCoverage > 0.999 ? color.rgb
                                : min(max(color.rgb, vec3(0.0)), erydonMetalCoverage
                                        * pow(erydonMetalF0, vec3(1.0 / 2.2)) * glColor.rgb);
                        if (%s) {
                            if (erydonInfo.x == 1) {
                                vec2 erydonBevel = erydonMetalBevel(texCoordM, erydonBounds, erydonInfo, erydonFootprint);
                                vec3 erydonTangentNormal = erydonMetalNormal(tbnMatrix * normalM, erydonBevel, erydonInfo.x);
                                normalM = normalize(mix(normalM, normalize(erydonTangentNormal * tbnMatrix), erydonMetalCoverage));
                                NdotU = dot(normalM, upVec);
                                NdotUmax0 = max0(NdotU);
                            }
                        }
                        // A selected mirror finish can be ineligible for an authored
                        // rough substrate. Preserve only the mirror actually applied.
                        int erydonFinish = int(materialMask * 255.1) == 242 ? 3
                                : (mat == 12040 || mat == 12041 || mat == 12043 || mat == 12045 || mat == 12053) ? 2
                                : (mat == 12032 || mat == 12033 || mat == 12035 || mat == 12037 || mat == 12051) ? 1 : 0;
                        erydonMetalPacked = ((floor(erydonMetalCoverage * 63.0 + 0.5) * 4.0 + float(erydonFinish)) * 256.0
                                + floor(erydonMetalRoughness * 255.0 + 0.5)) / 65535.0;
                        erydonMetalTag = (erydonInfo.y == 1 ? 243.0 : 244.0) / 255.0;
                        %s
                    } else erydonMetalCoverage = 0.0;
                """.formatted(Pattern.compile("normalM\\s*=\\s*normalMap\\.xyz").matcher(source).find() ? "true" : "false",
                                water ? "// Keep materialMaskPh exclusively for the approved two-way coating."
                                     : "materialMask = erydonMetalTag;");
        s = s.substring(0, end) + apply + s.substring(end);
        if (water) {
            require(unique(WATER_REFLECT, s));
            s = after(WATER_REFLECT, s, "\nif (erydonMetalCoverage > 0.0) materialMask = erydonMetalTag;\n");
            if (WATER_BUFFER.matcher(s).find()) {
                require(unique(WATER_BUFFER, s));
                s = replace(WATER_BUFFER, s, "vec4(erydonMetalCoverage > 0.0 ? smoothnessG : 1.0, materialMask, skyLightFactor, erydonMetalPacked)");
            }
        } else {
            require(unique(TERRAIN_BUFFER, s));
            s = replace(TERRAIN_BUFFER, s, "gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, erydonMetalPacked);");
            // CU can compile out its alpha test with POM_ALLOW_CUTOUT. Insert our scoped
            // coverage test unconditionally after smoothness initialization, before lighting.
            Pattern beforeMaterial = Pattern.compile("float\\s+smoothnessD\\s*=\\s*0\\.0,\\s*materialMask\\s*=\\s*0\\.0\\s*;");
            require(unique(beforeMaterial, s));
            String temporal = temporalCoverage
                    ? "fract(Bayer64(gl_FragCoord.xy) + 0.61803398875 * mod(float(frameCounter), 3600.0))"
                    : "Bayer64(gl_FragCoord.xy)";
            s = after(beforeMaterial, s, "\nfloat erydonOverlayAlpha = erydonMetalCutout(texCoord, " + temporal + ");\n"
                    + "if (erydonOverlayAlpha >= 0.0) { if (erydonOverlayAlpha < 0.5) discard; color.a = 1.0; }\n");
            // POM re-samples albedo; keep the admitted, flush overlay opaque afterward.
            Pattern call = Pattern.compile("GetCustomMaterials\\(color,\\s*normalM,[^;]+;");
            require(unique(call, s));
            s = after(call, s, "\nif (erydonMetalInfo.x == 1) color.a = 1.0;\n");
        }
        if (recess) {
            // Replace only these authored insertions, not native CTM/POM code.
            s = s.replace("if (any(lessThan(erydonPixel, erydonBounds.xy))", "if (!erydonInlayActive && (any(lessThan(erydonPixel, erydonBounds.xy))")
                    .replace("greaterThanEqual(erydonPixel, erydonBounds.xy + erydonBounds.zw))) {", "greaterThanEqual(erydonPixel, erydonBounds.xy + erydonBounds.zw)))) {");
            s = s.replace("erydonMetalCoverage = erydonInfo.x == 1 ? 1.0", "erydonMetalCoverage = erydonInlayActive ? erydonInlayMetalCoverage : erydonInfo.x == 1 ? 1.0");
            s = s.replace("if (erydonInfo.x == 1 && erydonFootprint >= 0.75)", "if (!erydonInlayActive && erydonInfo.x == 1 && erydonFootprint >= 0.75)");
            s = s.replace("if (erydonInfo.x == 1)", "if (erydonInfo.x == 1 && !erydonInlayActive)");
        }
        return s;
    }

    private static String recess(String source, boolean temporalCoverage) {
        Matcher depth = POM_DEPTH.matcher(source), distance = POM_DISTANCE.matcher(source);
        require(depth.find() && distance.find());
        String constants = "const float ERYDON_INLAY_POM_DEPTH = " + depth.group(1) + ";\n"
                + "const float ERYDON_INLAY_POM_DISTANCE = " + distance.group(1) + ";\n";
        String result = CUSTOM_MATERIALS.matcher(source).replaceFirst(Matcher.quoteReplacement(constants + RECESS + "\n") + "$0");
        String dither = temporalCoverage
                ? "fract(Bayer64(gl_FragCoord.xy) + 0.61803398875 * mod(float(frameCounter), 3600.0))"
                : "Bayer64(gl_FragCoord.xy)";
        result = after(MATERIAL_UV, result, "\nif (erydonInlayActive) {\n"
                + "    erydonInlayTrace(texCoordM, color, lViewPos);\n"
                + "    shadowMult *= erydonInlayShadow(tbnMatrix * lightVec, " + dither + ");\n}\n");
        result = after(SKIP_POM, result, "\nif (erydonInlayActive) skipPom = true;\n");
        result = replace(SKIPPED_NORMAL, result,
                "else normalMap = erydonInlayActive ? erydonInlayNormalSample : texture2D(normals, texCoordM);");
        // The native NdotU update and directional block lighting must both
        // see the cavity wall normal rather than the substrate's floor normal.
        result = after(MATERIAL_NORMAL, result, """

                    if (erydonInlayActive && dot(erydonInlayWallNormal, erydonInlayWallNormal) > 0.5) {
                        normalM = normalize(erydonInlayWallNormal * tbnMatrix);
                    }
                    """);
        result = after(SPECULAR, result, """

                    if (erydonInlayActive) {
                        specularMap = erydonInlaySpecularSample;
                    }
                    """);
        Pattern cutout = Pattern.compile("float\\s+erydonOverlayAlpha\\s*=");
        require(unique(cutout, result));
        result = cutout.matcher(result).replaceFirst(Matcher.quoteReplacement("""
                erydonInlayActive = erydonInlaySubstrateRecord >= 0 && erydonMetalInfo.x == 1
                        && all(greaterThan(erydonInlayBaseBounds.zw, vec2(0.0)));
                """) + "$0");
        result = result.replace("float erydonOverlayAlpha = erydonMetalCutout(",
                "float erydonOverlayAlpha = erydonInlayActive ? 1.0 : erydonMetalCutout(");
        return result;
    }

    private static int matchingBrace(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            if (text.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new UnsupportedSource();
    }

    private static String injectHelpers(String source, String helpers) {
        Matcher first = FIRST_FUNCTION.matcher(source);
        require(first.find());
        return source.substring(0, first.start()) + helpers + "\n" + source.substring(first.start());
    }

    private static boolean accepted(String status) {
        return status.equals("TRANSFORMED") || status.equals("TRANSFORMED_LOW_SAMPLER")
                || status.equals("ALREADY_TRANSFORMED") || status.equals("OTHER_PROGRAM")
                || status.equals("HIGHLIGHT_NOT_COMPILED") || status.equals("REFLECTIONS_NOT_COMPILED");
    }

    private static String after(Pattern pattern, String source, String text) {
        return pattern.matcher(source).replaceFirst("$0" + Matcher.quoteReplacement(text));
    }

    private static String replace(Pattern pattern, String source, String text) {
        return pattern.matcher(source).replaceFirst(Matcher.quoteReplacement(text));
    }

    private static boolean unique(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() && !matcher.find();
    }

    private static void require(boolean condition) { if (!condition) throw new UnsupportedSource(); }
    private static final class UnsupportedSource extends RuntimeException { }

    private static String resource(String file) {
        try (var stream = MetallicShaderAdapter.class.getResourceAsStream("/assets/erydon/shaders/include/" + file)) {
            if (stream == null) throw new IllegalStateException("Missing metal shader helper " + file);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("Cannot read metal shader helper", exception); }
    }

    private MetallicShaderAdapter() { }
}
