package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class ShallowSlopeModelLoadingPlugin implements ModelLoadingPlugin {
    private static final Identifier MINI_CTM_PHASE = new Identifier(Erydon.MOD_ID, "shallow_slope_mini_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();

        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, MINI_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(MINI_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(mid.getNamespace()) || !isShallowSlopeBlock(mid.getPath())) {
                return model;
            }

            ErydonSlopeModelClassifier.Family family = mid.getPath().contains("_slope_shallow_upper")
                    ? ErydonSlopeModelClassifier.Family.SHALLOW_UPPER
                    : ErydonSlopeModelClassifier.Family.SHALLOW_LOWER;
            return cache.wrap(mid, model, wrapped -> new ShallowSlopeBakedModel(wrapped, family));
        });
    }

    private static boolean isShallowSlopeBlock(String path) {
        return path.contains("_slope_shallow_lower") || path.contains("_slope_shallow_upper");
    }
}
