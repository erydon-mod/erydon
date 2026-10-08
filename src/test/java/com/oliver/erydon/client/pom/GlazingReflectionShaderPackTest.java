package com.oliver.erydon.client.pom;

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

/** Optional validation of installed shader sources; archives and settings remain untouched. */
@EnabledIf("configured")
@Execution(ExecutionMode.SAME_THREAD)
class GlazingReflectionShaderPackTest {
    private static final Map<String, String> REFLECTION_OPTIONS = Map.of(
            "WORLD_SPACE_REFLECTIONS", "1", "COLORED_LIGHTING", "512", "REFLECTION_RES", "1.0");

    static boolean configured() { return InstalledShaderTestSupport.configured(); }
    @AfterAll static void closeCompiler() { InstalledShaderTestSupport.close(); }
    @AfterEach void resetFinishState() { HighPolishShaderAdapter.beginShaderLoad(false, false); }

    @Test void actualWaterProgramsCompileWithFixedGlassResponseInEveryDimensionAndPomMode() throws Exception {
        int cases = 0;
        for (Path path : InstalledShaderTestSupport.shaderPaths()) {
            try (var zip = new ZipFile(path.toFile())) {
                assertEquals(HighPolishShaderAdapter.Profile.COMPLEMENTARY,
                        HighPolishShaderAdapter.profileForProperties(
                                HighPolishShaderPackTest.read(zip, "shaders/shaders.properties")));
                String materialIds = HighPolishShaderPackTest.read(zip, "shaders/block.properties");
                assertFalse(Pattern.compile("(?m)^\\s*block\\." + HighPolishShaderAdapter.GLAZING_ID
                        + "\\s*=").matcher(materialIds).find(), "Glazing must not claim a pack's existing material ID");
                for (String dimension : List.of("world0", "world-1", "world1")) {
                    for (boolean pom : new boolean[]{false, true}) {
                        var options = new InstalledShaderTestSupport.Options(pom, 8, 200);
                        String vertex = InstalledShaderTestSupport.source(zip, dimension,
                                "gbuffers_water.vsh", options, List.of(), REFLECTION_OPTIONS);
                        String original = InstalledShaderTestSupport.source(zip, dimension,
                                "gbuffers_water.fsh", options, List.of(), REFLECTION_OPTIONS);
                        begin(false);
                        var predecessor = HighPolishShaderAdapter.adaptFragment("gbuffers_water", original);
                        assertTrue(predecessor.changed(), predecessor.status());
                        assertFalse(predecessor.text().contains("erydonGlazingReflection"));
                        var nativeMetal = MetallicShaderAdapter.adapt("gbuffers_water", vertex,
                                predecessor.text(), true);
                        assertTrue(nativeMetal.changed(), nativeMetal.status());
                        String label = path.getFileName() + "/" + dimension + "/glazing=0.5/POM=" + pom;
                        begin(true);
                        var glass = HighPolishShaderAdapter.adaptFragment("gbuffers_water", original);
                        assertTrue(glass.changed(), label + ": " + glass.status());
                        assertTrue(glass.text().contains("(0.5 + (1.0 - 0.5) * pow(fresnel, 5.0))"), label);
                        assertEquals(samplingCalls(original), samplingCalls(glass.text()),
                                label + ": glazing reuses the existing material sample");
                        assertTrue(glass.text().contains("materialMask = -1.0"), label);
                        assertTrue(glass.text().contains("(0.9 - 0.15) / 0.85"),
                                label + ": the separate two-way coating retains its 90% floor");
                        assertTrue(glass.text().indexOf("color.a = color.a * (1.0 - erydonGlazingReflection)")
                                > glass.text().indexOf("translucentMult.rgb = mix(translucentMult.rgb"),
                                label + ": volumetric tint must use the original pane alpha");
                        var combined = MetallicShaderAdapter.adapt("gbuffers_water", vertex, glass.text(), true);
                        assertTrue(combined.changed(), label + ": " + combined.status());
                        assertFalse(Pattern.compile("(?m)^\\s*#\\s*(?:if|ifdef|ifndef|else|elif|endif|define|undef|include)\\b")
                                .matcher(combined.fragment()).find(),
                                label + ": runtime adapters execute after Iris preprocessing");
                        assertEquals(samplingCalls(nativeMetal.fragment()), samplingCalls(combined.fragment()),
                                label + ": composing glazing and metal adds no texture samples");
                        HighPolishShaderPackTest.parse(combined.vertex());
                        HighPolishShaderPackTest.parse(combined.fragment());
                        InstalledShaderTestSupport.validateProgram(label, combined.vertex(), combined.fragment());
                        cases++;
                        begin(false);
                        assertEquals(predecessor.text(),
                                HighPolishShaderAdapter.adaptFragment("gbuffers_water", original).text(),
                                "Glass-off retains the exact preceding adapter response, including mirror coatings");
                        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.UNSUPPORTED,
                                true, true);
                        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                        assertSame(original, HighPolishShaderAdapter.adaptFragment("gbuffers_water", original).text());
                        begin(true);
                        HighPolishShaderAdapter.acceptMaterialIds(id -> id == HighPolishShaderAdapter.GLAZING_ID);
                        assertSame(original, HighPolishShaderAdapter.adaptFragment("gbuffers_water", original).text(),
                                "Conflicting material IDs must leave the program untouched");
                    }
                }
            }
        }
        assertEquals(6 * InstalledShaderTestSupport.shaderPaths().size(), cases);
        System.out.println("Glazing shader programs: " + cases + " actual dimension/POM combinations at fixed 50% reflection");
    }

    private static void begin(boolean glass) {
        HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.COMPLEMENTARY,
                true, glass);
        HighPolishShaderAdapter.acceptMaterialIds(id -> false);
    }

    private static long samplingCalls(String source) {
        return Pattern.compile("\\b(?:texture\\w*|texelFetch\\w*)\\s*\\(").matcher(source).results().count();
    }
}
