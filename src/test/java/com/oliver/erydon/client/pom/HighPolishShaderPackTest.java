package com.oliver.erydon.client.pom;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Optional read-only audit of the user's shader; no shader assets are redistributed. */
@EnabledIfEnvironmentVariable(named = "ERYDON_CU_TEST_SHADER", matches = ".+")
class HighPolishShaderPackTest {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\".*$");

    @Test void installedPackPreprocessesAndParsesWithBothAdaptersInEveryDimension() throws Exception {
        try (var zip = new ZipFile(System.getenv("ERYDON_CU_TEST_SHADER"))) {
            assertTrue(ComplementaryUnboundDev5SourceTransformer.matchesSupportedProperties(
                    read(zip, "shaders/shaders.properties")));
            String ids = read(zip, "shaders/block.properties");
            for (int id : new int[]{12024, 12025, 12027}) assertFalse(ids.contains("block." + id + "="));
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
                assertTrue(terrain.changed(), dimension + ": " + terrain.status());
                assertTrue(deferred.changed(), dimension + ": " + deferred.status());
                assertTrue(HighPolishShaderAdapter.ready());
                parse(HighPolishShaderAdapter.adaptSpiralPredicate(pom.vertexText()));
                parse(HighPolishShaderAdapter.adaptSpiralPredicate(terrain.text()));
                parse(deferred.text());
            }
        } finally {
            HighPolishShaderAdapter.beginShaderLoad(false, false);
        }
    }

    private static String source(ZipFile zip, String dimension, String file) throws Exception {
        String expanded = expand(zip, "shaders/" + dimension + "/" + file, 0)
                .replaceAll("(?m)^([ \\t]*#define RP_MODE) \\d+", "$1 3");
        return JcppProcessor.glslPreprocessSource(expanded, List.of(
                new StringPair("MC_VERSION", "12001"), new StringPair("IS_IRIS", "1"),
                new StringPair("MC_GL_VERSION", "430"), new StringPair("MC_GL_VENDOR_NVIDIA", "1"),
                new StringPair("IRIS_FEATURE_SSBO", "1"), new StringPair("IRIS_FEATURE_CUSTOM_IMAGES", "1")));
    }

    private static String expand(ZipFile zip, String path, int depth) throws Exception {
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

    private static String read(ZipFile zip, String path) throws Exception {
        var entry = zip.getEntry(path);
        assertNotNull(entry, path);
        try (var stream = zip.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void parse(String source) throws Exception {
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
