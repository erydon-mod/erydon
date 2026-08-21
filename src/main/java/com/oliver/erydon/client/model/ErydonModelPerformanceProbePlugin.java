package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class ErydonModelPerformanceProbePlugin implements ModelLoadingPlugin {
    private static final Identifier SLOPE_MINI_CTM_PHASE = new Identifier(Erydon.MOD_ID, "slope_mini_ctm");
    private static final Identifier PROBE_PHASE = new Identifier(Erydon.MOD_ID, "model_performance_probe");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        if (!ErydonLoadProfiler.isEnabled()) {
            return;
        }

        ErydonLoadProfiler.beginModelReload();
        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, PROBE_PHASE);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(SLOPE_MINI_CTM_PHASE, PROBE_PHASE);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(GothicArchCtmModelLoadingPlugin.REPEAT_CTM_PHASE, PROBE_PHASE);
        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModernArchCtmModelLoadingPlugin.REPEAT_CTM_PHASE, PROBE_PHASE);
        pluginContext.modifyModelAfterBake().register(PROBE_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(id.getNamespace())) {
                ErydonLoadProfiler.inspectFinalModel(id, model);
                return model;
            }

            ErydonLoadProfiler.inspectFinalModel(id, model);
            return model;
        });
    }
}
