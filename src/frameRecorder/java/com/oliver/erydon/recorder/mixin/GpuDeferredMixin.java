package com.oliver.erydon.recorder.mixin;

import com.oliver.erydon.recorder.GpuStageRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class GpuDeferredMixin {
    @Inject(method = "beginTranslucents", at = @At("HEAD"), remap = false)
    private void start(CallbackInfo callback) { GpuStageRecorder.begin("deferred_depth_copy"); }
    @Inject(method = "beginTranslucents", at = @At("RETURN"), remap = false)
    private void end(CallbackInfo callback) { GpuStageRecorder.end("deferred_depth_copy"); }
}
