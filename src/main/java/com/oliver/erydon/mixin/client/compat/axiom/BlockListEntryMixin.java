package com.oliver.erydon.mixin.client.compat.axiom;

import com.oliver.erydon.client.migration.IdAliasSearchVocabulary;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.editor.BlockList$Entry", remap = false)
public abstract class BlockListEntryMixin {
    @Shadow
    @Final
    private Identifier location;

    @Shadow
    @Final
    @Mutable
    private String searchKeyId;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void erydon$appendLegacyAliasSearchKeys(CallbackInfo callbackInfo) {
        searchKeyId = IdAliasSearchVocabulary.appendTerms(
                searchKeyId,
                IdAliasSearchVocabulary.terms(location),
                true
        );
    }
}
