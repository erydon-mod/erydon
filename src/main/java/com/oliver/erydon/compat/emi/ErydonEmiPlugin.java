package com.oliver.erydon.compat.emi;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.item.ErydonBlockCategories;
import com.oliver.erydon.item.ErydonItemOrdering;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

public final class ErydonEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        int termCount = 0;
        List<ItemStack> stacks = ErydonItemOrdering.orderedBlockStacks();
        for (ItemStack stack : stacks) {
            List<String> terms = ErydonBlockCategories.searchTerms(ErydonItemOrdering.path(stack));
            for (String term : terms) {
                registry.addAlias(EmiStack.of(stack), Text.literal(term));
                termCount++;
            }
        }
        Erydon.LOGGER.info("[id-migration][emi] Registered {} search terms across {} ERYDON items.",
                termCount, stacks.size());
    }
}
