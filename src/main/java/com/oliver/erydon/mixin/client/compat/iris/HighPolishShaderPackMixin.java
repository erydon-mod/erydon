package com.oliver.erydon.mixin.client.compat.iris;

import com.google.common.collect.ImmutableList;
import com.oliver.erydon.client.pom.HighPolishShaderAdapter;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Map;

/** Parse the existing ID map once, before Iris eagerly reads its base shaders. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
abstract class HighPolishShaderPackMixin {
    @Shadow @Final private ShaderPackOptions shaderPackOptions;
    @Unique private IdMap erydon$earlyIdMap;

    @Inject(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V",
            at = @At(value = "NEW", target = "net/irisshaders/iris/shaderpack/programs/ProgramSet"),
            remap = false, require = 1)
    private void erydon$checkIdsBeforePrograms(Path root, Map<String, String> changedOptions,
                                              ImmutableList<StringPair> environment, CallbackInfo ci) {
        if (!HighPolishShaderAdapter.requested()) return;
        IdMap ids = HighPolishIdMapAccessor.erydon$create(root, shaderPackOptions, environment);
        erydon$earlyIdMap = ids;
        HighPolishShaderAdapter.acceptMaterialIds(id -> ids.getBlockProperties().containsKey(id)
                || ids.getTagEntries().containsKey(id));
    }

    @Redirect(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V",
            at = @At(value = "NEW", target = "net/irisshaders/iris/shaderpack/IdMap"),
            remap = false, require = 1)
    private IdMap erydon$reuseCheckedIds(Path root, ShaderPackOptions options, Iterable<StringPair> environment) {
        IdMap checked = erydon$earlyIdMap;
        erydon$earlyIdMap = null;
        return checked != null ? checked : HighPolishIdMapAccessor.erydon$create(root, options, environment);
    }
}
