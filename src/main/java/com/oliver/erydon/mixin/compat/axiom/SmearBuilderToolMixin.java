package com.oliver.erydon.mixin.compat.axiom;

import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.buildertools.SmearBuilderTool")
public class SmearBuilderToolMixin {
    // In Axiom 5.3.0, smear's copyAir path lets source air bypass the destination replaceable check.
    // That causes smear to stop on solid blocks for non-air cells while still clearing them with air cells.
    @Redirect(
        method = "lambda$updateSmear$1",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;isAir()Z",
            ordinal = 1
        ),
        require = 0
    )
    private boolean erydon$smearAirMustRespectBlockedTargets(BlockState state) {
        return false;
    }
}
