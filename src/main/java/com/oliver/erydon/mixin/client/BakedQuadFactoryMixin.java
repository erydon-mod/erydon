package com.oliver.erydon.mixin.client;

import com.oliver.erydon.client.model.HorizontalUvLock;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelRotation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BakedQuadFactory.class)
public abstract class BakedQuadFactoryMixin {
    @Inject(
            method = "bake(Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lnet/minecraft/client/render/model/json/ModelElementFace;Lnet/minecraft/client/texture/Sprite;Lnet/minecraft/util/math/Direction;Lnet/minecraft/client/render/model/ModelBakeSettings;Lnet/minecraft/client/render/model/json/ModelRotation;ZLnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/model/BakedQuad;",
            at = @At("RETURN"))
    private void erydon$projectFlatHorizontalUvs(Vector3f from,
                                                  Vector3f to,
                                                  ModelElementFace face,
                                                  Sprite sprite,
                                                  Direction direction,
                                                  ModelBakeSettings settings,
                                                  ModelRotation rotation,
                                                  boolean shade,
                                                  Identifier modelId,
                                                  CallbackInfoReturnable<BakedQuad> cir) {
        if (HorizontalUvLock.shouldProjectAtBake(modelId)) {
            HorizontalUvLock.projectFlatHorizontal(cir.getReturnValue());
        }
    }
}
