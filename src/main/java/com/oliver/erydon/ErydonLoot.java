package com.oliver.erydon;

import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.condition.SurvivesExplosionLootCondition;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class ErydonLoot {

    public static void init() {
        LootTableEvents.REPLACE.register((resourceManager, lootManager, id, original, source) -> {
            // Only touch our own tables
            // Assumes you have public static final String MOD_ID = "erydon" in Erydon.java
            if (!id.getNamespace().equals(Erydon.MOD_ID)) {
                return null;
            }

            // Only block loot tables: erydon:blocks/<block_name>
            if (!id.getPath().startsWith("blocks/")) {
                return null;
            }

            // If a real loot table exists (JSON or datapack), leave it alone
            if (original != LootTable.EMPTY) {
                return null;
            }

            // erydon:blocks/aganite_block -> erydon:aganite_block
            String blockPath = id.getPath().substring("blocks/".length());
            Identifier blockId = new Identifier(Erydon.MOD_ID, blockPath);
            Block block = Registries.BLOCK.get(blockId);

            // Unknown id? Don't crash, just skip.
            if (block == Blocks.AIR) {
                return null;
            }

            LootPool.Builder pool = LootPool.builder()
                    .rolls(ConstantLootNumberProvider.create(1.0F))
                    .with(ItemEntry.builder(block))
                    .conditionally(SurvivesExplosionLootCondition.builder());

            return LootTable.builder()
                    .type(LootContextTypes.BLOCK)
                    .pool(pool)
                    .build();
        });
    }

    private ErydonLoot() {
        // no instantiation
    }
}
