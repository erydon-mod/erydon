package com.oliver.erydon.mixin.client.compat.axiom;

import com.oliver.erydon.block.HorizontalSliceBlock;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Axiom's generic vertical flip does not know the slices' custom top property. */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.utils.BlockHelper", remap = false)
public abstract class SliceFlipMixin {
    // Only handle the fallback: preserve Axiom's ignore list and explicit custom mappings.
    @Inject(method = "flipY", at = @At("TAIL"), cancellable = true, require = 0, remap = false)
    private static void erydon$flipHorizontalSlice(BlockState state, CallbackInfoReturnable<BlockState> cir) {
        if (state.getBlock() instanceof HorizontalSliceBlock) {
            cir.setReturnValue(state.cycle(HorizontalSliceBlock.TOP));
        }
    }
}
