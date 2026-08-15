package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class CoverModelLoadingPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        pluginContext.modifyModelAfterBake().register((model, context) -> {
            Identifier id = context.id();
            if (id == null) return model;

            // Blockstate models: erydon:cover_*#facing=...,size=...,finish=...
            boolean isCoverBlockState =
                    (id instanceof ModelIdentifier mid)
                            && "erydon".equals(mid.getNamespace())
                            && ( "cover_white".equals(mid.getPath())
                            || "cover_black".equals(mid.getPath())
                            || "cover_bronze".equals(mid.getPath())
                            || "cover_silver".equals(mid.getPath()) );

            // Raw json models: erydon:block/cover/cover/cover_*_...
            boolean isCoverJson =
                    "erydon".equals(id.getNamespace())
                            && (id.getPath().startsWith("block/cover/cover/cover_white")
                            || id.getPath().startsWith("block/cover/cover/cover_black")
                            || id.getPath().startsWith("block/cover/cover/cover_bronze")
                            || id.getPath().startsWith("block/cover/cover/cover_silver"));

            if (isCoverBlockState || isCoverJson) {
                return new CoverBakedModel(model);
            }
            return model;
        });
    }
}
