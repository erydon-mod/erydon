package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class SlopeModelLoadingPlugin implements ModelLoadingPlugin {
    private static final Identifier MINI_CTM_PHASE = new Identifier(Erydon.MOD_ID, "slope_mini_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();

        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, MINI_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(MINI_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(mid.getNamespace()) || !isSlopeBlock(mid.getPath())) {
                return model;
            }

            ErydonSlopeModelClassifier.Family family = familyForPath(mid.getPath());
            return switch (family) {
                case STEEP_LOWER, STEEP_UPPER -> cache.wrap(mid, model, wrapped -> new SlopeSteepBakedModel(wrapped, family));
                case STANDARD -> cache.wrap(mid, model, wrapped -> new SlopeBakedModel(wrapped, family));
                default -> model;
            };
        });
    }

    private static ErydonSlopeModelClassifier.Family familyForPath(String path) {
        if (path.contains("_slope_steep_upper")) {
            return ErydonSlopeModelClassifier.Family.STEEP_UPPER;
        }
        if (path.contains("_slope_steep_lower")) {
            return ErydonSlopeModelClassifier.Family.STEEP_LOWER;
        }
        return ErydonSlopeModelClassifier.Family.STANDARD;
    }

    private static boolean isSlopeBlock(String path) {
        return isNormalSlopeBlock(path) || isSteepSlopeBlock(path);
    }

    private static boolean isNormalSlopeBlock(String path) {
        if (path.startsWith("glazing_")) {
            return false;
        }
        return path.endsWith("_slope") || path.endsWith("_slope_aged");
    }

    private static boolean isSteepSlopeBlock(String path) {
        return path.contains("_slope_steep_lower") || path.contains("_slope_steep_upper");
    }
}
