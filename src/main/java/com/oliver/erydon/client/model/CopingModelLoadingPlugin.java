package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.CopingBlock;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CopingModelLoadingPlugin implements PreparableModelLoadingPlugin<Map<CopingBlock.Surface,CopingGeometry>> {
    public static CompletableFuture<Map<CopingBlock.Surface,CopingGeometry>> load(ResourceManager resources, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            Map<CopingBlock.Surface,CopingGeometry> models = new EnumMap<>(CopingBlock.Surface.class);
            for (CopingBlock.Surface surface : CopingBlock.Surface.values()) {
                if(surface.aligned()) continue;
                Identifier id = new Identifier(Erydon.MOD_ID, "authoring_models/block/coping/georgian/coping_georgian_"+surface.asString()+".json");
                try (var reader=resources.getResource(id).orElseThrow(() -> new IOException("Missing coping model: "+id)).getReader()) {
                    models.put(surface,CopingGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject(),id,surface));
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load editable coping model "+id,exception);
                }
            }
            CopingGeometry flat=models.get(CopingBlock.Surface.FLAT);
            for(CopingBlock.Surface surface:CopingBlock.Surface.values()) if(surface.aligned())
                models.put(surface,flat.fitted(surface.fit));
            return Map.copyOf(models);
        },executor);
    }
    @Override public void onInitializeModelLoader(Map<CopingBlock.Surface,CopingGeometry> models, ModelLoadingPlugin.Context context) {
        Map<String,CopingBakedModel> wrappers=new java.util.HashMap<>();
        context.modifyModelAfterBake().register((model, modification) -> {
            if (!(modification.id() instanceof ModelIdentifier id) || !Erydon.MOD_ID.equals(id.getNamespace())
                    || !id.getPath().endsWith("_coping_georgian")) return model;
            if (model == null) return null;
            String key=id.getPath()+("inventory".equals(id.getVariant()) ? "/item" : "/placed");
            return wrappers.computeIfAbsent(key,ignored -> new CopingBakedModel(model,models));
        });
    }
}
