package com.oliver.erydon.mixin.client.indium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import link.infra.indium.renderer.mesh.MutableQuadViewImpl;
import link.infra.indium.renderer.render.TerrainRenderContext;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TerrainRenderContext.class, remap = false)
abstract class TerrainRenderContextMixin {
    @Inject(method = "bufferQuad", at = @At("HEAD"), remap = false)
    private void erydon$keepTriangleVertexOrder(MutableQuadViewImpl quad, Material material, CallbackInfo ci) {
        // Fabric represents a triangle as A-B-C-C. Sodium's FLIP orientation
        // rotates that to B-C-C-A, so Iris sees a zero-area first triangle when
        // it generates shader tangents. Keep genuine quads unchanged.
        if (quad.x(2) == quad.x(3)
                && quad.y(2) == quad.y(3)
                && quad.z(2) == quad.z(3)) {
            quad.orientation(ModelQuadOrientation.NORMAL);
        }
    }

    @WrapOperation(method = "bufferQuad", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push("
                    + "[Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;)V"),
            remap = false, require = 0)
    private void erydon$columnCornerNormals(ChunkMeshBufferBuilder buffer, ChunkVertexEncoder.Vertex[] vertices,
                                           Material material, Operation<Void> original,
                                           MutableQuadViewImpl quad, Material ignoredMaterial) {
        if (!ColumnMeshNormalTransport.isColumn(quad.tag()) && !ColumnMeshNormalTransport.hasScope()) {
            original.call(buffer, vertices, material);
            return;
        }
        ModelQuadOrientation order = quad.orientation();
        int previous = ColumnMeshNormalTransport.push(quad, vertices, order.getVertexIndex(0), order.getVertexIndex(1),
                order.getVertexIndex(2), order.getVertexIndex(3));
        try {
            original.call(buffer, vertices, material);
        } finally {
            ColumnMeshNormalTransport.restore(previous);
        }
    }
}
