package com.oliver.erydon.mixin.client;

import com.oliver.erydon.client.pom.ErydonCuPomLookupTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureMixin {
    @Inject(method = "upload", at = @At("HEAD"))
    private void erydon$registerInvalidCuPomLookupBeforeAtlasUpload(
            SpriteLoader.StitchResult stitchResult,
            CallbackInfo ci
    ) {
        SpriteAtlasTexture atlas = (SpriteAtlasTexture) (Object) this;
        if (SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(atlas.getId())) {
            ErydonCuPomLookupTexture.registerPlaceholder();
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void erydon$rebuildCuPomLookupAfterAtlasUpload(SpriteLoader.StitchResult stitchResult, CallbackInfo ci) {
        ErydonCuPomLookupTexture.rebuildAfterBlockAtlasUpload((SpriteAtlasTexture) (Object) this);
    }
}
