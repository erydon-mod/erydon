package com.oliver.erydon.client.pom;

import java.util.regex.Pattern;
import java.util.function.IntPredicate;

/** Reuses CU's opaque reflection pass; never adds a draw, sampler or surface. */
public final class HighPolishShaderAdapter {
    // Even/odd retain CU's solid/non-solid voxel-light convention. Keep these
    // below 30000 (glass) and within Iris's signed-short material attribute.
    public static final int SOLID_ID = 12024;
    public static final int SHAPE_ID = 12025;
    public static final int SPIRAL_ID = 12027;
    private static final String MARKER = "// ERYDON opaque high polish";
    private static final Pattern DIELECTRIC = Pattern.compile(
            "materialMask\\s*=\\s*specularMap\\.g\\s*\\*\\s*OSIEBCA\\s*\\*\\s*214\\.0\\s*;");
    private static final Pattern FRESNEL = Pattern.compile(
            "fresnelM\\s*=\\s*fresnelM\\s*\\*\\s*sqrt1\\(smoothnessD\\)\\s*-\\s*dither\\s*\\*\\s*0\\.01\\s*;");
    private static volatile boolean requested, eligible, terrainReady, deferredReady, failed;

    public record Result(String text, boolean changed, String status) { }

    public static void beginShaderLoad(boolean supported, boolean enabled) {
        requested = supported && enabled;
        eligible = false;
        terrainReady = deferredReady = failed = false;
    }

    /** CU dev5 loads its ID map before its dimension-specific programs. */
    public static void acceptMaterialIds(IntPredicate alreadyUsed) {
        eligible = requested && !alreadyUsed.test(SOLID_ID)
                && !alreadyUsed.test(SHAPE_ID) && !alreadyUsed.test(SPIRAL_ID);
    }

    public static boolean ready() {
        return eligible && terrainReady && deferredReady && !failed;
    }

    public static Result adaptFragment(String program, String source, boolean enabled) {
        if (!enabled || source == null) return new Result(source, false, "DISABLED");
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
                    if ((mat == 12024 || mat == 12025 || mat == 12027) && specularMap.r >= 0.68) {
                        smoothnessG = 1.0;
                        smoothnessD = 1.0;
                        materialMask = OSIEBCA * 242.0;
                    }
                """ : """

                    // ERYDON opaque high polish
                    // Same angular reflection strength as CU's transparent path,
                    // while retaining opaque depth, voxel visibility and POM.
                    if (materialMaskInt == 242) {
                        fresnelM = (pow3(fresnel) * 0.85 + 0.15) * smoothnessD;
                    }
                """;
        return new Result(source.substring(0, end) + insertion + source.substring(end), true, "TRANSFORMED");
    }

    public static Result adaptFragment(String program, String source) {
        Result result = adaptFragment(program, source, eligible);
        if (eligible && source != null && ("gbuffers_terrain".equals(program) || "deferred1".equals(program))) {
            boolean ok = result.changed() || "ALREADY_TRANSFORMED".equals(result.status());
            if (!ok) failed = true;
            if ("gbuffers_terrain".equals(program)) terrainReady = ok;
            else deferredReady = ok;
        }
        return result;
    }

    /** Preserve the existing exact-bounds spiral bridge under its polish ID. */
    public static String adaptSpiralPredicate(String source) {
        return eligible && source != null && !source.contains("mat == 12027")
                ? source.replace("mat == 32120", "(mat == 32120 || mat == 12027)") : source;
    }

    public static int materialId(boolean fullCube, boolean spiral) {
        return spiral ? SPIRAL_ID : fullCube ? SOLID_ID : SHAPE_ID;
    }

    private HighPolishShaderAdapter() { }
}
