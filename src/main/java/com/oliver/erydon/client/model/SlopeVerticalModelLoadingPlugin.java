package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class SlopeVerticalModelLoadingPlugin implements ModelLoadingPlugin {
    private static final Identifier MINI_CTM_PHASE = new Identifier(Erydon.MOD_ID, "vertical_slope_mini_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();

        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, MINI_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(MINI_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(mid.getNamespace()) || !isVerticalSlopeBlock(mid.getPath())) {
                return model;
            }

            ErydonSlopeModelClassifier.Family family = familyForPath(mid.getPath());
            return cache.wrap(mid, model, wrapped -> new SlopeVerticalBakedModel(wrapped, family));
        });
    }

    private static ErydonSlopeModelClassifier.Family familyForPath(String path) {
        if (path.contains("_slope_vertical_shallow_broad")) {
            return ErydonSlopeModelClassifier.Family.VERTICAL_SHALLOW_BROAD;
        }
        if (path.contains("_slope_vertical_shallow_narrow")) {
            return ErydonSlopeModelClassifier.Family.VERTICAL_SHALLOW_NARROW;
        }
        return ErydonSlopeModelClassifier.Family.VERTICAL;
    }

    private static boolean isVerticalSlopeBlock(String path) {
        if (path.startsWith("glazing_")) {
            return false;
        }
        return (path.contains("_slope_vertical")
                && !path.contains("_slope_vertical_shallow_"))
                || isShallowVerticalSlopeBlock(path);
    }

    private static boolean isShallowVerticalSlopeBlock(String path) {
        return path.contains("_slope_vertical_shallow_broad")
                || path.contains("_slope_vertical_shallow_narrow");
    }
}
