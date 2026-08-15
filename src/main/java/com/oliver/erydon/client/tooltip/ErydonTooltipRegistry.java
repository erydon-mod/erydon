package com.oliver.erydon.client.tooltip;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.item.ErydonMaterialSources;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.util.ErydonIdNaming;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

public final class ErydonTooltipRegistry {
    private static final int MAX_NUMBERED_LINES = 32;
    private static final String EXACT_KEY_PREFIX = "tooltip." + Erydon.MOD_ID + ".item.";
    private static final String FAMILY_KEY_PREFIX = "tooltip." + Erydon.MOD_ID + ".family.";

    private static final List<TooltipFamily> FAMILIES = List.of(
            family("layer_multiface", "_layer_multiface"),
            family("layer_vertical", "_layer_vertical"),
            family("vertical_slice", "_vertical_slice"),
            family("horizontal_slice", "_horizontal_slice"),
            family("post", "_post"),
            family("stairs_spiral_large", "_stairs_spiral_large"),
            family("stairs_shallow_bottom", "_stairs_shallow_bottom"),
            family("stairs_shallow_top", "_stairs_shallow_top"),
            family("slope_vertical_shallow_broad", "_slope_vertical_shallow_broad"),
            family("slope_vertical_shallow_narrow", "_slope_vertical_shallow_narrow"),
            family("slope_vertical", "_slope_vertical"),
            family("slope_steep_lower", "_slope_steep_lower"),
            family("slope_steep_upper", "_slope_steep_upper"),
            family("slope_shallow_lower", "_slope_shallow_lower"),
            family("slope_shallow_upper", "_slope_shallow_upper"),
            family("window_french_georgian", "_window_french_georgian"),
            family("window_arch", "_window_arch"),
            family("arch_romanesque", "_arch_romanesque"),
            family("arch_modern", "_arch_modern"),
            family("arch_gothic", "_arch_gothic"),
            family("alcove_georgian", "_alcove_georgian"),
            family("alcove_gothic", "_alcove_gothic"),
            family("column_circular", "_column_circular"),
            family("column_gothic", "_column_gothic"),
            family("column_square", "_column_square"),
            family("chimney_circular", "_chimney_circular"),
            family("cornice_gothic", "_cornice_gothic"),
            family("cornice_guilloche", "_cornice_guilloche"),
            family("cornice_georgian", "_cornice_georgian"),
            family("cornice_modern", "_cornice_modern"),
            familyContains("cover", "cover_"),
            family("surround_guilloche", "_surround_guilloche"),
            family("surround_gothic_ornate", "_surround_gothic_ornate"),
            family("surround_georgian", "_surround_georgian"),
            family("surround_modern", "_surround_modern"),
            familyContains("ceiling_coffered", "ceiling_coffered"),
            family("light_wall", "_light_wall"),
            family("light_modern", "_light_modern"),
            family("layer", "_layer"),
            family("slope", "_slope")
    );

    private ErydonTooltipRegistry() {
    }

    public static List<Text> getTooltipLines(ItemStack stack) {
        return resolveLines(stack, true);
    }

    public static List<Text> getDescriptionLines(ItemStack stack) {
        return resolveLines(stack, false);
    }

    public static List<TooltipEntry> getTooltipEntries() {
        return StreamSupport.stream(Registries.ITEM.spliterator(), false)
                .filter(item -> item instanceof BlockItem)
                .map(Item::getDefaultStack)
                .map(stack -> new TooltipEntry(stack, getDescriptionLines(stack)))
                .filter(entry -> !entry.lines().isEmpty())
                .toList();
    }

    private static List<Text> resolveLines(ItemStack stack, boolean styleAsTooltip) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return List.of();
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null || !Objects.equals(itemId.getNamespace(), Erydon.MOD_ID)) {
            return List.of();
        }

        List<String> rawLines = findRawLines(itemId);
        String materialSource = findMaterialSource(itemId.getPath());
        if (rawLines.isEmpty() && materialSource == null) {
            return List.of();
        }

        List<Text> lines = new ArrayList<>(rawLines.size() + (materialSource == null ? 0 : 1));
        for (String rawLine : rawLines) {
            MutableText line = Text.literal(rawLine);
            if (styleAsTooltip && !rawLine.isEmpty()) {
                line = line.formatted(Formatting.GRAY);
            }
            lines.add(line);
        }

        if (materialSource != null) {
            MutableText materialLine = Text.literal("Based on: " + materialSource);
            if (styleAsTooltip) {
                materialLine = materialLine.formatted(Formatting.DARK_AQUA, Formatting.ITALIC);
            }
            lines.add(materialLine);
        }
        return List.copyOf(lines);
    }

    private static List<String> findRawLines(Identifier itemId) {
        List<String> exactLines = readLines(EXACT_KEY_PREFIX + itemId.getPath());
        if (!exactLines.isEmpty()) {
            return exactLines;
        }

        String path = ErydonIdMigration.legacyResourcePath(itemId.getPath());
        if (!path.equals(itemId.getPath())) {
            exactLines = readLines(EXACT_KEY_PREFIX + path);
            if (!exactLines.isEmpty()) {
                return exactLines;
            }
        }
        for (TooltipFamily family : FAMILIES) {
            if (family.matcher().test(path)) {
                List<String> familyLines = readLines(FAMILY_KEY_PREFIX + family.id());
                if (!familyLines.isEmpty()) {
                    return familyLines;
                }
            }
        }

        return List.of();
    }

    private static List<String> readLines(String baseKey) {
        if (I18n.hasTranslation(baseKey)) {
            return splitLines(I18n.translate(baseKey));
        }

        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= MAX_NUMBERED_LINES; index++) {
            String numberedKey = baseKey + "." + index;
            if (!I18n.hasTranslation(numberedKey)) {
                break;
            }
            lines.addAll(splitLines(I18n.translate(numberedKey)));
        }
        return List.copyOf(lines);
    }

    private static List<String> splitLines(String value) {
        return Arrays.asList(value.split("\\R", -1));
    }

    private static TooltipFamily family(String id, String suffix) {
        return new TooltipFamily(id, path -> matchesSuffix(path, suffix));
    }

    private static TooltipFamily familyContains(String id, String fragment) {
        return new TooltipFamily(id, path -> path.contains(fragment));
    }

    private static boolean matchesSuffix(String path, String suffix) {
        return ErydonIdNaming.matchesForm(path, suffix);
    }

    private static String findMaterialSource(String path) {
        return ErydonMaterialSources.findSourceName(path);
    }

    private record TooltipFamily(String id, Predicate<String> matcher) {
    }

    public record TooltipEntry(ItemStack stack, List<Text> lines) {
    }
}
