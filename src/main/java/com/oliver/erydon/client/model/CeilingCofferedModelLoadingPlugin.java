package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CeilingCofferedModelLoadingPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();
        pluginContext.addModels(extraModels());

        pluginContext.modifyModelAfterBake().register((model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            if (!Erydon.MOD_ID.equals(mid.getNamespace()) || !ErydonModelFamilyIndex.isCofferedCeilingBlock(mid.getPath())) {
                return model;
            }

            return cache.wrap(mid, model, CeilingCofferedBakedModel::new,
                    ErydonLoadProfiler.customWrapObserver("coffered_ceilings", "inventory".equals(mid.getVariant())));
        });
    }

    private static List<Identifier> extraModels() {
        Set<Identifier> models = new LinkedHashSet<>();
        for (String blockPath : ErydonModelFamilyIndex.get().cofferedCeilings()) {
            for (Identifier modelId : CeilingCofferedBakedModel.modelIdsForBlock(blockPath)) {
                if (!isCorniceModel(modelId)) {
                    models.add(modelId);
                }
            }
        }
        return new ArrayList<>(models);
    }

    private static boolean isCorniceModel(Identifier id) {
        return Erydon.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("block/cornice/");
    }

}
