package com.oliver.erydon.mixin.client.compat.iris;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.IdMap", remap = false)
public interface HighPolishIdMapAccessor {
    @Invoker("<init>")
    static IdMap erydon$create(Path root, ShaderPackOptions options, Iterable<StringPair> environment) {
        throw new AssertionError("Mixin constructor invoker was not applied");
    }
}
