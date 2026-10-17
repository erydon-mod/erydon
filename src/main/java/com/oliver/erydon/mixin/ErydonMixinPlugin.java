package com.oliver.erydon.mixin;

import com.oliver.erydon.client.pom.InlaySubstrateVertexFormat;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ErydonMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".compat.axiom.")) {
            return FabricLoader.getInstance().isModLoaded("axiom");
        }
        if (mixinClassName.contains(".compat.iris.")) {
            if (!FabricLoader.getInstance().isModLoaded("iris")) return false;
            if (mixinClassName.equals("com.oliver.erydon.mixin.client.compat.iris.InlaySubstrateVertexMixin")
                    || mixinClassName.equals("com.oliver.erydon.mixin.client.compat.iris.CornicePartBlockContextAccessor")) {
                return IrisTerrainHooks.SUPPORTED;
            }
            return true;
        }
        if (mixinClassName.contains(".compat.continuity.")) {
            return FabricLoader.getInstance().isModLoaded("continuity");
        }
        if (mixinClassName.contains(".compat.rei.")) {
            return FabricLoader.getInstance().isModLoaded("roughlyenoughitems");
        }
        if (mixinClassName.contains(".compat.worldedit.")) {
            return FabricLoader.getInstance().isModLoaded("worldedit");
        }
        if (mixinClassName.endsWith(".client.indium.TerrainRenderContextMixin")) {
            return FabricLoader.getInstance().isModLoaded("indium")
                    && FabricLoader.getInstance().isModLoaded("sodium");
        }
        return true;
    }

    private static final class IrisTerrainHooks {
        private static final boolean SUPPORTED = InlaySubstrateVertexFormat.supported();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
