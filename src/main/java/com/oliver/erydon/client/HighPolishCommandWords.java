package com.oliver.erydon.client;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.oliver.erydon.HighPolishSettings;
import java.util.List;

/** Presentation only: both old and new spellings still resolve to the same server family. */
public final class HighPolishCommandWords {
    private HighPolishCommandWords() { }
    public static Suggestions present(Suggestions suggestions, HighPolishSettings settings) {
        if (!settings.enabled()) return suggestions;
        var entries = suggestions.getList().stream().map(suggestion -> {
            String text = suggestion.getText();
            for (String material : List.of("kelastrion", "latmion", "psamatheon")) {
                String name = Character.toUpperCase(material.charAt(0)) + material.substring(1);
                if (settings.enables(material, HighPolishSettings.Finish.PLAIN)
                        && text.equals('"' + name + " Honed\"")) {
                    return new Suggestion(suggestion.getRange(), '"' + name + " Polished\"", suggestion.getTooltip());
                }
            }
            return suggestion;
        }).toList();
        return new Suggestions(suggestions.getRange(), entries);
    }
}
