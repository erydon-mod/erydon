package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.client.pom.HighPolishShaderAdapter;
import net.irisshaders.iris.shaderpack.IdMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reject reserved-ID collisions before CU's dimension programs are adapted. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.IdMap", remap = false)
abstract class HighPolishIdMapMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 1)
    private void erydon$checkPolishIds(CallbackInfo ci) {
        IdMap map = (IdMap) (Object) this;
        HighPolishShaderAdapter.acceptMaterialIds(id -> map.getBlockProperties().containsKey(id)
                || map.getTagEntries().containsKey(id));
    }
}
