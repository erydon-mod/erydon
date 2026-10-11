package com.oliver.erydon.recorder.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Optional diagnostic hooks are pinned to the locally inspected renderer versions. */
public final class GpuStageMixinPlugin implements IMixinConfigPlugin {
    public static boolean supportedVersions() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.getModContainer("iris").map(mod -> mod.getMetadata().getVersion().getFriendlyString()
                .equals("1.7.6+mc1.20.1")).orElse(false)
                && loader.getModContainer("sodium").map(mod -> mod.getMetadata().getVersion().getFriendlyString()
                .equals("0.5.13+mc1.20.1")).orElse(false);
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return !mixin.substring(mixin.lastIndexOf('.') + 1).startsWith("Gpu") || supportedVersions();
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
