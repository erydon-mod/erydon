package com.oliver.erydon.compat.jei;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.item.ErydonBlockCategories;
import com.oliver.erydon.item.ErydonItemOrdering;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Predicate;

@JeiPlugin
public final class ErydonJeiPlugin implements IModPlugin {
    private static final Identifier PLUGIN_UID = new Identifier(Erydon.MOD_ID, "jei");

    @Override
    public Identifier getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerIngredientAliases(IIngredientAliasRegistration registration) {
        for (ItemStack stack : ErydonItemOrdering.orderedBlockStacks()) {
            List<String> aliases = ErydonBlockCategories.searchTerms(ErydonItemOrdering.path(stack));
            if (!aliases.isEmpty()) {
                registration.addAliases(VanillaTypes.ITEM_STACK, stack, aliases);
            }
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        addInfo(registration, ErydonBlockCategories::isStairs,
                "ERYDON stairs include normal, shallow, and large spiral variants. Search for steps, staircase, shallow steps, or spiral staircase to find them quickly.");
        addInfo(registration, ErydonBlockCategories::isLayer,
                "Layers are thin decorative surface blocks for trim, cladding, and panel work.");
        addInfo(registration, ErydonBlockCategories::isSliceOrPost,
                "Slices and posts are small shape pieces for fine detail work. Search for thin, slice, eighth, beam, or rod.");
        addInfo(registration, ErydonBlockCategories::isSlope,
                "Slopes are angled shape blocks for ramps, roofs, and shaped trim. Lower, upper, steep, and vertical variants cover different edges.");
        addInfo(registration, ErydonBlockCategories::isArchitecture,
                "Architecture blocks include arches, columns, chimneys, cornices, surrounds, and windows.");
        addInfo(registration, ErydonBlockCategories::isGlass,
                "Glass sets include glazing and diaphanes blocks for transparent panes, framed glass, and lit glass details.");
        addInfo(registration, ErydonBlockCategories::isWeave,
                "Weave blocks mix two texture families. In ERYDON ordering, each weave belongs under the first texture name in the pair.");
        addInfo(registration, path -> ErydonBlockCategories.isCover(path) || ErydonBlockCategories.isCeiling(path),
                "Covers and coffered ceilings are decorative panel blocks. Use /recalc if a connected cluster needs rebuilding after edits.");
    }

    private static void addInfo(IRecipeRegistration registration, Predicate<String> pathPredicate, String description) {
        List<ItemStack> stacks = ErydonItemOrdering.orderedBlockStacksMatching(pathPredicate);
        if (!stacks.isEmpty()) {
            registration.addItemStackInfo(stacks, Text.literal(description));
        }
    }
}
