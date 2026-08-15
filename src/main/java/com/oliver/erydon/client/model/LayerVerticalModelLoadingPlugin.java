package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class LayerVerticalModelLoadingPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();
        pluginContext.addModels(extraModels());

        pluginContext.modifyModelAfterBake().register((model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(mid.getNamespace()) || !ErydonModelFamilyIndex.isLayerVerticalBlock(mid.getPath())) {
                return model;
            }

            return cache.wrap(mid, model, LayerVerticalBakedModel::new,
                    ErydonLoadProfiler.customWrapObserver("layer_vertical", "inventory".equals(mid.getVariant())));
        });
    }

    private static List<Identifier> extraModels() {
        List<Identifier> models = new ArrayList<>();
        for (String blockPath : ErydonModelFamilyIndex.get().layerVertical()) {
            models.addAll(LayerVerticalBakedModel.modelIdsForBlock(blockPath));
        }
        return models;
    }
}
