package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CorniceCtmCoverageTest {
    @Test void everyMaterialFinishAndSavedCornerPoseRetainsRepeatCoverageAndChildModels() throws Exception {
        Path root = Path.of("src/main/resources");
        var values = JsonParser.parseString(Files.readString(root.resolve("data/erydon/tags/blocks/cornice.json")))
                .getAsJsonObject().getAsJsonArray("values");
        Set<Identifier> cornices = new LinkedHashSet<>();
        for (var value : values) cornices.add(new Identifier(value.getAsString()));
        assertEquals(216, cornices.size());
        List<SynapheiaManifest.Rule> rules = new ArrayList<>();
        try (var paths = Files.walk(root.resolve("assets"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".properties")
                    && file.toString().replace('\\', '/').contains("/ctm/")).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                Properties properties = new Properties();
                try (var input = Files.newInputStream(path)) { properties.load(input); }
                Set<Identifier> blocks = SynapheiaManifest.parseBlocks(properties.getProperty("matchBlocks"));
                boolean matchesCornice = blocks.stream().map(id -> new Identifier(id.getNamespace(),
                        ErydonIdMigration.canonicalPath(id.getPath()))).anyMatch(cornices::contains);
                if (!matchesCornice) continue;
                assertFalse(bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191,
                        "Cornice CTM properties must not start with a UTF-8 BOM: " + path);
                Path relative = root.resolve("assets").relativize(path);
                String resource = relative.toString().replace('\\', '/');
                int slash = resource.indexOf('/');
                Identifier ruleId = new Identifier(resource.substring(0, slash), resource.substring(slash + 1));
                var rule = SynapheiaManifest.parseRule(ruleId, "native", properties, blocks);
                assertEquals(SynapheiaManifest.Method.REPEAT, rule.method());
                assertEquals(36, rule.tiles().size());
                assertEquals(Set.of(Direction.values()), rule.faces());
                for (Identifier tile : rule.tiles()) {
                    assertTrue(Files.isRegularFile(root.resolve("assets/" + tile.getNamespace() + "/textures/" + tile.getPath() + ".png")),
                            "Missing native cornice repeat tile " + tile);
                }
                rules.add(rule);
            }
        }
        assertEquals(54, rules.size());
        var snapshot = SynapheiaService.publish(new SynapheiaManifest.Prepared(List.copyOf(rules), "native", 0, rules.size(), 0, 0));
        for (Identifier id : cornices) {
            assertNotNull(snapshot.planFor(id), "Missing native cornice plan for " + id);
            assertTrue(snapshot.planFor(id).hasRepeat());
            Path blockstate = root.resolve("assets/erydon/blockstates/" + id.getPath() + ".json");
            var multipart = JsonParser.parseString(Files.readString(blockstate)).getAsJsonObject().getAsJsonArray("multipart");
            assertEquals(1, multipart.size());
            assertFalse(multipart.get(0).getAsJsonObject().has("when"), "Placed wrapper must cover new angled shape values");
            for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) for (Direction facing : Direction.Type.HORIZONTAL) {
                assertTrue(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id,
                        "facing=" + facing.asString() + ",shape=" + shape.asString())), "Missing placed CTM wrapper for " + id + " " + shape);
            }
            assertFalse(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id, "inventory")));
            for (String suffix : CorniceBakedModel.modelSuffixes(id.getPath())) {
                Identifier child = CorniceBakedModel.modelId(id.getPath(), suffix);
                assertTrue(Files.isRegularFile(root.resolve("assets/" + child.getNamespace() + "/models/" + child.getPath() + ".json")),
                        "Missing cornice baked child model " + child);
            }
        }
    }
}
