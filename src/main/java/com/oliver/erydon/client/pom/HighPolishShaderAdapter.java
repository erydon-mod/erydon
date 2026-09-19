package com.oliver.erydon.client.pom;

import java.util.regex.Pattern;
import java.util.function.IntPredicate;

/** Reuses supported shaders' opaque reflection pass; no extra draw or texture sample. */
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
    private static final String MARKER = "// ERYDON opaque high polish";
    private static final String MIRROR_MARKER = "// ERYDON two-way mirror coating";
    private static final Pattern DIELECTRIC = Pattern.compile(
            "materialMask\\s*=\\s*specularMap\\.g\\s*\\*\\s*OSIEBCA\\s*\\*\\s*214\\.0\\s*;");
    private static final Pattern FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*fresnelM\\s*\\*\\s*sqrt1\\(smoothnessD\\)\\s*-\\s*dither\\s*\\*\\s*0\\.01\\s*;");
    private static final Pattern BLISS_SPECULAR = Pattern.compile(
            "SpecularTex\\.g\\s*=\\s*max\\(SpecularTex\\.g,\\s*max\\(Puddle_shape\\*0\\.02,0\\.02\\)\\);");
    private static final Pattern CUSTOM_EMISSION = Pattern.compile(
            "emission\\s*=\\s*GetCustomEmission\\(specularMap,\\s*texCoordM\\);");
    private static final Pattern TRANSLUCENT_REFLECTION = Pattern.compile("reflectMult\\s*=\\s*smoothnessD\\s*;");
    private static volatile Profile profile = Profile.UNSUPPORTED;
    private static volatile boolean requested, eligible, terrainReady, deferredReady, waterReady, failed;

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
        profile = selectedProfile;
        requested = selectedProfile != Profile.UNSUPPORTED && enabled;
        eligible = false;
        terrainReady = deferredReady = waterReady = failed = false;
    }

    public static boolean requested() { return requested; }

    /** Called before any ProgramSet, including Iris's eagerly loaded base dimension. */
    public static void acceptMaterialIds(IntPredicate alreadyUsed) {
        eligible = requested && !alreadyUsed.test(SOLID_ID)
                && !alreadyUsed.test(SHAPE_ID) && !alreadyUsed.test(SPIRAL_ID)
                && !alreadyUsed.test(MIRROR_POLISHED_FRAME_ID) && !alreadyUsed.test(MIRROR_NORMAL_FRAME_ID);
    }

    public static boolean ready() {
        return eligible && terrainReady && (profile == Profile.BLISS || deferredReady && waterReady) && !failed;
    }

    public static String status() {
        return profile + " ids=" + eligible + " terrain=" + terrainReady + " deferred=" + deferredReady
                + " glass=" + waterReady + " failed=" + failed;
    }

    private static Result adaptMirror(String source) {
        if (source.contains(MIRROR_MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        var sample = CUSTOM_EMISSION.matcher(source);
        var reflection = TRANSLUCENT_REFLECTION.matcher(source);
        if (!sample.find() || !reflection.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        if (sample.find() || reflection.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        String tagged = CUSTOM_EMISSION.matcher(source).replaceFirst("$0\n" + """
                    // ERYDON two-way mirror coating
                    // Carry the coating flag through the existing unused inout value.
                    materialMask = ((mat == 12029 || mat == 12031)
                            && specularMap.r >= 0.99 && specularMap.g >= 229.5 / 255.0) ? 1.0 : 0.0;
                """);
        String result = TRANSLUCENT_REFLECTION.matcher(tagged).replaceFirst("$0\n" + """
                    // The later CU blend is (fresnelM * 0.85 + 0.15) * reflectMult.
                    // Give only the outward metallic coating a 90% reflection floor.
                    if (materialMaskPh > 0.5) fresnelM = max(fresnelM, (0.9 - 0.15) / 0.85);
                """);
        return new Result(result, true, "TRANSFORMED");
    }

    private static Result adaptBliss(String program, String source, boolean enabled) {
        if (!enabled || source == null) return new Result(source, false, "DISABLED");
        if (!"gbuffers_terrain".equals(program)) return new Result(source, false, "OTHER_PROGRAM");
        if (source.contains(MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        var matcher = BLISS_SPECULAR.matcher(source);
        if (!matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        int end = matcher.end();
        if (matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        String insertion = """

                    // ERYDON opaque high polish
                    // Reuse Bliss's existing labPBR sample; preserve metals and grout.
                    if ((blockID == 12024.0 || blockID == 12025.0 || blockID == 12027.0)
                            && SpecularTex.r >= 0.68 && SpecularTex.g < 229.5 / 255.0) {
                        SpecularTex.r = 1.0;
                        SpecularTex.g = max(SpecularTex.g, 0.25);
                    }
                """;
        return new Result(source.substring(0, end) + insertion + source.substring(end), true, "TRANSFORMED");
    }

    public static Result adaptFragment(String program, String source, boolean enabled) {
        if (!enabled || source == null) return new Result(source, false, "DISABLED");
        if ("gbuffers_water".equals(program)) return adaptMirror(source);
        if (!"gbuffers_terrain".equals(program) && !"deferred1".equals(program)) {
            return new Result(source, false, "OTHER_PROGRAM");
        }
        if (source.contains(MARKER)) return new Result(source, false, "ALREADY_TRANSFORMED");
        Pattern anchor = "gbuffers_terrain".equals(program) ? DIELECTRIC : FRESNEL;
        var matcher = anchor.matcher(source);
        if (!matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        int end = matcher.end();
        if (matcher.find()) return new Result(source, false, "UNSUPPORTED_SOURCE");
        String insertion = "gbuffers_terrain".equals(program) ? """

                    // ERYDON opaque high polish
                    // Keep metal and grout authored; only polish the stone.
                    if ((mat == 12024 || mat == 12025 || mat == 12027 || mat == 12029) && specularMap.r >= 0.68) {
                        smoothnessG = 1.0;
                        smoothnessD = 1.0;
                        materialMask = OSIEBCA * 242.0;
                    }
                """ : """

                    // ERYDON opaque high polish
                    // A 25% reflection floor, increasing toward grazing angles,
                    // while retaining opaque depth, voxel visibility and POM.
                    if (materialMaskInt == 242) {
                        fresnelM = (pow3(fresnel) * 0.75 + 0.25) * smoothnessD;
                    }
                """;
        return new Result(source.substring(0, end) + insertion + source.substring(end), true, "TRANSFORMED");
    }

    public static Result adaptFragment(String program, String source) {
        Result result = profile == Profile.BLISS ? adaptBliss(program, source, eligible)
                : adaptFragment(program, source, eligible);
        if (eligible && source != null && ("gbuffers_terrain".equals(program)
                || profile == Profile.COMPLEMENTARY && ("deferred1".equals(program) || "gbuffers_water".equals(program)))) {
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
        return eligible && profile == Profile.COMPLEMENTARY && source != null && !source.contains("mat == 12027")
                ? source.replace("mat == 32120", "(mat == 32120 || mat == 12027)") : source;
    }

    public static int materialId(boolean fullCube, boolean spiral) {
        return spiral ? SPIRAL_ID : fullCube ? SOLID_ID : SHAPE_ID;
    }

    private HighPolishShaderAdapter() { }
}
