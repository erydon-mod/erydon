package com.oliver.erydon.mixin.client;

import net.minecraft.client.texture.atlas.SingleAtlasSource;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SingleAtlasSource.class)
public abstract class ContinuityReservedSpriteWarningMixin {
    private static final String MISSING_SPRITE_MESSAGE = "Missing sprite: {}";
    private static final String CONTINUITY_RESERVED_PATH = "textures/continuity_reserved/";

    @Redirect(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false
            )
    )
    private void erydon$suppressContinuityReservedSpriteWarning(Logger logger, String message, Object spriteId) {
        if (MISSING_SPRITE_MESSAGE.equals(message)
                && spriteId instanceof Identifier id
                && "minecraft".equals(id.getNamespace())
                && id.getPath().startsWith(CONTINUITY_RESERVED_PATH)
                && id.getPath().endsWith(".png")) {
            return;
        }

        logger.warn(message, spriteId);
    }
}
