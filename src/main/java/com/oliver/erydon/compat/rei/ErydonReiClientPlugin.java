package com.oliver.erydon.compat.rei;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.tooltip.ErydonTooltipDelay;
import com.oliver.erydon.item.ErydonBlockCategories;
import com.oliver.erydon.item.ErydonItemOrdering;
import com.oliver.erydon.item.ErydonMaterialSources;
import me.shedaniel.rei.api.client.entry.renderer.EntryRendererRegistry;
import me.shedaniel.rei.api.client.gui.widgets.Tooltip;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.entry.CollapsibleEntryRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.subsets.SubsetsRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@SuppressWarnings("removal")
public final class ErydonReiClientPlugin implements REIClientPlugin {
    @Override
    public void registerEntries(EntryRegistry registry) {
        registry.removeEntryIf(entry -> {
            Object value = entry.getValue();
            return value instanceof ItemStack stack && ErydonItemOrdering.isErydonBlockItem(stack);
        });

        List<EntryStack<?>> orderedEntries = ErydonItemOrdering.orderedBlockStacks().stream()
                .<EntryStack<?>>map(EntryStacks::of)
                .toList();
        registry.addEntries(orderedEntries);
    }

    @Override
    public void registerCollapsibleEntries(CollapsibleEntryRegistry registry) {
        registerGroup(registry, "slabs", "ERYDON Slabs", ErydonBlockCategories::isSlab);
        registerGroup(registry, "stairs", "ERYDON Stairs", ErydonBlockCategories::isStairs);
        registerGroup(registry, "walls", "ERYDON Walls", ErydonBlockCategories::isWall);
        registerGroup(registry, "layers", "ERYDON Layers", ErydonBlockCategories::isLayer);
        registerGroup(registry, "slices_and_posts", "ERYDON Slices and Posts", ErydonBlockCategories::isSliceOrPost);
        registerGroup(registry, "slopes", "ERYDON Slopes", ErydonBlockCategories::isSlope);
        registerGroup(registry, "arches", "ERYDON Arches", ErydonBlockCategories::isArch);
        registerGroup(registry, "columns", "ERYDON Columns", ErydonBlockCategories::isColumn);
        registerGroup(registry, "chimneys", "ERYDON Chimneys", ErydonBlockCategories::isChimney);
        registerGroup(registry, "cornices", "ERYDON Cornices", ErydonBlockCategories::isCornice);
        registerGroup(registry, "surrounds", "ERYDON Surrounds", ErydonBlockCategories::isSurround);
        registerGroup(registry, "windows", "ERYDON Windows", ErydonBlockCategories::isWindow);
        registerGroup(registry, "lights", "ERYDON Lights", ErydonBlockCategories::isLight);
        registerGroup(registry, "covers_and_ceilings", "ERYDON Covers and Ceilings",
                path -> ErydonBlockCategories.isCover(path) || ErydonBlockCategories.isCeiling(path));
    }

    @Override
    public void registerSubsets(SubsetsRegistry registry) {
        for (String material : ErydonMaterialSources.materialPrefixes()) {
            registerSubset(registry, "ERYDON/Materials/" + titleCase(material),
                    path -> ErydonBlockCategories.belongsToMaterial(path, material));
        }

        registerSubset(registry, "ERYDON/Shapes/Slabs", ErydonBlockCategories::isSlab);
        registerSubset(registry, "ERYDON/Shapes/Stairs", ErydonBlockCategories::isStairs);
        registerSubset(registry, "ERYDON/Shapes/Walls", ErydonBlockCategories::isWall);
        registerSubset(registry, "ERYDON/Shapes/Layers", ErydonBlockCategories::isLayer);
        registerSubset(registry, "ERYDON/Shapes/Slices and Posts", ErydonBlockCategories::isSliceOrPost);
        registerSubset(registry, "ERYDON/Shapes/Slopes", ErydonBlockCategories::isSlope);
        registerSubset(registry, "ERYDON/Architecture/Arches", ErydonBlockCategories::isArch);
        registerSubset(registry, "ERYDON/Architecture/Columns", ErydonBlockCategories::isColumn);
        registerSubset(registry, "ERYDON/Architecture/Chimneys", ErydonBlockCategories::isChimney);
        registerSubset(registry, "ERYDON/Architecture/Cornices", ErydonBlockCategories::isCornice);
        registerSubset(registry, "ERYDON/Architecture/Surrounds", ErydonBlockCategories::isSurround);
        registerSubset(registry, "ERYDON/Architecture/Windows", ErydonBlockCategories::isWindow);
        registerSubset(registry, "ERYDON/Utility/Lights", ErydonBlockCategories::isLight);
        registerSubset(registry, "ERYDON/Utility/Covers", ErydonBlockCategories::isCover);
        registerSubset(registry, "ERYDON/Utility/Ceilings", ErydonBlockCategories::isCeiling);
        registerSubset(registry, "ERYDON/Textures/Glass", ErydonBlockCategories::isGlass);
        registerSubset(registry, "ERYDON/Textures/Weaves", ErydonBlockCategories::isWeave);
        registerSubset(registry, "ERYDON/Textures/Decorated Blocks", ErydonBlockCategories::isDecoratedBlock);
    }

    @Override
    public void registerEntryRenderers(EntryRendererRegistry registry) {
        registry.transformTooltip(VanillaEntryTypes.ITEM, (entry, mouse, tooltip) -> {
            ItemStack stack = entry.castValue();
            List<Text> lines = ErydonTooltipDelay.getTooltipLines(stack);
            if (lines.isEmpty()) {
                return tooltip;
            }

            Tooltip resolvedTooltip = tooltip == null
                    ? Tooltip.create(mouse, lines).withContextStack(entry.cast())
                    : tooltip.copy();

            Set<String> existingLines = new HashSet<>();
            for (Tooltip.Entry tooltipEntry : resolvedTooltip.entries()) {
                if (tooltipEntry.isText()) {
                    existingLines.add(tooltipEntry.getAsText().getString());
                }
            }

            for (Text line : lines) {
                if (existingLines.add(line.getString())) {
                    resolvedTooltip.add(line);
                }
            }

            return resolvedTooltip;
        });
    }

    private static void registerGroup(CollapsibleEntryRegistry registry, String id, String name,
                                      Predicate<String> pathPredicate) {
        List<EntryStack<?>> entries = entryStacks(pathPredicate);
        if (entries.size() > 1) {
            registry.group(new Identifier(Erydon.MOD_ID, "rei_group_" + id), Text.literal(name), entries);
        }
    }

    private static void registerSubset(SubsetsRegistry registry, String path, Predicate<String> pathPredicate) {
        List<EntryStack<?>> entries = entryStacks(pathPredicate);
        if (!entries.isEmpty()) {
            registry.registerPathEntries(path, entries);
        }
    }

    private static List<EntryStack<?>> entryStacks(Predicate<String> pathPredicate) {
        return ErydonItemOrdering.orderedBlockStacksMatching(pathPredicate).stream()
                .<EntryStack<?>>map(EntryStacks::of)
                .toList();
    }

    private static String titleCase(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean nextUpper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_') {
                builder.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                builder.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
