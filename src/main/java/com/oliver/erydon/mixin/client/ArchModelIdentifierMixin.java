package com.oliver.erydon.mixin.client;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Arch wrappers already render from the supplied state, so each material needs one model ID. */
@Mixin(BlockModels.class)
public abstract class ArchModelIdentifierMixin {
    @Unique private static final Map<Identifier, ModelIdentifier> erydon$archIds = new ConcurrentHashMap<>();

    @Inject(method = "getModelId(Lnet/minecraft/util/Identifier;Lnet/minecraft/block/BlockState;)Lnet/minecraft/client/util/ModelIdentifier;",
            at = @At("HEAD"), cancellable = true)
    private static void erydon$sharedArchModel(Identifier id, BlockState state,
                                              CallbackInfoReturnable<ModelIdentifier> cir) {
        if (state.getBlock() instanceof ArchRomanesqueBlock) {
            cir.setReturnValue(erydon$archIds.computeIfAbsent(id, key -> new ModelIdentifier(key, "")));
        }
    }
}
