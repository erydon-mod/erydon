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

    public static void run() throws Exception {
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
            BakedModel halfPose = modify(first, source, new ModelIdentifier(block, "facing=west,shape=half_edge_low"));
            require(halfPose == placed, "An appended half-support pose lost its actual family wrapper/model chain");
            BakedModel inventory = modify(first, source, new ModelIdentifier(block, "inventory"));
            require(inventory instanceof CorniceBakedModel && cache(field, inventory) == shared,
                    "Inventory cornice allocated another fitted geometry budget");
            if (firstPath == null) firstPath = placed;
            else require(firstPath != placed, "Two materials incorrectly shared one material-specific model");
            wrappers += 4;
        }
        require(modify(first, source, new ModelIdentifier(new Identifier("minecraft", "stone"), "")) == source,
                "Cornice factory changed a foreign model");
        CapturedLoad second = load(loader);
        BakedModel reloaded = modify(second, source, new ModelIdentifier(new Identifier("erydon", "glacium_cornice_modern"),
                "facing=north,shape=diagonal_primary"));
        require(reloaded instanceof CorniceBakedModel && cache(field, reloaded) != shared,
                "Resource reload retained the previous atlas's fitted cache");
        resolvedProfileVolumeContexts(loader, source);
        System.out.println("ERYDON_CORNICE_LIVE_MODEL_CACHE_OK: " + wrappers
                + " actual family-loader material/pose/inventory factories share one cache; next reload creates a fresh budget");
    }

    private static void resolvedProfileVolumeContexts(ErydonFamilyModelLoadingPlugin loader, BakedModel wrapped) throws Exception {
        var json = net.minecraft.client.render.model.json.JsonUnbakedModel.deserialize("""
                {"textures":{"stone":"erydon:custom_crop_material"},"elements":[
                  {"from":[2,5,1],"to":[14,15,4],"faces":{"west":{"texture":"#stone"}}}]}
                """);
        var volumeField = CorniceBakedModel.class.getDeclaredField("profileVolumes"); volumeField.setAccessible(true);
        try (var firstContents = new net.minecraft.client.texture.SpriteContents(new Identifier("erydon","profile_reload_a"),
                new net.minecraft.client.texture.SpriteDimensions(16,16),new net.minecraft.client.texture.NativeImage(16,16,false),
                net.minecraft.client.resource.metadata.AnimationResourceMetadata.EMPTY);
             var secondContents = new net.minecraft.client.texture.SpriteContents(new Identifier("erydon","profile_reload_b"),
                new net.minecraft.client.texture.SpriteDimensions(16,16),new net.minecraft.client.texture.NativeImage(16,16,false),
                net.minecraft.client.resource.metadata.AnimationResourceMetadata.EMPTY)) {
            var firstSprite = new ProfileSprite(firstContents); var secondSprite = new ProfileSprite(secondContents);
            int[] reads = {0};
            net.minecraft.client.render.model.Baker baker = (net.minecraft.client.render.model.Baker) Proxy.newProxyInstance(
                    net.minecraft.client.render.model.Baker.class.getClassLoader(),new Class<?>[]{net.minecraft.client.render.model.Baker.class},
                    (proxy,method,args) -> {
                        if (method.getName().equals("getOrLoadModel")) {
                            require(args[0].equals(CorniceBakedModel.modelId("glacium_cornice_georgian","straight")),
                                    "Cornice profile volume resolved a rotated/icon/foreign source"); reads[0]++; return json;
                        }
                        throw new AssertionError("Unexpected profile Baker access " + method.getName());
                    });
            Identifier block = new Identifier("erydon","glacium_cornice_georgian");
            CapturedLoad first = load(loader);
            BakedModel model = modify(first,wrapped,new ModelIdentifier(block,"facing=north,shape=half_edge_low"),baker,ignored -> firstSprite);
            @SuppressWarnings("unchecked") List<net.minecraft.client.render.model.BakedQuad> volumes =
                    (List<net.minecraft.client.render.model.BakedQuad>) volumeField.get(model);
            require(volumes != null && volumes.size() == 6,"Resolved custom JSON did not supply all six actual element-volume faces");
            double low = 1, high = 0;
            for (var quad : volumes) {
                require(quad.getSprite() == firstSprite,"Profile cap ignored the actual resolved texture getter");
                for (int vertex = 0; vertex < 4; vertex++) {
                    double x = Float.intBitsToFloat(quad.getVertexData()[vertex * 8]); low = Math.min(low,x); high = Math.max(high,x);
                }
            }
            require(Math.abs(low-.125) < .000001 && Math.abs(high-.875) < .000001,
                    "Profile cap used a native classpath model instead of the actual JSON override");
            require(modify(first,wrapped,new ModelIdentifier(block,"facing=west,shape=half_edge_high"),baker,ignored -> firstSprite) == model
                            && reads[0] == 1,"Profile geometry was rebaked for another saved pose");
            BakedModel reloaded = modify(load(loader),wrapped,new ModelIdentifier(block,"facing=north,shape=half_edge_low"),baker,ignored -> secondSprite);
            @SuppressWarnings("unchecked") List<net.minecraft.client.render.model.BakedQuad> fresh =
                    (List<net.minecraft.client.render.model.BakedQuad>) volumeField.get(reloaded);
            require(fresh != volumes && fresh.stream().allMatch(q -> q.getSprite() == secondSprite) && reads[0] == 2,
                    "Resource reload retained previous profile-volume/material metadata");
            var custom = (net.minecraft.client.render.model.UnbakedModel) Proxy.newProxyInstance(
                    net.minecraft.client.render.model.UnbakedModel.class.getClassLoader(),new Class<?>[]{net.minecraft.client.render.model.UnbakedModel.class},
                    (proxy,method,args) -> { throw new AssertionError("Custom loader volume must remain opaque"); });
            var customBaker = (net.minecraft.client.render.model.Baker) Proxy.newProxyInstance(
                    net.minecraft.client.render.model.Baker.class.getClassLoader(),new Class<?>[]{net.minecraft.client.render.model.Baker.class},
                    (proxy,method,args) -> { if(method.getName().equals("getOrLoadModel"))return custom;throw new AssertionError(method.getName()); });
            require(volumeField.get(modify(load(loader),wrapped,new ModelIdentifier(block,"facing=north,shape=half_edge_low"),
                            customBaker,ignored -> firstSprite)) == null,"A custom non-JSON model acquired invented native volume metadata");
        }
    }

    private static final class ProfileSprite extends net.minecraft.client.texture.Sprite {
        ProfileSprite(net.minecraft.client.texture.SpriteContents contents) { super(new Identifier("erydon","profile_atlas"),contents,64,64,16,32); }
        @Override public float getAnimationFrameDelta() { return 0; }
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
        return modify(load, model, id, null, null);
    }

    private static BakedModel modify(CapturedLoad load, BakedModel model, Identifier id,
                                     net.minecraft.client.render.model.Baker baker,
                                     java.util.function.Function<net.minecraft.client.util.SpriteIdentifier, net.minecraft.client.texture.Sprite> textures) {
        ModelModifier.AfterBake.Context context = (ModelModifier.AfterBake.Context) Proxy.newProxyInstance(
                ModelModifier.AfterBake.Context.class.getClassLoader(), new Class<?>[]{ModelModifier.AfterBake.Context.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id")) return id;
                    if (method.getName().equals("baker")) return baker;
                    if (method.getName().equals("textureGetter")) return textures;
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
