package com.oliver.erydon.mixin.client.compat.rei;

import com.oliver.erydon.client.migration.IdAliasSearchVocabulary;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.shedaniel.rei.impl.client.search.argument.type.TextArgumentType", remap = false)
public abstract class ReiTextArgumentTypeMixin {
    @Inject(
            method = "cacheData(Lme/shedaniel/rei/api/common/entry/EntryStack;)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void erydon$appendLegacyTextSearch(EntryStack<?> entry,
                                               CallbackInfoReturnable<String> callbackInfo) {
        Object value = entry.getValue();
        if (value instanceof ItemStack stack) {
            callbackInfo.setReturnValue(
                    IdAliasSearchVocabulary.appendSearchTerms(callbackInfo.getReturnValue(), stack, false)
            );
        }
    }
}
