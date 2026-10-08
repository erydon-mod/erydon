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

@Mixin(State.class)
public abstract class ArchStateTransitionsMixin<O, S> {
    @Shadow @Final protected O owner;
    @Shadow @Final private ImmutableMap<Property<?>, Comparable<?>> entries;
    @Shadow private Table<Property<?>, Comparable<?>, S> withTable;

    @Inject(method = "createWithTable", at = @At("HEAD"), cancellable = true)
    private void erydon$compactArchTransitions(Map<Map<Property<?>, Comparable<?>>, S> states, CallbackInfo ci) {
        if (owner instanceof ArchRomanesqueBlock) {
            if (withTable != null) throw new IllegalStateException("Arch transitions already initialized");
            withTable = ArchStateTransitions.create(states, entries);
            ci.cancel();
        }
    }
}
