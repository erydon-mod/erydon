package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

/** Applies Gothic arch world projection after Continuity has finished wrapping the model. */
public final class GothicArchCtmModelLoadingPlugin implements ModelLoadingPlugin {
    static final Identifier REPEAT_CTM_PHASE = new Identifier(Erydon.MOD_ID, "gothic_arch_repeat_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, REPEAT_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(REPEAT_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier modelId)
                    || !isWorldGothicArchModel(modelId)) {
                return model;
            }
            if (model == null) {
                return null;
            }
            String ctmSet = ErydonCtmService.get(null).gothicArchCtmSetName(modelId.getPath());
            return ctmSet == null ? model : new ArchRepeatCtmRenderer(
                    model, ctmSet, ArchRepeatCtmRenderer.Family.GOTHIC);
        });
    }

    static boolean isWorldGothicArchModel(ModelIdentifier id) {
        if (!Erydon.MOD_ID.equals(id.getNamespace()) || "inventory".equals(id.getVariant())) {
            return false;
        }
        return ErydonModelFamilyIndex.isArchGothicBlock(id.getPath());
    }
}
