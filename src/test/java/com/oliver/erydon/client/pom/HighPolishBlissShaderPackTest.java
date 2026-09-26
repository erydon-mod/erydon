package com.oliver.erydon.client.pom;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Reads the installed shader, without copying or redistributing its assets. */
@EnabledIfEnvironmentVariable(named = "ERYDON_BLISS_TEST_SHADER", matches = ".+")
class HighPolishBlissShaderPackTest {
    @Test void installedBlissRemainsNativeInEveryDimensionDuringTheComplementaryTrial() throws Exception {
        try (var zip = new ZipFile(System.getenv("ERYDON_BLISS_TEST_SHADER"))) {
            assertEquals(HighPolishShaderAdapter.Profile.BLISS, HighPolishShaderAdapter.profileForProperties(
                    HighPolishShaderPackTest.read(zip, "shaders/shaders.properties")));
            String ids = HighPolishShaderPackTest.read(zip, "shaders/block.properties");
            for (int id : new int[]{12024, 12025, 12027, 12029, 12031}) assertFalse(ids.matches("(?s).*block\\." + id + "\\s*=.*"));
            for (String dimension : List.of("world0", "world-1", "world1")) {
                HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.BLISS, true);
                HighPolishShaderAdapter.acceptMaterialIds(id -> false);
                String expanded = HighPolishShaderPackTest.expand(zip, "shaders/" + dimension + "/gbuffers_terrain.fsh", 0)
                        .replace("// #define Specular_Reflections", "#define Specular_Reflections");
                String source = JcppProcessor.glslPreprocessSource(expanded,
                        List.of(new StringPair("MC_VERSION", "12001"), new StringPair("IS_IRIS", "1")));
                var adapted = HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", source);
                assertFalse(adapted.changed(), dimension + ": " + adapted.status());
                assertFalse(HighPolishShaderAdapter.ready());
                assertSame(source, adapted.text());
                HighPolishShaderPackTest.parse(adapted.text());
            }
        } finally {
            HighPolishShaderAdapter.beginShaderLoad(false, false);
        }
    }
}
