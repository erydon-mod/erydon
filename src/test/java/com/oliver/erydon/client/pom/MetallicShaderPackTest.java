package com.oliver.erydon.client.pom;

import net.irisshaders.iris.helpers.StringPair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the complete shader composition against optional installed, unmodified archives. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class MetallicShaderPackTest {
    static boolean configured() { return InstalledShaderTestSupport.configured(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }
    @AfterEach void resetFinishState() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void allMaterialAndReflectionStagesCompileAndLinkInEverySupportedDimension() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                assertTrue(ComplementaryUnboundDev5SourceTransformer.matchesSupportedProperties(
                        HighPolishShaderPackTest.read(zip, "shaders/shaders.properties")), path.toString());
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (String program : MetallicShaderAdapter.PROGRAMS) {
                        String label = path.getFileName() + "/" + dimension + "/" + program;
                        var result = transformed(zip, dimension, program, InstalledShaderTestSupport.Options.DEFAULT, label);
                        HighPolishShaderPackTest.parse(result.vertex());
                        HighPolishShaderPackTest.parse(result.fragment());
                        InstalledShaderTestSupport.validateProgram(label, result.vertex(), result.fragment());
                    }
                }
            }
        }
    }

    @Test void opaqueAndTranslucentMetalCompileWithIndependentPomFilteringAndNormalOptions() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (boolean pom : new boolean[]{false, true}) {
                        for (int filtering : new int[]{0, 8}) {
                            for (int strength : new int[]{0, 120, 200}) {
                                var options = new InstalledShaderTestSupport.Options(pom, filtering, strength);
                                for (String program : List.of("gbuffers_terrain", "gbuffers_water")) {
                                    String label = path.getFileName() + "/" + dimension + "/" + program + "/" + options;
                                    var result = transformed(zip, dimension, program, options, label);
                                    HighPolishShaderPackTest.parse(result.vertex());
                                    HighPolishShaderPackTest.parse(result.fragment());
                                    InstalledShaderTestSupport.validateProgram(label, result.vertex(), result.fragment());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void removedOrDuplicatedRequiredAnchorsRejectTheWholeProgramWithoutPartialEdits() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String program : List.of("gbuffers_terrain", "gbuffers_water")) {
                    String vertex = source(zip, "world0", program + ".vsh", InstalledShaderTestSupport.Options.DEFAULT);
                    String fragment = source(zip, "world0", program + ".fsh", InstalledShaderTestSupport.Options.DEFAULT);
                    fragment = HighPolishShaderAdapter.adaptFragment(program, fragment, true).text();
                    for (String invalid : List.of(fragment.replace("vec4 specularMap =", "vec4 unknownSpecularMap ="),
                            fragment + "\nvec4 specularMap = texture2D(specular, texCoordM);\n")) {
                        var result = MetallicShaderAdapter.adapt(program, vertex, invalid, true);
                        assertFalse(result.changed(), path.getFileName() + "/" + program);
                        assertEquals("UNSUPPORTED_SOURCE", result.status());
                        assertSame(vertex, result.vertex());
                        assertSame(invalid, result.fragment());
                    }
                }
            }
        }
    }

    @Test void actualLowSamplerProfileKeepsItsNativeSamplerBudget() throws Exception {
        var defines = List.of(new StringPair("MC_OS_MAC", "1"), new StringPair("DISTANT_HORIZONS", "1"));
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    String vertex = InstalledShaderTestSupport.source(zip, dimension, "composite1.vsh",
                            InstalledShaderTestSupport.Options.DEFAULT, defines);
                    String fragment = InstalledShaderTestSupport.source(zip, dimension, "composite1.fsh",
                            InstalledShaderTestSupport.Options.DEFAULT, defines);
                    assertFalse(fragment.contains("float smoothnessD = texture6.r;"));
                    var result = MetallicShaderAdapter.adapt("composite1", vertex, fragment, true);
                    assertTrue(result.changed(), path.getFileName() + "/" + dimension + ": " + result.status());
                    assertEquals(calls(fragment, "texelFetch"), calls(result.fragment(), "texelFetch"));
                    HighPolishShaderPackTest.parse(result.fragment());
                    InstalledShaderTestSupport.validateProgram(path.getFileName() + "/" + dimension + "/low-sampler-composite1",
                            result.vertex(), result.fragment());
                }
            }
        }
    }

    @Test void metalSurfaceDoesNotRequireStoneOrGlassEnhancements() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (String program : List.of("gbuffers_terrain", "gbuffers_water", "deferred1")) {
                        String label = path.getFileName() + "/" + dimension + "/" + program + "/finish-controls-disabled";
                        var result = transformed(zip, dimension, program, InstalledShaderTestSupport.Options.DEFAULT, label, false);
                        HighPolishShaderPackTest.parse(result.fragment());
                        InstalledShaderTestSupport.validateProgram(label, result.vertex(), result.fragment());
                    }
                }
            }
        }
    }

    @Test void temporalAntialiasingDisabledKeepsOverlayCoverageStable() throws Exception {
        var options = new InstalledShaderTestSupport.Options(true, 8, 120, false);
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    String label = path.getFileName() + "/" + dimension + "/gbuffers_terrain/TAA-off";
                    var result = transformed(zip, dimension, "gbuffers_terrain", options, label);
                    assertFalse(result.vertex().contains("TAAJitter(gl_Position.xy"), label);
                    assertTrue(result.fragment().contains("erydonMetalCutout(texCoord, Bayer64(gl_FragCoord.xy))"), label);
                    assertFalse(result.fragment().contains("0.61803398875 * mod(float(frameCounter)"), label);
                    HighPolishShaderPackTest.parse(result.fragment());
                    InstalledShaderTestSupport.validateProgram(label, result.vertex(), result.fragment());
                }
            }
        }
    }

    @Test void worldSpaceReflectionsCompileCompleteProgramsWithDetailedMaterialProfile() throws Exception {
        // One complete profile complements the option matrix without multiplying it by every WSR setting.
        Path path = InstalledShaderTestSupport.shaderPaths().get(0);
        var options = new InstalledShaderTestSupport.Options(true, 8, 200);
        var overrides = Map.of("COLORED_LIGHTING", "512", "WORLD_SPACE_REFLECTIONS", "1",
                "WORLD_SPACE_PLAYER_REF", "1", "DETAIL_QUALITY", "3", "REFLECTION_RES", "1.0",
                "POM_DEPTH", "2.00", "POM_DISTANCE", "1024", "POM_QUALITY", "512");
        // WSR shadow voxelization references Iris's real phase constants, normally supplied at pack load.
        var phases = net.irisshaders.iris.gl.shader.StandardMacros.getRenderStages().entrySet().stream()
                .map(entry -> new StringPair(entry.getKey(), entry.getValue())).toList();
        try (var zip = new ZipFile(path.toFile())) {
            for (String program : MetallicShaderAdapter.PROGRAMS) {
                String label = path.getFileName() + "/world0/" + program + "/WSR-detailed-materials";
                var result = transformed(zip, "world0", program, options, label, true, overrides, phases);
                switch (program) {
                    case "gbuffers_terrain" -> assertTrue(result.fragment().contains("voxelDistance = 64.0"), label);
                    case "deferred1" -> assertTrue(result.fragment().contains("float erydonBaseReflection = fresnel * 0.7 + 0.3;"), label);
                    case "composite1" -> assertTrue(result.fragment().contains("int erydonTranslucentMask ="),
                            label + ": exercise the actual WSR replacement of translucent SSR");
                    case "shadow" -> assertTrue(result.vertex().contains("UpdatePlayerVertexList(position.xyz);"), label);
                    case "final" -> assertTrue(result.fragment().contains("clearSSBOs();"), label);
                    default -> { }
                }
                HighPolishShaderPackTest.parse(result.vertex());
                HighPolishShaderPackTest.parse(result.fragment());
                InstalledShaderTestSupport.validateProgram(label, result.vertex(), result.fragment());
            }
        }
    }

    private static MetallicShaderAdapter.Program transformed(ZipFile zip, String dimension, String program,
                                                              InstalledShaderTestSupport.Options options,
                                                              String label) throws Exception {
        return transformed(zip, dimension, program, options, label, true);
    }

    private static MetallicShaderAdapter.Program transformed(ZipFile zip, String dimension, String program,
                                                              InstalledShaderTestSupport.Options options,
                                                              String label, boolean finishEnabled) throws Exception {
        return transformed(zip, dimension, program, options, label, finishEnabled, Map.of());
    }

    private static MetallicShaderAdapter.Program transformed(ZipFile zip, String dimension, String program,
                                                              InstalledShaderTestSupport.Options options,
                                                              String label, boolean finishEnabled,
                                                              Map<String, String> overrides) throws Exception {
        return transformed(zip, dimension, program, options, label, finishEnabled, overrides, List.of());
    }

    private static MetallicShaderAdapter.Program transformed(ZipFile zip, String dimension, String program,
                                                              InstalledShaderTestSupport.Options options,
                                                              String label, boolean finishEnabled,
                                                              Map<String, String> overrides,
                                                              List<StringPair> extraDefines) throws Exception {
        HighPolishShaderAdapter.beginShaderLoad(true, finishEnabled);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
        String vertex = InstalledShaderTestSupport.source(zip, dimension, program + ".vsh", options, extraDefines, overrides);
        String fragment = InstalledShaderTestSupport.source(zip, dimension, program + ".fsh", options, extraDefines, overrides);
        var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram(program, vertex, fragment,
                ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), true);
        vertex = pom.vertexText();
        fragment = HighPolishShaderAdapter.adaptFragment(program, pom.fragmentText(), finishEnabled).text();
        var result = MetallicShaderAdapter.adapt(program, vertex, fragment, true);
        assertTrue(result.changed(), label + ": " + result.status());
        boolean recessed = program.equals("gbuffers_terrain") && options.pom() && options.normalStrength() != 0;
        assertEquals(recessed, result.fragment().contains("// ERYDON recessed inlay substrate"),
                label + ": unsupported POM/normal paths must keep the original overlay fallback");
        if (recessed) {
            assertTrue(result.vertex().contains("erydonInlayDecodeRecord(mc_Entity.y, erydonInlayRibbon)"));
            assertTrue(result.vertex().contains("erydonInlayProjectBase(erydonWorld, at_midBlock.xyz, gl_Normal)"));
            assertTrue(result.vertex().contains("+ fract(cameraPosition)"), "World-cell UVs must avoid far-origin precision loss");
            assertTrue(result.fragment().contains("erydonInlayRibbonFrame(local)"));
            int wall = result.fragment().indexOf("normalM = normalize(erydonInlayWallNormal * tbnMatrix)");
            int lighting = result.fragment().indexOf("mat3 lightmapTBN", result.fragment().indexOf("void GetCustomMaterials("));
            assertTrue(wall >= 0, label + ": cavity wall shading normal");
            if (lighting >= 0) assertTrue(wall < lighting, label + ": wall normal precedes directional block lighting");
        }
        assertEquals(directives(vertex), directives(result.vertex()), label + ": no late vertex preprocessor directives");
        assertEquals(directives(fragment), directives(result.fragment()), label + ": no late fragment preprocessor directives");
        assertEquals(calls(fragment, "GetReflection"), calls(result.fragment(), "GetReflection"),
                label + ": reuse the existing reflection rays");
        if (finishEnabled && program.equals("deferred1")) {
            assertTrue(result.fragment().contains("(pow3(fresnel) * 0.5 + 0.5) * smoothnessD"),
                    "The approved Mirror stone reflection remains unchanged");
        }
        if (finishEnabled && program.equals("gbuffers_water")) {
            assertTrue(result.fragment().contains("if (materialMaskPh > 0.5) fresnelM = max(fresnelM, (0.9 - 0.15) / 0.85)"),
                    "The approved outward glass coating remains unchanged");
        }
        var twice = MetallicShaderAdapter.adapt(program, result.vertex(), result.fragment(), true);
        assertEquals("ALREADY_TRANSFORMED", twice.status(), label);
        assertSame(result.vertex(), twice.vertex());
        assertSame(result.fragment(), twice.fragment());
        if (program.equals("gbuffers_terrain") || program.equals("gbuffers_water")) {
            assertTrue(result.vertex().contains("uniform sampler2D erydonMetalLookup;"), label);
            assertTrue(result.fragment().contains("uniform sampler2D erydonMetalLookup;"), label);
            assertTrue(result.fragment().contains("erydonInfo.x > 0"), "Only classified sprite texels receive metal response");
            assertTrue(result.fragment().contains("else erydonMetalCoverage = 0.0"));
            assertFalse(result.fragment().contains("mat == 122"), "Metal fixtures and sculpture use sprite metadata, not finish IDs");
        }
        if (program.equals("gbuffers_terrain")) {
            return new MetallicShaderAdapter.Program(HighPolishShaderAdapter.adaptSpiralPredicate(result.vertex()),
                    HighPolishShaderAdapter.adaptSpiralPredicate(result.fragment()), result.changed(), result.status());
        }
        if (program.equals("shadow")) {
            return new MetallicShaderAdapter.Program(HighPolishShaderAdapter.adaptColumnReflections(result.vertex()).text(),
                    result.fragment(), result.changed(), result.status());
        }
        return result;
    }

    private static String source(ZipFile zip, String dimension, String file,
                                 InstalledShaderTestSupport.Options options) throws Exception {
        return InstalledShaderTestSupport.source(zip, dimension, file, options, List.of());
    }

    private static List<String> directives(String source) {
        return Pattern.compile("(?m)^[ \\t]*#.*$").matcher(source).results().map(match -> match.group().trim()).toList();
    }

    private static long calls(String source, String function) {
        return Pattern.compile("\\b" + Pattern.quote(function) + "\\s*\\(").matcher(source).results().count();
    }
}
