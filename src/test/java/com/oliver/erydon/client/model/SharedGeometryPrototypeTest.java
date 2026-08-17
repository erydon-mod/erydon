package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedGeometryPrototypeTest {
    private static final Map<String, String> AUTHORING_MODELS = Map.of(
            "plinth", "column_gothic_plinth.json",
            "base", "column_gothic_base.json",
            "pillar", "column_gothic_pillar.json",
            "capital", "column_gothic_capital.json"
    );

    @AfterEach
    void restoreMode() {
        System.clearProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY);
    }

    @Test
    void productionDefaultAndInvalidValuesRemainBaseline() {
        System.clearProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY);
        assertEquals("baseline", ErydonRawModelLoadingPlugin.configuredModeForTest());

        System.setProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY, "unexpected");
        assertEquals("baseline", ErydonRawModelLoadingPlugin.configuredModeForTest());

        System.setProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY, "shared_geometry");
        assertEquals("shared_geometry", ErydonRawModelLoadingPlugin.configuredModeForTest());
    }

    @Test
    void batchPoolIsLimitedToCompatibleComponentFamilies() {
        assertEquals("column/circular", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/column/circular/aganite_column_circular_base"));
        assertEquals("column/square", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/column/square/aganite_column_square_pillar"));
        assertEquals("surround/georgian", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/surround/georgian/aganite_surround_georgian_shaft"));
        assertEquals("cornice/georgian", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/cornice/georgian/aganite_cornice_georgian_straight"));
        assertEquals("ceiling/coffered/georgian", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/ceiling/coffered/georgian/aganite_ceiling_coffered_georgian_small"));
        assertEquals("layer/solid", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/layer/layer/aganite_ashlar_layer_vertical_depth8"));
        assertEquals("layer/glazing", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/glazing/layer/clearglass_layer_vertical_depth8"));
        assertEquals("window/arch", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/window/arch/aganite_window_arch_wall"));
        assertEquals("arch/romanesque", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/arch/romanesque/aganite_arch_romanesque_side_small"));
        assertEquals("arch/modern", ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/arch/modern/aganite_arch_modern_side_small"));

        assertNull(ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/column/circular/column_circular_base"));
        assertNull(ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/slope/aganite_slope"));
        assertNull(ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/light/modern/aganite_light_modern"));
        assertNull(ErydonSharedBakedGeometryPlugin.familyForTest(
                "block/alcove/gothic/aganite_alcove_gothic_single_top"));
    }

    @Test
    void allBatchRawAuthoringModelsProduceDeterministicSurfaces() throws Exception {
        Path root = Path.of("src/main/resources/assets/erydon/authoring_models/block");
        List<Path> modelFiles = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        return relative.startsWith("column/gothic/")
                                || relative.startsWith("arch/gothic/")
                                || relative.startsWith("alcove/alcove_georgian_")
                                || relative.startsWith("alcove/alcove_gothic_");
                    })
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(modelFiles::add);
        }
        assertEquals(43, modelFiles.size());

        for (Path modelFile : modelFiles) {
            String relative = root.relativize(modelFile).toString().replace('\\', '/');
            JsonElement model;
            try (var reader = Files.newBufferedReader(modelFile, StandardCharsets.UTF_8)) {
                model = JsonParser.parseReader(reader);
            }
            Identifier sourceId = new Identifier(
                    "erydon", "authoring_models/block/" + relative);
            List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> first =
                    ErydonRawModelLoadingPlugin.surfaceSnapshots(relative, sourceId, model);
            List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> second =
                    ErydonRawModelLoadingPlugin.surfaceSnapshots(relative, sourceId, model);
            assertFalse(first.isEmpty(), relative);
            assertEquals(first.size(), second.size(), relative);
            for (int surface = 0; surface < first.size(); surface++) {
                assertDeepEquals(first.get(surface).vertexPositions(), second.get(surface).vertexPositions());
                assertDeepEquals(first.get(surface).sourceUvs(), second.get(surface).sourceUvs());
                assertDeepEquals(first.get(surface).normalizedUvs(), second.get(surface).normalizedUvs());
            }
        }
    }

    @Test
    void allFourGothicComponentsProduceDeterministicCompleteSurfaces() {
        for (Map.Entry<String, String> entry : AUTHORING_MODELS.entrySet()) {
            JsonElement root = authoringModel(entry.getValue());
            Identifier sourceId = new Identifier(
                    "erydon",
                    "authoring_models/block/column/gothic/" + entry.getValue()
            );
            List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> first =
                    ErydonRawModelLoadingPlugin.surfaceSnapshots(
                            "column_gothic/" + entry.getKey(), sourceId, root);
            List<ErydonRawModelLoadingPlugin.SurfaceSnapshot> second =
                    ErydonRawModelLoadingPlugin.surfaceSnapshots(
                            "column_gothic/" + entry.getKey(), sourceId, root);

            assertFalse(first.isEmpty(), entry.getKey());
            assertEquals(first.size(), second.size(), entry.getKey());
            for (int surface = 0; surface < first.size(); surface++) {
                ErydonRawModelLoadingPlugin.SurfaceSnapshot left = first.get(surface);
                ErydonRawModelLoadingPlugin.SurfaceSnapshot right = second.get(surface);
                assertEquals(left.faceDirection(), right.faceDirection());
                assertEquals(left.cullFace(), right.cullFace());
                assertDeepEquals(left.vertexPositions(), right.vertexPositions());
                assertDeepEquals(left.sourceUvs(), right.sourceUvs());
                assertDeepEquals(left.normalizedUvs(), right.normalizedUvs());
                assertEquals(4, left.vertexPositions().length);
                assertEquals(4, left.sourceUvs().length);
                assertEquals(4, left.normalizedUvs().length);
            }
        }
    }

    @Test
    void textureOnlyOverrideKeepsSharingAndPreservesExactSprites() {
        JsonObject override = JsonParser.parseString("""
                {
                  "parent": "erydon:block/column/gothic/column_gothic_plinth",
                  "textures": {
                    "stone": "third_party:block/custom_stone",
                    "particle": "third_party:block/custom_particle"
                  }
                }
                """).getAsJsonObject();

        assertTrue(ErydonRawModelLoadingPlugin.sharedChildOverrideIsSafe(override, "plinth"));
        assertEquals(
                "particle=third_party:block/custom_particle;surface=third_party:block/custom_stone",
                ErydonRawModelLoadingPlugin.sharedOverrideBinding(override)
        );
    }

    @Test
    void structuralOrAmbiguousOverrideUsesVanillaFallback() {
        JsonObject structural = JsonParser.parseString("""
                {
                  "parent": "erydon:block/column/gothic/column_gothic_plinth",
                  "textures": {
                    "stone": "third_party:block/custom_stone",
                    "particle": "third_party:block/custom_stone"
                  },
                  "elements": [
                    {"from":[0,0,0],"to":[16,16,16],"faces":{}}
                  ]
                }
                """).getAsJsonObject();
        JsonObject unresolvedTexture = JsonParser.parseString("""
                {
                  "parent": "erydon:block/column/gothic/column_gothic_plinth",
                  "textures": {
                    "stone": "#missing_slot",
                    "particle": "third_party:block/custom_stone"
                  }
                }
                """).getAsJsonObject();

        assertFalse(ErydonRawModelLoadingPlugin.sharedChildOverrideIsSafe(structural, "plinth"));
        assertNull(ErydonRawModelLoadingPlugin.sharedOverrideBinding(unresolvedTexture));
    }

    private static JsonElement authoringModel(String fileName) {
        String path = "assets/erydon/authoring_models/block/column/gothic/" + fileName;
        InputStream input = SharedGeometryPrototypeTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(input, path);
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }

    private static void assertDeepEquals(float[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int row = 0; row < expected.length; row++) {
            assertEquals(expected[row].length, actual[row].length);
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(expected[row][column], actual[row][column], 0.0F);
            }
        }
    }
}
