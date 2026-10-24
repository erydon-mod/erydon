package com.oliver.erydon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.block.StairsSpiralLargeBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.BlockCollisionSpliterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Admit the single spiral pivot when its cached footprint reaches a diagonal collision cell. */
@Mixin(BlockCollisionSpliterator.class)
abstract class SpiralStairCollisionIteratorMixin {
    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;isOf(Lnet/minecraft/block/Block;)Z"))
    private boolean erydon$admitSpiralPivot(BlockState state, Block tested, Operation<Boolean> original) {
        return original.call(state, tested) || tested == Blocks.MOVING_PISTON
                && state.getBlock() instanceof StairsSpiralLargeBlock
                && state.get(StairsSpiralLargeBlock.PART) == StairsSpiralLargeBlock.Part.B;
    }
}
