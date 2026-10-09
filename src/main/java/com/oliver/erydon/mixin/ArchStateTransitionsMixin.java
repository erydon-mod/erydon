package com.oliver.erydon.mixin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.state.ArchStateTransitions;
import net.minecraft.state.State;
import net.minecraft.state.property.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = State.class, priority = 2000)
public abstract class ArchStateTransitionsMixin<O, S> {
    @Shadow @Final protected O owner;
    @Shadow @Final private ImmutableMap<Property<?>, Comparable<?>> entries;
    @Shadow private Table<Property<?>, Comparable<?>, S> withTable;

    // Only replace vanilla's dense-table construction. An optimizer such as
    // FerriteCore overwrites this method and initializes its own lookup fields;
    // cancelling at HEAD would leave those fields null. The optional call site
    // also lets other replacement initializers run without a mod-ID allowlist.
    @Inject(method = "createWithTable", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/HashBasedTable;create()Lcom/google/common/collect/HashBasedTable;",
            remap = false), cancellable = true, require = 0)
    private void erydon$compactArchTransitions(Map<Map<Property<?>, Comparable<?>>, S> states, CallbackInfo ci) {
        if (owner instanceof ArchRomanesqueBlock) {
            if (withTable != null) throw new IllegalStateException("Arch transitions already initialized");
            withTable = ArchStateTransitions.create(states, entries);
            ci.cancel();
        }
    }
}
