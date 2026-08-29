package com.oliver.erydon.mixin.client.compat.continuity;

import com.google.common.collect.ImmutableMap;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.model.SynapheiaModelLoadingPlugin;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps Continuity available without allowing it to process Synapheia-owned CTM twice. */
@Pseudo
@Mixin(targets = "me.pepperbell.continuity.client.resource.ModelWrappingHandler", remap = false)
public abstract class SynapheiaOwnershipMixin {
    private static final AtomicBoolean ERYDON$OWNERSHIP_LOGGED = new AtomicBoolean();

    @Redirect(
            method = "wrap",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableMap;get(Ljava/lang/Object;)Ljava/lang/Object;",
                    remap = false
            ),
            require = 1,
            expect = 1,
            allow = 1,
            remap = false
    )
    private Object erydon$keepSynapheiaCtmSinglePass(ImmutableMap<?, ?> blockStateModelIds,
                                                      Object modelId) {
        if (modelId instanceof net.minecraft.util.Identifier identifier
                && SynapheiaModelLoadingPlugin.ownsCtmModel(identifier)) {
            ErydonLoadProfiler.continuitySkipRequested("synapheia_ownership");
            ErydonLoadProfiler.continuitySkipApplied("synapheia_ownership", 1);
            if (ERYDON$OWNERSHIP_LOGGED.compareAndSet(false, true)) {
                Erydon.LOGGER.info(
                        "[{}] Continuity compatibility active: Synapheia owns ERYDON CTM; "
                                + "Continuity remains active for other models and emissive rendering.",
                        Erydon.MOD_ID);
            }
            return null;
        }
        return blockStateModelIds.get(modelId);
    }
}
