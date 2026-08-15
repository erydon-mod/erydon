package com.oliver.erydon.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;

public final class BlockStateTagHelper {
    private static final String BLOCK_STATE_TAG_KEY = "BlockStateTag";

    private BlockStateTagHelper() {
    }

    public static boolean hasBlockStateTag(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return false;
        }

        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.contains(BLOCK_STATE_TAG_KEY, NbtElement.COMPOUND_TYPE);
    }

    public static BlockState applyBlockStateTag(BlockState state, ItemStack stack) {
        if (!hasBlockStateTag(stack)) {
            return state;
        }

        NbtCompound blockStateTag = stack.getNbt().getCompound(BLOCK_STATE_TAG_KEY);
        StateManager<Block, BlockState> stateManager = state.getBlock().getStateManager();
        BlockState result = state;

        for (String key : blockStateTag.getKeys()) {
            Property<?> property = stateManager.getProperty(key);
            if (property == null || !result.contains(property)) {
                continue;
            }

            result = applyProperty(result, property, blockStateTag.getString(key));
        }

        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<?> property, String serializedValue) {
        Property<T> typedProperty = (Property<T>) property;
        return typedProperty.parse(serializedValue)
                .map(value -> state.with(typedProperty, value))
                .orElse(state);
    }
}
