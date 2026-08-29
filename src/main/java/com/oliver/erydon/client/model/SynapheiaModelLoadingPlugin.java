package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SynapheiaModelLoadingPlugin
        implements PreparableModelLoadingPlugin<SynapheiaManifest.Prepared> {
    static final Identifier CTM_PHASE = new Identifier(Erydon.MOD_ID, "synapheia_ctm");

    public static void register() {
        PreparableModelLoadingPlugin.register(SynapheiaModelLoadingPlugin::load,
                new SynapheiaModelLoadingPlugin());
    }

    public static CompletableFuture<SynapheiaManifest.Prepared> load(ResourceManager manager, Executor executor) {
        boolean metricsEnabled = SynapheiaMetrics.enabled();
        long startedNanos = metricsEnabled ? System.nanoTime() : 0L;
        if (metricsEnabled) {
            SynapheiaMetrics.event("resource_reload_phase", SynapheiaMode.SYNAPHEIA, 0L, fields(
                    "phase", "ctm_rule_prepare", "state", "start", "duration_ns", 0L
            ));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                SynapheiaManifest.Prepared prepared = SynapheiaManifest.load(manager);
                return metricsEnabled
                        ? prepared.withDuration(System.nanoTime() - startedNanos)
                        : prepared;
            } catch (RuntimeException exception) {
                String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                if (metricsEnabled) {
                    SynapheiaMetrics.event("ctm_rule_parsed", SynapheiaMode.SYNAPHEIA, 0L, fields(
                            "rule_id", "<active-resource-stack>", "method", "unknown", "status", "rejected",
                            "reason", reason
                    ));
                }
                Erydon.LOGGER.error("[{}] Synapheia cannot load the active CTM rules: {}", Erydon.MOD_ID, reason);
                throw exception;
            }
        }, executor);
    }

    /** Returns whether Synapheia will wrap this placed ERYDON block model. */
    public static boolean ownsCtmModel(Identifier modelId) {
        if (!(modelId instanceof ModelIdentifier placedModel)
                || !Erydon.MOD_ID.equals(placedModel.getNamespace())
                || "inventory".equals(placedModel.getVariant())) {
            return false;
        }
        Identifier blockId = new Identifier(placedModel.getNamespace(), placedModel.getPath());
        return SynapheiaService.current().planFor(blockId) != null;
    }

    @Override
    public void onInitializeModelLoader(SynapheiaManifest.Prepared prepared,
                                        ModelLoadingPlugin.Context pluginContext) {
        SynapheiaService.Snapshot snapshot = SynapheiaService.publish(prepared);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(
                ErydonSlopeModelLoadingPlugin.MINI_CTM_PHASE, CTM_PHASE);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(
                GothicArchCtmModelLoadingPlugin.REPEAT_CTM_PHASE, CTM_PHASE);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(
                ModernArchCtmModelLoadingPlugin.REPEAT_CTM_PHASE, CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(CTM_PHASE, (model, context) -> {
            if (model == null || !(context.id() instanceof ModelIdentifier modelId)
                    || "inventory".equals(modelId.getVariant())) {
                return model;
            }
            if (!ownsCtmModel(modelId)) {
                return model;
            }
            Identifier blockId = new Identifier(modelId.getNamespace(), modelId.getPath());
            SynapheiaBlockPlan plan = snapshot.planFor(blockId);
            if (plan == null) {
                return model;
            }
            return new SynapheiaRepeatBakedModel(model, modelId, plan, snapshot);
        });
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], entries[index + 1]);
        }
        return fields;
    }
}
