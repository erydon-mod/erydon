package com.oliver.erydon.client;

import com.google.gson.JsonParser;
import com.oliver.erydon.HighPolishSettings;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.oliver.erydon.HighPolishSettings.*;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishLabelsPackTest {
    @Test void finishLabelsNeverReadOrReplaceAnyResolutionOfPackTextures() throws Exception {
        for (int resolution : new int[]{16, 32, 64}) {
            ResourcePack original = (ResourcePack) Proxy.newProxyInstance(ResourcePack.class.getClassLoader(),
                    new Class[]{ResourcePack.class}, (proxy, method, args) -> {
                        throw new AssertionError("Must not inspect or decode the " + resolution + "x source pack");
                    });
            var packs = List.of(original);
            for (Level level : Level.values()) {
                var result = HighPolishLabelsPack.append(ResourceType.CLIENT_RESOURCES, packs,
                        defaults().withEnabled(true).withAllStones(level));
                assertSame(original, result.get(0));
                var labels = result.get(1);
                var found = new HashSet<Identifier>();
                for (String namespace : List.of("erydon", "minecraft")) {
                    labels.findResources(ResourceType.CLIENT_RESOURCES, namespace, "textures", (id, supplier) -> found.add(id));
                    for (String material : List.of("kelastrion", "latmion", "psamatheon", "glacium")) {
                        for (String suffix : List.of("_s", "_n", "")) {
                            assertNull(labels.open(ResourceType.CLIENT_RESOURCES, new Identifier(namespace,
                                    "textures/optifine/ctm/" + material + "/0" + suffix + ".png")));
                            assertNull(labels.open(ResourceType.CLIENT_RESOURCES, new Identifier(namespace,
                                    "textures/block/" + material + "_block" + suffix + ".png")));
                        }
                    }
                }
                assertTrue(found.isEmpty());
            }
            assertSame(packs, HighPolishLabelsPack.append(ResourceType.SERVER_DATA, packs, defaults()));
        }
    }

    @Test void allLanguagesDescribeTheActivePlainFinishAndMasterOffUsesHoned() throws Exception {
        var settings = defaults().withEnabled(true).withAllStones(Level.POLISHED)
                .withStone("kelastrion", new Stone(Level.HONED, Choice.MIRROR, Choice.INHERIT, Choice.INHERIT))
                .withStone("latmion", new Stone(Level.MIRROR, Choice.INHERIT, Choice.INHERIT, Choice.INHERIT));
        var translations = Map.of("en_us", List.of("Honed", "Polished", "Ganosis"),
                "de_de", List.of("Geschliffen", "Poliert", "Ganosis"),
                "es_es", List.of("Apomazado", "Pulido", "Ganosis"));
        for (boolean enabled : new boolean[]{false, true}) {
            var pack = HighPolishLabelsPack.append(ResourceType.CLIENT_RESOURCES, List.of(), settings.withEnabled(enabled)).get(0);
            for (var language : translations.entrySet()) {
                var id = new Identifier("erydon", "lang/" + language.getKey() + ".json");
                try (var stream = pack.open(ResourceType.CLIENT_RESOURCES, id).get()) {
                    var labels = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    assertTrue(labels.size() > MATERIALS.size(), "Every eligible stone name must follow its active finish");
                    assertEquals("Kelastrion " + language.getValue().get(0), labels.get("command.erydon.swap.material.kelastrion").getAsString());
                    assertEquals("Glacium " + language.getValue().get(enabled ? 1 : 0), labels.get("command.erydon.swap.material.glacium").getAsString());
                    assertEquals("Latmion " + language.getValue().get(enabled ? 2 : 0), labels.get("command.erydon.swap.material.latmion").getAsString());
                }
                assertNull(pack.open(ResourceType.SERVER_DATA, id));
            }
        }
    }

    @Test void namesCoverIndependentPatternChoicesWithoutChangingMaterialsOrMetalDescriptors() throws Exception {
        var settings = defaults().withEnabled(true).withAllStones(Level.POLISHED)
                .withStone("aganite", new Stone(Level.HONED, Choice.MIRROR, Choice.POLISHED, Choice.MIRROR))
                .withStone("calacattum", new Stone(Level.POLISHED, Choice.INHERIT, Choice.HONED, Choice.INHERIT));
        var pack = HighPolishLabelsPack.append(ResourceType.CLIENT_RESOURCES, List.of(), settings).get(0);
        try (var stream = pack.open(ResourceType.CLIENT_RESOURCES, new Identifier("erydon", "lang/en_us.json")).get()) {
            var labels = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("Aganite Block (Honed)", labels.get("block.erydon.aganite_block").getAsString());
            assertEquals("Aganite Georgian Coping (Honed)", labels.get("block.erydon.aganite_coping_georgian").getAsString());
            assertEquals("Aganite Bronze Herringbone Block (Ganosis)", labels.get("block.erydon.aganite_herringbone_bronze_block").getAsString());
            assertEquals("Aganite Bronze Trim Inlay (Ganosis)", labels.get("block.erydon.aganite_trim_bronze_block").getAsString());
            assertEquals("Calacattum and Portorium Bronze Weave Block (Honed)", labels.get("block.erydon.calacattum_portorium_weave_bronze_block").getAsString());
            assertFalse(labels.has("block.erydon.aganite_aged_block"));
            assertFalse(labels.has("block.erydon.glazing_two_way_block"));
        }
        assertEquals("Aganite Bronze Polished Inlay (Ganosis)", HighPolishLabelsPack.finishedName(
                "Aganite Bronze Polished Inlay", "aganite_trim_bronze_block", "Ganosis"));
        for (String old : List.of("Honed", "Polished", "Mirror", "Ganosis", "Geschliffen", "Poliert", "Spiegelglanz", "Apomazado", "Pulido", "Espejo"))
            assertEquals("Aganite Georgian Coping (Ganosis)", HighPolishLabelsPack.finishedName(
                    "Aganite " + old + " Georgian Coping", "aganite_coping_georgian", "Ganosis"));
    }
}
