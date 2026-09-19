package com.oliver.erydon.mixin.client;

import com.oliver.erydon.client.ErydonHighPolish;
import com.oliver.erydon.client.HighPolishSpecularPack;
import net.minecraft.resource.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.List;

@Mixin(LifecycledResourceManagerImpl.class)
abstract class HighPolishResourceManagerMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private List<ResourcePack> erydon$polishedSpecs(List<ResourcePack> packs, ResourceType type, List<ResourcePack> original) {
        return type == ResourceType.CLIENT_RESOURCES
                ? HighPolishSpecularPack.append(type, packs, ErydonHighPolish.activeSettings()) : packs;
    }
}
