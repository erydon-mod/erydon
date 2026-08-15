package com.oliver.erydon.mixin.client.texturealias;

import com.oliver.erydon.client.texturealias.CollectionResourcePackCompatibility;
import com.oliver.erydon.client.texturealias.FamilyTextureAliasCoordinator;
import com.oliver.erydon.client.texturealias.TextureAliasResourcePack;
import net.fabricmc.fabric.impl.resource.loader.GroupResourcePack;
import net.fabricmc.fabric.impl.resource.loader.ResourcePackSourceTracker;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackCompatibility;
import net.minecraft.resource.ResourcePackProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ResourcePackProfile.class, priority = 900)
abstract class ResourcePackProfileMixin {
    @Unique
    private Boolean erydon$legacyCollectionPack;

    @Inject(method = "getCompatibility", at = @At("HEAD"), cancellable = true)
    private void erydon$markLegacyCollectionPackTooOld(
            CallbackInfoReturnable<ResourcePackCompatibility> info
    ) {
        if (!FamilyTextureAliasCoordinator.isLeader()) {
            return;
        }

        ResourcePackProfile profile = (ResourcePackProfile) (Object) this;
        if (erydon$legacyCollectionPack == null) {
            erydon$legacyCollectionPack =
                    CollectionResourcePackCompatibility.shouldMarkTooOld(
                            profile.getName(),
                            profile.getDescription().getString()
                    );
        }
        if (erydon$legacyCollectionPack) {
            info.setReturnValue(ResourcePackCompatibility.TOO_OLD);
        }
    }

    @Inject(method = "createResourcePack", at = @At("RETURN"), cancellable = true)
    private void erydon$wrapExactTextureAliasPack(CallbackInfoReturnable<ResourcePack> info) {
        if (!FamilyTextureAliasCoordinator.isLeader()) {
            return;
        }

        ResourcePack original = info.getReturnValue();
        ResourcePackProfile profile = (ResourcePackProfile) (Object) this;
        ResourcePack pack = CollectionResourcePackCompatibility.disableIfLegacy(
                profile.getName(),
                profile.getDescription().getString(),
                original
        );
        if (pack != original) {
            ResourcePackSourceTracker.setSource(
                    pack,
                    ResourcePackSourceTracker.getSource(original)
            );
        }
        if (pack instanceof GroupResourcePack) {
            info.setReturnValue(pack);
            return;
        }

        ResourcePack wrapped = TextureAliasResourcePack.wrapFamilyNamespaces(pack);
        if (wrapped == pack) {
            if (pack != original) {
                info.setReturnValue(pack);
            }
            return;
        }

        ResourcePackSourceTracker.setSource(
                wrapped,
                ResourcePackSourceTracker.getSource(pack)
        );
        info.setReturnValue(wrapped);
    }
}
