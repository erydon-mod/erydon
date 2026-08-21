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
    private static final Identifier CTM_PHASE = new Identifier(Erydon.MOD_ID, "synapheia_ctm");

    public static void register() {
        PreparableModelLoadingPlugin.register(SynapheiaModelLoadingPlugin::load,
                new SynapheiaModelLoadingPlugin());
    }

    public static CompletableFuture<SynapheiaManifest.Prepared> load(ResourceManager manager, Executor executor) {
        long startedNanos = System.nanoTime();
        SynapheiaMetrics.event("resource_reload_phase", SynapheiaMode.SYNAPHEIA, 0L, fields(
                "phase", "ctm_rule_prepare", "state", "start", "duration_ns", 0L
        ));
        return CompletableFuture.supplyAsync(() -> {
            try {
                SynapheiaManifest.Prepared prepared = SynapheiaManifest.load(manager);
                return prepared.withDuration(System.nanoTime() - startedNanos);
            } catch (RuntimeException exception) {
                String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                SynapheiaMetrics.event("ctm_rule_parsed", SynapheiaMode.SYNAPHEIA, 0L, fields(
                        "rule_id", "<active-resource-stack>", "method", "unknown", "status", "rejected",
                        "reason", reason
                ));
                Erydon.LOGGER.error("[{}] Synapheia cannot load the active CTM rules: {}", Erydon.MOD_ID, reason);
                throw exception;
            }
        }, executor);
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
            if (!Erydon.MOD_ID.equals(modelId.getNamespace())) {
                return model;
            }
            Identifier blockId = new Identifier(modelId.getNamespace(), modelId.getPath());
            if (snapshot.rulesFor(blockId).isEmpty()) {
                return model;
            }
            return new SynapheiaRepeatBakedModel(model, modelId, blockId, snapshot);
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
