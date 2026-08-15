package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class ErydonFamilyModelLoadingPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();
        pluginContext.addModels(extraModels());

        pluginContext.modifyModelAfterBake().register((model, context) -> {
            Identifier id = context.id();
            if (!(id instanceof ModelIdentifier mid) || !Erydon.MOD_ID.equals(mid.getNamespace())) {
                return model;
            }

            String path = mid.getPath();
            boolean itemModel = "inventory".equals(mid.getVariant());
            if (ErydonModelFamilyIndex.isColumnBlock(path)) {
                return wrap(cache, mid, model, ColumnBakedModel::new, "columns", itemModel);
            }
            if (ErydonModelFamilyIndex.isCorniceBlock(path)) {
                return wrap(cache, mid, model, CorniceBakedModel::new, "cornices", itemModel);
            }
            if (ErydonModelFamilyIndex.isCofferedCeilingBlock(path)) {
                return wrap(cache, mid, model, CeilingCofferedBakedModel::new, "coffered_ceilings", itemModel);
            }
            if (ErydonModelFamilyIndex.isLayerVerticalBlock(path)) {
                return wrap(cache, mid, model, LayerVerticalBakedModel::new, "layer_vertical", itemModel);
            }
            if (ErydonModelFamilyIndex.isSurroundBlock(path)) {
                return wrap(cache, mid, model, SurroundBakedModel::new, "surrounds", itemModel);
            }
            if (ErydonModelFamilyIndex.isWindowArchBlock(path)) {
                return wrap(cache, mid, model, WindowArchBakedModel::new, "arch_windows", itemModel);
            }
            if (ErydonModelFamilyIndex.isWindowFrenchGeorgianBlock(path)) {
                return wrap(cache, mid, model, WindowFrenchGeorgianBakedModel::new, "french_georgian_windows", itemModel);
            }
            if (ErydonModelFamilyIndex.isArchRomanesqueBlock(path)) {
                return wrap(cache, mid, model, ArchRomanesqueBakedModel::new, "romanesque_arches", itemModel);
            }
            if (ErydonModelFamilyIndex.isArchModernBlock(path)) {
                return wrap(cache, mid, model, ArchRomanesqueBakedModel::new, "modern_arches", itemModel);
            }
            if (ErydonModelFamilyIndex.isArchGothicBlock(path)) {
                return wrap(cache, mid, model, ArchRomanesqueBakedModel::new, "gothic_arches", itemModel);
            }
            if (ErydonModelFamilyIndex.isAlcoveBlock(path)) {
                return wrap(cache, mid, model, AlcoveBakedModel::new, "alcoves", itemModel);
            }
            return model;
        });
    }

    private static BakedModel wrap(DynamicBakedModelCache cache,
                                   ModelIdentifier id,
                                   BakedModel model,
                                   Function<BakedModel, BakedModel> factory,
                                   String family,
                                   boolean itemModel) {
        return cache.wrap(id, model, factory, ErydonLoadProfiler.customWrapObserver(family, itemModel));
    }

    private static List<Identifier> extraModels() {
        ErydonModelFamilyIndex index = ErydonModelFamilyIndex.get();
        List<Identifier> models = new ArrayList<>();

        for (String blockPath : index.columns()) {
            for (String suffix : ColumnBakedModel.modelSuffixes(blockPath)) {
                models.add(ColumnBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.cornices()) {
            for (String suffix : CorniceBakedModel.modelSuffixes(blockPath)) {
                models.add(CorniceBakedModel.modelId(blockPath, suffix));
            }
        }
        models.addAll(cofferedCeilingModels(index));
        for (String blockPath : index.layerVertical()) {
            models.addAll(LayerVerticalBakedModel.modelIdsForBlock(blockPath));
        }
        for (String blockPath : index.surrounds()) {
            for (String suffix : SurroundBakedModel.modelSuffixes(blockPath)) {
                models.add(SurroundBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.archWindows()) {
            for (String suffix : WindowArchBakedModel.MODEL_SUFFIXES) {
                models.add(WindowArchBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.frenchGeorgianWindows()) {
            for (String suffix : WindowFrenchGeorgianBakedModel.MODEL_SUFFIXES) {
                models.add(WindowFrenchGeorgianBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.romanesqueArches()) {
            for (String suffix : ArchRomanesqueBakedModel.modelSuffixes(blockPath)) {
                models.add(ArchRomanesqueBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.modernArches()) {
            for (String suffix : ArchRomanesqueBakedModel.modelSuffixes(blockPath)) {
                models.add(ArchRomanesqueBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.gothicArches()) {
            for (String suffix : ArchRomanesqueBakedModel.modelSuffixes(blockPath)) {
                models.add(ArchRomanesqueBakedModel.modelId(blockPath, suffix));
            }
        }
        for (String blockPath : index.alcoves()) {
            for (String suffix : AlcoveBakedModel.modelSuffixes(blockPath)) {
                models.add(AlcoveBakedModel.modelId(blockPath, suffix));
            }
        }

        return models;
    }

    private static List<Identifier> cofferedCeilingModels(ErydonModelFamilyIndex index) {
        Set<Identifier> models = new LinkedHashSet<>();
        for (String blockPath : index.cofferedCeilings()) {
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
