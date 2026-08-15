package com.oliver.erydon.mixin.compat.worldedit;

import com.google.common.collect.Table;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BlockState.class, remap = false)
public interface WorldEditBlockStateAccessor {
    @Invoker("<init>")
    static BlockState erydon$create(BlockType blockType) {
        throw new AssertionError();
    }

    @Invoker("setState")
    BlockState erydon$setState(Property<?> property, Object value);

    @Accessor("states")
    void erydon$setStates(Table<Property<?>, Object, BlockState> states);
}
