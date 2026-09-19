package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.ErydonHighPolish;
import com.oliver.erydon.client.pom.HighPolishShaderAdapter;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shader-load-only classification. Both model formats use this same state map. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping", remap = false)
abstract class HighPolishBlockMaterialMixin {
    @Inject(method = "createBlockStateIdMap", at = @At("RETURN"), remap = false, require = 1)
    private static void erydon$assignOpaquePolish(CallbackInfoReturnable<Object2IntMap<BlockState>> cir) {
        if (!HighPolishShaderAdapter.ready()) return;
        Object2IntMap<BlockState> ids = cir.getReturnValue();
        // Refuse a colliding shader mapping rather than changing another mod's material.
        if (ids.containsValue(HighPolishShaderAdapter.SOLID_ID)
                || ids.containsValue(HighPolishShaderAdapter.SHAPE_ID)
                || ids.containsValue(HighPolishShaderAdapter.SPIRAL_ID)) {
            Erydon.LOGGER.warn("[erydon] Opaque high polish skipped: reserved shader material ID collision.");
            return;
        }
        int count = 0;
        for (var block : Registries.BLOCK) {
            var id = Registries.BLOCK.getId(block);
            if (!ErydonHighPolish.usesHighPolish(id.getNamespace(), id.getPath())) continue;
            boolean spiral = id.getPath().endsWith("_stairs_spiral_large");
            for (BlockState state : block.getStateManager().getStates()) {
                // Preserve specialised light-source shader classifications.
                if (state.getLuminance() > 0) continue;
                ids.put(state, HighPolishShaderAdapter.materialId(
                        state.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), spiral));
                count++;
            }
        }
        Erydon.LOGGER.info("[erydon] Opaque high polish classified {} block states; no water-neighbour scans.", count);
    }
}
