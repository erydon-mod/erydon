package com.oliver.erydon.client.pom;

import java.util.regex.Pattern;
import java.util.List;
import com.oliver.erydon.HighPolishSettings.Level;
import java.util.function.IntPredicate;

/** Stone finish and glass controls; no extra draw or texture sample. */
public final class HighPolishShaderAdapter {
    public enum Profile { COMPLEMENTARY, BLISS, UNSUPPORTED }
    private static final String BLISS_PROPERTIES_SHA256 =
            "6c1b92c65d27eaf55f0723e5bb85e9c213aa6603d9eb558a3803caa7d3621e2f";
    // Even/odd retain CU's solid/non-solid voxel-light convention. Keep these
    // below 30000 (glass) and within Iris's signed-short material attribute.
    public static final int SOLID_ID = 12024;
    public static final int SHAPE_ID = 12025;
    public static final int SPIRAL_ID = 12027;
    public static final int MIRROR_POLISHED_FRAME_ID = 12029;
    public static final int MIRROR_NORMAL_FRAME_ID = 12031;
    public static final int HONED_SOLID_ID = 12032;
    public static final int HONED_SHAPE_ID = 12033;
    public static final int HONED_SPIRAL_ID = 12035;
    public static final int HONED_FRAME_ID = 12037;
    public static final int POLISHED_SOLID_ID = 12040;
    public static final int POLISHED_SHAPE_ID = 12041;
    public static final int POLISHED_SPIRAL_ID = 12043;
    public static final int POLISHED_FRAME_ID = 12045;
    // Keep columns odd for voxel lighting; only the reflection voxelizer accepts them.
    public static final int MIRROR_COLUMN_ID = 12049;
    public static final int HONED_COLUMN_ID = 12051;
    public static final int POLISHED_COLUMN_ID = 12053;
    public static final int NORMAL_COLUMN_ID = 12055;
    public static final int GLAZING_ID = 12057;
    public static final float GLAZING_REFLECTION_FLOOR = 0.5F;
    public static final List<Integer> RESERVED_IDS = List.of(SOLID_ID, SHAPE_ID, SPIRAL_ID,
            MIRROR_POLISHED_FRAME_ID, MIRROR_NORMAL_FRAME_ID, HONED_SOLID_ID, HONED_SHAPE_ID,
            HONED_SPIRAL_ID, HONED_FRAME_ID, POLISHED_SOLID_ID, POLISHED_SHAPE_ID, POLISHED_SPIRAL_ID, POLISHED_FRAME_ID,
            MIRROR_COLUMN_ID, HONED_COLUMN_ID, POLISHED_COLUMN_ID, NORMAL_COLUMN_ID, GLAZING_ID);
    private static final String HONED = "(mat == 12032 || mat == 12033 || mat == 12035 || mat == 12037 || mat == 12051)";
    private static final String POLISHED = "(mat == 12040 || mat == 12041 || mat == 12043 || mat == 12045 || mat == 12053)";
    private static final String MIRROR = "(mat == 12024 || mat == 12025 || mat == 12027 || mat == 12029 || mat == 12049)";
    private static final String STONE = "(" + HONED + " || " + POLISHED + " || " + MIRROR + ")";
    private static final Pattern SMOOTHNESS = Pattern.compile("float\\s+smoothnessM\\s*=\\s*pow2\\(specularMap\\.r\\)\\s*;");
    private static final String MARKER = "// ERYDON opaque high polish";
    private static final String MIRROR_MARKER = "// ERYDON two-way mirror coating";
    private static final String COLUMN_MARKER = "// ERYDON circular-column reflection approximation";
    private static final Pattern REFLECTION_SOLID_CHECK = Pattern.compile("if\\s*\\(doSolidBlockCheck\\)\\s*\\{");
    private static final Pattern DIELECTRIC = Pattern.compile(
            "materialMask\\s*=\\s*specularMap\\.g\\s*\\*\\s*OSIEBCA\\s*\\*\\s*214\\.0\\s*;");
    private static final Pattern FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*fresnelM\\s*\\*\\s*sqrt1\\(smoothnessD\\)\\s*-\\s*dither\\s*\\*\\s*0\\.01\\s*;");
    private static final Pattern CUSTOM_EMISSION = Pattern.compile(
            "emission\\s*=\\s*GetCustomEmission\\(specularMap,\\s*texCoordM\\);");
    private static final Pattern TRANSLUCENT_REFLECTION = Pattern.compile("reflectMult\\s*=\\s*smoothnessD\\s*;");
    private static final Pattern TRANSLUCENT_FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*\\(fresnelM\\s*\\*\\s*0\\.85\\s*\\+\\s*0\\.15\\)\\s*\\*\\s*reflectMult\\s*;");
    private static final Pattern TRANSLUCENT_TINT = Pattern.compile(Pattern.quote(
            "translucentMult.rgb = mix(translucentMult.rgb, vec3(1.0), min1(pow2(pow2(lViewPos / far))));"));
    private static volatile Profile profile = Profile.UNSUPPORTED;
    private static volatile boolean requested, eligible, terrainReady, deferredReady, waterReady, columnsReady, failed;
    private static volatile boolean glazingRequested;

    public record Result(String text, boolean changed, String status) { }

    public static Profile profileForProperties(String contents) {
        if (ComplementaryUnboundDev5SourceTransformer.matchesSupportedProperties(contents)) return Profile.COMPLEMENTARY;
        return BLISS_PROPERTIES_SHA256.equals(ComplementaryUnboundDev5SourceTransformer.sha256(contents))
                ? Profile.BLISS : Profile.UNSUPPORTED;
    }

    public static Profile profile() { return profile; }

    public static void beginShaderLoad(boolean supported, boolean enabled) {
        beginShaderLoad(supported ? Profile.COMPLEMENTARY : Profile.UNSUPPORTED, enabled);
    }

    public static void beginShaderLoad(Profile selectedProfile, boolean enabled) {
        beginShaderLoad(selectedProfile, enabled, false);
    }

    public static void beginShaderLoad(Profile selectedProfile, boolean enabled, boolean glazing) {
        profile = selectedProfile;
        // Keep other shader packs native.
        requested = selectedProfile == Profile.COMPLEMENTARY && enabled;
        glazingRequested = requested && glazing;
        eligible = false;
        terrainReady = deferredReady = waterReady = columnsReady = failed = false;
    }

    public static boolean requested() { return requested; }

    /** Called before any ProgramSet, including Iris's eagerly loaded base dimension. */
    public static void acceptMaterialIds(IntPredicate alreadyUsed) {
        eligible = requested && RESERVED_IDS.stream().noneMatch(alreadyUsed::test);
    }

    /** Avoid boxing each entry while checking the large, existing block-state map. */
    public static boolean isReservedMaterial(int id) {
        return switch (id) {
            case SOLID_ID, SHAPE_ID, SPIRAL_ID, MIRROR_POLISHED_FRAME_ID, MIRROR_NORMAL_FRAME_ID,
                    HONED_SOLID_ID, HONED_SHAPE_ID, HONED_SPIRAL_ID, HONED_FRAME_ID,
                    POLISHED_SOLID_ID, POLISHED_SHAPE_ID, POLISHED_SPIRAL_ID, POLISHED_FRAME_ID,
                    MIRROR_COLUMN_ID, HONED_COLUMN_ID, POLISHED_COLUMN_ID, NORMAL_COLUMN_ID, GLAZING_ID -> true;
            default -> false;
        };
    }

    public static boolean ready() {
        return eligible && terrainReady && deferredReady && waterReady && !failed;
    }

    public static boolean columnsReady() { return columnsReady && ready(); }

    /** Reuse CU's one-voxel block approximation, without changing lighting or drawing extra geometry. */
    public static Result adaptColumnReflections(String source, boolean enabled) {
        if (!enabled || source == null) return new Result(source, false, "NOT_APPLICABLE");
        if (source.contains(COLUMN_MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        if (!source.contains("void UpdateSceneVoxelMap(") || !unique(REFLECTION_SOLID_CHECK, source))
            return new Result(source, false, "UNSUPPORTED_SOURCE");
        String insertion = """
                    // ERYDON circular-column reflection approximation
                    // Preserve odd material IDs everywhere else, particularly voxel lighting.
                    if (mat == 12049 || mat == 12051 || mat == 12053 || mat == 12055) {
                        doSolidBlockCheck = false;
                    }
                """;
        return new Result(REFLECTION_SOLID_CHECK.matcher(source).replaceFirst(
                java.util.regex.Matcher.quoteReplacement(insertion) + "$0"), true, "TRANSFORMED");
    }

    public static Result adaptColumnReflections(String source) {
        Result result = adaptColumnReflections(source, eligible && profile == Profile.COMPLEMENTARY);
        if (eligible && source != null) {
            columnsReady = result.changed() || "ALREADY_TRANSFORMED".equals(result.status());
        }
        return result;
    }

    public static int columnMaterialId(Level level) {
        if (level == null) return NORMAL_COLUMN_ID;
        return switch (level) {
            case HONED -> HONED_COLUMN_ID;
            case POLISHED -> POLISHED_COLUMN_ID;
            case MIRROR -> MIRROR_COLUMN_ID;
        };
    }

    public static String status() {
        return profile + " ids=" + eligible + " terrain=" + terrainReady + " deferred=" + deferredReady
                + " glass=" + waterReady
                + " failed=" + failed;
    }

    private static Result adaptMirror(String source) {
        if (source.contains(MIRROR_MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        var sample = CUSTOM_EMISSION.matcher(source);
        var reflection = TRANSLUCENT_REFLECTION.matcher(source);
        if (!sample.find() || !reflection.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        if (sample.find() || reflection.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        if (glazingRequested && (!unique(TRANSLUCENT_TINT, source) || !unique(TRANSLUCENT_FRESNEL, source)))
            return new Result(source, false, "UNSUPPORTED_SOURCE");
        String flags = """
                    // ERYDON two-way mirror coating
                    // Carry the coating flag through the existing unused inout value.
                    materialMask = ((mat == 12029 || mat == 12031 || mat == 12037 || mat == 12045)
                            && specularMap.r >= 0.99 && specularMap.g >= 229.5 / 255.0) ? 1.0 : 0.0;
                """;
        if (glazingRequested) {
            flags += """
                        // Negative flag: ordinary dielectric panes only. Opaque frames,
                        // metal coatings and exactly transparent edge pixels stay native.
                        if ((mat == 12057 || mat == 12029 || mat == 12031 || mat == 12037 || mat == 12045)
                                && specularMap.r >= 0.99 && specularMap.g < 229.1 / 255.0
                                && color.a > 0.0 && color.a < 1.0) materialMask = -1.0;
                    """;
        }
        String tagged = CUSTOM_EMISSION.matcher(source).replaceFirst("$0\n" + flags);
        String result = TRANSLUCENT_REFLECTION.matcher(tagged).replaceFirst("$0\n" + """
                    // The later CU blend is (fresnelM * 0.85 + 0.15) * reflectMult.
                    // Give only the outward metallic coating a 90% reflection floor.
                    if (materialMaskPh > 0.5) fresnelM = max(fresnelM, (0.9 - 0.15) / 0.85);
                """);
        if (glazingRequested) {
            String response = """
                        // ERYDON glazing: Fresnel reflection is independent of pane alpha.
                        float erydonGlazingReflection = 0.0;
                        if (materialMaskPh < -0.5) {
                            // CU stores sqrt(R) in RGBA8_SNORM; stay below its exact-one sentinel after quantization.
                            erydonGlazingReflection = min(0.99, (%s + (1.0 - %s) * pow(fresnel, 5.0)) * reflectMult);
                        }
                    """.formatted(Float.toString(GLAZING_REFLECTION_FLOOR),
                            Float.toString(GLAZING_REFLECTION_FLOOR));
            result = TRANSLUCENT_REFLECTION.matcher(result).replaceFirst("$0\n" + response);
            // These anchors prove custom PBR survived Iris preprocessing. Do not
            // insert preprocessor directives: its AST transformation runs next.
            // Keep CU's authored pane tint for volumetric light; only the final
            // surface compositing alpha includes reflection coverage.
            result = TRANSLUCENT_TINT.matcher(result).replaceFirst("$0\n" + """
                        if (materialMaskPh < -0.5) {
                            color.a = color.a * (1.0 - erydonGlazingReflection) + erydonGlazingReflection;
                        }
                    """);
            result = TRANSLUCENT_FRESNEL.matcher(result).replaceFirst("$0\n" + """
                        if (materialMaskPh < -0.5) fresnelM = erydonGlazingReflection / color.a;
                    """);
        }
        return new Result(result, true, "TRANSFORMED");
    }

    private static boolean unique(Pattern pattern, String source) {
        var match = pattern.matcher(source);
        return match.find() && !match.find();
    }

    private static Result adaptTerrain(String source) {
        // Preflight all anchors before changing anything. Metals and grout retain
        // their own masks; no specular image is swapped or sampled a second time.
        if (!unique(SMOOTHNESS, source) || !unique(DIELECTRIC, source))
            return new Result(source, false, "UNSUPPORTED_SOURCE");
        String finish = """
                    // ERYDON opaque high polish
                    if (%s && specularMap.r >= 0.68 && specularMap.g < 229.1 / 255.0) {
                        specularMap.r = %s ? 175.0 / 255.0 : 1.0;
                        specularMap.g = max(specularMap.g, 10.0 / 255.0);
                    }
                """.formatted(STONE, HONED);
        String result = SMOOTHNESS.matcher(source).replaceFirst(java.util.regex.Matcher.quoteReplacement(finish) + "$0");
        result = DIELECTRIC.matcher(result).replaceFirst("$0\n" + """
                    if (%s && specularMap.r >= 0.99) materialMask = OSIEBCA * 242.0;
                """.formatted(MIRROR));
        return new Result(result, true, "TRANSFORMED");
    }

    private static boolean opaqueProgram(String program) {
        return "gbuffers_terrain".equals(program) || "deferred1".equals(program);
    }

    public static Result adaptFragment(String program, String source, boolean enabled) {
        if (!enabled || source == null) return new Result(source, false, "DISABLED");
        if ("gbuffers_water".equals(program)) return adaptMirror(source);
        if (!opaqueProgram(program)) {
            return new Result(source, false, "OTHER_PROGRAM");
        }
        if (source.contains(MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        if ("gbuffers_terrain".equals(program)) return adaptTerrain(source);
        var matcher = FRESNEL.matcher(source);
        if (!matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        int end = matcher.end();
        if (matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        String insertion = """

                    // ERYDON opaque high polish
                    // Mirror stone: 50% floor. Honed/Polished use CU's ordinary response.
                    if (materialMaskInt == 242) {
                        fresnelM = (pow3(fresnel) * 0.5 + 0.5) * smoothnessD;
                    }
                """;
        return new Result(source.substring(0, end) + insertion + source.substring(end), true, "TRANSFORMED");
    }

    public static Result adaptFragment(String program, String source) {
        Result result = adaptFragment(program, source, eligible);
        if (eligible && source != null && (opaqueProgram(program) || "gbuffers_water".equals(program))) {
            boolean ok = result.changed() || "ALREADY_TRANSFORMED".equals(result.status());
            if (!ok) failed = true;
            if ("gbuffers_terrain".equals(program)) terrainReady = ok;
            else if ("deferred1".equals(program)) deferredReady = ok;
            else waterReady = ok;
        }
        return result;
    }

    /** Preserve the existing exact-bounds spiral bridge under its polish ID. */
    public static String adaptSpiralPredicate(String source) {
        return eligible && profile == Profile.COMPLEMENTARY && source != null && !source.contains("mat == 12035")
                ? source.replace("mat == 32120", "(mat == 32120 || mat == 12027 || mat == 12035 || mat == 12043)") : source;
    }

    public static int materialId(Level level, boolean fullCube, boolean spiral) {
        int base = switch (level) {
            case HONED -> HONED_SOLID_ID;
            case POLISHED -> POLISHED_SOLID_ID;
            case MIRROR -> SOLID_ID;
        };
        return base + (spiral ? 3 : fullCube ? 0 : 1);
    }

    public static int mirrorFrameId(Level level) {
        return switch (level) {
            case HONED -> HONED_FRAME_ID;
            case POLISHED -> POLISHED_FRAME_ID;
            case MIRROR -> MIRROR_POLISHED_FRAME_ID;
        };
    }

    private HighPolishShaderAdapter() { }
}
