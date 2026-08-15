package com.oliver.erydon.client.migration;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.item.ErydonBlockCategories;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/** Shared old/new search vocabulary for optional item browsers and Axiom. */
public final class IdAliasSearchVocabulary {
    private IdAliasSearchVocabulary() {
    }

    public static List<String> terms(ItemStack stack) {
        Identifier canonicalId = Registries.ITEM.getId(stack.getItem());
        return terms(canonicalId);
    }

    public static List<String> terms(Identifier canonicalId) {
        if (canonicalId == null || !Erydon.MOD_ID.equals(canonicalId.getNamespace())) {
            return List.of();
        }
        return ErydonBlockCategories.searchTerms(canonicalId.getPath());
    }

    public static String appendSearchTerms(String existing, ItemStack stack, boolean removeWhitespace) {
        return appendTerms(existing, terms(stack), removeWhitespace);
    }

    public static String appendTerms(String existing, List<String> terms, boolean removeWhitespace) {
        if (terms.isEmpty()) {
            return existing;
        }

        StringBuilder builder = new StringBuilder(existing == null ? "" : existing);
        for (String term : terms) {
            builder.append('\u0000').append(normalize(term, removeWhitespace));
        }
        return builder.toString();
    }

    static String normalize(String value, boolean removeWhitespace) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (!removeWhitespace) {
            return lowered;
        }

        StringBuilder builder = new StringBuilder(lowered.length());
        for (int index = 0; index < lowered.length(); index++) {
            char character = lowered.charAt(index);
            if (!Character.isWhitespace(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
