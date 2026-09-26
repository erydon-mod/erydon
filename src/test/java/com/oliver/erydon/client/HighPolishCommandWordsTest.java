package com.oliver.erydon.client;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.oliver.erydon.HighPolishSettings;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishCommandWordsTest {
    @Test void presentsAllThreeFinishesAndPreservesCompletionRangeAndRoughNames() {
        var range = StringRange.between(12, 16);
        var entries = List.of(new Suggestion(range, "\"Kelastrion Honed\""),
                new Suggestion(range, "\"Latmion Honed\""), new Suggestion(range, "\"Psamatheon Honed\""),
                new Suggestion(range, "\"Glacium Polished\""), new Suggestion(range, "\"Latmion Hewn\""));
        var original = new Suggestions(range, entries);
        var off = HighPolishSettings.defaults();
        var honed = HighPolishCommandWords.present(original, off);
        assertEquals("\"Glacium Honed\"", honed.getList().get(3).getText());
        var on = off.withEnabled(true)
                .withStone("latmion", new HighPolishSettings.Stone(HighPolishSettings.Level.HONED,
                        HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT))
                .withStone("glacium", new HighPolishSettings.Stone(HighPolishSettings.Level.POLISHED,
                        HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT, HighPolishSettings.Choice.INHERIT));
        var result = HighPolishCommandWords.present(original, on);
        assertEquals(List.of("\"Kelastrion Mirror\"", "\"Latmion Honed\"", "\"Psamatheon Mirror\"",
                "\"Glacium Polished\"", "\"Latmion Hewn\""), result.getList().stream().map(Suggestion::getText).toList());
        result.getList().forEach(entry -> assertEquals(range, entry.getRange()));
        assertEquals("\"Kelastrion Honed\"", original.getList().get(0).getText());
        assertEquals(result, HighPolishCommandWords.present(result, on));
    }
}
