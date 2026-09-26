package com.oliver.erydon.client;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.oliver.erydon.HighPolishSettings;

/** Presentation only: both old and new spellings still resolve to the same server family. */
public final class HighPolishCommandWords {
    private HighPolishCommandWords() { }
    public static Suggestions present(Suggestions suggestions, HighPolishSettings settings) {
        var entries = suggestions.getList().stream().map(suggestion -> {
            String text = suggestion.getText();
            for (String material : HighPolishSettings.MATERIALS) {
                String name = Character.toUpperCase(material.charAt(0)) + material.substring(1);
                if (text.equals('"' + name + " Honed\"") || text.equals('"' + name + " Polished\"")
                        || text.equals('"' + name + " Mirror\"")) {
                    String key = settings.level(material, HighPolishSettings.Finish.PLAIN).key();
                    String finish = Character.toUpperCase(key.charAt(0)) + key.substring(1);
                    return new Suggestion(suggestion.getRange(), '"' + name + " " + finish + '"', suggestion.getTooltip());
                }
            }
            return suggestion;
        }).toList();
        return new Suggestions(suggestions.getRange(), entries);
    }
}
