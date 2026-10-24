package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class ErydonFamilyModelLoadingPlugin implements ModelLoadingPlugin {
    private final DoubleColumnMeshBaker columnMeshes;

    public ErydonFamilyModelLoadingPlugin() { this(null); }

    public ErydonFamilyModelLoadingPlugin(DoubleColumnMeshBaker columnMeshes) {
        this.columnMeshes = columnMeshes;
    }

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        DynamicBakedModelCache cache = new DynamicBakedModelCache();
        CorniceFittedCache fittedQuads = new CorniceFittedCache();
        var corniceProfiles = new ConcurrentHashMap<String, List<BakedQuad>>();
        pluginContext.addModels(extraModels());

        pluginContext.modifyModelAfterBake().register((model, context) -> {
            Identifier id = context.id();
            if (model != null && !(id instanceof ModelIdentifier) && Erydon.MOD_ID.equals(id.getNamespace())
                    && (id.getPath().startsWith("block/window/arch/")
                    || id.getPath().startsWith("block/window/french_georgian/"))
                    && context.sourceModel() instanceof JsonUnbakedModel source) {
                JsonUnbakedModel wall = null;
                String path = id.getPath();
                String withoutAge = path.endsWith("_aged") ? path.substring(0, path.length() - 5) : path;
                if (path.startsWith("block/window/arch/") && withoutAge.endsWith("_glass_lower")) {
                    String wallPath = withoutAge.substring(0, withoutAge.length() - "_glass_lower".length())
                            + "_wall" + (path.endsWith("_aged") ? "_aged" : "");
                    if (context.baker().getOrLoadModel(new Identifier(id.getNamespace(), wallPath))
                            instanceof JsonUnbakedModel sibling) wall = sibling;
                }
                BakedModel capped = WindowBottomCaps.wrap(model, source, context.textureGetter(), context.settings(), id, wall);
                BlockState seed = windowSeed(id);
                if (seed == null) return capped;
                return withoutAge.endsWith("_sill")
                        ? WindowBottomRail.wrapSill(capped, source, context.textureGetter(), context.settings(), id, seed)
                        : WindowBottomRail.wrapChild(capped, seed);
            }
            if (!(id instanceof ModelIdentifier mid) || !Erydon.MOD_ID.equals(mid.getNamespace())) {
                return model;
            }

            String path = mid.getPath();
            boolean itemModel = "inventory".equals(mid.getVariant());
            if ("cornice_assembly".equals(path)) {
                return wrap(cache, mid, model, CorniceAssemblyBakedModel::new, "cornice_assemblies", itemModel);
            }
            if (path.endsWith("_column_circular_double") && !itemModel) {
                return wrap(cache, mid, model, wrapped -> columnMeshes == null
                        ? new DoubleCircularColumnBakedModel(wrapped)
                        : columnMeshes.wrap(wrapped, path, context.baker(), context.textureGetter()),
                        "double_circular_columns", false);
            }
            if (ErydonModelFamilyIndex.isColumnBlock(path)) {
                return wrap(cache, mid, model, ColumnBakedModel::new, "columns", itemModel);
            }
            if (ErydonModelFamilyIndex.isCorniceBlock(path)) {
                return wrap(cache, mid, model, wrapped -> {
                    List<BakedQuad> volumes = null;
                    if (!itemModel && context.baker() != null) {
                        volumes = corniceProfiles.computeIfAbsent(path, blockPath -> {
                            Identifier straight = CorniceBakedModel.modelId(blockPath, "straight");
                            return context.baker().getOrLoadModel(straight) instanceof JsonUnbakedModel profile
                                    ? CorniceProfileVolumes.bake(profile, context.textureGetter(), straight) : null;
                        });
                    }
                    return new CorniceBakedModel(wrapped, fittedQuads, volumes);
                }, "cornices", itemModel);
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
                return wrap(cache, mid, model, wrapped -> new WindowArchBakedModel(wrapped,
                        com.oliver.erydon.client.ErydonHighPolish.usesHighPolish(mid.getNamespace(), path),
                        com.oliver.erydon.client.ErydonHighPolish.twoWayEnabled()), "arch_windows", itemModel);
            }
            if (ErydonModelFamilyIndex.isWindowFrenchGeorgianBlock(path)) {
                return wrap(cache, mid, model, wrapped -> new WindowFrenchGeorgianBakedModel(wrapped,
                        com.oliver.erydon.client.ErydonHighPolish.usesHighPolish(mid.getNamespace(), path),
                        com.oliver.erydon.client.ErydonHighPolish.twoWayEnabled()), "french_georgian_windows", itemModel);
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
            if (ErydonModelFamilyIndex.isArchGeorgianBlock(path)) {
                return wrap(cache, mid, model, ArchGeorgianBakedModel::new, "georgian_arches", itemModel);
            }
            if (ErydonModelFamilyIndex.isAlcoveBlock(path)) {
                return wrap(cache, mid, model, AlcoveBakedModel::new, "alcoves", itemModel);
            }
            return model;
        });
    }

    private static BlockState windowSeed(Identifier id) {
        String leaf = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
        boolean aged = leaf.endsWith("_aged");
        String base = aged ? leaf.substring(0, leaf.length() - 5) : leaf;
        String[] suffixes = id.getPath().startsWith("block/window/arch/")
                ? WindowArchBakedModel.MODEL_SUFFIXES : WindowFrenchGeorgianBakedModel.MODEL_SUFFIXES;
        for (String suffix : suffixes) {
            if (!base.endsWith("_" + suffix)) continue;
            String resourcePath = base.substring(0, base.length() - suffix.length() - 1) + (aged ? "_aged" : "");
            var block = Registries.BLOCK.get(new Identifier(id.getNamespace(), ErydonIdMigration.canonicalPath(resourcePath)));
            if (block instanceof WindowArchBlock || block instanceof WindowFrenchGeorgianBlock) return block.getDefaultState();
            break;
        }
        return null;
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
        models.add(WindowArchBakedModel.MIRROR_MATERIAL_MODEL);

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
        for (String blockPath : index.georgianArches()) {
            for (String suffix : ArchGeorgianBakedModel.modelSuffixes(blockPath)) {
                models.add(ArchGeorgianBakedModel.modelId(blockPath, suffix));
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
