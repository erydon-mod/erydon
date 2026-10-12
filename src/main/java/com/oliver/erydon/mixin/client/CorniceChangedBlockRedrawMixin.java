package com.oliver.erydon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.client.CorniceClientRedraw;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Changed owner/support packets must invalidate peers after the new scene data is actually stored. */
@Mixin(World.class)
public abstract class CorniceChangedBlockRedrawMixin {
    @WrapOperation(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;onBlockChanged(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V"))
    private void erydon$redrawAppliedCorniceChange(World world, BlockPos pos, BlockState oldState, BlockState newState,
                                                  Operation<Void> original) {
        original.call(world, pos, oldState, newState);
        CorniceClientRedraw.afterChange(world, pos, oldState, newState);
    }
}
