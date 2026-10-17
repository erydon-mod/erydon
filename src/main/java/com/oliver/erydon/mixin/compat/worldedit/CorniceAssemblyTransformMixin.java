package com.oliver.erydon.mixin.compat.worldedit;

import com.oliver.erydon.compat.worldedit.CorniceAssemblyWorldEditTransform;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = BlockTransformExtent.class, remap = false)
public abstract class CorniceAssemblyTransformMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "transform(Lcom/sk89q/worldedit/world/block/BlockStateHolder;Lcom/sk89q/worldedit/math/transform/Transform;)Lcom/sk89q/worldedit/world/block/BlockStateHolder;",
            at = @At("RETURN"), cancellable = true, remap = false, require = 1)
    private static void erydon$transformAssemblyBasis(BlockStateHolder original, Transform transform,
                                                       CallbackInfoReturnable<BlockStateHolder> callback) {
        callback.setReturnValue(CorniceAssemblyWorldEditTransform.apply(original, callback.getReturnValue(), transform));
    }
}
