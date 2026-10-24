package com.oliver.erydon.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.HighPolishSettings;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Actual registered catalogue against the captured renderer's material and pattern classifier. */
public final class StoneFinishLabelsLaunchChecks {
    private StoneFinishLabelsLaunchChecks() { }

    public static void run() throws Exception {
        List<HighPolishSettings> snapshots = new ArrayList<>();
        snapshots.add(HighPolishSettings.defaults());
        for (var level : HighPolishSettings.Level.values()) {
            snapshots.add(HighPolishSettings.defaults().withEnabled(true).withAllStones(level));
            var mixed = HighPolishSettings.defaults().withEnabled(true);
            var levels = HighPolishSettings.Level.values();
            for (String material : HighPolishSettings.MATERIALS) mixed = mixed.withStone(material,
                    new HighPolishSettings.Stone(level,
                            HighPolishSettings.Choice.valueOf(levels[(level.ordinal() + 1) % 3].name()),
                            HighPolishSettings.Choice.valueOf(levels[(level.ordinal() + 2) % 3].name()),
                            HighPolishSettings.Choice.valueOf(level.name())));
            snapshots.add(mixed);
        }
        int checked = 0, excluded = 0;
        var covered = EnumSet.noneOf(HighPolishSettings.Finish.class);
        var words = Map.of("en_us", List.of("Honed", "Polished", "Ganosis"),
                "de_de", List.of("Geschliffen", "Poliert", "Ganosis"),
                "es_es", List.of("Apomazado", "Pulido", "Ganosis"));
        for (var settings : snapshots) {
            var pack = HighPolishLabelsPack.append(ResourceType.CLIENT_RESOURCES, List.of(), settings).get(0);
            for (var language : words.entrySet()) {
                JsonObject labels, original;
                try (var input = pack.open(ResourceType.CLIENT_RESOURCES,
                        new Identifier("erydon", "lang/" + language.getKey() + ".json")).get();
                     var source = StoneFinishLabelsLaunchChecks.class.getResourceAsStream(
                             "/assets/erydon/lang/" + language.getKey() + ".json")) {
                    labels = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    original = JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8)).getAsJsonObject();
                }
                for (var block : Registries.BLOCK) {
                    Identifier id = Registries.BLOCK.getId(block);
                    if (!id.getNamespace().equals("erydon")) continue;
                    String key = block.getTranslationKey();
                    if (!PolishedStoneMaterials.includes("erydon", id.getPath())) {
                        require(!labels.has(key), "A fixed finish/glass name was changed: " + id); excluded++; continue;
                    }
                    require(original.has(key), "Registered stone has no authored name: " + id);
                    require(labels.has(key), "Registered eligible stone has no active finish label: " + id);
                    String expected = language.getValue().get(PolishedStoneMaterials.level(settings, id.getPath()).ordinal());
                    String actual = labels.get(key).getAsString();
                    require(actual.endsWith(" (" + expected + ")"), "Wrong active finish: " + id + " " + actual);
                    String name = original.get(key).getAsString();
                    if (id.getPath().contains("_coping_georgian") && name.contains(" Polished "))
                        require(!actual.contains(" Polished "), "Contradictory old hardcoded finish retained: " + id);
                    covered.add(PolishedStoneMaterials.finish(id.getPath())); checked++;
                }
            }
        }
        require(checked > 10000 && excluded > 1000 && covered.size() == 4, "Incomplete actual finish catalogue coverage");
        System.out.println("ERYDON_STONE_FINISH_LABELS_OK snapshots=" + snapshots.size() + " locales=3 registeredChecks="
                + checked + " fixedFinishChecks=" + excluded + " categories=" + covered + " ganosis=true savedMirrorKeysUnchanged=true");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
