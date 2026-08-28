package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.client.pom.ErydonCuPomRuntimeState;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Confirms the adapted terrain path only after every Sodium pass links. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides",
        remap = false)
public abstract class IrisChunkProgramOverridesMixin {
    @Inject(
            method = "createShaders(Lnet/irisshaders/iris/pipeline/SodiumTerrainPipeline;"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void erydon$beginTerrainProgramCompilation(
            SodiumTerrainPipeline pipeline,
            ChunkVertexType vertexType,
            CallbackInfo ci
    ) {
        if (pipeline != null) {
            ErydonCuPomRuntimeState.beginTerrainProgramCompilation();
        }
    }

    @Inject(
            method = "createShaders(Lnet/irisshaders/iris/pipeline/SodiumTerrainPipeline;"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void erydon$confirmTerrainProgramsCompiled(
            SodiumTerrainPipeline pipeline,
            ChunkVertexType vertexType,
            CallbackInfo ci
    ) {
        if (pipeline != null) {
            ErydonCuPomRuntimeState.confirmTerrainProgramsCompiled();
        }
    }
}
