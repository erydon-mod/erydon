package com.oliver.erydon.mixin.client.compat.axiom;

import com.oliver.erydon.Erydon;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Axiom's ERYDON palette icons on the same item-rendering path used by
 * the Creative inventory and item viewers. Axiom still owns and bounds its
 * icon cache; this only changes which renderer supplies a newly cached icon.
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.render.BlockRenderCache", remap = false)
public abstract class BlockRenderCacheMixin {
    @Inject(
            method = "shouldRenderAsItem",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void erydon$useInventoryIcon(
            BlockState state,
            ItemStack stack,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (stack.isEmpty()) {
            return;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId != null && Erydon.MOD_ID.equals(itemId.getNamespace())) {
            callbackInfo.setReturnValue(true);
        }
    }
}
