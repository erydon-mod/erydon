package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.GlazingSlopeGeometry;
import com.oliver.erydon.block.GlazingSlopeGeometry.Profile;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Restricted to the twelve pitched framed glazing blocks; other slope families keep their own loaders. */
public final class GlazingSlopeModelLoadingPlugin implements PreparableModelLoadingPlugin<Map<Profile, GlazingSlopeGeometry.Template>> {
    private record Material(Profile profile, String finish) { }
    private static final Map<String, Material> MATERIALS = materials();

    private static Map<String, Material> materials() {
        Map<String, Material> materials = new HashMap<>();
        for (String finish : new String[]{"tinted", "silver", "crystal", "bronze"}) {
            String prefix = "glazing_framed_" + finish;
            materials.put(prefix + "_slope", new Material(Profile.STANDARD, finish));
            materials.put(prefix + "_shallow_slope_lower", new Material(Profile.SHALLOW_LOWER, finish));
            materials.put(prefix + "_shallow_slope_upper", new Material(Profile.SHALLOW_UPPER, finish));
        }
        return Map.copyOf(materials);
    }

    public static CompletableFuture<Map<Profile, GlazingSlopeGeometry.Template>> load(ResourceManager resources, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            Map<Profile, GlazingSlopeGeometry.Template> templates = new EnumMap<>(Profile.class);
            for (Profile profile : Profile.values()) {
                String path = switch (profile) {
                    case STANDARD -> "slope/glazing_framed_slope";
                    case SHALLOW_LOWER -> "shallow_slope/glazing_slope_shallow_lower_straight";
                    case SHALLOW_UPPER -> "shallow_slope/glazing_slope_shallow_upper_straight";
                };
                Identifier id = new Identifier(Erydon.MOD_ID, "models/block/glazing/" + path + ".json");
                try (var reader = resources.getResource(id).orElseThrow(() -> new IOException("Missing glazing parent: " + id)).getReader()) {
                    templates.put(profile, GlazingSlopeGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject()));
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load glazing parent " + id, exception);
                }
            }
            return Map.copyOf(templates);
        }, executor);
    }

    @Override public void onInitializeModelLoader(Map<Profile, GlazingSlopeGeometry.Template> templates, ModelLoadingPlugin.Context context) {
        Map<String, GlazingSlopeBakedModel> wrappers = new HashMap<>();
        context.modifyModelAfterBake().register((model, modification) -> {
            if (model == null || !(modification.id() instanceof ModelIdentifier id)
                    || !Erydon.MOD_ID.equals(id.getNamespace())) return model;
            Material material = MATERIALS.get(id.getPath());
            if (material == null) return model;
            String key = id.getPath() + ("inventory".equals(id.getVariant()) ? "/item" : "/placed");
            return wrappers.computeIfAbsent(key, ignored -> new GlazingSlopeBakedModel(model, material.profile, templates.get(material.profile),
                    modification.textureGetter().apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                            new Identifier(Erydon.MOD_ID, "block/glazing_" + material.finish))),
                    modification.textureGetter().apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                            new Identifier(Erydon.MOD_ID, "block/lead_black")))));
        });
    }
}
