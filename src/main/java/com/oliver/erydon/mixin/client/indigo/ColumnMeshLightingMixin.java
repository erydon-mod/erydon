package com.oliver.erydon.mixin.client.indigo;

import com.oliver.erydon.client.model.ColumnMeshLighting;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractBlockRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractBlockRenderContext.class, remap = false)
abstract class ColumnMeshLightingMixin {
    @Inject(method = "shadeQuad", at = @At("HEAD"), remap = false, require = 0)
    private void erydon$beginColumnLight(MutableQuadViewImpl quad, boolean vanillaModel, boolean useAo,
                                        boolean emissive, CallbackInfo ci) {
        ColumnMeshLighting.beginQuad(quad, !useAo && !emissive);
    }

    @ModifyArg(method = "shadeQuad", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/helper/ColorHelper;maxBrightness(II)I"), index = 1, remap = false, require = 0)
    private int erydon$continuousColumnLight(int nativeFloor) {
        int sampled = ColumnMeshLighting.next();
        return sampled < 0 ? nativeFloor : sampled;
    }

    @Inject(method = "shadeQuad", at = @At("RETURN"), remap = false, require = 0)
    private void erydon$endColumnLight(MutableQuadViewImpl quad, boolean vanillaModel, boolean useAo,
                                      boolean emissive, CallbackInfo ci) {
        ColumnMeshLighting.endQuad(quad);
    }
}
