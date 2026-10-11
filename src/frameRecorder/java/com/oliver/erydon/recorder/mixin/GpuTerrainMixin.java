package com.oliver.erydon.recorder.mixin;

import com.oliver.erydon.recorder.GpuStageRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
abstract class GpuTerrainMixin {
    @Inject(method = "renderLayer", at = @At("HEAD"), remap = false)
    private void start(CallbackInfo callback) { GpuStageRecorder.begin("terrain"); }
    @Inject(method = "renderLayer", at = @At("RETURN"), remap = false)
    private void end(CallbackInfo callback) { GpuStageRecorder.end("terrain"); }
}
