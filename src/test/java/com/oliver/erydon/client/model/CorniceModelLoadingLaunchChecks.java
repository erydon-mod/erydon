package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Exercises the registered family loader's actual factories and reload-scoped cache. */
public final class CorniceModelLoadingLaunchChecks {
    private CorniceModelLoadingLaunchChecks() { }

    public static void run() throws ReflectiveOperationException {
        var loader = new ErydonFamilyModelLoadingPlugin();
        CapturedLoad first = load(loader);
        BakedModel source = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getParticleSprite")) return null;
                    throw new AssertionError("Unexpected dummy model access " + method.getName());
                });
        var field = CorniceBakedModel.class.getDeclaredField("fittedQuads");
        field.setAccessible(true);
        CorniceFittedCache shared = null;
        BakedModel firstPath = null;
        int wrappers = 0;
        for (String path : new String[]{"glacium_cornice_modern", "glacium_cornice_georgian"}) {
            Identifier block = new Identifier("erydon", path);
            require(Registries.BLOCK.containsId(block), "Live loader test needs registered cornice " + block);
            for (String suffix : CorniceBakedModel.modelSuffixes(path)) {
                require(first.models.contains(CorniceBakedModel.modelId(path, suffix)),
                        "Registered family loader omitted a cornice child model");
            }
            BakedModel placed = modify(first, source, new ModelIdentifier(block,
                    "facing=north,shape=diagonal_primary"));
            require(placed instanceof CorniceBakedModel, "Live family loader did not create a cornice wrapper");
            CorniceFittedCache cache = cache(field, placed);
            if (shared == null) shared = cache;
            require(cache == shared, "Live cornice materials allocated separate fitted caches");
            BakedModel otherPose = modify(first, source, new ModelIdentifier(block,
                    "facing=east,shape=broad_right_secondary"));
            require(otherPose == placed, "Live cornice poses did not reuse their block wrapper");
            BakedModel inventory = modify(first, source, new ModelIdentifier(block, "inventory"));
            require(inventory instanceof CorniceBakedModel && cache(field, inventory) == shared,
                    "Inventory cornice allocated another fitted geometry budget");
            if (firstPath == null) firstPath = placed;
            else require(firstPath != placed, "Two materials incorrectly shared one material-specific model");
            wrappers += 3;
        }
        require(modify(first, source, new ModelIdentifier(new Identifier("minecraft", "stone"), "")) == source,
                "Cornice factory changed a foreign model");
        CapturedLoad second = load(loader);
        BakedModel reloaded = modify(second, source, new ModelIdentifier(new Identifier("erydon", "glacium_cornice_modern"),
                "facing=north,shape=diagonal_primary"));
        require(reloaded instanceof CorniceBakedModel && cache(field, reloaded) != shared,
                "Resource reload retained the previous atlas's fitted cache");
        System.out.println("ERYDON_CORNICE_LIVE_MODEL_CACHE_OK: " + wrappers
                + " actual family-loader material/pose/inventory factories share one cache; next reload creates a fresh budget");
    }

    private record CapturedLoad(Event<ModelModifier.AfterBake> event, List<Identifier> models) { }

    private static CapturedLoad load(ErydonFamilyModelLoadingPlugin loader) {
        Event<ModelModifier.AfterBake> event = EventFactory.createArrayBacked(ModelModifier.AfterBake.class,
                callbacks -> (model, context) -> {
                    for (ModelModifier.AfterBake callback : callbacks) model = callback.modifyModelAfterBake(model, context);
                    return model;
                });
        List<Identifier> models = new ArrayList<>();
        ModelLoadingPlugin.Context context = (ModelLoadingPlugin.Context) Proxy.newProxyInstance(
                ModelLoadingPlugin.Context.class.getClassLoader(), new Class<?>[]{ModelLoadingPlugin.Context.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "modifyModelAfterBake" -> event;
                    case "addModels" -> {
                        if (args[0] instanceof Identifier[] values) models.addAll(List.of(values));
                        else if (args[0] instanceof Collection<?> values) {
                            for (Object value : values) models.add((Identifier) value);
                        } else throw new AssertionError("Unexpected model collection");
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected loader context access " + method.getName());
                });
        loader.onInitializeModelLoader(context);
        return new CapturedLoad(event, List.copyOf(models));
    }

    private static BakedModel modify(CapturedLoad load, BakedModel model, Identifier id) {
        ModelModifier.AfterBake.Context context = (ModelModifier.AfterBake.Context) Proxy.newProxyInstance(
                ModelModifier.AfterBake.Context.class.getClassLoader(), new Class<?>[]{ModelModifier.AfterBake.Context.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id")) return id;
                    throw new AssertionError("Unexpected bake context access " + method.getName());
                });
        return load.event.invoker().modifyModelAfterBake(model, context);
    }

    private static CorniceFittedCache cache(Field field, BakedModel model) throws IllegalAccessException {
        return (CorniceFittedCache) field.get(model);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
