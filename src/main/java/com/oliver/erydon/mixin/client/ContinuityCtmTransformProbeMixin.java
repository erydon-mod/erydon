package com.oliver.erydon.mixin.client;

import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.pepperbell.continuity.client.model.CtmBakedModel$CtmQuadTransform", remap = false)
public abstract class ContinuityCtmTransformProbeMixin {
    @Inject(method = "transform", at = @At("HEAD"), remap = false)
    private void erydon$countContinuityTransform(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
        ErydonLoadProfiler.continuityTransformCalled();
    }
}
