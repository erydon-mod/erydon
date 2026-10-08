package com.oliver.erydon.mixin;

import com.oliver.erydon.state.ArchShapeCaches;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class ArchShapeCacheMixin {
    @Coerce
    @Redirect(method = "initShapeCache", at = @At(value = "NEW",
            target = "net/minecraft/block/AbstractBlock$AbstractBlockState$ShapeCache"))
    private Object erydon$shareArchGeometry(BlockState state) {
        return ArchShapeCaches.create(state);
    }
}
