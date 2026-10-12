package com.oliver.erydon.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerSaveMixin {
    private static final boolean CAP_SAVE_PALETTE =
            Boolean.parseBoolean(System.getProperty("erydon.perf.save_palette_cap", "true"));

    // A serialized section cannot contain more distinct values than positions.
    @ModifyExpressionValue(method = "serialize", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/util/collection/PaletteStorage;getElementBits()I"), require = 1)
    private int erydon$capTemporaryPaletteBits(int storageBits, IndexedIterable<?> ids,
                                               PalettedContainer.PaletteProvider provider) {
        return CAP_SAVE_PALETTE
                ? Math.min(storageBits, MathHelper.ceilLog2(provider.getContainerSize()))
                : storageBits;
    }
}
