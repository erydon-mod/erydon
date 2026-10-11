package com.oliver.erydon.recorder.mixin;

import com.oliver.erydon.recorder.GpuStageRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class GpuCompositeMixin {
    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), remap = false)
    private void start(CallbackInfo callback) { GpuStageRecorder.begin("composite_final"); }
    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), remap = false)
    private void end(CallbackInfo callback) { GpuStageRecorder.end("composite_final"); }
}
