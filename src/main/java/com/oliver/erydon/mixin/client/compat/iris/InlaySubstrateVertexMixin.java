package com.oliver.erydon.mixin.client.compat.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.oliver.erydon.client.pom.InlaySubstrateTransport;
import com.oliver.erydon.client.pom.CornicePartMaterialTransport;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Iris's verified vertex format while preserving inlay payloads and shared-cell part identities. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex", remap = false)
public abstract class InlaySubstrateVertexMixin {
    @Shadow private BlockContextHolder contextHolder;
    @ModifyReturnValue(method = "write(JLme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;"
            + "[Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J",
            at = @At("RETURN"), remap = false, require = 0)
    private long erydon$writeInlaySubstrate(long result, long pointer, Material material,
                                           ChunkVertexEncoder.Vertex[] vertices, int section) {
        int record = InlaySubstrateTransport.currentRecord();
        var part = CornicePartMaterialTransport.current();
        if ((record < 0 && part == null) || vertices.length != 4) return result;
        // The guard verifies this exact bytecode layout before any payload can be active.
        long start = result - 4L * 40;
        if (record >= 0) {
            short encoded = InlaySubstrateTransport.encodedRenderType(record, InlaySubstrateTransport.currentRibbon());
            for (int vertex = 0; vertex < 4; vertex++) MemoryUtil.memPutShort(start + vertex * 40L + 34, encoded);
        }
        if (part != null && InlaySubstrateTransport.rendererSupported()) {
            var ids = contextHolder == null ? null
                    : ((CornicePartBlockContextAccessor) contextHolder).erydon$partBlockStateIds();
            if (ids != null) {
                short materialId = (short) ids.getOrDefault(part, -1);
                for (int vertex = 0; vertex < 4; vertex++) {
                    MemoryUtil.memPutShort(start + vertex * 40L + 32, materialId);
                    MemoryUtil.memPutByte(start + vertex * 40L + 39, (byte) part.getLuminance());
                }
            }
        }
        return result;
    }
}
