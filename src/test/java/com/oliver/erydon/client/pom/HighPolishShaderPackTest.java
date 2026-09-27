package com.oliver.erydon.client.pom;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Optional read-only audit of the user's shader; no shader assets are redistributed. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class HighPolishShaderPackTest {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\".*$");

    static boolean configured() { return InstalledShaderTestSupport.configured(); }

    @AfterAll static void closeDriverCompiler() { InstalledShaderTestSupport.close(); }

    @Test void installedPackPreprocessesAndParsesWithBothAdaptersInEveryDimension() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                String properties = read(zip, "shaders/shaders.properties");
                assertEquals(HighPolishShaderAdapter.Profile.COMPLEMENTARY,
                        HighPolishShaderAdapter.profileForProperties(properties));
                assertTrue(ComplementaryUnboundDev5SourceTransformer.adaptProperties(properties,
                        ComplementaryUnboundDev5SourceTransformer.Mode.AUTO).eligible(), "CTM-POM must also recognise this release");
                assertFalse(ComplementaryUnboundDev5SourceTransformer.matchesSupportedProperties(properties + "\n# unknown edit"));
                String ids = read(zip, "shaders/block.properties");
                for (int id : HighPolishShaderAdapter.RESERVED_IDS) assertFalse(ids.matches("(?s).*block\\." + id + "\\s*=.*"));
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    HighPolishShaderAdapter.beginShaderLoad(true, true);
                    HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                    String vertex = source(zip, dimension, "gbuffers_terrain.vsh");
                    String fragment = source(zip, dimension, "gbuffers_terrain.fsh");
                    var pom = ComplementaryUnboundDev5SourceTransformer.transformProgram(
                            "gbuffers_terrain", vertex, fragment, ErydonCuPomShaderBridge.vertexSource(),
                            ErydonCuPomShaderBridge.fragmentSource(), true);
                    assertTrue(pom.changed(), dimension + ": " + pom.status());
                    var terrain = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", pom.fragmentText());
                    var deferred = HighPolishShaderAdapter.adaptFragment("deferred1", source(zip, dimension, "deferred1.fsh"));
                    var water = HighPolishShaderAdapter.adaptFragment("gbuffers_water", source(zip, dimension, "gbuffers_water.fsh"));
                    String compositeSource = source(zip, dimension, "composite.fsh");
                    String blendSource = source(zip, dimension, "composite1.fsh");
                    var composite = HighPolishShaderAdapter.adaptFragment("composite", compositeSource);
                    var blend = HighPolishShaderAdapter.adaptFragment("composite1", blendSource);
                    assertTrue(terrain.changed(), dimension + ": " + terrain.status());
                    assertTrue(deferred.changed(), dimension + ": " + deferred.status());
                    assertTrue(water.changed(), dimension + ": " + water.status());
                    assertFalse(composite.changed(), dimension + ": " + composite.status());
                    assertFalse(blend.changed(), dimension + ": " + blend.status());
                    assertTrue(HighPolishShaderAdapter.ready());
                    parse(HighPolishShaderAdapter.adaptSpiralPredicate(pom.vertexText()));
                    parse(HighPolishShaderAdapter.adaptSpiralPredicate(terrain.text()));
                    parse(deferred.text());
                    parse(water.text());
                    parse(composite.text());
                    parse(blend.text());
                    InstalledShaderTestSupport.validateProgram(path.getFileName() + "/" + dimension + "/terrain",
                            HighPolishShaderAdapter.adaptSpiralPredicate(pom.vertexText()),
                            HighPolishShaderAdapter.adaptSpiralPredicate(terrain.text()));
                    for (var stage : List.of(new String[]{"deferred1", deferred.text()},
                            new String[]{"gbuffers_water", water.text()}, new String[]{"composite", composite.text()},
                            new String[]{"composite1", blend.text()})) {
                        InstalledShaderTestSupport.validateProgram(path.getFileName() + "/" + dimension + "/" + stage[0],
                                source(zip, dimension, stage[0] + ".vsh"), stage[1]);
                    }
                    assertEquals(samplingCalls(compositeSource), samplingCalls(composite.text()), "No additional reflection samples");
                    assertEquals(samplingCalls(blendSource), samplingCalls(blend.text()), "Reuse the filter's material sample");
                    assertSame(compositeSource, composite.text(), "Stone finishes do not alter metal tint");
                    assertSame(blendSource, blend.text(), "Stone finishes do not alter metal reflection blending");
                }
            } finally {
                HighPolishShaderAdapter.beginShaderLoad(false, false);
            }
        }
    }

    @Test void actualLowSamplerProfileParsesWithoutAddingTheOmittedSampler() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                String source = source(zip, "world0", "composite1.fsh", List.of(
                        new StringPair("MC_OS_MAC", "1"), new StringPair("DISTANT_HORIZONS", "1")));
                assertFalse(source.contains("float smoothnessD = texture6.r;"));
                var result = HighPolishShaderAdapter.adaptFragment("composite1", source, true);
                assertFalse(result.changed(), result.status());
                assertSame(source, result.text());
                assertEquals(samplingCalls(source), samplingCalls(result.text()));
                assertFalse(result.text().contains("int(texture6.g * 255.1) == 243"));
                parse(result.text());
            }
        }
    }

    private static long samplingCalls(String source) {
        return Pattern.compile("\\b(?:texture\\w*|texelFetch\\w*)\\s*\\(").matcher(source).results().count();
    }

    @Test void circularColumnsReuseTheInstalledWorldSpaceReflectionVoxelizer() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    String expanded = expand(zip, "shaders/" + dimension + "/shadow.vsh", 0)
                            .replaceAll("(?m)^([ \\t]*#define WORLD_SPACE_REFLECTIONS) -1", "$1 1")
                            .replaceAll("(?m)^([ \\t]*#define COLORED_LIGHTING) 0", "$1 128");
                    String source = JcppProcessor.glslPreprocessSource(expanded, List.of(
                            new StringPair("MC_VERSION", "12001"), new StringPair("IS_IRIS", "1"),
                            new StringPair("IRIS_FEATURE_CUSTOM_IMAGES", "1"), new StringPair("IRIS_FEATURE_SSBO", "1")));
                    if (!source.contains("void UpdateSceneVoxelMap(")) continue; // Older CU versions lack world-space reflections.
                    var result = HighPolishShaderAdapter.adaptColumnReflections(source, true);
                    assertTrue(result.changed(), dimension + ": " + result.status());
                    assertEquals(samplingCalls(source), samplingCalls(result.text()));
                    assertEquals(source.split("imageStore", -1).length, result.text().split("imageStore", -1).length);
                    parse(result.text());
                }
            }
        }
    }

    @Test void terrainParsesWithIndependentPomFilteringAndNormalOptions() throws Exception {
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (boolean pomEnabled : new boolean[]{false, true}) {
                        for (int filtering : new int[]{0, 8}) {
                            for (int strength : new int[]{0, 120}) {
                                var options = new InstalledShaderTestSupport.Options(pomEnabled, filtering, strength);
                                String label = path.getFileName() + "/" + dimension + "/" + options;
                                String vertex = InstalledShaderTestSupport.source(zip, dimension,
                                        "gbuffers_terrain.vsh", options, List.of());
                                String fragment = InstalledShaderTestSupport.source(zip, dimension,
                                        "gbuffers_terrain.fsh", options, List.of());
                                var result = ComplementaryUnboundDev5SourceTransformer.transformProgram(
                                        "gbuffers_terrain", vertex, fragment, ErydonCuPomShaderBridge.vertexSource(),
                                        ErydonCuPomShaderBridge.fragmentSource(), true);
                                if (pomEnabled) {
                                    assertTrue(result.changed(), label + ": " + result.status());
                                    vertex = result.vertexText();
                                    fragment = result.fragmentText();
                                } else {
                                    assertFalse(result.changed(), label);
                                    assertEquals("POM_NOT_COMPILED", result.status(), label);
                                    assertSame(vertex, result.vertexText());
                                    assertSame(fragment, result.fragmentText());
                                }
                                var finish = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", fragment, true);
                                assertTrue(finish.changed(), label + ": " + finish.status());
                                parse(vertex);
                                parse(finish.text());
                                InstalledShaderTestSupport.validateProgram(label, vertex, finish.text());
                            }
                        }
                    }
                }
            }
        }
    }

    private static String source(ZipFile zip, String dimension, String file) throws Exception {
        return source(zip, dimension, file, List.of());
    }

    private static String source(ZipFile zip, String dimension, String file, List<StringPair> extraDefines) throws Exception {
        return InstalledShaderTestSupport.source(zip, dimension, file,
                InstalledShaderTestSupport.Options.DEFAULT, extraDefines);
    }

    static String expand(ZipFile zip, String path, int depth) throws Exception {
        if (depth > 32) throw new IllegalStateException("Recursive shader include: " + path);
        var matcher = INCLUDE.matcher(read(zip, path));
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String include = matcher.group(1);
            String child = include.startsWith("/") ? "shaders" + include
                    : path.substring(0, path.lastIndexOf('/') + 1) + include;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(expand(zip, child, depth + 1)));
        }
        return matcher.appendTail(result).toString();
    }

    static String read(ZipFile zip, String path) throws Exception {
        var entry = zip.getEntry(path);
        assertNotNull(entry, path);
        try (var stream = zip.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void parse(String source) throws Exception {
        // Iris's runtime parser is deliberately not added as a production compile dependency.
        Class<?> parserType = Class.forName("io.github.douira.glsl_transformer.ast.transform.ASTParser");
        Class<?> rootType = Class.forName("io.github.douira.glsl_transformer.ast.query.Root");
        Object parser = parserType.getConstructor().newInstance();
        parserType.getMethod("setThrowParseErrors", boolean.class).invoke(parser, true);
        Object root = Class.forName("io.github.douira.glsl_transformer.ast.query.RootSupplier")
                .getMethod("supplyDefault").invoke(null);
        parserType.getMethod("parseTranslationUnit", rootType, String.class)
                .invoke(parser, root, source);
    }
}
