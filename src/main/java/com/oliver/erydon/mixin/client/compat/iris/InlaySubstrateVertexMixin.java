package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.client.pom.InlaySubstrateTransport;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Iris's existing vertex format; only supported inlay draws replace its block render-type short. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex", remap = false)
public abstract class InlaySubstrateVertexMixin {
    @Inject(method = "write(JLme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;"
            + "[Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J",
            at = @At("RETURN"), remap = false, require = 0)
    private void erydon$writeInlaySubstrate(long pointer, Material material, ChunkVertexEncoder.Vertex[] vertices,
                                           int section, CallbackInfoReturnable<Long> result) {
        int record = InlaySubstrateTransport.currentRecord();
        if (record < 0 || vertices.length != 4) return;
        // The guard verifies this exact bytecode layout before any payload can be active.
        long start = result.getReturnValueJ() - 4L * 40;
        short encoded = InlaySubstrateTransport.encodedRenderType(record, InlaySubstrateTransport.currentRibbon());
        for (int vertex = 0; vertex < 4; vertex++) MemoryUtil.memPutShort(start + vertex * 40L + 34, encoded);
    }
}
