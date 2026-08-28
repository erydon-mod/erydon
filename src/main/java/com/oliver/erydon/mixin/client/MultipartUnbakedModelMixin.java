package com.oliver.erydon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.oliver.erydon.Erydon;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.MultipartUnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Mixin(MultipartUnbakedModel.class)
public abstract class MultipartUnbakedModelMixin {
    @Unique
    private static final AtomicBoolean ERYDON$REUSE_LOGGED = new AtomicBoolean();

    @Unique
    private final Map<ModelBakeSettings, BakedModel> erydon$cachedBakes = new IdentityHashMap<>();

    @WrapMethod(method = "bake")
    private BakedModel erydon$reuseMultipartBake(Baker baker,
                                                  Function<SpriteIdentifier, Sprite> textureGetter,
                                                  ModelBakeSettings settings,
                                                  Identifier modelId,
                                                  Operation<BakedModel> original) {
        if (!(modelId instanceof ModelIdentifier) || !Erydon.MOD_ID.equals(modelId.getNamespace())) {
            return original.call(baker, textureGetter, settings, modelId);
        }

        synchronized (erydon$cachedBakes) {
            if (erydon$cachedBakes.containsKey(settings)) {
                if (ERYDON$REUSE_LOGGED.compareAndSet(false, true)) {
                    Erydon.LOGGER.info("[{}] Reload-scoped multipart blockstate bake reuse is active.", Erydon.MOD_ID);
                }
                return erydon$cachedBakes.get(settings);
            }

            BakedModel baked = original.call(baker, textureGetter, settings, modelId);
            erydon$cachedBakes.put(settings, baked);
            return baked;
        }
    }
}
