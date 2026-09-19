package com.oliver.erydon.mixin.client;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.oliver.erydon.client.ErydonHighPolish;
import com.oliver.erydon.client.HighPolishCommandWords;
import net.minecraft.client.network.ClientCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(ClientCommandSource.class)
abstract class HighPolishCommandSuggestionsMixin {
    @Inject(method = "getCompletions", at = @At("RETURN"), cancellable = true)
    private void erydon$polishedNames(CommandContext<?> context,
            CallbackInfoReturnable<CompletableFuture<Suggestions>> info) {
        String input = context.getInput();
        if (input.startsWith("/")) input = input.substring(1);
        if (!input.startsWith("erydon swap ") && !input.startsWith("erydon:erydon swap ")) return;
        info.setReturnValue(info.getReturnValue().thenApply(suggestions ->
                HighPolishCommandWords.present(suggestions, ErydonHighPolish.activeSettings())));
    }
}
