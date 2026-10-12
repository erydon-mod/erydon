package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only release matrix: shader code stays in external reference ZIPs. */
@EnabledIf("configured")
class ComplementaryCompatibilityMatrixTest {
    private record Preset(String label, InstalledShaderTestSupport.Options options, Map<String, String> overrides) { }
    private static final List<Preset> PRESETS = List.of(
            new Preset("default-wsr", InstalledShaderTestSupport.Options.DEFAULT,
                    Map.of("WORLD_SPACE_REFLECTIONS", "1", "COLORED_LIGHTING", "512", "REFLECTION_RES", "1.0")),
            new Preset("pom-off-filtered-wsr", new InstalledShaderTestSupport.Options(false, 8, 200),
                    Map.of("WORLD_SPACE_REFLECTIONS", "1", "COLORED_LIGHTING", "512", "REFLECTION_RES", "1.0")),
            new Preset("installed-wsr", new InstalledShaderTestSupport.Options(true, 0, 120),
                    Map.of("WORLD_SPACE_REFLECTIONS", "1", "COLORED_LIGHTING", "256", "REFLECTION_RES", "1.0")),
            new Preset("capture-screen-space", new InstalledShaderTestSupport.Options(true, 0, 120),
                    Map.of("WORLD_SPACE_REFLECTIONS", "-1", "COLORED_LIGHTING", "256", "REFLECTION_RES", "1.0")));

    static boolean configured() { return InstalledShaderTestSupport.configured(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }

    @Test void auditedReleaseContractsCompileAndOlderContractsFailClosed() throws Exception {
        int compiled = 0;
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                String properties = HighPolishShaderPackTest.read(zip, "shaders/shaders.properties");
                boolean supported = !path.getFileName().toString().contains("r5.5.1");
                assertEquals(supported, ComplementaryShaderProfiles.supported(properties), path.toString());
                assertFalse(ComplementaryShaderProfiles.supported(properties + "\n# unaudited edit"));
                assertEquals(supported, ComplementaryUnboundDev5SourceTransformer.adaptProperties(properties,
                        ComplementaryUnboundDev5SourceTransformer.Mode.AUTO).eligible());
                System.out.println(path.getFileName() + ": " + ComplementaryShaderProfiles.diagnostic(properties));
                String ids = HighPolishShaderPackTest.read(zip, "shaders/block.properties");
                for (int id : HighPolishShaderAdapter.RESERVED_IDS) {
                    assertFalse(Pattern.compile("(?m)^\\s*(?:block|tag)\\." + id + "\\s*=").matcher(ids).find());
                }
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (var preset : PRESETS) {
                        var options = preset.options();
                        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.COMPLEMENTARY, true, true);
                        var vertices = new HashMap<String, String>();
                        var fragments = new HashMap<String, String>();
                        for (String program : MetallicShaderAdapter.PROGRAMS) {
                            vertices.put(program, source(zip, dimension, program + ".vsh", preset));
                            fragments.put(program, source(zip, dimension, program + ".fsh", preset));
                        }
                        var preflight = HighPolishShaderAdapter.preflight(fragments::get);
                        assertEquals(supported, preflight.supported(), path + "/" + dimension + ": " + preflight.status());
                        if (!supported) {
                            assertTrue(ComplementaryShaderProfiles.diagnostic(properties).contains("r5.5.1"));
                            HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.profileForProperties(properties), true, true);
                            HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                            for (var entry : fragments.entrySet()) {
                                assertSame(entry.getValue(), HighPolishShaderAdapter.adaptProgramFragment(
                                        entry.getKey(), entry.getValue(), false).text());
                            }
                            continue;
                        }
                        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                        boolean metalSupported = true;
                        for (String program : MetallicShaderAdapter.PROGRAMS) {
                            var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram(program,
                                    vertices.get(program), fragments.get(program), ErydonCuPomShaderBridge.vertexSource(),
                                    ErydonCuPomShaderBridge.fragmentSource(), true);
                            var finish = HighPolishShaderAdapter.adaptFragment(program, pom.fragmentText(), true);
                            var metal = MetallicShaderAdapter.adapt(program, pom.vertexText(), finish.text(), true);
                            metalSupported &= metal.changed();
                        }
                        System.out.println(path.getFileName() + "/" + dimension + "/" + options
                                + "/" + preset.label() + " metal preflight=" + metalSupported);
                        for (String program : MetallicShaderAdapter.PROGRAMS) {
                            String vertex = vertices.containsKey(program) ? vertices.get(program)
                                    : source(zip, dimension, program + ".vsh", preset);
                            String fragment = fragments.containsKey(program) ? fragments.get(program)
                                    : source(zip, dimension, program + ".fsh", preset);
                            if (vertex == null || fragment == null) continue; // Iris leaves absent optional programs native.
                            String label = path.getFileName() + "/" + dimension + "/" + preset.label() + "/" + options + "/" + program;
                            var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram(program, vertex, fragment,
                                    ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), true);
                            if ("gbuffers_terrain".equals(program)) {
                                assertEquals(options.pom() ? "TRANSFORMED" : "POM_NOT_COMPILED", pom.status(), label);
                            }
                            var polish = HighPolishShaderAdapter.adaptProgramFragment(program, pom.fragmentText(), true);
                            assertEquals(HighPolishShaderAdapter.REQUIRED_PROGRAMS.contains(program), polish.changed(), label);
                            if ("gbuffers_water".equals(program)) {
                                assertTrue(polish.text().contains("erydonGlazingReflection / color.a"), label);
                                assertTrue(polish.text().contains("(0.9 - 0.15) / 0.85"), label);
                                assertEquals(samples(fragment), samples(polish.text()), label + ": no extra glass samples");
                            }
                            var metal = MetallicShaderAdapter.adapt(program, pom.vertexText(), polish.text(), metalSupported);
                            assertEquals(metalSupported, metal.changed(), label + ": " + metal.status());
                            if (!metalSupported) {
                                assertSame(pom.vertexText(), metal.vertex(), label + ": atomic native metal fallback");
                                assertSame(polish.text(), metal.fragment(), label + ": atomic native metal fallback");
                            }
                            String finalVertex = metal.vertex(), finalFragment = metal.fragment();
                            if ("gbuffers_terrain".equals(program)) {
                                finalVertex = HighPolishShaderAdapter.adaptSpiralPredicate(finalVertex);
                                finalFragment = HighPolishShaderAdapter.adaptSpiralPredicate(finalFragment);
                            }
                            if ("shadow".equals(program) && finalVertex.contains("void UpdateSceneVoxelMap(")) {
                                var columns = HighPolishShaderAdapter.adaptColumnReflections(finalVertex, true);
                                assertTrue(columns.changed(), label + ": " + columns.status());
                                finalVertex = columns.text();
                            }
                            HighPolishShaderPackTest.parse(finalVertex);
                            HighPolishShaderPackTest.parse(finalFragment);
                            InstalledShaderTestSupport.validateProgram(label, finalVertex, finalFragment);
                            compiled++;
                        }
                        assertTrue(HighPolishShaderAdapter.ready());
                    }
                }
            } finally { HighPolishShaderAdapter.beginShaderLoad(false, false); }
        }
        System.out.println("Complementary compatibility matrix: " + compiled + " combined programs parsed"
                + (InstalledShaderTestSupport.driverValidationEnabled() ? " and driver compiled/linked" : " (driver validation disabled)"));
    }

    private static String source(ZipFile zip, String dimension, String file, Preset preset) throws Exception {
        if (zip.getEntry("shaders/" + dimension + "/" + file) == null) return null;
        var stages = net.irisshaders.iris.gl.shader.StandardMacros.getRenderStages().entrySet().stream()
                .map(entry -> new net.irisshaders.iris.helpers.StringPair(entry.getKey(), entry.getValue())).toList();
        return InstalledShaderTestSupport.source(zip, dimension, file, preset.options(), stages, preset.overrides());
    }

    private static long samples(String source) {
        return Pattern.compile("\\b(?:texture\\w*|texelFetch\\w*)\\s*\\(").matcher(source).results().count();
    }
}
