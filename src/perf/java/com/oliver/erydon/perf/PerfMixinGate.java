package com.oliver.erydon.perf;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Disabled capture has no injected frame callback or clock reads. */
public final class PerfMixinGate implements IMixinConfigPlugin {
    static boolean enabled(boolean development, String flag) { return development && "true".equals(flag); }
    public boolean shouldApplyMixin(String target, String mixin) {
        return enabled(FabricLoader.getInstance().isDevelopmentEnvironment(), System.getProperty("erydon.perf.frame_capture"));
    }
    public void onLoad(String name) { }
    public String getRefMapperConfig() { return null; }
    public void acceptTargets(Set<String> mine, Set<String> others) { }
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
