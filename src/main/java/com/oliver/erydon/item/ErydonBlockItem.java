package com.oliver.erydon.item;

import com.oliver.erydon.Erydon;
import net.minecraft.block.Block;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ErydonBlockItem extends BlockItem {
    public ErydonBlockItem(Block block, Item.Settings settings) {
        super(block, settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);

        if (world != null || !context.isCreative()) {
            return;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null || !Erydon.MOD_ID.equals(itemId.getNamespace())) {
            return;
        }

        for (String searchTerm : ErydonBlockCategories.searchTerms(itemId.getPath())) {
            tooltip.add(Text.literal(searchTerm));
        }
    }
}
