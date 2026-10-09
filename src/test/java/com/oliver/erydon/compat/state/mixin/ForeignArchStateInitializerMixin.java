package com.oliver.erydon.compat.state.mixin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.compat.state.ForeignArchStateTable;
import com.oliver.erydon.state.ArchStateTransitions;
import net.minecraft.state.State;
import net.minecraft.state.property.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Probe-only optimizer with a separate lookup, deliberately without a known mod ID. */
@Mixin(value = State.class, priority = 500)
public abstract class ForeignArchStateInitializerMixin<O, S> {
    @Shadow @Final protected O owner;
    @Shadow @Final private ImmutableMap<Property<?>, Comparable<?>> entries;
    @Shadow private Table<Property<?>, Comparable<?>, S> withTable;
    @Unique private Table<Property<?>, Comparable<?>, S> probe$lookup;

    @Inject(method = "createWithTable", at = @At("HEAD"), cancellable = true)
    private void probe$initialize(Map<Map<Property<?>, Comparable<?>>, S> states, CallbackInfo ci) {
        if (owner instanceof ArchRomanesqueBlock) {
            probe$lookup = new ForeignArchStateTable<>(ArchStateTransitions.create(states, entries));
            withTable = probe$lookup;
            ci.cancel();
        }
    }

    @Redirect(method = "with", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/Table;get(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            remap = false))
    private Object probe$transition(Table<?, ?, ?> table, Object property, Object value) {
        if (!(owner instanceof ArchRomanesqueBlock)) return table.get(property, value);
        if (probe$lookup == null) throw new IllegalStateException("Other optimizer initialization was skipped");
        return probe$lookup.get(property, value);
    }

}
