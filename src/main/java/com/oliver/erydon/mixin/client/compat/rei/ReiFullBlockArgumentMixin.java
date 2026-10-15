package com.oliver.erydon.mixin.client.compat.rei;

import com.oliver.erydon.item.ErydonBlockCategories;
import me.shedaniel.rei.api.client.search.method.InputMethod;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.impl.client.search.argument.AlternativeArgument;
import me.shedaniel.rei.impl.client.search.argument.Argument;
import me.shedaniel.rei.impl.client.search.argument.type.TextArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Keep plain full-block searches strict even when REI also searches incidental tooltip text. */
@Pseudo
@Mixin(targets = "me.shedaniel.rei.impl.client.search.argument.Argument", remap = false)
public abstract class ReiFullBlockArgumentMixin {
    @Unique
    private static final ConcurrentMap<Item, Byte> erydon$fullBlockCategories = new ConcurrentHashMap<>();

    @Inject(
            method = "matches(Lme/shedaniel/rei/api/common/entry/EntryStack;JLme/shedaniel/rei/impl/client/search/argument/AlternativeArgument;Lme/shedaniel/rei/api/client/search/method/InputMethod;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false
    )
    private static void erydon$matchFullBlockCategory(EntryStack<?> entry, long hashExact,
                                                     AlternativeArgument alternatives, InputMethod<?> inputMethod,
                                                     CallbackInfoReturnable<Boolean> callback) {
        for (Argument<?, ?> argument : alternatives) {
            if (!(argument.getArgument() instanceof TextArgumentType)) continue;
            String text = argument.getText();
            if (!text.equals("block") && !text.equals("blocks")
                    && !text.equals("full block") && !text.equals("full blocks")) continue;
            // Ordinary searches return above without touching the item registry.
            // Explicit #/*/$/@ prefixes have no TextArgumentType alternative.
            if (!(entry.getValue() instanceof ItemStack stack)) return;
            byte category = erydon$fullBlockCategories.computeIfAbsent(stack.getItem(), item -> {
                var id = Registries.ITEM.getId(item);
                if (id == null || !id.getNamespace().equals("erydon")) return (byte) 0;
                return ErydonBlockCategories.isFullBlock(id.getPath()) ? (byte) 1 : (byte) 2;
            });
            if (category != 0) {
                // REI still combines AND, OR and inversion outside this one token.
                callback.setReturnValue((category == 1) == argument.isRegular());
            }
            return;
        }
    }
}
