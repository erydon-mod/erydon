package com.oliver.erydon.recorder.mixin;

import com.oliver.erydon.recorder.FrameRecorder;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class CompletedFrameMixin {
    // The proven 1.20.1 frame boundary includes swapBuffers and frame limiting.
    @Inject(method = "render(Z)V", at = @At("RETURN"))
    private void completedFrame(boolean tick, CallbackInfo callback) {
        FrameRecorder.frame((MinecraftClient) (Object) this);
    }

    @Inject(method = {"setScreen", "setOverlay", "onWindowFocusChanged"}, at = @At("HEAD"))
    private void guardTransition(CallbackInfo callback) {
        FrameRecorder.interrupted();
    }
}
