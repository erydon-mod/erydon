package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.util.ErydonIdNaming;
import com.oliver.erydon.block.AlcoveBlock;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AlcoveModelRoutingTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/erydon");

    @Test
    void georgianVariantsResolveTheirAuthoredBodyDetailsAcrossEveryFinish() throws Exception {
        Class<?> componentClass = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawComponent");
        Method from = componentClass.getDeclaredMethod("from", Identifier.class);
        Field modelKey = componentClass.getDeclaredField("modelKey");
        Field textureId = componentClass.getDeclaredField("textureId");
        from.setAccessible(true);
        modelKey.setAccessible(true);
        textureId.setAccessible(true);
        List<Path> blockstates;
        try (var paths = Files.list(ASSETS.resolve("blockstates"))) {
            blockstates = paths.filter(path -> path.getFileName().toString().endsWith("_alcove_georgian.json"))
                    .sorted().toList();
        }
        assertEquals(162, blockstates.size());
        for (Path blockstate : blockstates) {
            String blockPath = blockstate.getFileName().toString().replace(".json", "");
            String base = ErydonIdNaming.withoutAged(blockPath);
            boolean aged = ErydonIdNaming.isAged(blockPath);
            String materialFinish = base.substring(0, base.length() - "_alcove_georgian".length());
            List<String> suffixes = AlcoveBakedModel.modelSuffixes(blockPath);
            assertEquals(26, suffixes.size(), blockPath);
            for (String suffix : suffixes) {
                Identifier id = AlcoveBakedModel.modelId(blockPath, suffix);
                var model = JsonParser.parseString(Files.readString(ASSETS.resolve("models/" + id.getPath() + ".json")))
                        .getAsJsonObject();
                assertEquals("erydon:block/alcove/georgian/alcove_georgian_" + suffix,
                        model.get("parent").getAsString(), id.toString());
                Object component = from.invoke(null, id);
                assertNotNull(component, id.toString());
                assertEquals("alcove_georgian/" + suffix, modelKey.get(component));
                assertEquals(new Identifier("erydon", "block/" + materialFinish + "_block" + (aged ? "_aged" : "")),
                        textureId.get(component), id.toString());
            }
        }
        assertEquals(14, AlcoveBakedModel.modelSuffixes("aganite_alcove_modern").size());
        assertEquals(14, AlcoveBakedModel.modelSuffixes("aganite_alcove_gothic").size());
    }

    @Test
    void georgianBodyDetailsFollowHeightWidthAndTheOuterJamb() {
        for (var part : AlcoveBlock.AlcovePart.values()) {
            for (var span : AlcoveBlock.AlcoveSpan.values()) {
                List<String> actual = AlcoveBakedModel.bodyDetailSuffixes(part, span);
                assertSame(actual, AlcoveBakedModel.bodyDetailSuffixes(part, span), "Detail selection must be cached");
                if (part == AlcoveBlock.AlcovePart.SINGLE || part == AlcoveBlock.AlcovePart.TOP
                        || span == AlcoveBlock.AlcoveSpan.TRIPLE_CENTER) {
                    assertTrue(actual.isEmpty(), part + "/" + span);
                    continue;
                }
                String height = part == AlcoveBlock.AlcovePart.BASE ? "base" : "middle";
                List<String> expected = switch (span) {
                    case SINGLE -> List.of(height + "_detail_left", height + "_detail_right");
                    case LEFT -> List.of("double_" + height + "_detail_left");
                    case RIGHT -> List.of("double_" + height + "_detail_right");
                    case TRIPLE_LEFT -> List.of("triple_" + height + "_detail_left");
                    case TRIPLE_RIGHT -> List.of("triple_" + height + "_detail_right");
                    case TRIPLE_CENTER -> throw new AssertionError("Handled above");
                };
                assertEquals(expected, actual);
                assertTrue(AlcoveBakedModel.modelSuffixes("aganite_alcove_georgian").containsAll(actual));
            }
        }
    }

    @Test
    void georgianCrownsOwnTheirShortenedSidesWhileBodyRowsKeepRepeatableSides() {
        for (var part : AlcoveBlock.AlcovePart.values()) {
            boolean crown = part == AlcoveBlock.AlcovePart.SINGLE || part == AlcoveBlock.AlcovePart.TOP;
            for (var span : AlcoveBlock.AlcoveSpan.values()) {
                String sides = switch (span) {
                    case SINGLE -> "sides";
                    case LEFT -> "double_side_left";
                    case RIGHT -> "double_side_right";
                    case TRIPLE_LEFT -> "triple_side_left";
                    case TRIPLE_CENTER -> "triple_side_center";
                    case TRIPLE_RIGHT -> "triple_side_right";
                };
                String top = switch (span) {
                    case SINGLE -> "top";
                    case LEFT -> "double_top_left";
                    case RIGHT -> "double_top_right";
                    case TRIPLE_LEFT -> "triple_top_left";
                    case TRIPLE_CENTER -> "triple_top_center";
                    case TRIPLE_RIGHT -> "triple_top_right";
                };
                List<String> actual = AlcoveBakedModel.componentSuffixes(part, span, true);
                assertSame(actual, AlcoveBakedModel.componentSuffixes(part, span, true),
                        "Component selection must be cached");
                if (crown) {
                    assertEquals(List.of("back", top), actual, part + "/" + span);
                    assertFalse(actual.contains(sides), "The authored crown already contains its sides");
                } else {
                    List<String> expected = new ArrayList<>(List.of("back", sides));
                    expected.addAll(AlcoveBakedModel.bodyDetailSuffixes(part, span));
                    assertEquals(expected, actual, part + "/" + span);
                }
                assertTrue(AlcoveBakedModel.modelSuffixes("aganite_alcove_georgian").containsAll(actual));

                List<String> plain = AlcoveBakedModel.componentSuffixes(part, span, false);
                assertSame(plain, AlcoveBakedModel.componentSuffixes(part, span, false));
                assertEquals(crown ? List.of("back", sides, top) : List.of("back", sides), plain,
                        "Modern and Gothic retain their separate side components");
            }
        }
    }

    @Test
    void modernVariantsResolveTheirOwnComponentsAndAuthoringFamily() throws Exception {
        List<Path> blockstates;
        try (var paths = Files.list(ASSETS.resolve("blockstates"))) {
            blockstates = paths.filter(path -> path.getFileName().toString().endsWith("_alcove_modern.json"))
                    .sorted().toList();
        }
        assertEquals(162, blockstates.size());
        Class<?> componentClass = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawComponent");
        Method from = componentClass.getDeclaredMethod("from", Identifier.class);
        Field modelKey = componentClass.getDeclaredField("modelKey");
        Field textureId = componentClass.getDeclaredField("textureId");
        from.setAccessible(true);
        modelKey.setAccessible(true);
        textureId.setAccessible(true);
        Set<String> finishes = new HashSet<>();
        for (Path blockstate : blockstates) {
            String blockPath = blockstate.getFileName().toString().replace(".json", "");
            assertTrue(ErydonModelFamilyIndex.isAlcoveBlock(blockPath), blockPath);
            assertTrue(ErydonModelFamilyIndex.isModernAlcoveBlock(blockPath), blockPath);
            assertFalse(ErydonModelFamilyIndex.isGeorgianAlcoveBlock(blockPath), blockPath);
            String base = ErydonIdNaming.withoutAged(blockPath);
            boolean aged = ErydonIdNaming.isAged(blockPath);
            String materialFinish = base.substring(0, base.length() - "_alcove_modern".length());
            finishes.add(materialFinish + (aged ? "_aged" : ""));
            assertFalse(blockPath.endsWith("_aged"), "Modern aged IDs must use material_aged_form");
            String blockstateText = Files.readString(blockstate);
            assertTrue(blockstateText.contains("erydon:block/internal/wrapped/" + blockPath), blockPath);
            assertTrue(Files.isRegularFile(ASSETS.resolve("models/block/internal/wrapped/" + blockPath + ".json")));
            String item = Files.readString(ASSETS.resolve("models/item/" + blockPath + ".json"));
            assertTrue(item.contains("erydon:block/alcove/modern/alcove_modern_icon"), blockPath);
            for (String suffix : AlcoveBakedModel.modelSuffixes(blockPath)) {
                Identifier id = AlcoveBakedModel.modelId(blockPath, suffix);
                assertEquals("block/alcove/modern/" + base + "_" + suffix + (aged ? "_aged" : ""), id.getPath());
                Path child = ASSETS.resolve("models/" + id.getPath() + ".json");
                var model = JsonParser.parseString(Files.readString(child)).getAsJsonObject();
                assertEquals("erydon:block/alcove/modern/alcove_modern_" + suffix,
                        model.get("parent").getAsString(), child.toString());
                Object component = from.invoke(null, id);
                assertNotNull(component, id.toString());
                assertEquals("alcove_modern/" + suffix, modelKey.get(component));
                assertEquals(new Identifier("erydon", "block/" + materialFinish + "_block" + (aged ? "_aged" : "")),
                        textureId.get(component), id.toString());
            }
        }
        assertEquals(162, finishes.size());
    }

    @Test
    void olderCollectionMaterialRulesCannotReplaceModernAlcoveCoverage() throws Exception {
        Path ctm = Path.of("src/main/resources/assets/minecraft/optifine/ctm/alcove_modern");
        List<Path> rules;
        try (var paths = Files.list(ctm)) {
            rules = paths.filter(path -> path.toString().endsWith(".properties")).toList();
        }
        assertEquals(162, rules.size());
        Set<String> covered = new HashSet<>();
        for (Path rule : rules) {
            byte[] bytes = Files.readAllBytes(rule);
            assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                    && bytes[2] == (byte) 0xbf, rule.toString());
            List<String> lines = Files.readAllLines(rule);
            assertTrue(lines.contains("method=repeat"), rule.toString());
            assertTrue(lines.contains("width=6") && lines.contains("height=6"), rule.toString());
            assertTrue(lines.contains("faces=all") || lines.contains("faces=top bottom north south east west"),
                    rule.toString());
            String blockId = lines.stream().filter(line -> line.startsWith("matchBlocks="))
                    .findFirst().orElseThrow().substring("matchBlocks=erydon:".length());
            assertTrue(covered.add(blockId), blockId);
            assertTrue(Files.isRegularFile(ASSETS.resolve("blockstates/" + blockId + ".json")), blockId);
            for (String tile : lines.stream().filter(line -> line.startsWith("tiles="))
                    .findFirst().orElseThrow().substring("tiles=".length()).split(" ")) {
                assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/minecraft")
                        .resolve(tile + ".png")), tile);
            }
        }
        assertEquals(162, covered.size());
    }
}
