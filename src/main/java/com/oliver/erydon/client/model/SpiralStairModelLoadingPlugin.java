package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class SpiralStairModelLoadingPlugin implements ModelLoadingPlugin {
    static final Identifier REPEAT_CTM_PHASE = new Identifier(Erydon.MOD_ID, "spiral_stair_repeat_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, REPEAT_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(REPEAT_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier modelId)
                    || !isWorldSpiralModel(modelId)) {
                return model;
            }
            if (model == null) {
                return null;
            }
            String ctmSet = ErydonCtmService.get(null).spiralCtmSetName(modelId.getPath());
            return ctmSet == null ? model : new SpiralStairBakedModel(model, ctmSet);
        });
    }

    static boolean isWorldSpiralModel(ModelIdentifier id) {
        if (!Erydon.MOD_ID.equals(id.getNamespace()) || "inventory".equals(id.getVariant())) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_stairs_spiral_large")
                || path.endsWith("_stairs_spiral_large_aged");
    }
}
