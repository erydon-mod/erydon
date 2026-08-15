package com.oliver.erydon.item;

import com.oliver.erydon.Erydon;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {

    public static final Item EMBLEM = Registry.register(
            Registries.ITEM,
            new Identifier(Erydon.MOD_ID, "emblem"),
            new Item(new Item.Settings())
    );

    private ModItems() {}

    public static void register() {
        // forces class load (and therefore registration)
    }
}
