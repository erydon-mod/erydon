package com.oliver.erydon.mixin.client.compat.iris;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reuse the current shader pack's actual block-state map, including its normal and polished materials. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder", remap = false)
public interface CornicePartBlockContextAccessor {
    @Accessor("blockStateIds") Object2IntMap<BlockState> erydon$partBlockStateIds();
}
