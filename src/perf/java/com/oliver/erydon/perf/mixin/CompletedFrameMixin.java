package com.oliver.erydon.perf.mixin;

import com.oliver.erydon.perf.PerformanceCapture;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class CompletedFrameMixin {
    // Verified against local Yarn 1.20.1+build.10: render(Z)V contains swapBuffers.
    // Consecutive returns also include frame limiting and intervening tick/stall time.
    @Inject(method = "render(Z)V", at = @At("RETURN"))
    private void completedFrame(boolean tick, CallbackInfo info) {
        PerformanceCapture.frame((MinecraftClient) (Object) this);
    }
}
