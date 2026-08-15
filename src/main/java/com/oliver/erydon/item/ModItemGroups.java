package com.oliver.erydon.item;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;


public final class ModItemGroups {

    public static final ItemGroup ERYDON = Registry.register(
            Registries.ITEM_GROUP,
            new Identifier(Erydon.MOD_ID, "erydon"),
            FabricItemGroup.builder()
                    .icon(() -> new ItemStack(ModItems.EMBLEM))
                    .displayName(Text.translatable("itemGroup.erydon"))
                    .entries((context, entries) -> {
                        ErydonItemOrdering.orderedBlockItems().forEach(entries::add);
                    })
                    .build()
    );

    private ModItemGroups() {}

    public static void register() {
        // Calling this ensures the class loads, which triggers the static registration above.
    }
}
